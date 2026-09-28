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
    /** 设计能量（µWh）；部分机型只暴露能量节点而不暴露容量节点 */
    val energyFullDesignUwh: Long = -1L,
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
data class MetricSample(    /** 采样时刻（System.currentTimeMillis） */
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

// ---------------- 耗电录制 ----------------

/**
 * 一次耗电录制中的单条采样（1 秒 1 条）。
 *
 * 只保留**绘制曲线与事后计算所需**的字段，不存原始 sysfs 文本：
 * 7200 条的样本量下，多存一个字符串就会让常驻内存明显上升。
 */
data class PowerSample(
    /** 采样时刻（System.currentTimeMillis） */
    val timeMs: Long = 0L,
    /** 相对录制开始的偏移，曲线的时间轴用它 */
    val elapsedMs: Long = 0L,
    /** 电量百分比 0~100 */
    val levelPercent: Int = -1,
    /** 端电压 mV */
    val voltageMv: Int = -1,
    /** 温度 ℃；不可读为 null */
    val tempC: Float? = null,
    /** 瞬时功耗 mW；<= 0 表示不可读 */
    val powerMw: Float = 0f,
    /** 采样这一刻是否在充电 */
    val charging: Boolean = false,
)

/** 平均功耗的计算来源——决定界面上要不要打「估算」标记 */
enum class PowerSource {
    /** 由电流（电压 × 电流）求得，精度最高 */
    Current,

    /** 由电量差与额定能量反推；只在电流不可读时使用，短录制误差大 */
    LevelDelta,

    /** 数据不足，无法给出 */
    Unavailable,
}

/** 录制期间单个应用的耗电明细 */
data class AppDrainEntry(
    val packageName: String,
    val label: String = "",
    /** 录制期间的前台时长 */
    val foregroundMs: Long = 0L,
    /** 录制期间的平均 CPU 占用（%） */
    val cpuPercentAvg: Float = 0f,
    /** 估算耗电占比 0~100 */
    val drainPercent: Float = 0f,
)

/**
 * 应用侧 CPU 占用的一个时间点。
 *
 * 用于「使用场景 → 曲线图」：Android 没有按应用的功率接口，
 * 只能以 CPU 占用作为代理指标画多序列曲线。
 */
data class AppCpuPoint(
    val elapsedMs: Long = 0L,
    /** 包名 → 该时刻的 CPU 占用（%） */
    val cpuByPackage: Map<String, Float> = emptyMap(),
)

/** 一次耗电录制的汇总 */
data class PowerRecordSummary(
    val durationMs: Long = 0L,
    val startLevel: Int = -1,
    val endLevel: Int = -1,
    /** 平均功耗 W；0 表示不可用 */
    val avgPowerW: Float = 0f,
    /** 电池额定总能量 Wh；0 表示不可读 */
    val fullWh: Float = 0f,
    /** 当前剩余能量 Wh */
    val remainWh: Float = 0f,
    /** 理论续航时长 ms；0 表示不可推算 */
    val theoreticalRemainMs: Long = 0L,
    val charging: Boolean = false,
    val source: PowerSource = PowerSource.Unavailable,
    /** 录制时长是否足够（不足时估算不准，界面要明确提示） */
    val reliable: Boolean = false,
) {
    /** 本次录制掉了多少电（百分点） */
    val levelDrop: Int get() = if (startLevel >= 0 && endLevel >= 0) startLevel - endLevel else 0
}

/**
 * 时长格式化：不足 1 分钟给秒，超过给「时分」，超过 24 小时给「天时分」。
 *
 * 与 [FpsRecord] 那套 `mm:ss` 时钟格式不同——这里表达的是「还能用多久」这类
 * 跨度可能长达十几小时的量，`10h47m` 比 `10:47:00` 更好读。
 */
fun formatSpan(ms: Long): String {
    if (ms <= 0L) return "-"
    val totalSec = ms / 1000
    val d = totalSec / 86_400
    val h = (totalSec % 86_400) / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        d > 0 -> "${d}天${h}小时"
        h > 0 -> "${h}h${"%02d".format(m)}m"
        m > 0 -> "${m}m${"%02d".format(s)}s"
        else -> "${s}s"
    }
}
