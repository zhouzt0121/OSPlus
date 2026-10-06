package com.osplus.tools.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.model.BatteryInfo
import com.osplus.tools.model.MetricSample
import com.osplus.tools.ui.components.ActionButton
import com.osplus.tools.ui.components.CardSectionLabel
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.HealthBanner
import com.osplus.tools.ui.components.HealthLevel
import com.osplus.tools.ui.components.LineChart
import com.osplus.tools.ui.components.LiquidNavTabs
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.OsMetricRing
import com.osplus.tools.ui.components.RingChart
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SpecGrid
import com.osplus.tools.ui.components.UsageBar
import com.osplus.tools.ui.components.axisSpanLabel
import com.osplus.tools.ui.components.bottomBarContentPadding
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * 总览页可下钻的详情页。
 *
 * [title] 供根布局的统一顶栏显示——标题文案集中在枚举里，
 * 避免顶栏在根布局、页面在屏幕文件里各写一份而漂移。
 *
 * 2.9.0 重构后，这些详情页从**总览与监测两页**都可下钻：
 * 总览的指标卡点进去只是「看更多」，监测的下钻同理。两边共用一套路由。
 */
enum class OverviewDetail(val title: String) {
    Memory("内存"),
    Gpu("GPU"),
    Cpu("CPU"),
    Process("进程"),
    Sched("性能调度"),
}

/**
 * 2×2 指标格里每格的内容高度。
 *
 * 取 [com.osplus.tools.ui.components.OsMetricRing] 的默认环径 96dp：
 * 环卡的内容本来就是 96dp，帧率卡不到这个高度，统一撑到 96dp 后四格等高。
 * 与 `OsMetricRing(size)` 保持一致——只改一边会重新错开。
 */
private val MetricCellContentHeight = 96.dp

/**
 * 总览（一级页）：回答「设备现在怎么样」。
 *
 * 版式按提案的三段式重排（2.9.0 信息架构重构）：
 *
 * 1. **设备状态** —— 健康结论（权限、温度、内存、电量）+ 异常快捷入口。
 *    先回答「有没有问题」，下面的卡片负责「是多少」。
 * 2. **关键指标卡** —— CPU / GPU / 内存 / 帧率 2×2。
 *    **点击进入对应监测详情，而不是直接跳到调参** —— 这是提案明确要求的一条：
 *    摘要卡的职责是「摘要」，从摘要直接跳进一个会写内核节点的页面，
 *    等于把一次只读的探查变成了潜在的高危操作。
 * 3. **设备与电池概况 + 快捷动作** —— 静态规格压到最后，动作收成一行。
 *
 * 总览页**不放任何会改变设备行为的控件**（清理内存是唯一的例外，
 * 它已在顶栏且需要 root）。所有调优入口统一在「调优」一级页。
 *
 * 顶部留白只有 4dp：页面标题由根布局的 [com.osplus.tools.ui.components.OsTopBar]
 * 统一承担，本页不再自绘标题，也不需要在顶部为它预留空间。
 */
