package com.osplus.tools.core

import android.content.Context
import com.osplus.tools.model.ThreadEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 线程级采样读取（线程监视器的数据源）。
 *
 * 与 [ProcessDataSource] 是同一套思路的两级粒度：进程级用 `top -b -n 1`，
 * 线程级用 `top -b -n 1 -H`（`-H` = 把每个线程当成独立任务列出）。
 *
 * ## 为什么走 root
 *
 * Android 12+ 起应用身份无法读取其他进程 `/proc/<pid>/task/<tid>` 下的
 * 线程节点，`top -H` 在应用身份下也拿不到别的 uid 的线程。
 * 因此统一经 root 执行一次，避免逐线程读节点造成几十次进程创建。
 *
 * ## 线程名从哪来（重要，踩过一次坑）
 *
 * **`top` 的 ARGS / NAME / CMDLINE 三个字段都不给线程名**，真机实测：
 *
 * | 进程 | `top -H` 的 ARGS | 真实线程名（`comm`） |
 * |---|---|---|
 * | surfaceflinger 的多个线程 | 全是 `surfaceflinger` | `TimerDispatch` / `RenderEngine` / … |
 * | zygote 的多个线程 | 全是 `zygote` | `HeapTaskDaemon` / `FinalizerDaemon` / … |
 * | zn-zygisk-companion64 | `zn-zygisk-companion64 zygisk_lsposed` | `zygisk_lsposed` |
 *
 * 也就是说：同一个进程的几十个线程在 `top` 里长得一模一样，只能靠 TID 区分。
 * 第一版据此假设 ARGS 里斜杠后是线程名，结果真机上从不出现斜杠，
 * 解析退化成「把 ARGS 按空格切第一个 token」——于是列表里出现
 * `data/adb/module` 这种被截断的路径片段，以及 `surfaceflinger#2509`
 * 这种「TID 恰好等于 PID 的主线程」。两种都毫无信息量。
 *
 * 正确来源是 **`/proc/<pid>/task/<tid>/comm`**：它直接给出内核记的线程名。
 * 关键是**可以合并进同一次 shell 往返**（见 [buildCommand]），
 * 不额外增加进程创建——这正是本数据源能保持 1 次调用的原因。
 *
 * ## 开销控制
 *
 * `top -H` 的输出行数等于整机线程总数（现代手机 3000~6000 行），
 * 比进程级的 300~600 行高一个数量级。因此：
 * 1. 排序与截断**在 shell 侧完成**（`sort -rn | head -N`），
 *    只把最终要显示的几行带回 Kotlin，避免把 5000 行文本跨进程搬运；
 * 2. 只为这 N 行读 `comm`，而不是为全部线程读。
 */
object ThreadDataSource {

    /**
     * 单次采样默认返回的线程条数上限。
     *
     * 悬浮窗最多显示 6~8 行，取 8 条留出过滤余量即可。
     */
    const val DEFAULT_LIMIT = 8

    /**
     * 读取当前 CPU 占用最高的若干线程。
     *
     * @param limit 返回条数上限；传 0 或负数表示不限（调试用，正式界面不要这么调）
     * @return 按 CPU 占用降序排列；root 不可用或命令失败时返回空列表
     *   （**空列表代表「读不到」而不是「没有线程在跑」**，界面应据此显示
     *   「需要 Root」而不是空面板）
     */
    suspend fun list(
        context: Context,
        @Suppress("UNUSED_PARAMETER") sampleMs: Long = 0L,
        limit: Int = DEFAULT_LIMIT,
    ): List<ThreadEntry> = withContext(Dispatchers.IO) {
        val take = if (limit > 0) limit else DEFAULT_LIMIT
        val raw = Shell.run(buildCommand(take), root = true).stdout
        if (raw.isBlank()) return@withContext emptyList()

        val pm = context.packageManager
        // uid → 包名缓存：同一次采样里同一应用会有几十个线程，
        // 不缓存的话每个线程都要走一次 PackageManager 查询（实测显著拖慢）
        val pkgCache = HashMap<Int, String?>()

        raw.lineSequence()
            .mapNotNull { parseLine(it) }
            .map { p ->
                val uid = uidFromUser(p.user)
                val pkg = if (uid >= 0) {
                    pkgCache.getOrPut(uid) {
                        runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
                    }
                } else null
                ThreadEntry(
                    tid = p.tid,
                    pid = p.pid,
                    threadName = p.threadName,
                    user = p.user,
                    cpuPercent = p.cpu,
                    rssKb = p.rssKb,
                    state = p.state,
                    packageName = pkg,
                )
            }
            .sortedByDescending { it.cpuPercent }
            .toList()
    }

