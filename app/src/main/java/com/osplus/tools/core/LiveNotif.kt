package com.osplus.tools.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实时任务通知（Live Updates）可展示的指标项。
 *
 * [key] 是持久化标识：改名会让用户已保存的选择失效，因此必须保持稳定。
 * [label] 是设置页里用的完整名称；[notifLabel] 是通知里用的极短标签。
 * 两者分开是必需的——见 [notifLabel] 的说明。
 * [defaultEnabled] 决定首次安装时的默认勾选状态。
 *
 * 默认值的选取依据是「瞬时变化、且不依赖 root 就能读到」：
 * 帧率、CPU 占用、GPU 占用三项。频率类指标变化较慢，功耗 / 温度 / 充电速度
 * 依赖厂商 sysfs（无 root 时读不到，会显示 `--`），因此默认关闭、按需开启。
 */
enum class NotifMetric(
    val key: String,
    val label: String,
    /**
     * 通知里显示的极短标签。
     *
     * 系统 `MetricStyle` 把卡片横向空间**均分**给每个指标，且字体与列宽
     * 由系统模板决定，应用无法调整字号。选满 3 项时每项只有约三分之一卡宽，
     * 长标签会被直接截断（实测「内存占用 (%)」被右边缘切掉，读不出单位）。
     * 因此通知里一律用短标签，把「单位 + 括号」也一并算进预算。
     */
    val notifLabel: String,
    val defaultEnabled: Boolean,
) {
    Fps("fps", "帧率", "帧率", true),
    CpuLoad("cpu_load", "CPU 占用", "CPU", true),
    CpuFreq("cpu_freq", "CPU 频率", "CPU 频率", false),
    GpuLoad("gpu_load", "GPU 占用", "GPU", true),
    GpuFreq("gpu_freq", "GPU 频率", "GPU 频率", false),
    MemUsed("mem_used", "内存占用", "内存", false),
    Power("power", "实时功耗", "功耗", false),
    BatteryTemp("battery_temp", "电池温度", "温度", false),
    ChargeSpeed("charge_speed", "充电速度", "充电", false);
}

/**
 * 「实时任务通知 / 悬浮窗」两种呈现方式的选择状态。
 *
 * 与 [FpsOverlayState] 一样做成单例：选择由设置页写入、由前台服务读取，
 * 两边必须是同一份真值，否则会出现「设置里改了、通知没变」的撕裂。
 * 服务订阅这里的 StateFlow，因此切换无需重启服务即可即时生效。
 */
object LiveNotif {

    /**
     * 通知最多能展示的指标项数量。
     *
     * 这是**平台硬限制**，不是本应用的取舍：官方《创建指标样式通知》与
     * `Notification.MetricStyle` 的 API 文档均写明「展开态最多显示 3 项指标」。
     * 因此这里做上限收敛，避免用户勾了 5 项却在真机上只看到 3 项、
     * 又不知道为什么后两项没出现。
     */
    const val MAX_METRICS = 3

    /** 至少保留 1 项：MetricStyle 不含任何 Metric 时会被 `Builder.build()` 判为非法样式 */
    const val MIN_METRICS = 1

    /** 出厂默认显示项：帧率 / CPU 占用 / GPU 占用 */
    val defaultMetrics: List<NotifMetric> = NotifMetric.entries.filter { it.defaultEnabled }

    /** true = 实时任务通知；false = 悬浮窗 */
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * 是否在通知模式下保留「保活锚点」。
     *
     * 一个 1×1 的不可见悬浮窗，不显示任何内容，只为让本进程持有可见窗口。
     * 原因见 [com.osplus.tools.service.FpsOverlayService] 的锚点说明：
     * ColorOS 的冻结框架会把「没有可见窗口的后台应用」冻住，
     * 前台服务不足以豁免，而 root 也解决不了（冻结是内核层面的）。
     *
     * 需要悬浮窗权限；未授权时锚点无法建立，通知在后台仍会暂停更新。
     */
    private val _keepAlive = MutableStateFlow(true)
    val keepAlive: StateFlow<Boolean> = _keepAlive.asStateFlow()

    private val _metrics = MutableStateFlow(defaultMetrics)
    val metrics: StateFlow<List<NotifMetric>> = _metrics.asStateFlow()

    /** 从偏好恢复，进程启动时调用一次 */
    fun load(context: Context) {
        _enabled.value = Preferences.isLiveNotifEnabled(context)
        _keepAlive.value = Preferences.isNotifKeepAliveEnabled(context)
        val saved = Preferences.liveNotifItems(context)
        // 偏好为空（首次安装或数据被清）时回落到默认项
        _metrics.value = normalize(NotifMetric.entries.filter { it.key in saved })
    }

    fun setEnabled(context: Context, value: Boolean) {
        _enabled.value = value
        Preferences.setLiveNotifEnabled(context, value)
    }

    fun setKeepAlive(context: Context, value: Boolean) {
        _keepAlive.value = value
        Preferences.setNotifKeepAliveEnabled(context, value)
    }

    fun setMetrics(context: Context, value: List<NotifMetric>) {
        val normalized = normalize(value)
        _metrics.value = normalized
        Preferences.setLiveNotifItems(context, normalized.map { it.key }.toSet())
    }

    /**
     * 收敛为「枚举声明顺序 + 数量在 [MIN_METRICS]..[MAX_METRICS] 之间」的列表。
     *
     * 固定用枚举顺序而不是用户点击顺序，是为了让通知里的指标位置稳定：
     * 否则同一组选择会因为勾选先后不同而每次刷新都换位置。
     */
    private fun normalize(value: List<NotifMetric>): List<NotifMetric> {
        val chosen = value.toSet()
        val ordered = NotifMetric.entries.filter { it in chosen }.take(MAX_METRICS)
        return ordered.ifEmpty { defaultMetrics }
    }
}
