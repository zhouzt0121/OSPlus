package com.osplus.tools.core.adb

import android.content.Context
import com.osplus.tools.core.CommandResult
import com.osplus.tools.core.PrivilegeBackend
import com.osplus.tools.core.PrivilegeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「电脑 ADB」模式的执行通道。
 *
 * ## 它在解决什么
 *
 * 无线 ADB 模式下，应用自己就是 ADB 客户端，命令随时能发。
 * 但「电脑 ADB」模式里，连着设备的是用户的电脑 —— 应用手上没有任何连接，
 * 光靠自己是发不出 shell 命令的。
 *
 * 因此 `up.sh` 会额外拉起一个常驻的、以 shell 身份运行的循环进程（daemon），
 * 本类就是应用侧与它对话的那一半：
 *
 * ```
 *   应用 ──写 req──> .daemon/req
 *                        │ daemon（shell, uid 2000）取走执行
 *                        ▼
 *   应用 <──读 res── .daemon/res   末尾追加 OSPLUS_EXIT=<退出码>
 *                        │
 *                    .daemon/beat   daemon 每轮刷新的心跳，供存活判断
 * ```
 *
 * ## 为什么交换目录放在应用外部私有目录
 *
 * 这是唯一「应用写得了、shell 也读得到」的落点 —— 该目录属组是 `ext_data_rw`，
 * 而 shell 恰好在这个组里。
 *
 * 反过来，若把交换目录开在 `/data/local/tmp` 并 `chmod 777`，
 * 任何应用都能往里写命令，那等于开放了一个「任意 App 以 shell 身份执行代码」的
 * 提权口子。放在外部私有目录则只有本应用能写，其它应用无路可走。
 *
 * ## 代价
 *
 * 轮询带来约 0.2 秒延迟，不适合高频采样（例如每秒读一次 CPU 频率）。
 * 高频场景应走无线 ADB 或 Root；本模式的定位是「没有 WiFi / 无法配对时也能用」的兜底。
 *
 * ## 生命周期
 *
 * daemon 由用户从电脑执行 `up.sh` 拉起，设备重启后失效，需要重新执行一次。
 * 这与无线调试「重启后要重新配对」是同一类约束，界面上应如实提示。
 */
class DaemonBackend(private val context: Context) : PrivilegeBackend {

    private companion object {
        const val TAG = "DaemonBackend"
        const val DIR_NAME = ".daemon"
        /** 心跳超过这个秒数没更新，就认为 daemon 已经没了 */
        const val HEARTBEAT_MAX_AGE_SEC = 15L
        const val POLL_INTERVAL_MS = 60L
        const val EXIT_MARKER = "OSPLUS_EXIT="
    }

    private val dir: File?
        get() = context.getExternalFilesDir(null)?.parentFile?.let { File(it, DIR_NAME) }

    /**
     * daemon 是否还活着。
     *
     * 判据是心跳文件的时间戳，而不是「有没有进程」——
     * 应用既没有权限遍历 shell 的进程，也没有稳定可靠的 pgrep。
     */
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val d = dir ?: return@withContext false
        val beat = File(d, "beat")
        if (!beat.exists()) return@withContext false
        val ts = runCatching { beat.readText().trim().toLongOrNull() }.getOrNull()
            ?: return@withContext false
        val age = System.currentTimeMillis() / 1000 - ts
        val alive = age in 0..HEARTBEAT_MAX_AGE_SEC
        if (!alive) {
            PrivilegeLog.d(TAG, "daemon 心跳过期：${age}s 前更新")
        }
        alive
    }

    /**
     * daemon 一次只处理一条命令，并发调用会让后写的 req 覆盖前一条，
     * 被覆盖的那条永远拿不到结果（表现为「某项数据偶尔读不出来」）。
     * 概览页每秒会并发读 CPU / GPU / 内存 / 电池多个数据源，必须串行化。
     */
    private val requestLock = Mutex()

    /**
     * 把一条命令交给 daemon 执行。
     *
     * 流程是「清旧结果 → 原子写请求 → 轮询到结束标记」。
     * 必须先删旧 res：否则上一轮留下的输出会被当成这一轮的结果，
     * 表现为「命令明明改了，返回值却没变」。
     */
    override suspend fun run(command: String, timeoutMs: Long): CommandResult =
        withContext(Dispatchers.IO) {
            val d = dir ?: return@withContext CommandResult(false, "", "外部存储不可用")
            requestLock.withLock {
                val req = File(d, "req")
                val res = File(d, "res")
                val tmp = File(d, "req.tmp")

                runCatching { res.delete() }

                // 先写临时文件再 rename —— rename 在同一分区内是原子的，
                // daemon 只会看到「没有请求」或「完整请求」，
                // 不会读到「正在写入、只写了一半」的命令。
                val written = runCatching {
                    tmp.writeText(command)
                    tmp.renameTo(req)
                }.getOrDefault(false)
                if (!written) {
                    return@withLock CommandResult(false, "", "写入请求失败：无法访问交换目录")
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (res.exists()) {
                        // 每轮重读：命令输出是 daemon 逐步写进去的，
                        // 缓存一次会在长输出时读到半截内容
                        val text = runCatching { res.readText() }.getOrNull().orEmpty()
                        val idx = text.lastIndexOf(EXIT_MARKER)
                        if (idx >= 0) {
                            val out = text.substring(0, idx).trimEnd()
                            val code = text.substring(idx + EXIT_MARKER.length)
                                .trim().toIntOrNull() ?: -1
                            return@withLock CommandResult(true, out, "", code)
                        }
                    }
                    delay(POLL_INTERVAL_MS)
                }
                return@withLock CommandResult(
                    false, "",
                    "执行超时（${timeoutMs}ms）：守护进程可能已停止，请重新运行激活脚本",
                )
            }
        }

    /** 交换目录是否存在（用于区分「没激活过」和「daemon 掉了」） */
    fun isProvisioned(): Boolean = dir?.exists() == true
}
