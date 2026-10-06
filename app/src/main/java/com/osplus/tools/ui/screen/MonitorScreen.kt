package com.osplus.tools.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.model.MetricSample
import com.osplus.tools.ui.components.ActionButton
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.CoreBarsChart
import com.osplus.tools.ui.components.Hairline
import com.osplus.tools.ui.components.LiquidNavTabs
import com.osplus.tools.ui.components.MetricChartCard
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SwitchRow
import com.osplus.tools.ui.components.axisSpanLabel
import com.osplus.tools.ui.components.bottomBarContentPadding
import com.osplus.tools.ui.components.downsample
import com.osplus.tools.ui.components.frameRateMax
import com.osplus.tools.ui.components.frameRateTicks
import com.osplus.tools.ui.components.panelRefreshHz
import com.osplus.tools.ui.components.pressable
import com.osplus.tools.ui.components.spanText
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import com.osplus.tools.core.MonitorKind
import com.osplus.tools.core.MonitorState
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/** 趋势观察窗口的候选长度（秒），与采样间隔 1 秒一一对应 */
private val WindowLabels = listOf("1分", "5分", "15分", "30分")
private val WindowSeconds = listOf(60, 300, 900, 1800)

/**
 * 监测（一级页）：**只读**的实时观察。
 *
 * 职责边界（2.9.0 信息架构重构时划定）：这一页回答「现在发生了什么」，
 * 因此**不放任何会改变设备行为的控件**。所有调参入口已迁到「调优」页。
 *
 * 为什么必须分家：旧「性能」页把实时趋势与调速器入口混在一屏，
 * 「看数据」和「改参数」是两种完全不同的意图——前者是安全的、频繁的，
 * 后者是高风险的、低频的。混在一起时用户很容易在只想看看的时候
 * 手指滑到一个会立刻写内核节点的控件上。
 *
 * 顺序按「从整体到细节」：悬浮监视器开关（全局工具）→ 观察窗口 →
 * 总览级趋势 → 各核心 → 进程列表。
 */
