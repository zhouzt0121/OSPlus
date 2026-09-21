package com.osplus.tools.core

import com.osplus.tools.model.MemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 内存 / SWAP / ZRAM 读取 */
object MemDataSource {

    suspend fun read(): MemInfo = withContext(Dispatchers.IO) {
        val map = readMemInfo()
        val total = map["MemTotal"] ?: 0L
        val free = map["MemFree"] ?: 0L
        val avail = map["MemAvailable"] ?: free
        val buffers = map["Buffers"] ?: 0L
        val cached = (map["Cached"] ?: 0L) + (map["SwapCached"] ?: 0L)
        val used = (total - avail).coerceAtLeast(0L)

        val swapTotal = map["SwapTotal"] ?: 0L
        val swapFree = map["SwapFree"] ?: 0L
        val swapUsed = (swapTotal - swapFree).coerceAtLeast(0L)

        val zram = readZram()

        MemInfo(
            totalKb = total,
            availKb = avail,
            freeKb = free,
            usedKb = used,
            cachedKb = cached,
            buffersKb = buffers,
            swapTotalKb = swapTotal,
            swapUsedKb = swapUsed,
            zramTotalKb = zram.totalKb,
            zramUsedKb = zram.usedKb,
            zramOrigKb = zram.origKb,
            zramDevices = zram.devices,
        )
    }

    private fun readMemInfo(): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        runCatching {
            File("/proc/meminfo").readLines().forEach { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                    .split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: return@forEach
                out[key] = value
            }
        }
        return out
    }

    private data class ZramStat(
        val totalKb: Long,
        val usedKb: Long,
        val origKb: Long,
        val devices: List<String>,
    )

    /** 汇总所有 zram 设备的容量与占用 */
    private fun readZram(): ZramStat {
        val devices = File("/sys/block").listFiles()
            ?.filter { it.name.startsWith("zram") }
            ?.map { it.name }
            ?.sorted() ?: emptyList()

        var total = 0L
        var used = 0L
        var orig = 0L
        for (name in devices) {
            total += readNodeKb("/sys/block/$name/disksize")
            used += readNodeKb("/sys/block/$name/mem_used_total")
            orig += readNodeKb("/sys/block/$name/orig_data_size")
        }
        return ZramStat(total, used, orig, devices)
    }

    /** 节点单位可能是字节，统一换算为 KB */
    private fun readNodeKb(path: String): Long {
        val raw = runCatching { File(path).readText().trim() }.getOrNull() ?: return 0L
        val v = raw.toLongOrNull() ?: return 0L
        // disksize 以字节为单位，其余 zram 统计项也是字节
        return if (v > 1_000_000L) v / 1024L else v
    }

    /**
     * 快速读取：仅解析 /proc/meminfo，不走 shell。
     * 返回 (已用百分比 0~100, 已用 KB, 总 KB)。
     */
    fun readFast(): Triple<Float, Long, Long> {
        val map = readMemInfo()
        val total = map["MemTotal"] ?: 0L
        val avail = map["MemAvailable"] ?: map["MemFree"] ?: 0L
        if (total <= 0L) return Triple(0f, 0L, 0L)
        val used = (total - avail).coerceAtLeast(0L)
        return Triple(used * 100f / total, used, total)
    }

    /** 调整 ZRAM 大小（需要 root；改动将在下次重设时生效） */
    suspend fun resizeZram(sizeKb: Long): Boolean {
        val devices = File("/sys/block").listFiles()
            ?.filter { it.name.startsWith("zram") } ?: return false
        var ok = true
        for (d in devices) {
            ok = ok && Shell.run(
                "echo 1 > /sys/block/${d.name}/reset 2>/dev/null; " +
                    "echo ${sizeKb * 1024} > /sys/block/${d.name}/disksize 2>/dev/null; " +
                    "echo 1 > /sys/block/${d.name}/reset 2>/dev/null; " +
                    "mkswap /dev/block/${d.name} >/dev/null 2>&1; " +
                    "swapon /dev/block/${d.name} >/dev/null 2>&1",
                root = true,
            ).success
        }
        return ok
    }

    /**
     * 调整 swappiness，返回 (请求值, 内核回读值)。
     * 回读校验：该节点可能被内核参数或厂商策略限制，写入会被静默忽略。
     */
    suspend fun setSwappiness(value: Int): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            Shell.run("echo $value > /proc/sys/vm/swappiness", root = true)
            val applied = Shell.readNode("/proc/sys/vm/swappiness")?.toIntOrNull() ?: -1
            value to applied
        }

}
