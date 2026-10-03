package com.osplus.tools.core

import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 一次命令执行的结果。
 *
 * 放在这里而不是 Shell.kt：后端实现与结果类型是同一层抽象，
 * 让 [Shell] 只保留分发逻辑，不必再持有数据结构。
 */
data class CommandResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String = "",
    val exitCode: Int = -1,
)

/**
 * 提权通道后端。
 *
 * 三种模式（Root / Shizuku / ADB）在「怎么拿到高权限」上完全不同，
 * 但拿到之后要做的事是一样的：把一条 shell 命令交给那个身份去执行。
 * 因此接口只暴露三个动作——探测可用性、跑命令、写节点——
 * 上层（各 DataSource）对此一无所知，也就不会因为新增模式而需要改动。
 *
 * 实现者必须自行保证：
 * 1. **有超时**（见 [Shell.COMMAND_TIMEOUT_MS] 的说明，挂死会导致整个监控停摆）；
 * 2. **stdout / stderr 并发读**（串行读会在大输出时死锁）；
 * 3. 读流异常要自己吞掉（超时强杀管道后 `readText()` 会抛 IOException，
 *    若冒到调用方会把整个采样循环打崩）。
 */
interface PrivilegeBackend {

    /** 该后端在当前设备上是否可用（root 已授权 / Shizuku 已运行且已授权 / ADB 已配对） */
    suspend fun isAvailable(): Boolean

    /**
     * 执行一条命令。
     *
     * @param command shell 命令正文（会交给 `sh -c` 解释）
     * @param timeoutMs 超时上限
     */
    suspend fun run(command: String, timeoutMs: Long): CommandResult

    /**
     * 向节点写入内容（需要先取得可写权限）。
     *
     * 默认实现走 `chmod` + 重定向；Shizuku / ADB 两个后端复用同一套。
     */
    suspend fun writeNode(path: String, value: String, timeoutMs: Long): Boolean {
        val cmd = "chmod 0644 '$path' 2>/dev/null; echo '$value' > '$path' 2>/dev/null"
        return run(cmd, timeoutMs).success
    }
}

/**
 * 基于本地 `su` 二进制的 Root 后端。
 *
 * 与旧实现的行为完全一致（先 `-c` 单发，失败再走交互式 su 会话），
 * 抽出来只是为了和另外两个后端放在同一层对比，行为不变。
 */
class RootBackend(
    private val suPath: () -> String?,
) : PrivilegeBackend {

    override suspend fun isAvailable(): Boolean {
        val su = suPath() ?: return false
        return runCatching {
            val p = ProcessBuilder(su, "-c", "id -u").start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out == "0"
        }.getOrDefault(false)
    }

    override suspend fun run(command: String, timeoutMs: Long): CommandResult =
        Shell.runProcess(arrayOf(suPath() ?: "su", "-c", command), timeoutMs)

    /**
     * `su -c` 在部分 Magisk 版本上对重定向的支持不稳定，
     * 因此保留旧实现的交互式会话写法作为首选，失败再退回 `-c`。
     */
    override suspend fun writeNode(path: String, value: String, timeoutMs: Long): Boolean {
        val su = suPath() ?: return false
        val viaSession = runCatching {
            val process = Runtime.getRuntime().exec(su)
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("chmod 0644 $path 2>/dev/null\n")
                os.writeBytes("echo '$value' > $path 2>/dev/null\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                false
            } else true
        }.getOrDefault(false)
        if (viaSession) return true
        // 兜底：退出码不可靠（重定向失败时 sh -c 仍返回 0），因此回读校验
        run("echo '$value' > '$path'", timeoutMs)
        return readBack(path, value)
    }

    private suspend fun readBack(path: String, expect: String): Boolean =
        run("cat '$path'", timeoutMs = 2_000L).stdout.trim() == expect.trim()
}

/**
 * 基于 shell 身份（uid 2000）的后端，Shizuku 与 ADB 共用。
 *
 * 两者拿到身份的方式不同，但**拿到之后调用形式完全一致**：
 * 都是「把命令字符串交给一个具备 shell 权限的执行器」。
 * 因此差别被收敛到 [execShell] 这一个方法上，其余逻辑复用。
 *
 * @param execShell 以 shell 身份执行命令并返回标准输出；失败抛异常
 */
class ShellBackend(
    private val execShell: suspend (command: String, timeoutMs: Long) -> CommandResult,
) : PrivilegeBackend {

    override suspend fun isAvailable(): Boolean =
        runCatching { run("id -u", 3_000L).stdout.trim() }
            .getOrDefault("")
            .let { it == "2000" || it == "0" }

    override suspend fun run(command: String, timeoutMs: Long): CommandResult =
        runCatching { execShell(command, timeoutMs) }
            .getOrElse { e -> CommandResult(false, "", e.message ?: e.toString()) }

    override suspend fun writeNode(path: String, value: String, timeoutMs: Long): Boolean {
        run("chmod 0644 '$path' 2>/dev/null", timeoutMs)
        val res = run("echo '$value' > '$path'", timeoutMs)
        // shell 身份对 sysfs 的写入经常「静默失败」：重定向失败时退出码仍是 0。
        // 所以必须回读确认，不能只看 success。
        val back = run("cat '$path' 2>/dev/null", 2_000L).stdout.trim()
        return back == value.trim() || res.success && back.isNotEmpty()
    }
}

/** 本地 shell 后端（无任何提权，`/system/bin/sh`） */
object LocalShellBackend : PrivilegeBackend {

    override suspend fun isAvailable(): Boolean = true

    override suspend fun run(command: String, timeoutMs: Long): CommandResult =
        Shell.runProcess(arrayOf("/system/bin/sh", "-c", command), timeoutMs)
}
