package com.osplus.tools.core

import android.os.Build
import com.osplus.tools.model.CpuCluster
import com.osplus.tools.model.CpuCoreInfo
import com.osplus.tools.model.CpuInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** CPU 信息读取与频率控制 */
object CpuDataSource {

    private const val CPU_BASE = "/sys/devices/system/cpu"

    /** 解析出在线的核心编号列表 */
    fun coreIndexes(): List<Int> {
        val possible = runCatching {
            File("$CPU_BASE/possible").readText().trim()
        }.getOrNull()
        if (!possible.isNullOrEmpty()) {
            return parseCpuList(possible)
        }
        val dir = File(CPU_BASE)
        return dir.listFiles()
            ?.mapNotNull { it.name.removePrefix("cpu").toIntOrNull() }
            ?.sorted()
            ?: emptyList()
    }

    /** 解析 "0-5,8" 或 "0 1 2 3 4 5" 形式的 CPU 列表 */
    private fun parseCpuList(raw: String): List<Int> {
        val result = mutableListOf<Int>()
        // related_cpus 在部分内核上以空格分隔，这里统一按逗号与空白切分
        raw.split(Regex("[,\\s]+")).filter { it.isNotBlank() }.forEach { part ->
            val p = part.trim()
            if (p.contains("-")) {
                val (a, b) = p.split("-", limit = 2)
                val from = a.toIntOrNull() ?: return@forEach
                val to = b.toIntOrNull() ?: return@forEach
                for (i in from..to) result += i
            } else {
                p.toIntOrNull()?.let { result += it }
            }
        }
        return result.sorted()
    }

    private fun readKhz(path: String): Long =
        runCatching { File(path).readText().trim().toLongOrNull() }.getOrNull() ?: -1L

    private fun readText(path: String): String =
        runCatching { File(path).readText().trim() }.getOrDefault("")

    /** 读取 CPU 全量信息 */
    suspend fun read(): CpuInfo = withContext(Dispatchers.IO) {
        val cores = coreIndexes()
        val load = readLoadPercent()

        val coreInfos = cores.map { idx ->
            CpuCoreInfo(
                index = idx,
                online = readText("$CPU_BASE/cpu$idx/online").let {
                    if (it.isEmpty()) true else it == "1"
                },
                curKhz = readKhz("$CPU_BASE/cpu$idx/cpufreq/scaling_cur_freq"),
                minKhz = readKhz("$CPU_BASE/cpu$idx/cpufreq/cpuinfo_min_freq"),
                maxKhz = readKhz("$CPU_BASE/cpu$idx/cpufreq/cpuinfo_max_freq"),
                governor = readText("$CPU_BASE/cpu$idx/cpufreq/scaling_governor"),
            )
        }

        val clusters = readClusters(coreInfos)

        CpuInfo(
            soc = readSoc(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "",
            coreCount = cores.size,
            clusters = clusters,
            cores = coreInfos,
            loadPercent = load,
            tempC = readTemperature(),
            uptimeSec = runCatching { File("/proc/uptime").readText().split(" ")[0].toFloat() }
                .getOrNull()?.toLong() ?: 0L,
            governors = readAvailableGovernors(cores.firstOrNull() ?: 0),
        )
    }

    /** 通过 cpufreq policy 目录聚合簇信息 */
    private fun readClusters(cores: List<CpuCoreInfo>): List<CpuCluster> {
        val byPolicy = linkedMapOf<String, List<Int>>()
        File(CPU_BASE).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("cpufreq/policy").not() && it.name.startsWith("cpufreq") }
        // policy 目录位于 /sys/devices/system/cpu/cpufreq/policyN
        File("$CPU_BASE/cpufreq").listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("policy") }
            ?.forEach { policy ->
                val related = parseCpuList(readText("${policy.absolutePath}/related_cpus"))
                byPolicy[policy.name] = related
            }

        if (byPolicy.isEmpty()) {
            // 退化：按最大频率分组
            return cores.groupBy { it.maxKhz }.entries.mapIndexed { i, e ->
                CpuCluster(
                    name = "簇 ${i + 1}",
                    cores = e.value.map { it.index },
                    curKhz = e.value.firstOrNull { it.curKhz > 0 }?.curKhz ?: -1L,
                    minKhz = e.value.firstOrNull()?.minKhz ?: -1L,
                    maxKhz = e.key,
                )
            }
        }

