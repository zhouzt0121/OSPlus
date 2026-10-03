package com.osplus.tools.core.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BoringSSL 兼容的 SPAKE2（Ed25519 群），用于 Android 无线调试配对。
 *
 * ## 来源与验证状态
 *
 * 移植自 [xswl369/android-wireless-debug](https://github.com/xswl369/android-wireless-debug)
 * 的 `Spake2.java`（Apache-2.0），该实现逐行对照 AOSP
 * `external/boringssl/src/crypto/curve25519/spake25519.c`。
 *
 * **已在本机用其内置测试向量验证通过**：
 * - 标量乘向量 `2B = c9a3f86aae465f0e56513864510f3997561fa2c9e85ea21dc2292309f3cd6022` ✓
 * - Alice/Bob 双方派生密钥一致 ✓
 * - 错误口令派生出不同密钥 ✓
 *
 * 这三项分别覆盖了「域运算与点编码正确」「完整交换正确」「安全性质成立」，
 * 是移植密码学代码的最低验证门槛。**改动本文件任何一行后必须重跑这三项**，
 * 否则等于没验证。
 *
 * ## 为什么不能用 JCA / BouncyCastle
 *
 * 已核对 BouncyCastle 1.80.2：**没有 SPAKE2、没有 Ristretto、没有 Ed25519 群运算**。
 * 且 ADB 用的是 BoringSSL 私有变体，与 RFC 9382 不互通（M/N 常量、转录格式、
 * 密钥派生都不同），标准实现拿过来也换不了。
 *
 * ## 与 BoringSSL 的已知差异
 *
 * 用 BigInteger 而非常量时间的域运算。对本场景（与本机 adbd 配对、
 * 口令是用户眼前弹出的 6 位码、不存在跨网络中间人）不构成风险；
 * 但**不要把这个类当作通用密码学库使用**。
 */
internal object Spake2 {

    // ---------------------------------------------------------------- 常量

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)

    /** Ed25519 群阶 l = 2^252 + 27742317777372353535851937790883648493 */
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    private val SQRT_M1: BigInteger =
        BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    const val ROLE_ALICE = 0
    const val ROLE_BOB = 1

    // M / N 掩码点：BoringSSL kSpakeMSmallPrecomp / kSpakeNSmallPrecomp 的首项，小端
    private val MX = leHex("c8a663c597f1ee40ab6242ee256f326c752ca7d3bd323b1e119cbd04a9786f45")
    private val MY = leHex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
    private val NX = leHex("201bc5b343177110441e73b3ae3fbf9ff544c8138fd101c28a1a6dea4d005d6e")
    private val NY = leHex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")

    /** Ed25519 基点 B 的编码（y = 4/5） */
    private val BASE_POINT_ENC = ByteArray(32).also {
        it[0] = 0x58
        for (i in 1 until 32) it[i] = 0x66
    }

    // ---------------------------------------------------------------- 实例

    /**
     * 一个 SPAKE2 会话。
     *
     * 状态机是 init → msg_generated → done，与 BoringSSL 的单次使用语义一致：
     * 同一个会话实例不能重复 `generateMessage`，否则会复用私钥（严重缺陷）。
     */
    class Session internal constructor(
        private val role: Int,
        private val myName: ByteArray,
        private val theirName: ByteArray,
    ) {
        private var privateKey: BigInteger? = null
        private var passwordScalar: BigInteger? = null
        private var passwordHash: ByteArray? = null
        private var myMsg: ByteArray? = null

        /** 生成本方 32 字节消息 */
        fun generateMessage(password: ByteArray, random: ByteArray? = null): ByteArray {
            check(myMsg == null) { "该会话已生成过消息，不能重复使用" }
            val rnd = random ?: ByteArray(64).also { SecureRandom().nextBytes(it) }
            // 私钥 = sc_reduce(随机 64 字节) << 3（清共因子位）
            privateKey = scReduce(rnd).shiftLeft(3)
            // 口令标量 = sc_reduce(SHA-512(pwd))，再通过加 l/2l/4l 把最低 3 位清零
            val pwdHash = sha512(password)
            passwordHash = pwdHash
            var scalar = scReduce(pwdHash)
            if (scalar.testBit(0)) scalar = scalar.add(L)
            if (scalar.testBit(1)) scalar = scalar.add(L.shiftLeft(1))
            if (scalar.testBit(2)) scalar = scalar.add(L.shiftLeft(2))
            check(scalar.and(BigInteger.valueOf(7)) == BigInteger.ZERO) {
                "口令标量共因子清零失败"
            }
            passwordScalar = scalar

            // P* = priv·B + h(pwd)·Mask
            val mask = if (role == ROLE_ALICE) arrayOf(MX, MY) else arrayOf(NX, NY)
            val p = scalarMultBase(privateKey!!)
            val maskPoint = scalarMult(scalar, mask)
            val msg = encode(add(p, maskPoint))
            myMsg = msg
            return msg
        }

        /**
         * 处理对端消息，返回 64 字节密钥材料。
         *
         * 转录顺序按角色区分（Alice 用自己的名字在前，Bob 反过来），
         * 这是协议规定，写反会导致双方派生出不同密钥。
         */
        fun processMessage(theirMsg: ByteArray): ByteArray {
            check(myMsg != null) { "必须先调用 generateMessage" }
            require(theirMsg.size == 32) { "对端消息长度应为 32 字节" }
            val qStar = decode(theirMsg)
            val peersMask = scalarMult(
                passwordScalar!!,
                if (role == ROLE_ALICE) arrayOf(NX, NY) else arrayOf(MX, MY),
            )
            val q = sub(fromAffine(qStar), peersMask)
            val dh = scalarMult(privateKey!!, q)

            val md = MessageDigest.getInstance("SHA-512")
            if (role == ROLE_ALICE) {
                md.updateLenPrefixed(myName)
                md.updateLenPrefixed(theirName)
                md.updateLenPrefixed(myMsg!!)
                md.updateLenPrefixed(theirMsg)
            } else {
                md.updateLenPrefixed(theirName)
                md.updateLenPrefixed(myName)
                md.updateLenPrefixed(theirMsg)
                md.updateLenPrefixed(myMsg!!)
            }
            md.updateLenPrefixed(encode(dh))
            md.updateLenPrefixed(passwordHash!!)
            return md.digest()
        }
    }

    fun newClient(): Session = Session(
        ROLE_ALICE,
        "adb pair client\u0000".toByteArray(Charsets.US_ASCII),
        "adb pair server\u0000".toByteArray(Charsets.US_ASCII),
    )

    fun newServer(): Session = Session(
        ROLE_BOB,
        "adb pair server\u0000".toByteArray(Charsets.US_ASCII),
        "adb pair client\u0000".toByteArray(Charsets.US_ASCII),
    )

    private fun MessageDigest.updateLenPrefixed(data: ByteArray) {
        val len = ByteArray(8)
        var v = data.size.toLong()
        for (i in 0 until 8) {
            len[i] = (v and 0xffL).toByte()
            v = v shr 8
        }
        update(len)
        update(data)
    }

    // ---------------------------------------------------------------- 群运算
    //
    // 射影坐标 X:Y:Z:T（extended twisted Edwards），公式取自 BoringSSL / RFC 8032 附录。

    private fun identity(): Array<BigInteger> =
        arrayOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun fromAffine(aff: Array<BigInteger>): Array<BigInteger> {
        val x = aff[0].mod(P)
        val y = aff[1].mod(P)
        return arrayOf(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** 点倍（dbl-2008-hwcd） */
    private fun doublePoint(q: Array<BigInteger>): Array<BigInteger> {
        val x1 = q[0]; val y1 = q[1]; val z1 = q[2]
        val a = x1.multiply(x1).mod(P)
        val b = y1.multiply(y1).mod(P)
        val c = z1.multiply(z1).mod(P).shiftLeft(1).mod(P)
        val d = P.subtract(a)
        val e = x1.add(y1).mod(P).pow(2).subtract(a).subtract(b).mod(P)
        val g = d.add(b).mod(P)
        val f = g.subtract(c).mod(P)
        val h = d.subtract(b).mod(P)
        return arrayOf(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P),
        )
    }

    /** 点加（add-2008-hwcd-3） */
    private fun add(p: Array<BigInteger>, q: Array<BigInteger>): Array<BigInteger> {
        val (x1, y1, z1, t1) = p
        val (x2, y2, z2, t2) = q
        val a = y1.subtract(x1).multiply(y2.subtract(x2)).mod(P)
        val b = y1.add(x1).multiply(y2.add(x2)).mod(P)
        val c = t1.multiply(TWO_D).multiply(t2).mod(P)
        val d = z1.shiftLeft(1).multiply(z2).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return arrayOf(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P),
        )
    }

    /** 点减：p + (-q)。扭曲爱德华兹（a = -1）的逆元是 (-x, y)。 */
    private fun sub(p: Array<BigInteger>, q: Array<BigInteger>): Array<BigInteger> {
        val (x1, y1, z1, t1) = p
        val negQ = arrayOf(
            q[0].negate().mod(P), q[1], q[2], q[3].negate().mod(P),
        )
        val (x2, y2, z2, t2) = negQ
        val a = y1.subtract(x1).multiply(y2.subtract(x2)).mod(P)
        val b = y1.add(x1).multiply(y2.add(x2)).mod(P)
        val c = t1.multiply(TWO_D).multiply(t2).mod(P)
        val d = z1.shiftLeft(1).multiply(z2).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return arrayOf(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P),
        )
    }

    /** 标量乘（double-and-add，从最高位起） */
    private fun scalarMult(s: BigInteger, point: Array<BigInteger>): Array<BigInteger> {
        val q = if (point.size == 4) point else fromAffine(point)
        var r = identity()
        for (i in s.bitLength() - 1 downTo 0) {
            r = doublePoint(r)
            if (s.testBit(i)) r = add(r, q)
        }
        return r
    }

    private fun scalarMultBase(s: BigInteger): Array<BigInteger> =
        scalarMult(s, decode(BASE_POINT_ENC))

    /** Ed25519 点编码：y（小端 32 字节）| (x 最低位) << 255 */
    private fun encode(pt: Array<BigInteger>): ByteArray {
        val aff = toAffine(pt)
        val out = ByteArray(32)
        val yb = aff[1].toByteArray()
        var i = 0
        while (i < yb.size && i < 32) {
            out[i] = yb[yb.size - 1 - i]
            i++
        }
        if (aff[0].testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun toAffine(pt: Array<BigInteger>): Array<BigInteger> {
        val zInv = pt[2].modInverse(P)
        return arrayOf(pt[0].multiply(zInv).mod(P), pt[1].multiply(zInv).mod(P))
    }

    /**
     * Ed25519 点解码：从 y 恢复 x，校验奇偶位与曲线方程。
     *
     * **必须拒绝非法点**，否则会接受攻击者构造的小子群点。
     */
    private fun decode(b: ByteArray): Array<BigInteger> {
        val y = leBytes(b).and(BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE))
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val vInv = v.modInverse(P)
        var x = u.multiply(vInv).mod(P)
            .modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).mod(P).multiply(v).mod(P) != u) {
            x = x.multiply(SQRT_M1).mod(P)
            require(x.multiply(x).mod(P).multiply(v).mod(P) == u) { "点不在曲线上" }
        }
        val signBit = (b[31].toInt() and 0x80) != 0
        if (x.testBit(0) != signBit) x = P.subtract(x)
        // on-curve 复核：x² + y² == 1 + d·x²·y²
        val x2 = x.multiply(x).mod(P)
        require(
            y2.subtract(x2).mod(P) ==
                BigInteger.ONE.add(D.multiply(x2).multiply(y2)).mod(P),
        ) { "点不在曲线上" }
        return arrayOf(x, y)
    }

    // ---------------------------------------------------------------- 工具

    /** x25519_sc_reduce：64 字节小端 → mod l */
    private fun scReduce(b64: ByteArray): BigInteger = leBytes(b64).mod(L)

    private fun leBytes(b: ByteArray): BigInteger {
        val rev = ByteArray(b.size)
        for (i in b.indices) rev[i] = b[b.size - 1 - i]
        return BigInteger(1, rev)
    }

    private fun leHex(hex: String): BigInteger {
        val b = ByteArray(hex.length / 2)
        for (i in b.indices) {
            b[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return leBytes(b)
    }

    private fun sha512(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(input)

    /**
     * 自检：三项固定向量。移植或改动本文件后必须运行，全部通过才算正确。
     *
     * 之所以把它写在生产代码里而不是测试目录：这是判断「这份密码学实现
     * 还能不能用」的唯一依据，需要在真机上也能随时跑一次。
     */
    fun selfTest(): SelfTestResult {
        // 1) 标量乘向量 2B
        val twoB = encode(scalarMult(BigInteger.valueOf(2), decode(BASE_POINT_ENC)))
        val expected = "c9a3f86aae465f0e56513864510f3997561fa2c9e85ea21dc2292309f3cd6022"
        val scalarOk = twoB.toHex() == expected

        // 2) Alice/Bob 密钥一致
        val pwd = "123456".toByteArray(Charsets.US_ASCII)
        val alice = newClient()
        val bob = newServer()
        val aMsg = alice.generateMessage(pwd)
        val bMsg = bob.generateMessage(pwd)
        val aKey = alice.processMessage(bMsg)
        val bKey = bob.processMessage(aMsg)
        val agreeOk = MessageDigest.isEqual(aKey, bKey)

        // 3) 错误口令必须派生出不同密钥
        val alice2 = newClient()
        val bob2 = newServer()
        val bMsg2 = bob2.generateMessage("654321".toByteArray(Charsets.US_ASCII))
        val aMsg2 = alice2.generateMessage(pwd)
        val aKey2 = alice2.processMessage(bMsg2)
        val bKey2 = bob2.processMessage(aMsg2)
        val mismatchOk = !MessageDigest.isEqual(aKey2, bKey2)

        return SelfTestResult(
            scalarMultVectorOk = scalarOk,
            keyAgreementOk = agreeOk,
            wrongPasswordMismatchOk = mismatchOk,
            twoBHex = twoB.toHex(),
        )
    }

    data class SelfTestResult(
        val scalarMultVectorOk: Boolean,
        val keyAgreementOk: Boolean,
        val wrongPasswordMismatchOk: Boolean,
        val twoBHex: String,
    ) {
        val allPassed: Boolean
            get() = scalarMultVectorOk && keyAgreementOk && wrongPasswordMismatchOk
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
