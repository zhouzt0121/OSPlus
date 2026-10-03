package com.osplus.tools.core.adb

import android.util.Base64
import com.osplus.tools.core.PrivilegeLog
import org.conscrypt.Conscrypt
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Android 11+ 官方「无线调试 → 使用配对码配对设备」协议的客户端。
 *
 * ## 完整流程
 *
 * ```
 *   TCP 连配对端口（用户在开发者选项里看到的那一个，随机且每次不同）
 *     ↓
 *   TLS 1.3 握手，客户端证书 = 内置 adbkey（CN=LinuxTransport）
 *     ↓
 *   从 TLS 会话导出 64 字节密钥材料，label = "adb-label" + NUL
 *     ↓
 *   SPAKE2，口令 = 6 位配对码(ASCII) ‖ 上面的 64 字节导出材料
 *     ↓
 *   HKDF-SHA256 → AES-128-GCM 会话密钥，交换 PeerInfo（内含内置公钥）
 *     ↓
 *   设备把内置公钥写进 adb_keys 白名单 → 之后可直接连连接端口开 shell
 * ```
 *
 * ## 设计要点（都是从参考实现里学到的、踩过坑才知道的）
 *
 * 1. **导出密钥材料必须带 NUL**。AOSP `tls_connection.cpp` 里
 *    `kExportedKeyLabel[] = "adb-label"` 是 `sizeof` 含结尾 NUL 的数组字面量，
 *    长度 10。少写这个 `\u0000` 会得到一个「看似成功、实则在 SPAKE2 阶段
 *    静默失配」的结果，且错误信息只会说「配对码错误」。
 *
 * 2. **TLS 必须是 1.3**。adbd 的配对服务器不协商 1.2，握手直接失败。
 *
 * 3. **客户端证书 CN 必须是 `LinuxTransport`**，这是 adbd 用来识别
 *    「这是配对流程而不是普通连接」的标记。
 *
 * 4. **信任所有服务端证书**。配对时设备用的是自签证书，没有任何 CA 能验它；
 *    配对的真实性由后面的 SPAKE2 保证（口令在用户眼前、只此一次）。
 *
 * 5. **IPv4 失败要回退 IPv6**。部分 ROM 的 adbd 只监听 `::1`（Android 的
 *    无线调试在无网络时也允许本机自己配对），反过来也有。两个都试。
 *
 * 6. **PeerInfo 的公钥行不带 `ssh-rsa ` 前缀**。这是 Android 的私有关键字
 *    编码（`android_pubkey_encode`），不是 OpenSSH 格式。带前缀的话设备
 *    「已配对的设备」列表会显示成一坨 base64 而不是注释名。
 */
object AdbPairClient {

    // ------------------------------------------------------------- 协议常量

    /** 配对包版本，AOSP 写死 1 */
    private const val FRAME_VERSION: Byte = 1

    /** 包类型：SPAKE2 消息 */
    private const val TYPE_SPAKE2_MSG: Byte = 0

    /** 包类型：PeerInfo（内含公钥或设备 GUID） */
    private const val TYPE_PEER_INFO: Byte = 1

    /** PeerInfo 固定 8192 字节（AOSP `PeerInfo` 结构体大小） */
    private const val PEER_INFO_SIZE = 8192

    /** PeerInfo 首字节标记：后面跟的是 RSA 公钥 */
    private const val TYPE_ADB_RSA_PUB_KEY: Byte = 0

    /** PeerInfo 首字节标记：后面跟的是设备 GUID（服务端回给我们的） */
    private const val TYPE_ADB_DEVICE_GUID: Byte = 1

    /** TLS 导出的密钥材料长度，AOSP 固定 64 */
    private const val EXPORTED_KEY_SIZE = 64

    /**
     * TLS exporter label。**结尾的 NUL (`\u0000`) 不能省**，见类注释第 1 条。
     */
    private const val EXPORTED_KEY_LABEL = "adb-label\u0000"

    /** HKDF info 字符串，AOSP `pairing_auth` 里写死 */
    private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(StandardCharsets.US_ASCII)

