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

    /**
     * 释放物理内存中的可回收缓存。
     *
     * `drop_caches` 只丢弃干净的页缓存 / 目录项 / inode 缓存，不触碰进程私有内存，
     * 因此不会导致后台应用被杀。释放量用 Cached 的差值衡量：`MemAvailable` 本身
     * 已把可回收缓存计为可用，拿它做差值几乎恒为 0，看不出效果。
     */
    suspend fun dropCaches(): MemCleanResult = withContext(Dispatchers.IO) {
        val before = read()
        val r = Shell.run("sync; echo 3 > /proc/sys/vm/drop_caches", root = true)
        val after = read()
        MemCleanResult(
            target = "物理内存",
            ok = r.success,
            freedKb = (before.cachedKb - after.cachedKb).coerceAtLeast(0L),
            detail = if (r.success) "" else r.stderr.ifBlank { r.stdout },
        )
    }

    /**
     * 重建交换分区以清空已用交换。
     *
     * 先关闭再重新启用每一个处于活动状态的交换设备（Android 上通常是 zram），
     * 使已换出的页回到物理内存、zram 占用归零。
     */
    suspend fun dropSwap(): MemCleanResult = withContext(Dispatchers.IO) {
        val before = read()
        val devices = Shell.run("cat /proc/swaps", root = true)
            .stdout.lines()
            .drop(1)
            .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull() }
            .filter { it.isNotEmpty() && it != "Filename" }
        if (devices.isEmpty()) {
            return@withContext MemCleanResult(
                target = "交换分区",
                ok = false,
                freedKb = 0L,
                detail = "未检测到活动的交换设备",
            )
        }
        val cmd = devices.joinToString("; ") {
            "swapoff $it 2>/dev/null; swapon $it 2>/dev/null"
        }
        val r = Shell.run(cmd, root = true)
        val after = read()
        MemCleanResult(
            target = "交换分区",
            ok = r.success,
            freedKb = (before.swapUsedKb - after.swapUsedKb).coerceAtLeast(0L),
            detail = if (r.success) "" else r.stderr.ifBlank { r.stdout },
        )
    }

}

/** 一次内存清理操作的结果，用于界面回执 */
data class MemCleanResult(
    /** 清理对象，如「物理内存」 */
    val target: String,
    val ok: Boolean,
    /** 实际释放量（KB）；无法量化时为 0 */
    val freedKb: Long,
    /** 失败原因，成功时为空串 */
    val detail: String,
)
