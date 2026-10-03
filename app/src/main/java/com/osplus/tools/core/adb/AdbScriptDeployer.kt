package com.osplus.tools.core.adb

import android.content.Context
import com.osplus.tools.core.CommandResult
import com.osplus.tools.core.PrivilegeLog
import java.io.File

/**
 * 照搬 Scene（`com.omarea.vtools`）的 ADB 激活方式。
 *
 * ## 这套方法在解决什么
 *
 * 普通应用拿不到 shell 权限，但 `adb shell` 拿得到。Scene 的做法是：
 * 把一个脚本放到**外部私有目录**，让用户用一条 adb 命令执行它，
 * 于是脚本里的每条命令都以 shell（uid 2000）身份运行 —— 不需要 root。
 *
 * ## 为什么偏偏是 /storage/emulated/0/Android/data/<pkg>/
 *
 * 这是 Android 上少见的「应用写得了、adb 读得到」的落点：
 *
 * | 位置 | 应用 | adb (shell) |
 * |---|---|---|
 * | `/data/data/<pkg>/` | 可读写 | **不可读**（SELinux 域隔离） |
 * | `/storage/emulated/0/Android/data/<pkg>/` | 可读写 | 可读（属组 `ext_data_rw`，shell 在组内） |
 *
 * 应用私有目录里的脚本 adb 根本执行不了，所以必须落到外部私有目录。
 * 本机实测 `id` 输出含 `1078(ext_data_rw)`，而该目录属组正是 `ext_data_rw`，链路成立。
 * 这也是 Scene 选这个路径的原因。
 *
 * ## 与 Scene 的差别
 *
 * Scene 的 `up.sh` 还会把 daemon / busybox 部署到 `/data/local/tmp/` 并常驻，
 * 靠 binder 与主应用通信。OSPlus 不需要常驻进程 —— 已有的 ADB 通道
 * （[AdbBackend]）本身就维持着长连接，因此这里只保留「脚本部署 + 执行」这一层。
 */
object AdbScriptDeployer {

    private const val TAG = "AdbScriptDeployer"

    /** assets 中的脚本路径 */
    private const val ASSET_SCRIPT = "adb/up.sh"

    /** 部署到外部私有目录后的文件名（与 Scene 保持一致，便于对照） */
    private const val SCRIPT_NAME = "up.sh"

    /** 本应用包名 */
    const val PACKAGE_NAME = "com.osplus.tools"

    /** 脚本执行后落盘的能力探测结果 */
    const val CAPABILITIES_PATH = "/data/local/tmp/osplus/capabilities.txt"

    /** 部署结果 */
    sealed interface DeployResult {
        /** @param path 脚本在设备上的绝对路径 */
        data class Ok(val path: String) : DeployResult

        /** @param reason 面向用户的原因说明 */
        data class Failed(val reason: String) : DeployResult
    }

    /**
     * 脚本在设备上的绝对路径。
     *
     * 用 `getExternalFilesDir(null).parentFile` 而不是 `filesDir`：
     * 前者是 `/storage/emulated/0/Android/data/<pkg>`（外部私有目录，adb 可读），
     * 后者是 `/data/data/<pkg>/files`（应用私有目录，adb 不可读）。
     * 取 parentFile 是为了让落点和 Scene 完全一致（脚本直接放在包目录下）。
     *
     * @return 外部存储不可用（未挂载/被移除）时返回 null
     */
    fun scriptPath(context: Context): String? =
        context.getExternalFilesDir(null)
            ?.parentFile
            ?.let { File(it, SCRIPT_NAME) }
            ?.absolutePath

    /**
     * 把 assets 里的脚本写到外部私有目录。
     *
     * 每次调用都覆盖写入 —— 应用升级后 assets 中的脚本可能已变，
     * 不做存在性跳过，避免用户拿到旧版本脚本。
     */
    fun deploy(context: Context): DeployResult {
        val dir = context.getExternalFilesDir(null)?.parentFile
            ?: return DeployResult.Failed("外部存储不可用，无法写出脚本")
        if (!dir.exists() && !dir.mkdirs()) {
            return DeployResult.Failed("无法创建目录：${dir.absolutePath}")
        }

        val target = File(dir, SCRIPT_NAME)
        return runCatching {
            context.assets.open(ASSET_SCRIPT).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            // 顺带把交换目录也建出来 —— **必须由应用来建**。
            //
            // 若让 up.sh 里的 shell 去 mkdir，目录属主就是 shell，应用只能靠
            // ext_data_rw 组权限去够它；而应用进程的附加组在不同 ROM 上并不一致，
            // 一旦拿不到组权限，DaemonBackend 就看不到心跳文件，
            // 表现为「daemon 明明在跑，应用却说未连接」。
            // 应用自己建目录则属主是应用，访问必然成功；
            // shell（在 ext_data_rw 组内）也能读写，两边都稳。
            File(dir, ".daemon").mkdirs()
            // 组可写：让 shell 身份的 daemon 能写心跳与结果文件
            File(dir, ".daemon").setWritable(true, false)
            PrivilegeLog.i(TAG, "脚本已部署到 ${target.absolutePath}（${target.length()} 字节）")
            DeployResult.Ok(target.absolutePath) as DeployResult
        }.getOrElse { e ->
            PrivilegeLog.e(TAG, "脚本部署失败", e)
            DeployResult.Failed("写入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 生成给用户复制的完整 adb 命令。
     *
     * 与 Scene 的调用形式保持一致：`adb shell sh <外部私有目录>/up.sh`
     */
    fun activationCommand(context: Context): String =
        "adb shell sh ${scriptPath(context) ?: "/storage/emulated/0/Android/data/$PACKAGE_NAME/$SCRIPT_NAME"}"

    /**
     * 通过**已建立的 ADB 通道**执行脚本。
     *
     * 这是相对 Scene 的增强：Scene 只能由用户从电脑手动执行，
     * 而 OSPlus 已经自带了 ADB 客户端（[AdbBackend]），
     * 因此在无线调试已配对的前提下，应用内可以一键完成同样的动作。
     *
     * @param backend 已就绪的 ADB 后端
     * @param context 用于定位脚本路径
     */
    suspend fun runViaAdb(
        backend: AdbBackend,
        context: Context,
        timeoutMs: Long = 60_000L,
    ): CommandResult {
        val path = scriptPath(context)
            ?: return CommandResult(false, "", "外部存储不可用，无法定位脚本")

        // 先确认脚本确实在设备上：应用写外部私有目录与实际可见性之间
        // 存在 MediaStore 扫描延迟，直接执行会得到「not found」这种误导性错误。
        val exists = backend.run("test -f '$path' && echo OK", 5_000L)
        if (!exists.stdout.contains("OK")) {
            return CommandResult(false, "", "脚本不存在于 $path，请先执行部署")
        }

        PrivilegeLog.i(TAG, "通过 ADB 通道执行 $path")
        return backend.run("sh '$path'", timeoutMs)
    }

    /**
     * 读取脚本落盘的能力探测结果。
     *
     * 脚本里已经实测过 shell 身份能做什么（见 `up.sh` 第 3 步），
     * 这里只是把它取回来。相比在 Kotlin 里逐项 `run()` 探测，
     * 好处是探测与使用发生在同一个身份下，结论不会漂移。
     */
    suspend fun readCapabilities(backend: AdbBackend): Map<String, String> {
        val res = backend.run("cat $CAPABILITIES_PATH 2>/dev/null", 5_000L)
        if (!res.success || res.stdout.isBlank()) return emptyMap()
        return res.stdout.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
    }
}
