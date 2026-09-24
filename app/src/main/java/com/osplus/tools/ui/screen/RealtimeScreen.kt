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
import com.osplus.tools.ui.components.axisSpanLabel
import com.osplus.tools.ui.components.downsample
import com.osplus.tools.ui.components.spanText
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * 实时（一级页）：把 1 秒采样的全部指标集中展示。
 *
 * 趋势窗口随运行时间累积（上限 30 分钟），不再固定 5 秒；
 * 绘制时按固定槽位降采样，因此窗口变长不会让曲线糊成一片。
 * 概览页只负责「看水位 + 下钻」，逐项曲线集中放在这里，
 * 避免概览被 6 张图拖成一条很长的滚动列表。
 * 进程摘要也从概览搬到了这里——它本身就是逐秒变化的动态数据，
 * 和「实时」的语义一致；点卡片仍可进入完整的进程管理页。
 */
@Composable
fun RealtimeScreen(vm: DeviceViewModel, onOpen: (OverviewDetail) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    val cpu by vm.cpu.collectAsStateWithLifecycle()
    val gpu by vm.gpu.collectAsStateWithLifecycle()
    val mem by vm.mem.collectAsStateWithLifecycle()
    val coreIndexes by vm.coreIndexes.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val processes by vm.processes.collectAsStateWithLifecycle()
    val appIcons by vm.appIcons.collectAsStateWithLifecycle()
    val c = osColors()

    val samples: List<MetricSample> = history
    val latest = samples.lastOrNull()
    val topProcesses = processes.take(6)

    // 采样间隔固定 1 秒，因此样本数即窗口秒数。
    // 窗口会随运行时间不断变长（上限见 DeviceViewModel.HISTORY_MAX_SAMPLES），
    // 绘制时统一降采样到固定槽位数，保证柱/线密度稳定、长窗口也不卡。
    val spanSeconds = samples.size
    val slots = 60
    val axisStart = axisSpanLabel(spanSeconds)

    // 进入本页时拉取一次进程快照，之后每 5 秒刷新（与完整快照同频）
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
                    topProcesses.forEach { p ->
                        ProcessSummaryRow(
                            name = p.name,
                            percent = p.cpuPercent,
                            icon = p.packageName?.let { appIcons[it] },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Hairline()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "共 ${processes.size} 个进程 · 按 CPU 占用排序",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }

        item {
            SectionCard {
                // 顶栏移除后，采样跨度改由卡片自身说明
                Text(
                    text = if (samples.isEmpty()) {
                        "正在采样…"
                    } else {
                        "已累积 ${spanText(spanSeconds)} · 每秒 1 次采样"
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