    /** PeerInfo 里公钥行的注释部分；设备「已配对设备」列表里显示的就是它 */
    private const val PUBLIC_KEY_COMMENT = "OSPlus@Android"

    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 8_000

    // ------------------------------------------------------------- 结果类型

    /**
     * 配对结果。区分失败原因是为了让 UI 能给出可操作的提示：
     * 「配对码错误」和「TLS 导出失败」用户要做的事完全不同。
     */
    sealed interface Result {
        data object Success : Result

        /** 6 位码不对或已过期（SPAKE2 派生出不同密钥 → GCM 解不开） */
        data object WrongCode : Result

        /** TLS 层失败：没进配对页面、协议不支持、网络不通 */
        data class TlsError(val detail: String) : Result

        /** 其它失败，附带原始信息 */
        data class Failed(val detail: String) : Result
    }

    // ------------------------------------------------------------- 入口

    /**
     * 执行配对。**阻塞调用**，必须在 IO 线程上调。
     *
     * @param host      优先尝试的地址（通常是无线调试页面显示的 IP）；为空则直接试回环
     * @param pairPort  配对端口（**不是**连接端口，两个端口不同）
     * @param code      设备上显示的 6 位配对码
     * @param context   用于读取内置密钥与证书
     */
    fun pair(
        context: android.content.Context,
        host: String?,
        pairPort: Int,
        code: String,
    ): Result {
        val keyStore = AdbKeyStore(context)
        val certificate = AdbCertificate.load(context)
        val trimmed = code.trim()
        if (trimmed.length != 6) return Result.Failed("配对码应为 6 位数字")
        val codeBytes = trimmed.toByteArray(StandardCharsets.US_ASCII)

        // 依次尝试：用户给的地址 → IPv4 回环 → IPv6 回环
        val candidates = buildList {
            if (!host.isNullOrBlank()) add(host)
            add("127.0.0.1")
            add("::1")
        }.distinct()

        var last: Result = Result.Failed("无可用地址")
        for (h in candidates) {
            val r = pairOnce(h, pairPort, codeBytes, keyStore, certificate)
            if (r is Result.Success) return r
            last = r
            // TLS 层就失败了，换地址也是白搭（说明没开配对页面），不必重试
            if (r is Result.TlsError) return r
        }
        return last
    }

