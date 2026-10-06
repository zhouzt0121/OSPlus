package com.osplus.tools.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.osplus.tools.core.Preferences
import com.osplus.tools.service.FpsOverlayService
import com.osplus.tools.service.MonitorOverlayService

/**
 * 开机广播接收器。
 *
 * 若用户在「进程 · 帧率记录」中开启过悬浮窗、或在「悬浮窗管理器」里开过
 * 监视器，则开机后自动恢复对应的前台服务；未开启或未授予悬浮窗权限时
 * 不做任何事。
 *
 * 两个服务各自独立判断：用户可能只开了其中一个，不能因为另一个没开就跳过。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.canDrawOverlays(context)) return
        // 启动失败（如被系统限制）不应影响开机流程，服务内部已做异常兜底
        if (Preferences.isFpsOverlayEnabled(context)) {
            FpsOverlayService.start(context)
        }
        if (Preferences.enabledMonitors(context).isNotEmpty()) {
            MonitorOverlayService.start(context)
        }
    }
}