@Composable
fun OverviewScreen(
    vm: DeviceViewModel,
    onOpen: (OverviewDetail) -> Unit,
    /** 点指标卡 → 切到监测页（提案：摘要卡点击进入监测详情） */
    onOpenMonitor: () -> Unit,
    /** 电池卡 → 切到记录页（电池会话） */
    onOpenRecords: () -> Unit,
    /** 快捷动作：开始帧率记录 */
    onStartFps: () -> Unit,
    /** 快捷动作：打开悬浮窗管理器 */
    onOpenOverlayManager: () -> Unit,
) {
    val history by vm.history.collectAsStateWithLifecycle()
    val cpu by vm.cpu.collectAsStateWithLifecycle()
    // 提权通道能力表：健康状态用它判断「有没有通道」，而不是「有没有 root」
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val gpu by vm.gpu.collectAsStateWithLifecycle()
    val mem by vm.mem.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val fpsRecording by vm.fpsRecording.collectAsStateWithLifecycle()
    val memClean by vm.memCleanState.collectAsStateWithLifecycle()
    val c = osColors()

    // 清理结果只做一次回执，4 秒后自动收起，避免长期占据首屏
    LaunchedEffect(memClean) {
        if (memClean != null) {
            delay(4000)
            vm.clearMemCleanState()
        }
    }

    val samples: List<MetricSample> = history
    val latest = samples.lastOrNull()

    // 兜底必须是「不可读」而不是 0f：没有采样记录时显示 0% 会让人以为 CPU 闲着
    val cpuLoad = latest?.cpuLoad ?: com.osplus.tools.core.LiveMetrics.UNREADABLE
    val gpuLoad = gpu.loadPercent
    val memPercent = latest?.memUsedPercent ?: 0f
    // SoC 结温与电池温度分别取值、分别判据：两者正常区间相差几十度，
    // 合并成一个 tempC 再套同一组阈值会把正常的 CPU 结温误判成「温度过高」
    val socTempC = cpu.tempC
    val batteryTempC = battery.tempC ?: latest?.batteryTempC
    val charging = battery.status.contains("充电") || battery.plugged.contains("USB") ||
        battery.plugged.contains("AC") || battery.plugged.contains("无线")

    val health = evaluateHealth(
        // 传「是否有提权通道」而不是「是否有 root」：能力表是可用性的真值来源，
        // 不该在页面里再判一次 root 名字
        privilegeAvailable = capabilities.available,
        socTempC = socTempC,
        batteryTempC = batteryTempC,
        memPercent = memPercent,
        availKb = mem.availKb,
        levelPercent = battery.levelPercent,
        charging = charging,
    )

    // 帧率折线取最近 60 秒：足够看出抖动，又不会被 30 分钟的长窗口压成直线
    val trend = remember(samples) { samples.takeLast(60) }
    // 时间轴左端随窗口长度变化（窗口尚未攒满 60 秒时不要谎报「-01:00」）
    val fpsAxisStart = axisSpanLabel(trend.size.coerceAtLeast(1))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------- 一、设备状态 ----------------
        item {
            HealthBanner(
                level = health.level,
                title = health.title,
                detail = health.detail,
            )
        }

        // 异常快捷入口：只有真的处于警示态才出现。
        // 日常「系统正常」时不给入口 —— 一个永远在那儿但它指向的页面里
        // 什么异常都没有的按钮，只会教用户忽略它。
        if (health.level != HealthLevel.Ok) {
            item {
                QuickActions(
                    rootAvailable = rootAvailable,
                    fpsRecording = fpsRecording,
                    // 权限缺失时，快捷入口指向提权管理而不是调优页 ——
                    // 用户此刻真正需要做的是「把权限开了」，不是去看一堆置灰的控件
                    onGrantRoot = { vm.reprobePrivilege() },
                    onStartFps = onStartFps,
                    onStopFps = { vm.stopFpsRecording() },
                    onOpenOverlayManager = onOpenOverlayManager,
                )
            }
        }

        // ---------------- 二、关键指标卡（2×2）----------------
        item { CardSectionLabel("关键指标") }

        // 四格等高的处理与重构前一致：给同一内容最小高度，
        // 帧率卡被撑到 96dp 后与三个环卡天然对齐。
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard(
                    modifier = Modifier.weight(1f),
                    // 提案要求：指标卡点击进入**监测详情**，不是调参页
                    onClick = onOpenMonitor,
                    contentHeight = MetricCellContentHeight,
                ) {
                    OsMetricRing(
                        label = "CPU",
                        valueText = if (cpuLoad < 0f) "--" else "%.0f".format(cpuLoad),
                        unit = "%",
                        hint = cpu.clusters.maxOfOrNull { it.curKhz }
                            ?.let { "${it / 1000} MHz" }
                            ?: "不可读",
                        progress = (cpuLoad.coerceAtLeast(0f)) / 100f,
                        accent = ChartColors.cpu,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                SectionCard(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenMonitor,
                    contentHeight = MetricCellContentHeight,
                ) {
                    OsMetricRing(
                        label = "GPU",
                        valueText = if (gpuLoad >= 0) "$gpuLoad" else "-",
                        unit = "%",
                        // 两列版环心放不下 GPU 型号（「Adreno830v2」需 57.8dp，
                        // 超过环心限宽 57.2dp），所以这里只留实时频率，型号去 GPU 详情页看
                        hint = if (gpu.curMhz > 0) "${gpu.curMhz} MHz" else "不可读",
                        progress = gpuLoad / 100f,
                        accent = ChartColors.gpu,
                        // 负载读不到时画空圈 + 灰字：画一个 0% 的环会让人误以为「显卡闲着」
                        enabled = gpuLoad >= 0,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenMonitor,
                    contentHeight = MetricCellContentHeight,
                ) {
                    OsMetricRing(
                        label = "内存",
                        valueText = "%.0f".format(memPercent),
                        unit = "%",
                        // 说明行只放可用量：加「可用」二字后 55.8dp，距环心安全线 57.2dp
                        // 只剩 1.4dp，页面标签已是「内存」，不再重复说明
                        hint = fmtGb(mem.availKb),
                        progress = memPercent / 100f,
                        accent = ChartColors.mem,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                SectionCard(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenMonitor,
                    contentHeight = MetricCellContentHeight,
                ) {
                    FpsTrendCard(values = trend.map { it.fps }, axisStartLabel = fpsAxisStart)
                }
            }
        }

        // 清理结果回执
        memClean?.let { r ->
            item {
                NoticeBanner(
                    text = if (r.ok) {
                        "已清理${r.target}" +
                            if (r.freedKb > 0) "，释放 ${fmtGb(r.freedKb)}" else ""
                    } else {
                        "清理${r.target}未成功：${r.detail.ifBlank { "需要 Root 权限" }}"
                    },
                    accent = if (r.ok) ChartColors.gpu else ChartColors.power,
                )
            }
        }

        // ---------------- 三、电池摘要 ----------------
        // 提案要求电池的**实时状态**留在总览（历史记录去「记录」页）。
        // 这一张卡只给「还能用多久」，耗电明细与录制在记录页。
        item {
            SectionCard(onClick = onOpenRecords) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingChart(
                        progress = if (battery.levelPercent >= 0) battery.levelPercent / 100f else 0f,
                        color = if (charging) ChartColors.gpu else ChartColors.power,
                        label = "电池",
                        value = if (battery.levelPercent >= 0) "${battery.levelPercent}" else "-",
                        unit = "%",
                        size = 76.dp,
                        stroke = 11.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = batteryRemainingText(battery, charging),
                            style = OsText.valueStrong,
                            color = c.textPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = listOf(
                                if (battery.voltageMv > 0) "%.2f V".format(battery.voltageMv / 1000f) else null,
                                batteryTempC?.let { "%.1f ℃".format(it) },
                                "%.0f mA".format(battery.currentNowUa / 1000f),
                            ).filterNotNull().joinToString(" · "),
                            style = OsText.caption,
                            color = c.textSecondary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "查看耗电记录 ›",
                            style = OsText.micro,
                            color = c.primary,
                        )
                    }
                }
            }
        }

        // ---------------- 四、设备概况 ----------------
        item {
            SectionCard {
                Text(
                    text = "设备概况",
                    style = OsText.value,
                    color = c.textPrimary,
                )

                // 分三组：芯片规格 / 存储容量 / 电池损耗。
                // 九项平铺成一条「标签左、数值右」的长列表时，三类信息完全混在一起，
                // 中间还横着一大片空白，只能一行行扫。分组 + 两列后同样的信息量只占 5 行，
                // 读者能直接跳到关心的那一组。
                Spacer(Modifier.height(14.dp))
                CardSectionLabel("芯片")
                SpecGrid(
                    listOf(
                        "SoC" to cpu.soc.ifBlank { "读取中…" },
                        "架构" to cpu.abi.ifBlank { "-" },
                        "核心" to "${cpu.coreCount} 核心 · ${cpu.clusters.size} 簇",
                        "GPU" to gpu.name.ifBlank { "读取中…" },
                    )
                )

                Spacer(Modifier.height(16.dp))
                CardSectionLabel("存储")
                SpecGrid(
                    listOf(
                        "物理内存" to "${fmtGb(mem.usedKb)} / ${fmtGb(mem.totalKb)}",
                        "SWAP" to if (mem.swapTotalKb > 0) {
                            "${fmtGb(mem.swapUsedKb)} / ${fmtGb(mem.swapTotalKb)}"
                        } else "未启用",
                        "ZRAM" to if (mem.zramTotalKb > 0) {
                            // 「已用」优先用 mem_used_total（压缩后实占内存）；
                            // 部分内核没有该节点（真机 SM8750 就是），退回 orig_data_size
                            // （压缩前原始数据量），语义上仍是「已换出到 zram 的数据量」，
                            // 比显示 `-` 有用。
                            val usedKb = mem.zramUsedKb.takeIf { it > 0L } ?: mem.zramOrigKb
                            "${fmtGb(usedKb)} / ${fmtGb(mem.zramTotalKb)}"
                        } else "未启用",
                    )
                )

                Spacer(Modifier.height(16.dp))
                CardSectionLabel("电池")
                SpecGrid(
                    listOf(
                        "健康" to battery.health.ifBlank { "-" },
                        "循环次数" to if (battery.cycleCount >= 0) {
                            "${battery.cycleCount} 次"
                        } else "-",
                    )
                )
            }
        }

        // ---------------- 五、系统负载 ----------------
        // 这些指标（负载 / 网络 / IO 压力 / 磁盘）此前已在 SystemExtrasDataSource
        // 里实现但没有界面消费，属于「有能力没出口」。
        //
        // 默认关闭：它们要多一条 shell 命令，只看 CPU / 内存 / 功耗的用户
        // 不该为此付每秒一次的往返开销。开关状态记在页内而不是持久化——
        // 它是「本次想看」的临时意图，不是长期偏好。
        item {
            SystemExtrasCard(vm)
        }
    }
}

