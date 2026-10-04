package com.osplus.tools.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 统一的命令执行入口。
 *
 * 所有耗时调用都必须在 [Dispatchers.IO] 上执行，禁止在主线程调用，
 * 否则会造成界面卡顿甚至 ANR。
 *
 * ## 提权后端
 *
 * 2.2.0 起本对象不再是「root 专用」，而是按 [PrivilegeManager] 当前选定的
 * [PrivilegeMode] 把命令分发给对应的 [PrivilegeBackend]。
 * 目前只有一条通道：[PrivilegeMode.ROOT] → `su`。
 *
 * （Shizuku 与 ADB 两条 shell 身份通道曾存在过，因实测在本应用的功能需求下
 * 全部失效而被移除，详见 [PrivilegeMode] 的说明。）
 *
 * **对外 API 保持原样**：各 DataSource 仍按 `Shell.run(cmd, root = true)` 调用，
 * 不需要知道底层是谁在跑命令。`root = true` 的语义是「用当前可用的最高身份跑」——
 * 无 root 设备上直接失败，而不是静默降级成普通 shell。
 */
object Shell {

    /** 当前后端。由 [PrivilegeManager] 在探测 / 切换模式时写入 */
    private val backendRef = AtomicReference<PrivilegeBackend?>(null)

    internal fun installBackend(backend: PrivilegeBackend?) {
        backendRef.set(backend)
    }

    internal fun currentBackend(): PrivilegeBackend? = backendRef.get()

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

    /**
     * 探测 su 可执行文件位置（**同步、仅按路径判断**）。
     *
     * ## 这个方法的局限，必须知道
     *
     * `File.exists()` 需要**整条父目录链都可搜索**才能返回 true。而 KernelSU / APatch /
     * Magisk 的 su 都在 `/data/adb/` 下，该目录对应用 uid 与 shell 身份（uid 2000）
     * **都不可搜索**（SELinux）。结果是：
     *
     * - 路径**真存在**时，`exists()` 依然返回 false —— 无法区分「没有 root」与
     *   「有 root 但目录被隔离」；
     * - 实测对照：`ls -l /data/adb/ksu/bin/su` 报 `Permission denied`，
     *   而一个**确定不存在**的 `/data/adb/ksu/bin/su_not_exist_xyz` **报同样的
     *   Permission denied** —— 说明这个错误信息来自父目录，**不能用来判断 su 存否**。
     *
     * 因此**不要**依据本方法的 false 结论就说「设备没有 root」。要判断 root 是否
     * 真的可用，应当用 [probeSuViaShell]（走 `command -v su`，与
     * `Runtime.exec("su")` 同一条机制）或直接尝试执行 `su -c id -u`。
     * 本方法只用于「能确定时给出路径」这一种情形。
     */
    fun findSu(): String? {
        suPathCache?.let { return it }
        val found = candidateSuPaths.firstOrNull { File(it).exists() }
        suPathCache = found
        return found
    }

    /**
     * 走 shell 探测 `su` 是否可执行，返回其解析到的路径。
     *
     * 这是判断「root 是否真的可用」的**正确方式**，理由：
     *
     * 1. **与执行路径一致**。KernelSU/Magisk 会把 `su` 注入进程 PATH（或提供
     *    一个 `/system/bin/su` 的绑定挂载）。`Runtime.exec("su")` / `su -c ...`
     *    靠的就是 PATH 解析，`command -v su` 走同一条路——它说找得到，就真的能执行。
     * 2. **绕开目录搜索限制**。不依赖应用去 stat `/data/adb/...`，而是让 shell
     *    自己解析，避免上一段说的「父目录不可搜索 ⇒ 一律 false」的假阴性。
     *
     * 注意仍然可能被**授权弹窗**挡住：KernelSU 首次调用 `su` 会弹授权框，
     * 因此本方法**不应在开机自启路径上调用**（无人可点，会卡启动）。
     */
    suspend fun probeSuViaShell(): String? = withContext(Dispatchers.IO) {
        // `command -v` 是 POSIX 标准、无需子 shell；找不到时输出空 + 退出码非 0
        val r = runProcess(
            arrayOf("/system/bin/sh", "-c", "command -v su"),
            COMMAND_TIMEOUT_MS,
        )
        r.stdout.trim().takeIf { it.isNotEmpty() && r.success }
    }

