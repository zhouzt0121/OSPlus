package com.osplus.tools.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
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

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        FpsOverlayState.set(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        userAlpha = Preferences.overlayAlpha(this).coerceIn(FpsOverlayState.MIN_ALPHA, 1f)
        FpsOverlayState.setAlpha(userAlpha)
        addOverlay()

        // 与录制功能共用同一个逐帧统计器，避免两套 Choreographer 互相干扰
        FpsRecorder.start()
        scope.launch {
            // 只显示三个数值：帧率 / CPU 占用 / GPU 占用
            combine(
                FpsRecorder.sample,
                LiveMetrics.cpuLoad,
                LiveMetrics.gpuLoad,
            ) { fps, cpu, gpu -> Triple(fps.fps, cpu, gpu) }
                .collectLatest { (fps, cpu, gpu) ->
                    overlayView?.text = buildString {
                        append("%.0f".format(fps))
                        append("   ")
                        append("%.0f%%".format(cpu))
                        append("   ")
                        append(if (gpu >= 0) "$gpu%" else "--")
                    }
                }
        }
        // 界面调整不透明度后立即作用到窗口，无需重启服务
        scope.launch {
            FpsOverlayState.alpha.collectLatest { value ->
                userAlpha = value
                layoutParams?.let { p ->
                    p.alpha = value
                    overlayView?.let { v -> runCatching { windowManager.updateViewLayout(v, p) } }
                }
            }
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
            alpha = userAlpha
        }
        attachDragHandler(view, params)
        runCatching { windowManager.addView(view, params) }
        overlayView = view
        layoutParams = params
    }

    /**
     * 拖动悬浮窗。
     *
     * 用 rawX/rawY 的位移增量直接累加到窗口偏移上，不依赖控件自身的布局坐标，
     * 因此不会出现「手指跟窗口错位」的问题；松手时把落点写回偏好。
     */
    private fun attachDragHandler(view: View, params: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    // 拖动过程中保证看得清
                    params.alpha = 1f
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(dx) > DRAG_SLOP || kotlin.math.abs(dy) > DRAG_SLOP)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = clampX(startX + dx.roundToInt(), view)
                        params.y = clampY(startY + dy.roundToInt(), view)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    params.alpha = userAlpha
                    runCatching { windowManager.updateViewLayout(view, params) }
                    if (dragging) {
                        Preferences.setOverlayPosition(this, params.x, params.y)
                    }
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

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, OsPlusApplication.CHANNEL_FPS)
            .setContentTitle("OSPlus 帧率监视")
            .setContentText("正在显示实时帧率，可拖动调整位置")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(intent)
            .build()
    }

    override fun onDestroy() {
        FpsOverlayState.set(false)
        scope.cancel()
        overlayView?.let { v -> runCatching { windowManager.removeView(v) } }
        overlayView = null
        layoutParams = null
        // 应用内正在录制时不要停掉统计器，否则录制会被中断
        if (!FpsRecorder.appRetained) FpsRecorder.stop()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x0521

        /** 判定为「拖动」而非「点击」的位移阈值（像素） */
        private const val DRAG_SLOP = 8f

        fun start(context: Context) {
            val intent = Intent(context, FpsOverlayService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FpsOverlayService::class.java)) }
        }
    }
}
