package com.osplus.tools.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Thermostat
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.PowerRecorder
import com.osplus.tools.model.AppDrainEntry
import com.osplus.tools.model.PowerSource
import com.osplus.tools.model.formatSpan
import com.osplus.tools.ui.components.CardSectionLabel
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.MetricChartCard
import com.osplus.tools.ui.components.MultiLineChart
import com.osplus.tools.ui.components.MultiSeries
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SwitchRow
import com.osplus.tools.ui.components.TimePoint
import com.osplus.tools.ui.components.TimeSeriesChart
import com.osplus.tools.ui.components.UsageBar
import com.osplus.tools.ui.components.autoXMaxMs
import com.osplus.tools.ui.components.axisSpanLabel
import com.osplus.tools.ui.components.downsample
import com.osplus.tools.ui.components.spanText
import com.osplus.tools.vm.DeviceViewModel
import com.osplus.tools.ui.components.LiquidNavTabs
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import com.osplus.tools.ui.components.bottomBarContentPadding
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PowerDetailScreen(vm: DeviceViewModel) {
    // 页签状态放在 ViewModel：顶栏由根布局渲染，要根据它决定是否显示
    // 「复制 / 删除本次记录」两个动作，页内状态顶栏读不到
    val tab by vm.powerTab.collectAsStateWithLifecycle()
    // 「充电控制」页签已于 2.6.5 移除：真机实测该功能不可用（写 charging_enabled /
    // input_suspend 等节点在当前内核上不存在或被厂商充电策略接管，回读校验必然失败），
    // 保留一个永远失败的页面没有意义。
    val tabs = remember { listOf("耗电统计", "充电统计") }

    Column(Modifier.fillMaxSize()) {
        LiquidNavTabs(
            items = tabs,
            selectedIndex = tab.coerceIn(0, tabs.lastIndex),
            onSelect = { vm.setPowerTab(it) },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        when (tab) {
            0 -> PowerRecordTab(vm)
            else -> ChargeStatsTab(vm)
        }
    }
}

/**
 * 耗电统计（录制式）。
 *
 * 与「充电统计」那种只读实时值的页面不同，这一页是**有状态**的：
 * 用户手动开始一次录制，页面持续累积采样，停止后锁定本次记录供事后分析。
 *
 * 版面按「先看结论、再看过程、最后看归因」组织：
 * 顶部录制控制回答「开始/停止」，其下使用过程回答「电量怎么掉的」，
 * 功耗续航卡回答「所以还能用多久」，使用场景卡回答「是谁在耗」。
 */
@Composable
private fun PowerRecordTab(vm: DeviceViewModel) {
    val samples by vm.powerSamples.collectAsStateWithLifecycle()
    val summary by vm.powerSummary.collectAsStateWithLifecycle()
    val recording by vm.powerRecording.collectAsStateWithLifecycle()
    val drains by vm.powerDrains.collectAsStateWithLifecycle()
    val cpuSeries by vm.powerCpuSeries.collectAsStateWithLifecycle()
    val energy by vm.batteryEnergy.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val hasUsageAccess by vm.usageAccess.collectAsStateWithLifecycle()
    val icons by vm.appIcons.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val c = osColors()

    /** 使用场景的两种视图：0 曲线图 / 1 列表 */
    var sceneView by rememberSaveable { mutableIntStateOf(0) }

    // 复制后的回执：状态放在 ViewModel 里，顶栏图标与卡内操作条两个入口共用，
    // 否则顶栏复制会静默成功、点完没有任何反馈
    val copied by vm.powerCopied.collectAsStateWithLifecycle()

    // 用户可能刚去系统设置里授予「使用情况访问」，回到本页时重读一次
    LaunchedEffect(Unit) { vm.refreshUsageAccess() }

    LaunchedEffect(drains) {
        vm.loadAppIcons(drains.map { it.packageName })
    }

    val context = LocalContext.current
    val last = samples.lastOrNull()
    val chargingNow = last?.charging ?: false

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---------------- 录制控制 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 2.dp)) {
                    CardSectionLabel("耗电录制")
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        label = if (recording) "正在录制" else "开始录制",
                        summary = "每秒采集电量、电压、温度与电流，停止后可回看整段曲线与统计",
                        checked = recording,
                        onCheckedChange = {
                            if (it) vm.startPowerRecording() else vm.stopPowerRecording()
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    LiquidNavTabs(
                        items = listOf("复制数据", "删除本次记录"),
                        selectedIndex = -1,
                        onSelect = {
                            if (it == 0) {
                                vm.copyPowerRecord()
                            } else {
                                vm.clearPowerRecord()
                            }
                        },
                        enabled = samples.isNotEmpty(),
                    )
                    if (copied) {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner(text = "已复制到剪贴板", accent = ChartColors.gpu)
                    }
                    if (chargingNow) {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner(text = "正在充电：充电时电流方向相反，本段不计入耗电功耗。")
                    }
                }
            }
        }

        // ---------------- 使用过程 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    if (samples.isEmpty()) {
                        Text(
                            text = if (recording) {
                                "正在采集第一秒数据…"
                            } else {
                                "点击上方「开始录制」，曲线会随录制时长实时延长（可自动扩展）。"
                            },
                            style = OsText.label,
                            color = c.textSecondary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        // 标题行：左标题 + 右上角当前剩余电量（规格要求）
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "使用过程",
                                    style = OsText.label,
                                    color = c.textPrimary,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    text = "录制时长 ${formatSpan(summary.durationMs)}" +
                                        " ｜ 采样 ${samples.size} 条" +
                                        " ｜ 时间轴 0 ~ ${axisMinutes(summary.durationMs)}",
                                    style = OsText.micro,
                                    color = c.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "${last?.levelPercent ?: 0}",
                                    style = OsText.metricSmall,
                                    color = ChartColors.power,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text = "%",
                                    style = OsText.micro,
                                    color = c.textSecondary,
                                    modifier = Modifier.padding(bottom = 3.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // 真实时间轴：X 按毫秒比例映射，0~10 分钟起步、超出后自动扩展。
                        // 不能用等距铺满的折线——那样录 1 分钟和录 10 分钟的曲线
                        // 长得一模一样，看不出电量掉得快还是慢。
                        TimeSeriesChart(
                            points = samples.map {
                                TimePoint(
                                    elapsedMs = it.elapsedMs,
                                    value = it.levelPercent.coerceAtLeast(0).toFloat(),
                                )
                            },
                            xMaxMs = autoXMaxMs(summary.durationMs),
                            color = ChartColors.power,
                        )
                        Spacer(Modifier.height(12.dp))
                        // 四个电池原始指标：2×2，避免四个并排后数值被挤到换行
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            BatteryMetric(
                                icon = Icons.Filled.BatteryFull,
                                label = "电池能量",
                                value = if (energy.fullWh > 0f) "%.1f Wh".format(energy.fullWh) else "-",
                                accent = ChartColors.power,
                                modifier = Modifier.weight(1f),
                            )
                            BatteryMetric(
                                icon = Icons.Rounded.Thermostat,
                                label = "电池温度",
                                value = last?.tempC?.let { "%.1f ℃".format(it) } ?: "-",
                                accent = ChartColors.temp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(11.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            BatteryMetric(
                                icon = Icons.Rounded.Bolt,
                                label = "电池电压",
                                value = last?.voltageMv?.takeIf { it > 0 }?.let {
                                    "%.3f V".format(it / 1000f)
                                } ?: "-",
                                accent = ChartColors.gpu,
                                modifier = Modifier.weight(1f),
                            )
                            BatteryMetric(
                                icon = Icons.Rounded.Power,
                                label = "充电状态",
                                value = if (chargingNow) "充电中" else "未充电",
                                accent = if (chargingNow) ChartColors.gpu else ChartColors.cpu,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (energy.fullWh > 0f && !energy.measured) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "能量为按设计容量折算值（本机未上报 ENERGY_COUNTER）",
                                style = OsText.micro,
                                color = c.textTertiary,
                            )
                        }
                    }
                }
            }
        }

        // ---------------- 功耗 & 续航 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("功耗与续航")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CoreMetric(
                            label = "平均功耗",
                            value = if (summary.avgPowerW > 0f) "%.2f".format(summary.avgPowerW) else "-",
                            unit = "W",
                            accent = ChartColors.power,
                            modifier = Modifier.weight(1f),
                        )
                        CoreMetric(
                            label = "已使用",
                            value = formatSpan(summary.durationMs),
                            unit = "",
                            accent = ChartColors.cpu,
                            modifier = Modifier.weight(1f),
                        )
                        CoreMetric(
                            label = "理论续航",
                            value = if (summary.theoreticalRemainMs > 0L) {
                                formatSpan(summary.theoreticalRemainMs)
                            } else {
                                "-"
                            },
                            unit = "",
                            accent = ChartColors.gpu,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    val sourceText = when (summary.source) {
                        PowerSource.Current -> "电流法 P = U × I（精度最高）"
                        PowerSource.LevelDelta -> "电量差法（电流不可读时的回落，短录制误差较大）"
                        PowerSource.Unavailable -> "尚无可用的功耗数据"
                    }
                    Text(
                        text = "计算来源：$sourceText\n" +
                            "Android 没有原生的「功耗」接口，这里的数值全部是算出来的。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                    if (samples.isNotEmpty() && !summary.reliable) {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner(
                            text = "录制不足 ${PowerRecorder.MIN_ACCURATE_MS / 1000} 秒，" +
                                "平均功耗与理论续航仅供参考。",
                        )
                    }
                    if (summary.charging && samples.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner(text = "本次录制全程处于充电状态，未计算耗电功耗与续航。")
                    }
                }
            }
        }

        // ---------------- 使用场景 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("使用场景")
                    Spacer(Modifier.height(8.dp))
                    LiquidNavTabs(
                        items = listOf("曲线图", "列表"),
                        selectedIndex = sceneView,
                        onSelect = { sceneView = it },
                        enabled = drains.isNotEmpty(),
                    )
                    Spacer(Modifier.height(10.dp))

                    if (!hasUsageAccess) {
                        NoticeBanner(
                            text = "需要「使用情况访问权限」才能统计各应用的活跃时长；" +
                                "Root 可用时还会叠加各进程的 CPU 占用。",
                        )
                    } else if (drains.isEmpty()) {
                        Text(
                            text = if (recording) {
                                "正在采集应用侧数据（每 10 秒一次，避免采集本身抬高功耗）…"
                            } else {
                                "开始录制后，这里会列出本次记录期间各应用的耗电明细。"
                            },
                            style = OsText.label,
                            color = c.textSecondary,
                        )
                    } else if (sceneView == 0) {
                        if (cpuSeries.isEmpty()) {
                            Text(
                                text = "曲线图需要 Root：无 Root 时读不到各进程的 CPU 占用，" +
                                    "无法绘制按应用的曲线。可切到「列表」查看活跃时长。",
                                style = OsText.label,
                                color = c.textSecondary,
                            )
                        } else {
                            val top = drains.take(PowerRecorder.MAX_SERIES)
                            val palette = listOf(
                                ChartColors.cpu,
                                ChartColors.gpu,
                                ChartColors.mem,
                                ChartColors.power,
                                ChartColors.fps,
                            )
                            val series = top.mapIndexed { i, e ->
                                MultiSeries(
                                    label = e.label,
                                    values = cpuSeries.map { it.cpuByPackage[e.packageName] ?: 0f },
                                    color = palette[i % palette.size],
                                )
                            }
                            val peak = series.flatMap { it.values }.maxOrNull() ?: 0f
                            MultiLineChart(
                                series = series,
                                maxValue = peak.coerceAtLeast(10f),
                                unit = "CPU %",
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "纵轴为各应用的 CPU 占用（%）。Android 没有按应用的功率接口，" +
                                    "CPU 是唯一能拿到的时间序列代理指标——它抓得到「谁在烧 CPU」，" +
                                    "抓不到「谁在后台唤醒」。",
                                style = OsText.micro,
                                color = c.textTertiary,
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            drains.take(30).forEach { entry ->
                                AppDrainRow(entry = entry, icon = icons[entry.packageName])
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = (if (rootAvailable) {
                                "占比按录制期间各应用的 CPU 占用累加值加权（不是平均值：" +
                                    "短时高负载与长时间低负载的能耗并不相同）。"
                            } else {
                                "无 Root 时退化为按前台活跃时长加权，占比会偏向「用得久」而非「用得狠」。"
                            }) + "\n活跃时长取自系统 UsageStats，它是定期落盘的，短录制内可能尚未刷新。",
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

/** 电池原始指标：图标 + 名称 + 数值 */
@Composable
private fun BatteryMetric(
    icon: ImageVector,
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val c = osColors()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = OsText.micro,
                color = c.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = OsText.value,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 核心指标：小标签 + 大数值 + 单位 */
@Composable
private fun CoreMetric(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val c = osColors()
    Column(modifier) {
        Text(
            text = label,
            style = OsText.micro,
            color = c.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = OsText.metricSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = unit,
                    style = OsText.micro,
                    color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/** 使用场景列表的一行 */
@Composable
private fun AppDrainRow(entry: AppDrainEntry, icon: ImageBitmap?) {
    val c = osColors()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = entry.label,
                style = OsText.label,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // 用主色而不是红色：耗电占比是中性读数，
                // 整页十几个红数字并排时观感像在报故障
                text = "%.1f%%".format(entry.drainPercent),
                style = OsText.valueStrong,
                color = c.primary,
            )
        }
        Spacer(Modifier.height(5.dp))
        UsageBar(fraction = entry.drainPercent / 100f, color = c.primary, barHeight = 5.dp)
        Spacer(Modifier.height(5.dp))
        // 活跃时长来自 UsageStats，而 UsageStats 是**定期落盘**的，不是实时值：
        // 短录制里前后两次查询的累计值往往完全相同，差值恒为 0。
        // 此时不能显示「前台 -」——那读起来像「它没在前台待过」，
        // 而事实是「系统还没刷新」。有值才显示，没值就直说。
        val detail = buildList {
            if (entry.foregroundMs > 0L) add("前台 ${formatSpan(entry.foregroundMs)}")
            if (entry.cpuPercentAvg > 0f) add("CPU %.1f%%".format(entry.cpuPercentAvg))
            if (isEmpty()) add("活跃时长待系统刷新")
        }.joinToString(" ｜ ")
        Text(
            text = detail,
            style = OsText.micro,
            color = c.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard() {
                Column(Modifier.padding(vertical = 3.dp)) {
                    MetricChartCard(
                        title = "整机功耗",
                        values = downsample(history.map { it.powerMw }, trendSlots),
                        maxValue = autoMax(history.map { it.powerMw }, 0f, 500f),
                        color = ChartColors.power,
                        unit = "mW",
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
                        axisStartLabel = trendAxis,
                    )
                }
            }
        }
        item {
            SectionCard() {
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

/** 时间轴上限的文案，与 [autoXMaxMs] 取同一口径，避免两处各算一遍导致对不上 */
private fun axisMinutes(durationMs: Long): String = "${autoXMaxMs(durationMs) / 60_000} 分"
