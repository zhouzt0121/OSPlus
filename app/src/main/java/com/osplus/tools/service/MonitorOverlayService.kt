package com.osplus.tools.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.osplus.tools.MainActivity
import com.osplus.tools.OsPlusApplication
import com.osplus.tools.R
import com.osplus.tools.core.CpuDataSource
import com.osplus.tools.core.FpsRecorder
import com.osplus.tools.core.FpsRecordingController
import com.osplus.tools.core.LiveMetrics
import com.osplus.tools.core.MonitorKind
import com.osplus.tools.core.MonitorState
import com.osplus.tools.core.Preferences
import com.osplus.tools.core.ProcessDataSource
import com.osplus.tools.core.ThreadDataSource
import com.osplus.tools.model.ThreadEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 悬浮窗管理器服务：同时承载最多 6 个**互相独立**的监视器悬浮窗。
 *
 * ## 为什么要独立窗口而不是一个窗口里的 6 张卡片
 *
 * 用户选择的模式就是「各自独立窗口」：每个监视器可以单独开关、单独拖动、
 * 单独摆到屏幕任意角落。合并成一个窗口时，「只想要帧率」的用户也得
 * 拖着包含另外 5 张卡片的大面板走，与「几乎不遮挡内容」的诉求直接冲突。
 *
 * 代价是窗口数量上去了，两条工程约束必须守住：
 *
 * 1. **保活锚点只能有一个**。锚点是「让进程持有可见窗口以免被 ColorOS 冻结」
 *    （见 [FpsOverlayService.addAnchor] 的完整原因），多个锚点没有额外收益，
 *    反而每多一个窗口就多一次被输入系统判定为遮挡的机会。因此这里只在
 *    「一个监视器都没开、但用户希望后台保活」时挂锚点；
 *    只要开了任意一个监视器，它们本身就是可见窗口，锚点反而是多余的。
 *
 * 2. **窗口 alpha 必须恒为 1.0**。Android 12 起 `TYPE_APPLICATION_OVERLAY`
 *    窗口的 `LayoutParams.alpha` 一旦 ≤0.8 就被判为「遮挡不足」，
 *    系统不再投递触摸事件（`Untrusted touch due to occlusion by <pkg>`），
 *    拖动与轻点全部失效。不透明度一律改在 `View.alpha` 上做。
 *
 * ## 数据来源
 *
 * 轻量指标（CPU / GPU / 内存 / 帧率 / 功耗 / 温度）复用 [LiveMetrics] 的共享快照
 * ——采样已在 ViewModel 里每秒跑过一次，服务不该再拉一套平行的 root 采样。
 * 重型指标（进程 / 线程列表）必须独立拉取，因为 ViewModel 不采它们；
 * 这两项各自带节流（见 [HEAVY_REFRESH_INTERVAL_MS]），避免每秒都跑
 * `top -b -n 1` 与 `top -b -n 1 -H`。
 */
class MonitorOverlayService : Service() {

    /** 一个监视器窗口的全部运行时状态 */
    private class MonitorWindow(
        val kind: MonitorKind,
        val root: LinearLayout,
        val titleView: TextView,
        val bodyView: TextView,
        val params: WindowManager.LayoutParams,
    ) {
        /** 上一次渲染的正文，用于跳过无变化的 setText（减少无谓的 re-layout） */
        var lastBody: String? = null
        /** 拖动中的临时不透明度提升标记 */
        var dragging = false
        /**
         * 上一次的录制状态（仅帧率窗口用）。
         *
         * 用 null 初值而非 false，是为了让第一帧必定重建一次背景
         * （否则「初始就是录制中」时会被判成「没变化」而漏掉红描边）。
         */
        var lastFrameRecording: Boolean? = null
    }

    private lateinit var windowManager: WindowManager
    private val windows = LinkedHashMap<MonitorKind, MonitorWindow>()

    /** 保活锚点（1×1 不可见窗口），与监视器窗口互斥，见类注释 */
    private var anchorView: View? = null
    private var anchorParams: WindowManager.LayoutParams? = null

    private var userAlpha = MonitorState.DEFAULT_ALPHA

