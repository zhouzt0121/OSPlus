package com.osplus.tools.vm

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import com.osplus.tools.model.FpsRecord
import com.osplus.tools.model.FpsSession
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import android.provider.MediaStore
import android.os.Environment
import android.graphics.Canvas
import android.graphics.Bitmap
import android.content.ContentValues
import android.util.Log
import com.osplus.tools.core.FpsCardRenderer
import com.osplus.tools.core.FpsOverlayState
import android.provider.Settings
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.osplus.tools.core.BatteryDataSource
import com.osplus.tools.core.BatteryEnergy
import com.osplus.tools.core.CpuDataSource
import com.osplus.tools.core.FpsRecorder
import com.osplus.tools.core.FpsRecordingController
import com.osplus.tools.core.FpsWatchStore
import com.osplus.tools.core.FpsSessionStats
import com.osplus.tools.core.SysFpsDataSource
import com.osplus.tools.core.GpuDataSource
import com.osplus.tools.core.LiveMetrics
import com.osplus.tools.core.LiveNotif
import com.osplus.tools.core.MonitorState
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
import com.osplus.tools.core.SystemProbe
import com.osplus.tools.core.SystemExtrasDataSource
import com.osplus.tools.core.SystemTogglesDataSource
import com.osplus.tools.service.FpsOverlayService
import com.osplus.tools.service.MonitorOverlayService
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

    /** 当前生效的提权模式，null 表示未就绪 */
    val privilegeMode: StateFlow<PrivilegeMode?> = PrivilegeManager.mode

    /**
     * 用户在设置页点选的模式（见 [PrivilegeManager.selection]）。
     *
     * 界面用它驱动「提权方式」的选中态与详情展示——**点了就跟着变**，
     * 不受「该模式当前能不能用」影响；实际生效的通道仍是 [privilegeMode]。
     */
    val privilegeSelection: StateFlow<PrivilegeMode?> = PrivilegeManager.selection

    /** 当前模式下各项能力的可用性，界面据此决定控件是否可点 */
    val capabilities: StateFlow<PrivilegeCapabilities> = PrivilegeManager.capabilities

    /** 本机的可探测状态，界面用来展示「能不能用」 */
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

        // 悬浮窗管理器：先把开关恢复到单例（服务订阅它），再按需拉起服务。
        // 顺序不能反——服务在 onCreate 里立刻订阅 MonitorState.enabled，
        // 若那时集合还是空的，已开启的监视器会一个都不建出来。
        MonitorState.load(context)
        if (MonitorState.enabled.value.isNotEmpty() && Settings.canDrawOverlays(context)) {
            MonitorOverlayService.start(context)
        }
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
                    // 扩展指标只有显式开启时才采：它是额外一条 shell 命令，
                    // 只看 CPU / 内存 / 功耗的用户不该为此付往返开销
                    if (_extrasEnabled.value) {
                        runCatching { SystemExtrasDataSource.read() }
                            .onSuccess { _extras.value = it }
                    }
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
        // 系统级帧率：只在「有人看」的时候才采，避免无谓地每秒起一个 shell 进程。
        // 有人看 = 正在记录 / 帧率页可见（sysFpsWanted）/ **悬浮窗正在运行**
        //          / **悬浮窗管理器的监视器正在运行**。
        //
        // 悬浮窗必须算进来：它的全部意义就是「盖在别的应用上面看帧率」，
        // 那种场景下本应用自己的逐帧回调早已不代表屏幕实况，只靠它会把
        // 悬浮窗的数字带偏。少了这一条，悬浮窗会一直退回自身值。
        //
        // 监视器（MonitorState.running）同理，而且**更不能少**：
        // 管理器可以只开温度/进程监视器而不开帧率悬浮窗，此时若不计入，
        // sysFps 恒为 -1，迷你/负载监视器就会退回读 FpsRecorder 的自测值——
        // 而应用退到后台后 Choreographer 回调不受 vsync 约束，
        // 该值会飙到 500~600 这种物理上不可能的数字（实测 597.8）。
        val sysFps: Float = if (
            FpsRecorder.recording.value || sysFpsWanted ||
            FpsOverlayState.running.value || MonitorState.running.value
        ) {
            runCatching { SysFpsDataSource.read() }.getOrNull() ?: -1f
        } else {
            -1f
        }
        _sysFps.value = sysFps
        // 节点普查：仅 debug 构建、每次进程生命周期跑一次。
        // 用途是换机型后快速确认「哪些节点真的可读」（见 SystemProbe.census 的说明），
        // release 包不跑，避免无谓开销。
        if (!censusDone && com.osplus.tools.BuildConfig.DEBUG && Shell.isRootAvailable()) {
            censusDone = true
            runCatching { SystemProbe.census() }
        }
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
            // 悬浮窗 / 通知要用：它们跑在 Service 里，拿不到 VM 的 _sysFps
            sysFps = sysFps,
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
            // **帧率取「有效值」：系统级优先。**
            // 系统级读的是显示控制器实际输出，是屏幕上真实发生的帧率（切到游戏里也有效）；
            // 应用自身 Choreographer 只反映本应用的渲染节奏，后台时几乎无意义。
            // 系统级不可读（-1 / 0）时才退回自身值，保证界面永远有数可看。
            fps = if (sysFps > 0f) sysFps else fpsState.fps,
            fpsApp = fpsState.fps,
            batteryTempC = tick.batteryTempC,
        )

        _history.value = (_history.value + sample).takeLast(HISTORY_MAX_SAMPLES)

        // 帧率记录：同时留档同期系统指标，便于事后定位卡顿成因
        if (FpsRecorder.recording.value) {
            val record = FpsRecord(
                timeMs = now,
                // 与 MetricSample.fps 同口径：有效值（系统级优先），保证图表与记录一致
                fps = if (sysFps > 0f) sysFps else fpsState.fps,
                fpsApp = fpsState.fps,
                fpsSys = sysFps,
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
            // 内存侧只保留最近一段给「实时视图」用；**完整记录落 SQLite**，
            // 这样长录制（整局游戏）不再受 7200 条上限截断，杀进程也不丢。
            _fpsRecords.value = (_fpsRecords.value + record).takeLast(MAX_FPS_RECORDS)
            // 落库统一走控制器：它持有 activeSessionId，且服务侧触发的录制
            // 也会正常落库（ViewModel 与服务共享同一份会话状态）。
            FpsRecordingController.addSample(context, record)
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

    // ---------------- 帧率记录会话化（SQLite 持久化） ----------------

    /** 帧率记录的持久化存储（查询/删除用；写入与录制生命周期见 [FpsRecordingController]） */
    private val fpsStore: FpsWatchStore by lazy { FpsWatchStore(context) }

    // 注：`activeSessionId` 与 `startJob` 已迁到 [FpsRecordingController]。
    // 原因是监视器悬浮窗（由 MonitorOverlayService 承载）也要能启停录制，
    // 而服务拿不到 ViewModel；会话状态必须收敛到进程级单例，否则服务与
    // ViewModel 各持一份、互相覆盖，会开出重复会话或丢数据。

    /** 历史会话列表（按开始时间倒序） */
    private val _fpsSessions = MutableStateFlow<List<FpsSession>>(emptyList())
    val fpsSessions: StateFlow<List<FpsSession>> = _fpsSessions.asStateFlow()

    /** 当前选中的历史会话及其采样，供双轴图查看 */
    private val _viewingSessionId = MutableStateFlow(-1L)
    val viewingSessionId: StateFlow<Long> = _viewingSessionId.asStateFlow()

    private val _viewingSamples = MutableStateFlow<List<FpsRecord>>(emptyList())
    val viewingSamples: StateFlow<List<FpsRecord>> = _viewingSamples.asStateFlow()

    // ---------------- 录制记录分析（独立整页） ----------------

    /**
     * 正在分析查看的会话 id；-1 表示没有。
     *
     * 之所以放到 ViewModel 而不是留在 FpsScreen 的 `remember`：分析卡现在是
     * **独立路由页**，FpsScreen 与 FpsAnalysisScreen 是两个 Composable，
     * 局部状态无法跨页传递，路由也不适合携带复杂参数。
     */
    private val _analysisSessionId = MutableStateFlow(-1L)
    val analysisSessionId: StateFlow<Long> = _analysisSessionId.asStateFlow()

    /** 该会话的统计摘要；打开分析页时异步加载 */
    private val _analysisStats = MutableStateFlow<FpsSessionStats?>(null)
    val analysisStats: StateFlow<FpsSessionStats?> = _analysisStats.asStateFlow()

    /** 打开某会话的分析页：加载统计摘要 */
    fun openFpsAnalysis(sessionId: Long) {
        _analysisSessionId.value = sessionId
        _analysisStats.value = null
        viewModelScope.launch {
            _analysisStats.value = fpsSessionStats(sessionId)
        }
    }

    /** 关闭分析页时清空，避免下次进入残留上一次的数据 */
    fun closeFpsAnalysis() {
        _analysisSessionId.value = -1L
        _analysisStats.value = null
    }

    /** 按包名解析应用显示名（用于分析卡顶栏、会话列表） */
    fun appLabel(packageName: String?): String {
        if (packageName.isNullOrBlank()) return ""
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    /** 系统级帧率（整机实测），-1 表示不可读 */
    private val _sysFps = MutableStateFlow(-1f)

    /** 【临时诊断】节点普查只跑一次 */
    private var censusDone = false
    val sysFps: StateFlow<Float> = _sysFps.asStateFlow()

    /**
     * 帧率页是否可见。
     *
     * 系统级帧率靠 shell 读 sysfs，每秒一次进程开销不小；页面不可见且未在记录时
     * 就没必要采，因此由页面在进入/离开时置位。
     */
    @Volatile
    private var sysFpsWanted: Boolean = false

    fun setSysFpsWanted(wanted: Boolean) {
        sysFpsWanted = wanted
        if (!wanted) _sysFps.value = -1f
    }

    /** 刷新历史会话列表 */
    fun refreshFpsSessions() {
        viewModelScope.launch {
            _fpsSessions.value = withContext(Dispatchers.IO) { fpsStore.sessions() }
        }
    }

    /** 打开某个历史会话，加载其采样供绘图 */
    fun openFpsSession(sessionId: Long) {
        viewModelScope.launch {
            // **先加载、后置 id**。反过来的话界面会有一瞬间处于
            // 「viewingSessionId 已设但 viewingSamples 还空」的中间态，
            // FpsScreen 此时会落到空状态分支，用户看到一闪而过的
            // 「开启开始记录后…」提示，像是没打开成功。
            val data = withContext(Dispatchers.IO) { fpsStore.samples(sessionId) }
            _viewingSamples.value = data
            _viewingSessionId.value = sessionId
        }
    }

    /** 关闭会话查看，回到当前记录视图 */
    fun closeFpsSession() {
        _viewingSessionId.value = -1L
        _viewingSamples.value = emptyList()
    }

    /** 删除一个历史会话 */
    fun deleteFpsSession(sessionId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { fpsStore.deleteSession(sessionId) }
            if (_viewingSessionId.value == sessionId) closeFpsSession()
            refreshFpsSessions()
        }
    }

    /** 查询某会话的统计（均值 / 极值 / 低帧占比 / 高温占比） */
    suspend fun fpsSessionStats(sessionId: Long): FpsSessionStats =
        withContext(Dispatchers.IO) { fpsStore.statsOf(sessionId) }

    private val _lastExportPath = MutableStateFlow<String?>(null)
    val lastExportPath: StateFlow<String?> = _lastExportPath.asStateFlow()

    /** 分析卡图片导出后的落盘路径（null = 尚未导出） */
    private val _lastCardPath = MutableStateFlow<String?>(null)
    val lastCardPath: StateFlow<String?> = _lastCardPath.asStateFlow()

    /**
     * 把一次记录会话的分析卡渲染成 PNG 并写入相册。
     *
     * 用 [FpsCardRenderer] 精绘固定 1200×900（严格 4:3），而不是截界面上的
     * Compose 卡片——导出的图尺寸与视觉不随屏幕密度、当前主题、页面状态
     * 变化，用户在任意设备上得到的都是同一张图。
     *
     * @param cropLabel 屏幕分辨率字符串，形如 `540x1200`
     */
    fun exportFpsCard(session: FpsSession, stats: FpsSessionStats, cropLabel: String) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                val dark = when (_themeMode.value) {
                    AppThemeMode.Dark -> true
                    AppThemeMode.Light -> false
                    // 跟随系统：读系统当前的深色开关（uiMode 的 night 位）
                    AppThemeMode.System -> {
                        val night = context.resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK
                        night == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    }
                }
                val result: String? = runCatching {
                    val bmp = FpsCardRenderer.render(session, stats, cropLabel, dark)
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val name = "OSPlus_fps_analysis_$stamp.png"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/OSPlus",
                        )
                    }
                    val uri = context.contentResolver
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: return@runCatching null
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                    bmp.recycle()
                    "图片/OSPlus/$name"
                }.getOrNull()
                result
            }
            _lastCardPath.value = path
        }
    }


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

    /**
     * 开始帧率记录。
     *
     * 完整流程（查前台包 → 建会话 → 打开采样）已收归
     * [FpsRecordingController]——因为**服务侧的监视器悬浮窗也要能触发录制**，
     * 而服务拿不到本 ViewModel。此处只做「关掉历史查看 + 委托」两件事。
     */
    fun startFpsRecording() {
        closeFpsSession()
        FpsRecordingController.start(context)
    }

    /**
     * 停止帧率记录并收尾会话。
     *
     * 同样委托给 [FpsRecordingController]：它会取消可能仍在进行的建会话作业、
     * 写回 `time_end`，并通知会话列表刷新。
     */
    fun stopFpsRecording() {
        FpsRecordingController.stop(context)
    }

    fun clearFpsRecords() {
        // 先停掉录制再清库：否则每秒节拍仍在往一个刚被清空的会话里写数据，
        // 用户会看到「清空后又冒出几条」。停录制交给控制器（它会收尾会话）。
        FpsRecordingController.stop(context)
        FpsRecordingController.resetState()
        _fpsRecords.value = emptyList()
        FpsRecorder.reset()
        closeFpsSession()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { fpsStore.clearAll() }
            refreshFpsSessions()
        }
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

    /**
     * 完整重新探测提权通道。
     *
     * 与 [refreshRootState] 的差别：后者只重探 root 探测缓存 + 让后端重连，
     * 不重跑「通道是否可用」那套判定。提权管理页的「重新探测」按钮
     * 期望的是后者——用户刚在系统里授权完 root，需要看到状态立刻刷新。
     *
     * 两个都做，顺序不能反：先让 root 探测缓存失效，再跑模式判定，
     * 否则模式判定读到的还是旧的 root 结果。
     */
    fun reprobePrivilege() {
        viewModelScope.launch {
            _rootAvailable.value = Shell.isRootAvailable(force = true)
            PrivilegeManager.redetect()
            PrivilegeManager.refresh(probeRoot = false)
            _privilegeMessage.value = "已重新探测提权通道"
        }
    }

    // ---------------- 提权模式 ----------------

    /**
     * 切换到指定提权模式。
     *
     * 切换后要重跑一次完整快照：不同模式下能读到的节点不同，
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
                }
            }
        }
    }

    /** 提权操作的回执，界面读取后应自行清空（[clearPrivilegeMessage]） */
    private val _privilegeMessage = MutableStateFlow<String?>(null)
    val privilegeMessage: StateFlow<String?> = _privilegeMessage.asStateFlow()

    fun clearPrivilegeMessage() {
        _privilegeMessage.value = null
    }

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

    // ------------------------------------------------------------------
    // 系统开关（settings get/put）
    //
    // 状态用「一个 map 装全部开关」而不是十几个独立 StateFlow：
    // 读回时本来就是一条命令拿全量，拆开反而要在十几个地方各自刷新。
    // ------------------------------------------------------------------

    private val _sysToggles = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val sysToggles: StateFlow<Map<String, Boolean>> = _sysToggles.asStateFlow()

    private val _animationScale = MutableStateFlow("1.0")
    val animationScale: StateFlow<String> = _animationScale.asStateFlow()

    private val _iconBlacklist = MutableStateFlow<Set<String>>(emptySet())
    val iconBlacklist: StateFlow<Set<String>> = _iconBlacklist.asStateFlow()

    private val _sysToggleNotice = MutableStateFlow<String?>(null)
    val sysToggleNotice: StateFlow<String?> = _sysToggleNotice.asStateFlow()

    /** 是否已读过一次开关状态。未读过时界面显示加载态而不是「全部关闭」 */
    private val _sysTogglesLoaded = MutableStateFlow(false)
    val sysTogglesLoaded: StateFlow<Boolean> = _sysTogglesLoaded.asStateFlow()

    fun clearSysToggleNotice() {
        _sysToggleNotice.value = null
    }

    /** 读取全部系统开关。进入该页时调用一次，写入后也会自动回读 */
    fun refreshSystemToggles() {
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { SystemTogglesDataSource.readAll() }.getOrDefault(emptyMap())
            }
            if (raw.isEmpty()) {
                _sysToggleNotice.value = "读取失败：提权通道未就绪"
                return@launch
            }
            _sysToggles.value = SystemTogglesDataSource.TOGGLES.associate { def ->
                def.id to SystemTogglesDataSource.boolOf(raw, def)
            }
            _animationScale.value = SystemTogglesDataSource.animationScale(raw)
            _iconBlacklist.value = SystemTogglesDataSource.iconBlacklist(raw)
            _sysTogglesLoaded.value = true
        }
    }

    /**
     * 写一个开关。
     *
     * 乐观更新：先改本地状态让开关立刻响应，再落命令；失败则回滚并给出提示。
     * 不这样做的话，每次点击都要等一个 shell 往返（daemon 通道约 0.2 秒），
     * 滑动开关会出现明显回弹。
     */
    fun setSystemToggle(def: SystemTogglesDataSource.ToggleDef, enabled: Boolean) {
        val before = _sysToggles.value
        _sysToggles.value = before + (def.id to enabled)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { SystemTogglesDataSource.writeBool(def, enabled) }.getOrDefault(false)
            }
            if (!ok) {
                _sysToggles.value = before
                _sysToggleNotice.value = "「${def.label}」写入失败，已回滚"
            }
        }
    }

    fun setAnimationScale(scale: String) {
        val before = _animationScale.value
        _animationScale.value = scale
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { SystemTogglesDataSource.writeAnimationScale(scale) }.getOrDefault(false)
            }
            if (!ok) {
                _animationScale.value = before
                _sysToggleNotice.value = "动画缩放写入失败，已回滚"
            }
        }
    }

    /** 状态栏图标显隐。注意写的是**完整集合**，调用方要负责增删后再传 */
    fun toggleIconBlacklist(item: String) {
        val before = _iconBlacklist.value
        val next = if (item in before) before - item else before + item
        _iconBlacklist.value = next
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { SystemTogglesDataSource.writeIconBlacklist(next) }.getOrDefault(false)
            }
            if (!ok) {
                _iconBlacklist.value = before
                _sysToggleNotice.value = "状态栏图标写入失败，已回滚"
            }
        }
    }

    // ------------------------------------------------------------------
    // 扩展系统指标（负载 / 网络 / IO / 磁盘）
    //
    // 1 秒一次、与主采样循环同频。数据源内部自带差分基准，
    // 因此不需要在这里额外维护上一次的值。
    // ------------------------------------------------------------------

    private val _extras = MutableStateFlow(SystemExtrasDataSource.Extras())
    val extras: StateFlow<SystemExtrasDataSource.Extras> = _extras.asStateFlow()

    /** 扩展指标的开关。默认关闭——多一条 shell 命令，纯看 CPU/内存的用户不需要付这个代价 */
    private val _extrasEnabled = MutableStateFlow(false)
    val extrasEnabled: StateFlow<Boolean> = _extrasEnabled.asStateFlow()

    fun setExtrasEnabled(enabled: Boolean) {
        _extrasEnabled.value = enabled
        if (enabled) {
            viewModelScope.launch {
                val e = withContext(Dispatchers.IO) {
                    runCatching { SystemExtrasDataSource.read() }
                        .getOrDefault(SystemExtrasDataSource.Extras())
                }
                _extras.value = e
            }
        }
    }

}
