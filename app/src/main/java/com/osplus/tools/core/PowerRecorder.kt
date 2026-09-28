package com.osplus.tools.core

import android.app.usage.UsageStatsManager
import android.content.Context
import com.osplus.tools.model.AppCpuPoint
import com.osplus.tools.model.AppDrainEntry
import com.osplus.tools.model.PowerRecordSummary
import com.osplus.tools.model.PowerSample
import com.osplus.tools.model.PowerSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 耗电录制器。
 *
 * 做成单例而不是放在 ViewModel 里，理由与 [FpsRecorder] 相同：
 * 录制要跨页面存活，且**必须能在应用退到后台后继续**——
 * 而 ViewModel 随 Activity 销毁而清理。采样节拍由 ViewModel 的 1 秒循环驱动
 * （那里已有完整的采集链路与 root 合并命令），这里只负责累积与计算。
 *
 * ### 关于「功耗」这件事必须先说清楚
 *
 * **Android 原生不提供任何「功耗(W)」接口**，所有功耗都是算出来的。本实现给两条路：
 *
 * 1. **电流法（优先）**：`P = U × I`。电流来自 `charge_counter` 的滑动窗口求导
 *    （见 [SystemProbe]），精度最高，但需要 root 才能读到厂商 sysfs。
 * 2. **电量差法（回落）**：`P = Δ能量 / Δt`，`Δ能量 = Δ电量% × 额定能量(Wh)`。
 *    无 root 时只有这条路。**短录制下它的误差会淹没信号**——
 *    28 Wh 电池跑 2 W，10 分钟只掉约 1.2%，而电量是以 1% 为步进上报的，
 *    量化误差可达 ±100%。因此它只在录制足够长（≥ 5 分钟）时才被采信。
 *
 * ### 应用侧明细的诚实边界
 *
 * Android 同样没有「按应用的耗电量」接口。这里以 **CPU 占用为代理指标**：
 * 按应用累加每次采样的 CPU 百分比，再归一化成占比。
 * 它抓得到「谁在烧 CPU」，抓不到「谁在后台唤醒/联网」，
 * 因此界面上必须标明这是 CPU 加权估算，而不是实测耗电。
 */
object PowerRecorder {

    /** 采样上限：1 秒 1 条 → 7200 条 = 2 小时 */
    private const val MAX_SAMPLES = 7_200

    /** 录制时长低于该值时，平均功耗与理论续航都标注为「估算不准」 */
    const val MIN_ACCURATE_MS = 10_000L

    /**
     * 电量差法可被采信的最短录制时长。
     *
     * 电量以 1% 为步进上报，5 分钟内最多掉 1~2 个百分点，
     * 用它反推功率的误差在 50% 量级——低于这条线就不给结论，而不是给一个假数字。
     */
    private const val MIN_LEVEL_METHOD_MS = 5 * 60_000L

    /** 超过该时长自动停止，避免用户忘记停止时无限累积 */
    private const val AUTO_STOP_MS = 4 * 3600_000L

    /** 应用侧数据的采样间隔（按 tick 计），root `top` 开销较大不宜每秒调用 */
    const val APP_SAMPLE_EVERY_TICKS = 10

    /** 曲线图最多画几条应用序列——再多颜色就分不清了 */
    const val MAX_SERIES = 5

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _samples = MutableStateFlow<List<PowerSample>>(emptyList())
    val samples: StateFlow<List<PowerSample>> = _samples.asStateFlow()

    private val _summary = MutableStateFlow(PowerRecordSummary())
    val summary: StateFlow<PowerRecordSummary> = _summary.asStateFlow()

    private val _appDrains = MutableStateFlow<List<AppDrainEntry>>(emptyList())
    val appDrains: StateFlow<List<AppDrainEntry>> = _appDrains.asStateFlow()

    private val _appCpuSeries = MutableStateFlow<List<AppCpuPoint>>(emptyList())
    val appCpuSeries: StateFlow<List<AppCpuPoint>> = _appCpuSeries.asStateFlow()

    // ---------------- 录制期间的累计量（不需要暴露给 UI） ----------------

    private var startMs = 0L
    private var fullWh = 0f
    private var remainWh = 0f
    private var lastCharging = false

    /** 录制开始时各应用的前台时长基线（UsageStats 是累计值，必须做差） */
    private var usageBaseline: Map<String, Long> = emptyMap()
    private var usageLatest: Map<String, Long> = emptyMap()

    /** 应用 → 各次采样的 CPU 百分比之和 / 采样次数 */
    private val cpuSum = HashMap<String, Float>()
    private val cpuCount = HashMap<String, Int>()