@Composable
fun MonitorScreen(
    vm: DeviceViewModel,
    onOpen: (OverviewDetail) -> Unit,
    onOpenOverlayManager: () -> Unit = {},
) {
    val panelHz = panelRefreshHz()
    val history by vm.history.collectAsStateWithLifecycle()
    val cpu by vm.cpu.collectAsStateWithLifecycle()
    val gpu by vm.gpu.collectAsStateWithLifecycle()
    val mem by vm.mem.collectAsStateWithLifecycle()
    val coreIndexes by vm.coreIndexes.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val processes by vm.processes.collectAsStateWithLifecycle()
    val appIcons by vm.appIcons.collectAsStateWithLifecycle()
    val c = osColors()

    // 默认 5 分：短到能看出抖动，长到不至于只剩噪声
    var windowIndex by rememberSaveable { mutableIntStateOf(1) }
    val windowSeconds = WindowSeconds[windowIndex]

    val samples: List<MetricSample> = history.takeLast(windowSeconds)
    val latest = samples.lastOrNull()
    val topProcesses = processes.take(5)

    // 采样间隔固定 1 秒，因此样本数即窗口秒数；绘制时统一降采样到固定槽位数，
    // 保证长窗口下线密度稳定、不糊成一片。
    val slots = 60
    val axisStart = axisSpanLabel(samples.size)

    // 进入本页时拉取一次进程快照，之后每 5 秒刷新
    LaunchedEffect(Unit) {
        vm.refreshProcesses()
        while (true) {
            delay(5000)
            vm.refreshProcesses()
        }
    }
    LaunchedEffect(topProcesses) {
        vm.loadAppIcons(topProcesses.mapNotNull { it.packageName })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!rootAvailable) {
            item {
                NoticeBanner(
                    "未获取到 Root 权限，CPU 占用、GPU 与功耗等节点将不可读。" +
                        "部分指标会显示为「不可读」。",
                )
            }
        }

        // ---------- 悬浮监视器快捷开关 ----------
        // 悬浮窗是跨页面工具，不是一组独立页面。把它放在监测页而不是塞进设置里：
        // 「看数据」和「把数据贴到屏幕上看」是同一个意图的两种强度，
        // 用户开着监测页时最可能顺手把浮窗点开。
        item {
            OverlayQuickSwitch(onClick = onOpenOverlayManager)
        }

        // ---------- 观察窗口选择器（只作用于紧随其后的趋势卡）----------
        item {
            LiquidNavTabs(
                items = WindowLabels,
                selectedIndex = windowIndex,
                onSelect = { windowIndex = it },
            )
        }

        // ---------- 趋势 ----------
        item {
            SectionCard {
                Text(
                    text = if (samples.isEmpty()) {
                        "正在采样…"
                    } else {
                        "窗口 ${spanText(samples.size)} · 每秒 1 次采样"
                    },
                    style = OsText.micro,
                    color = c.textTertiary,
                )
                Spacer(Modifier.height(10.dp))
                MetricChartCard(
                    title = "CPU 总占用",
                    values = downsample(samples.map { it.cpuLoad.coerceAtLeast(0f) }, slots),
                    maxValue = 100f,
                    color = ChartColors.cpu,
                    unit = "%",
                    subtitle = cpu.clusters.maxOfOrNull { it.curKhz }
                        ?.let { "最高簇 ${it / 1000} MHz" },
                    axisStartLabel = axisStart,
                )
                Hairline(verticalPadding = 12.dp)
                MetricChartCard(
                    title = "内存占用",
                    values = downsample(samples.map { it.memUsedPercent }, slots),
                    maxValue = 100f,
                    color = ChartColors.mem,
                    unit = "%",
                    subtitle = latest?.let { "已用 ${gb(it.memUsedPercent, mem.totalKb)} / 共 ${fmtGb(mem.totalKb)}" },
                    axisStartLabel = axisStart,
                )
                Hairline(verticalPadding = 12.dp)
                MetricChartCard(
                    title = "GPU 频率",
                    values = downsample(
                        samples.map { if (it.gpuMhz > 0) it.gpuMhz.toFloat() else 0f },
                        slots,
                    ),
                    maxValue = autoMax(samples.map { it.gpuMhz.toFloat() }, gpu.maxMhz.toFloat(), 800f),
                    color = ChartColors.gpu,
                    unit = "MHz",
                    subtitle = latest?.let {
                        if (it.gpuLoad >= 0) "负载 ${it.gpuLoad}%" else "负载不可读"
                    },
                    axisStartLabel = axisStart,
                )
                Hairline(verticalPadding = 12.dp)
                MetricChartCard(
                    title = "整机功耗",
                    values = downsample(samples.map { it.powerMw }, slots),
                    maxValue = autoMax(samples.map { it.powerMw }, 0f, 500f),
                    color = ChartColors.power,
                    unit = "mW",
                    subtitle = latest?.let { "≈ ${"%.2f".format(it.powerMw / 1000f)} W" },
                    axisStartLabel = axisStart,
                )
                Hairline(verticalPadding = 12.dp)
                MetricChartCard(
                    title = "实时帧率",
                    values = downsample(samples.map { it.fps }, slots),
                    // 纵轴跟着峰值走 + 面板上限作参照，见 frameRateMax
                    maxValue = frameRateMax(
                        observedPeak = samples.maxOfOrNull { it.fps } ?: 0f,
                        panelMax = panelHz,
                    ),
                    color = ChartColors.fps,
                    unit = "FPS",
                    subtitle = "需在「记录」页开启记录",
                    yTicks = { frameRateTicks(it) },
                    axisStartLabel = axisStart,
                )
                Hairline(verticalPadding = 12.dp)
                MetricChartCard(
                    title = "电池温度",
                    values = downsample(samples.map { it.batteryTempC ?: 0f }, slots),
                    maxValue = autoMax(samples.map { it.batteryTempC ?: 0f }, 0f, 50f),
                    color = ChartColors.temp,
                    unit = "℃",
                    valueFormatter = { "%.1f".format(it) },
                    axisStartLabel = axisStart,
                )
            }
        }

        if (coreIndexes.isNotEmpty()) {
            item {
                SectionCard {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        CardSectionHeader(
                            title = "各核心占用",
                            action = "详情 ›",
                            onAction = { onOpen(OverviewDetail.Cpu) },
                        )
                        CoreBarsChart(
                            coreIndexes = coreIndexes,
                            loads = latest?.coreLoads ?: emptyList(),
                            freqs = latest?.coreFreqs ?: emptyList(),
                        )
                    }
                }
            }
        }

        // ---------- 进程占用 ----------
        item {
            SectionCard(onClick = { onOpen(OverviewDetail.Process) }) {
                if (topProcesses.isEmpty()) {
                    Text(
                        text = if (rootAvailable) "正在读取进程…" else "需要 Root 权限才能读取进程",
                        style = OsText.caption,
                        color = c.textTertiary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "进程占用",
                            style = OsText.value,
                            color = c.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "全部 ${processes.size} ›",
                            style = OsText.caption,
                            color = c.primary,
                        )
                    }
                    Spacer(Modifier.height(9.dp))
                    topProcesses.forEach { p ->
                        ProcessSummaryRow(
                            name = p.name,
                            percent = p.cpuPercent,
                            icon = p.packageName?.let { appIcons[it] },
                        )
                    }
                }
            }
        }

        // ---------- 下钻详情入口 ----------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 2.dp)) {
                    DetailRow("GPU 详情", gpu.name.ifBlank { "-" }) { onOpen(OverviewDetail.Gpu) }
                    Hairline(verticalPadding = 2.dp)
                    DetailRow("内存详情", fmtGb(mem.totalKb)) { onOpen(OverviewDetail.Memory) }
                    Hairline(verticalPadding = 2.dp)
                    DetailRow("系统负载", "负载 / 网络 / 磁盘 / IO 压力") {
                        onOpen(OverviewDetail.Cpu)
                    }
                }
            }
        }
    }
}

