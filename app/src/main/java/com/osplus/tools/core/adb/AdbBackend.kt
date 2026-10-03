package com.osplus.tools.core.adb

import android.content.Context
import com.osplus.tools.core.CommandResult
import com.osplus.tools.core.PrivilegeBackend
import com.osplus.tools.core.PrivilegeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * ADB 无线调试后端：以 shell（uid 2000）身份执行命令。
 *
 * ## 为什么这条通道值得做
 *
 * Root 要刷机，Shizuku 要额外装一个 App。而「开发者选项 → 无线调试」是
 * **每台 Android 11+ 自带的**，用户只需要点一下配对码——门槛最低，
 * 覆盖面最广，而且不需要装任何东西。
 *
 * ## 为什么必须自己实现协议
 *
 * adbd 是原生守护进程，不是 Binder 服务，**没有任何系统 API**。唯一通路是
 * 它监听的 TCP 端口，用 ADB 自己的私有协议。因此这里从包格式到 TLS 升级
 * 全部手写，不依赖 `ddmlib`（那是给 PC 用的，Android 上跑不起来）。
 *
 * ## 完整握手流程
 *
 * ```
 *   TCP 连连接端口
 *     ↓
 *   发明文 CNXN（带 host::features=... 的 payload）
 *     ↓
 *   设备回 STLS  ← 无线调试要求升级加密，旧式 root adbd 会直接回 AUTH/CNXN
 *     ↓
 *   回发 STLS，在同一 socket 上就地升级 TLS（客户端证书 = 内置 adbkey）
 *     ↓
 *   设备主动发 CNXN（授权在证书验证阶段就完成了，不能再回 CNXN，否则死锁）
 *     ↓
 *   之后 OPEN("shell:cmd") → WRTE/OKAY 收输出 → CLSE
 * ```
 *
 * ## 与 Shizuku 后端的关系
 *
 * 两者产出的都是「一段命令的 stdout」，所以共用
 * [com.osplus.tools.core.ShellBackend] 的调用约定：本类只负责把命令送出去、
 * 把输出读回来，重试与回退交给上层。
 *
 * 两者能力**几乎等价**（都是 uid 2000）——都读不到 `/data/adb`，
 * 因此性能调度页在 ADB 模式下同样不可用。见
 * [com.osplus.tools.core.PrivilegeCapabilities.canControlPerfSched]。
 */
/** ADB 包头固定 24 字节：command/arg0/arg1/data_length/data_check/magic */
private const val HEADER_LEN = 24

private const val MAX_PAYLOAD = 1024 * 1024

private const val A_VERSION = 0x01000001

private const val MAX_DATA = 256 * 1024

/**
 * CNXN 的 banner。features 列表不需要完全准确——adbd 会取交集，
 * 少了某个 feature 只是那条路径走不到，不影响 shell。
 */
private const val CNXN_BANNER =
    "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb"

class AdbBackend(private val appContext: Context) : PrivilegeBackend {

    companion object {
        private const val TAG = "AdbBackend"

        /** 无线调试的**连接**端口对应的 mDNS 服务名 */
        const val CONNECT_SERVICE = "_adb-tls-connect._tcp"

        /** 无线调试的**配对**端口对应的 mDNS 服务名 */
        const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"

        /** 是否已配过（有内置密钥） */
        fun isPaired(context: Context): Boolean = AdbKeyStore(context).exists()
    }

    private val keyStore = AdbKeyStore(appContext)

    /** 会话缓存：握手一次后复用。每条命令都重新 CNXN + TLS 会有几百毫秒开销。 */
    @Volatile
    private var session: AdbSession? = null

    /** 用户手动指定的端点（自动发现失败时由 UI 传入） */
    @Volatile
    private var manualEndpoint: Pair<String, Int>? = null

