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
    private const val KEY_OVERLAY_X = "overlay_x"
    private const val KEY_OVERLAY_Y = "overlay_y"
    private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
    private const val KEY_OVERLAY_FORM = "overlay_form"
    private const val KEY_POWER_CURRENT_FACTOR = "power_current_factor"
    private const val KEY_POWER_DUAL_CELL = "power_serial_dual_cell"
    private const val KEY_LIVE_NOTIF = "live_notif_enabled"
    private const val KEY_NOTIF_ITEMS = "live_notif_items"
    private const val KEY_NOTIF_KEEPALIVE = "notif_keepalive_enabled"
    private const val KEY_ZRAM_RESIZE = "zram_resize_enabled"
    private const val KEY_PRIVILEGE_MODE = "privilege_mode"

    /** 悬浮窗位置尚未记录时的默认落点 */
    private const val OVERLAY_DEFAULT_X = 40
    private const val OVERLAY_DEFAULT_Y = 320

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

    // ---------------- 实时任务通知 ----------------

    /**
     * 是否使用「实时任务通知」呈现实时数据。
     *
     * 默认 **true**：优先走系统实时任务通知（状态栏芯片 + 通知抽屉置顶卡片），
     * 它不需要悬浮窗权限；关闭后改用悬浮窗展示。
     */
    fun isLiveNotifEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_LIVE_NOTIF, true)

    fun setLiveNotifEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_LIVE_NOTIF, enabled).apply()
    }

    /**
     * 实时任务通知中显示的指标项 key 集合。
     *
     * 返回空集表示「尚未选择过」，由 [LiveNotif] 决定默认值；
     * 这里不做默认值兜底，避免默认值在两处各写一份而漂移。
     */
    fun liveNotifItems(context: Context): Set<String> =
        sp(context).getStringSet(KEY_NOTIF_ITEMS, emptySet()) ?: emptySet()

    fun setLiveNotifItems(context: Context, keys: Set<String>) {
        sp(context).edit().putStringSet(KEY_NOTIF_ITEMS, keys).apply()
    }

    /**
     * 通知模式下是否保留「保活锚点」（1×1 不可见悬浮窗）。
     *
     * 默认 **true**：没有它，应用退到后台约 5~10 秒就会被 ColorOS 的冻结框架冻住，
     * 通知随之停更。需要悬浮窗权限；未授权时锚点建不起来，功能自动降级。
     */
    fun isNotifKeepAliveEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_NOTIF_KEEPALIVE, true)

    fun setNotifKeepAliveEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_NOTIF_KEEPALIVE, enabled).apply()
    }

    // ---------------- ZRAM 容量调整 ----------------

    /**
     * 是否允许调整 ZRAM 容量。
     *
     * 默认 **false**：重建 zram 交换分区会让正在使用交换区的应用短暂卡顿，
     * 属于有副作用、且改错容量不易回滚的操作，因此默认上锁，
     * 由用户显式开启后才解锁容量控件。
     */
    fun isZramResizeEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_ZRAM_RESIZE, false)

    fun setZramResizeEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_ZRAM_RESIZE, enabled).apply()
    }

    // ---------------- 提权模式 ----------------

    /**
     * 用户显式选择的提权模式名（[PrivilegeMode.name]）。
     *
     * null 表示「用户没选过」，由 [PrivilegeManager] 按可用性自动挑。
     * 存名字而不是序号：枚举顺序将来可能调整，序号会悄悄错位。
     */
    fun privilegeMode(context: Context): String? =
        sp(context).getString(KEY_PRIVILEGE_MODE, null)

    fun setPrivilegeMode(context: Context, name: String) {
        sp(context).edit().putString(KEY_PRIVILEGE_MODE, name).apply()
    }

    // ---------------- 帧率悬浮窗的拖动位置与不透明度 ----------------    /** 悬浮窗水平偏移（像素，相对屏幕左上角） */
    fun overlayX(context: Context): Int =
        sp(context).getInt(KEY_OVERLAY_X, OVERLAY_DEFAULT_X)

    /** 悬浮窗垂直偏移（像素，相对屏幕左上角） */
    fun overlayY(context: Context): Int =
        sp(context).getInt(KEY_OVERLAY_Y, OVERLAY_DEFAULT_Y)

    fun setOverlayPosition(context: Context, x: Int, y: Int) {
        sp(context).edit().putInt(KEY_OVERLAY_X, x).putInt(KEY_OVERLAY_Y, y).apply()
    }

    /** 悬浮窗不透明度 0.25~1.0 */
    fun overlayAlpha(context: Context): Float =
        sp(context).getFloat(KEY_OVERLAY_ALPHA, 0.92f)

    fun setOverlayAlpha(context: Context, alpha: Float) {
        sp(context).edit().putFloat(KEY_OVERLAY_ALPHA, alpha).apply()
    }

    // ---------------- 悬浮窗形态（复刻 Metric 的导航切换） ----------------

    /**
     * 悬浮窗形态。轻点胶囊在三种形态间循环切换，选择会持久化。
     *
     * - [PILL]：紧凑单行，只显示用户勾选的指标；
     * - [LOAD]：负载监视器，多行面板列出全部指标（Metric 的核心形态）；
     * - [MINI]：迷你监视器，极简单行两项，几乎不遮挡内容。
     */
    enum class OverlayForm { PILL, LOAD, MINI }

    fun overlayForm(context: Context): OverlayForm =
        runCatching {
            OverlayForm.valueOf(sp(context).getString(KEY_OVERLAY_FORM, null) ?: return OverlayForm.PILL)
        }.getOrDefault(OverlayForm.PILL)

    fun setOverlayForm(context: Context, form: OverlayForm) {
        sp(context).edit().putString(KEY_OVERLAY_FORM, form.name).apply()
    }

    // ---------------- 功率校准（复刻 Metric：电流倍率 + 串联双电芯） ----------------
    //
    // 内核电流节点在不同 SoC 上的口径差异极大（有的报设计值、有的符号反、
    // 有的差一个固定倍率），电压节点在串联双电芯设备上只报单节电压。
    // 两个开关决定所有 Power 展示的口径：功率 = V × I × 电流倍率 ×（双电芯 ? 2 : 1）。

    /** 电流读数倍率，0.1~10.0；用于修正「功率整体偏大/偏小」的机型 */
    fun powerCurrentFactor(context: Context): Float =
        sp(context).getFloat(KEY_POWER_CURRENT_FACTOR, 1f).coerceIn(0.1f, 10f)

    fun setPowerCurrentFactor(context: Context, factor: Float) {
        sp(context).edit().putFloat(KEY_POWER_CURRENT_FACTOR, factor).apply()
    }

    /** 串联双电芯：总电压是读数的两倍，功率需要 ×2 修正 */
    fun powerSerialDualCell(context: Context): Boolean =
        sp(context).getBoolean(KEY_POWER_DUAL_CELL, false)

    fun setPowerSerialDualCell(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_POWER_DUAL_CELL, enabled).apply()
    }
}