    /**
     * 判断设备是否已获取 root。
     *
     * 语义是「**本机是否存在可用的 su**」，与当前选定的提权模式无关——
     * 界面要用它来展示「设备是否具备 root」。
     * 需要判断「当前通道是否能用」请用 [isPrivilegeAvailable]。
     */
    suspend fun isRootAvailable(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force) rootProbe?.let { return@withContext it }
        val ok = RootBackend(::findSu).isAvailable()
        rootProbe = ok
        ok
    }

    /** 当前选定的提权通道是否就绪 */
    suspend fun isPrivilegeAvailable(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val backend = backendRef.get() ?: return@withContext false
        if (force && backend is RootBackend) rootProbe = null
        backend.isAvailable()
    }

    /**
     * 单条命令的超时上限。
     *
     * **必须有超时。** [runProcess] 内部是阻塞式 `waitFor`，而 `su` 在应用退到后台后可能
     * 长时间不返回（授权对话框无法在后台弹出、root 守护进程被挂起等）。
     * 一旦挂住，调用它的采样循环就永远停在原地，且**不会自愈**——实测表现为
     * 「切到后台约 10 秒后所有实时数据冻结、通知不再刷新」，同时进程 CPU 增量归零、
     * 前台服务仍在运行，从外面看完全查不出原因。
     *
     * 超时后强杀子进程并返回失败结果，采样循环得以进入下一轮，最坏情况只是
     * 本轮读数缺失，而不是整个监控停摆。
     */
    const val COMMAND_TIMEOUT_MS = 5_000L

    /**
     * 执行一条 shell 命令。
     *
     * @param command 命令正文
     * @param root 是否要求提权。设备无任何提权通道时自动降级为普通 shell
     */
    suspend fun run(command: String, root: Boolean = true): CommandResult =
        withContext(Dispatchers.IO) {
            if (!root) {
                return@withContext LocalShellBackend.run(command, COMMAND_TIMEOUT_MS)
            }
            val backend = backendRef.get()
                ?: return@withContext CommandResult(false, "", "提权通道不可用")
            if (!backend.isAvailable()) {
                return@withContext CommandResult(false, "", "提权通道不可用")
            }
            backend.run(command, COMMAND_TIMEOUT_MS)
        }

    /**
     * 以指定进程参数执行命令并收集输出。
     *
     * 抽成公开工具方法供三个后端复用，避免各写一份读流逻辑——
     * 这里的并发读处理是踩过坑的（见下方注释），复制一份必然会漏掉修正。
     */
    suspend fun runProcess(argv: Array<String>, timeoutMs: Long): CommandResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder(*argv).apply {
                    redirectErrorStream(false)
                }.start()
                // stdout / stderr 必须**并发**读。串行的「先把 stdout 读到 EOF、再读 stderr」
                // 会在另一个管道被写满（约 64 KB）时互相死锁：父进程在等 stdout 关闭，
                // 子进程在等 stderr 被读走。
                //
                // 读取体**必须自己吞掉异常**：超时分支会 `destroyForcibly()` 关掉管道，
                // 此时挂起的 `readText()` 会抛 IOException。作为 `async` 子协程，
                // 这个异常会沿 Job 层级取消父协程、**绕过外层的 runCatching**，
                // 最终冒到采样循环之外把整个应用打崩（真机已复现过该崩溃）。
                val stdout = async(Dispatchers.IO) {
                    runCatching { process.inputStream.bufferedReader().readText() }
                        .getOrDefault("")
                }
                val stderr = async(Dispatchers.IO) {
                    runCatching { process.errorStream.bufferedReader().readText() }
                        .getOrDefault("")
                }
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    // 等子进程真正消失，管道的写端随之关闭，两个读协程即刻返回
                    runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
                    return@runCatching CommandResult(
                        success = false,
                        stdout = "",
                        stderr = "命令超时（${timeoutMs}ms），已强制结束",
                        exitCode = -1,
                    )
                }
                val code = process.exitValue()
                CommandResult(code == 0, stdout.await().trim(), stderr.await().trim(), code)
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
        val backend = backendRef.get() ?: return@withContext false
        if (!backend.isAvailable()) return@withContext false
        backend.writeNode(path, value, COMMAND_TIMEOUT_MS)
    }
}
