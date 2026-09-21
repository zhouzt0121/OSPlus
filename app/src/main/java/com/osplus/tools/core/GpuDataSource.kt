package com.osplus.tools.core

import com.osplus.tools.model.GpuInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GPU 信息读取与频率控制。
 *
 * 优先适配高通 Adreno（kgsl-3d0），其次回落到通用 devfreq。
 * 厂商节点在 Android 12+ 对普通应用不可读，因此统一通过 [Shell.readNode] 读取（必要时走 root）。
 */
object GpuDataSource {

    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    /** 部分内核把 kgsl 挂在通用 devfreq 目录下，命名含总线地址 */
    private const val DEV = "/sys/class/devfreq/3d00000.qcom,kgsl-3d0"
    private val devfreqCandidates = listOf(
        "/sys/class/devfreq/3d00000.qcom,kgsl-3d0",
        "/sys/class/devfreq/gpu",
    )

    private suspend fun firstValue(vararg paths: String): String? {
        for (p in paths) {
            val v = Shell.readNode(p)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private suspend fun firstLong(vararg paths: String): Long =
        firstValue(*paths)?.trim()?.toLongOrNull() ?: -1L

    /**
     * 频率节点单位在不同机型上有 Hz / MHz 两种，统一归一为 MHz。
     *
     * 不依赖节点文件名判断单位：厂商 sysfs 目录常被 SELinux 拒绝列举，
     * File.exists() 会返回 false 从而误判。改为按数量级判定——
     * GPU 频率若以 Hz 表示必然 >= 1e8，MHz 表示则落在 100~3000 区间。
     */
    internal fun toMhz(value: Long): Long = when {
        value <= 0L -> -1L
        value >= 100_000L -> value / 1_000_000L
        else -> value
    }

    suspend fun read(): GpuInfo = withContext(Dispatchers.IO) {
        val curRaw = firstLong("$KGSL/clock_mhz", "$KGSL/gpuclk", "$KGSL/devfreq/cur_freq")
        val curMhz = toMhz(curRaw)

        val maxRaw = firstLong("$KGSL/max_gpuclk", "$KGSL/devfreq/max_freq", "$DEV/max_freq")
        val minRaw = firstLong("$KGSL/devfreq/min_freq", "$DEV/min_freq")

        val load = firstValue("$KGSL/gpu_busy_percentage", "$KGSL/gpubusy", "$DEV/gpu_load")
            ?.filter { it.isDigit() }?.toIntOrNull() ?: -1

        val governor = firstValue("$KGSL/devfreq/governor", "$DEV/governor").orEmpty()

        val freqsRaw = firstValue(
            "$KGSL/gpu_available_frequencies",
            "$KGSL/freq_table_mhz",
            "$DEV/available_frequencies",
        )
            .orEmpty()
        val freqs = freqsRaw.split(Regex("\\s+"))
            .mapNotNull { it.toLongOrNull() }
            .filter { it > 0L }
            .map { if (it < 10_000L) it * 1_000_000L else it } // MHz -> Hz
            .sorted()

        GpuInfo(
            name = detectName(),
            curMhz = curMhz,
            minMhz = toMhz(minRaw),
            maxMhz = toMhz(maxRaw),
            loadPercent = load,
            governor = governor,
            availableFreqs = freqs,
        )
    }

    private fun detectName(): String {
        val model = runCatching { File("$KGSL/gpu_model").readText().trim() }.getOrNull()
        if (!model.isNullOrBlank()) {
            // 部分内核返回 "830v2"，部分已自带 "Adreno" 前缀，避免重复拼接
            return if (model.startsWith("Adreno", ignoreCase = true)) model else "Adreno $model"
        }
        if (File(KGSL).exists()) return "Adreno (kgsl-3d0)"
        devfreqCandidates.firstOrNull { File(it).exists() }?.let {
            return "GPU (${it.substringAfterLast('/')})"
        }
        return "未知 GPU"
    }

    /**
     * 快速读取：仅走直接文件读，不回落到 shell。
     * 返回 (当前频率 MHz, 负载百分比)，不可读时为 (-1, -1)。
     */
    fun readFast(): Pair<Long, Int> {
        val mhzFile = File("$KGSL/clock_mhz")
        if (mhzFile.exists()) {
            val v = runCatching { mhzFile.readText().trim().toLongOrNull() }.getOrNull() ?: -1L
            val load = runCatching {
                File("$KGSL/gpu_busy_percentage").readText().filter { it.isDigit() }.toIntOrNull()
            }.getOrNull() ?: -1
            if (v > 0L) return v to load
        }
        val hz = runCatching { File("$KGSL/gpuclk").readText().trim().toLongOrNull() }.getOrNull()
        if (hz != null && hz > 0L) return (hz / 1_000_000L) to -1
        return -1L to -1
    }

    private suspend fun writeTarget(value: String, vararg paths: String): Boolean {
        for (p in paths) {
            if (Shell.writeNode(p, value)) return true
        }
        return false
    }

    /**
     * 读取内核支持的调速器列表——界面选项必须来自这里。
     * 内核只接受此列表中的值，硬编码的常见调速器名在本机可能是无效值，
     * 写入会被静默忽略（本机实测只有 msm-adreno-tz 一项）。
     */
    suspend fun availableGovernors(): List<String> = withContext(Dispatchers.IO) {
        firstValue("$KGSL/devfreq/available_governors", "$DEV/available_governors")
            ?.split(Regex("\\s+"))
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    /**
     * 切换 GPU 调速器，返回 (请求值, 内核回读值)。
     *
     * 部分机型的 governor 节点被厂商策略接管，写入会被静默忽略，必须回读校验。
     */
    suspend fun setGovernor(governor: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            writeTarget(governor, "$KGSL/devfreq/governor", "$DEV/governor", "/sys/class/devfreq/gpu/governor")
            val applied = firstValue("$KGSL/devfreq/governor", "$DEV/governor").orEmpty()
            governor to applied
        }

    /** 设置 GPU 最高频率（Hz），返回 (请求值, 内核回读值) */
    suspend fun setMaxFreq(hz: Long): Pair<Long, Long> =
        withContext(Dispatchers.IO) {
            writeTarget(hz.toString(), "$KGSL/devfreq/max_freq", "$DEV/max_freq", "/sys/class/devfreq/gpu/max_freq")
            val applied = firstLong("$KGSL/devfreq/max_freq", "$DEV/max_freq")
            hz to applied
        }

}
