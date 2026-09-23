package com.osplus.tools.ui.screen

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.ChargeController
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.MetricChartCard
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SegmentedTabs
import com.osplus.tools.ui.components.SwitchRow
import com.osplus.tools.ui.components.UsageBar
import com.osplus.tools.ui.components.axisSpanLabel
import com.osplus.tools.ui.components.downsample
import com.osplus.tools.ui.components.spanText
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PowerDetailScreen(vm: DeviceViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember { listOf("耗电统计", "充电统计", "充电控制") }

    Column(Modifier.fillMaxSize()) {
        SegmentedTabs(
            tabs = tabs,
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        when (tab) {
            0 -> PowerUsageTab(vm)
            1 -> ChargeStatsTab(vm)
            else -> ChargeControlTab(vm)
        }
    }
}

@Composable
private fun PowerUsageTab(vm: DeviceViewModel) {
    val context = LocalContext.current
    val entries by vm.powerUsage.collectAsStateWithLifecycle()
    val hasAccess by vm.usageAccess.collectAsStateWithLifecycle()
    val icons by vm.appIcons.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshPowerUsage() }

    // 统计结果变化后按需补齐缺失的应用图标
    LaunchedEffect(entries) {
        vm.loadAppIcons(entries.map { it.packageName })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!hasAccess) {
            item {
                Column {
                    NoticeBanner("需要「使用情况访问权限」才能统计各应用耗电")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("前往授权") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "近 24 小时估算耗电占比",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { vm.refreshPowerUsage() }) { Text("刷新") }
            }
        }
        if (entries.isEmpty()) {
            item {
                SectionCard {
                    Text(
                        text = if (hasAccess) "暂无统计数据，请稍后刷新" else "授权后可查看统计",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
        items(entries.take(60)) { entry ->
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val icon = icons[entry.packageName]
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(9.dp)),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            text = entry.label,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "%.1f%%".format(entry.percent),
                            style = MiuixTheme.textStyles.title4,
                            color = ChartColors.power,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    UsageBar(
                        fraction = entry.percent / 100f,
                        color = ChartColors.power,
                        barHeight = 6.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "前台 ${formatDuration(entry.foregroundMs)} · 可见 ${
                            formatDuration(entry.backgroundMs)
                        } · ${entry.packageName}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChargeStatsTab(vm: DeviceViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    // 趋势窗口随运行时间累积（1 秒 1 条），绘制前按固定槽位降采样
    val trendSlots = 60
    val trendAxis = axisSpanLabel(history.size)
    val trendSpan = spanText(history.size)
    val currentMa = battery.currentNowUa / 1000f
    val capacityMah = if (battery.chargeFullUah > 0) battery.chargeFullUah / 1000f else -1f

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(title = "功耗 · 已累积 $trendSpan") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    MetricChartCard(
                        title = "整机功耗",
                        values = downsample(history.map { it.powerMw }, trendSlots),
                        maxValue = autoMax(history.map { it.powerMw }, 0f, 500f),
                        color = ChartColors.power,
                        unit = "mW",
                        slots = trendSlots,
                        axisStartLabel = trendAxis,
                    )
                    Spacer(Modifier.height(10.dp))
                    MetricChartCard(
                        title = "电池温度",
                        values = downsample(history.map { it.batteryTempC ?: 0f }, trendSlots),
                        maxValue = autoMax(history.map { it.batteryTempC ?: 0f }, 0f, 50f),
                        color = ChartColors.temp,
                        unit = "℃",
                        valueFormatter = { "%.1f".format(it) },
                        slots = trendSlots,
                        axisStartLabel = trendAxis,
                    )
                }
            }
        }
        item {
            SectionCard(title = "电池信息") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    InfoRow("电量", "${battery.levelPercent}%", emphasis = true)
                    InfoRow("状态", battery.status)
                    InfoRow("供电来源", battery.plugged)
                    InfoRow("健康度", battery.health)
                    InfoRow("电压", "${battery.voltageMv} mV")
                    InfoRow("电流", "%.0f mA".format(currentMa))
                    InfoRow("功耗", "%.2f W".format(battery.voltageMv * currentMa / 1000f))
                    InfoRow("温度", battery.tempC?.let { "%.1f ℃".format(it) } ?: "-")
                    InfoRow("技术", battery.technology.ifBlank { "-" })
                    InfoRow("设计容量", if (battery.chargeFullDesignUah > 0) "${battery.chargeFullDesignUah / 1000} mAh" else "-")
                    InfoRow("当前满电容量", if (capacityMah > 0) "%.0f mAh".format(capacityMah) else "-")
                    InfoRow("循环次数", if (battery.cycleCount >= 0) "${battery.cycleCount}" else "-")
                }
            }
        }
    }
}

@Composable
private fun ChargeControlTab(vm: DeviceViewModel) {
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var nodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentLimit by remember { mutableStateOf(3000f) }

    LaunchedEffect(rootAvailable) {
        nodes = ChargeController.availableNodes()
        ChargeController.readChargeCurrentLimit()?.let { currentLimit = it / 1000f }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!rootAvailable) {
            item { NoticeBanner("充电控制需要 Root 权限；未授权时仅可查看状态。") }
        }
        item {
            SectionCard(title = "充电开关") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    SwitchRow(
                        label = "允许充电",
                        summary = if (nodes.any { it.contains("charging") || it.contains("suspend") }) {
                            "通过内核节点直接控制充电通断"
                        } else {
                            "当前内核未暴露充电开关节点，无法控制"
                        },
                        checked = battery.chargingEnabled ?: true,
                        enabled = rootAvailable && nodes.any {
                            it.contains("charging") || it.contains("suspend")
                        },
                        onCheckedChange = { vm.setChargingEnabled(it) },
                    )
                }
            }
        }
        item {
            SectionCard(title = "充电电流上限") {
                Column(Modifier.padding(vertical = 5.dp)) {
                    Text(
                        text = "%.0f mA".format(currentLimit),
                        style = MiuixTheme.textStyles.title4,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Slider(
                        value = currentLimit,
                        onValueChange = { currentLimit = it },
                        valueRange = 100f..6000f,
                        onValueChangeFinished = {
                            vm.setChargeCurrentLimit((currentLimit * 1000).toInt())
                        },
                        enabled = rootAvailable && nodes.any {
                            it.contains("current") || it.contains("limit")
                        },
                    )
                    Text(
                        text = "范围 100 ~ 6000 mA，仅在支持的内核上生效",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }
        item {
            SectionCard(title = "内核节点探测") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    if (nodes.isEmpty()) {
                        Text(
                            text = "未检测到可用充电控制节点",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    } else {
                        nodes.forEach { InfoRow(label = it, value = "可用") }
                    }
                }
            }
        }
        item {
            Button(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) { Text("打开系统电池设置") }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0s"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return buildString {
        if (h > 0) append("${h}h ")
        if (m > 0) append("${m}m ")
        append("${s}s")
    }
}