    /** 进程列表缓存与上次拉取时刻（各自节流，见 [HEAVY_REFRESH_INTERVAL_MS]） */
    private var processCache: List<com.osplus.tools.model.ProcessEntry> = emptyList()
    private var processFetchedAt = 0L

    private var threadCache: List<ThreadEntry> = emptyList()
    private var threadFetchedAt = 0L

    /**
     * 温度读数缓存（CPU 结温 / GPU 温度）。
     *
     * 走 [CpuDataSource.readTemperatures] 的分类读取：它按 thermal zone 的
     * `type` 语义挑传感器，而不是固定读 `thermal_zone0`——编号由内核设备树
     * 注册顺序决定，本机 SM8750 上 `thermal_zone0` 其实是 `aoss-0`
     * （Always-On 子系统，比真实 CPU 结温低 3~4℃），拿它当 CPU 温度会低估。
     *
     * 比读 CPU 频率贵（要遍历全部 thermal zone），因此单独按
     * [HEAVY_REFRESH_INTERVAL_MS] 节流。
     */
    private var thermalCache: CpuDataSource.TemperatureBundle? = null
    private var thermalFetchedAt = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知上的「全部关闭」：清空开关并停服务。
        // 清开关必须在这里同步完成（而不是交给订阅去响应），
        // 因为 stopSelf 之后订阅协程可能来不及跑。
        if (intent?.action == ACTION_STOP_ALL) {
            MonitorState.setAll(this, emptySet())
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        MonitorState.setRunning(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 只从单例读首次值，不回写：单例才是真值来源，回写会在服务重启时
        // 用服务自己记的值覆盖用户在管理器页刚调好的值。
        userAlpha = MonitorState.alpha.value.coerceIn(MonitorState.MIN_ALPHA, 1f)

        // 与帧率悬浮窗共用同一个逐帧统计器，避免两套 Choreographer 互相干扰。
        // 即使只开温度监视器也要启动它：帧率记录器窗口需要它，而且开销可忽略。
        FpsRecorder.overlayHolds = true
        FpsRecorder.start()

        // 订阅开关：管理器页改动后无需重启服务即可增减窗口
        scope.launch {
            MonitorState.enabled.collectLatest { kinds -> syncWindows(kinds) }
        }

        // 订阅不透明度：滑块拖动时实时作用到全部窗口
        scope.launch {
            MonitorState.alpha.collectLatest { value -> applyAlpha(value) }
        }

        // 订阅记录状态：录制开始/结束时立刻刷新一次，让帧率窗口的边框
        // 描边（红=记录中）与录制开关状态即时同步，不必等下一个 1 Hz 节拍。
        scope.launch {
            FpsRecorder.recording.collectLatest { refreshAll() }
        }

        // 刷新节拍。轻量指标 1 秒一轮，重型指标在轮内按节流条件决定是否重拉。
        scope.launch {
            while (isActive) {
                refreshAll()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    // ─────────────────────────── 窗口同步 ───────────────────────────

    /**
     * 把当前开启的监视器集合同步成实际窗口。
     *
     * 只做增量：已有且仍开启的原样保留（保住用户拖出来的位置），
     * 新开启的创建，关闭的移除。全量重建会让每次开关都把所有窗口弹回默认位置。
     */
    private fun syncWindows(kinds: Set<MonitorKind>) {
        if (!Settings.canDrawOverlays(this)) {
            // 没有悬浮窗权限时窗口根本建不出来。静默失败会让人以为「开关坏了」，
            // 因此留下可检索的日志（管理器页另有权限提示横幅）。
            Log.w(TAG, "缺少悬浮窗权限，监视器窗口无法创建")
            return
        }

        // 1) 关掉不再需要的
        val stale = windows.keys.filter { it !in kinds }
        stale.forEach { removeWindow(it) }

        // 2) 补上新增的
        kinds.filter { it !in windows.keys }.forEach { addWindow(it) }

        // 3) 锚点：只在「一个监视器都没有」时才有意义（见类注释第 1 条）
        if (windows.isEmpty()) {
            syncAnchor()
        } else {
            removeAnchor()
        }

        refreshAll()
    }

    /** 一次性重算所有窗口的正文 */
    private fun refreshAll() {
        if (windows.isEmpty()) return
        val snapshot = LiveMetrics.snapshot.value
        val fps = effectiveFps()
        val now = SystemClock.elapsedRealtime()

        val recordingNow = FpsRecorder.recording.value
        windows.values.forEach { w ->
            val body = when (w.kind) {
                MonitorKind.Mini -> textMini(snapshot, fps)
                MonitorKind.Load -> textLoad(snapshot, fps)
                MonitorKind.Fps -> textFps(fps)
                MonitorKind.Thermal -> textThermal(snapshot, now)
                MonitorKind.Process -> textProcess(now)
                MonitorKind.Thread -> textThread(now)
            }
            if (body != w.lastBody) {
                // 帧率窗口的正文是纯数字，不加录制小圆点（会破坏数字居中，
                // 且状态已由边框颜色表达）
                w.bodyView.text = if (w.kind == MonitorKind.Fps) body else buildBody(body)
                w.lastBody = body
            }
            // 帧率窗口：录制中用红色描边表达「正在记录」，无需文字提示。
            // 直接换背景 Drawable 的描边色（每帧构造一次 GradientDrawable 开销
            // 可忽略，1 Hz 且仅此一个窗口）。
            if (w.kind == MonitorKind.Fps) {
                applyFpsWindowFrame(w, recordingNow)
            }
        }

        updateNotification()
    }

    /**
     * 当前用于显示的**有效帧率**：系统级优先。
     *
     * 与 [FpsOverlayService.effectiveFps] 完全同口径：悬浮窗盖在别的应用上时，
     * 本应用自己的 Choreographer 早已不代表屏幕实况，必须优先用系统级实测值。
     *
     * **退回自测值前必须过物理闸门**。`FpsRecorder` 走 Choreographer，
     * 而应用退到后台、自身没有可见窗口时，帧回调不再被 vsync 节流，
     * 两次回调的间隔会缩到远小于一帧的物理耗时，算出来的「帧率」能到
     * 500~600（实测 597.8）。这个数字在物理上不可能是屏幕帧率，
     * 显示出来会被当成真实负载去分析卡顿——比显示「不可读」更糟。
     *
     * 因此超过面板上限（[MAX_PLAUSIBLE_FPS]）时一律退回 0，
     * 由调用方显示成 `--`。
     */
    private fun effectiveFps(): Float {
        val sys = LiveMetrics.snapshot.value.sysFps
        if (sys > 0f) return sys
        val self = FpsRecorder.sample.value.fps
        return if (self > 0f && self <= MAX_PLAUSIBLE_FPS) self else 0f
    }

    // ─────────────────────────── 各监视器文案 ───────────────────────────

    /**
     * 迷你监视器：单行三格，几乎不遮挡内容。
     *
     * 只放「一眼能看出卡不卡」的三项：CPU 占用、帧率、CPU 结温。
     * 刻意不放内存——内存变化慢，占了宝贵的横向空间却几乎没有信息量。
     */
    private fun textMini(s: LiveMetrics.Snapshot, fps: Float): String {
        val cpu = if (s.cpuLoad < 0f) "--" else "%.0f%%".format(s.cpuLoad)
        val temp = thermalCached()?.let { "%.0f°".format(it) } ?: "--"
        return "$cpu · ${"%.0f".format(fps)}f · $temp"
    }

    /**
     * 负载监视器：多行面板，每指标一行「标签 值」。
     *
     * 与 [FpsOverlayService] 的 LOAD 形态保持同一套字段与同一套「读不到就 `--`」
     * 的约定——同一个数据在两个入口显示成不同口径，用户会怀疑哪个是错的。
     */
    private fun textLoad(s: LiveMetrics.Snapshot, fps: Float): String = listOf(
        "CPU  ${if (s.cpuLoad < 0f) "--" else "%.0f%%".format(s.cpuLoad)}  " +
            "${if (s.cpuFreqMhz > 0) fmtFreq(s.cpuFreqMhz) else "--"}",
        "GPU  ${if (s.gpuLoad >= 0) "${s.gpuLoad}%" else "--"}  " +
            "${if (s.gpuMhz > 0) fmtFreq(s.gpuMhz) else "--"}",
        "RAM  ${"%.0f%%".format(s.memUsedPercent)}",
        "FPS  ${"%.0f".format(fps)}",
        "PWR  ${if (s.powerMw > 0f) "%.2fW".format(s.powerMw / 1000f) else "--"}",
        "TEMP ${thermalDisplay(s)}",
    ).joinToString("\n")

    /**
     * 帧率监视器正文：**只有一个帧率数值**。
     *
     * 刻意去掉了「FPS」前缀与「记录中 MM:SS」状态行——这个窗口的唯一职责
     * 是「瞄一眼现在多少帧」。录制状态由窗口边框颜色表达（记录中描红），
     * 点击窗口即可开始/停止，因此文字里不必再重复。
     *
     * 读不到（0）时显示 `--`，而不是 `0.0`：0 FPS 是一个物理上不可能
     * 的读数，显示 0 会被误读成「掉帧到零」。
     */
    private fun textFps(fps: Float): String =
        if (fps > 0f) "%.1f".format(fps) else "--"

    /**
     * 温度监视器：CPU 结温 / GPU / 电池。
     *
     * CPU 与 GPU 走 thermal zone 的语义选择（见 [refreshThermalCache]）；
     * 电池温度来自 [LiveMetrics] 快照（它采的是 `power_supply` 的 temp 节点，
     * 与 CPU 完全不同的物理位置，不能混为一谈）。
     */
    /**
     * 温度监视器：CPU 结温 / GPU 温度 / 电池温度。
     *
     * 三个数是**三个不同的物理位置**，因此三行分开列：
     * - CPU / GPU 走 thermal zone 的语义选择（见 [refreshThermalCache]）；
     * - 电池温度来自 [LiveMetrics] 快照（它采的是 `power_supply` 的 temp 节点，
     *   与 SoC 完全无关）。
     *
     * 绝不用其中任何一个去顶替读不到的那一行——它们都落在 30~60℃ 区间，
     * 数字上无法自证身份，混填会让人误判散热瓶颈。
     */
    private fun textThermal(s: LiveMetrics.Snapshot, now: Long): String {
        refreshThermalCache(now)
        val cpu = thermalCache?.cpuC?.let { "%.1f℃".format(it) } ?: "--"
        val gpu = thermalCache?.gpuC?.let { "%.1f℃".format(it) } ?: "--"
        val battery = s.batteryTempC?.let { "%.1f℃".format(it) } ?: "--"
        return "CPU   $cpu\nGPU   $gpu\nBAT   $battery"
    }

    /**
     * 进程监视器：CPU 占用最高的进程（Top N）。
     *
     * 未提权时显示明确的提示而不是空面板：空面板会被读成「系统里没有进程」，
     * 而真实原因是 Android 12+ 不允许应用身份读别的进程的 /proc。
     */
    private fun textProcess(now: Long): String {
        refreshProcessCache(now)
        if (processCache.isEmpty()) return "需要 Root\n（top 读不到进程列表）"
        return processCache.take(MONITOR_ROWS).joinToString("\n") { p ->
            val name = p.packageName?.substringAfterLast('.') ?: p.name.take(12)
            "%.1f%%  %s".format(p.cpuPercent, name.take(14))
        }
    }

    /**
     * 线程监视器：CPU 占用最高的线程（Top N）。
     *
     * 显示「线程名」而不是进程名：打开线程监视器的人想看的是
     * 哪个线程在吃 CPU（渲染线程？GC 线程？某个后台线程？），
     * 只显示进程名的话和进程监视器没有任何区别。
     *
     * 线程名来自 `/proc/<pid>/task/<tid>/comm`（见 [ThreadDataSource]）。
     * 极少数线程 `comm` 读不到（刚退出、或 SELinux 拒读）时退回
     * `线程名? #tid` 的形式——**必须保留 TID**，否则多行 `?` 完全无法区分。
     */
    private fun textThread(now: Long): String {
        refreshThreadCache(now)
        if (threadCache.isEmpty()) return "需要 Root\n（top -H 读不到线程）"
        return threadCache.take(MONITOR_ROWS).joinToString("\n") { t ->
            val label = if (t.threadName.isNotBlank() && t.threadName != "?") {
                t.threadName
            } else {
                "?#${t.tid}"
            }
            "%.1f%%  %s".format(t.cpuPercent, label.take(16))
        }
    }

    // ─────────────────────────── 重型数据节流 ───────────────────────────

    /**
     * 进程列表节流拉取。
     *
     * `top -b -n 1` 是整机进程快照（现代手机 300~600 条），解析 + 包名查询
     * 约几十毫秒。每秒都跑会让 6 个窗口同时在开时的刷新链明显变长，
     * 因此按 [HEAVY_REFRESH_INTERVAL_MS] 拉一次，中间轮次复用缓存。
     */
    private fun refreshProcessCache(now: Long) {
        if (now - processFetchedAt < HEAVY_REFRESH_INTERVAL_MS && processCache.isNotEmpty()) return
        // 同步标记时刻，防止上一轮还没回来时下一轮又发起（拉取在协程里异步完成）
        processFetchedAt = now
        scope.launch {
            val list: List<com.osplus.tools.model.ProcessEntry> = runCatching {
                ProcessDataSource.list(this@MonitorOverlayService)
            }.getOrDefault(emptyList())
            processCache = list
            // 拉回来后立刻重绘一次，否则要等下一个 1 秒节拍才出现内容
            refreshAll()
        }
    }

    /** 线程列表节流拉取；`top -H` 比进程级更贵（整机线程数高一个数量级），见 [ThreadDataSource] */
    private fun refreshThreadCache(now: Long) {
        if (now - threadFetchedAt < HEAVY_REFRESH_INTERVAL_MS && threadCache.isNotEmpty()) return
        threadFetchedAt = now
        scope.launch {
            val list: List<ThreadEntry> = runCatching {
                ThreadDataSource.list(this@MonitorOverlayService)
            }.getOrDefault(emptyList())
            threadCache = list
            refreshAll()
        }
    }

    /**
     * 温度节流拉取。
     *
     * 走 [CpuDataSource] 的语义选择路径而不是直接读 `thermal_zone0`：
     * 编号由内核设备树注册顺序决定，本机 SM8750 上 `thermal_zone0` 其实是
     * `aoss-0`（Always-On 子系统，比真实 CPU 结温低 3~4℃），
     * 拿它当 CPU 温度会低估。
     */
    private fun refreshThermalCache(now: Long) {
        // 首次（fetchedAt == 0）无条件拉一次；之后按节流周期
        if (thermalFetchedAt > 0L && now - thermalFetchedAt < HEAVY_REFRESH_INTERVAL_MS) return
        // 先把时刻打上，防止上一轮协程还没回来时下一轮又发起
        thermalFetchedAt = now
        scope.launch {
            val t: CpuDataSource.TemperatureBundle = runCatching {
                CpuDataSource.readTemperatures()
            }.getOrDefault(CpuDataSource.TemperatureBundle(null, null))
            thermalCache = t
            refreshAll()
        }
    }

    /** 供迷你监视器读取：必要时触发一次拉取，并返回当前缓存（无缓存时为 null） */
    private fun thermalCached(): Float? {
        refreshThermalCache(SystemClock.elapsedRealtime())
        return thermalCache?.cpuC
    }

    /**
     * 温度监视器的主读数显示：优先 CPU 结温，读不到时退回电池温度。
     *
     * 退回时必须让人看得出来这是电池温度——两者都在 30~45℃ 区间，
     * 数字上无法自证身份，因此这里带 `BAT` 前缀而不是裸数字。
     */
    private fun thermalDisplay(s: LiveMetrics.Snapshot): String =
        thermalCache?.cpuC?.let { "%.1f℃".format(it) }
            ?: s.batteryTempC?.let { "BAT %.1f℃".format(it) }
            ?: "--"

    /** 记录正文：记录中在行首加一个红点，与既有悬浮窗的约定一致 */
    private fun buildBody(body: String): CharSequence {
        if (!FpsRecorder.recording.value) return body
        val sb = SpannableStringBuilder()
        sb.append("● ")
        sb.setSpan(
            ForegroundColorSpan(RECORDING_DOT_COLOR),
            0,
            1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.append(body)
        return sb
    }

    // ─────────────────────────── 窗口创建与拖动 ───────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun addWindow(kind: MonitorKind) {
        if (windows.containsKey(kind)) return

        val density = resources.displayMetrics.density

        val titleView = TextView(this).apply {
            setTextColor(Color.parseColor("#99FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            text = kind.title
        }
        val bodyView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
            text = "--"
        }
        // 帧率监视器：**不要标题，只要一个大号帧率数字**。
        // 它的用法是「瞄一眼现在多少帧」，标题占的那一行是纯噪声；
        // 数字放大到 30sp 后从远处一眼可读，才是这个窗口该有的形态。
        // 录制开关改由「点击窗口」触发（见 attachTouchHandler），
        // 因此窗口上也不再需要「记录中」这类状态文字。
        if (kind == MonitorKind.Fps) {
            titleView.visibility = View.GONE
            bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            bodyView.typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD,
            )
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (14 * density).toInt(),
                (9 * density).toInt(),
                (14 * density).toInt(),
                (9 * density).toInt(),
            )
            background = makeWindowBackground(FpsRecorder.recording.value)
            elevation = 8f * density
            alpha = userAlpha
            addView(titleView)
            addView(bodyView)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val (defX, defY) = defaultOrSavedPosition(kind)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defX
            y = defY
            // 窗口 alpha 恒为 1.0，原因见类注释第 2 条
            alpha = 1f
        }

        val window = MonitorWindow(kind, root, titleView, bodyView, params)
        attachTouchHandler(window)
        val ok = runCatching { windowManager.addView(root, params) }.isSuccess
        if (ok) {
            windows[kind] = window
            Log.i(TAG, "监视器窗口已挂上：${kind.key}")
        } else {
            Log.w(TAG, "监视器窗口挂载失败：${kind.key}")
        }
    }

    /** 优先用用户拖动过的位置，否则按枚举序铺开默认落点 */
    private fun makeWindowBackground(recording: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(Color.parseColor("#CC101014"))
            cornerRadius = 14f * density
            // 录制中：红色描边（与「● 记录中」同一红），一眼可辨
            setStroke(
                (1.5f * density).toInt(),
                if (recording) RECORDING_DOT_COLOR else Color.parseColor("#33FFFFFF"),
            )
        }
    }

    /**
     * 帧率监视器：随录制状态切换描边颜色。
     *
     * 只在状态**变化**时重建 Drawable（记在 [MonitorWindow.lastFrameRecording]），
     * 避免 1 Hz 节拍每次都 new 一个 Drawable 触发无谓重绘。
     */
    private fun applyFpsWindowFrame(w: MonitorWindow, recording: Boolean) {
        if (w.lastFrameRecording == recording && w.root.background != null) return
        w.lastFrameRecording = recording
        w.root.background = makeWindowBackground(recording)
    }

    private fun defaultOrSavedPosition(kind: MonitorKind): Pair<Int, Int> {
        if (Preferences.monitorPlaced(this, kind)) {
            val x = Preferences.monitorX(this, kind)
            val y = Preferences.monitorY(this, kind)
            if (x != Int.MIN_VALUE && y != Int.MIN_VALUE) return x to y
        }
        return MonitorState.defaultPosition(this, kind)
    }

    private fun removeWindow(kind: MonitorKind) {
        val w = windows.remove(kind) ?: return
        runCatching { windowManager.removeView(w.root) }
    }

    /**
     * 监视器窗口手势：**拖动移动位置**；帧率窗口额外支持**轻点开始/停止录制**。
     *
     * 其余窗口刻意不做轻点动作——它们没有「点一下要发生的事」，加了只会让
     * 用户想挪位置时误触。因此只有 [MonitorKind.Fps] 在「未拖动」的
     * ACTION_UP 上触发录制切换，判定用 [DRAG_SLOP]，一碰就走不误触。
     *
     * 帧率窗口的轻点走 [FpsRecordingController]（而不是直接
     * `FpsRecorder.toggleRecording()`）：前者才会**建会话**，让数据落库。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchHandler(window: MonitorWindow) {
        val view = window.root
        val params = window.params
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var downTime = 0L

        // 长按进入拖动模式：与帧率悬浮窗同一套手感，给一点触感反馈
        val longPress = Runnable {
            dragging = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downTime = SystemClock.uptimeMillis()
                    startX = params.x
                    startY = params.y
                    dragging = false
                    // 拖动期间提到全不透明，松手恢复（只动 View，不动窗口 alpha）
                    view.alpha = 1f
                    view.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(dx) > DRAG_SLOP || kotlin.math.abs(dy) > DRAG_SLOP)) {
                        view.removeCallbacks(longPress)
                        dragging = true
                    }
                    if (dragging) {
                        params.x = clampX(startX + dx.roundToInt(), view)
                        params.y = clampY(startY + dy.roundToInt(), view)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    view.alpha = userAlpha
                    if (dragging) {
                        Preferences.setMonitorPosition(this, window.kind, params.x, params.y)
                    } else if (
                        window.kind == MonitorKind.Fps &&
                        SystemClock.uptimeMillis() - downTime < TAP_MAX_MS
                    ) {
                        // 轻点帧率窗口 = 开始/停止录制
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        FpsRecordingController.toggle(this)
                        // 立刻刷新一次，让描边马上变色，不必等下一个 1 Hz 节拍
                        refreshAll()
                    }
                    dragging = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    view.alpha = userAlpha
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    /** 限制在屏幕内，避免被拖出可视区域后找不回来 */
    private fun clampX(x: Int, view: View): Int {
        val max = (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0)
        return x.coerceIn(0, max)
    }

    private fun clampY(y: Int, view: View): Int {
        val max = (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0)
        return y.coerceIn(0, max)
    }

    /**
     * 设置全部监视器窗口的视觉透明度。
     *
     * **必须改 `View.alpha`，不能改 `WindowManager.LayoutParams.alpha`**——
     * 原因见类注释第 2 条：窗口 alpha ≤0.8 会触发「不受信任的触摸事件」
     * 限制，整个窗口的触摸分发被系统掐掉。透明度改在 View 层只影响绘制。
     */
    private fun applyAlpha(value: Float) {
        userAlpha = value
        windows.values.forEach { w ->
            if (!w.dragging) w.root.alpha = value
        }
    }

    // ─────────────────────────── 保活锚点 ───────────────────────────

    /**
     * 挂上「保活锚点」：一个 1×1 的不可见悬浮窗。
     *
     * **它不显示任何东西，唯一目的是让本进程持有可见窗口**，以免被
     * ColorOS 的冻结框架（`OplusHansManager`）在后台 5~10 秒后冻住，
     * 导致采样与通知刷新全部停摆。完整机制见
     * [FpsOverlayService.addAnchor] 的注释——那边是首次发现这个问题的地方。
     *
     * 三条实现约束同样适用：
     * - **窗口 alpha 必须是 1.0**（0 会被判定为不可见，锚点就白挂了）；
     * - **`FLAG_NOT_TOUCHABLE`**，绝不拦截触摸；
     * - 需要 [Settings.canDrawOverlays]，未授权时静默跳过。
     */
    private fun syncAnchor() {
        if (anchorView != null) return
        if (windows.isNotEmpty()) return
        if (!Settings.canDrawOverlays(this)) return
        val view = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 1f
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 1f
        }
        val ok = runCatching { windowManager.addView(view, params) }.isSuccess
        if (ok) {
            anchorView = view
            anchorParams = params
        }
    }

    private fun removeAnchor() {
        anchorView?.let { v -> runCatching { windowManager.removeView(v) } }
        anchorView = null
        anchorParams = null
    }

    // ─────────────────────────── 通知与格式化 ───────────────────────────

    /**
     * 前台服务通知。
     *
     * 与 [FpsOverlayService] 的凝光卡片不同，这里用最简单的默认通知：
     * 管理器服务本身**不是**用户关心的展示面——真正的展示面是那 6 个悬浮窗。
     * 通知在这里的唯一职责是满足「前台服务必须有常驻通知」的平台要求，
     * 并提供一个点回应用与一键全关的入口，因此不需要自定义视图。
     */
    private fun buildNotification(): Notification {
        val count = windows.size
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitorOverlayService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, OsPlusApplication.CHANNEL_FLUID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("OSPlus 悬浮窗管理器")
            .setContentText(if (count == 0) "无监视器开启" else "已开启 $count 个监视器")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notify),
                    "全部关闭",
                    stopIntent,
                ).build(),
            )
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    /** MHz → 「2.05GHz」/「855MHz」 */
    private fun fmtFreq(mhz: Int): String =
        if (mhz >= 1000) "%.2fGHz".format(mhz / 1000.0) else "${mhz}MHz"

    override fun onDestroy() {
        MonitorState.setRunning(false)
        scope.cancel()
        windows.values.toList().forEach { runCatching { windowManager.removeView(it.root) } }
        windows.clear()
        removeAnchor()
        // 仍在记录时不要停掉统计器，否则记录会被中断
        FpsRecorder.overlayHolds = false
        if (!FpsRecorder.recording.value) FpsRecorder.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OSPlusMonitor"

        private const val NOTIFICATION_ID = 0x0522

        /** 轻量指标的刷新周期，与其他展示面统一 1 Hz */
        private const val REFRESH_INTERVAL_MS = 1000L

        /**
         * 重型指标（进程 / 线程 / 温度）的节流周期。
         *
         * 2 秒而不是 1 秒：这三项各自要拉一次 root 命令或一组 sysfs 节点，
         * 每秒都跑会在「6 个监视器全开」时明显拖慢刷新链。而它们的语义也
         * 允许慢一拍——进程 / 线程占用的瞬时抖动本来就不该被逐秒盯着看。
         */
        private const val HEAVY_REFRESH_INTERVAL_MS = 2000L

        /** 列表类监视器显示的行数上限 */
        private const val MONITOR_ROWS = 6

        /**
         * 可信帧率上限。
         *
         * 与 [com.osplus.tools.core.SysFpsDataSource] 的闸门同值：当前量产面板
         * 最高 165Hz，留 1.2 倍余量覆盖驱动上报抖动。超过即判定为坏数据。
         */
        private const val MAX_PLAUSIBLE_FPS = 200f

        /** 判定为「拖动」而非「误触」的位移阈值（像素） */
        private const val DRAG_SLOP = 6f

        /** 按住超过该时长即进入拖动模式 */
        private const val LONG_PRESS_MS = 300L

        /**
         * 帧率窗口「轻点」的最长按压时长（毫秒）。
         *
         * 超过这个时长仍认定为「想按住拖动」而不是「想切录制」，
         * 避免长按不放时松手被误判成点击。
         */
        private const val TAP_MAX_MS = 400L

        /** 记录中的红点颜色 */
        private val RECORDING_DOT_COLOR = Color.parseColor("#FF5252")

        /** 通知「全部关闭」按钮的 action */
        private const val ACTION_STOP_ALL = "com.osplus.tools.action.MONITOR_STOP_ALL"

        /**
         * 启动服务。
         *
         * **只有真的至少开了一个监视器才启动**：服务常驻会占一条前台通知，
         * 用户没开任何监视器时挂着它是纯噪音。调用方应保证
         * [Preferences.setEnabledMonitors] 已先写入非空集合。
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, MonitorOverlayService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, MonitorOverlayService::class.java))
            }
        }

        /**
         * 「全部关闭」：清空开关并停服务。供设置页与通知按钮共用。
         *
         * 必须先清开关再停服务，否则服务 `onDestroy` 后如果还有开关为真，
         * 下次进程重启会被 `BootReceiver` 或界面重新拉起，表现为「关了又自己开」。
         */
        fun stopAll(context: Context) {
            MonitorState.setAll(context, emptySet())
            stop(context)
        }
    }
}
