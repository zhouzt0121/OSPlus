package com.osplus.tools.ui.screen

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.model.MetricSample
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.CoreFreqGrid
import com.osplus.tools.ui.components.Hairline
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.ProgressRow
import com.osplus.tools.ui.components.RingChart
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.StatTile
import com.osplus.tools.ui.components.pressable
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** 概览页可下钻的详情页 */
enum class OverviewDetail {
    Memory,
    Gpu,
    Cpu,
    Process,
    Power,
}

/**
 * 概览（唯一主页）。
 *
 * 版式参照性能仪表盘：每张卡片是一个子系统的「水位 + 关键数字」，
 * 点卡片进入对应详情页做细调。
 *
 * 卡片不再带外部小节标题——圆环中心已有「内存 / GPU / CPU / 电池」标签，
 * 外面再叠一行同名标题属于重复信息；下钻提示改由卡片右上角的细箭头承担。
 * CPU 卡片把「占用环 + 频率信息 + 各核心频率瓦片」收在一张卡内，
 * 进程摘要则移到「实时」页，主页只保留需要一眼看到的水位。
 */
@Composable
fun OverviewScreen(vm: DeviceViewModel, onOpen: (OverviewDetail) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    val cpu by vm.cpu.collectAsStateWithLifecycle()
    val gpu by vm.gpu.collectAsStateWithLifecycle()
    val mem by vm.mem.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val coreIndexes by vm.coreIndexes.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val c = osColors()
    val memClean by vm.memCleanState.collectAsStateWithLifecycle()

    // 清理结果只做一次回执，4 秒后自动收起，避免长期占据卡片
    LaunchedEffect(memClean) {
        if (memClean != null) {
            delay(4000)
            vm.clearMemCleanState()
        }
    }

    val samples: List<MetricSample> = history
    val latest = samples.lastOrNull()

    val memPercent = latest?.memUsedPercent ?: 0f
    val swapPercent = if (mem.swapTotalKb > 0) {
        mem.swapUsedKb.toFloat() / mem.swapTotalKb * 100f
    } else 0f
    val gpuFreqUtil = if (gpu.maxMhz > 0 && gpu.curMhz > 0) {
        gpu.curMhz.toFloat() / gpu.maxMhz
    } else 0f
    val charging = battery.status.contains("充电") || battery.plugged.contains("USB") ||
        battery.plugged.contains("AC") || battery.plugged.contains("无线")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!rootAvailable) {
            item { NoticeBanner("未获取到 Root 权限，频率控制与部分系统节点读取将不可用。") }
        }

        // ---------- 内存 ----------
        item {
            SectionCard(onClick = { onOpen(OverviewDetail.Memory) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingChart(
                        progress = memPercent / 100f,
                        color = ChartColors.mem,
                        label = "内存",
                        value = "%.0f".format(memPercent),
                        unit = "%",
                        size = 92.dp,
                        stroke = 13.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        ProgressRow(
                            label = "物理内存",
                            value = "${fmtGb(mem.usedKb)} / ${fmtGb(mem.totalKb)}",
                            fraction = memPercent / 100f,
                            color = ChartColors.mem,
                            trailing = {
                                CleanButton(
                                    contentDescription = "清理物理内存",
                                    onClick = { vm.cleanMemCaches() },
                                )
                            },
                        )
                        Spacer(Modifier.height(12.dp))
                        ProgressRow(
                            label = "交换分区",
                            value = if (mem.swapTotalKb > 0) {
                                "${fmtGb(mem.swapUsedKb)} / ${fmtGb(mem.swapTotalKb)}"
                            } else "未启用",
                            fraction = swapPercent / 100f,
                            color = c.primary,
                            trailing = {
                                CleanButton(
                                    contentDescription = "清理交换分区",
                                    onClick = { vm.cleanSwap() },
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Hairline()
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "可用 ${fmtGb(mem.availKb)}",
                        style = OsText.caption,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (mem.zramTotalKb > 0) {
                            "ZRAM ${fmtGb(mem.zramUsedKb)} / ${fmtGb(mem.zramTotalKb)}"
                        } else {
                            "缓存 ${fmtGb(mem.cachedKb)}"
                        },
                        style = OsText.caption,
                        color = c.textTertiary,
                    )
                }
                memClean?.let { r ->
                    Spacer(Modifier.height(10.dp))
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
        }

        // ---------- GPU ----------
        item {
            SectionCard(onClick = { onOpen(OverviewDetail.Gpu) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingChart(
                        // 环的填充与中心数值都表达 GPU 负载；负载不可读时退回频率水位
                        progress = if (gpu.loadPercent >= 0) {
                            gpu.loadPercent / 100f
                        } else {
                            gpuFreqUtil
                        },
                        color = ChartColors.gpu,
                        label = "GPU",
                        value = if (gpu.loadPercent >= 0) "${gpu.loadPercent}" else "-",
                        unit = "%",
                        size = 92.dp,
                        stroke = 13.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = if (gpu.curMhz > 0) "${gpu.curMhz}" else "-",
                                style = OsText.metric,
                                color = c.textPrimary,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "MHz",
                                style = OsText.caption,
                                color = c.textSecondary,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "当前频率",
                            style = OsText.caption,
                            color = c.textSecondary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = gpu.name.ifBlank { "读取中…" },
                            style = OsText.micro,
                            color = c.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (gpu.maxMhz > 0) {
                                "可用 ${gpu.availableFreqs.size} 档 · ${gpu.minMhz}~${gpu.maxMhz} MHz"
                            } else {
                                "频率范围不可读"
                            },
                            style = OsText.micro,
                            color = c.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "调速器 ${gpu.governor.ifBlank { "-" }}",
                            style = OsText.micro,
                            color = c.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // ---------- CPU（占用环 + 关键频率 + 各核心频率瓦片，合并为一张卡）----------
        item {
            SectionCard(onClick = { onOpen(OverviewDetail.Cpu) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingChart(
                        progress = (latest?.cpuLoad ?: 0f) / 100f,
                        color = ChartColors.cpu,
                        label = "CPU",
                        value = "%.0f".format(latest?.cpuLoad ?: 0f),
                        unit = "%",
                        size = 92.dp,
                        stroke = 13.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = cpu.soc.ifBlank { "读取中…" },
                            style = OsText.valueStrong,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "${cpu.coreCount} 核心 · ${cpu.clusters.size} 簇",
                            style = OsText.caption,
                            color = c.textSecondary,
                        )
                        Spacer(Modifier.height(6.dp))
                        InfoRow(
                            "温度",
                            latest?.batteryTempC?.let { "%.1f ℃".format(it) }
                                ?: cpu.tempC?.let { "%.1f ℃".format(it) }
                                ?: "-",
                        )
                        InfoRow(
                            "最高频率",
                            cpu.clusters.maxOfOrNull { it.maxKhz }?.let { "${it / 1000} MHz" } ?: "-",
                        )
                        InfoRow(
                            "当前频率",
                            cpu.clusters.maxOfOrNull { it.curKhz }?.let { "${it / 1000} MHz" } ?: "-",
                            emphasis = true,
                        )
                    }
                }
                if (coreIndexes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Hairline()
                    Spacer(Modifier.height(11.dp))
                    CoreFreqGrid(
                        coreIndexes = coreIndexes,
                        loads = latest?.coreLoads ?: emptyList(),
                        loadsHistory = coreIndexes.indices.map { i ->
                            samples.map { it.coreLoads.getOrElse(i) { 0f } }
                        },
                        freqsKhz = latest?.coreFreqs ?: emptyList(),
                        minKhz = cpu.cores.map { it.minKhz },
                        maxKhz = cpu.cores.map { it.maxKhz },
                    )
                }
            }
        }

        // ---------- 电源 ----------
        item {
            SectionCard(onClick = { onOpen(OverviewDetail.Power) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingChart(
                        progress = if (battery.levelPercent >= 0) battery.levelPercent / 100f else 0f,
                        color = if (charging) ChartColors.gpu else c.primary,
                        label = "电池",
                        value = if (battery.levelPercent >= 0) "${battery.levelPercent}" else "-",
                        unit = "%",
                        size = 92.dp,
                        stroke = 13.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "%.2f".format((latest?.powerMw ?: 0f) / 1000f),
                                style = OsText.metric,
                                color = c.textPrimary,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "W",
                                style = OsText.caption,
                                color = c.textSecondary,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "整机功耗",
                            style = OsText.caption,
                            color = c.textSecondary,
                        )
                        Spacer(Modifier.height(6.dp))
                        InfoRow(
                            "状态",
                            listOf(battery.status, battery.plugged)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                                .ifBlank { "-" },
                        )
                        InfoRow(
                            "温度",
                            battery.tempC?.let { "%.1f ℃".format(it) }
                                ?: latest?.batteryTempC?.let { "%.1f ℃".format(it) }
                                ?: "-",
                        )
                        InfoRow(
                            "电压",
                            if (battery.voltageMv > 0) "${battery.voltageMv} mV" else "-",
                        )
                    }
                }
            }
        }

        // ---------- 设备概况 ----------
        item {
            SectionCard {
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
                Hairline(verticalPadding = 9.dp)
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

/**
 * 进度条右侧的圆形清理按钮。
 *
 * 做成小尺寸圆形而非文字按钮：它挂在某一行的数值后面，
 * 文字按钮会把「物理内存 / 交换分区」两行的行宽顶开、破坏对齐。
 */
@Composable
private fun CleanButton(
    contentDescription: String,
    onClick: () -> Unit,
) {
    val c = osColors()
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(c.cardAlt)
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.CleaningServices,
            contentDescription = contentDescription,
            tint = c.textSecondary,
            modifier = Modifier.size(14.dp),
        )
    }
}