    /** 应用名缓存，避免每次归一化都查 PackageManager */
    private var labelCache: Map<String, String> = emptyMap()

    /**
     * 开始录制。
     *
     * [battery] 用于取电量与充电状态——**必须由调用方传入**，
     * 因为电量只在 `ACTION_BATTERY_CHANGED` 里更新，本模块不重复注册广播。
     */
    suspend fun start(context: Context) {
        clear()
        startMs = System.currentTimeMillis()
        usageBaseline = readForeground(context)
        usageLatest = usageBaseline
        labelCache = readLabels(context, usageBaseline.keys)
        _recording.value = true
    }

    /** 结束录制并做最后一次应用侧采样，锁定本次记录 */
    suspend fun stop(context: Context) {
        if (!_recording.value) return
        sampleApps(context, System.currentTimeMillis())
        _recording.value = false
    }

    /** 丢弃本次记录（含曲线与全部统计） */
    fun clear() {
        _samples.value = emptyList()
        _summary.value = PowerRecordSummary()
        _appDrains.value = emptyList()
        _appCpuSeries.value = emptyList()
        usageBaseline = emptyMap()
        usageLatest = emptyMap()
        cpuSum.clear()
        cpuCount.clear()
        startMs = 0L
        fullWh = 0f
        remainWh = 0f
        lastCharging = false
    }

    /** 每秒由 ViewModel 调用一次 */
    fun onTick(
        nowMs: Long,
        levelPercent: Int,
        voltageMv: Int,
        tempC: Float?,
        powerMw: Float,
        charging: Boolean,
        remainWhNow: Float,
        fullWhNow: Float,
    ) {
        if (!_recording.value) return
        if (startMs == 0L) startMs = nowMs
        if (fullWhNow > 0f) fullWh = fullWhNow
        if (remainWhNow > 0f) remainWh = remainWhNow
        lastCharging = charging

        val sample = PowerSample(
            timeMs = nowMs,
            elapsedMs = nowMs - startMs,
            levelPercent = levelPercent,
            voltageMv = voltageMv,
            tempC = tempC,
            powerMw = powerMw,
            charging = charging,
        )
        val next = (_samples.value + sample).takeLast(MAX_SAMPLES)
        _samples.value = next
        _summary.value = computeSummary(next, fullWh, remainWh, charging)

        if (sample.elapsedMs >= AUTO_STOP_MS) {
            // 自动收尾：不再做应用侧采样（那需要 Context），保留已有统计
            _recording.value = false
        }
    }

    /**
     * 采一次应用侧数据：前台时长差分 + 各进程 CPU 占用。
     *
     * 由 ViewModel 每 [APP_SAMPLE_EVERY_TICKS] 个 tick 调用一次——
     * root `top` 一次要上百毫秒，每秒调用会明显抬高录制本身的功耗，
     * 反而污染被测对象。
     */
    suspend fun sampleApps(context: Context, nowMs: Long) {
        if (!_recording.value) return
        val elapsed = (nowMs - startMs).coerceAtLeast(0L)

        val usage = readForeground(context)
        usageLatest = usage

        // 按包名汇总本进程 CPU%（一个包可能有多个进程）
        val byPackage = HashMap<String, Float>()
        runCatching { ProcessDataSource.list(context) }.getOrNull()?.forEach { p ->
            val pkg = p.packageName ?: return@forEach
            byPackage[pkg] = (byPackage[pkg] ?: 0f) + p.cpuPercent
        }
        byPackage.forEach { (pkg, cpu) ->
            cpuSum[pkg] = (cpuSum[pkg] ?: 0f) + cpu
            cpuCount[pkg] = (cpuCount[pkg] ?: 0) + 1
        }
        if (byPackage.isNotEmpty()) {
            _appCpuSeries.value =
                (_appCpuSeries.value + AppCpuPoint(elapsed, byPackage)).takeLast(MAX_SAMPLES)
        }

        val missing = (usage.keys + byPackage.keys).filter { it !in labelCache }
        if (missing.isNotEmpty()) {
            labelCache = labelCache + readLabels(context, missing)
        }
        _appDrains.value = buildDrains()
    }

    // ---------------- 汇总计算 ----------------

