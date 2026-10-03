package com.osplus.tools.core.adb

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * ADB 客户端私钥（PEM PKCS#8）。
 *
 * 私钥随 APK 内置在 `assets/adbkey/adbkey`，**不是每台设备现生成**。
 *
 * ## 这个选择的代价（必读）
 *
 * 内置意味着**所有装了本应用的设备共用同一把私钥**，因此设备侧的
 * 「已配对设备」列表里，多台设备会显示成同一个身份。更实际的问题是：
 * 这把密钥同时被打进了这个开源库的仓库里，任何人都能取到。
 *
 * 权衡如下：
 * - **支持的场景**：本机自用、单设备、图省事（配对一次，设备永久认这把 key）
 * - **不支持**：多设备分发、或对身份隔离有要求的场景
 *
 * 如果将来要发布给他人使用，应当改成「首次使用时在设备本地生成密钥对」，
 * 并把公钥按 [AdbPairClient] 的格式在配对时交给设备
 * （设备认的是公钥，现生成完全可行，只是每次换设备要重新配对）。
 * 这条路径的代码骨架见 [AdbIdentityStore]，目前未被接入。
 */
internal class AdbKeyStore(private val context: Context) {

    companion object {
        private const val ASSET_KEY = "adbkey/adbkey"
    }

    @Volatile
    private var cached: PrivateKey? = null

    /** assets 里是否带了私钥（打包遗漏时要能明确报错，而不是抛异常） */
    fun exists(): Boolean = runCatching {
        val stream: java.io.InputStream = context.assets.open(ASSET_KEY)
        stream.use { it.readBytes() }.isNotEmpty()
    }.getOrDefault(false)

    /**
     * 读取私钥。
     *
     * @throws IllegalStateException 资产缺失或格式非法——这两种都是打包问题，
     *   静默返回 null 会让配对失败得毫无线索，所以直接抛出并说明原因。
     */
    fun privateKey(): PrivateKey {
        cached?.let { return it }
        val pem = context.assets.open(ASSET_KEY)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val body = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        if (body.isEmpty()) {
            throw IllegalStateException("assets/$ASSET_KEY 内容为空，无法用于配对")
        }
        val der = runCatching { Base64.decode(body, Base64.DEFAULT) }
            .getOrElse { throw IllegalStateException("assets/$ASSET_KEY 不是合法 Base64：${it.message}") }
        val key = runCatching {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        }.getOrElse {
            throw IllegalStateException(
                "assets/$ASSET_KEY 不是 PKCS#8 格式的 RSA 私钥（需 openssl 导出的 " +
                    "-----BEGIN PRIVATE KEY----- 形式，而非 BEGIN RSA PRIVATE KEY）：${it.message}",
            )
        }
        cached = key
        return key
    }
}
