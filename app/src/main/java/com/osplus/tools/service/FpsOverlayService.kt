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
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
import android.widget.TextView
import com.osplus.tools.MainActivity
import com.osplus.tools.OsPlusApplication
import com.osplus.tools.R
import com.osplus.tools.core.FpsOverlayState
import com.osplus.tools.core.FpsRecorder
import com.osplus.tools.core.LiveMetrics
import com.osplus.tools.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 跨应用帧率悬浮窗。
 *
 * 需要 SYSTEM_ALERT_WINDOW 权限，以 specialUse 前台服务常驻。
 *
 * 关键点：悬浮窗本身是本进程的可见窗口，系统会持续向本进程投递 vsync，
 * 因此它同时充当「跨应用帧率采样源」——服务启动后驱动共享的 [FpsRecorder]，
 * 应用退到后台时录制仍会继续（前台服务保活 + 悬浮窗持续收到帧回调），
 * 从而支持在**其他应用**中记录帧率。
 *
 * 悬浮窗可拖动，位置与不透明度都会持久化，下次启动沿用上次的落点。
 */
class FpsOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 用户设定的不透明度；拖动时临时提到 1.0，松手后恢复 */
    private var userAlpha = 0.92f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知 action：直接在实时任务通知上开始 / 停止帧率记录
        if (intent?.action == ACTION_TOGGLE_RECORDING) {
            FpsRecorder.toggleRecording()
            updateNotification()
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
        // 悬浮窗已由实时任务通知代替：跨应用监视不再依赖悬浮窗窗口，
        // 也就不再需要 SYSTEM_ALERT_WINDOW 权限。addOverlay 代码保留以备回滚。

        // 与录制功能共用同一个逐帧统计器，避免两套 Choreographer 互相干扰
        FpsRecorder.overlayHolds = true
        FpsRecorder.start()
        scope.launch {
            // 只显示三个数值：帧率 / CPU 占用 / GPU 占用；记录中在前面加一个红点
            combine(
                FpsRecorder.sample,
                LiveMetrics.cpuLoad,
                LiveMetrics.gpuLoad,
                FpsRecorder.recording,
            ) { fps, cpu, gpu, recording -> OverlayValues(fps.fps, cpu, gpu, recording) }
                .collectLatest { v ->
                    overlayView?.text = buildOverlayText(v)
                }
        }
        // 通知实时状态：每秒一次的采样快照驱动通知两行数值刷新
        scope.launch {
            combine(LiveMetrics.snapshot, FpsRecorder.recording) { s, rec -> s to rec }
                .collectLatest { (s, _) ->
                    updateNotification()
                }
        }
        // 界面调整不透明度后立即作用到窗口，无需重启服务
        scope.launch {
            FpsOverlayState.alpha.collectLatest { value -> applyAlpha(value) }
        }
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
            text = "--   --%   --%"
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

    /** 窗上文字：记录中在数值前加一个红点，表示正在留档 */
    private fun buildOverlayText(v: OverlayValues): CharSequence {
        val body = "%.0f   %.0f%%   %s".format(
            v.fps,
            v.cpu,
            if (v.gpu >= 0) "${v.gpu}%" else "--",
        )
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
        val cpu: Float,
        val gpu: Int,
        val recording: Boolean,
    )

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
     * 前台服务通知：自定义 RemoteViews + App 内预渲染的「凝光」材质 Bitmap。
     *
     * RemoteViews 不支持自定义 View / Canvas / Shader，玻璃质感无法在通知里实时画，
     * 所以材质在 App 进程里预渲染成一张图塞进 [android.widget.ImageView]，
     * 两行实时数值用 RemoteViews 的 TextView 叠在图上：
     * 第一行 CPU（频率、占用）与 GPU（频率、占用），第二行内存（可用 / 全部）。
     *
     * 通知渠道走标准 [android.app.NotificationChannel]（OsPlusApplication 里注册），
     * 更新只走 [NotificationManager.notify]，保持完全原生通知。
     */
    /**
     * 构建通知。
     *
     * Android 16+（API 36）：**实时任务通知（Live Updates / promoted ongoing）**——
     * 走系统标准 [Notification.MetricStyle]（CPU/GPU/内存五项指标），
     * 请求系统提升为实时任务卡片：通知抽屉顶部、锁屏与状态栏芯片常驻展示。
     * 官方明确 promoted 通知**不得携带 customContentView（RemoteViews）**，
     * 否则直接失去资格，因此此分支不挂自定义视图。
     *
     * Android 12–15：系统没有实时任务机制，回退为 RemoteViews 凝光玻璃卡片。
     */
    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, OsPlusApplication.CHANNEL_FLUID)
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val s = LiveMetrics.snapshot.value
            val fps = FpsRecorder.sample.value.fps
            val recording = FpsRecorder.recording.value
            val memText = if (s.memTotalKb > 0) {
                "%.1f/%.1fGB".format(s.memAvailKb / 1048576f, s.memTotalKb / 1048576f)
            } else {
                "--"
            }
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
            val style = Notification.MetricStyle()
                .addMetric(
                    Notification.Metric(
                        Notification.Metric.FixedFloat(fps, "FPS"),
                        "帧率",
                    ),
                )
                .addMetric(
                    Notification.Metric(
                        Notification.Metric.FixedInt(s.cpuLoad.roundToInt(), "%"),
                        "CPU 占用",
                    ),
                )
                .addMetric(
                    Notification.Metric(
                        Notification.Metric.FixedText(if (s.cpuFreqMhz > 0) fmtFreq(s.cpuFreqMhz) else "--"),
                        "CPU 频率",
                    ),
                )
                .addMetric(
                    Notification.Metric(
                        Notification.Metric.FixedInt(if (s.gpuLoad >= 0) s.gpuLoad else 0, "%"),
                        "GPU 占用",
                    ),
                )
                .addMetric(
                    Notification.Metric(
                        Notification.Metric.FixedText(if (s.gpuMhz > 0) fmtFreq(s.gpuMhz) else "--"),
                        "GPU 频率",
                    ),
                )
                .addMetric(
                    Notification.Metric(Notification.Metric.FixedText(memText), "内存 可用/全部"),
                )
                .setCriticalMetric(0)
            return builder
                .setContentTitle(if (recording) "OSPlus 正在记录" else "OSPlus 实时状态")
                .setStyle(style)
                .setRequestPromotedOngoing(true)
                // 状态栏芯片常驻显示帧率（代替悬浮窗的跨应用帧率读取）
                .setShortCriticalText("%.0fFPS".format(fps))
                .addAction(toggleAction)
                .build()
        }
        val views = RemoteViews(packageName, R.layout.notification_liquid_card)
        views.setImageViewBitmap(R.id.notif_glass_bg, glassBitmap())
        applyNotificationText(views)
        // DecoratedCustomViewStyle 让系统为自定义视图套上标准通知外壳；
        // 小图标必须用单色原生 ic_notify，彩色 mipmap 会被状态栏渲染成白色色块
        return builder
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .build()
    }

    /** 把两行实时数值写进 RemoteViews；数据取 [LiveMetrics.snapshot] 的最新值 */
    private fun applyNotificationText(views: RemoteViews) {
        val s = LiveMetrics.snapshot.value
        val cpuFreq = if (s.cpuFreqMhz > 0) fmtFreq(s.cpuFreqMhz) else "--"
        val cpuLoad = "%.0f%%".format(s.cpuLoad)
        val gpuFreq = if (s.gpuMhz > 0) fmtFreq(s.gpuMhz) else "--"
        val gpuLoad = if (s.gpuLoad >= 0) "${s.gpuLoad}%" else "--"
        val recording = FpsRecorder.recording.value
        val dot = if (recording) "● " else ""
        views.setTextViewText(
            R.id.notif_line1,
            "${dot}CPU $cpuFreq · $cpuLoad    GPU $gpuFreq · $gpuLoad",
        )
        val totalGb = s.memTotalKb / 1024.0 / 1024.0
        val availGb = s.memAvailKb / 1024.0 / 1024.0
        val memText = if (totalGb > 0) "%.1f GB / %.1f GB".format(availGb, totalGb) else "--"
        views.setTextViewText(R.id.notif_line2, "内存 $memText")
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
        overlayView?.let { v -> runCatching { windowManager.removeView(v) } }
        overlayView = null
        layoutParams = null
        cachedGlass?.recycle()
        cachedGlass = null
        // 仍在记录时不要停掉统计器，否则记录会被中断
        FpsRecorder.overlayHolds = false
        if (!FpsRecorder.recording.value) FpsRecorder.stop()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x0521

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
