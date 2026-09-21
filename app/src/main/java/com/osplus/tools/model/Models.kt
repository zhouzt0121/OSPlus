package com.osplus.tools.model

/** CPU 单个核心的运行状态 */
data class CpuCoreInfo(
    val index: Int,
    val online: Boolean = true,
    val curKhz: Long = 0L,
    val minKhz: Long = 0L,
    val maxKhz: Long = 0L,
    val governor: String = "",
)

/** CPU 簇（大小核架构） */
data class CpuCluster(
    val name: String,
    val cores: List<Int>,
    val curKhz: Long = 0L,
    val minKhz: Long = 0L,
    val maxKhz: Long = 0L,
)

/** CPU 整体信息 */
data class CpuInfo(
    val soc: String = "",
    val abi: String = "",
    val coreCount: Int = 0,
    val clusters: List<CpuCluster> = emptyList(),
    val cores: List<CpuCoreInfo> = emptyList(),
    /** 近 1 秒 CPU 总占用百分比 */
    val loadPercent: Float = 0f,
    /** CPU 温度（摄氏度），不可读时为 null */
    val tempC: Float? = null,
    val uptimeSec: Long = 0L,
    val governors: List<String> = emptyList(),
)

/** GPU 信息 */
data class GpuInfo(
    val name: String = "",
    val curMhz: Long = -1L,
    val minMhz: Long = -1L,
    val maxMhz: Long = -1L,
    val loadPercent: Int = -1,
    val governor: String = "",
    val availableFreqs: List<Long> = emptyList(),
)

/** 内存 / SWAP / ZRAM 信息（单位均为 KB） */
data class MemInfo(
    val totalKb: Long = 0L,
    val availKb: Long = 0L,
    val freeKb: Long = 0L,
    val usedKb: Long = 0L,
    val cachedKb: Long = 0L,
    val buffersKb: Long = 0L,
    val swapTotalKb: Long = 0L,
    val swapUsedKb: Long = 0L,
    val zramTotalKb: Long = 0L,
    val zramUsedKb: Long = 0L,
    val zramOrigKb: Long = 0L,
    val zramDevices: List<String> = emptyList(),
)

/** 电池 / 充电信息 */
data class BatteryInfo(
    val levelPercent: Int = -1,
    val status: String = "",
    val health: String = "",
    val tempC: Float? = null,
    val voltageMv: Int = -1,
    val currentNowUa: Int = 0,
    val currentAvgUa: Int = 0,
    val chargeCounterUah: Long = -1L,
    val capacityPercent: Int = -1,
    val technology: String = "",
    val plugged: String = "",
    val chargingEnabled: Boolean? = null,
    val chargeFullDesignUah: Long = -1L,
    val chargeFullUah: Long = -1L,
    val cycleCount: Int = -1,
)

/** 进程条目 */
data class ProcessEntry(
    val pid: Int,
    val name: String,
    val user: String = "",
    val cpuPercent: Float = 0f,
    val rssKb: Long = 0L,
    val state: String = "",
    val packageName: String? = null,
)

/** 应用耗电条目 */
data class PowerUsageEntry(
    val packageName: String,
    val label: String,
    val foregroundMs: Long = 0L,
    val backgroundMs: Long = 0L,
    /** 估算耗电占比 0~100 */
    val percent: Float = 0f,
)

/** 帧率采样快照 */
data class FpsSample(
    val fps: Float = 0f,
    val jankCount: Int = 0,
    val bigJankCount: Int = 0,
    val avgFrameMs: Float = 0f,
    val maxFrameMs: Float = 0f,
    val totalFrames: Int = 0,
)

/**
 * 一条帧率记录，同时携带同期系统指标，便于事后分析卡顿成因。
 *
 * 仅在用户开启「帧率记录」时写入，采样间隔与实时采样一致（1 秒）。
 */
data class FpsRecord(
    val timeMs: Long = 0L,
    val fps: Float = 0f,
    val jank: Int = 0,
    val bigJank: Int = 0,
    val avgFrameMs: Float = 0f,
    val maxFrameMs: Float = 0f,
    val totalFrames: Int = 0,
    /** 同期系统指标 */
    val cpuLoad: Float = 0f,
    val coreLoads: List<Float> = emptyList(),
    val coreFreqsKhz: List<Long> = emptyList(),
    val gpuMhz: Long = -1L,
    val gpuLoad: Int = -1,
    val memUsedPercent: Float = 0f,
    val powerMw: Float = 0f,
    val batteryTempC: Float? = null,
) {
    /** 导出为 CSV 的一行（不含表头） */
    fun toCsvRow(coreCount: Int): String {
        val sb = StringBuilder()
        sb.append(timeMs).append(',')
        sb.append("%.1f".format(fps)).append(',')
        sb.append(jank).append(',')
        sb.append(bigJank).append(',')
        sb.append("%.2f".format(avgFrameMs)).append(',')
        sb.append("%.2f".format(maxFrameMs)).append(',')
        sb.append(totalFrames).append(',')
        sb.append("%.1f".format(cpuLoad)).append(',')
        repeat(coreCount) { i ->
            sb.append(coreLoads.getOrElse(i) { 0f }.let { "%.0f".format(it) }).append(',')
        }
        repeat(coreCount) { i ->
            sb.append(coreFreqsKhz.getOrElse(i) { -1L }).append(',')
        }
        sb.append(gpuMhz).append(',')
        sb.append(gpuLoad).append(',')
        sb.append("%.1f".format(memUsedPercent)).append(',')
        sb.append("%.0f".format(powerMw)).append(',')
        sb.append(batteryTempC?.let { "%.1f".format(it) } ?: "")
        return sb.toString()
    }

    companion object {
        /** CSV 表头，列顺序必须与 [toCsvRow] 一致 */
        fun csvHeader(coreCount: Int): String {
            val sb = StringBuilder()
            sb.append("timestamp_ms,fps,jank,big_jank,avg_frame_ms,max_frame_ms,total_frames,cpu_load,")
            repeat(coreCount) { sb.append("core${it}_load,") }
            repeat(coreCount) { sb.append("core${it}_freq_khz,") }
            sb.append("gpu_mhz,gpu_load,mem_percent,power_mw,battery_temp_c")
            return sb.toString()
        }
    }
}

/**
 * 单次实时采样点。
 *
 * 采样器按固定间隔产出本对象，UI 侧保留最近 5 秒窗口用于绘制柱状图。
 */
data class MetricSample(
    /** 采样时刻（System.currentTimeMillis） */
    val timeMs: Long = 0L,
    /** CPU 总占用 0~100 */
    val cpuLoad: Float = 0f,
    /** 每个核心的占用 0~100，下标与核心编号一致 */
    val coreLoads: List<Float> = emptyList(),
    /** 每个核心的当前频率 kHz */
    val coreFreqs: List<Long> = emptyList(),
    /** 内存占用百分比 0~100 */
    val memUsedPercent: Float = 0f,
    /** GPU 频率 MHz，-1 表示不可读 */
    val gpuMhz: Long = -1L,
    /** GPU 负载百分比，-1 表示不可读 */
    val gpuLoad: Int = -1,
    /** 整机功耗 mW，正值，不可读时为 0 */
    val powerMw: Float = 0f,
    /** 实时帧率 */
    val fps: Float = 0f,
    /** 电池温度摄氏度 */
    val batteryTempC: Float? = null,
) {
}