/**
 * 悬浮监视器入口卡。
 *
 * 这里**只做一件事：跳转到悬浮窗管理器**。
 *
 * 为什么把开关撤掉：本页是「只读观察」页，而开关悬浮窗是「改变设备状态」。
 * 更重要的是，六个监视器各自的开关、外观、位置全在悬浮窗管理器里，
 * 在这里再摆一份 6 格迷你开关等于把同一个真值暴露在两个地方——
 * 用户在监测页点开一个、又去管理器里关掉，两边状态容易对不上。
 * 现在整张卡就是一个按钮，点一下去唯一的那处配置页。
 */
@Composable
private fun OverlayQuickSwitch(onClick: () -> Unit) {
    val c = osColors()
    val context = androidx.compose.ui.platform.LocalContext.current
    val enabled by MonitorState.enabled.collectAsStateWithLifecycle()
    val overlayGranted = remember {
        android.provider.Settings.canDrawOverlays(context)
    }

    val status = buildString {
        append(if (enabled.isEmpty()) "未开启" else "已开启 ${enabled.size} / ${MonitorKind.entries.size} 个窗口")
        append(" · ")
        append(if (overlayGranted) "权限正常" else "缺少悬浮窗权限")
    }

    ActionButton(
        text = "悬浮监视器 · $status  ›",
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 卡片内的小节标题行：左侧标题 + 右侧动作 */
@Composable
private fun CardSectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = osColors()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = OsText.value,
            color = c.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                text = action,
                style = OsText.caption,
                color = c.primary,
                modifier = if (onAction != null) {
                    Modifier.clip(RoundedCornerShape(6.dp)).pressable(onAction)
                } else {
                    Modifier
                },
            )
        }
    }
    Spacer(Modifier.height(10.dp))
}

/** 下钻详情行：左标题、中摘要、右箭头 */
@Composable
private fun DetailRow(
    label: String,
    summary: String,
    onClick: () -> Unit,
) {
    val c = osColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .pressable(onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = OsText.value,
            color = c.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = summary,
            style = OsText.caption,
            color = c.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Text(text = "›", style = OsText.caption, color = c.textTertiary)
    }
}

/** 进程摘要行：图标 + 名称 + CPU 占用 */
@Composable
internal fun ProcessSummaryRow(
    name: String,
    percent: Float,
    icon: ImageBitmap?,
) {
    val c = osColors()
    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)),
                )
            } else {
                Box(Modifier.fillMaxSize().background(c.cardAlt), contentAlignment = Alignment.Center) {
                    Text(
                        text = name.take(1).uppercase(),
                        style = OsText.micro,
                        color = c.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = name,
            style = OsText.value,
            color = c.textPrimary,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "%.1f%%".format(percent),
            style = OsText.caption,
            color = c.textSecondary,
            maxLines = 1,
        )
    }
}