    // ------------------------------------------------------------ 能力查询

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val s = ensureSession() ?: return@withContext false
            val uid = s.exec("id -u", 4_000L).stdout.trim()
            uid == "2000" || uid == "0"
        }.getOrDefault(false)
    }

    override suspend fun run(command: String, timeoutMs: Long): CommandResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = ensureSession()
                    ?: return@withContext CommandResult(false, "", "未连接到无线调试端口")
                s.exec(command, timeoutMs)
            }.getOrElse { e ->
                // 会话可能已失效（无线调试被关 / 切了网络 / 息屏后掉线），清掉下次重建
                PrivilegeLog.w(TAG, "命令执行失败，丢弃会话", e)
                closeSession()
                CommandResult(false, "", e.message ?: e.toString())
            }
        }

    // ------------------------------------------------------------ 连接管理

    /** 设置手动端点（用户在设置页填了 IP:端口 时用） */
    fun setManualEndpoint(host: String, port: Int) {
        manualEndpoint = host to port
        closeSession()
    }

    /** 主动连接指定端点；成功返回 true。用于设置页的「测试连接」。 */
    suspend fun connect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            manualEndpoint = host to port
            closeSession()
            val s = AdbSession(
                host = host,
                port = port,
                key = keyStore.privateKey(),
                certificate = AdbCertificate.load(appContext),
            )
            s.handshake()
            session = s
            true
        }.getOrElse { e ->
            PrivilegeLog.w(TAG, "连接 $host:$port 失败", e)
            false
        }
    }

    fun closeSession() {
        runCatching { session?.close() }
        session = null
    }

    /**
     * 拿到一个可用会话：优先复用，其次用缓存端点，最后 mDNS 发现。
     *
     * 找不到时返回 null 而不是抛异常——调用方（[isAvailable]）把它当作
     * 「当前不可用」，这是预期路径而不是错误。
     */
    private suspend fun ensureSession(): AdbSession? {
        session?.let { if (it.isAlive()) return it }
        closeSession()

        if (!keyStore.exists()) {
            PrivilegeLog.d(TAG, "没有客户端密钥，需要先配对")
            return null
        }

        val endpoint = manualEndpoint
            ?: AdbDiscovery.discover(appContext, CONNECT_SERVICE)
            ?: run {
                PrivilegeLog.d(TAG, "mDNS 未发现连接端口")
                return null
            }

        return runCatching {
            AdbSession(
                host = endpoint.first,
                port = endpoint.second,
                key = keyStore.privateKey(),
                certificate = AdbCertificate.load(appContext),
            ).also { it.handshake() }.also { session = it }
        }.getOrElse { e ->
            PrivilegeLog.w(TAG, "建立会话失败 ${endpoint.first}:${endpoint.second}", e)
            null
        }
    }
}

// =================================================================== 会话

/**
 * 一条 ADB 会话，持有 socket 与流。
 *
 * [exec] 用 [lock] 串行化：ADB 是单流协议，两条命令并发会互相吃掉对方的
 * OKAY/WRTE，表现为输出错乱或永久挂起。
 */