    private fun pairOnce(
        host: String,
        pairPort: Int,
        codeBytes: ByteArray,
        keyStore: AdbKeyStore,
        certificate: AdbCertificate,
    ): Result {
        var sock: SSLSocket? = null
        try {
            val key = keyStore.privateKey()
            val certPem = certificate.pem()

            // ---- 1) 建立 TLS 1.3 连接 ----
            val raw = Socket()
            raw.connect(InetSocketAddress(host, pairPort), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS

            val ctx = SSLContext.getInstance("TLS", Conscrypt.newProvider())
            ctx.init(
                arrayOf<KeyManager>(clientKeyManager(key, certPem, certificate.certificate)),
                arrayOf<TrustManager>(TrustAllManager),
                SecureRandom(),
            )
            sock = ctx.socketFactory.createSocket(raw, host, pairPort, true) as SSLSocket
            sock.useClientMode = true
            sock.enabledProtocols = arrayOf("TLSv1.3")
            sock.soTimeout = READ_TIMEOUT_MS
            try {
                sock.startHandshake()
            } catch (t: Throwable) {
                runCatching { sock.close() }
                return Result.TlsError(
                    "TLS 握手失败，请确认已打开「使用配对码配对设备」页面（该页面打开后 1 分钟内有效）：${t.brief()}",
                )
            }

            val input = DataInputStream(sock.inputStream)
            val output: OutputStream = sock.outputStream

            // ---- 2) 导出 TLS 密钥材料 ----
            val exported = try {
                Conscrypt.exportKeyingMaterial(sock, EXPORTED_KEY_LABEL, null, EXPORTED_KEY_SIZE)
            } catch (t: Throwable) {
                runCatching { sock.close() }
                return Result.Failed("TLS 导出密钥失败，本机 TLS 实现不支持 exporter：${t.brief()}")
            }
            if (exported == null || exported.size != EXPORTED_KEY_SIZE) {
                runCatching { sock.close() }
                return Result.Failed("TLS 导出的密钥材料长度异常")
            }

            // ---- 3) SPAKE2，口令 = 配对码 ‖ 导出材料 ----
            val password = codeBytes + exported
            val spake = Spake2.newClient()
            val myMsg = spake.generateMessage(password)

            // ---- 4) 交换 SPAKE2 消息 ----
            writeFrame(output, TYPE_SPAKE2_MSG, myMsg)
            val theirMsg = readFrame(input)
                ?: return Result.Failed("配对超时：未收到设备响应")
            val keyMaterial = try {
                spake.processMessage(theirMsg)
            } catch (t: Throwable) {
                runCatching { sock.close() }
                return Result.WrongCode
            }

            // ---- 5) HKDF → AES-128-GCM ----
            val aesKey = hkdfSha256(keyMaterial, HKDF_INFO, 16)
            val enc = AesGcmBox(aesKey)
            val dec = AesGcmBox(aesKey)

            // ---- 6) 发送 PeerInfo（内置公钥） ----
            val peerInfo = ByteArray(PEER_INFO_SIZE)
            peerInfo[0] = TYPE_ADB_RSA_PUB_KEY
            val pubLine = sshRsaPublicKeyLine(certificate.certificate.publicKey as RSAPublicKey)
            System.arraycopy(
                pubLine, 0, peerInfo, 1,
                minOf(pubLine.size, PEER_INFO_SIZE - 2),
            )
            writeFrame(output, TYPE_PEER_INFO, enc.encrypt(peerInfo))

            // ---- 7) 读设备 PeerInfo 并解密（这一步才真正验证配对码） ----
            val theirEnc = readFrame(input)
                ?: return Result.Failed("配对超时：未收到设备确认")
            val theirInfo = try {
                dec.decrypt(theirEnc)
            } catch (t: Throwable) {
                runCatching { sock.close() }
                return Result.WrongCode
            }
            runCatching { sock.close() }

            if (theirInfo.isEmpty() || theirInfo[0] != TYPE_ADB_DEVICE_GUID) {
                return Result.Failed("配对失败：设备返回了异常响应")
            }
            PrivilegeLog.i(TAG, "配对成功 host=$host port=$pairPort")
            return Result.Success
        } catch (t: Throwable) {
            runCatching { sock?.close() }
            PrivilegeLog.w(TAG, "配对失败 host=$host port=$pairPort", t)
            return Result.Failed(t.brief())
        } finally {
            runCatching { sock?.close() }
        }
    }

    // ------------------------------------------------------------- TLS 辅助

    /**
     * 构造只发我们这一张证书的 KeyManager。
     *
     * 不能用默认实现：默认的 `KeyManagerFactory` 会按服务端发来的 CA 列表
     * 过滤客户端证书，而 adbd 配对时给的列表是空的，结果就是**一张证书都
     * 不发**、握手虽然成功但服务端把我们当匿名客户端，SPAKE2 阶段必然失败。
     */
    private fun clientKeyManager(
        key: PrivateKey,
        certPem: String,
        cert: X509Certificate,
    ): X509KeyManager = object : X509KeyManager {
        private val alias = "adbkey"
        private val chain = arrayOf(cert)

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out java.security.Principal>?,
            socket: Socket?,
        ): String = alias

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
            socket: Socket?,
        ): String? = null

        override fun getCertificateChain(a: String?): Array<X509Certificate>? =
            if (alias == a) chain else null

        override fun getPrivateKey(a: String?): PrivateKey? = if (alias == a) key else null

        override fun getClientAliases(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
        ): Array<String> = arrayOf(alias)

