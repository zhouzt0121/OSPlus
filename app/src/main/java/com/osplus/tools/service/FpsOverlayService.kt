package com.osplus.tools.service

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
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.osplus.tools.MainActivity
import com.osplus.tools.OsPlusApplication
import com.osplus.tools.R
import com.osplus.tools.core.FpsOverlayState
import com.osplus.tools.core.FpsRecorder
import kotlinx.coroutines.flow.combine
import com.osplus.tools.core.LiveMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 跨应用帧率悬浮窗。
 *
 * 需要 SYSTEM_ALERT_WINDOW 权限，以 specialUse 前台服务常驻。
 *
 * 关键点：悬浮窗本身是本进程的可见窗口，系统会持续向本进程投递 vsync，
 * 因此它同时充当「跨应用帧率采样源」——服务启动后驱动共享的 [FpsRecorder]，
 * 应用退到后台时录制仍会继续（前台服务保活 + 悬浮窗持续收到帧回调），
 * 从而支持在**其他应用**中记录帧率。
 */
class FpsOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: TextView? = null

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
    }

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
            x = 40
            y = 320
        }
        runCatching { windowManager.addView(view, params) }
        overlayView = view
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
            .setContentText("正在显示实时帧率")
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
        // 应用内正在录制时不要停掉统计器，否则录制会被中断
        if (!FpsRecorder.appRetained) FpsRecorder.stop()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x0521


        fun start(context: Context) {
            val intent = Intent(context, FpsOverlayService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FpsOverlayService::class.java)) }
        }
    }
}
