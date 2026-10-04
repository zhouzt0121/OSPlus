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
    private suspend fun readZram(): ZramStat {
        // **不能用 File.listFiles() 列举 /sys/block。**
        // 实测：应用身份与 shell 身份对 /sys/block/zram0/ 一律 Permission denied
        // （SELinux），listFiles() 返回 null → devices 为空 → 总容量恒为 0 →
        // 界面显示「ZRAM 未启用」，而设备实际有 12 GB zram。
        // 这是本项目在 sysfs 上反复踩的同一个坑（见 CpuDataSource / BatteryDataSource
        // 里的同类注释），必须交给 root shell 用 glob 展开。
        val script = buildString {
            append("for z in /sys/block/zram*; do n=\$(basename \$z); ")
            append("sz=\$(cat \$z/disksize 2>/dev/null); ")
            append("u=\$(cat \$z/mem_used_total 2>/dev/null); ")
            append("[ -z \"\$u\" ] && u=\$(cat \$z/mem_used 2>/dev/null); ")
            append("[ -z \"\$u\" ] && u=\$(cat \$z/mem_used_bytes 2>/dev/null); ")
            append("o=\$(cat \$z/orig_data_size 2>/dev/null); ")
            append("[ -d \$z ] && echo \"\$n|\$sz|\$u|\$o\"; done")
        }
        val out = Shell.run(script, root = true).stdout

        val devices = mutableListOf<String>()
        var total = 0L
        var used = 0L
        var orig = 0L
        out.lineSequence().forEach { line ->
            val cols = line.trim().split('|')
            val name = cols.getOrNull(0)?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            devices += name
            total += readKb(cols.getOrNull(1))
            used += readKb(cols.getOrNull(2))
            orig += readKb(cols.getOrNull(3))
        }
        return ZramStat(total, used, orig, devices.sorted())
    }

    /** 把节点原始字节值换算成 KB；空/非法一律 0 */
    private fun readKb(raw: String?): Long {
        val v = raw?.trim()?.toLongOrNull() ?: return 0L
        return if (v > 1_000_000L) v / 1024L else v
    }

    /**
     * 列出全部 zram 块设备名（`zram0`、`zram1`…）。
     *
     * 必须走 root shell 的 glob 展开：`File("/sys/block").listFiles()` 在
     * Android 12+ 被 SELinux 拒绝，恒返回 null。
     */
    private suspend fun listZramDevices(): List<String> {
        val out = Shell.run(
            "for z in /sys/block/zram*; do [ -d \$z ] && basename \$z; done",
            root = true,
        ).stdout
        return out.lineSequence().map { it.trim() }
            .filter { it.startsWith("zram") }
            .toList()
            .sorted()
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

    /** swapoff/swapon/mkswap 在部分 ROM 不在 PATH 里，按 Scene 的做法显式解析全路径 */
    private fun binPath(name: String): String {
        for (dir in listOf("/system/bin", "/vendor/bin")) {
            val f = File("$dir/$name")
            if (f.exists()) return "$dir/$name"
        }
        return name
    }

    /**
     * 当前处于活动状态的交换设备（/proc/swaps 第一列，形如 /dev/block/zram0）。
     *
     * **必须走 root cat**：Android 12+ 对普通应用 SELinux 拒读 /proc/swaps，
     * 直接 `File("/proc/swaps").readLines()` 拿到空串，会误判「没有交换设备」，
     * 这正是「清理交换分区」一直报未检测到的根因。
     */
    private suspend fun activeSwapDevices(): List<String> =
        Shell.run("cat /proc/swaps", root = true).stdout.lines()
            .drop(1)
            .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull() }
            .filter { it.isNotEmpty() && it != "Filename" }
            // 只取设备名：不同内核 /proc/swaps 第一列形态不一
            //（/dev/block/zram0、/block/zram0 都见过），直接拼进命令会 No such file；
            // 统一归一为 zramN，路径一律用 /dev/block/zramN 重新构造
            .map { it.substringAfterLast('/') }

    /**
     * 调整 ZRAM 大小（需要 root）。
     *
     * 对齐 Scene（swap-controller）的重建顺序：swapoff → reset → 写 disksize →
     * mkswap → swapon。此前版本在写 disksize 之后又补了一次 reset，把刚设好的
     * 容量清零，导致「应用并重建」后 zram 容量恒为 0。
     *
     * 只重建当前活动的交换设备；没有活动交换设备时仅写容量，不做 mkswap/swapon。
     *
     * [sizeKb] 为 0 表示**关闭**该 zram 交换设备：只关交换并清零容量，
     * 不执行 mkswap/swapon——0 字节的设备无法格式化，swapon 必然失败。
     */
    suspend fun resizeZram(sizeKb: Long): Boolean {
        // 同 readZram()：不能用 File.listFiles() 列举 /sys/block（SELinux 拒绝），
        // 否则 devices 恒为空、本函数直接 return false —— ZRAM 调整功能整体不可用。
        val devices = listZramDevices()
        if (devices.isEmpty()) return false
        val active = activeSwapDevices()
        val targets = devices.filter { it in active }.ifEmpty { devices }

        val swapoff = binPath("swapoff")
        val mkswap = binPath("mkswap")
        val swapon = binPath("swapon")
        var ok = true
        for (name in targets) {
            val dev = "/dev/block/$name"
            val cmd = if (sizeKb <= 0L) {
                "$swapoff $dev 2>/dev/null; " +
                    "echo 1 > /sys/block/$name/reset 2>/dev/null; " +
                    "echo 0 > /sys/block/$name/disksize"
            } else {
                "$swapoff $dev 2>/dev/null; " +
                    "echo 1 > /sys/block/$name/reset 2>/dev/null; " +
                    "echo ${sizeKb * 1024} > /sys/block/$name/disksize; " +
                    "$mkswap $dev >/dev/null 2>&1; " +
                    "$swapon $dev -p 0 >/dev/null 2>&1"
            }
            ok = ok && Shell.run(cmd, root = true).success
            // 写后回读校验：disksize 写入失败时节点仍为 0。
            // 走 Shell.readNode（直读优先，失败降级 root），与写路径的身份一致。
            val appliedRaw = Shell.readNode("/sys/block/$name/disksize", root = true)
            val applied = readKb(appliedRaw)
            if (applied !in (sizeKb - 1)..(sizeKb + 1)) ok = false
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
        val devices = activeSwapDevices()
        if (devices.isEmpty()) {
            return@withContext MemCleanResult(
                target = "交换分区",
                ok = false,
                freedKb = 0L,
                detail = "未检测到活动的交换设备",
            )
        }
        val swapoff = binPath("swapoff")
        val swapon = binPath("swapon")
        // 每台设备单独一条命令再串联：合并成一条时前一台 swapoff 失败
        // （例如 PATH 里找不到工具）会让后面的设备全部漏处理；
        // 路径由设备名重新构造，不沿用 /proc/swaps 的原始文本
        val cmd = devices.joinToString("; ") {
            val dev = "/dev/block/" + it.substringAfterLast('/')
            "$swapoff $dev; $swapon $dev"
        }
        val r = Shell.run(cmd, root = true)
        val after = read()
        val freed = (before.swapUsedKb - after.swapUsedKb).coerceAtLeast(0L)
        // 成功判定不能只看退出码：swapoff 对「已换出页较多」的 zram 可能
        // 超时返回非零，但换出页仍已回收。以 swapUsed 实际下降为准。
        val ok = freed > 0L || after.swapUsedKb == 0L
        MemCleanResult(
            target = "交换分区",
            ok = ok,
            freedKb = freed,
            detail = if (ok) "" else r.stderr.ifBlank { r.stdout.ifBlank { "swapoff/swapon 执行失败" } },
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
