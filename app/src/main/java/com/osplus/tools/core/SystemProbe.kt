package com.osplus.tools.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单次采样的原始快照。
 *
 * Android 12 起 /proc/stat、/proc/swaps、/proc/uptime 以及厂商 sysfs 节点
 * （kgsl / power_supply）对普通应用不可读，必须经由 root 读取。
 * 为把开销压到最低，这里把一秒钟需要的所有节点合并成 **一次** root 命令。
 */
data class ProbeTick(
    val cpuTotal: Long = 0L,
    val cpuIdle: Long = 0L,
    val coreTotals: LongArray = LongArray(0),
    val coreIdles: LongArray = LongArray(0),
    val gpuMhz: Long = -1L,
    val gpuLoad: Int = -1,
    val gpuMaxMhz: Long = -1L,
    val memUsedPercent: Float = 0f,
    val memTotalKb: Long = 0L,
    val memUsedKb: Long = 0L,
    val swapTotalKb: Long = 0L,
    val swapUsedKb: Long = 0L,
    val zramUsedKb: Long = 0L,
    val zramTotalKb: Long = 0L,
    /** 电池电压 mV */
    val voltageMv: Float = 0f,
    /** 瞬时电流（原始节点值，单位因机型而异） */
    val currentRaw: Long = 0L,
    /** 累计电量 µAh，用于通过增量推算真实电流 */
    val chargeCounterUah: Long = -1L,
    val batteryTempC: Float? = null,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

object SystemProbe {

    private const val SEP = "@@OSPLUS@@"

    /** 一次性读取所有指标所需的 root 脚本 */
    private fun buildScript(): String = buildString {
        appendLine("cat /proc/stat 2>/dev/null")
        appendLine("echo '$SEP'")
        appendLine("cat /proc/meminfo 2>/dev/null | head -n 12")
        appendLine("echo '$SEP'")
        appendLine("cat /proc/swaps 2>/dev/null")
        appendLine("echo '$SEP'")
        appendLine("for z in /sys/block/zram*/; do n=\$(basename \$z); echo \"\$n \$(cat \$z/disksize 2>/dev/null) \$(cat \$z/mem_used_total 2>/dev/null)\"; done")
        appendLine("echo '$SEP'")
        appendLine("cat /sys/class/kgsl/kgsl-3d0/clock_mhz 2>/dev/null")
        appendLine("cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null")
        appendLine("cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null")
        appendLine("echo '$SEP'")
        appendLine("cat /sys/class/power_supply/battery/current_now 2>/dev/null")
        appendLine("cat /sys/class/power_supply/battery/voltage_now 2>/dev/null")
        appendLine("cat /sys/class/power_supply/battery/temp 2>/dev/null")
        appendLine("cat /sys/class/power_supply/battery/charge_counter 2>/dev/null")
    }

    /**
     * 执行一次采样。
     *
     * 无 root 时退回直接文件读取（在部分机型/系统版本上仍可读到内存与 CPU 频率）。
     */
    suspend fun sample(): ProbeTick = withContext(Dispatchers.IO) {
        if (Shell.isRootAvailable()) {
            val out = Shell.run(buildScript(), root = true).stdout
            if (out.isNotBlank()) return@withContext parse(out)
        }
        parseFallback()
    }

    private fun parse(raw: String): ProbeTick {
        val parts = raw.split(SEP)
        val statPart = parts.getOrElse(0) { "" }
        val memPart = parts.getOrElse(1) { "" }
        val swapPart = parts.getOrElse(2) { "" }
        val zramPart = parts.getOrElse(3) { "" }
        val gpuPart = parts.getOrElse(4) { "" }
        val battPart = parts.getOrElse(5) { "" }

        // ---- /proc/stat ----
        var total = 0L
        var idle = 0L
        val coreT = ArrayList<Long>()
        val coreI = ArrayList<Long>()
        statPart.lineSequence().forEach { line ->
            if (!line.startsWith("cpu")) return@forEach
            val nums = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 5) return@forEach
            val i = nums[3] + nums.getOrElse(4) { 0L }
            val t = nums.sum()
            if (line.startsWith("cpu ")) {
                total = t; idle = i
            } else {
                coreT += t; coreI += i
            }
        }

        // ---- /proc/meminfo ----
        var memTotal = 0L
        var memAvail = 0L
        memPart.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim().split(Regex("\\s+"))
                .firstOrNull()?.toLongOrNull() ?: return@forEach
            when (key) {
                "MemTotal" -> memTotal = v
                "MemAvailable" -> memAvail = v
                "MemFree" -> if (memAvail == 0L) memAvail = v
            }
        }
        val memUsed = (memTotal - memAvail).coerceAtLeast(0L)
        val memPercent = if (memTotal > 0) memUsed * 100f / memTotal else 0f

        // ---- /proc/swaps ----
        var swapTotal = 0L
        var swapUsed = 0L
        swapPart.lineSequence().drop(1).forEach { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size >= 4) {
                swapTotal += cols[2].toLongOrNull() ?: 0L
                swapUsed += cols[3].toLongOrNull() ?: 0L
            }
        }

        // ---- zram ----
        var zramTotal = 0L
        var zramUsed = 0L
        zramPart.lineSequence().forEach { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size >= 3) {
                zramTotal += (cols[1].toLongOrNull() ?: 0L) / 1024L
                zramUsed += (cols[2].toLongOrNull() ?: 0L) / 1024L
            }
        }

        // ---- GPU ----
        val gpuLines = gpuPart.lineSequence().filter { it.isNotBlank() }.toList()
        val gpuMhz = gpuLines.getOrNull(0)?.trim()?.toLongOrNull() ?: -1L
        val gpuLoad = gpuLines.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: -1
        val gpuMaxHz = gpuLines.getOrNull(2)?.trim()?.toLongOrNull() ?: -1L

        // ---- 电池 ----
        val battLines = battPart.lineSequence().filter { it.isNotBlank() }.toList()
        val currentUa = battLines.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
        val voltageUv = battLines.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
        val tempRaw = battLines.getOrNull(2)?.trim()?.toFloatOrNull()
        val chargeCounter = battLines.getOrNull(3)?.trim()?.toLongOrNull() ?: -1L
        // voltage_now 单位为 µV
        val mv = if (voltageUv > 100_000L) voltageUv / 1000f else voltageUv.toFloat()
        val tempC = tempRaw?.let { if (it > 1000f) it / 1000f else if (it > 100f) it / 10f else it }

        return ProbeTick(
            cpuTotal = total,
            cpuIdle = idle,
            coreTotals = coreT.toLongArray(),
            coreIdles = coreI.toLongArray(),
            gpuMhz = gpuMhz,
            gpuLoad = gpuLoad,
            gpuMaxMhz = if (gpuMaxHz > 0) gpuMaxHz / 1_000_000L else -1L,
            memUsedPercent = memPercent,
            memTotalKb = memTotal,
            memUsedKb = memUsed,
            swapTotalKb = swapTotal,
            swapUsedKb = swapUsed,
            zramUsedKb = zramUsed,
            zramTotalKb = zramTotal,
            voltageMv = mv,
            currentRaw = currentUa,
            chargeCounterUah = chargeCounter,
            batteryTempC = tempC,
        )
    }

    /** 无 root 时的降级采样：仅读取应用可访问的节点 */
    private fun parseFallback(): ProbeTick {
        val mem = runCatching {
            File("/proc/meminfo").readLines().mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null
                else line.substring(0, i).trim() to
                    (line.substring(i + 1).trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L)
            }.toMap()
        }.getOrDefault(emptyMap())

        val total = mem["MemTotal"] ?: 0L
        val avail = mem["MemAvailable"] ?: mem["MemFree"] ?: 0L
        val used = (total - avail).coerceAtLeast(0L)
        return ProbeTick(
            memUsedPercent = if (total > 0) used * 100f / total else 0f,
            memTotalKb = total,
            memUsedKb = used,
        )
    }
}
