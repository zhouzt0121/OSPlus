package com.osplus.tools.core

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Shizuku 后端：通过 Shizuku 的 shell 通道执行命令。
 *
 * ## 为什么不用 `Shizuku.newProcess` 之外的方式
 *
 * Shizuku 提供两类能力：
 * 1. **Binder 调用**——直接调用系统服务的隐藏接口（如 `IPackageManager`）。
 *    需要为每个服务写 AIDL stub，且只能做已有 API 能做的事。
 * 2. **`newProcess`**——以 shell 身份 fork 一个进程执行命令，等价于 `adb shell`。
 *
 * OSPlus 需要的是**读写 sysfs 节点**（`/sys/devices/system/cpu/` 下的各级目录），
 * 这既不是 Binder 能覆盖的（sysfs 不走 Binder），也不是标准 API，
 * 因此必须走 (2)。选它还有个附带好处：命令文本与 root 模式**完全一致**，
 * 各 DataSource 不需要为 Shizuku 分支改一行代码。
 *
 * ## 权限模型
 *
 * Shizuku 授予的身份是 `shell`（uid 2000），落在 `shell` SELinux 域：
 * - **可**：读 `/proc`、`/sys`，写 `/sys/devices/system/cpu/` 下的调频节点，
 *   跑 `dumpsys` / `am` / `top` / `pidof`
 * - **不可**：`/data/adb`（root 专属），因此性能调度页在 Shizuku 下不可用
 */
class ShizukuBackend : PrivilegeBackend {

    companion object {
        /** 权限请求的返回码，取值任意但必须与 [Shizuku.addRequestPermissionResultListener] 一致 */
        private const val REQUEST_CODE = 0x4F53 // "OS"

        /** IShizukuService.TRANSACTION_newProcess（见 [newProcess] 的说明） */
        private const val TRANSACTION_NEW_PROCESS = 14

        /** Shizuku 是否已安装（装了但没启动时要用不同的提示文案） */
        fun isInstalled(): Boolean = runCatching {
            Shizuku.getVersion() >= 0
        }.getOrDefault(false)
    }

    /** Shizuku 服务端是否正在运行 */
    fun isRunning(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    /** 是否已被授予 shell 权限 */
    fun isGranted(): Boolean = runCatching {
        isRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 该设备上 Shizuku 模式**是否可能**可用（已安装且服务在跑）。
     *
     * 与 [isAvailable] 的区别：这里不要求已授权。设置页用它来决定
     * 「Shizuku」这个选项要不要显示 / 是否可点，授权则是下一步的事。
     */
    fun isSupported(): Boolean = isInstalled() && isRunning()

    override suspend fun isAvailable(): Boolean = isGranted()

    override suspend fun run(command: String, timeoutMs: Long): CommandResult =
        withContext(Dispatchers.IO) {
            if (!isGranted()) {
                return@withContext CommandResult(false, "", "Shizuku 未授权")
            }
            try {
                coroutineScope {
                    // `Shizuku.newProcess` 在 13.1.5 上是 **private**（已用 javap 核对），
                    // 公开可用的等价入口是 `Shizuku.transactRemote`：
                    // 对 IShizukuService 的 Binder 发 TRANSACTION_newProcess 请求。
                    val process = newProcess(command)
                        ?: return@coroutineScope CommandResult(false, "", "Shizuku 无法创建进程")
                    // 读流必须并发（见 Shell.runProcess 的说明）；读体自己吞异常，
                    // 因为超时分支 destroy() 后挂起的 readText() 会抛 IOException，
                    // 让它冒出去会取消整个父协程、绕过调用方的 runCatching。
                    val stdoutJob = async(Dispatchers.IO) {
                        runCatching { process.inputStream.bufferedReader().readText() }
                            .getOrDefault("")
                    }
                    val stderrJob = async(Dispatchers.IO) {
                        runCatching { process.errorStream.bufferedReader().readText() }
                            .getOrDefault("")
                    }
                    // ShizukuRemoteProcess 自带带超时的 waitForTimeout，直接用，
                    // 不必像早期实现那样轮询 exitValue()（轮询既有延迟又占一个线程）。
                    val finished = runCatching {
                        process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    }.getOrDefault(false)
                    if (!finished) {
                        runCatching { process.destroy() }
                        CommandResult(false, "", "命令超时（${timeoutMs}ms），已强制结束", -1)
                    } else {
                        CommandResult(
                            success = process.exitValue() == 0,
                            stdout = stdoutJob.await(),
                            stderr = stderrJob.await(),
                            exitCode = process.exitValue(),
                        )
                    }
                }
            } catch (e: Throwable) {
                CommandResult(false, "", e.message ?: e.toString())
            }
        }

    /**
     * 通过 `transactRemote` 请求 Shizuku 服务 fork 一个进程。
     *
     * `newProcess` 的 AIDL 事务号无法从公开 API 取得（`IShizukuService` 是
     * `moe.shizuku.server` 包下的隐藏接口），因此这里按 Shizuku 客户端库
     * 长期稳定的约定取 14。若将来服务端调整事务号，失败会以
     * 「无法创建进程」的形式明确暴露，而不是静默返回空输出。
     */
    private fun newProcess(command: String): ShizukuRemoteProcess? = runCatching {
        val argv = arrayOf("/system/bin/sh", "-c", command)
        val data = android.os.Parcel.obtain()
        val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeStringArray(argv)
            data.writeStringArray(null) // env
            data.writeString(null)      // working dir
            Shizuku.transactRemote(data, reply, TRANSACTION_NEW_PROCESS)
            reply.readException()
            // 回程是 ShizukuRemoteProcess 的 Parcelable，无参构造不可见，只能走 CREATOR
            ShizukuRemoteProcess.CREATOR.createFromParcel(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }.getOrNull()

    /**
     * 请求 shell 权限。
     *
     * Shizuku 的授权是**异步 Binder 回调**：调用 `requestPermission` 后
     * 系统弹窗，用户点了才会通过 listener 回来。因此这里必须挂起等待回调，
     * 不能调用完就当作成功返回。
     *
     * @return true 表示用户已授权
     */
    suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
        if (isGranted()) return@withContext true
        if (!isRunning()) return@withContext false
        suspendCancellableCoroutine { cont ->
            // Shizuku 的回调接口是自定义 interface（非 java.util.function.Consumer），
            // 签名是 onRequestPermissionResult(requestCode, grantResult)。
            // holder 用来让注销逻辑拿到同一个实例——每次 new 一个 lambda
            // 会导致 remove 时找不到已注册的那个，listener 永久泄漏。
            val holder = arrayOfNulls<Shizuku.OnRequestPermissionResultListener>(1)
            fun unregister() {
                holder[0]?.let {
                    runCatching { Shizuku.removeRequestPermissionResultListener(it) }
                }
                holder[0] = null
            }
            val listener = Shizuku.OnRequestPermissionResultListener { code, _ ->
                if (code == REQUEST_CODE) {
                    unregister()
                    if (cont.isActive) cont.resume(isGranted())
                }
            }
            holder[0] = listener
            runCatching { Shizuku.addRequestPermissionResultListener(listener) }
            cont.invokeOnCancellation { unregister() }
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
        }
    }
}
