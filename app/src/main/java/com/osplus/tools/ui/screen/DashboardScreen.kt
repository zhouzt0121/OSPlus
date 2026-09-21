package com.osplus.tools.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.model.MetricSample
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.CoreBarsChart
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.MetricChartCard
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.StatTile
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 概览：所有指标的 5 秒实时柱状图 */
@Composable
fun DashboardScreen(vm: DeviceViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val cpu by vm.cpu.collectAsStateWithLifecycle()
    val gpu by vm.gpu.collectAsStateWithLifecycle()
    val mem by vm.mem.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val coreIndexes by vm.coreIndexes.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()

    val samples: List<MetricSample> = history
    val latest = samples.lastOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 2.dp,
            bottom = 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!rootAvailable) {
            item { NoticeBanner("未获取到 Root 权限，频率控制与部分系统节点读取将不可用。") }
        }

        item {
            SectionCard(title = "实时采样 · 最近 5 秒（每秒 1 次）") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    MetricChartCard(
                        title = "CPU 总占用",
                        values = samples.map { it.cpuLoad },
                        maxValue = 100f,
                        color = ChartColors.cpu,
                        unit = "%",
                    )
                    Spacer(Modifier.height(10.dp))
                    MetricChartCard(
                        title = "内存占用",
                        values = samples.map { it.memUsedPercent },
                        maxValue = 100f,
                        color = ChartColors.mem,
                        unit = "%",
                        subtitle = latest?.let {
                            "已用 ${gb(it.memUsedPercent, mem.totalKb)} / 共 ${fmtGb(mem.totalKb)}"
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    MetricChartCard(
                        title = "GPU 频率",
                        values = samples.map { if (it.gpuMhz > 0) it.gpuMhz.toFloat() else 0f },
                        maxValue = autoMax(samples.map { it.gpuMhz.toFloat() }, gpu.maxMhz.toFloat(), 800f),
                        color = ChartColors.gpu,
                        unit = "MHz",
                        subtitle = latest?.let {
                            if (it.gpuLoad >= 0) "负载 ${it.gpuLoad}%" else "负载不可读"
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    MetricChartCard(
                        title = "整机功耗",
                        values = samples.map { it.powerMw },
                        maxValue = autoMax(samples.map { it.powerMw }, 0f, 500f),
                        color = ChartColors.power,
                        unit = "mW",
                        valueFormatter = { "%.0f".format(it) },
                        subtitle = latest?.let {
                            "≈ ${"%.2f".format(it.powerMw / 1000f)} W"
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    MetricChartCard(
                        title = "实时帧率",
                        values = samples.map { it.fps },
                        maxValue = autoMax(samples.map { it.fps }, 0f, 60f),
                        color = ChartColors.fps,
                        unit = "FPS",
                        subtitle = "需在「进程 · 帧率」中开启记录",
                    )
                    Spacer(Modifier.height(10.dp))
                    MetricChartCard(
                        title = "电池温度",
                        values = samples.map { it.batteryTempC ?: 0f },
                        maxValue = autoMax(samples.map { it.batteryTempC ?: 0f }, 0f, 50f),
                        color = ChartColors.temp,
                        unit = "℃",
                        valueFormatter = { "%.1f".format(it) },
                    )
                }
            }
        }

        item {
            SectionCard(title = "CPU 核心占用（每核一柱）") {
                Column(Modifier.padding(vertical = 5.dp)) {
                    CoreBarsChart(
                        coreIndexes = coreIndexes,
                        loads = latest?.coreLoads ?: emptyList(),
                        freqs = latest?.coreFreqs ?: emptyList(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "柱下数字为核心编号，柱顶为当前频率(MHz)与占用率",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "设备概况") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        StatTile(
                            label = "CPU",
                            value = cpu.soc.ifBlank { "读取中" },
                            hint = "${cpu.coreCount} 核心 · ${cpu.clusters.size} 簇",
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "CPU 温度",
                            value = cpu.tempC?.let { "%.1f".format(it) } ?: "-",
                            unit = "℃",
                            accent = ChartColors.temp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        StatTile(
                            label = "GPU",
                            value = if (gpu.curMhz > 0) gpu.curMhz.toString() else "-",
                            unit = "MHz",
                            accent = ChartColors.gpu,
                            hint = gpu.name,
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "电池",
                            value = if (battery.levelPercent >= 0) battery.levelPercent.toString() else "-",
                            unit = "%",
                            hint = "${battery.status} · ${battery.plugged}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    InfoRow("内存", "${fmtGb(mem.usedKb)} / ${fmtGb(mem.totalKb)}")
                    InfoRow("SWAP", "${fmtGb(mem.swapUsedKb)} / ${fmtGb(mem.swapTotalKb)}")
                    InfoRow(
                        "ZRAM",
                        if (mem.zramTotalKb > 0) {
                            "${fmtGb(mem.zramUsedKb)} / ${fmtGb(mem.zramTotalKb)}（${mem.zramDevices.size} 个设备）"
                        } else "未启用",
                    )
                    InfoRow("ABI", cpu.abi.ifBlank { "-" })
                    InfoRow("电池健康", battery.health)
                    InfoRow("循环次数", if (battery.cycleCount >= 0) "${battery.cycleCount} 次" else "-")
                }
            }
        }
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