internal class AdbSession(
    private val host: String,
    private val port: Int,
    private val key: PrivateKey,
    private val certificate: AdbCertificate,
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private val lock = Any()
    private var nextLocalId = 1

    fun isAlive(): Boolean = socket?.let { !it.isClosed && it.isConnected } ?: false

    // ---------------------------------------------------------- 握手

    fun handshake() {
        val raw = Socket().apply {
            connect(InetSocketAddress(host, port), 4_000)
            soTimeout = 30_000
        }
        var cur: Socket = raw
        var ins = DataInputStream(raw.getInputStream())
        var outs: OutputStream = raw.getOutputStream()

        // ---- 明文 CNXN 触发 STLS ----
        writePacket(outs, CMD_CNXN, A_VERSION, MAX_DATA, CNXN_BANNER.toByteArray(StandardCharsets.UTF_8))
        var pkt = readPacket(ins)

        when (pkt.command) {
            CMD_STLS -> {
                // 设备的 STLS 里带的是它支持的 STLS 版本，原样回发
                writePacket(outs, CMD_STLS, pkt.arg0, 0, null)
                val tls = upgradeToTls(raw)
                cur = tls
                ins = DataInputStream(tls.getInputStream())
                outs = tls.getOutputStream()
                // TLS 下的授权在证书校验阶段完成，设备会主动发 CNXN。
                // 这里**不能**再回发 CNXN，否则设备会再回一次 STLS 死锁。
                pkt = awaitCnxnAfterTls(ins, outs)
            }

            CMD_AUTH -> {
                if (!authLoop(ins, outs, pkt)) {
                    throw IllegalStateException("设备拒绝了本机凭据，请在无线调试中重新配对")
                }
                pkt = readPacket(ins)
                if (pkt.command != CMD_CNXN) {
                    throw IllegalStateException("授权后未收到 CNXN，而是 ${pkt.commandName}")
                }
            }

            CMD_CNXN -> Unit // 直连成功

            else -> throw IllegalStateException("协议应答异常：${pkt.commandName}")
        }

        socket = cur
        input = ins
        output = outs
        PrivilegeLog.i("AdbSession", "握手完成 $host:$port")
    }

    private fun awaitCnxnAfterTls(ins: DataInputStream, outs: OutputStream): AdbPacket {
        var guard = 0
        while (guard++ < 10) {
            val p = readPacket(ins)
            when (p.command) {
                CMD_CNXN -> return p
                CMD_AUTH -> {
                    if (authLoop(ins, outs, p)) {
                        return readPacket(ins)
                    }
                    throw IllegalStateException("TLS 后的 AUTH 认证失败")
                }
                else -> Unit // 忽略其它包，继续等
            }
        }
        throw IllegalStateException("等待 CNXN 超时")
    }

    /**
     * 同一 socket 上升级 TLS。客户端证书用内置 adbkey，
     * 信任任意服务端证书（设备是自签的，且真正的授权靠证书指纹匹配）。
     */
    private fun upgradeToTls(raw: Socket): SSLSocket {
        val cert = certificate.certificate
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(
            arrayOf<KeyManager>(object : X509KeyManager {
                private val alias = "adbkey"
                private val chain = arrayOf(cert)
                override fun chooseClientAlias(
                    kt: Array<out String>?, i: Array<out java.security.Principal>?, s: Socket?,
                ) = alias
                override fun chooseServerAlias(
                    kt: String?, i: Array<out java.security.Principal>?, s: Socket?,
                ): String? = null
                override fun getCertificateChain(a: String?) = if (alias == a) chain else null
                override fun getPrivateKey(a: String?) = if (alias == a) key else null
                override fun getClientAliases(kt: String?, i: Array<out java.security.Principal>?) =
                    arrayOf(alias)
                override fun getServerAliases(kt: String?, i: Array<out java.security.Principal>?) =
                    null
            }),
            arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
                override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }),
            SecureRandom(),
        )
        val tls = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        tls.useClientMode = true
        tls.soTimeout = 30_000
        tls.startHandshake()
        return tls
    }

    /**
     * 明文 AUTH 循环，服务于旧式 adbd / root adbd。
     *
     * 签名必须用 `NONEwithRSA` 手动拼 SHA-1 DigestInfo 前缀：
     * adbd 发来的 token **本身就是 20 字节的 SHA-1 摘要**，用
     * `SHA1withRSA` 会再哈希一次，签名验不过。
     */
    private fun authLoop(ins: DataInputStream, outs: OutputStream, first: AdbPacket): Boolean {
        var pkt = first
        var tries = 0
        while (tries++ < 4) {
            when {
                pkt.command == CMD_CNXN -> return true
                pkt.command == CMD_AUTH && pkt.arg0 == AUTH_TOKEN && pkt.payload.isNotEmpty() -> {
                    val sig = Signature.getInstance("NONEwithRSA")
                    sig.initSign(key)
                    sig.update(SHA1_DIGESTINFO_PREFIX + pkt.payload)
                    writePacket(outs, CMD_AUTH, AUTH_SIGNATURE, 0, sig.sign())
                }
                pkt.command == CMD_AUTH && pkt.arg0 == AUTH_OK -> return true
                pkt.command == CMD_AUTH && pkt.arg0 == AUTH_RSAPUBLICKEY ->
                    throw IllegalStateException("设备未授权本机公钥，需要先完成配对")
            }
            pkt = readPacket(ins)
        }
        return false
    }

    // ---------------------------------------------------------- 执行命令

    /**
     * 执行 shell 命令，返回合并后的 stdout。
     *
     * 用 `shell:` 而不是 `shell,v2:` 是因为 v2 的流带协议头（退出码、stderr
     * 分流），解析复杂且收益有限——上游只需要 stdout 文本。
     */
    fun exec(command: String, timeoutMs: Long): CommandResult {
        synchronized(lock) {
            val ins = input ?: return CommandResult(false, "", "会话未建立")
            val outs = output ?: return CommandResult(false, "", "会话未建立")
            val localId = nextLocalId++

            writePacket(
                outs, CMD_OPEN, localId, 0,
                ("shell:$command").toByteArray(StandardCharsets.UTF_8),
            )

            val sb = StringBuilder()
            var remoteId = 0
            val deadline = System.currentTimeMillis() + timeoutMs
            var sawClose = false

            while (!sawClose) {
                if (System.currentTimeMillis() > deadline) break
                val p = readPacket(ins)
                when (p.command) {
                    CMD_OKAY -> {
                        // OKAY(arg0=远端 id, arg1=本地 id)；之后数据只认这个远端 id
                        if (remoteId == 0) remoteId = p.arg0
                    }
                    CMD_WRTE -> {
                        if (p.payload.isNotEmpty()) {
                            sb.append(String(p.payload, StandardCharsets.UTF_8))
                        }
                        // 每条 WRTE 必须回 OKAY，否则对端不会继续发
                        writePacket(outs, CMD_OKAY, p.arg1, p.arg0, null)
                    }
                    CMD_CLSE -> {
                        sawClose = true
                        writePacket(outs, CMD_OKAY, p.arg1, p.arg0, null)
                    }
                    CMD_CNXN -> Unit // 忽略心跳/重连类的 CNXN
                    else -> Unit
                }
            }

            return CommandResult(
                success = true,
                stdout = sb.toString(),
                stderr = "",
                exitCode = 0,
            )
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    // ---------------------------------------------------------- 包读写

    private fun readPacket(ins: DataInputStream): AdbPacket {
        val hdr = ByteArray(HEADER_LEN)
        var off = 0
        while (off < HEADER_LEN) {
            val r = ins.read(hdr, off, HEADER_LEN - off)
            if (r < 0) throw EOFException("连接已关闭")
            off += r
        }
        val cmd = String(hdr, 0, 4, StandardCharsets.US_ASCII)
        val arg0 = leInt(hdr, 4)
        val arg1 = leInt(hdr, 8)
        val len = leInt(hdr, 12)
        val payload = if (len > 0) {
            if (len > MAX_PAYLOAD) throw IllegalStateException("负载过大：$len")
            val buf = ByteArray(len)
            var o = 0
            while (o < len) {
                val r = ins.read(buf, o, len - o)
                if (r < 0) throw EOFException("负载读取中断")
                o += r
            }
            buf
        } else {
            ByteArray(0)
        }
        return AdbPacket(cmd, arg0, arg1, payload)
    }

    private fun writePacket(outs: OutputStream, cmd: String, arg0: Int, arg1: Int, payload: ByteArray?) {
        val data = payload ?: ByteArray(0)
        val cbytes = cmd.toByteArray(StandardCharsets.US_ASCII)
        val cmdInt = (cbytes[0].toInt() and 0xff) or
            ((cbytes[1].toInt() and 0xff) shl 8) or
            ((cbytes[2].toInt() and 0xff) shl 16) or
            ((cbytes[3].toInt() and 0xff) shl 24)

        val crc = if (data.isNotEmpty()) {
            CRC32().apply { update(data) }.value.toInt()
        } else {
            0
        }

        val hdr = ByteArray(HEADER_LEN)
        System.arraycopy(cbytes, 0, hdr, 0, 4)
        putLeInt(hdr, 4, arg0)
        putLeInt(hdr, 8, arg1)
        putLeInt(hdr, 12, data.size)
        putLeInt(hdr, 16, crc)
        putLeInt(hdr, 20, cmdInt xor 0xffffffff.toInt())
        outs.write(hdr)
        if (data.isNotEmpty()) outs.write(data)
        outs.flush()
    }
}

// =================================================================== 基础类型

/** 一个 ADB 包 */
internal data class AdbPacket(
    val command: String,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbPacket) return false
        return command == other.command && arg0 == other.arg0 && arg1 == other.arg1 &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = command.hashCode()
        r = 31 * r + arg0
        r = 31 * r + arg1
        r = 31 * r + payload.contentHashCode()
        return r
    }

    val commandName: String get() = command
}

