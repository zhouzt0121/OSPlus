package com.osplus.tools.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.osplus.tools.core.Preferences
import com.osplus.tools.service.FpsOverlayService

/**
 * 开机广播接收器。
 *
 * 若用户在「进程 · 帧率记录」中开启过悬浮窗，则开机后自动恢复该前台服务；
 * 未开启或未授予悬浮窗权限时不做任何事。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Preferences.isFpsOverlayEnabled(context)) return
        if (!Settings.canDrawOverlays(context)) return
        // 启动失败（如被系统限制）不应影响开机流程，服务内部已做异常兜底
        FpsOverlayService.start(context)
    }
}
