package com.osplus.tools.core.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.osplus.tools.core.PrivilegeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * 用 mDNS 找无线调试的端口。
 *
 * ## 为什么需要它
 *
 * 无线调试的**连接端口和配对端口都是每次打开都变的随机端口**，用户看不到
 * （开发者选项页面只显示配对码和「IP 地址和端口」的配对那一行）。
 * 系统广播这两个服务：
 *
 * * `_adb-tls-pairing._tcp` —— 配对页面打开期间才广播
 * * `_adb-tls-connect._tcp` —— 无线调试开启期间一直广播
 *
 * ## 两条路，系统栈优先
 *
 * 1. [NsdManager]（系统 mDNS 栈）：最省事，但部分 ROM 会过滤掉非自家应用的
 *    adb 服务查询，返回空。
 * 2. 原始 socket 查询：自己往 `224.0.0.251:5353` 发 PTR 查询。需要
 *    MulticastLock（否则 WiFi 省电模式会丢多播包），但绕开了 ROM 过滤。
 *
 * 先试 1 再试 2，任一成功即返回。
 */
internal object AdbDiscovery {

    private const val TAG = "AdbDiscovery"

    /** 系统栈发现的超时（毫秒）。太长会让设置页卡住。 */
    private const val NSD_TIMEOUT_MS = 4_000L

    /** 原始 socket 发现的超时 */
    private const val RAW_TIMEOUT_MS = 2_500

    private const val MDNS_ADDR = "224.0.0.251"
    private const val MDNS_PORT = 5353

    /**
     * 发现指定服务的端点。
     *
     * @param service 不带尾部 `.local` 的服务名，如 [_CONNECT]
     * @return `(host, port)`；超时或失败返回 null
     */
    suspend fun discover(context: Context, service: String): Pair<String, Int>? {
        // 服务名要的是 `_adb-tls-connect`，不带 `._tcp`
        val shortName = service.removeSuffix("._tcp")

        withTimeoutOrNull(NSD_TIMEOUT_MS) {
            discoverViaNsd(context, shortName)
        }?.let { return it }

        PrivilegeLog.d(TAG, "系统 mDNS 栈未发现 $shortName，改用原始查询")
        return withContext(Dispatchers.IO) {
            discoverViaRawQuery(context, service)
        }
    }

    /**
     * 走系统 [NsdManager]。用 `suspendCancellableCoroutine` 把它转成挂起函数，
     * 这样超时和取消都由协程管，不需要自己开线程加 latch。
     */
    private suspend fun discoverViaNsd(context: Context, shortName: String): Pair<String, Int>? =
        suspendCancellableCoroutine { cont ->
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsd == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            var finished = false
            // 注意：listener 内部引用自身来 stopServiceDiscovery，
            // 所以要用 lateinit var 而不能是 val（Kotlin 局部变量捕获限制）
            lateinit var listener: NsdManager.DiscoveryListener

            fun finish(result: Pair<String, Int>?) {
                if (finished) return
                finished = true
                runCatching { nsd.stopServiceDiscovery(listener) }
                if (cont.isActive) cont.resume(result)
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) = Unit

                override fun onServiceFound(info: NsdServiceInfo?) {
                    val port = info?.port ?: 0
                    if (port > 0) {
                        PrivilegeLog.d(TAG, "NSD 发现 ${info?.serviceName} port=$port")
                        // 拿到端口就够：host 一律用回环。
                        // mDNS 给的主机名在部分 ROM 上解析不到，而无线调试
                        // 的服务端一定监听在本机（这是「本机自己连自己」的场景）。
                        finish("127.0.0.1" to port)
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo?) = Unit

                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    finish(null)
                }

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit

                override fun onDiscoveryStopped(serviceType: String?) = Unit
            }

            cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }

            runCatching {
                nsd.discoverServices(shortName, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                PrivilegeLog.w(TAG, "启动 NSD 发现失败", it)
                finish(null)
            }
        }

    /**
     * 原始 mDNS PTR 查询。
     *
     * 需要一个 MulticastLock：没有它的时候，屏幕一黑 WiFi 就进省电模式，
     * 多播包会被直接丢掉，表现为「第一次能发现、第二次就不行」。
     *
     * 查询包里同时也请求 SRV 记录（type 33），因为 PTR 只给服务名，
     * 端口在 SRV 里。这里简化处理：直接把响应里出现的任意 A 记录 + 端口
     * 组合拿来用，因为我们只需要端口，host 固定回环。
     */
    private fun discoverViaRawQuery(context: Context, service: String): Pair<String, Int>? {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val lock = runCatching {
            wifi?.createMulticastLock("osplus-adb-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        var sock: DatagramSocket? = null
        return try {
            sock = DatagramSocket()
            sock.soTimeout = RAW_TIMEOUT_MS
            val query = buildPtrQuery(service)
            sock.send(DatagramPacket(query, query.size, InetAddress.getByName(MDNS_ADDR), MDNS_PORT))

            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + RAW_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (e: java.net.SocketTimeoutException) {
                    return null
                }
                val port = parseSrvPort(pkt.data, pkt.length)
                if (port > 0) {
                    PrivilegeLog.d(TAG, "原始 mDNS 查询得到 port=$port")
                    return "127.0.0.1" to port
                }
            }
            null
        } catch (e: Exception) {
            PrivilegeLog.w(TAG, "原始 mDNS 查询失败", e)
            null
        } finally {
            runCatching { sock?.close() }
            runCatching { lock?.takeIf { it.isHeld }?.release() }
        }
    }

    /**
     * 构造一条 DNS-SD 的 PTR 查询。
     *
     * 报文结构（RFC 6762 / 6763）：
     * ```
     *   header 12 字节：id=0, flags=0, qdcount=1, 其余 0
     *   question：<service 名按标签编码> + QTYPE(PTR=12) + QCLASS(IN=1)
     * ```
     * QCLASS 要带 `0x8000`（QU 位，unicast-response），否则部分 ROM 的
     * mDNS 响应走多播而我们已经在收包了，反而容易漏。
     */
    private fun buildPtrQuery(service: String): ByteArray {
        val out = ByteArrayOutputStream()

        // ---- header ----
        // id = 0（mDNS 要求查询用 0）
        out.write(0); out.write(0)
        // flags = 0（标准查询）
        out.write(0); out.write(0)
        // qdcount = 1
        out.write(0); out.write(1)
        // ancount / nscount / arcount = 0
        repeat(6) { out.write(0) }

        // ---- question: _adb-tls-connect._tcp.local ----
        val name = "$service._tcp.local"
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0) // 名字结束

        // QTYPE = PTR(12)
        out.write(0); out.write(12)
        // QCLASS = IN(1) | QU 位
        out.write(0x80); out.write(1)

        return out.toByteArray()
    }

    /**
     * 从 mDNS 响应里抠出 SRV 记录的端口。
     *
     * 这是个**够用就好的解析器**，不是完整 DNS 解析：只顺着 answer 段扫，
     * 遇到 SRV（type 33）就读它的 6 字节 rdata 里的端口。报文字段可能带
     * 压缩指针，但 SRV 的 rdata 里端口是明文的两字节，所以不用解压缩。
     */
    private fun parseSrvPort(data: ByteArray, len: Int): Int {
        if (len < 12) return -1
        val qd = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        val an = ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)

        var pos = 12
        // 跳过 question 段
        repeat(qd) {
            pos = skipName(data, pos, len) ?: return -1
            pos += 4 // qtype + qclass
            if (pos > len) return -1
        }

        repeat(an) {
            pos = skipName(data, pos, len) ?: return -1
            if (pos + 10 > len) return -1
            val type = ((data[pos].toInt() and 0xff) shl 8) or (data[pos + 1].toInt() and 0xff)
            val rdlen = ((data[pos + 8].toInt() and 0xff) shl 8) or (data[pos + 9].toInt() and 0xff)
            val rdata = pos + 10
            if (type == 33 && rdlen >= 6 && rdata + 6 <= len) {
                // SRV rdata: priority(2) weight(2) port(2) target(...)
                val port = ((data[rdata + 4].toInt() and 0xff) shl 8) or
                    (data[rdata + 5].toInt() and 0xff)
                if (port > 0) return port
            }
            pos = rdata + rdlen
        }
        return -1
    }

    /** 跳过 DNS 名字（支持压缩指针）。返回下一个字节的位置。 */
    private fun skipName(data: ByteArray, start: Int, len: Int): Int? {
        var pos = start
        while (pos < len) {
            val b = data[pos].toInt() and 0xff
            when {
                b == 0 -> return pos + 1
                // 压缩指针：高两位为 11，占 2 字节且立即结束
                b and 0xC0 == 0xC0 -> return pos + 2
                else -> pos += 1 + b
            }
        }
        return null
    }

}
