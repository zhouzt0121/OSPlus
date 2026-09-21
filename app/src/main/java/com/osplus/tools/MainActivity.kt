package com.osplus.tools

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                OsPlusApp()
            }
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
