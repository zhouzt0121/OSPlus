package com.osplus.tools.vm

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
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
import android.util.Log
import com.osplus.tools.core.FpsOverlayState
import android.provider.Settings
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.osplus.tools.core.BatteryDataSource
import com.osplus.tools.core.BatteryEnergy
import com.osplus.tools.core.ChargeController
import com.osplus.tools.core.CpuDataSource
import com.osplus.tools.core.FpsRecorder
import com.osplus.tools.core.GpuDataSource
import com.osplus.tools.core.LiveMetrics
import com.osplus.tools.core.LiveNotif
import com.osplus.tools.core.NotifMetric
import com.osplus.tools.core.PowerRecorder
import com.osplus.tools.core.MemDataSource
import com.osplus.tools.core.MemCleanResult
import com.osplus.tools.core.AsoulGame
import com.osplus.tools.core.AsoulState
import com.osplus.tools.core.PerAppRule
import com.osplus.tools.core.PerfSchedDataSource
import com.osplus.tools.core.SchedResult
import com.osplus.tools.core.UperfState
import com.osplus.tools.core.PowerStatsDataSource
import com.osplus.tools.core.Preferences
import com.osplus.tools.core.PrivilegeCapabilities
import com.osplus.tools.core.PrivilegeManager
import com.osplus.tools.core.PrivilegeMode
import com.osplus.tools.core.ProcessDataSource
import com.osplus.tools.core.Shell
import com.osplus.tools.core.adb.AdbPairClient
import com.osplus.tools.core.adb.AdbScriptDeployer
import com.osplus.tools.core.adb.Spake2
import com.osplus.tools.core.SystemProbe
import com.osplus.tools.service.FpsOverlayService
import com.osplus.tools.ui.theme.AppThemeMode
import com.osplus.tools.model.BatteryInfo
import com.osplus.tools.model.AppCpuPoint
import com.osplus.tools.model.AppDrainEntry
import com.osplus.tools.model.CpuInfo
import com.osplus.tools.model.GpuInfo
import com.osplus.tools.model.MemInfo
import com.osplus.tools.model.MetricSample
import com.osplus.tools.model.PowerRecordSummary
import com.osplus.tools.model.PowerSample
import com.osplus.tools.model.PowerSource
import com.osplus.tools.model.ProcessEntry
import com.osplus.tools.model.formatSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局设备数据中枢。
 *
 * 采样策略：每 [SAMPLE_INTERVAL_MS] 采集一次轻量指标，滚动保留最近
 * [HISTORY_MAX_SAMPLES] 条数据用于绘制趋势；较重的完整快照按较低频率刷新。
 * 所有采集均在 IO 线程完成，UI 只订阅 StateFlow。
 */
class DeviceViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "OSPlusVM"

        /** 采样间隔：1 秒 */
        const val SAMPLE_INTERVAL_MS = 1000L

        /**
         * 趋势数据保留上限：1 秒 1 条 → 1800 条 = 30 分钟。
         *
         * 早期版本只留 5 条（5 秒），实时趋势几乎看不出走势。
         * 改成累积保留后，曲线会随运行时间不断变长；到达上限后按滚动窗口
         * 丢弃最早的一条，内存占用恒定在 1800 条采样量级。
         */
        const val HISTORY_MAX_SAMPLES = 1_800

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

    private val _rootAvailable = MutableStateFlow(false)
    val rootAvailable: StateFlow<Boolean> = _rootAvailable.asStateFlow()

    /** 当前生效的提权模式（Root / Shizuku / ADB），null 表示都没就绪 */
    val privilegeMode: StateFlow<PrivilegeMode?> = PrivilegeManager.mode

    /**
     * 用户在设置页点选的模式（见 [PrivilegeManager.selection]）。
     *
     * 界面用它驱动「提权方式」分段条的选中态与详情展示——**点了就跟着变**，
     * 不受「该模式当前能不能用」影响；实际生效的通道仍是 [privilegeMode]。
     */
    val privilegeSelection: StateFlow<PrivilegeMode?> = PrivilegeManager.selection

    /** 当前模式下各项能力的可用性，界面据此决定控件是否可点 */
    val capabilities: StateFlow<PrivilegeCapabilities> = PrivilegeManager.capabilities

    /** 各模式在本机的可探测状态，设置页用来展示「能用哪些模式」 */
    val privilegeProbe: StateFlow<PrivilegeManager.ModeProbe> = PrivilegeManager.probe

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

    /** 功率校准（复刻 Metric）：电流倍率与串联双电芯，立即影响下一次采样 */
    fun setPowerCurrentFactor(factor: Float) {
        Preferences.setPowerCurrentFactor(context, factor.coerceIn(0.1f, 10f))
    }

    fun setPowerSerialDualCell(enabled: Boolean) {
        Preferences.setPowerSerialDualCell(context, enabled)
        // 校准一变，上一次的功率值就是旧口径了，清掉避免曲线突变前残留半秒
        lastPowerMw = 0f
    }

    /**
     * ZRAM 容量调整的安全闸门。
     *
     * 关闭时容量滑块与「应用并重建」整体置灰且不执行——重建交换分区会短暂占用 IO、
     * 让正在使用交换区的应用卡顿，因此默认关闭，需用户显式开启。
     */
    private val _zramResizeEnabled = MutableStateFlow(false)
    val zramResizeEnabled: StateFlow<Boolean> = _zramResizeEnabled.asStateFlow()

    fun setZramResizeEnabled(enabled: Boolean) {
        _zramResizeEnabled.value = enabled
        Preferences.setZramResizeEnabled(context, enabled)
    }


    private var loopJob: Job? = null
    private var tick = 0L

    init {
        // 呈现方式（实时任务通知 / 悬浮窗）与显示项必须在拉起服务之前恢复，
        // 否则服务会先按默认值构建一次通知、再被真实值覆盖，出现一次可见的闪烁
        LiveNotif.load(context)

        // 若上次开启过跨应用监视，重新拉起服务，使界面状态与实际一致。
        // 实时任务通知模式不需要悬浮窗权限，只有悬浮窗模式才需要
        val needsOverlay = !LiveNotif.enabled.value
        if (Preferences.isFpsOverlayEnabled(context) &&
            (!needsOverlay || Settings.canDrawOverlays(context))
        ) {
            FpsOverlayService.start(context)
        }
        _autoRefresh.value = Preferences.isAutoRefreshEnabled(context)
        _themeMode.value = runCatching {
            AppThemeMode.valueOf(Preferences.themeModeName(context))
        }.getOrDefault(AppThemeMode.System)
        _monet.value = Preferences.isMonetEnabled(context)
        _zramResizeEnabled.value = Preferences.isZramResizeEnabled(context)
        FpsOverlayState.setAlpha(Preferences.overlayAlpha(context))
        viewModelScope.launch {
            // 默认不提权：只有此前配置过模式才恢复。
            // 首次安装 / 开机自启时若强行探测，Root 会触发授权弹框而无人可点，
            // 进程卡死在启动阶段。详见 PrivilegeManager.restoreIfConfigured。
            PrivilegeManager.restoreIfConfigured()
            // rootAvailable 只用于设置页展示「设备是否具备 root」。
            // 未授权时不主动执行 su —— 那会弹授权框，开机自启场景下无人可点，
            // 进程就卡在启动阶段。等用户去设置页点 Root 时再探。
            if (PrivilegeManager.mode.value == PrivilegeMode.ROOT) {
                _rootAvailable.value = Shell.isRootAvailable(force = true)
            }
            // 首屏就要在「性能调度」入口显示模块状态摘要，只多一次提权调用
            refreshPerfSched()
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
                    // 单次采样失败绝不能终结整个循环。采样要拉起 root shell，
                    // 任何一次异常都不该让监控永久停摆——循环一旦死掉，
                    // 界面上只是数字不再变化，用户只能靠重启应用才能恢复。
                    runCatching { sampleOnce() }
                        .onFailure { Log.w(TAG, "采样失败，跳过本轮", it) }
                    // 每 5 个采样点做一次完整快照，降低开销
                    if (tick % 5L == 0L) runCatching { fullSnapshot() }
                    tick++
                    // 录制期间按更低频率采应用侧数据：root `top` 一次要上百毫秒，
                    // 每秒调用会明显抬高录制本身的功耗，反而污染被测对象
                    if (PowerRecorder.recording.value &&
                        tick % PowerRecorder.APP_SAMPLE_EVERY_TICKS == 0L
                    ) {
                        runCatching {
                            PowerRecorder.sampleApps(context, System.currentTimeMillis())
                        }
                    }
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

    /** 功率校准系数（复刻 Metric）：电流倍率 × 串联双电芯修正，设置页可调 */
    private fun powerCalibrationFactor(): Float =
        Preferences.powerCurrentFactor(context) *
            (if (Preferences.powerSerialDualCell(context)) 2f else 1f)

    /**
     * 由 charge_counter 增量推算真实电流并换算功耗。
     *
     * 部分机型的 current_now 单位为 mA 且空闲时恒为 0，直接使用会得到 0 mW；
     * 累计电量（µAh）对时间求导可得到与机型无关的真实电流。
     */
    private fun estimatePowerMw(tick: com.osplus.tools.core.ProbeTick, nowMs: Long): Float {
        val calib = powerCalibrationFactor()
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
                    lastPowerMw = tick.voltageMv * currentUa / 1_000_000f * calib
                    return lastPowerMw
                }
            }
            // 窗口尚未成熟时沿用上一次结果，避免曲线在 0 与真值之间跳变
            if (lastPowerMw > 0f) return lastPowerMw
        }

        // 2) 回落：直接使用瞬时电流节点，单位按数量级推断
        val ua = normalizeCurrentUa(tick.currentRaw)
        if (tick.voltageMv > 0f && ua != 0L) {
            lastPowerMw = tick.voltageMv * ua / 1_000_000f * calib
            return lastPowerMw
        }
        return 0f
    }

    /**
     * 把瞬时电流节点归一到 µA（绝对值）。
     *
     * 不同内核把 `current_now` 记为 mA 或 µA，而**节点名不携带单位**，
     * 只能按数量级判定：20 mA（= 20000 µA）以下不可能是有意义的整机电流，
     * 因此小于该阈值时按 mA 解释并乘 1000。
     */
    private fun normalizeCurrentUa(raw: Long): Long {
        if (raw == 0L) return 0L
        val abs = kotlin.math.abs(raw)
        return if (abs < 20_000L) abs * 1000L else abs
    }

    /**
     * 充电功率（W）。
     *
     * 只在**确实接入充电器**时给值：放电时 `current_now` 同样非零，
     * 不做接入判断会把整机功耗误报成「充电速度」。
     * 接入状态取自 [BatteryDataSource] 的快照（每 5 秒刷新一次），
     * 因此刚插上充电器时读数可能滞后最多 5 秒。
     */
    private fun estimateChargeW(tick: com.osplus.tools.core.ProbeTick): Float {
        val plugged = _battery.value.plugged
        if (plugged.isBlank() || plugged == "未连接") return 0f
        val ua = normalizeCurrentUa(tick.currentRaw)
        if (ua <= 0L || tick.voltageMv <= 0f) return 0f
        // mV × µA = 1e-9 W；与 estimatePowerMw 共用同一套校准口径
        return tick.voltageMv * ua / 1_000_000_000f * powerCalibrationFactor()
    }

    /**
     * 单次采样。
     *
     * CPU 占用 / GPU / 功耗 / SWAP 等节点在 Android 12+ 对普通应用不可读，
     * 因此统一走 [SystemProbe] 的一次 root 合并命令；CPU 频率走可直读的 sysfs。
     */
    private suspend fun sampleOnce() = withContext(Dispatchers.IO) {
        val tick = SystemProbe.sample()
        val cachedCores = _coreIndexes.value
        val cores = if (cachedCores.isNotEmpty()) cachedCores else CpuDataSource.coreIndexes()
        if (cachedCores.isEmpty()) _coreIndexes.value = cores

        val prev = lastTick
        val now = System.currentTimeMillis()
        lastTick = tick
        lastTickAtMs = now

        val cpuLoad = if (prev != null && tick.cpuTotal > prev.cpuTotal) {
            val dt = tick.cpuTotal - prev.cpuTotal
            val di = tick.cpuIdle - prev.cpuIdle
            ((dt - di) * 100f / dt).coerceIn(0f, 100f)
        } else {
            // 读不到 /proc/stat（无提权通道）时记不可读，不填 0
            LiveMetrics.UNREADABLE
        }

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
        val chargeW = estimateChargeW(tick)

        // 供桌面悬浮窗与前台服务通知显示；CPU 频率取各核平均（kHz → MHz）
        val cpuFreqMhz = if (coreFreqs.isEmpty()) 0 else (coreFreqs.average() / 1000.0).toInt()
        LiveMetrics.update(
            cpuLoad = cpuLoad,
            gpuLoad = tick.gpuLoad,
            cpuFreqMhz = cpuFreqMhz,
            gpuMhz = if (tick.gpuMhz > 0) tick.gpuMhz.toInt() else -1,
            memAvailKb = (tick.memTotalKb - tick.memUsedKb).coerceAtLeast(0L),
            memTotalKb = tick.memTotalKb,
            powerMw = powerMw,
            batteryTempC = tick.batteryTempC,
            chargeW = chargeW,
        )

        // 耗电录制：喂一条采样。
        //
        // 电量取自电池快照（每 5 秒刷新）——电量本身以 1% 为步进，
        // 每秒重读一次不会多出任何信息，反而多一次 binder 调用。
        // 能量读数则每拍都取：ENERGY_COUNTER 是直接测量值，比按比例折算准。
        val battery = _battery.value
        val energy = BatteryDataSource.readEnergy(context, battery)
        _batteryEnergy.value = energy
        if (PowerRecorder.recording.value) {
            PowerRecorder.onTick(
                nowMs = now,
                levelPercent = battery.levelPercent,
                voltageMv = tick.voltageMv.toInt(),
                tempC = tick.batteryTempC,
                powerMw = powerMw,
                charging = isExternallyPowered(battery),
                remainWhNow = energy.remainWh,
                fullWhNow = energy.fullWh,
            )
        }

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

        _history.value = (_history.value + sample).takeLast(HISTORY_MAX_SAMPLES)

        // 帧率记录：同时留档同期系统指标，便于事后定位卡顿成因
        if (FpsRecorder.recording.value) {
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

    /**
     * 重新读取「使用情况访问」授权状态。
     *
     * 用户可能在设置页里刚授予权限再切回来，所以耗电统计页每次进入都重读一次，
     * 而不是只在进程启动时读一次。
     */
    fun refreshUsageAccess() {
        viewModelScope.launch {
            _usageAccess.value = PowerStatsDataSource.hasUsageAccess(context)
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

    /**
     * 调整 ZRAM 容量（KB），会重建交换分区。
     *
     * 闸门在 ViewModel 侧再挡一次：界面已把控件置灰，但状态可能被别处改写，
     * 这里拒绝执行可以保证「未开启就绝不会真的重建交换分区」。
     * 容量 0 表示关闭该 zram 交换设备，由 [MemDataSource.resizeZram] 处理。
     */
    fun resizeZram(sizeKb: Long) {
        if (!_zramResizeEnabled.value) return
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

    // ---------------- 内存清理 ----------------

    /** 最近一次内存清理的结果；界面展示后调用 [clearMemCleanState] 收起 */
    private val _memCleanState = MutableStateFlow<MemCleanResult?>(null)
    val memCleanState: StateFlow<MemCleanResult?> = _memCleanState.asStateFlow()

    fun clearMemCleanState() {
        _memCleanState.value = null
    }

    /** 释放物理内存中的可回收缓存（需要 root） */
    fun cleanMemCaches() {
        viewModelScope.launch {
            _memCleanState.value = withContext(Dispatchers.IO) { MemDataSource.dropCaches() }
            fullSnapshot()
        }
    }

    /** 重建交换分区以清空已用交换（需要 root） */
    fun cleanSwap() {
        viewModelScope.launch {
            _memCleanState.value = withContext(Dispatchers.IO) { MemDataSource.dropSwap() }
            fullSnapshot()
        }
    }

    // ---------------- 帧率记录会话 ----------------

    /**
     * 记录会话状态。
     *
     * 直接复用 [FpsRecorder] 的单例状态，而不是在 ViewModel 里另存一份：
     * 桌面悬浮窗轻点也能开始/停止记录，两处必须是同一个真值，
     * 否则「悬浮窗开了记录、页面开关却显示关闭」。
     */
    val fpsRecording: StateFlow<Boolean> = FpsRecorder.recording

    private val _fpsRecords = MutableStateFlow<List<FpsRecord>>(emptyList())
    val fpsRecords: StateFlow<List<FpsRecord>> = _fpsRecords.asStateFlow()

    private val _lastExportPath = MutableStateFlow<String?>(null)
    val lastExportPath: StateFlow<String?> = _lastExportPath.asStateFlow()

    /** 悬浮窗服务的真实运行状态（不是偏好值） */
    val overlayRunning: StateFlow<Boolean> = FpsOverlayState.running

    /** 悬浮窗不透明度（0.25~1.0），改动会立即作用到悬浮窗 */
    val overlayAlpha: StateFlow<Float> = FpsOverlayState.alpha

    fun setOverlayAlpha(value: Float) {
        val v = value.coerceIn(FpsOverlayState.MIN_ALPHA, 1f)
        FpsOverlayState.setAlpha(v)
        Preferences.setOverlayAlpha(context, v)
    }

    // ---------------- 实时任务通知的呈现方式与显示项 ----------------

    /** true = 用实时任务通知；false = 用悬浮窗 */
    val liveNotifEnabled: StateFlow<Boolean> = LiveNotif.enabled

    /** 通知 / 悬浮窗中显示的指标项，1~[LiveNotif.MAX_METRICS] 项 */
    val notifMetrics: StateFlow<List<NotifMetric>> = LiveNotif.metrics

    /**
     * 通知模式下是否保留「保活锚点」（1×1 不可见悬浮窗）。
     * 关闭后通知在后台约 5~10 秒即停更——这是系统的冻结行为，不是应用能绕过的。
     */
    val notifKeepAlive: StateFlow<Boolean> = LiveNotif.keepAlive

    fun setNotifKeepAlive(enabled: Boolean) {
        LiveNotif.setKeepAlive(context, enabled)
    }

    /** 悬浮窗权限是否已授予；关闭实时任务通知（切到悬浮窗）的前提 */
    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    /**
     * 切换呈现方式。
     *
     * 同时确保前台服务在运行——否则用户改完开关看不到任何变化，
     * 会以为功能没生效。切到悬浮窗需要悬浮窗权限，未授权时**不切换**
     * （界面负责提示并引导授权），避免出现「开关已关、通知却还在」的错位。
     */
    fun setLiveNotifEnabled(enabled: Boolean) {
        if (!enabled && !canDrawOverlay()) return
        LiveNotif.setEnabled(context, enabled)
        Preferences.setFpsOverlayEnabled(context, true)
        FpsOverlayService.start(context)
    }

    /**
     * 勾选 / 取消一个指标项。
     *
     * 数量被夹在 [LiveNotif.MIN_METRICS]..[LiveNotif.MAX_METRICS] 之间：
     * 上限来自系统的「展开态最多 3 项指标」，下限来自 MetricStyle 不接受空样式。
     * 达到边界时静默忽略本次点击，界面同步把这些项置灰。
     */
    fun toggleNotifMetric(metric: NotifMetric) {
        val current = LiveNotif.metrics.value
        val next = when {
            metric in current && current.size > LiveNotif.MIN_METRICS -> current - metric
            metric !in current && current.size < LiveNotif.MAX_METRICS -> current + metric
            else -> return
        }
        LiveNotif.setMetrics(context, next)
    }

    /** 恢复出厂默认显示项（帧率 / CPU 占用 / GPU 占用） */
    fun resetNotifMetrics() {
        LiveNotif.setMetrics(context, LiveNotif.defaultMetrics)
    }

    fun startFpsRecording() {
        FpsRecorder.startRecording()
    }

    fun stopFpsRecording() {
        FpsRecorder.stopRecording()
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
            PrivilegeManager.redetect()
        }
    }

    // ---------------- 提权模式 ----------------

    /**
     * 切换到指定提权模式。
     *
     * 切换后要重跑一次完整快照：不同模式下能读到的节点不同
     * （例如 Shizuku 读不到 /data/adb，性能调度摘要会变成「不可用」），
     * 不刷新的话界面会停留在上一个模式的读数上。
     */
    fun selectPrivilegeMode(mode: PrivilegeMode) {
        viewModelScope.launch {
            val ok = PrivilegeManager.select(mode)
            if (ok) {
                _cpu.value = CpuDataSource.read()
                refreshPerfSched()
            }
            _privilegeMessage.value = if (ok) {
                "已切换到「${mode.label}」"
            } else {
                when (mode) {
                    PrivilegeMode.ROOT -> "Root 不可用：未检测到已授权的 su"
                    PrivilegeMode.SHIZUKU ->
                        "Shizuku 不可用：请确认已安装并启动 Shizuku，且已授权本应用"
                    PrivilegeMode.ADB ->
                        "ADB 不可用：请开启「无线调试」并完成配对；" +
                            "或用数据线连电脑，执行设置页给出的 adb 命令拉起守护进程" +
                            "（后者在设备重启后需重新执行）"
                }
            }
        }
    }

    /** 对 Shizuku 请求 shell 权限 */
    fun requestShizukuPermission() {
        viewModelScope.launch {
            val backend = PrivilegeManager.shizuku()
            if (backend == null || !backend.isSupported()) {
                _privilegeMessage.value = "Shizuku 未运行：请先安装并启动 Shizuku"
                return@launch
            }
            val granted = backend.requestPermission()
            _privilegeMessage.value =
                if (granted) "Shizuku 已授权" else "Shizuku 授权被拒绝"
            PrivilegeManager.refresh(preferred = PrivilegeMode.SHIZUKU)
        }
    }

    /**
     * ADB 无线调试配对。
     *
     * 配对端口是随机的、且只在「使用配对码配对设备」页面打开期间才广播，
     * 因此端口留空时现查一次——用户只需要填那 6 位配对码。
     */
    fun pairAdb(port: Int?, code: String, host: String? = null) {
        viewModelScope.launch {
            if (code.trim().length != 6) {
                _privilegeMessage.value = "配对码应为 6 位数字"
                return@launch
            }
            _privilegeMessage.value = "正在查找配对端口…"
            val resolvedPort = port ?: PrivilegeManager.discoverPairingPort()
            if (resolvedPort == null || resolvedPort <= 0) {
                _privilegeMessage.value =
                    "未找到配对端口：请先在开发者选项里打开「使用配对码配对设备」页面，再点开始配对"
                return@launch
            }
            _privilegeMessage.value = "正在配对（端口 $resolvedPort）…"
            val result = PrivilegeManager.pairAdb(host, resolvedPort, code)
            _privilegeMessage.value = when (result) {
                is AdbPairClient.Result.Success -> "配对成功，已切换到 ADB 模式"
                is AdbPairClient.Result.WrongCode -> "配对失败：配对码错误或已过期"
                is AdbPairClient.Result.TlsError -> result.detail
                is AdbPairClient.Result.Failed -> "配对失败：${result.detail}"
            }
            if (result is AdbPairClient.Result.Success) {
                _cpu.value = CpuDataSource.read()
                refreshPerfSched()
            }
        }
    }

    /**
     * 手动连接无线调试**连接端口**。
     *
     * 配对与连接是两个不同端口：配对给了我们身份，连接才是拿 shell 的通路。
     * 正常情况下 mDNS 会自动发现连接端口，这里只是发现失败时的兜底。
     */
    fun connectAdb(port: Int, host: String = "127.0.0.1") {
        viewModelScope.launch {
            _privilegeMessage.value = "正在连接…"
            // 端口留空（传 0）时先让 mDNS 查一次，查不到再报错。
            // 无线调试的连接端口同样是随机的，让用户去翻没有道理。
            val resolved = if (port > 0) port else PrivilegeManager.discoverConnectPort() ?: 0
            if (resolved <= 0) {
                _privilegeMessage.value =
                    "未找到连接端口：请确认「无线调试」开关处于开启状态，或手动填入端口号"
                return@launch
            }
            val ok = PrivilegeManager.adbConnect(host, resolved)
            _privilegeMessage.value =
                if (ok) "已连接无线调试（端口 $resolved）"
                else "连接失败：请检查端口，并确认「无线调试」开关处于开启状态"
            if (ok) {
                PrivilegeManager.refresh(preferred = PrivilegeMode.ADB)
                _cpu.value = CpuDataSource.read()
                refreshPerfSched()
            }
        }
    }

    // ------------------------------------------------------------------
    // 脚本激活通道（照搬 Scene 的 up.sh 方式）
    //
    // 与上面的配对流程互补：配对是应用内自动、依赖无线调试；
    // 脚本是用户从电脑跑一条 adb 命令、只要有 USB 就行，不需要配对。
    // ------------------------------------------------------------------

    /** 激活脚本的路径与调用命令 */
    data class ScriptInfo(val path: String, val command: String)

    private val _scriptInfo = MutableStateFlow<ScriptInfo?>(null)

    /** 脚本落点与可复制的 adb 命令；未部署时为 null */
    val scriptInfo: StateFlow<ScriptInfo?> = _scriptInfo.asStateFlow()

    private val _scriptCapabilities = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 脚本实测出的 shell 能力表（`up.sh` 第 3 步的探测结果） */
    val scriptCapabilities: StateFlow<Map<String, String>> = _scriptCapabilities.asStateFlow()

    /** 把 `up.sh` 从 assets 部署到外部私有目录，供 adb 读取执行 */
    fun deployActivationScript() {
        when (val r = PrivilegeManager.deployActivationScript()) {
            is AdbScriptDeployer.DeployResult.Ok -> {
                _scriptInfo.value = ScriptInfo(
                    path = r.path,
                    command = PrivilegeManager.activationCommand().orEmpty(),
                )
                _privilegeMessage.value = "脚本已部署，可用电脑执行下方命令"
            }
            is AdbScriptDeployer.DeployResult.Failed -> {
                _privilegeMessage.value = "部署失败：${r.reason}"
            }
        }
    }

    /**
     * 通过**已建立的 ADB 通道**执行激活脚本。
     *
     * 这是相对 Scene 的增强 —— Scene 只能由用户从电脑手动执行。
     */
    fun runActivationScript() {
        viewModelScope.launch {
            _privilegeMessage.value = "正在执行激活脚本…"
            val res = PrivilegeManager.runActivationScript()
            _privilegeMessage.value = if (res.success) {
                // 脚本输出较长，只保留结尾部分，前面是逐项进度
                "激活完成\n" + res.stdout.trim().takeLast(320)
            } else {
                "激活失败：" + res.stderr.ifBlank { res.stdout }.trim().take(200)
            }
            if (res.success) {
                _scriptCapabilities.value = PrivilegeManager.readScriptCapabilities()
                PrivilegeManager.refresh()
            }
        }
    }

    /** 提权操作的回执，界面读取后应自行清空（[clearPrivilegeMessage]） */
    private val _privilegeMessage = MutableStateFlow<String?>(null)
    val privilegeMessage: StateFlow<String?> = _privilegeMessage.asStateFlow()

    fun clearPrivilegeMessage() {
        _privilegeMessage.value = null
    }

    /**
     * 运行 SPAKE2 自检。
     *
     * 之所以做成界面上可点的操作而不是单元测试：这段密码学代码是自行移植的，
     * 而 R8 会裁剪无人调用的方法——被裁剪掉的自检等于不存在。
     * 挂在这里既保证它在 Release 包里存活，也让用户/开发者随时能在真机上验证。
     *
     * @return 面向用户的结果描述
     */
    fun runSpake2SelfTest(): String = runCatching {
        val r = Spake2.selfTest()
        buildString {
            append(if (r.allPassed) "全部通过" else "存在问题")
            append("（标量乘向量 ")
            append(if (r.scalarMultVectorOk) "✓" else "✗")
            append("，密钥一致 ")
            append(if (r.keyAgreementOk) "✓" else "✗")
            append("，错误口令分离 ")
            append(if (r.wrongPasswordMismatchOk) "✓" else "✗")
            append("）")
            if (!r.scalarMultVectorOk) {
                // 向量不符说明域运算被改坏了，带上实际值便于比对
                append("\n2B 实际=")
                append(r.twoBHex)
            }
        }
    }.getOrElse { "自检执行失败：${it.message ?: it.javaClass.simpleName}" }

    // ---------------- 耗电录制 ----------------

    /** 是否正在录制耗电 */
    val powerRecording: StateFlow<Boolean> = PowerRecorder.recording

    /** 本次录制的全部采样点（1 秒 1 条，上限 2 小时） */
    val powerSamples: StateFlow<List<PowerSample>> = PowerRecorder.samples

    /** 本次录制的汇总：平均功耗 / 理论续航 / 能量 */
    val powerSummary: StateFlow<PowerRecordSummary> = PowerRecorder.summary

    /** 本次录制期间的应用耗电明细 */
    val powerDrains: StateFlow<List<AppDrainEntry>> = PowerRecorder.appDrains

    /** 本次录制期间各应用的 CPU 占用时间序列（曲线图用） */
    val powerCpuSeries: StateFlow<List<AppCpuPoint>> = PowerRecorder.appCpuSeries

    /**
     * 是否存在本次记录。
     *
     * 顶栏动作只需要知道「按钮能不能点」，**不能直接订阅 [powerSamples]**：
     * 那个列表每秒都在变，根布局一旦订阅它就会跟着每秒重组一次，
     * 连顶栏按钮一起重建——录制期间点击会变得不灵敏。
     * 这里用 `map + stateIn` 把「每秒变化」收敛成「只在有/无之间翻转」。
     */
    val powerHasRecord: StateFlow<Boolean> = PowerRecorder.samples
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 电池能量读数（Wh），随采样刷新 */
    private val _batteryEnergy = MutableStateFlow(BatteryEnergy())
    val batteryEnergy: StateFlow<BatteryEnergy> = _batteryEnergy.asStateFlow()

    /**
     * 电源页当前页签。
     *
     * 放在 ViewModel 而不是页面里：顶栏由根布局渲染，要根据它决定
     * 是否显示「复制 / 删除本次记录」两个动作——页内状态无法被顶栏读到。
     */
    private val _powerTab = MutableStateFlow(0)
    val powerTab: StateFlow<Int> = _powerTab.asStateFlow()

    fun setPowerTab(index: Int) {
        _powerTab.value = index
    }

    /** 开始录制。会先清空上一次记录，避免两次录制的曲线接在一起 */
    fun startPowerRecording() {
        viewModelScope.launch { PowerRecorder.start(context) }
    }

    /** 结束录制并锁定本次记录 */
    fun stopPowerRecording() {
        viewModelScope.launch { PowerRecorder.stop(context) }
    }

    /** 丢弃本次记录 */
    fun clearPowerRecord() {
        PowerRecorder.clear()
    }

    /**
     * 「已复制」回执。
     *
     * 放在 ViewModel 而不是页面里：复制有两个入口（顶栏图标、卡内操作条），
     * 而回执原本只是页面内的一个局部状态——于是顶栏复制会**静默成功**，
     * 用户点完看不到任何反馈，只能靠猜。两个入口现在读同一个状态。
     */
    private val _powerCopied = MutableStateFlow(false)
    val powerCopied: StateFlow<Boolean> = _powerCopied.asStateFlow()

    /**
     * 把本次记录写入剪贴板，返回是否成功。
     *
     * 放在 ViewModel 而不是页面里：顶栏的「复制」动作由根布局渲染，
     * 它拿不到页面内的 Context，也不该为了复制而各自去取一次剪贴板服务。
     */
    fun copyPowerRecord(): Boolean {
        val text = powerRecordText()
        if (text.isEmpty()) return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val ok = runCatching {
            cm.setPrimaryClip(ClipData.newPlainText("OSPlus 耗电记录", text))
            true
        }.getOrDefault(false)
        if (ok) {
            _powerCopied.value = true
            viewModelScope.launch {
                delay(3_000)
                _powerCopied.value = false
            }
        }
        return ok
    }

    /**
     * 把本次记录汇总成可粘贴的纯文本，供顶栏「复制」使用。
     *
     * 有意做成文本而不是图表截图：贴到聊天/笔记里要的是可检索的数字，
     * 截图既不可搜索也无法二次计算。
     */
    fun powerRecordText(): String {
        val samples = PowerRecorder.samples.value
        if (samples.isEmpty()) return ""
        val s = PowerRecorder.summary.value
        val last = samples.last()
        return buildString {
            appendLine("OSPlus 耗电记录")
            appendLine(
                "时长 ${formatSpan(s.durationMs)} ｜ 电量 ${s.startLevel}% → ${s.endLevel}%" +
                    "（${if (s.levelDrop >= 0) "-" else "+"}${kotlin.math.abs(s.levelDrop)}%）"
            )
            if (s.charging) {
                appendLine("录制期间处于充电状态，未计算耗电功耗")
            } else if (s.avgPowerW > 0f) {
                val method = when (s.source) {
                    PowerSource.Current -> "电流法"
                    PowerSource.LevelDelta -> "电量差法"
                    PowerSource.Unavailable -> "—"
                }
                appendLine("平均功耗 %.2f W（%s）".format(s.avgPowerW, method))
                appendLine("理论续航 ${formatSpan(s.theoreticalRemainMs)}")
            } else {
                appendLine("平均功耗不可用（电流与电量差都不足以推算）")
            }
            if (s.fullWh > 0f) {
                appendLine("电池能量 %.1f Wh ｜ 剩余 %.1f Wh".format(s.fullWh, s.remainWh))
            }
            last.tempC?.let { appendLine("温度 %.1f ℃".format(it)) }
            if (last.voltageMv > 0) appendLine("电压 %.3f V".format(last.voltageMv / 1000f))
            appendLine("采样 ${samples.size} 条")
            val top = PowerRecorder.appDrains.value.take(5)
            if (top.isNotEmpty()) {
                appendLine("耗电前列（CPU 加权估算）：")
                top.forEach { appendLine("  ${it.label}  %.1f%%".format(it.drainPercent)) }
            }
        }.trim()
    }

    /**
     * 是否处于外部供电。
     *
     * 用 `plugged` 而不是 `status`：`status` 的取值里「未充电」也含「充电」二字，
     * 用 `contains("充电")` 判断会把「未充电」误判成充电中。
     */
    private fun isExternallyPowered(b: BatteryInfo): Boolean =
        b.plugged.isNotBlank() && b.plugged != "未连接"

    // ---------------- 性能调度（Uperf / A-SOUL 模块） ----------------

    private val _uperfState = MutableStateFlow(UperfState())
    val uperfState: StateFlow<UperfState> = _uperfState.asStateFlow()

    private val _asoulState = MutableStateFlow(AsoulState())
    val asoulState: StateFlow<AsoulState> = _asoulState.asStateFlow()

    /** 最近一次调度操作的回执；界面展示后调用 [clearSchedNotice] 收起 */
    private val _schedNotice = MutableStateFlow<String?>(null)
    val schedNotice: StateFlow<String?> = _schedNotice.asStateFlow()

    private val _schedRefreshing = MutableStateFlow(false)
    val schedRefreshing: StateFlow<Boolean> = _schedRefreshing.asStateFlow()

    fun clearSchedNotice() {
        _schedNotice.value = null
    }

    /** 读取两个模块的状态。进入调度页时调用，也可手动刷新 */
    fun refreshPerfSched() {
        viewModelScope.launch {
            _schedRefreshing.value = true
            val pair = runCatching { PerfSchedDataSource.read() }.getOrNull()
            if (pair != null) {
                _uperfState.value = pair.first
                _asoulState.value = pair.second
            }
            _schedRefreshing.value = false
        }
    }

    fun setUperfMode(mode: String) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { PerfSchedDataSource.setUperfMode(mode) }
            _schedNotice.value = r.message
            refreshPerfSched()
        }
    }

    fun setUperfPerAppRules(rules: List<PerAppRule>) {
        viewModelScope.launch {
            _uperfState.value = _uperfState.value.copy(rules = rules)
            val r = withContext(Dispatchers.IO) { PerfSchedDataSource.setUperfPerApp(rules) }
            _schedNotice.value = r.message
            refreshPerfSched()
        }
    }

    fun restartUperf() {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { PerfSchedDataSource.restartUperf() }
            _schedNotice.value = r.message
            refreshPerfSched()
        }
    }

    fun setAsoulConfig(mode: String, rt: String, games: List<AsoulGame>) {
        viewModelScope.launch {
            _asoulState.value = _asoulState.value.copy(mode = mode, rt = rt, games = games)
            val r = withContext(Dispatchers.IO) { PerfSchedDataSource.setAsoul(mode, rt, games) }
            _schedNotice.value = r.message
            refreshPerfSched()
        }
    }

    fun restartAsoul() {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { PerfSchedDataSource.restartAsoul() }
            _schedNotice.value = r.message
            refreshPerfSched()
        }
    }

}
