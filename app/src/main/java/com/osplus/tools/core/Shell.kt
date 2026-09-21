package com.osplus.tools.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File

/**
 * 统一的命令执行入口。
 *
 * 所有耗时调用都必须在 [Dispatchers.IO] 上执行，禁止在主线程调用，
 * 否则会造成界面卡顿甚至 ANR。
 */
object Shell {

    /** root 可用性缓存（进程内仅探测一次） */
    @Volatile
    private var rootProbe: Boolean? = null

    @Volatile
    private var suPathCache: String? = null

    private val candidateSuPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su",
    )

    /** 探测 su 可执行文件位置 */
    private fun findSu(): String? {
        suPathCache?.let { return it }
        val found = candidateSuPaths.firstOrNull { File(it).exists() }
        suPathCache = found
        return found
    }

    /**
     * 判断设备是否已获取 root。
     * 结果会被缓存，避免频繁拉起进程。
     */
    suspend fun isRootAvailable(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force) rootProbe?.let { return@withContext it }
        val su = findSu()
        val ok = if (su == null) {
            false
        } else {
            runCatching {
                val p = ProcessBuilder(su, "-c", "id -u").start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                out == "0"
            }.getOrDefault(false)
        }
        rootProbe = ok
        ok
    }

    /**
     * 执行一条 shell 命令。
     *
     * @param command 命令正文
     * @param root 是否以 root 身份执行；设备无 root 时自动降级为普通 shell
     */
    suspend fun run(command: String, root: Boolean = true): CommandResult =
        withContext(Dispatchers.IO) {
            val useRoot = root && isRootAvailable()
            if (root && !useRoot) {
                return@withContext CommandResult(false, "", "root 不可用")
            }
            runCatching {
                val cmd = if (useRoot) arrayOf(findSu() ?: "su", "-c", command) else
                    arrayOf("/system/bin/sh", "-c", command)
                val process = ProcessBuilder(*cmd).apply {
                    redirectErrorStream(false)
                }.start()
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val code = process.waitFor()
                CommandResult(code == 0, stdout.trim(), stderr.trim(), code)
            }.getOrElse { e ->
                CommandResult(false, "", e.message ?: e.toString())
            }
        }

    /**
     * 读取单个 sysfs / procfs 节点。
     * 节点不存在或不可读时返回 null，不抛异常。
     *
     * 直接读取失败有两种原因：节点不存在，或节点存在但被 SELinux 拒绝
     * （Android 12+ 的 vendor_sysfs_kgsl / vendor_sysfs_battery_supply 等标签）。
     * 两种情况都必须再交给 shell（必要时提权）重试一次，否则会漏掉后者。
     */
    suspend fun readNode(path: String, root: Boolean = true): String? = withContext(Dispatchers.IO) {
        val direct = runCatching { File(path).readText().trim() }.getOrNull()
        if (!direct.isNullOrBlank()) return@withContext direct
        val viaShell = run(command = "cat $path 2>/dev/null", root = root).stdout
        viaShell.takeIf { it.isNotBlank() }
    }

    /** 写入 sysfs 节点 */
    suspend fun writeNode(path: String, value: String): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false
        runCatching {
            val su = findSu() ?: return@runCatching false
            val process = Runtime.getRuntime().exec(su)
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("chmod 0644 $path 2>/dev/null\n")
                os.writeBytes("echo '$value' > $path 2>/dev/null\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            process.waitFor()
            true
        }.getOrDefault(false) || run("echo '$value' > $path", root = true).success
    }


}

data class CommandResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String = "",
    val exitCode: Int = -1,
)
