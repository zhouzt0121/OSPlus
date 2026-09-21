package com.osplus.tools.core

import android.content.Context

/**
 * 轻量偏好存储。
 *
 * 仅保存需要在进程重启后恢复的开关状态（目前是帧率悬浮窗），
 * 不使用 DataStore 以保持零额外依赖。
 */
object Preferences {

    private const val FILE = "osplus_prefs"
    private const val KEY_FPS_OVERLAY = "fps_overlay_enabled"
    private const val KEY_AUTO_REFRESH = "auto_refresh_enabled"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_MONET = "theme_monet"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 帧率悬浮窗是否开启（用于开机恢复与界面回显） */
    fun isFpsOverlayEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_FPS_OVERLAY, false)

    fun setFpsOverlayEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_FPS_OVERLAY, enabled).apply()
    }

    /** 实时采样开关 */
    fun isAutoRefreshEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_REFRESH, true)

    fun setAutoRefreshEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_REFRESH, enabled).apply()
    }

    /** 主题模式名称，取值见 [top.yukonga.miuix.kmp.theme.ColorSchemeMode] */
    fun themeModeName(context: Context): String =
        sp(context).getString(KEY_THEME_MODE, "System") ?: "System"

    fun setThemeModeName(context: Context, name: String) {
        sp(context).edit().putString(KEY_THEME_MODE, name).apply()
    }

    /** 是否使用壁纸取色（Monet） */
    fun isMonetEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_MONET, false)

    fun setMonetEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_MONET, enabled).apply()
    }
}
