package com.osplus.tools.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
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
import android.widget.RemoteViews
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.osplus.tools.MainActivity
import com.osplus.tools.OsPlusApplication
import com.osplus.tools.R
import com.osplus.tools.core.FpsOverlayState
import com.osplus.tools.core.FpsRecorder
import com.osplus.tools.core.LiveMetrics
import com.osplus.tools.core.LiveNotif
import com.osplus.tools.core.NotifMetric
import com.osplus.tools.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 跨应用实时监视前台服务，提供两种互斥的呈现方式：
 *
 * - **实时任务通知**（默认，见 [LiveNotif.enabled]）：Android 17+ 走系统
 *   `MetricStyle` 实时任务通知，状态栏芯片常驻；**不需要悬浮窗权限**。
 * - **悬浮窗**：`TYPE_APPLICATION_OVERLAY` 窗口，需要 `SYSTEM_ALERT_WINDOW`。
 *   悬浮窗是本进程的可见窗口，系统会持续投递 vsync，因此它同时充当
 *   「跨应用帧率采样源」——服务启动后驱动共享的 [FpsRecorder]，
 *   应用退到后台时录制仍会继续（前台服务保活）。
 *
 * 两种方式显示的指标项由用户在设置页勾选（最多 3 项，见 [LiveNotif]），
 * 服务订阅其 StateFlow，因此切换方式或改动显示项都无需重启服务。
 *
 * 悬浮窗可拖动，位置与不透明度都会持久化，下次启动沿用上次的落点。
 */
class FpsOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 保活锚点（1×1 不可见窗口），与悬浮窗互斥，见 [addAnchor] */
    private var anchorView: View? = null
    private var anchorParams: WindowManager.LayoutParams? = null

    /** 用户设定的不透明度；拖动时临时提到 1.0，松手后恢复 */
    private var userAlpha = 0.92f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知 action：直接在实时任务通知上开始 / 停止帧率记录
        if (intent?.action == ACTION_TOGGLE_RECORDING) {
            FpsRecorder.toggleRecording()
            // 立即刷新一次，不等下一个 1 秒节拍，让标题与按钮文案马上翻转
            refreshLiveReadouts()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        FpsOverlayState.set(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        userAlpha = Preferences.overlayAlpha(this).coerceIn(FpsOverlayState.MIN_ALPHA, 1f)
        FpsOverlayState.setAlpha(userAlpha)

        // 与录制功能共用同一个逐帧统计器，避免两套 Choreographer 互相干扰
        FpsRecorder.overlayHolds = true
        FpsRecorder.start()

        // 呈现方式：实时任务通知 ↔ 悬浮窗；通知模式下还要跟随保活开关增减锚点。
        // 订阅而非只在 onCreate 读一次，因此在设置页切换后无需重启服务即可生效
        scope.launch {
            combine(LiveNotif.enabled, LiveNotif.keepAlive) { enabled, _ -> enabled }
                .collectLatest { enabled -> applyPresentation(enabled) }
        }

        // 实时读数刷新节拍。
        //
        // 三处显示——抽屉置顶卡片、状态栏芯片、TYPE_APPLICATION_OVERLAY 悬浮窗——
        // 统一按 1 秒刷新，用独立节拍而不是「数据源一变就刷」：帧率与系统指标是两条
        // 各自 1 秒的流，直接订阅会在同一秒内触发两次重建（通知被 notify() 两次、
        // 悬浮窗文字被改写两次），既浪费，也让刷新频率无法确定。这里固定成 1 Hz。
        scope.launch {
            while (isActive) {
                refreshLiveReadouts()
                delay(REFRESH_INTERVAL_MS)
            }
        }

        // 界面调整不透明度后立即作用到窗口，无需重启服务
        scope.launch {
            FpsOverlayState.alpha.collectLatest { value -> applyAlpha(value) }
        }
    }

    /** 取当前一刻的实时读数快照，供悬浮窗与通知共用 */
    private fun currentOverlayValues() = OverlayValues(
        fps = FpsRecorder.sample.value.fps,
        snapshot = LiveMetrics.snapshot.value,
        recording = FpsRecorder.recording.value,
        metrics = LiveNotif.metrics.value,
    )

    /**
     * 刷新一次实时读数：悬浮窗文字 + 通知（通知同时承载抽屉置顶卡片与状态栏芯片）。
     *
     * 由 1 秒节拍驱动；切换呈现方式、点通知按钮切换记录这类用户动作也会立即调用一次，
     * 让反馈不必等到下一个节拍。
     */
    private fun refreshLiveReadouts() {
        overlayView?.text = buildOverlayText(currentOverlayValues())
        updateNotification()
    }

    /**
     * 应用当前的呈现方式（并同步锚点）。
     *
     * 三种组合：
     * - 通知模式 + 保活开 → 只挂 1×1 锚点，通知后台可持续刷新；
     * - 通知模式 + 保活关 → 不挂任何窗口，通知在后台约 5~10 秒后被系统冻结而停更；
     * - 悬浮窗模式 → 悬浮窗本身就是可见窗口，锚点多余，摘掉。
     *
     * 悬浮窗与锚点都需要 [Settings.canDrawOverlays]；未授权时静默降级。
     */
    private fun applyPresentation(liveNotif: Boolean) {
        if (liveNotif) {
            removeOverlay()
            if (LiveNotif.keepAlive.value) addAnchor() else removeAnchor()
        } else {
            removeAnchor()
            if (Settings.canDrawOverlays(this)) addOverlay()
        }
        refreshLiveReadouts()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        if (overlayView != null) return
        // 圆角深色底 + 内边距：直接压在应用图标上时文字几乎不可读
        val background = GradientDrawable().apply {
            setColor(Color.parseColor("#CC101014"))
            cornerRadius = 20f * resources.displayMetrics.density
            setStroke((1f * resources.displayMetrics.density).toInt(), Color.parseColor("#33FFFFFF"))
        }
        val view = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(26, 12, 26, 12)
            // 首帧占位：具体数值由 LiveMetrics 的第一次采样填入
            text = "--"
            this.background = background
            elevation = 8f * resources.displayMetrics.density
            // 视觉透明度挂在 View 上，见 applyAlpha 的说明
            alpha = userAlpha
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Preferences.overlayX(this@FpsOverlayService)
            y = Preferences.overlayY(this@FpsOverlayService)
            // 窗口 alpha 恒为 1.0，绝不能用它表达「半透明」——原因见 applyAlpha 的说明
            alpha = 1f
        }
        attachTouchHandler(view, params)
        runCatching { windowManager.addView(view, params) }
        overlayView = view
        layoutParams = params
    }

    /** 摘掉悬浮窗窗口（切回实时任务通知模式、或服务销毁时调用） */
    private fun removeOverlay() {
        overlayView?.let { v -> runCatching { windowManager.removeView(v) } }
        overlayView = null
        layoutParams = null
    }

    /**
     * 挂上「保活锚点」：一个 1×1 的不可见悬浮窗。
     *
     * **它不显示任何东西，唯一目的是让本进程持有可见窗口。**
     *
     * ColorOS 的冻结框架（`OplusHansManager` / `oplus_freeze`）会在应用退到后台
     * 约 5~10 秒后冻结整个进程，前台服务不足以豁免，AOSP 的
     * `cached_apps_freezer` 开关也管不到它；一旦冻上，进程拿不到任何 CPU，
     * 采样与通知刷新全部停摆（实测 `cgroup.freeze=1`、CPU 增量 0）。
     * 而该框架的目标是**后台应用**——持有可见窗口的进程不属于后台，
     * 因此不会被选中（实测悬浮窗模式下后台 CPU 持续增长、全程 `freeze=0`）。
     *
     * 三条实现约束：
     * - **窗口 alpha 必须是 1.0**：0 会被判定为「不可见」，锚点就白挂了；
     * - **`FLAG_NOT_TOUCHABLE`**：绝不拦截任何触摸；
     * - 需要 `SYSTEM_ALERT_WINDOW` 权限，未授权时静默跳过（功能自动降级）。
     */
    private fun addAnchor() {
        if (anchorView != null) return
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
            Log.i(TAG, "保活锚点已挂上（1×1 不可见窗口）")
        } else {
            Log.w(TAG, "保活锚点挂载失败")
        }
    }

    /** 摘掉保活锚点（切到悬浮窗模式、关闭保活开关、或服务销毁时调用） */
    private fun removeAnchor() {
        anchorView?.let { v -> runCatching { windowManager.removeView(v) } }
        anchorView = null
        anchorParams = null
    }

    /**
     * 设置悬浮窗的视觉透明度。
     *
     * **必须改 `View.alpha`，不能改 `WindowManager.LayoutParams.alpha`。**
     *
     * Android 12 起引入了「不受信任的触摸事件」限制：`TYPE_APPLICATION_OVERLAY` 窗口的
     * `LayoutParams.alpha` 一旦小于等于 `InputManager.getMaximumObscuringOpacityForTouch()`
     * （当前为 **0.8**），该窗口就被判定为「遮挡不足」，**系统不再把触摸事件投递给它**，
     * 于是轻点 / 长按 / 拖动全部失效——logcat 会打印
     * `Untrusted touch due to occlusion by <pkg>`。
     *
     * 窗口 alpha 保持 1.0 后，窗口在输入层面始终是「足够遮挡」的正常触摸目标；
     * 透明度改由 View 层承担，只影响绘制、不影响输入分发，观感与之前完全一致。
     */
    private fun applyAlpha(value: Float) {
        userAlpha = value
        overlayView?.alpha = value
    }

    /**
     * 悬浮窗手势：**轻点切换记录，长按或拖动移动位置**。
     *
     * 两种意图靠「按住时长」和「位移」区分：
     * - 位移超过 [DRAG_SLOP] → 立即进入拖动（快速拖不会误判成轻点）；
     * - 按住超过 [LONG_PRESS_MS] → 也进入拖动（长按即准备移动，避免长按被当成点击）；
     * - 抬起时两者都不满足且按压时长很短 → 视为轻点，切换记录状态。
     *
     * 用 rawX/rawY 的位移增量直接累加到窗口偏移上，不依赖控件自身的布局坐标，
     * 因此不会出现「手指跟窗口错位」的问题；松手时把落点写回偏好。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchHandler(view: View, params: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var downTime = 0L
        var startX = 0
        var startY = 0
        var dragging = false

        // 长按进入拖动模式：给一点触感反馈，让用户知道现在可以挪了
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
                    // 拖动过程中保证看得清；只动 View 的透明度，不动窗口 alpha
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
                        Preferences.setOverlayPosition(this, params.x, params.y)
                    } else if (SystemClock.uptimeMillis() - downTime < LONG_PRESS_MS) {
                        // 轻点：开始 / 停止记录，并同步通知文案
                        FpsRecorder.toggleRecording()
                        updateNotification()
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

    /** 窗上文字：按用户勾选的指标项拼接；记录中在数值前加一个红点，表示正在留档 */
    private fun buildOverlayText(v: OverlayValues): CharSequence {
        val body = v.metrics.joinToString("   ") { compact(it, v.snapshot, v.fps) }
        if (!v.recording) return body
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

    private data class OverlayValues(
        val fps: Float,
        val snapshot: LiveMetrics.Snapshot,
        val recording: Boolean,
        val metrics: List<NotifMetric>,
    )

    /**
     * 单个指标的紧凑文案，供悬浮窗与状态栏芯片共用。
     *
     * 读不到的指标一律显示 `--`，而不是 0——「读不到」与「就是 0」是两回事，
     * 后者会让人误以为显卡闲着、电池不热。
     *
     * [withUnit] 给状态栏芯片用：芯片孤零零挂在状态栏上，没有上下文，
     * 只写 `60` 看不出是帧率还是别的；写 `60FPS` 才能自解释
     * （≤7 字符才会被完整显示）。悬浮窗里多个数值并排，上下文已足够，故不带单位。
     */
    private fun compact(
        m: NotifMetric,
        s: LiveMetrics.Snapshot,
        fps: Float,
        withUnit: Boolean = false,
    ): String = when (m) {
        NotifMetric.Fps -> if (withUnit) "%.0fFPS".format(fps) else "%.0f".format(fps)
        NotifMetric.CpuLoad -> "%.0f%%".format(s.cpuLoad)
        NotifMetric.CpuFreq -> if (s.cpuFreqMhz > 0) fmtFreq(s.cpuFreqMhz) else "--"
        NotifMetric.GpuLoad -> if (s.gpuLoad >= 0) "${s.gpuLoad}%" else "--"
        NotifMetric.GpuFreq -> if (s.gpuMhz > 0) fmtFreq(s.gpuMhz) else "--"
        NotifMetric.MemUsed -> "%.0f%%".format(s.memUsedPercent)
        NotifMetric.Power -> if (s.powerMw > 0f) "%.0fmW".format(s.powerMw) else "--"
        NotifMetric.BatteryTemp -> s.batteryTempC?.let { "%.1f℃".format(it) } ?: "--"
        NotifMetric.ChargeSpeed -> if (s.chargeW > 0f) "%.1fW".format(s.chargeW) else "--"
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
     * 构建前台服务通知。
     *
     * 分两种呈现方式，由 [LiveNotif.enabled] 决定：
     *
     * 1. **实时任务通知**（开关开启）：Android 17（API 37）起可用系统的
     *    [Notification.MetricStyle] + `setRequestPromotedOngoing(true)`，
     *    通知被提升为抽屉顶部卡片、状态栏芯片常驻。官方明确 promoted 通知
     *    **不得携带 customContentView（RemoteViews）**，否则直接失去资格，
     *    因此这一分支不挂任何自定义视图。指标项取自 [LiveNotif.metrics]。
     *
     * 2. **悬浮窗 / 旧系统**（开关关闭，或系统低于 API 37）：回退为
     *    RemoteViews 凝光玻璃卡片——RemoteViews 不支持自定义 View / Canvas / Shader，
     *    玻璃材质在 App 进程里预渲染成一张 Bitmap 塞进 ImageView。
     *
     * 版本判断用 `CINNAMON_BUN`(37) 而不是 `BAKLAVA`(36)：`MetricStyle` 的引入级别
     * 是 **37**（SDK 的 api-versions.xml 记为 `since="37.0"`），在 API 36 上引用它
     * 会 `NoClassDefFoundError` 直接崩掉前台服务。提升分支整体包在 runCatching 里，
     * 任一步失败都退回卡片而不是崩溃。
     */
    private fun buildNotification(): Notification {
        val recording = FpsRecorder.recording.value
        val metrics = LiveNotif.metrics.value
        val title = if (recording) "OSPlus 正在记录" else "OSPlus 实时状态"

        if (LiveNotif.enabled.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            val promoted = runCatching {
                buildPromotedNotification(metrics, title, recording)
            }.onFailure {
                // 不能让回退静默发生：卡片「没被提升」在界面上完全看不出原因，
                // 只能靠 dumpsys 反推。这里留下可检索的痕迹。
                Log.w(TAG, "实时任务通知构建失败，回退为凝光卡片", it)
            }.getOrNull()
            if (promoted != null) return promoted
        }
        return buildCardNotification(metrics)
    }

    /**
     * 通知构建的公共头。
     *
     * **两个分支各自 new 一个，绝不跨分支复用同一个 Builder**：
     * Builder 是可变的，若提升分支在 `build()` 抛异常前已经写入
     * `setStyle(MetricStyle)` / `setRequestPromotedOngoing(true)`，
     * 回退分支再往同一个 Builder 上挂 RemoteViews，就会得到一条
     * 「既声明提升、又带自定义视图」的通知——而官方明确禁止 promoted
     * 通知携带 customContentView，系统会直接丢弃它。
     */
    private fun baseNotificationBuilder(): Notification.Builder {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, OsPlusApplication.CHANNEL_FLUID)
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
    }

    /**
     * 实时任务通知分支（API 37+）。
     *
     * 指标项在 [LiveNotif] 侧已收敛到 1~3 项，这里再 `take(3)` 作为第二道防线：
     * 多传的项会被系统**静默丢弃**（不报错），只表现为「勾了却没显示」，很难排查。
     */
    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun buildPromotedNotification(
        metrics: List<NotifMetric>,
        title: String,
        recording: Boolean,
    ): Notification {
        val s = LiveMetrics.snapshot.value
        val fps = FpsRecorder.sample.value.fps
        val shown = metrics.take(LiveNotif.MAX_METRICS).ifEmpty { LiveNotif.defaultMetrics }

        val style = Notification.MetricStyle()
        shown.forEach { m -> style.addMetric(metricOf(m, s, fps)) }

        // 关键指标：优先帧率（折叠态与状态栏芯片最常看它），未勾选时取第一项
        val critical = shown.indexOf(NotifMetric.Fps).takeIf { it >= 0 } ?: 0
        style.setCriticalMetric(critical)

        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FpsOverlayService::class.java).setAction(ACTION_TOGGLE_RECORDING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notify),
            if (recording) "停止记录" else "开始记录",
            toggleIntent,
        ).build()

        return baseNotificationBuilder()
            .setContentTitle(title)
            .setStyle(style)
            .setRequestPromotedOngoing(true)
            // 状态栏芯片显示关键指标的紧凑文案（≤7 字符才会完整显示）
            .setShortCriticalText(compact(shown[critical], s, fps, withUnit = true))
            .addAction(toggleAction)
            .build()
    }

    /**
     * RemoteViews 凝光卡片分支（悬浮窗模式 / API 36 及以下 / 提升分支失败时的退路）。
     *
     * RemoteViews 不支持自定义 View / Canvas / Shader，玻璃材质在 App 进程里
     * 预渲染成一张 Bitmap 塞进 ImageView。
     */
    private fun buildCardNotification(metrics: List<NotifMetric>): Notification {
        val views = RemoteViews(packageName, R.layout.notification_liquid_card)
        views.setImageViewBitmap(R.id.notif_glass_bg, glassBitmap())
        applyNotificationText(views, metrics)
        // DecoratedCustomViewStyle 让系统为自定义视图套上标准通知外壳；
        // 小图标必须用单色原生 ic_notify，彩色 mipmap 会被状态栏渲染成白色色块
        return baseNotificationBuilder()
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .build()
    }

    /**
     * 单个指标对应的系统 Metric。
     *
     * 值类型按「读得到什么」选：频率 / 功耗 / 充电速度是格式化文本（单位已含在文本里），
     * 占用率与帧率是数值，温度是浮点——数值型的单位交给系统渲染，展开态会并到标签后。
     *
     * 标签一律取 [NotifMetric.notifLabel]（短标签）：系统把卡片宽度均分给每个指标，
     * 字号与列宽都由系统模板决定、应用改不了，长标签会被直接截断。
     */
    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun metricOf(
        m: NotifMetric,
        s: LiveMetrics.Snapshot,
        fps: Float,
    ): Notification.Metric = when (m) {
        NotifMetric.Fps -> Notification.Metric(
            Notification.Metric.FixedFloat(fps, "FPS"), m.notifLabel,
        )
        NotifMetric.CpuLoad -> Notification.Metric(
            Notification.Metric.FixedInt(s.cpuLoad.roundToInt(), "%"), m.notifLabel,
        )
        NotifMetric.CpuFreq -> Notification.Metric(
            Notification.Metric.FixedText(if (s.cpuFreqMhz > 0) fmtFreq(s.cpuFreqMhz) else "--"),
            m.notifLabel,
        )
        NotifMetric.GpuLoad -> Notification.Metric(
            Notification.Metric.FixedInt(if (s.gpuLoad >= 0) s.gpuLoad else 0, "%"), m.notifLabel,
        )
        NotifMetric.GpuFreq -> Notification.Metric(
            Notification.Metric.FixedText(if (s.gpuMhz > 0) fmtFreq(s.gpuMhz) else "--"),
            m.notifLabel,
        )
        NotifMetric.MemUsed -> Notification.Metric(
            Notification.Metric.FixedInt(s.memUsedPercent.roundToInt(), "%"), m.notifLabel,
        )
        NotifMetric.Power -> Notification.Metric(
            Notification.Metric.FixedText(if (s.powerMw > 0f) "%.0fmW".format(s.powerMw) else "--"),
            m.notifLabel,
        )
        NotifMetric.BatteryTemp -> Notification.Metric(
            s.batteryTempC
                ?.let { Notification.Metric.FixedFloat(it, "℃") }
                ?: Notification.Metric.FixedText("--"),
            m.notifLabel,
        )
        NotifMetric.ChargeSpeed -> Notification.Metric(
            Notification.Metric.FixedText(if (s.chargeW > 0f) "%.1fW".format(s.chargeW) else "--"),
            m.notifLabel,
        )
    }

    /**
     * 把用户勾选的指标写进 RemoteViews 卡片（悬浮窗模式 / API 36 及以下的回退分支）。
     *
     * 卡片只有两行文本：第 1 行放前两项、第 2 行放第三项。
     * 选满 3 项时正好铺满两行，选 1~2 项时第 2 行留空。
     */
    private fun applyNotificationText(views: RemoteViews, metrics: List<NotifMetric>) {
        val s = LiveMetrics.snapshot.value
        val fps = FpsRecorder.sample.value.fps
        val shown = metrics.take(LiveNotif.MAX_METRICS).ifEmpty { LiveNotif.defaultMetrics }
        val dot = if (FpsRecorder.recording.value) "● " else ""
        val parts = shown.map { "${it.label} ${compact(it, s, fps)}" }
        views.setTextViewText(R.id.notif_line1, dot + parts.take(2).joinToString("  ·  "))
        views.setTextViewText(R.id.notif_line2, parts.drop(2).joinToString("  ·  "))
    }

    /** MHz → 「2.05GHz」/「855MHz」 */
    private fun fmtFreq(mhz: Int): String =
        if (mhz >= 1000) "%.2fGHz".format(mhz / 1000.0) else "${mhz}MHz"

    /** 凝光材质 Bitmap 缓存：尺寸与渠道风格不随采样变化，渲染一次反复使用 */
    private var cachedGlass: Bitmap? = null

    private fun glassBitmap(): Bitmap {
        cachedGlass?.let { return it }
        val d = resources.displayMetrics.density
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceIn(360, 1080)
        val height = (74f * d).toInt().coerceAtLeast(96)
        val radius = 22f * d

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        // 1) 玻璃体：纵向深蓝渐变，模拟半透明深色玻璃的厚度
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.parseColor("#E62A3750"),
                Color.parseColor("#D8131826"),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, radius, radius, body)

        // 2) 凝光：斜向高光带，从左上掠到右下，模拟光源在曲面玻璃上的反射
        val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(
                    Color.parseColor("#40FFFFFF"),
                    Color.parseColor("#14FFFFFF"),
                    Color.parseColor("#00000000"),
                    Color.parseColor("#0FFFFFFF"),
                ),
                floatArrayOf(0f, 0.35f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, radius, radius, gloss)

        // 3) 顶部受光边缘：一条更亮的细渐变，勾出玻璃上缘
        val topEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, 10f * d,
                Color.parseColor("#59FFFFFF"),
                Color.parseColor("#00FFFFFF"),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, radius, radius, topEdge)

        // 4) 发丝描边：勾出轮廓，通知阴影里仍有清晰边界
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * d
            color = Color.parseColor("#2EFFFFFF")
        }
        val outlineRect = RectF(
            rect.left + 0.5f * d,
            rect.top + 0.5f * d,
            rect.right - 0.5f * d,
            rect.bottom - 0.5f * d,
        )
        canvas.drawRoundRect(outlineRect, radius - 0.5f * d, radius - 0.5f * d, outline)

        cachedGlass = bmp
        return bmp
    }

    /** 记录状态或采样快照变化后刷新通知内容 */
    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    override fun onDestroy() {
        FpsOverlayState.set(false)
        scope.cancel()
        removeOverlay()
        removeAnchor()
        cachedGlass?.recycle()
        cachedGlass = null
        // 仍在记录时不要停掉统计器，否则记录会被中断
        FpsRecorder.overlayHolds = false
        if (!FpsRecorder.recording.value) FpsRecorder.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OSPlusNotif"

        private const val NOTIFICATION_ID = 0x0521

        /**
         * 实时读数的刷新周期（毫秒）。
         *
         * 抽屉置顶卡片、状态栏芯片与悬浮窗三处共用这一个节拍，
         * 与 ViewModel 的采样间隔（1 秒）一致，因此显示与数据同频。
         */
        private const val REFRESH_INTERVAL_MS = 1000L

        /** 通知「开始/停止记录」按钮的 action */
        private const val ACTION_TOGGLE_RECORDING = "com.osplus.tools.action.TOGGLE_FPS_RECORDING"

        /** 判定为「拖动」而非「点击」的位移阈值（像素） */
        private const val DRAG_SLOP = 8f

        /** 按住超过该时长即进入拖动模式，避免长按被误判成轻点 */
        private const val LONG_PRESS_MS = 320L

        /** 记录中的红点颜色 */
        private val RECORDING_DOT_COLOR = Color.parseColor("#FF5252")

        fun start(context: Context) {
            val intent = Intent(context, FpsOverlayService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FpsOverlayService::class.java)) }
        }
    }
}