private fun leInt(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xff) or
        ((b[off + 1].toInt() and 0xff) shl 8) or
        ((b[off + 2].toInt() and 0xff) shl 16) or
        ((b[off + 3].toInt() and 0xff) shl 24)

private fun putLeInt(b: ByteArray, off: Int, v: Int) {
    b[off] = (v and 0xff).toByte()
    b[off + 1] = ((v ushr 8) and 0xff).toByte()
    b[off + 2] = ((v ushr 16) and 0xff).toByte()
    b[off + 3] = ((v ushr 24) and 0xff).toByte()
}

// 命令字（小端 u32 的 ASCII 常量）
internal const val CMD_CNXN = "CNXN"
internal const val CMD_OPEN = "OPEN"
internal const val CMD_OKAY = "OKAY"
internal const val CMD_WRTE = "WRTE"
internal const val CMD_CLSE = "CLSE"
internal const val CMD_STLS = "STLS"
internal const val CMD_AUTH = "AUTH"

internal const val AUTH_TOKEN = 1
internal const val AUTH_SIGNATURE = 2
internal const val AUTH_RSAPUBLICKEY = 3
internal const val AUTH_OK = 0

/**
 * AUTH 签名的 SHA-1 DigestInfo 前缀。
 *
 * `RSA_sign(NID_sha1, token, 20, sig)` 的语义是「token 已经是摘要，
 * 直接做 PKCS#1 v1.5 原始签名」。JCA 里对应 `NONEwithRSA`（不再哈希），
 * 所以必须手动把这 15 字节前缀拼在 token 前面。
 */
private val SHA1_DIGESTINFO_PREFIX = byteArrayOf(
    0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
    0x05, 0x00, 0x04, 0x14,
)