        override fun getServerAliases(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
        ): Array<String>? = null
    }

    /**
     * 配对场景必须信任任意服务端证书：设备只有自签证书，没有 CA 能验。
     * 真正的身份保证来自 SPAKE2 的口令（用户眼前的 6 位码）。
     */
    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // ------------------------------------------------------------- 帧协议

    /**
     * 写一个配对包。包头 6 字节且**没有对齐填充**（AOSP 里是 `__attribute__((packed))`）：
     * `version(1) | type(1) | payloadLength(u32 大端)`。
     */
    private fun writeFrame(out: OutputStream, type: Byte, payload: ByteArray) {
        val hdr = ByteArray(6)
        hdr[0] = FRAME_VERSION
        hdr[1] = type
        val len = payload.size
        hdr[2] = (len ushr 24).toByte()
        hdr[3] = (len ushr 16).toByte()
        hdr[4] = (len ushr 8).toByte()
        hdr[5] = len.toByte()
        out.write(hdr)
        out.write(payload)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray? {
        val hdr = ByteArray(6)
        if (!input.readFullyOrNull(hdr, 6)) return null
        if (hdr[0] != FRAME_VERSION) return null
        val len = ((hdr[2].toLong() and 0xff) shl 24) or
            ((hdr[3].toLong() and 0xff) shl 16) or
            ((hdr[4].toLong() and 0xff) shl 8) or
            (hdr[5].toLong() and 0xff)
        // 上限取 PeerInfo 的两倍：AES-GCM 密文比明文多 16 字节 tag，
        // 再加一些余量，同时挡住畸形长度导致的巨额分配。
        if (len <= 0 || len > 2L * PEER_INFO_SIZE) return null
        val payload = ByteArray(len.toInt())
        if (!input.readFullyOrNull(payload, payload.size)) return null
        return payload
    }

    private fun DataInputStream.readFullyOrNull(buf: ByteArray, n: Int): Boolean {
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) return false
            off += r
        }
        return true
    }

    // ------------------------------------------------------------- 加解密

    /**
     * AES-128-GCM 分帧箱。
     *
     * nonce 是 **12 字节**：前 8 字节是小端计数器，后 4 字节为 0。
     * 这一点必须和 BoringSSL 的 `AEAD_CTX` 用法一致——用随机 nonce 或
     * 大端计数器都能自己算对，但对端解不开，且报错是「配对码错误」，
     * 极难定位。加密和解密各自维护独立计数器。
     */
    private class AesGcmBox(private val key: ByteArray) {
        private var encSeq = 0L
        private var decSeq = 0L

        fun encrypt(data: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(encSeq++)))
            return c.doFinal(data)
        }

        fun decrypt(data: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(decSeq++)))
            return c.doFinal(data)
        }

        private fun nonce(seq: Long): ByteArray {
            val n = ByteArray(12)
            for (i in 0 until 8) n[i] = (seq ushr (8 * i)).toByte()
            return n
        }
    }

    /**
     * HKDF-SHA256，只用一轮 expand（输出 16 字节，远小于一个 hash 块）。
     *
     * salt 传 32 个 0 字节，等价于「无 salt」，与 AOSP 的 `HKDF(salt=null)` 一致。
     */
    internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01)
        return mac.doFinal().copyOf(outLen)
    }

    // ------------------------------------------------------------- 公钥编码

    /**
     * 生成设备要的公钥行：`Base64(android_pubkey_encode(n,e)) + " " + 注释`。
     *
     * **没有 `ssh-rsa ` 前缀**。AOSP 的 `rsa_2048_key.cpp` 里公钥是按
     * `android_pubkey_encode` 的二进制结构 Base64 出去的，和 OpenSSH 的
     * wire format 完全不同。名字里带 rsa 只是因为它也是 RSA。
     */
    internal fun sshRsaPublicKeyLine(rsa: RSAPublicKey): ByteArray {
        val androidKey = androidPubkeyBytes(rsa.modulus, rsa.publicExponent)
        val line = Base64.encodeToString(androidKey, Base64.NO_WRAP) + " " + PUBLIC_KEY_COMMENT
        return line.toByteArray(StandardCharsets.US_ASCII)
    }

    /**
     * AOSP `libcrypto_utils/android_pubkey_encode` 的字节布局（2048 位密钥共 524 字节）：
     *
     * | 偏移 | 长度 | 内容 |
     * |---|---|---|
     * | 0   | 4   | modulus_size_words（32 位字数 = 64），小端 |
     * | 4   | 4   | n0inv，小端 |
     * | 8   | 256 | modulus N，小端，定长 |
     * | 264 | 256 | rr = 2^4096 mod N，小端，定长 |
     * | 520 | 4   | 公钥指数 e（65537），小端 |
     *
     * `n0inv` 的定义是 `-N⁻¹ mod 2³²`，即「N 模 2³² 的逆元取负」。
     * 这个值本来是给 Montgomery 乘法做预计算用的，写错不影响配对
     * （设备端只做格式校验、不算它），但按真实值填更稳。
     */
    internal fun androidPubkeyBytes(n: BigInteger, e: BigInteger): ByteArray {
        val modSize = 256
        val out = ByteArrayOutputStream(4 + 4 + modSize + modSize + 4)
        val two32 = BigInteger.ONE.shiftLeft(32)
        val mask32 = two32.subtract(BigInteger.ONE)

        // 1) modulus_size_words = 2048/32 = 64
        writeLeU32(out, 64L)

        // 2) n0inv = (2^32 - N^-1 mod 2^32) mod 2^32
        val n0 = n.and(mask32).toLong()
        val inv = BigInteger.valueOf(n0).modInverse(two32).toLong()
        writeLeU32(out, (0x1_0000_0000L - inv) and 0xFFFF_FFFFL)

        // 3) modulus，小端定长 256
        writeLePadded(out, stripLeadingZero(n.toByteArray()), modSize)

        // 4) rr = 2^4096 mod N，小端定长 256
        writeLePadded(
            out,
            stripLeadingZero(BigInteger.ONE.shiftLeft(4096).mod(n).toByteArray()),
            modSize,
        )

        // 5) 指数，小端 u32
        writeLeU32(out, e.toLong() and 0xFFFF_FFFFL)
        return out.toByteArray()
    }

    /** 把大端字节数组反转成小端并补零到 size 字节。 */
    private fun writeLePadded(out: ByteArrayOutputStream, bigEndian: ByteArray, size: Int) {
        for (i in 0 until size) {
            val src = bigEndian.size - 1 - i
            out.write(if (src >= 0) bigEndian[src].toInt() and 0xff else 0)
        }
    }

    private fun writeLeU32(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xff).toInt())
        out.write(((v ushr 8) and 0xff).toInt())
        out.write(((v ushr 16) and 0xff).toInt())
        out.write(((v ushr 24) and 0xff).toInt())
    }

    private fun stripLeadingZero(inBytes: ByteArray): ByteArray {
        var off = 0
        while (off < inBytes.size - 1 && inBytes[off] == 0.toByte()) off++
        return if (off == 0) inBytes else inBytes.copyOfRange(off, inBytes.size)
    }

    private const val TAG = "AdbPairClient"

    private fun Throwable.brief(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}

/**
 * 内置的 ADB 客户端证书（公钥部分用于告诉设备「以后认这张证书」）。
 *
 * 证书和私钥都从 assets 读，第一次使用时落到应用私有目录再解析。
 * 之所以可以直接用一对固定密钥而不是每台设备现生成：配对成功后设备认的是
 * **公钥指纹**，而不是「哪台设备生成的」；一对固定密钥省掉了设备端每次
 * 配对都要重新确认的麻烦，也让「已配对设备」列表里的名字稳定。
 */
class AdbCertificate private constructor(
    val certificate: X509Certificate,
    private val pemText: String,
) {
    fun pem(): String = pemText

    companion object {
        private const val ASSET_CERT = "adbkey/adbcert.pem"

        fun load(context: android.content.Context): AdbCertificate {
            val pem = context.assets.open(ASSET_CERT)
                .bufferedReader(StandardCharsets.UTF_8)
                .use { it.readText() }
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(pem.byteInputStream(StandardCharsets.US_ASCII)) as X509Certificate
            return AdbCertificate(cert, pem)
        }
    }
}
