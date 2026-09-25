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
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.HealthBanner
import com.osplus.tools.ui.components.HealthLevel
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.LineChart
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.OsMetricRing
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.axisSpanLabel
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * 概览页可下钻的详情页。
 *
 * [title] 供根布局的统一顶栏显示——标题文案集中在枚举里，
 * 避免顶栏在根布局、页面在屏幕文件里各写一份而漂移。
 *
 * 电源已提升为一级页，不再是概览的下钻目标；概览页的电池卡直接切到「电源」标签。
 */
enum class OverviewDetail(val title: String) {
    Memory("内存"),
    Gpu("GPU"),
    Cpu("CPU"),
    Process("进程"),
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
 * 概览（一级页）。
 *
 * 版式按「结论 → 水位 → 明细」排列：
 * 1. **健康结论条** —— 先回答「有没有问题」，下面的卡片负责「是多少」，这一条负责「算不算正常」；
 * 2. **三张通栏圆环卡** —— CPU / GPU / 内存，名称、百分比、说明行三行全部收在环心；
 *    再一张**帧率折线卡** —— 帧率要回答的是「稳不稳」，折线才看得出抖动；
 * 3. **设备概况** —— 低频静态信息压到最后。
 *
 * 圆环取 140dp：环内文字受内切矩形约束（说明行的矩形底边也必须落在圆内）。
 * 按等线真实 ascent/descent 排版，说明行拆两行后四行总高 60.8dp，
 * 末行底边距环心 30.4dp，环内径 60dp 时说明行限宽 103.5dp，
 * 最长行（内存「10.71 GB / 14.75 GB」）85.8dp，余量 17.7dp。
 * 说明行只放纯数值、不带「当前 / 可用」前缀——前缀不承载信息却显著加宽文案；
 * 核心数、簇数等次要信息在「设备概况」里。
 *
 * 动作（清理内存 / 清理交换 / 记录帧率 / 设置）在统一顶栏，
 * 因此页面本身只剩「读」的内容。
 *
 * 顶部留白只有 4dp：页面标题由根布局的 [com.osplus.tools.ui.components.OsTopBar]
 * 统一承担，本页不再自绘标题，也不需要在顶部为它预留空间。
 */
@Composable
fun OverviewScreen(
    vm: DeviceViewModel,
    onOpen: (OverviewDetail) -> Unit,
    onOpenPower: () -> Unit,
) {
    val history by vm.history.collectAsStateWithLifecycle()
    val cpu by vm.cpu.collectAsStateWithLifecycle()
    val gpu by vm.gpu.collectAsStateWithLifecycle()
    val mem by vm.mem.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
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

    val cpuLoad = latest?.cpuLoad ?: 0f
    val gpuLoad = gpu.loadPercent
    val memPercent = latest?.memUsedPercent ?: 0f
    // SoC 结温与电池温度分别取值、分别判据：两者正常区间相差几十度，
    // 合并成一个 tempC 再套同一组阈值会把正常的 CPU 结温误判成「温度过高」
    val socTempC = cpu.tempC
    val batteryTempC = battery.tempC ?: latest?.batteryTempC
    val charging = battery.status.contains("充电") || battery.plugged.contains("USB") ||
        battery.plugged.contains("AC") || battery.plugged.contains("无线")

    val health = evaluateHealth(
        rootAvailable = rootAvailable,
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
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HealthBanner(
                level = health.level,
                title = health.title,
                detail = health.detail,
            )
        }

        // 指标区 2×2：CPU / GPU / 内存 / 帧率 各占一格。
        //
        // 尺寸统一靠 `contentHeight = RingSize`（96dp）：环卡的内容天然就是 96dp 的环，
        // 而帧率卡是「表头 + 折线 + 时间轴」，实测只有 79.5dp，比环卡矮 16.5dp，
        // 于是 2×2 的第二行横线会明显比第一行矮、左右也不齐。
        // 给四格同一个内容最小高度后，环卡高度不变（本来就有 96dp），
        // 帧率卡被撑到同一高度，四张卡自然等高。
        //
        // 两列意味着单卡只有 160dp 宽（360 − 左右 14×2 − 列间距 12，对半），
        // 环也随之从通栏版的 140dp 收到 96dp——环心内径只剩 40dp，
        // 因此说明行必须极短（「2400 MHz」44.0dp 是上限附近），
        // GPU 型号这种长文案放不进环心，只留频率。
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard(
                    modifier = Modifier.weight(1f),
                    onClick = { onOpen(OverviewDetail.Cpu) },
                    contentHeight = MetricCellContentHeight,
                ) {
                    OsMetricRing(
                        label = "CPU",
                        valueText = "%.0f".format(cpuLoad),
                        unit = "%",
                        hint = cpu.clusters.maxOfOrNull { it.curKhz }
                            ?.let { "${it / 1000} MHz" }
                            ?: "不可读",
                        progress = cpuLoad / 100f,
                        accent = ChartColors.cpu,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                SectionCard(
                    modifier = Modifier.weight(1f),
                    onClick = { onOpen(OverviewDetail.Gpu) },
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
                    onClick = { onOpen(OverviewDetail.Memory) },
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
                    onClick = onOpenPower,
                    contentHeight = MetricCellContentHeight,
                ) {
                    FpsTrendCard(values = trend.map { it.fps }, axisStartLabel = fpsAxisStart)
                }
            }
        }

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

        item {
            SectionCard {
                Text(
                    text = "设备概况",
                    style = OsText.value,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                InfoRow("SoC", cpu.soc.ifBlank { "读取中…" })
                InfoRow("架构", cpu.abi.ifBlank { "-" })
                InfoRow("核心", "${cpu.coreCount} 核心 · ${cpu.clusters.size} 簇")
                // GPU 型号放在「核心」之下：两者同属芯片规格，读起来是连续的
                InfoRow("GPU", gpu.name.ifBlank { "读取中…" })
                InfoRow("物理内存", "${fmtGb(mem.usedKb)} / ${fmtGb(mem.totalKb)}")
                InfoRow(
                    label = "SWAP",
                    value = if (mem.swapTotalKb > 0) {
                        "${fmtGb(mem.swapUsedKb)} / ${fmtGb(mem.swapTotalKb)}"
                    } else "未启用",
                )
                InfoRow(
                    label = "ZRAM",
                    value = if (mem.zramTotalKb > 0) {
                        "${fmtGb(mem.zramUsedKb)} / ${fmtGb(mem.zramTotalKb)}"
                    } else "未启用",
                )
                InfoRow("电池健康", battery.health.ifBlank { "-" })
                InfoRow("循环次数", if (battery.cycleCount >= 0) "${battery.cycleCount} 次" else "-")
            }
        }
    }
}

/**
 * 概览页的帧率折线卡（2×2 网格的第四格）。
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
 * [socTempC] 来自 `/sys/class/thermal/thermal_zone0`（真机上是 `aoss-0`，SoC 结温），
 * [batteryTempC] 来自 `BatteryManager.EXTRA_TEMPERATURE`。两者的正常区间差着几十度：
 * SoC 结温 40~70 ℃ 属正常工作范围，要到 85 ℃ 附近才触发内核降频；
 * 电池温度则 45 ℃ 已接近告警线。曾经把二者写成 `cpu.tempC ?: batteryTempC`
 * 共用 50 ℃/42 ℃ 阈值，结果 CPU 结温常年 50~60 ℃ 被误判成「温度过高」，
 * 与同一屏里「电池健康 良好」自相矛盾。
 */
private fun evaluateHealth(
    rootAvailable: Boolean,
    socTempC: Float?,
    batteryTempC: Float?,
    memPercent: Float,
    availKb: Long,
    levelPercent: Int,
    charging: Boolean,
): Health {
    if (!rootAvailable) {
        return Health(
            level = HealthLevel.Warn,
            title = "未获取 Root 权限",
            detail = "频率控制、进程管理、充电控制不可用；信息读取正常",
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