        return byPolicy.entries.mapIndexed { i, (name, related) ->
            val members = cores.filter { it.index in related }
            CpuCluster(
                name = "簇 ${i + 1} ($name)",
                cores = related,
                curKhz = members.firstOrNull { it.curKhz > 0 }?.curKhz ?: -1L,
                minKhz = members.firstOrNull()?.minKhz ?: -1L,
                maxKhz = members.maxOfOrNull { it.maxKhz } ?: -1L,
            )
        }
    }

    private fun readSoc(): String {
        val hw = runCatching {
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Hardware") }
                ?.substringAfter(":")?.trim()
        }.getOrNull()
        if (!hw.isNullOrEmpty()) return hw
        return Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: Build.BOARD
    }

    /** 依据 /proc/stat 两次采样计算总占用率 */
    private suspend fun readLoadPercent(): Float = withContext(Dispatchers.IO) {
        fun snapshot(): Pair<Long, Long> {
            val line = runCatching {
                File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") }
            }.getOrNull() ?: return 0L to 0L
            val parts = line.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }
            if (parts.size < 8) return 0L to 0L
            val idle = parts[3] + parts.getOrElse(4) { 0L }
            val total = parts.sum()
            return total to idle
        }
        val (t1, i1) = snapshot()
        if (t1 == 0L) return@withContext 0f
        kotlinx.coroutines.delay(260)
        val (t2, i2) = snapshot()
        val dt = t2 - t1
        val di = i2 - i1
        if (dt <= 0) return@withContext 0f
        ((dt - di) * 100f / dt).coerceIn(0f, 100f)
    }


    /** 快速读取：仅取频率，不做任何等待 */
    fun readFreqs(cores: List<Int> = coreIndexes()): List<Long> =
        cores.map { readKhz("$CPU_BASE/cpu$it/cpufreq/scaling_cur_freq") }


    /** 读取 CPU 温度，优先电池/CPU 专用节点 */
    private fun readTemperature(): Float? {
        val candidates = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/class/hwmon/hwmon0/temp1_input",
        )
        for (path in candidates) {
            val raw = runCatching { File(path).readText().trim().toFloatOrNull() }.getOrNull()
                ?: continue
            if (raw <= 0f) continue
            // 多数节点单位为 millidegree
            return if (raw > 1000f) raw / 1000f else raw
        }
        return null
    }

    private fun readAvailableGovernors(firstCore: Int): List<String> {
        val raw = readText("$CPU_BASE/cpu$firstCore/cpufreq/scaling_available_governors")
        return raw.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    /** 可用频率列表（Hz） */
    suspend fun availableFrequencies(core: Int): List<Long> = withContext(Dispatchers.IO) {
        readText("$CPU_BASE/cpu$core/cpufreq/scaling_available_frequencies")
            .split(Regex("\\s+"))
            .mapNotNull { it.toLongOrNull() }
            .filter { it > 0 }
            .sorted()
    }

    /**
     * 该核心所属调频策略组（policy）包含的全部核心。
     *
     * 现代 SoC 普遍以「簇」为单位管理 CPU 频率：同一簇内所有核心的
     * `scaling_max_freq` / `scaling_min_freq` 实际是**同一个文件**（inode 相同），
     * 写入任意一个核心都会影响整组。本方法用于在界面上如实展示影响范围。
     */
    suspend fun policyGroup(core: Int): List<Int> = withContext(Dispatchers.IO) {
        val raw = Shell.readNode("$CPU_BASE/cpu$core/cpufreq/related_cpus")
            ?: Shell.readNode("$CPU_BASE/cpufreq/policy$core/related_cpus")
            ?: return@withContext listOf(core)
        parseCpuList(raw).ifEmpty { listOf(core) }
    }

    /** 该核心的硬件频率区间（内核声明的 cpuinfo_min/max_freq，单位 kHz） */
    fun hardwareRange(core: Int): Pair<Long, Long> {
        val min = readKhz("$CPU_BASE/cpu$core/cpufreq/cpuinfo_min_freq")
        val max = readKhz("$CPU_BASE/cpu$core/cpufreq/cpuinfo_max_freq")
        return min to max
    }


    /** 频率写入结果：请求值、内核回读值、是否真正生效 */
    data class FreqApplyResult(
        val requestedKhz: Long,
        val appliedMinKhz: Long,
        val appliedMaxKhz: Long,
        /** true 表示这是「恢复自动」操作，界面提示文案不同 */
        val restored: Boolean = false,
    ) {
        /** 内核把请求值归一到最近挡位后可能略有出入，允许 1 个挡位的偏差 */
        val accepted: Boolean
            get() = appliedMaxKhz > 0L &&
                kotlin.math.abs(appliedMaxKhz - requestedKhz) <= requestedKhz / 10L
    }

    /** 读取当前生效的上下限（kHz） */
    suspend fun readLimits(core: Int): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val min = Shell.readNode("$CPU_BASE/cpu$core/cpufreq/scaling_min_freq")
            ?.toLongOrNull() ?: -1L
        val max = Shell.readNode("$CPU_BASE/cpu$core/cpufreq/scaling_max_freq")
            ?.toLongOrNull() ?: -1L
        min to max
    }

    /**
     * 把某个核心（实际是其所属簇）锁定到指定频率挡位。
     *
     * 做法是同时把上下限设为同一值——这样 governor 无法再上下浮动，
     * CPU 会稳定运行在该挡位。写入后**回读校验**：
     * 部分机型的某些簇会被内核/厂商策略接管，写入被静默忽略，
     * 只有回读才能判断是否真正生效。
     */
    suspend fun lockFrequency(core: Int, khz: Long): FreqApplyResult =
        withContext(Dispatchers.IO) {
            // 先放开上限再抬高下限，避免 max < min 导致内核拒绝
            Shell.run("echo $khz > $CPU_BASE/cpu$core/cpufreq/scaling_max_freq 2>/dev/null", root = true)
            Shell.run("echo $khz > $CPU_BASE/cpu$core/cpufreq/scaling_min_freq 2>/dev/null", root = true)
            Shell.run("echo $khz > $CPU_BASE/cpu$core/cpufreq/scaling_max_freq 2>/dev/null", root = true)
            // 同步到同簇其余核心
            Shell.run(
                "for c in \$(cat $CPU_BASE/cpu$core/cpufreq/related_cpus 2>/dev/null | tr ' ' '\\n'); do " +
                    "echo $khz > $CPU_BASE/cpu\$c/cpufreq/scaling_max_freq 2>/dev/null; " +
                    "echo $khz > $CPU_BASE/cpu\$c/cpufreq/scaling_min_freq 2>/dev/null; done",
                root = true,
            )
            val (min, max) = readLimits(core)
            FreqApplyResult(khz, min, max)
        }

    /** 恢复该簇的自动调频（下限=硬件最低，上限=硬件最高） */
    suspend fun restoreAuto(core: Int): FreqApplyResult = withContext(Dispatchers.IO) {
        val (hwMin, hwMax) = hardwareRange(core)
        val lo = if (hwMin > 0) hwMin else 384000L
        val hi = if (hwMax > 0) hwMax else 3532800L
        Shell.run("echo $hi > $CPU_BASE/cpu$core/cpufreq/scaling_max_freq 2>/dev/null", root = true)
        Shell.run("echo $lo > $CPU_BASE/cpu$core/cpufreq/scaling_min_freq 2>/dev/null", root = true)
        Shell.run(
            "for c in \$(cat $CPU_BASE/cpu$core/cpufreq/related_cpus 2>/dev/null | tr ' ' '\\n'); do " +
                "echo $hi > $CPU_BASE/cpu\$c/cpufreq/scaling_max_freq 2>/dev/null; " +
                "echo $lo > $CPU_BASE/cpu\$c/cpufreq/scaling_min_freq 2>/dev/null; done",
            root = true,
        )
        val (min, max) = readLimits(core)
        FreqApplyResult(hi, min, max, restored = true)
    }

    /**
     * 设置调速器（需要 root）。
     *
     * 注意：调频策略以簇为单位，写入后同簇核心会一并生效。
     */
    suspend fun setGovernor(core: Int, governor: String): Boolean {
        val ok = Shell.writeNode("$CPU_BASE/cpu$core/cpufreq/scaling_governor", governor)
        if (ok) {
            // 同步到同簇其余核心
            Shell.run(
                "for c in \$(cat $CPU_BASE/cpu$core/cpufreq/related_cpus 2>/dev/null | tr ' ' '\\n'); do " +
                    "echo $governor > $CPU_BASE/cpu\$c/cpufreq/scaling_governor 2>/dev/null; done",
                root = true,
            )
        }
        return ok
    }



}
