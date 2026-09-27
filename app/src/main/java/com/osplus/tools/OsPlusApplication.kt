package com.osplus.tools

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class OsPlusApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // 旧通道保留注册，避免覆盖升级时残留订阅异常；通知已不再使用它
        val legacy = NotificationChannel(
            CHANNEL_FPS,
            "帧率悬浮窗",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "OSPlus 跨应用帧率显示"
            setShowBadge(false)
        }
        manager.createNotificationChannel(legacy)

        // 实时任务状态通知（仿流体云卡片）：
        // 高优先级 + 锁屏公开 + 静音免振动，确保状态卡片始终可见且不打扰
        val channel = NotificationChannel(
            CHANNEL_FLUID,
            "实时任务流体云",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "实时任务状态，仿流体云通知卡片"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_FPS = "osplus_fps"
        const val CHANNEL_FLUID = "fluid_cloud_task"
    }
}
