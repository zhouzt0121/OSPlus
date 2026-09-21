package com.osplus.tools.vm

import android.app.Application
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import com.osplus.tools.model.FpsRecord
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import android.provider.MediaStore
import android.os.Environment
import android.graphics.Canvas
import android.graphics.Bitmap
import android.content.ContentValues
import com.osplus.tools.core.FpsOverlayState
import android.provider.Settings
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.osplus.tools.core.BatteryDataSource
import com.osplus.tools.core.ChargeController
import com.osplus.tools.core.CpuDataSource
import com.osplus.tools.core.FpsRecorder
import com.osplus.tools.core.GpuDataSource
import com.osplus.tools.core.LiveMetrics
import com.osplus.tools.core.MemDataSource
import com.osplus.tools.core.PowerStatsDataSource
import com.osplus.tools.core.Preferences
import com.osplus.tools.core.ProcessDataSource
import com.osplus.tools.core.Shell
import com.osplus.tools.core.SystemProbe
import com.osplus.tools.ui.theme.AppThemeMode
import com.osplus.tools.model.BatteryInfo
import com.osplus.tools.model.CpuInfo
import com.osplus.tools.model.GpuInfo
import com.osplus.tools.model.MemInfo
import com.osplus.tools.model.MetricSample
import com.osplus.tools.model.PowerUsageEntry
import com.osplus.tools.model.ProcessEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局设备数据中枢。
 *
 * 采样策略：每 [SAMPLE_INTERVAL_MS] 采集一次轻量指标，滚动保留最近 [WINDOW_MS]
 * 的数据用于绘制柱状图；较重的完整快照按较低频率刷新。
 * 所有采集均在 IO 线程完成，UI 只订阅 StateFlow。
 */
class DeviceViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** 采样间隔：1 秒 */
        const val SAMPLE_INTERVAL_MS = 1000L

        /** 柱状图窗口：5 秒 */
        const val WINDOW_MS = 5000L

        /** 窗口内保留的采样点数 */
        const val WINDOW_SAMPLES = (WINDOW_MS / SAMPLE_INTERVAL_MS).toInt()

        /** 功耗计算的滑动窗口长度 */
        private const val POWER_WINDOW_MS = 6_000L

        /** 帧率记录上限（约 2 小时 @1 秒） */
        private const val MAX_FPS_RECORDS = 7_200
    }

    private val context: Context get() = getApplication()

    private val _cpu = MutableStateFlow(CpuInfo())
    val cpu: StateFlow<CpuInfo> = _cpu.asStateFlow()

    private val _gpu = MutableStateFlow(GpuInfo())
    val gpu: StateFlow<GpuInfo> = _gpu.asStateFlow()

    private val _mem = MutableStateFlow(MemInfo())
    val mem: StateFlow<MemInfo> = _mem.asStateFlow()

    private val _battery = MutableStateFlow(BatteryInfo())
    val battery: StateFlow<BatteryInfo> = _battery.asStateFlow()

    private val _processes = MutableStateFlow<List<ProcessEntry>>(emptyList())
    val processes: StateFlow<List<ProcessEntry>> = _processes.asStateFlow()

    private val _powerUsage = MutableStateFlow<List<PowerUsageEntry>>(emptyList())
    val powerUsage: StateFlow<List<PowerUsageEntry>> = _powerUsage.asStateFlow()

    private val _rootAvailable = MutableStateFlow(false)
    val rootAvailable: StateFlow<Boolean> = _rootAvailable.asStateFlow()

    private val _usageAccess = MutableStateFlow(false)
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()

    /** 最近 5 秒的实时采样序列（尾部为最新） */
    private val _history = MutableStateFlow<List<MetricSample>>(emptyList())
    val history: StateFlow<List<MetricSample>> = _history.asStateFlow()

    /** 每个核心的编号顺序，与 history 中 coreLoads / coreFreqs 下标一致 */
    private val _coreIndexes = MutableStateFlow<List<Int>>(emptyList())
    val coreIndexes: StateFlow<List<Int>> = _coreIndexes.asStateFlow()

    private val _autoRefresh = MutableStateFlow(true)
    val autoRefresh: StateFlow<Boolean> = _autoRefresh.asStateFlow()

    /** 主题模式（跟随系统 / 浅色 / 深色） */
    private val _themeMode = MutableStateFlow(AppThemeMode.System)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    /** 是否使用壁纸取色 */
    private val _monet = MutableStateFlow(false)
    val monet: StateFlow<Boolean> = _monet.asStateFlow()

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        Preferences.setThemeModeName(context, mode.name)
    }

    fun setMonet(enabled: Boolean) {
        _monet.value = enabled
        Preferences.setMonetEnabled(context, enabled)
    }


    private var loopJob: Job? = null
    private var tick = 0L

    init {
        // 若上次开启过悬浮窗，重新拉起服务，使界面状态与实际一致
        if (Preferences.isFpsOverlayEnabled(context) &&
            Settings.canDrawOverlays(context)
        ) {
            com.osplus.tools.service.FpsOverlayService.start(context)
        }
        _autoRefresh.value = Preferences.isAutoRefreshEnabled(context)
        _themeMode.value = runCatching {
            AppThemeMode.valueOf(Preferences.themeModeName(context))
        }.getOrDefault(AppThemeMode.System)
        _monet.value = Preferences.isMonetEnabled(context)
        viewModelScope.launch {
            _rootAvailable.value = Shell.isRootAvailable(force = true)
        }
        viewModelScope.launch {
            _usageAccess.value = PowerStatsDataSource.hasUsageAccess(context)
        }
        startSampling()
    }

    fun setAutoRefresh(enabled: Boolean) {
        _autoRefresh.value = enabled
        Preferences.setAutoRefreshEnabled(context, enabled)
    }

    /** 启动 1 秒周期的采样循环 */
    private fun startSampling() {
        loopJob?.cancel()
        loopJob = viewModelScope.launch {
            while (isActive) {
                val begin = System.currentTimeMillis()
                if (_autoRefresh.value) {
                    sampleOnce()
                    // 每 5 个采样点做一次完整快照，降低开销
                    if (tick % 5L == 0L) fullSnapshot()
                    tick++
                }
                val cost = System.currentTimeMillis() - begin
                delay((SAMPLE_INTERVAL_MS - cost).coerceAtLeast(50L))
            }
        }
    }

    /** 上一次采样，用于计算 CPU 增量占用率与功耗 */
    private var lastTick: com.osplus.tools.core.ProbeTick? = null
    private var lastTickAtMs: Long = 0L

    /** (时间戳, 累计电量 µAh) 环形记录，用于滑动窗口计算电流 */
    private val chargeHistory = ArrayDeque<Pair<Long, Long>>()
    private var lastPowerMw: Float = 0f

    /**
     * 由 charge_counter 增量推算真实电流并换算功耗。
     *
     * 部分机型的 current_now 单位为 mA 且空闲时恒为 0，直接使用会得到 0 mW；
     * 累计电量（µAh）对时间求导可得到与机型无关的真实电流。
     */
    private fun estimatePowerMw(tick: com.osplus.tools.core.ProbeTick, nowMs: Long): Float {
        // 1) 优先用累计电量做 5 秒滑动窗口求导：charge_counter 更新粒度较粗，
        //    单次 1 秒差分经常为 0，拉长窗口才能得到稳定的真实电流。
        if (tick.chargeCounterUah >= 0L) {
            chargeHistory.addLast(nowMs to tick.chargeCounterUah)
            while (chargeHistory.size > 2 &&
                nowMs - chargeHistory.first().first > POWER_WINDOW_MS
            ) {
                chargeHistory.removeFirst()
            }
            val first = chargeHistory.first()
            val spanSec = (nowMs - first.first) / 1000f
            if (spanSec >= 3.5f) {
                val dUah = (tick.chargeCounterUah - first.second).toFloat()
                val currentUa = kotlin.math.abs(dUah * 3600f / spanSec)
                if (currentUa > 1_000f && tick.voltageMv > 0f) {
                    lastPowerMw = tick.voltageMv * currentUa / 1_000_000f
                    return lastPowerMw
                }
            }
            // 窗口尚未成熟时沿用上一次结果，避免曲线在 0 与真值之间跳变
            if (lastPowerMw > 0f) return lastPowerMw
        }

        // 2) 回落：直接使用瞬时电流节点，单位按数量级推断
        if (tick.voltageMv > 0f && tick.currentRaw != 0L) {
            val ua = if (kotlin.math.abs(tick.currentRaw) < 20_000L) {
                tick.currentRaw * 1000L
            } else {
                tick.currentRaw
            }
            lastPowerMw = kotlin.math.abs(tick.voltageMv * ua / 1_000_000f)
            return lastPowerMw
        }
        return 0f
    }

    /**
     * 单次采样。
     *
     * CPU 占用 / GPU / 功耗 / SWAP 等节点在 Android 12+ 对普通应用不可读，
     * 因此统一走 [SystemProbe] 的一次 root 合并命令；CPU 频率走可直读的 sysfs。
     */
    private suspend fun sampleOnce() = withContext(Dispatchers.IO) {
        val tick = SystemProbe.sample()
        val cores = _coreIndexes.value.ifEmpty { CpuDataSource.coreIndexes() }
        if (_coreIndexes.value.isEmpty()) _coreIndexes.value = cores

        val prev = lastTick
        val now = System.currentTimeMillis()
        lastTick = tick
        lastTickAtMs = now

        val cpuLoad = if (prev != null && tick.cpuTotal > prev.cpuTotal) {
            val dt = tick.cpuTotal - prev.cpuTotal
            val di = tick.cpuIdle - prev.cpuIdle
            ((dt - di) * 100f / dt).coerceIn(0f, 100f)
        } else 0f

        val coreLoads = List(cores.size) { idx ->
            if (prev == null) return@List 0f
            val t = tick.coreTotals.getOrElse(idx) { 0L }
            val i = tick.coreIdles.getOrElse(idx) { 0L }
            val pt = prev.coreTotals.getOrElse(idx) { 0L }
            val pi = prev.coreIdles.getOrElse(idx) { 0L }
            val dt = t - pt
            val di = i - pi
            if (pt <= 0L || dt <= 0L) 0f else ((dt - di) * 100f / dt).coerceIn(0f, 100f)
        }

        val coreFreqs = CpuDataSource.readFreqs(cores)
        val fpsState = FpsRecorder.sample.value
        val powerMw = estimatePowerMw(tick, now)

        // 供桌面悬浮窗显示
        LiveMetrics.update(cpuLoad, tick.gpuLoad)

        val sample = MetricSample(
            timeMs = now,
            cpuLoad = cpuLoad,
            coreLoads = coreLoads,
            coreFreqs = coreFreqs,
            memUsedPercent = tick.memUsedPercent,
            gpuMhz = tick.gpuMhz,
            gpuLoad = tick.gpuLoad,
            powerMw = powerMw,
            fps = fpsState.fps,
            batteryTempC = tick.batteryTempC,
        )

        _history.value = (_history.value + sample).takeLast(WINDOW_SAMPLES)

        // 帧率记录：同时留档同期系统指标，便于事后定位卡顿成因
        if (_fpsRecording.value) {
            val record = FpsRecord(
                timeMs = now,
                fps = fpsState.fps,
                jank = fpsState.jankCount,
                bigJank = fpsState.bigJankCount,
                avgFrameMs = fpsState.avgFrameMs,
                maxFrameMs = fpsState.maxFrameMs,
                totalFrames = fpsState.totalFrames,
                cpuLoad = cpuLoad,
                coreLoads = coreLoads,
                coreFreqsKhz = coreFreqs,
                gpuMhz = tick.gpuMhz,
                gpuLoad = tick.gpuLoad,
                memUsedPercent = tick.memUsedPercent,
                powerMw = powerMw,
                batteryTempC = tick.batteryTempC,
            )
            _fpsRecords.value = (_fpsRecords.value + record).takeLast(MAX_FPS_RECORDS)
        }

        // 用同一次采样的结果同步刷新概览用的汇总数据，避免重复读取
        _mem.value = _mem.value.copy(
            totalKb = tick.memTotalKb,
            usedKb = tick.memUsedKb,
            availKb = (tick.memTotalKb - tick.memUsedKb).coerceAtLeast(0L),
            swapTotalKb = tick.swapTotalKb,
            swapUsedKb = tick.swapUsedKb,
            zramTotalKb = tick.zramTotalKb,
            zramUsedKb = tick.zramUsedKb,
        )
    }

    /** 完整快照：CPU 簇信息、GPU 详情、内存详情、电池详情 */
    private suspend fun fullSnapshot() = withContext(Dispatchers.IO) {
        runCatching { _battery.value = BatteryDataSource.read(context) }
        runCatching { _mem.value = MemDataSource.read() }
        runCatching { _gpu.value = GpuDataSource.read() }
        runCatching { _cpu.value = CpuDataSource.read() }
    }


    fun refreshProcesses() {
        viewModelScope.launch {
            _processes.value = runCatching { ProcessDataSource.list(context) }
                .getOrDefault(emptyList())
        }
    }

    fun refreshPowerUsage() {
        viewModelScope.launch {
            _usageAccess.value = PowerStatsDataSource.hasUsageAccess(context)
            _powerUsage.value = runCatching { PowerStatsDataSource.read(context) }
                .getOrDefault(emptyList())
        }
    }

    fun killProcess(pid: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ProcessDataSource.kill(pid) }
            refreshProcesses()
        }
    }

    fun forceStop(pkg: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ProcessDataSource.forceStop(pkg) }
            refreshProcesses()
        }
    }

    fun setCpuGovernor(core: Int, governor: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CpuDataSource.setGovernor(core, governor) }
            fullSnapshot()
        }
    }

    /** 最近一次频率锁定/恢复的结果，用于界面回读校验提示 */
    private val _freqApplyState = MutableStateFlow<CpuDataSource.FreqApplyResult?>(null)
    val freqApplyState: StateFlow<CpuDataSource.FreqApplyResult?> = _freqApplyState.asStateFlow()

    /** 把某个核心所属的簇锁定到指定频率挡位 */
    fun lockCpuFrequency(core: Int, khz: Long) {
        viewModelScope.launch {
            _freqApplyState.value =
                withContext(Dispatchers.IO) { CpuDataSource.lockFrequency(core, khz) }
            fullSnapshot()
        }
    }

    /** 恢复该簇的自动调频 */
    fun restoreCpuAuto(core: Int) {
        viewModelScope.launch {
            _freqApplyState.value =
                withContext(Dispatchers.IO) { CpuDataSource.restoreAuto(core) }
            fullSnapshot()
        }
    }

    private val _gpuApplyState = MutableStateFlow<Pair<String, String>?>(null)
    val gpuApplyState: StateFlow<Pair<String, String>?> = _gpuApplyState.asStateFlow()

    fun setGpuGovernor(governor: String) {
        viewModelScope.launch {
            _gpuApplyState.value =
                withContext(Dispatchers.IO) { GpuDataSource.setGovernor(governor) }
            fullSnapshot()
        }
    }

    private val _gpuFreqState = MutableStateFlow<Pair<Long, Long>?>(null)
    val gpuFreqState: StateFlow<Pair<Long, Long>?> = _gpuFreqState.asStateFlow()

    private val _swapApplyState = MutableStateFlow<Pair<Int, Int>?>(null)
    val swapApplyState: StateFlow<Pair<Int, Int>?> = _swapApplyState.asStateFlow()

    fun setGpuMaxFreq(hz: Long) {
        viewModelScope.launch {
            _gpuFreqState.value = withContext(Dispatchers.IO) { GpuDataSource.setMaxFreq(hz) }
            fullSnapshot()
        }
    }


    fun setChargingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ChargeController.setChargingEnabled(enabled) }
            fullSnapshot()
        }
    }

    fun setChargeCurrentLimit(ua: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ChargeController.setChargeCurrentLimit(ua) }
        }
    }

    /** 调整 ZRAM 容量（KB），会重建交换分区 */
    fun resizeZram(sizeKb: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { MemDataSource.resizeZram(sizeKb) }
            fullSnapshot()
        }
    }

    fun setSwappiness(value: Int) {
        viewModelScope.launch {
            _swapApplyState.value = withContext(Dispatchers.IO) { MemDataSource.setSwappiness(value) }
            fullSnapshot()
        }
    }

    // ---------------- 帧率记录会话 ----------------

    private val _fpsRecording = MutableStateFlow(false)
    val fpsRecording: StateFlow<Boolean> = _fpsRecording.asStateFlow()

    private val _fpsRecords = MutableStateFlow<List<FpsRecord>>(emptyList())
    val fpsRecords: StateFlow<List<FpsRecord>> = _fpsRecords.asStateFlow()

    private val _lastExportPath = MutableStateFlow<String?>(null)
    val lastExportPath: StateFlow<String?> = _lastExportPath.asStateFlow()

    /** 悬浮窗服务的真实运行状态（不是偏好值） */
    val overlayRunning: StateFlow<Boolean> = FpsOverlayState.running

    fun startFpsRecording() {
        FpsRecorder.appRetained = true
        FpsRecorder.reset()
        FpsRecorder.start()
        _fpsRecording.value = true
    }

    fun stopFpsRecording() {
        _fpsRecording.value = false
        FpsRecorder.appRetained = false
        FpsRecorder.stop()
    }

    fun clearFpsRecords() {
        _fpsRecords.value = emptyList()
        FpsRecorder.reset()
    }

    /**
     * 把当前记录导出为 CSV，写入「下载 / OSPlus /」。
     * 通过 MediaStore 写入，Android 10+ 无需任何存储权限。
     */
    fun exportFpsCsv() {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                val records = _fpsRecords.value
                if (records.isEmpty()) return@withContext null
                val coreCount = _coreIndexes.value.size.coerceAtLeast(1)
                val sb = StringBuilder()
                sb.append(FpsRecord.csvHeader(coreCount)).append('\n')
                records.forEach { sb.append(it.toCsvRow(coreCount)).append('\n') }

                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val name = "OSPlus_fps_$stamp.csv"
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/OSPlus",
                        )
                    }
                    val uri = context.contentResolver
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return@runCatching null
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(sb.toString().toByteArray(Charsets.UTF_8))
                    }
                    "下载/OSPlus/$name"
                }.getOrNull()
            }
            _lastExportPath.value = path
        }
    }

    // ---------------- 应用图标 ----------------

    private val _appIcons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val appIcons: StateFlow<Map<String, ImageBitmap>> = _appIcons.asStateFlow()

    /** 懒加载进程列表涉及的应用图标，结果缓存复用 */
    fun loadAppIcons(packages: List<String>) {
        val missing = packages.distinct().filter { it.isNotEmpty() && it !in _appIcons.value }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val pm = context.packageManager
            val loaded = withContext(Dispatchers.IO) {
                missing.mapNotNull { pkg ->
                    runCatching {
                        val drawable = pm.getApplicationIcon(pkg)
                        val size = 96
                        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        drawable.setBounds(0, 0, size, size)
                        drawable.draw(canvas)
                        pkg to bmp.asImageBitmap()
                    }.getOrNull()
                }.toMap()
            }
            if (loaded.isNotEmpty()) _appIcons.value = _appIcons.value + loaded
        }
    }

    fun refreshRootState() {
        viewModelScope.launch {
            _rootAvailable.value = Shell.isRootAvailable(force = true)
        }
    }


}