    /**
     * 由样本序列算出平均功耗与理论续航。
     *
     * **充电中的样本一律排除**：充电时电流方向相反，混进来会把平均功耗算成负值或
     * 极小的噪声值。若全部样本都在充电，则明确返回 [PowerSource.Unavailable]，
     * 由界面提示「正在充电」，而不是给一个没有意义的数字。
     */
    private fun computeSummary(
        samples: List<PowerSample>,
        fullWh: Float,
        remainWh: Float,
        charging: Boolean,
    ): PowerRecordSummary {
        if (samples.isEmpty()) return PowerRecordSummary(charging = charging)

        val first = samples.first()
        val last = samples.last()
        val durationMs = (last.elapsedMs - first.elapsedMs).coerceAtLeast(0L)
        val usable = samples.filter { !it.charging }

        var avgW = 0f
        var source = PowerSource.Unavailable

        // 1) 电流法：对可读的瞬时功耗取均值
        val powerSamples = usable.map { it.powerMw }.filter { it > 0f }
        if (powerSamples.isNotEmpty()) {
            avgW = powerSamples.average().toFloat() / 1000f
            source = PowerSource.Current
        } else if (durationMs >= MIN_LEVEL_METHOD_MS && fullWh > 0f) {
            // 2) 电量差法：Δ能量 / Δt
            val drop = first.levelPercent - last.levelPercent
            if (drop > 0) {
                val deltaWh = drop / 100f * fullWh
                avgW = (deltaWh / (durationMs / 3_600_000f)).coerceAtLeast(0f)
                source = PowerSource.LevelDelta
            }
        }

        // 理论续航 = 剩余能量 / 平均功耗。充电中不给——那时「还能用多久」没有意义。
        val theoreticalMs = if (!charging && avgW > 0.0001f && remainWh > 0f) {
            (remainWh / avgW * 3_600_000f).toLong()
        } else {
            0L
        }

        return PowerRecordSummary(
            durationMs = durationMs,
            startLevel = first.levelPercent,
            endLevel = last.levelPercent,
            avgPowerW = avgW,
            fullWh = fullWh,
            remainWh = remainWh,
            theoreticalRemainMs = theoreticalMs,
            charging = charging,
            source = source,
            reliable = durationMs >= MIN_ACCURATE_MS,
        )
    }

    /**
     * 归一化成耗电占比。
     *
     * 有 CPU 数据时按 **CPU 占用累加值**加权——注意是累加而不是平均：
     * 「50% 占用持续 10 秒」与「5% 占用持续 100 秒」的能耗并不相同，
     * 取平均会让短时高负载的应用被严重低估。
     * 完全没有 CPU 数据（无 root）时回落到前台时长加权。
     */
    private fun buildDrains(): List<AppDrainEntry> {
        val packages = (usageLatest.keys + cpuSum.keys).filter { it.isNotBlank() }
        if (packages.isEmpty()) return emptyList()

        val raw = packages.mapNotNull { pkg ->
            val fg = ((usageLatest[pkg] ?: 0L) - (usageBaseline[pkg] ?: 0L)).coerceAtLeast(0L)
            val count = cpuCount[pkg] ?: 0
            val cpuAvg = if (count > 0) (cpuSum[pkg] ?: 0f) / count else 0f
            val cpuTotal = cpuSum[pkg] ?: 0f
            if (fg <= 0L && cpuTotal <= 0f) return@mapNotNull null
            Triple(pkg, fg, cpuAvg) to cpuTotal
        }
        if (raw.isEmpty()) return emptyList()

        val cpuTotalAll = raw.sumOf { it.second.toDouble() }
        val useCpu = cpuTotalAll > 0.01
        val scores = raw.map { (info, cpuTotal) ->
            val (pkg, fg, cpuAvg) = info
            val score = if (useCpu) cpuTotal else fg / 1000f
            AppDrainEntry(
                packageName = pkg,
                label = labelCache[pkg] ?: pkg,
                foregroundMs = fg,
                cpuPercentAvg = cpuAvg,
                drainPercent = score,
            )
        }
        val total = scores.sumOf { it.drainPercent.toDouble() }.toFloat()
        if (total <= 0f) return emptyList()
        return scores
            .map { it.copy(drainPercent = it.drainPercent * 100f / total) }
            .sortedByDescending { it.drainPercent }
    }

    // ---------------- UsageStats ----------------

    private suspend fun readForeground(context: Context): Map<String, Long> =
        withContext(Dispatchers.IO) {
            if (!PowerStatsDataSource.hasUsageAccess(context)) return@withContext emptyMap()
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@withContext emptyMap()
            val now = System.currentTimeMillis()
            // 取 24 小时窗口：UsageStats 是累计值，跨桶会归零，窗口开大一些更稳
            val stats = runCatching {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 24 * 3600_000L, now)
            }.getOrNull() ?: return@withContext emptyMap()
            stats.associate { it.packageName to it.totalTimeInForeground }
        }

    private suspend fun readLabels(
        context: Context,
        packages: Collection<String>,
    ): Map<String, String> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            packages.filter { it.isNotBlank() }.associateWith { pkg ->
                runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
        }
}