/**
 * 快捷动作条。
 *
 * 只在异常状态下出现（见调用点注释），三项都指向「用户此刻最可能要做的动作」：
 * 补权限 / 开记录 / 开浮窗。
 */
@Composable
private fun QuickActions(
    rootAvailable: Boolean,
    fpsRecording: Boolean,
    onGrantRoot: () -> Unit,
    onStartFps: () -> Unit,
    onStopFps: () -> Unit,
    onOpenOverlayManager: () -> Unit,
) {
    SectionCard {
        Column(Modifier.padding(vertical = 2.dp)) {
            Text(
                text = "快捷动作",
                style = OsText.value,
                color = osColors().textPrimary,
            )
            Spacer(Modifier.height(10.dp))
            if (!rootAvailable) {
                ActionButton(
                    text = "授权 Root 提权",
                    onClick = onGrantRoot,
                    filled = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    text = if (fpsRecording) "停止帧率记录" else "开始帧率记录",
                    onClick = { if (fpsRecording) onStopFps() else onStartFps() },
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = "悬浮监视器",
                    onClick = onOpenOverlayManager,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 系统负载卡。
 *
 * 四项指标压在一张卡里而不是各占一张：
 *
 * - **负载**必须在同一张卡里看。「1 分钟高、15 分钟正常」是突发，
 *   三个值分三张卡就丢了这层对照关系。
 * - **网络与磁盘**天然成对（都在回答「谁在搬运数据」），
 *   分开会让人以为是两类无关的信息。
 * - **IO 压力**是解释「磁盘很忙但网速为零」的关键——它和磁盘放在一起，
 *   用户才能自己看出「是本地存储瓶颈而不是网络问题」。
 *
 * 不可读时显示 `--` 而不是 0：0 是「确实没有流量」，
 * `--` 是「读不到」，两者对排障的意义完全相反。
 */
@Composable
private fun SystemExtrasCard(vm: DeviceViewModel) {
    val c = osColors()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val enabled by vm.extrasEnabled.collectAsStateWithLifecycle()
    val caps by vm.capabilities.collectAsStateWithLifecycle()

    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "系统负载",
                style = OsText.value,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (enabled) "已开启" else "未开启",
                style = OsText.micro,
                color = c.textTertiary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "读取 /proc/loadavg、/proc/net/dev、/proc/pressure/io、/proc/diskstats。" +
                "这些节点应用身份读不到，需要提权通道；开启后每秒多一条命令。" +
                if (!caps.available) "（当前提权通道不可用，开启后指标会显示 --）" else "",
            style = OsText.micro,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(10.dp))
        // 不再用 `enabled = caps.available || enabled` 置灰。
        //
        // 之前的写法让「开启采集」在没 root 时是个灰按钮且点不动，用户报「按钮无效」——
        // 而真正的问题是：他此刻想做的事（开采集）本身**不需要权限就能开**，
        // 是**数据**需要权限。置灰等于把两件事混成一件，用户既开不了也不知道为什么。
        // 现在始终可点：点开 → 数据读不到 → 下方已有 NoticeBanner 说明原因并指向提权管理。
        ActionButton(
            text = if (enabled) "关闭采集" else "开启采集",
            onClick = { vm.setExtrasEnabled(!enabled) },
            filled = !enabled,
            modifier = Modifier.fillMaxWidth(),
        )

        if (enabled) {
            Spacer(Modifier.height(14.dp))
            if (!extras.anyAvailable) {
                NoticeBanner(
                    text = "尚未采到数据。首次采样没有差分基准，速率类指标要等第二个采样点；" +
                        "若持续为空，请确认提权通道可用。",
                    accent = ChartColors.cpu,
                )
                Spacer(Modifier.height(12.dp))
            }

            CardSectionLabel("平均负载")
            Spacer(Modifier.height(6.dp))
            SpecGrid(
                listOf(
                    "1 分钟" to fmtLoad(extras.load1),
                    "5 分钟" to fmtLoad(extras.load5),
                    "15 分钟" to fmtLoad(extras.load15),
                    "进程" to if (extras.runningProcs >= 0) {
                        "${extras.runningProcs} / ${extras.totalProcs}"
                    } else "--",
                )
            )

            Spacer(Modifier.height(16.dp))
            CardSectionLabel("网络与磁盘")
            Spacer(Modifier.height(6.dp))
            SpecGrid(
                listOf(
                    "网络下行" to fmtRate(extras.netRxBps),
                    "网络上行" to fmtRate(extras.netTxBps),
                    "磁盘读" to fmtRate(extras.diskReadBps),
                    "磁盘写" to fmtRate(extras.diskWriteBps),
                )
            )

            Spacer(Modifier.height(16.dp))
            CardSectionLabel("IO 压力")
            Spacer(Modifier.height(6.dp))
            if (extras.ioPressure10 >= 0f) {
                // UsageBar 只画条，标签与数值要自己排一行
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "some avg10",
                        style = OsText.label,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f%%", extras.ioPressure10),
                        style = OsText.value,
                        color = c.textPrimary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                UsageBar(
                    fraction = extras.ioPressure10 / 100f,
                    color = ChartColors.power,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "表示最近 10 秒内有进程因等待 IO 而停顿的时间占比。接近 0 说明存储不构成瓶颈。",
                    style = OsText.micro,
                    color = c.textTertiary,
                )
            } else {
                Text(
                    text = "-- 该内核未提供 PSI（需要 5.13 及以上且 CONFIG_PSI 开启）",
                    style = OsText.micro,
                    color = c.textTertiary,
                )
            }
        }
    }
}

/** 负载值格式化：不可读显示 `--`，保留两位小数 */
private fun fmtLoad(v: Float): String =
    if (v < 0f) "--" else String.format(java.util.Locale.US, "%.2f", v)

/** 速率格式化：不可读显示 `--`，自动进位到 B/KB/MB 每秒 */
private fun fmtRate(bps: Long): String {
    if (bps < 0L) return "--"
    return when {
        bps >= 1_048_576L -> String.format(java.util.Locale.US, "%.2f MB/s", bps / 1_048_576.0)
        bps >= 1_024L -> String.format(java.util.Locale.US, "%.1f KB/s", bps / 1_024.0)
        else -> "$bps B/s"
    }
}

/**
 * 总览页的帧率折线卡（2×2 网格的第四格）。
 *
 * 三个圆环回答「现在的百分比是多少」，帧率要回答的是「稳不稳」——
 * 掉帧是短时抖动，一个瞬时百分比会把「稳定 60 帧」与「在 60/30 之间来回跳」
 * 画成同一个数字，折线才看得出起伏。所以这一格不跟成圆环。
 *
 * 高度对齐 96dp 的环卡：表头 + 折线 42dp + 时间轴凑到同样的卡高，
 * 于是 2×2 的上下两行不需要任何额外等高处理就天然对齐。
 * 本卡宽只有 160dp（内容宽 132dp），所以折线不标峰值——
 * 峰值标注要占 14dp 竖向空间，会把折线压得太扁。
 */
@Composable
private fun FpsTrendCard(
    values: List<Float>,
    axisStartLabel: String,
    modifier: Modifier = Modifier,
) {
    val c = osColors()
    val current = values.lastOrNull() ?: 0f
    // 内容整体垂直居中：卡片被撑到 96dp 后，若不居中会在底部留下大块空白，
    // 与左右环卡的视觉重心对不上
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                text = "帧率",
                style = OsText.label,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "%.1f".format(current),
                style = OsText.metricSmall,
                color = ChartColors.fps,
                maxLines = 1,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = "FPS",
                style = OsText.micro,
                color = c.textSecondary,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        LineChart(
            values = values,
            // 上限参考 120 而非 60：面板刷新率可到 120Hz，若按 60 做基准，
            // 高刷下的 80~120 帧会被顶到图表外看不见
            maxValue = autoMax(values, 0f, 120f),
            color = ChartColors.fps,
            height = 42.dp,
            valueFormatter = { "%.0f".format(it) },
            showPeak = false,
            // 概览页的迷你折线只有 42dp 高，塞刻度会挤掉曲线本身；
            // 这一格的量程和当前值都由卡片头部（右上角的 FPS 数字）交代
            showYAxis = false,
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = axisStartLabel, style = OsText.micro, color = c.textTertiary)
            Text(text = "现在", style = OsText.micro, color = c.textTertiary)
        }
    }
}

/** 健康结论 */
private data class Health(val level: HealthLevel, val title: String, val detail: String)

/**
 * 由当前水位推导一句结论。
 *
 * 判据刻意只取「用户能采取行动」的几项：权限、SoC 温度、内存。
 * 把这些混在一起按最严重的一项定级，避免出现「一切正常」与「温度偏高」同时出现在一屏。
 *
 * **SoC 结温与电池温度必须是两个维度，不能合并。**
 * [socTempC] 来自 `thermal_zone*` 中按 `type` 语义挑出的 CPU/SoC 传感器
 * （真机实测：优先取 `cpuss-*`，其次 `cpu-N-M-K`；`aoss-*` 是常开子系统，
 * 不是 SoC 结温，已在 [CpuDataSource] 中排除），
 * [batteryTempC] 来自 `BatteryManager.EXTRA_TEMPERATURE`。两者的正常区间差着几十度：
 * SoC 结温 40~70 ℃ 属正常工作范围，要到 85 ℃ 附近才触发内核降频；
 * 电池温度则 45 ℃ 已接近告警线。曾经把二者写成 `cpu.tempC ?: batteryTempC`
 * 共用 50 ℃/42 ℃ 阈值，结果 CPU 结温常年 50~60 ℃ 被误判成「温度过高」，
 * 与同一屏里「电池健康 良好」自相矛盾。
 */
private fun evaluateHealth(
    privilegeAvailable: Boolean,
    socTempC: Float?,
    batteryTempC: Float?,
    memPercent: Float,
    availKb: Long,
    levelPercent: Int,
    charging: Boolean,
): Health {
    // 判据是「有没有提权通道」，不是「有没有 root」。
    // 用能力表而不是 rootAvailable，可以让门控逻辑在通道增减时保持一致。
    if (!privilegeAvailable) {
        return Health(
            level = HealthLevel.Warn,
            title = "未启用提权通道",
            detail = "CPU 占用、网络、磁盘 IO 等需要 root 身份的数据不可读；" +
                "可在设置 → 提权管理中完成 Root 授权",
        )
    }
    // SoC 结温阈值按内核 thermal 的降频尺度取：85 ℃ 接近关核/降频，72 ℃ 是持续满载的预警线
    if (socTempC != null && socTempC >= 85f) {
        return Health(
            level = HealthLevel.Danger,
            title = "CPU 结温 %.1f ℃".format(socTempC),
            detail = "内核可能正在降频，建议暂停高负载任务",
        )
    }
    if (memPercent >= 92f) {
        return Health(
            level = HealthLevel.Danger,
            title = "内存接近耗尽",
            detail = "可用仅 ${fmtGb(availKb)}，建议清理内存",
        )
    }
    // 电池温度阈值按锂电池的安全尺度取：45 ℃ 已接近厂商告警线
    if (batteryTempC != null && batteryTempC >= 45f) {
        return Health(
            level = HealthLevel.Danger,
            title = "电池温度 %.1f ℃".format(batteryTempC),
            detail = if (charging) "充电中发热，建议降低充电功率或暂停使用" else "建议暂停高负载任务",
        )
    }
    if (socTempC != null && socTempC >= 72f) {
        return Health(
            level = HealthLevel.Warn,
            title = "CPU 结温偏高 %.1f ℃".format(socTempC),
            detail = "可用内存 ${fmtGb(availKb)} · 占用 %.0f%%".format(memPercent),
        )
    }
    if (memPercent >= 82f) {
        return Health(
            level = HealthLevel.Warn,
            title = "内存占用偏高",
            detail = "已用 %.0f%%，可用 ${fmtGb(availKb)}".format(memPercent),
        )
    }
    val parts = mutableListOf<String>()
    parts += "无降频"
    parts += "内存可用 ${fmtGb(availKb)}"
    // 结论行里给出温度时必须带来源标签，否则同一个数字读者无法判断是 SoC 还是电池
    if (socTempC != null) parts += "CPU %.1f ℃".format(socTempC)
    if (batteryTempC != null) parts += "电池 %.1f ℃".format(batteryTempC)
    if (charging) parts += "充电中"
    if (levelPercent in 0..15) parts += "电量偏低"
    return Health(
        level = if (levelPercent in 0..15) HealthLevel.Warn else HealthLevel.Ok,
        title = if (levelPercent in 0..15) "电量偏低 $levelPercent%" else "系统正常",
        detail = parts.joinToString(" · "),
    )
}

/**
 * 剩余可用时长估算。
 *
 * 用「当前满电容量 × 电量百分比 ÷ 当前电流」推算，
 * 放电电流不可读时退回一句明确的状态文案，而不是显示一个假数字。
 */
internal fun batteryRemainingText(battery: BatteryInfo, charging: Boolean): String {
    if (charging) return "充电中"
    val level = battery.levelPercent
    if (level < 0) return "电量不可读"
    val ua = kotlin.math.abs(battery.currentNowUa)
    val full = battery.chargeFullUah
    if (full <= 0L || ua < 1000) return "电流不可读"
    val hours = full * (level / 100f) / ua
    return when {
        hours >= 1f -> "可用约 %.1f 小时".format(hours)
        hours > 0f -> "可用约 %.0f 分钟".format(hours * 60)
        else -> "电流不可读"
    }
}

/** 自动缩放上限：取数据峰值与参考值中的较大者，并向上取整到易读刻度 */
internal fun autoMax(values: List<Float>, reference: Float, floor: Float): Float {
    val peak = (values.maxOrNull() ?: 0f).coerceAtLeast(reference)
    val base = peak.coerceAtLeast(floor)
    val step = when {
        base <= 10f -> 1f
        base <= 100f -> 10f
        base <= 1000f -> 100f
        base <= 5000f -> 500f
        else -> 1000f
    }
    return kotlin.math.ceil(base / step) * step
}

internal fun fmtGb(kb: Long): String {
    if (kb <= 0L) return "-"
    val gb = kb / 1024f / 1024f
    return if (gb >= 1f) "%.2f GB".format(gb) else "%.0f MB".format(kb / 1024f)
}

internal fun gb(percent: Float, totalKb: Long): String {
    if (totalKb <= 0L) return "-"
    return fmtGb((totalKb * percent / 100f).toLong())
}
