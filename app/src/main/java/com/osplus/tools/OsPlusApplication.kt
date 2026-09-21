package com.osplus.tools

import android.app.Application
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
        val channel = NotificationChannel(
            CHANNEL_FPS,
            "帧率悬浮窗",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "OSPlus 跨应用帧率显示"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_FPS = "osplus_fps"
    }
}
