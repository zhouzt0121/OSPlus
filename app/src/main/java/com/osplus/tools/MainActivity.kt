package com.osplus.tools

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.osplus.tools.ui.OsPlusApp

/**
 * 唯一 Activity。
 *
 * 已启用 edge-to-edge 与预测性返回手势（AndroidManifest 中
 * android:enableOnBackInvokedCallback="true"）。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 用户选择即可 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 显式传透明 scrim，**不要用 enableEdgeToEdge() 的默认值**。
        //
        // 默认实现会按系统深色模式挑一层遮罩（浅色 ~90% 白、深色 ~50% 黑），
        // 叠在状态栏上。我们的主题跟随系统没问题，但遮罩一旦叠上去，
        // 状态栏区域就会与下方页面之间出现一条硬边的色带——
        // 实测该处为 rgb(130,134,143)，而页面是 rgb(238,242,248)，
        // 视觉上就是顶上糊了一条灰胶带。
        //
        // 两个 scrim 都传 TRANSPARENT，让状态栏彻底透明、由页面背景自己铺满。
        // 图标明暗单独用 detectDarkMode 决定，与主题保持同一判据。
        val detectDarkMode: (Resources) -> Boolean = { res ->
            (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                detectDarkMode,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                detectDarkMode,
            ),
        )
        requestHighestRefreshRate()
        requestNotificationPermissionIfNeeded()
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                OsPlusApp()
            }
        }
    }

    /**
     * 向系统请求本机支持的最高刷新率。
     *
     * 这台设备的面板支持 120Hz，但系统默认会按内容与功耗策略在 60/90/120 之间切换；
     * 不显式声明偏好时，仪表类界面很容易被压到 60Hz，
     * 于是「实时帧率」这类读数本身也被限制在 60，看不出设备真实能力。
     *
     * 做法是挑出**分辨率与当前窗口一致、且刷新率最高**的显示模式，
     * 用 [WindowManager.LayoutParams.preferredDisplayModeId] 指定它——
     * 直接改模式 id 会影响分辨率，所以先用 `physicalWidth/Height` 过滤，
     * 只在同分辨率的模式里比刷新率。
     *
     * 这是**偏好**而非强制：系统仍可在省电或发热时降频，
     * 应用不应假设它一定生效，因此不读回、不据此调整任何 UI。
     */
    private fun requestHighestRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return
        if (display.supportedModes.isEmpty()) return

        val w = display.mode.physicalWidth
        val h = display.mode.physicalHeight
        // 同分辨率里挑刷新率最高的那个模式
        val best = display.supportedModes
            .filter { it.physicalWidth == w && it.physicalHeight == h }
            .maxByOrNull { it.refreshRate }
            ?: return

        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
            preferredRefreshRate = best.refreshRate
        }
    }

    /**
     * Android 13+ 需要运行时授予通知权限，否则帧率悬浮窗的前台服务通知不可见。
     * 仅在首次启动时询问，用户拒绝后不再打扰。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