    /**
     * 组装采集命令。
     *
     * 输出格式（`|` 分隔，Kotlin 侧按定宽字段解析）：
     * ```
     * <cpu%>|<res>|<pid>|<tid>|<user>|<state>|<processName>|<threadName>
     * ```
     *
     * 几处必须的细节：
     *
     * - **`sort -rn | head -N` 放在 shell 里**：`top -H` 有 5000 行，
     *   全量带回 Kotlin 再排序会把每次采样的 IPC 数据量放大两个数量级。
     *   注意 `top` 的首字符是空格（右对齐的 %CPU 列），
     *   `sort -r` 直接按文本比较会把 `" 9.0"` 排在 `"10.0"` 前面，
     *   必须用 **`-n` 数值模式**。
     * - **`awk` 提取 ARGS 的第一个 token 作为进程名**：ARGS 可能含空格
     *   （如 `zygote_next --name ...`），用 `read` 会把参数错切进后面的变量。
     *   进程名取第一个 token 即可，参数部分对悬浮窗没有价值。
     * - **`comm` 读线程名**：`/proc/<pid>/task/<tid>/comm` 是内核记的线程名，
     *   一个线程一个，比 `top` 的任何字段都准。
     * - **剔除 `top` / `sh` / `awk` / `sort` / `head` / `cat` 自身**：
     *   它们会以辅助进程身份出现在列表靠前位置。用 awk 按「进程名精确等于」
     *   过滤，而不是 `grep -v`（后者会把名字里含 `top` 的正常进程一起误杀，
     *   比如 `desktop`、`toolbox`）。
     */
    private fun buildCommand(take: Int): String {
        // 整段脚本里的 `$1` / `$pid` 等是 **shell 变量**，不是 Kotlin 模板，
        // 因此在 Kotlin 字符串里必须写成 `\$`。用 buildString 拼接时特别容易漏，
        // 漏掉的那行会被 Kotlin 编译器当成「引用了一个不存在的属性」报
        // `Unresolved reference`，错误位置指向脚本内容而非真实原因。
        return buildString {
            append("top -b -n 1 -H -q -o %CPU,RES,PID,TID,USER,S,ARGS 2>/dev/null ")
            append("| awk 'NR>1 && \$1 ~ /^[0-9.]+\$/ { ")
            append("pn=\$7; ")
            // 辅助进程过滤：只看第 7 列（进程名）的完整匹配
            append(
                "if (pn==\"top\"||pn==\"sh\"||pn==\"awk\"||pn==\"sort\"" +
                    "||pn==\"head\"||pn==\"cat\"||pn==\"ps\") next; ",
            )
            append("print \$1\"|\"\$2\"|\"\$3\"|\"\$4\"|\"\$5\"|\"\$6\"|\"pn }' ")
            append("| sort -t'|' -k1,1 -rn ")
            append("| head -$take ")
            append("| while IFS='|' read cpu res pid tid user state; do ")
            append("nm=\$(cat /proc/\$pid/task/\$tid/comm 2>/dev/null); ")
            append("[ -z \"\$nm\" ] && nm='?'; ")
            append("echo \"\$cpu|\$res|\$pid|\$tid|\$user|\$state|\$nm\"; ")
            append("done")
        }
    }

    /**
     * 解析一行：`cpu|res|pid|tid|user|state|threadName`
     *
     * 第 7 列是 `comm` 的原始内容，直接就是线程名。
     * 进程名不在这里出现——诊断信息由 [ThreadEntry] 的 `pid` 承担。
     */
    private fun parseLine(line: String): ParsedThread? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val cols = trimmed.split('|')
        if (cols.size < 7) return null
        val cpu = cols[0].toFloatOrNull() ?: return null
        val rssKb = parseRes(cols[1])
        val pid = cols[2].toIntOrNull() ?: return null
        val tid = cols[3].toIntOrNull() ?: return null
        val thread = cols[6].trim()
        if (thread.isEmpty()) return null
        return ParsedThread(
            cpu = cpu,
            rssKb = rssKb,
            pid = pid,
            tid = tid,
            user = cols[4],
            state = cols[5],
            threadName = thread,
        )
    }

    private data class ParsedThread(
        val cpu: Float,
        val rssKb: Long,
        val pid: Int,
        val tid: Int,
        val user: String,
        val state: String,
        val threadName: String,
    )

    /** 解析 "142M" / "3.7M" / "9160" 形式的常驻内存 */
    private fun parseRes(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0L
        val unit = t.last()
        val number = when (unit) {
            'K', 'k' -> t.dropLast(1).toFloatOrNull()?.times(1f)
            'M', 'm' -> t.dropLast(1).toFloatOrNull()?.times(1024f)
            'G', 'g' -> t.dropLast(1).toFloatOrNull()?.times(1024f * 1024f)
            else -> t.toFloatOrNull()
        }
        return number?.toLong() ?: 0L
    }

    /**
     * 由 top 的 USER 列反推 uid。
     *
     * 命名规则为 u{userId}_a{appId 去掉首位 1}，例如 uid 10525 → "u0_a525"。
     * 与 [ProcessDataSource] 保持完全一致——两处口径不同会导致
     * 「同一个进程在进程监视器里显示包名、在线程监视器里显示 uid」。
     */
    private fun uidFromUser(user: String): Int {
        return when (user) {
            "root" -> 0
            "system" -> 1000
            "shell" -> 2000
            "radio" -> 1001
            else -> {
                val m = UID_PATTERN.find(user)
                if (m == null) {
                    user.toIntOrNull() ?: -1
                } else {
                    val userId = m.groupValues[1].toIntOrNull() ?: 0
                    val appId = m.groupValues[2].toIntOrNull() ?: -1
                    if (appId < 0) -1 else userId * 100_000 + 10_000 + appId
                }
            }
        }
    }

    private val UID_PATTERN = Regex("u(\\d+)_a(\\d+)")
}
