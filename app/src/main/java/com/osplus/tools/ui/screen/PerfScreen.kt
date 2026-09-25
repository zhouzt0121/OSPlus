package com.osplus.tools.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.model.MetricSample
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.CoreBarsChart
import com.osplus.tools.ui.components.Hairline
import com.osplus.tools.ui.components.MetricChartCard
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SegmentedTabs
import com.osplus.tools.ui.components.axisSpanLabel
import com.osplus.tools.ui.components.downsample
import com.osplus.tools.ui.components.pressable
import com.osplus.tools.ui.components.spanText
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/** 趋势观察窗口的候选长度（秒），与采样间隔 1 秒一一对应 */
private val WindowLabels = listOf("5秒", "1分", "5分", "30分")
private val WindowSeconds = listOf(5, 60, 300, 1800)

/**
 * 性能（一级页）：进程占用 + 累积趋势 + 每核状态 + 调优入口。
 *
 * 由原「实时」页与原「CPU / GPU / 内存」详情页合并而来——它们回答的是同一个问题
 * 「现在谁在吃资源、能不能调」，拆成四个入口只会让用户在页面之间来回跳。
 *
 * 观察窗口改由分段控件选择：原来趋势窗口只能随运行时间被动增长，
 * 想看瞬时抖动只能等重启，想看长趋势又要等半小时。窗口一旦可选，
 * 同一张图既承担「秒级抖动」也承担「半小时走势」，不再需要第二个页面。
 *
 * 进程摘要保持在最上方：它逐秒变化，是这一页最常被扫视的内容。
 */
@Composable
fun PerfScreen(vm: DeviceViewModel, onOpen: (OverviewDetail) -> Unit) {
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
    var windowIndex by rememberSaveable { mutableIntStateOf(2) }
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
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!rootAvailable) {
            item { NoticeBanner("未获取到 Root 权限，CPU 占用、GPU 与功耗等节点将不可读。") }
        }

        // ---------- 进程摘要（置顶：点击进入进程管理）----------
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

        // ---------- 观察窗口选择器（只作用于紧随其后的趋势卡）----------
        item {
            SegmentedTabs(
                tabs = WindowLabels,
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
                    values = downsample(samples.map { it.cpuLoad }, slots),
                    maxValue = 100f,
                    color = ChartColors.cpu,
                    unit = "%",
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
                    maxValue = autoMax(samples.map { it.fps }, 0f, 60f),
                    color = ChartColors.fps,
                    unit = "FPS",
                    subtitle = "需在「帧率」页开启记录",
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
                        CoreBarsChart(
                            coreIndexes = coreIndexes,
                            loads = latest?.coreLoads ?: emptyList(),
                            freqs = latest?.coreFreqs ?: emptyList(),
                        )
                    }
                }
            }
        }

        // ---------- 调优入口（低频高危操作下沉为二级页）----------
        item {
            SectionCard {
                TuningRow(
                    label = "CPU 频率与调速器",
                    summary = cpu.governors.firstOrNull { it.isNotBlank() } ?: "-",
                    onClick = { onOpen(OverviewDetail.Cpu) },
                )
                Hairline(verticalPadding = 2.dp)
                TuningRow(
                    label = "GPU 频率与调速器",
                    summary = gpu.governor.ifBlank { "-" },
                    onClick = { onOpen(OverviewDetail.Gpu) },
                )
                Hairline(verticalPadding = 2.dp)
                TuningRow(
                    label = "内存与 ZRAM",
                    summary = if (mem.zramTotalKb > 0) fmtGb(mem.zramTotalKb) else "未启用",
                    onClick = { onOpen(OverviewDetail.Memory) },
                )
            }
        }
    }
}

/**
 * 调优入口行：左标题、右当前状态摘要。
 *
 * 一级页只负责「告知当前是什么」和「能点进去」，
 * 具体有哪些可选值、写哪个节点，留给二级页——这样一级页不会因机型差异而长度不一。
 */
@Composable
private fun TuningRow(
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
        Text(
            text = "›",
            style = OsText.caption,
            color = c.textTertiary,
        )
    }
}

/** 进程摘要行：图标 + 名称 + CPU 占用 */
@Composable
private fun ProcessSummaryRow(
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
