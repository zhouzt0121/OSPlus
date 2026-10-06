package com.osplus.tools.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮窗管理器可开启的监视器种类。
 *
 * [key] 是持久化标识：改名会让用户已保存的开关键失效，因此必须保持稳定。
 * [title] 是管理器页里的名称；[summary] 一句话说清它显示什么。
 *
 * **顺序即界面顺序**，也是悬浮窗初始落点的计算序（见 [MonitorState.defaultPosition]）。
 * 声明顺序按「信息密度从低到高」排：迷你 → 负载 → 帧率 → 温度 → 进程 → 线程，
 * 用户从上面开始往下开，开得越多越往下，不会一上来就撞在一起。
 */
enum class MonitorKind(
    val key: String,
    val title: String,
    val summary: String,
) {
    /** 设备性能概览：CPU / GPU / 内存 / 帧率 / 功耗 / 温度各有读数时的单行摘要 */
    Mini("mini", "迷你监视器", "单行概览：CPU 占用、帧率、温度，几乎不遮挡内容"),

    /** 负载监视器：CPU / GPU / 内存 / 功耗的多行面板（对应现有 LOAD 形态） */
    Load("load", "负载监视器", "多行面板：CPU、GPU、内存、帧率、功耗、电池温度"),

    /**
     * 帧率监视器：只显示一个帧率数值，**点一下窗口就开始/停止录制**。
     *
     * 刻意极简（无标题、无状态文字）——它的用法是「瞄一眼现在多少帧」，
     * 录制状态由窗口边框颜色（记录中转红）表达。点击即切换录制，
     * 走的是会建会话、能落库的完整链路。
     */
    Fps("fps", "帧率监视器", "只显示帧率数值，点一下窗口开始/停止录制"),

    /** 温度监视器：CPU 结温 / GPU / 电池，按真机可读的 thermal zone 语义选取 */
    Thermal("thermal", "温度监视器", "CPU 结温、GPU 温度与电池温度（三行分别列出）"),

    /** 进程监视器：CPU 占用最高的进程（需 Root） */
    Process("process", "进程监视器", "CPU 占用最高的进程列表（需要 Root）"),

    /** 线程监视器：CPU 占用最高的线程（需 Root） */
    Thread("thread", "线程监视器", "CPU 占用最高的线程列表（需要 Root）"),
}

/**
 * 6 个监视器悬浮窗的开关与位置状态。
 *
 * 与 [FpsOverlayState] / [LiveNotif] 一样做成单例：开关由管理器页写入、
 * 由前台服务读取，两边必须是同一份真值，否则会出现「管理器里开了、窗口没出来」
 * 的撕裂。服务订阅这里的 StateFlow，因此改动无需重启服务即可即时生效。
 *
 * **为什么用 Set 而不是 6 个布尔**：开关数量随枚举增长，6 个独立布尔
 * 意味着 6 组 if / 6 个 MutableStateFlow / 6 条持久化 key，新增监视器
 * 要改 6 处；用集合后新增一个枚举项即可，其余代码零改动。
 */
object MonitorState {

    /** 当前开启的监视器集合；空集 = 全部关闭（此时服务可以整体停掉） */
    private val _enabled = MutableStateFlow<Set<MonitorKind>>(emptySet())
    val enabled: StateFlow<Set<MonitorKind>> = _enabled.asStateFlow()

    /** 服务是否真的在运行（由服务自身回写，不直接等于 enabled 非空） */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * 统一不透明度，作用到全部监视器窗口。
     *
     * 做成全局一个值而不是每个窗口一个：6 个窗口各调一次不透明度
     * 是没有意义的负担，用户想要的只是「这些浮窗别太挡视线」。
     */
    private val _alpha = MutableStateFlow(DEFAULT_ALPHA)
    val alpha: StateFlow<Float> = _alpha.asStateFlow()

    /** 不透明度下限。低于 0.25 已看不清文字，再低就只剩一块色斑 */
    const val MIN_ALPHA = 0.25f

    /** 默认不透明度，与既有帧率悬浮窗保持一致 */
    const val DEFAULT_ALPHA = 0.92f

    /** 从偏好恢复，进程启动时调用一次 */
    fun load(context: Context) {
        _enabled.value = Preferences.enabledMonitors(context)
        _alpha.value = Preferences.monitorAlpha(context).coerceIn(MIN_ALPHA, 1f)
    }

    fun isEnabled(kind: MonitorKind): Boolean = kind in _enabled.value

    fun setEnabled(context: Context, kind: MonitorKind, on: Boolean) {
        val next = if (on) _enabled.value + kind else _enabled.value - kind
        _enabled.value = next
        Preferences.setEnabledMonitors(context, next)
    }

    /** 全开 / 全关（管理器页的「全部开启」「全部关闭」动作） */
    fun setAll(context: Context, kinds: Set<MonitorKind>) {
        _enabled.value = kinds
        Preferences.setEnabledMonitors(context, kinds)
    }

    fun setAlpha(context: Context, value: Float) {
        val v = value.coerceIn(MIN_ALPHA, 1f)
        _alpha.value = v
        Preferences.setMonitorAlpha(context, v)
    }

    internal fun setRunning(value: Boolean) {
        _running.value = value
    }

    /**
     * 某个监视器的初始落点。
     *
     * 首次开启时按枚举顺序**纵向铺开**（每个往下让出 [STACK_STEP_PX]），
     * 而不是全部堆在同一个坐标：6 个窗口落在同一点时，用户只能看到最上面那个，
     * 拖动它之后才「发现」下面还叠着 5 个——这正是需要避免的困惑。
     *
     * 用户拖动过之后位置由偏好决定，本方法不再参与。
     */
    fun defaultPosition(context: Context, kind: MonitorKind): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val baseY = (metrics.heightPixels * 0.18f).toInt()
        val step = (metrics.heightPixels * 0.075f).toInt().coerceAtLeast(96)
        val index = kind.ordinal
        val x = (metrics.widthPixels * 0.06f).toInt()
        return x to (baseY + index * step)
    }

    /** 每个监视器的位置持久化 key 前缀；存成 "monitor_x_<key>" 形式 */
    internal fun posKeyX(kind: MonitorKind) = "monitor_x_${kind.key}"

    internal fun posKeyY(kind: MonitorKind) = "monitor_y_${kind.key}"
}
