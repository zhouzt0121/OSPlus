package com.osplus.tools.core

import android.content.Context
import com.osplus.tools.model.ProcessEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 进程列表读取。
 *
 * Android 12+ 起应用无法读取其他进程的 /proc/<pid> 节点，
 * 因此统一通过 root 执行 `top -b -n 1` 一次性获取全部进程的
 * CPU 占用、常驻内存与名称，避免多次采样带来的开销。
 */
object ProcessDataSource {

    /** top 输出的字段顺序必须与 [TOP_FIELDS] 保持一致 */
    private const val TOP_FIELDS = "%CPU,RES,PID,USER,S,ARGS"

    private val uidPattern = Regex("u(\\d+)_a(\\d+)")

    /** 本应用为采集数据而临时拉起的辅助进程，不应出现在列表中 */
    private val HELPER_NAMES = setOf("top", "ps")

    suspend fun list(context: Context, @Suppress("UNUSED_PARAMETER") sampleMs: Long = 0L): List<ProcessEntry> =
        withContext(Dispatchers.IO) {
            val raw = Shell.run(
                "top -b -n 1 -q -o $TOP_FIELDS 2>/dev/null",
                root = true,
            ).stdout
            if (raw.isBlank()) return@withContext emptyList()

            val pm = context.packageManager
            val pkgCache = HashMap<Int, String?>()

            raw.lineSequence()
                .mapNotNull { parseLine(it) }
                .map { (cpu, resKb, pid, user, state, args) ->
                    val uid = uidFromUser(user)
                    val pkg = if (uid >= 0) {
                        pkgCache.getOrPut(uid) {
                            runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
                        }
                    } else null
                    ProcessEntry(
                        pid = pid,
                        name = args.substringBefore(' ').ifBlank { args },
                        user = user,
                        cpuPercent = cpu,
                        rssKb = resKb,
                        state = state,
                        packageName = pkg,
                    )
                }
                .filterNot { it.name in HELPER_NAMES }
                .sortedByDescending { it.cpuPercent }
                .toList()
        }

    /** 解析一行 top 输出：cpu% res pid user state args... */
    internal fun parseLine(line: String): ParsedProcess? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val cols = trimmed.split(Regex("\\s+"), limit = 6)
        if (cols.size < 6) return null
        val cpu = cols[0].toFloatOrNull() ?: return null
        val resKb = parseRes(cols[1])
        val pid = cols[2].toIntOrNull() ?: return null
        return ParsedProcess(cpu, resKb, pid, cols[3], cols[4], cols[5])
    }

    internal data class ParsedProcess(
        val cpu: Float,
        val resKb: Long,
        val pid: Int,
        val user: String,
        val state: String,
        val args: String,
    )

    /** 解析 "142M" / "3.7M" / "9160" 形式的常驻内存 */
    internal fun parseRes(text: String): Long {
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
     */
    internal fun uidFromUser(user: String): Int = when (user) {
        "root" -> 0
        "system" -> 1000
        "shell" -> 2000
        "radio" -> 1001
        else -> {
            val m = uidPattern.find(user)
            if (m == null) {
                user.toIntOrNull() ?: -1
            } else {
                val userId = m.groupValues[1].toIntOrNull() ?: 0
                val appId = m.groupValues[2].toIntOrNull() ?: -1
                if (appId < 0) -1 else userId * 100_000 + 10_000 + appId
            }
        }
    }

    /** 结束进程（需要 root） */
    suspend fun kill(pid: Int): Boolean =
        Shell.run("kill -9 $pid", root = true).success

    /** 按包名强制停止应用 */
    suspend fun forceStop(pkg: String): Boolean =
        Shell.run("am force-stop $pkg", root = true).success
}
