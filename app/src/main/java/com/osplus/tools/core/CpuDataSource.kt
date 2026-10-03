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

    /** 合并命令的分隔符——各平台共用一套，避免与节点内容撞车 */
    private const val SEP = "@@OSPLUS-CPU@@"

    /**
     * 解析出在线的核心编号列表。
     *
     * **不能只用 `File(CPU_BASE).listFiles()` 枚举目录**：Android 12+ 上
     * vendor 侧的 sysfs 目录对普通应用禁止列目录（SELinux），`listFiles()`
     * 会返回 null，导致核心数被判定为 0、整个 CPU 页面空白。
     * 这与 BatteryDataSource 上踩到的是同一个陷阱。
     *
     * 因此改为「root shell 读取 + 多路径兜底」：
     *  1. `possible` —— 标准路径，最可靠；
     *  2. `present` —— 部分内核只提供这个；
     *  3. root shell 遍历目录名 —— 前两者都缺失时的兜底。
     */
    suspend fun coreIndexes(): List<Int> = withContext(Dispatchers.IO) {
        // 多路径依次尝试，任一成功即返回
        val candidates = listOf("$CPU_BASE/possible", "$CPU_BASE/present")
        for (path in candidates) {
            val raw = Shell.readNode(path) ?: continue
            val parsed = parseCpuList(raw)
            if (parsed.isNotEmpty()) return@withContext parsed
        }
        // 兜底：交给 root shell 遍历，绕开 listFiles() 的列目录限制
        val listed = Shell.run(
            "ls -1 $CPU_BASE 2>/dev/null | grep -E '^cpu[0-9]+$' | " +
                "sed 's/^cpu//' | sort -n | tr '\\n' ' '",
            root = true,
        ).stdout
        parseCpuList(listed)
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

    /** 把 kHz 文本转成数值，无法解析或非正数统一记为 -1（哨兵值） */
    private fun khzOf(raw: String?): Long =
        raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: -1L

    /**
     * 一次性读取全部 CPU 相关节点。
     *
     * **为什么必须合并成一条 root 命令**：参考 BatteryDataSource 的做法——
     * 逐节点调用 `su -c` 会为每个节点创建一个进程，CPU 数量多时（联发科常见
     * 8~10 核，每核 5 个节点）单轮就是几十次进程创建，既慢又容易触发
     * 厂商的进程监控。合并后每轮只需 1 次。
     *
     * 每个节点都用 `[ -f ]` 先判存在再输出：Android 12+ 上目录不可列举，
     * 无法用 `File.exists()` 判断，而直接 `cat` 一个不存在的节点会把
     * 错误信息混进 stdout 的解析流里。
     *
     * @return 节点路径 -> 内容（空串表示该节点不存在或不可读）
     */
    private suspend fun readNodes(cores: List<Int>): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (cores.isEmpty()) return@withContext emptyMap()
            val paths = mutableListOf<String>()
            paths += "$CPU_BASE/possible"
            paths += "$CPU_BASE/present"
            paths += "/proc/uptime"
            // 温度：联发科常见 thermal_zone0，高通在 hwmon；两条都试
            paths += "/sys/class/thermal/thermal_zone0/temp"
            paths += "/sys/class/hwmon/hwmon0/temp1_input"
            paths += "/proc/cpuinfo"
            for (c in cores) {
                paths += "$CPU_BASE/cpu$c/online"
                paths += "$CPU_BASE/cpu$c/cpufreq/scaling_cur_freq"
                paths += "$CPU_BASE/cpu$c/cpufreq/cpuinfo_min_freq"
                paths += "$CPU_BASE/cpu$c/cpufreq/cpuinfo_max_freq"
                paths += "$CPU_BASE/cpu$c/cpufreq/scaling_governor"
            }
            // 逐个走 Shell.readNode —— 它内部是「直读优先，失败再降级 shell」。
            //
            // 这里曾经为了减少 su 调用，改成「一条 root 命令批量 cat 所有节点」。
            // 那个优化有严重副作用：**没有提权通道时整条命令失败，返回空 map，
            // 连本来应用身份就能直读的 CPU 频率都一起丢掉**。
            // 实测 uid 10548（无任何提权）可直读：
            //   scaling_cur_freq / cpuinfo_max_freq / scaling_governor /
            //   thermal_zone0/temp / /proc/uptime / /proc/cpuinfo
            // 不可直读的只有 /proc/stat、loadavg、kgsl 这几类。
            // Metric 在非 root 下能显示 CPU 频率，走的也是直读这条路。
            //
            // 代价是约 50 次本地文件读，比一次 shell 往返更便宜。
            val result = linkedMapOf<String, String>()
            for (p in paths) {
                val v = Shell.readNode(p, root = true)?.trim().orEmpty()
                if (v.isNotEmpty()) result[p] = v
            }
            result
        }

    /** 读取 CPU 全量信息 */
    suspend fun read(): CpuInfo = withContext(Dispatchers.IO) {
        val cores = coreIndexes()
        val nodes = readNodes(cores)
        val load = readLoadPercent()

        val coreInfos = cores.map { idx ->
            CpuCoreInfo(
                index = idx,
                // online 节点不存在时（部分内核不导出）视为在线
                online = nodes["$CPU_BASE/cpu$idx/online"]
                    ?.takeIf { it.isNotEmpty() }?.let { it == "1" } ?: true,
                curKhz = khzOf(nodes["$CPU_BASE/cpu$idx/cpufreq/scaling_cur_freq"]),
                minKhz = khzOf(nodes["$CPU_BASE/cpu$idx/cpufreq/cpuinfo_min_freq"]),
                maxKhz = khzOf(nodes["$CPU_BASE/cpu$idx/cpufreq/cpuinfo_max_freq"]),
                governor = nodes["$CPU_BASE/cpu$idx/cpufreq/scaling_governor"].orEmpty(),
            )
        }

        val clusters = readClusters(coreInfos)

        CpuInfo(
            soc = readSoc(nodes),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "",
            coreCount = cores.size,
            clusters = clusters,
            cores = coreInfos,
            loadPercent = load,
            tempC = readTemperature(nodes),
            uptimeSec = nodes["/proc/uptime"]
                ?.split(" ")?.firstOrNull()?.toFloatOrNull()?.toLong() ?: 0L,
            governors = readAvailableGovernors(cores.firstOrNull() ?: 0),
        )
    }

    /** 通过 cpufreq policy 目录聚合簇信息 */
    private suspend fun readClusters(cores: List<CpuCoreInfo>): List<CpuCluster> {
        // policy 目录位于 /sys/devices/system/cpu/cpufreq/policyN。
        // 注意不能依赖 File.listFiles() 列目录（SELinux 会拒绝），改用 root shell。
        val byPolicy = linkedMapOf<String, List<Int>>()
        // 先直读目录列表：实测应用身份可以 ls sysfs 的 cpufreq 目录（返回 policy0/policy6），
        // 不必经提权通道。原来直接走 root ls，在没有提权通道时会失败，
        // 簇信息整个丢失——而它本来读得到。
        val policyDirs = runCatching {
            File("$CPU_BASE/cpufreq").listFiles()
                ?.map { it.name }
                ?.filter { it.matches(Regex("policy[0-9]+")) }
                ?.sortedBy { it.removePrefix("policy").toIntOrNull() ?: 0 }
        }.getOrNull().orEmpty().takeIf { it.isNotEmpty() }
            ?: Shell.run(
                "ls -1 $CPU_BASE/cpufreq 2>/dev/null | grep -E '^policy[0-9]+$' | sort -V",
                root = true,
            ).stdout.split(Regex("\\s+")).filter { it.isNotBlank() }

        for (name in policyDirs) {
            val related = Shell.readNode("$CPU_BASE/cpufreq/$name/related_cpus")
                ?.let { parseCpuList(it) } ?: continue
            if (related.isNotEmpty()) byPolicy[name] = related
        }

        if (byPolicy.isEmpty()) {
            // 退化：按最大频率分组（联发科部分内核不导出 cpufreq/policy 目录）
            return cores.filter { it.maxKhz > 0 }
                .groupBy { it.maxKhz }.entries
                .sortedBy { it.key }
                .mapIndexed { i, e ->
                    CpuCluster(
                        name = "簇 ${i + 1}",
                        cores = e.value.map { it.index },
                        curKhz = e.value.firstOrNull { it.curKhz > 0 }?.curKhz ?: -1L,
                        minKhz = e.value.firstOrNull { it.minKhz > 0 }?.minKhz ?: -1L,
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
                minKhz = members.firstOrNull { it.minKhz > 0 }?.minKhz ?: -1L,
                maxKhz = members.maxOfOrNull { it.maxKhz } ?: -1L,
            )
        }
    }

    /**
     * 芯片型号。
     *
     * 联发科机型的 `/proc/cpuinfo` 里 **通常没有 `Hardware` 行**
     * （高通/三星多有），这是"读不到 SoC"的直接原因之一。
     * 因此补三条兜底：`model name` 行、`Build.SOC_MODEL`、`Build.BOARD`。
     */
    private fun readSoc(nodes: Map<String, String>): String {
        val cpuinfo = nodes["/proc/cpuinfo"].orEmpty()
        if (cpuinfo.isNotEmpty()) {
            // 优先 Hardware，其次 model name（联发科常用后者）
            for (key in listOf("Hardware", "model name", "Processor", "chip")) {
                val line = cpuinfo.lines().firstOrNull { it.startsWith(key) }
                val v = line?.substringAfter(":")?.trim().orEmpty()
                if (v.isNotEmpty()) return v
            }
        }
        return Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            ?: Build.BOARD.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            ?: "未知"
    }

    /** 依据 /proc/stat 两次采样计算总占用率 */
    private suspend fun readLoadPercent(): Float = withContext(Dispatchers.IO) {
        // /proc/stat 在 Android 12+ 上对应用不可直读，必须经 Shell（必要时提权）。
        // Shell.readNode 内部会先尝试直读，失败再走 root，避免无 root 设备白白多开进程。
        suspend fun snapshot(): Pair<Long, Long> {
            val content = Shell.readNode("/proc/stat") ?: return 0L to 0L
            val line = content.lines().firstOrNull { it.startsWith("cpu ") } ?: return 0L to 0L
            val parts = line.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }
            if (parts.size < 4) return 0L to 0L
            val idle = parts[3] + parts.getOrElse(4) { 0L }
            val total = parts.sum()
            return total to idle
        }
        val (t1, i1) = snapshot()
        // 读不到 /proc/stat 时返回 -1（不可读哨兵），不是 0。
        // 「读不到」与「占用就是 0」是两回事：后者会让人以为 CPU 闲着，
        // 而真实原因是没有提权通道。界面据此显示「不可读」。
        if (t1 == 0L) return@withContext -1f
        kotlinx.coroutines.delay(260)
        val (t2, i2) = snapshot()
        val dt = t2 - t1
        val di = i2 - i1
        if (dt <= 0) return@withContext -1f
        ((dt - di) * 100f / dt).coerceIn(0f, 100f)
    }


    /**
     * 快速读取：仅取各核当前频率，不做任何等待。
     *
     * 保持同步签名（调用方在采样循环里高频使用），因此这里只做直读尝试，
     * 读不到就记 -1，不阻塞等 root。需要可靠读数的场景请用 [read]。
     */
    fun readFreqs(cores: List<Int> = emptyList()): List<Long> {
        val list = cores.ifEmpty { readText("$CPU_BASE/possible").let { parseCpuList(it) } }
        return list.map { readKhz("$CPU_BASE/cpu$it/cpufreq/scaling_cur_freq") }
    }


    /**
     * 读取 CPU 温度。
     *
     * 单位判定不能只看数值大小：不同平台差异很大——
     *  - 高通/多数内核：millidegree（如 `45000` = 45℃）
     *  - 联发科部分节点：直接输出摄氏度（如 `45`）
     *  - hwmon：有些输出千分之一度，有些是温度值本身
     *
     * 这里改成按**节点路径语义**判断，而不是猜数值量级：
     * `thermal_zoneN/temp` 按规范是 millidegree，直接除以 1000；
     * `hwmon/…/tempN_input` 同样是 millidegree。
     * 若除完落在合理区间（-20~150）则采用，否则回退原值。
     */
    private fun readTemperature(nodes: Map<String, String>): Float? {
        val candidates = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/hwmon/hwmon0/temp1_input",
        )
        for (path in candidates) {
            val raw = nodes[path]?.toFloatOrNull() ?: continue
            if (raw <= 0f) continue
            val scaled = if (raw > 1000f) raw / 1000f else raw
            // 合理性校验：手机 SoC 温度不会超出这个区间，越界说明单位判断错了
            if (scaled in -20f..150f) return scaled
        }
        return null
    }

    private suspend fun readAvailableGovernors(firstCore: Int): List<String> {
        // 部分机型在 policy 目录下才有该节点，两处都试
        val raw = Shell.readNode("$CPU_BASE/cpu$firstCore/cpufreq/scaling_available_governors")
            ?: Shell.readNode("$CPU_BASE/cpufreq/policy$firstCore/scaling_available_governors")
            ?: return emptyList()
        return raw.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    /** 可用频率列表（Hz） */
    suspend fun availableFrequencies(core: Int): List<Long> = withContext(Dispatchers.IO) {
        // 节点可能在 cpuN/cpufreq 或 cpufreq/policyN 下
        val raw = Shell.readNode("$CPU_BASE/cpu$core/cpufreq/scaling_available_frequencies")
            ?: Shell.readNode("$CPU_BASE/cpufreq/policy$core/scaling_available_frequencies")
            ?: return@withContext emptyList()
        raw.split(Regex("\\s+"))
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
        // 路径 1：cpuN/cpufreq/related_cpus（高通常见）
        Shell.readNode("$CPU_BASE/cpu$core/cpufreq/related_cpus")
            ?.let { parseCpuList(it) }?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        // 路径 2：cpufreq/policyN/related_cpus（联发科常见）。
        // 注意 policy 编号不保证等于 core 编号，因此遍历全部 policy 目录，
        // 找出 related_cpus 包含本 core 的那一个。
        // 目录列表同样直读优先，理由见 readClusters
        val policyDirs = runCatching {
            File("$CPU_BASE/cpufreq").listFiles()
                ?.map { it.name }
                ?.filter { it.matches(Regex("policy[0-9]+")) }
                ?.sortedBy { it.removePrefix("policy").toIntOrNull() ?: 0 }
        }.getOrNull().orEmpty().takeIf { it.isNotEmpty() }
            ?: Shell.run(
                "ls -1 $CPU_BASE/cpufreq 2>/dev/null | grep -E '^policy[0-9]+$' | sort -V",
                root = true,
            ).stdout.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (name in policyDirs) {
            val related = Shell.readNode("$CPU_BASE/cpufreq/$name/related_cpus")
                ?.let { parseCpuList(it) } ?: continue
            if (core in related) return@withContext related
        }
        listOf(core)
    }

    /**
     * 该核心的硬件频率区间（内核声明的 cpuinfo_min/max_freq，单位 kHz）。
     *
     * 改为挂起函数：这两个节点在 Android 12+ 上常需 root 才能读到，
     * 而 [restoreAuto] 会用返回的上限去写 `scaling_max_freq`——
     * 若直读失败退回硬编码值，会在联发科机型上把上限设成错误的挡位。
     * 因此这里必须尝试 root，读不到才返回 -1 交由调用方兜底。
     */
    suspend fun hardwareRange(core: Int): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val min = khzOf(Shell.readNode("$CPU_BASE/cpu$core/cpufreq/cpuinfo_min_freq"))
        val max = khzOf(Shell.readNode("$CPU_BASE/cpu$core/cpufreq/cpuinfo_max_freq"))
        min to max
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

    /** 读取当前生效的上下限（kHz），不可读时返回 -1 */
    suspend fun readLimits(core: Int): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val min = khzOf(Shell.readNode("$CPU_BASE/cpu$core/cpufreq/scaling_min_freq"))
        val max = khzOf(Shell.readNode("$CPU_BASE/cpu$core/cpufreq/scaling_max_freq"))
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
            // 用 policyGroup 解析出的显式核心列表，避免依赖 cpuN/cpufreq/related_cpus
            // （联发科部分内核只在 cpufreq/policyN 下提供该节点）
            for (c in policyGroup(core)) {
                Shell.writeNode("$CPU_BASE/cpu$c/cpufreq/scaling_max_freq", khz.toString())
                Shell.writeNode("$CPU_BASE/cpu$c/cpufreq/scaling_min_freq", khz.toString())
            }
            val (min, max) = readLimits(core)
            FreqApplyResult(khz, min, max)
        }

    /** 恢复该簇的自动调频（下限=硬件最低，上限=硬件最高） */
    suspend fun restoreAuto(core: Int): FreqApplyResult = withContext(Dispatchers.IO) {
        val (hwMin, hwMax) = hardwareRange(core)
        // 兜底值刻意不写死具体挡位：各平台差异极大（联发科天玑常见 2.0~3.0GHz，
        // 高通旗舰可到 4.0GHz+），写死会在异平台上把频率锁到错误的挡位。
        // 读不到硬件区间时改用「放开到可写范围内的极值」策略：
        // 上限用整数上限交给内核自行归一，下限用 1 让 governor 完全自由。
        val lo = if (hwMin > 0) hwMin else 1L
        val hi = if (hwMax > 0) hwMax else Long.MAX_VALUE
        val writeMax = if (hi == Long.MAX_VALUE) {
            // 尝试从内核的 available_frequencies 取最大挡位
            Shell.readNode("$CPU_BASE/cpu$core/cpufreq/scaling_available_frequencies")
                ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.maxOrNull()
                ?: Shell.readNode("$CPU_BASE/cpu$core/cpufreq/cpuinfo_max_freq")
                    ?.trim()?.toLongOrNull()
        } else hi

        if (writeMax == null || writeMax <= 0) {
            // 完全无法确定上限：不写 max，避免锁错；只把 min 放开让 governor 自由
            Shell.run("echo $lo > $CPU_BASE/cpu$core/cpufreq/scaling_min_freq 2>/dev/null", root = true)
            val (min, max) = readLimits(core)
            return@withContext FreqApplyResult(-1L, min, max, restored = true)
        }

        Shell.run("echo $writeMax > $CPU_BASE/cpu$core/cpufreq/scaling_max_freq 2>/dev/null", root = true)
        Shell.run("echo $lo > $CPU_BASE/cpu$core/cpufreq/scaling_min_freq 2>/dev/null", root = true)
        // 用 policyGroup 解析出的显式核心列表，避免依赖 cpuN/cpufreq/related_cpus
        // （联发科部分内核只在 cpufreq/policyN 下提供该节点）
        for (c in policyGroup(core)) {
            Shell.writeNode("$CPU_BASE/cpu$c/cpufreq/scaling_max_freq", writeMax.toString())
            Shell.writeNode("$CPU_BASE/cpu$c/cpufreq/scaling_min_freq", lo.toString())
        }
        val (min, max) = readLimits(core)
        FreqApplyResult(writeMax, min, max, restored = true)
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
            for (c in policyGroup(core)) {
                Shell.writeNode("$CPU_BASE/cpu$c/cpufreq/scaling_governor", governor)
            }
        }
        return ok
    }



}
