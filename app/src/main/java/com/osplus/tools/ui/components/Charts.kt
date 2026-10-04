package com.osplus.tools.ui.components

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Text

/**
 * 把长序列等分聚合为至多 [buckets] 个点（每段取均值）。
 *
 * 帧率记录窗口可达数分钟（数百个采样点），直接绘制会退化成一条实心色块，
 * 因此按桶聚合后再绘图。
 */
fun downsample(values: List<Float>, buckets: Int = 30): List<Float> {
    if (buckets <= 0 || values.size <= buckets) return values
    val step = values.size.toFloat() / buckets
    return List(buckets) { i ->
        val from = (i * step).toInt().coerceIn(0, values.size - 1)
        val to = ((i + 1) * step).toInt().coerceIn(from + 1, values.size)
        values.subList(from, to).average().toFloat()
    }
}

/**
 * 时间跨度（秒）→ 时间轴左端文案。
 *
 * 趋势数据改成累积保留后，窗口长度会从几秒一路长到几十分钟，
 * 固定的「5 秒前」不再成立，因此统一用「-mm:ss」表达左端相对当前的偏移。
 * 例：45 → `-00:45`；155 → `-02:35`；3725 → `-1:02:05`。
 */
fun axisSpanLabel(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s < 3600) "-%02d:%02d".format(s / 60, s % 60)
    else "-%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** 时间跨度（秒）→ 可读文案，如「45 秒」「2 分 35 秒」「1 时 2 分」 */
fun spanText(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "$s 秒"
        s < 3600 -> "${s / 60} 分 ${s % 60} 秒"
        else -> "${s / 3600} 时 ${(s % 3600) / 60} 分"
    }
}

/** 统一图表配色 */
object ChartColors {
    val cpu = Color(0xFF3B7BE0)
    val gpu = Color(0xFF3DBE6B)
    val mem = Color(0xFFF0A868)
    val power = Color(0xFFF0524A)
    val fps = Color(0xFF9B6BE8)
    val temp = Color(0xFFEF6E7E)
    val zram = Color(0xFF4CB8E8)
}

/**
 * 圆环进度图（环心文案由 [content] 提供）。
 *
 * 参考图的核心视觉元素：粗圆头圆环 + 居中标签，
 * 用极简的几何形状表达一个百分比，比数字堆叠更易读。
 * 概览页的指标卡、详情页的水位环都用它。
 */
@Composable
fun RingChart(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 90.dp,
    stroke: Dp = 12.dp,
    label: String? = null,
    value: String? = null,
    unit: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val c = osColors()
    val target = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(target, tween(600), label = "ring")

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val inset = s / 2f
            val arcSize = Size(this.size.width - s, this.size.height - s)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = c.track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = s, cap = StrokeCap.Round),
            )
            if (animated > 0.002f) {
                drawArc(
                    brush = Brush.linearGradient(
                        colors = listOf(color.copy(alpha = 0.55f), color),
                        start = Offset(0f, 0f),
                        end = Offset(this.size.width, this.size.height),
                    ),
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = s, cap = StrokeCap.Round),
                )
            }
        }
        if (content != null) {
            content()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (value != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = value,
                            style = OsText.metricSmall,
                            color = c.textPrimary,
                            maxLines = 1,
                        )
                        if (unit != null) {
                            Spacer(Modifier.width(1.dp))
                            Text(
                                text = unit,
                                style = OsText.micro,
                                color = c.textSecondary,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
                }
                if (label != null) {
                    Text(
                        text = label,
                        style = OsText.micro,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 概览指标环卡：名称、百分比、说明行全部收在环心。
 *
 * **环为什么是 96dp（两列版）：**
 * 概览指标区是 2×2 网格，单卡只有 160dp 宽
 * （360 − 左右各 14 的内边距 − 列间距 12，再对半），卡内容宽 132dp。
 * 环还要给两侧留呼吸，因此 96dp 是这张卡里能用的上限附近（左右各余 18dp）。
 *
 * **环内的文字容量受内切矩形限制，不是内径宽度：**
 * 环内文字块的四个角都必须落在内圆内，最下面那行说明文字的外沿是约束点。
 * 按等线真实 ascent/descent 排版（名称 14.0dp + 百分比 22.2dp + 说明 10.8dp，行距 1dp），
 * 三行总高 49.0dp，末行外沿距环心 24.5dp。环 96dp / 环宽 8dp 时内径 40dp，
 * 于是说明行可用宽度 = `2·√(40² − 24.5²)` = 63.2dp。
 *
 * **所以两列版的说明行必须极短**（去掉一切前缀词）：
 * `2400 MHz` 44.0dp ✓ / `222 MHz` 38.8dp ✓ / `10.7 GB 可用` 需更多但仍在限内 ✓；
 * 而 GPU 型号 `Adreno830v2` 需 57.8dp，**已顶到 63.2dp 的安全边界**，
 * 因此概览只显示实时频率，型号移到 GPU 详情页——两列布局的环心装不下它。
 *
 * **注意 `sp × 1.3` 不能当行高用**：21sp 真实行高 22.2dp，而 `21 × 1.3 = 27.3dp`
 * 有 23% 误差，会直接改变环尺寸的结论。必须读字体 `ascent + descent`。
 *
 * [hint] 用 `\n` 分隔多行。
 *
 * 环心各行由外向内收紧层级：名称用次级灰（它只是索引）、
 * 百分比用主题色加粗（唯一读数，视觉重心）、说明行用三级灰（补充信息）。
 * 百分比沿用 [OsText.metric]（21sp），名称 [OsText.label]（13sp），
 * 说明 [OsText.micro]（10sp）——字号与 1.3.0 的折线卡保持一致，不因换图形而改变字阶。
 *
 * [enabled] 为 false 时表示数值不可读（如 GPU 负载 -1）：画空圈 + 灰字，
 * 而不是画一个 0% 的环——「读不到」和「负载为零」是两回事，
 * 后者会让人误以为设备闲着。
 */
@Composable
fun OsMetricRing(
    label: String,
    valueText: String,
    unit: String,
    hint: String,
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    stroke: Dp = 8.dp,
    enabled: Boolean = true,
) {
    val c = osColors()
    val hintLines = remember(hint) { hint.split('\n') }
    RingChart(
        progress = if (enabled) progress else 0f,
        color = accent,
        modifier = modifier,
        size = size,
        stroke = stroke,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = OsText.label,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Visible,
            )
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = valueText,
                    style = OsText.metric,
                    color = if (enabled) accent else c.textTertiary,
                    maxLines = 1,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(1.dp))
                    Text(
                        text = unit,
                        style = OsText.micro,
                        color = if (enabled) accent else c.textTertiary,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(1.dp))
            hintLines.forEach { line ->
                Text(
                    text = line,
                    style = OsText.micro,
                    color = c.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

/**
 * 折线趋势图。
 *
 * 用于观察长窗口下的波动：柱状图在几十个采样点时只能看出高低，
 * 折线能直观呈现起伏、持续低帧区间与回升过程。
 *
 * ## Y 轴
 *
 * 左侧保留 [yAxisWidth] 宽的刻度栏，画水平网格线 + 数值标签，标签后缀 [unit]。
 * 刻度来源由 [ticks] 决定：默认按量程等分 5 档；帧率场景传
 * `{ frameRateTicks(it) }`，让刻度落在真实存在的面板挡位上。
 *
 * **量程 [maxValue] 必须由调用方钉死**，不要传自适应峰值——轴一浮动，
 * 30fps 和 60fps 就会画成同一条曲线，图也就失去了意义。
 */
@Composable
fun LineChart(
    values: List<Float>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 68.dp,
    valueFormatter: (Float) -> String = { "%.0f".format(it) },
    /** 是否在顶部标注峰值。概览页的迷你折线不需要，数值由卡片头部承担 */
    showPeak: Boolean = true,
    /** 是否画 Y 轴（刻度栏 + 网格线 + 标签）。概览页的小尺寸折线关掉 */
    showYAxis: Boolean = true,
    /** 刻度后缀单位，如 `FPS` / `%` / `℃` / `MHz` / `mW` / `ms` */
    unit: String = "",
    /** Y 轴刻度值的生成方式，默认按量程等分 5 档 */
    ticks: (Float) -> List<Float> = { axisTicks(it, 5) },
) {
    val c = osColors()
    val safeMax = maxValue.coerceAtLeast(0.001f)
    val data = remember(values) { if (values.isEmpty()) listOf(0f) else values }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = c.textTertiary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    val density = LocalDensity.current
    val tickValues = remember(safeMax, ticks) { ticks(safeMax) }
    val tickLabels = remember(tickValues, valueFormatter, unit) {
        tickValues.map { if (unit.isBlank()) valueFormatter(it) else "${valueFormatter(it)}$unit" }
    }
    // 刻度栏宽度按最长的标签算——不然 `800MHz` 会被 `0` 的宽度截掉，
    // 而各图量程差异很大（温度 50、频率 800、功耗 3000），写死一个值必然有图吃亏
    val axisWidthPx = remember(tickLabels, density) {
        if (!showYAxis) 0f
        else {
            val w = tickLabels.maxOfOrNull { measurer.measure(it, labelStyle).size.width } ?: 0
            with(density) { (w.toDp() + 6.dp).toPx() }
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val labelH = if (showPeak) with(density) { 14.dp.toPx() } else 0f
        val chartTop = labelH
        val chartBottom = size.height
        val chartH = (chartBottom - chartTop).coerceAtLeast(1f)
        val plotLeft = axisWidthPx
        val plotW = (size.width - plotLeft).coerceAtLeast(1f)
        val stepX = if (data.size > 1) plotW / (data.size - 1) else plotW

        fun yOf(v: Float): Float {
            val f = (v / safeMax).coerceIn(0f, 1f)
            return chartBottom - chartH * f
        }

        // Y 轴：网格线 + 刻度标签
        if (showYAxis) {
            tickValues.forEachIndexed { i, t ->
                val y = yOf(t)
                val tm = measurer.measure(tickLabels[i], labelStyle)
                // 最底一档（0）与基线重合，不重复画
                if (t > 0f) {
                    drawLine(
                        color = c.hairline,
                        start = Offset(plotLeft, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                }
                drawText(
                    textLayoutResult = tm,
                    topLeft = Offset(
                        (plotLeft - with(density) { 4.dp.toPx() } - tm.size.width).coerceAtLeast(0f),
                        (y - tm.size.height / 2f)
                            .coerceIn(chartTop, (chartBottom - tm.size.height).coerceAtLeast(chartTop)),
                    ),
                )
            }
        }

        // 基线
        drawLine(
            color = c.hairline,
            start = Offset(plotLeft, chartBottom - 0.5f),
            end = Offset(size.width, chartBottom - 0.5f),
            strokeWidth = 1f,
        )

        // 面积填充
        val area = Path().apply {
            moveTo(plotLeft, chartBottom)
            data.forEachIndexed { i, v -> lineTo(plotLeft + stepX * i, yOf(v)) }
            lineTo(plotLeft + stepX * (data.size - 1), chartBottom)
            close()
        }
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0.02f)),
                startY = chartTop,
                endY = chartBottom,
            ),
        )

        // 折线
        val line = Path().apply {
            data.forEachIndexed { i, v ->
                val x = plotLeft + stepX * i
                val y = yOf(v)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = line,
            color = color,
            style = Stroke(
                width = with(density) { 2.dp.toPx() },
                join = StrokeJoin.Round,
                cap = StrokeCap.Round,
            ),
        )

        // 末端标记点
        drawCircle(
            color = color,
            radius = with(density) { 3.5.dp.toPx() },
            center = Offset(size.width, yOf(data.last())),
        )

        // 顶部标注：只标峰值，当前值由卡片头部承担，避免同一数字出现两次。
        // 有 Y 轴时右移让位，否则会和最高刻度叠在一起
        if (showPeak) {
            val peak = data.maxOrNull() ?: 0f
            val pm = measurer.measure("峰值 " + valueFormatter(peak), labelStyle)
            drawText(textLayoutResult = pm, topLeft = Offset(plotLeft, 0f))
        }
    }
}

/**
 * 指标卡片：标题 + 当前值 + 折线图 + 时间轴。
 *
 * 全站统一折线：柱状图在几十个采样点下只能看出高低，折线才能呈现起伏、
 * 持续高位区间与回落过程。唯一保留柱状的是「每核一柱」的
 * [CoreBarsChart] 与 [CoreFreqGrid]。
 */
@Composable
fun MetricChartCard(
    title: String,
    values: List<Float>,
    maxValue: Float,
    color: Color,
    unit: String = "",
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    valueFormatter: (Float) -> String = { "%.0f".format(it) },
    showAxis: Boolean = true,
    /** 时间轴左端文案；调用方按实际窗口长度传入 [axisSpanLabel] 的结果 */
    axisStartLabel: String = "最早",
    /**
     * Y 轴刻度值的生成方式。默认按量程等分 5 档；
     * 帧率场景传 `{ frameRateTicks(it) }`，刻度落在真实面板挡位上。
     */
    yTicks: (Float) -> List<Float> = { axisTicks(it, 5) },
) {
    val c = osColors()
    val current = values.lastOrNull() ?: 0f
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = OsText.label,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = it,
                        style = OsText.micro,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = valueFormatter(current),
                    style = OsText.metricSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = unit,
                        style = OsText.micro,
                        color = c.textSecondary,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        LineChart(
            values = values,
            maxValue = maxValue,
            color = color,
            valueFormatter = valueFormatter,
            unit = unit,
            ticks = yTicks,
        )
        if (showAxis) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = axisStartLabel, style = OsText.micro, color = c.textTertiary)
                Text(text = "现在", style = OsText.micro, color = c.textTertiary)
            }
        }
    }
}

/**
 * 每核一柱：柱高为占用率，柱顶标注频率，柱下标注核心编号。
 */
@Composable
fun CoreBarsChart(
    coreIndexes: List<Int>,
    loads: List<Float>,
    freqs: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 86.dp,
) {
    val c = osColors()
    if (coreIndexes.isEmpty()) {
        Box(modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "未读取到 CPU 核心",
                style = OsText.label,
                color = c.textSecondary,
            )
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = c.textTertiary, fontSize = 9.sp)
    val indexStyle = TextStyle(color = c.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    val density = LocalDensity.current

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = coreIndexes.size
            val slotW = size.width / n
            val barW = minOf(slotW * 0.40f, with(density) { 13.dp.toPx() })
            val minBarH = with(density) { 4.dp.toPx() }
            val radius = CornerRadius(barW / 2f, barW / 2f)
            val topLabelH = with(density) { 13.dp.toPx() }
            val bottomLabelH = with(density) { 15.dp.toPx() }
            val chartTop = topLabelH
            val chartBottom = size.height - bottomLabelH
            val chartH = (chartBottom - chartTop).coerceAtLeast(1f)

            coreIndexes.forEachIndexed { i, coreId ->
                val load = loads.getOrElse(i) { 0f }.coerceIn(0f, 100f)
                val khz = freqs.getOrElse(i) { -1L }
                val left = slotW * i + (slotW - barW) / 2f
                val centerX = left + barW / 2f

                val barColor = when {
                    load >= 80f -> c.red
                    load >= 50f -> c.orange
                    load >= 20f -> ChartColors.cpu
                    else -> c.primarySoft
                }
                val h = (chartH * (load / 100f)).coerceAtLeast(minBarH)
                val top = chartBottom - h
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(barColor, barColor.copy(alpha = 0.62f)),
                        startY = top,
                        endY = chartBottom,
                    ),
                    topLeft = Offset(left, top),
                    size = Size(barW, h),
                    cornerRadius = radius,
                )

                val freqText = if (khz > 0) "${khz / 1000}" else "-"
                val fm = measurer.measure(freqText, labelStyle)
                drawText(
                    textLayoutResult = fm,
                    topLeft = Offset(centerX - fm.size.width / 2f, 0f),
                )

                val im = measurer.measure("$coreId", indexStyle)
                drawText(
                    textLayoutResult = im,
                    topLeft = Offset(centerX - im.size.width / 2f, chartBottom + with(density) { 2.dp.toPx() }),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "柱顶为当前频率 (MHz)，柱下为核心编号；颜色越暖表示占用越高",
            style = OsText.micro,
            color = c.textTertiary,
        )
    }
}

/**
 * 核心频率瓦片网格。
 *
 * 对应参考图右下角的频率宫格：每个核心一块瓦片，
 * 用迷你柱状图表示最近几秒的占用，下方给出当前频率与硬件范围。
 */
@Composable
fun CoreFreqGrid(
    coreIndexes: List<Int>,
    loads: List<Float>,
    loadsHistory: List<List<Float>>,
    freqsKhz: List<Long>,
    minKhz: List<Long>,
    maxKhz: List<Long>,
    modifier: Modifier = Modifier,
    columns: Int = 4,
) {
    val c = osColors()
    if (coreIndexes.isEmpty()) {
        Box(modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "未读取到 CPU 核心",
                style = OsText.label,
                color = c.textSecondary,
            )
        }
        return
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        coreIndexes.chunked(columns).forEach { rowCores ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowCores.forEach { coreId ->
                    val i = coreIndexes.indexOf(coreId)
                    val load = loads.getOrElse(i) { 0f }.coerceIn(0f, 100f)
                    val history = loadsHistory.getOrElse(i) { emptyList() }
                    val khz = freqsKhz.getOrElse(i) { -1L }
                    val min = minKhz.getOrElse(i) { -1L }
                    val max = maxKhz.getOrElse(i) { -1L }
                    FreqTile(
                        coreId = coreId,
                        load = load,
                        history = history,
                        curMhz = if (khz > 0) khz / 1000 else -1L,
                        minMhz = if (min > 0) min / 1000 else -1L,
                        maxMhz = if (max > 0) max / 1000 else -1L,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 补齐末行，保持列宽一致
                repeat(columns - rowCores.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FreqTile(
    coreId: Int,
    load: Float,
    history: List<Float>,
    curMhz: Long,
    minMhz: Long,
    maxMhz: Long,
    modifier: Modifier = Modifier,
) {
    val c = osColors()
    val accent = when {
        load >= 80f -> c.red
        load >= 50f -> c.orange
        else -> c.primarySoft
    }
    Column(
        modifier = modifier
            .osTile()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        MiniBarStrip(
            values = history,
            accent = accent,
            label = "%.0f%%".format(load),
            height = 34.dp,
        )
        Spacer(Modifier.height(7.dp))
        // 数字与单位分级排版：四位数频率（如 1017MHz）按同字号整体排版会超出瓦片宽度，
        // 拆成「主数字 + 小号单位」后与卡片区其他指标的数字层级也保持一致。
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (curMhz > 0) "$curMhz" else "-",
                style = OsText.value,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (curMhz > 0) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = "MHz",
                    style = OsText.micro,
                    color = c.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(1.dp))
        Text(
            text = if (minMhz > 0 && maxMhz > 0) "$minMhz-$maxMhz" else "核心 $coreId",
            style = OsText.micro,
            color = c.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 瓦片内的迷你柱群：柱顶叠加一行数值标注 */
@Composable
private fun MiniBarStrip(
    values: List<Float>,
    accent: Color,
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
) {
    val c = osColors()
    val data = remember(values) {
        if (values.isEmpty()) List(5) { 0f } else values.takeLast(6)
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = c.textTertiary, fontSize = 8.sp, fontWeight = FontWeight.Medium)
    val density = LocalDensity.current

    Canvas(modifier.fillMaxWidth().height(height)) {
        val labelH = with(density) { 10.dp.toPx() }
        val chartTop = labelH
        val chartBottom = size.height
        val chartH = (chartBottom - chartTop).coerceAtLeast(1f)
        val n = data.size.coerceAtLeast(1)
        val slotW = size.width / n
        val barW = minOf(slotW * 0.62f, with(density) { 9.dp.toPx() })
        val radius = CornerRadius(barW / 2f, barW / 2f)
        val minBarH = with(density) { 3.dp.toPx() }

        data.forEachIndexed { i, v ->
            val f = (v / 100f).coerceIn(0f, 1f)
            val left = slotW * i + (slotW - barW) / 2f
            val h = (chartH * f).coerceAtLeast(minBarH)
            val top = chartBottom - h
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(accent, accent.copy(alpha = 0.55f)),
                    startY = top,
                    endY = chartBottom,
                ),
                topLeft = Offset(left, top),
                size = Size(barW, h),
                cornerRadius = radius,
            )
        }

        val measured = measurer.measure(label, labelStyle)
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                ((size.width - measured.size.width) / 2f).coerceAtLeast(0f),
                0f,
            ),
        )
    }
}

/** 列表行内的迷你趋势条 */
/** [MultiLineChart] 的一条曲线 */
data class MultiSeries(
    val label: String,
    val values: List<Float>,
    val color: Color,
)

/**
 * 多序列折线图 + 图例。
 *
 * 单序列的 [LineChart] 只能回答「整体怎么变」，回答不了「谁在变」——
 * 「使用场景」要比较几个应用在同一段时间里的占用变化，必须同时画多条。
 *
 * 有意**不画面积填充**：多条曲线的渐变面积会互相叠加成一团色块，
 * 反而看不清各自走势。多序列场景下只保留线本身。
 *
 * 序列数建议不超过 5 条，再多颜色就分不清了；各序列的 `values` 长度应一致，
 * 否则短的那条会被拉伸到整幅宽度，时间轴对不齐。
 *
 * 多序列共用一根 Y 轴（各序列量纲一致才能画在一起，如「各应用 CPU 占用 %」），
 * 因此 [unit] 放在图例里说明，刻度标签只出数值——否则 5 枚刻度各带一遍单位会很挤。
 */
@Composable
fun MultiLineChart(
    series: List<MultiSeries>,
    maxValue: Float,
    modifier: Modifier = Modifier,
    height: Dp = 92.dp,
    /** 是否画 Y 轴（刻度栏 + 网格线 + 数值标签） */
    showYAxis: Boolean = true,
    /** 各序列共用的单位，显示在图例标签后 */
    unit: String = "",
) {
    val c = osColors()
    val safeMax = maxValue.coerceAtLeast(0.001f)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = c.textTertiary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    val tickValues = remember(safeMax) { axisTicks(safeMax, 5) }
    val tickLabels = remember(tickValues) { tickValues.map { "%.0f".format(it) } }
    val axisWidthPx = remember(tickLabels, density) {
        if (!showYAxis) 0f
        else {
            val w = tickLabels.maxOfOrNull { measurer.measure(it, labelStyle).size.width } ?: 0
            with(density) { (w.toDp() + 6.dp).toPx() }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val chartTop = 0f
            val chartBottom = size.height
            val chartH = (chartBottom - chartTop).coerceAtLeast(1f)
            val plotLeft = axisWidthPx
            val plotW = (size.width - plotLeft).coerceAtLeast(1f)

            fun yOf(v: Float): Float = chartBottom - chartH * (v / safeMax).coerceIn(0f, 1f)

            if (showYAxis) {
                tickValues.forEachIndexed { i, t ->
                    val y = yOf(t)
                    val tm = measurer.measure(tickLabels[i], labelStyle)
                    if (t > 0f) {
                        drawLine(
                            color = c.hairline,
                            start = Offset(plotLeft, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f,
                        )
                    }
                    drawText(
                        textLayoutResult = tm,
                        topLeft = Offset(
                            (plotLeft - with(density) { 4.dp.toPx() } - tm.size.width).coerceAtLeast(0f),
                            (y - tm.size.height / 2f)
                                .coerceIn(chartTop, (chartBottom - tm.size.height).coerceAtLeast(chartTop)),
                        ),
                    )
                }
            }

            drawLine(
                color = c.hairline,
                start = Offset(plotLeft, chartBottom - 0.5f),
                end = Offset(size.width, chartBottom - 0.5f),
                strokeWidth = 1f,
            )
            series.forEach { s ->
                val data = s.values
                if (data.isEmpty()) return@forEach
                val stepX = if (data.size > 1) plotW / (data.size - 1) else plotW
                val path = Path().apply {
                    data.forEachIndexed { i, v ->
                        val x = plotLeft + stepX * i
                        val y = yOf(v)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(
                    path = path,
                    color = s.color,
                    style = Stroke(
                        width = with(density) { 1.8.dp.toPx() },
                        join = StrokeJoin.Round,
                        cap = StrokeCap.Round,
                    ),
                )
            }
        }
        if (series.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            // 多序列共用一根 Y 轴，单位只在图例里标一次，避免 5 枚刻度各带一遍单位
            if (unit.isNotBlank()) {
                Text(text = "纵轴单位：$unit", style = OsText.micro, color = c.textTertiary)
                Spacer(Modifier.height(4.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                series.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { s ->
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Canvas(Modifier.size(7.dp)) { drawCircle(s.color) }
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = s.label,
                                    style = OsText.micro,
                                    color = c.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // 奇数项时补空位，否则最后一格会被拉伸成整行宽
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** [TimeSeriesChart] 的一个数据点：相对录制开始的毫秒偏移 + 数值 */
data class TimePoint(val elapsedMs: Long, val value: Float)

/**
 * 时间轴折线图（真实 X 轴，不是等距铺满）。
 *
 * 与 [LineChart] 的关键差别：**X 轴按时间比例映射，而不是把样本均匀铺满整幅宽度**。
 * 这一点对「耗电录制」是必需的——等距铺满时，录 2 分钟和录 2 小时的曲线长得一模一样，
 * 电量掉得快还是慢完全看不出来；只有把时间当真轴，斜率才有意义。
 *
 * Y 轴固定 0~100%（电量百分比），带刻度网格；X 轴按 [xMaxMs] 分档，默认给 3 枚刻度。
 * 首点处画一条虚线基准，便于一眼比出「掉了多少」。
 */
@Composable
fun TimeSeriesChart(
    points: List<TimePoint>,
    xMaxMs: Long,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 156.dp,
    yMax: Float = 100f,
    yTicks: List<Float> = listOf(0f, 25f, 50f, 75f, 100f),
    xTickCount: Int = 3,
    xLabel: (Long) -> String = { ms -> "%d:%02d".format(ms / 60_000, (ms / 1000) % 60) },
    yLabel: (Float) -> String = { "%.0f".format(it) },
) {
    val c = osColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = c.textTertiary,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
    )
    val density = LocalDensity.current

    Canvas(modifier.fillMaxWidth().height(height)) {
        val leftPad = with(density) { 26.dp.toPx() }
        val bottomPad = with(density) { 15.dp.toPx() }
        val topPad = with(density) { 4.dp.toPx() }
        val plotLeft = leftPad
        val plotRight = size.width
        val plotTop = topPad
        val plotBottom = size.height - bottomPad
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
        val safeX = xMaxMs.coerceAtLeast(1L).toFloat()
        val safeY = yMax.coerceAtLeast(0.001f)

        fun xOf(ms: Long): Float = plotLeft + plotW * (ms / safeX).coerceIn(0f, 1f)
        fun yOf(v: Float): Float = plotBottom - plotH * (v / safeY).coerceIn(0f, 1f)

        // Y 轴刻度 + 网格
        yTicks.forEach { t ->
            val y = yOf(t)
            drawLine(
                color = c.hairline,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1f,
            )
            val tm = measurer.measure(yLabel(t), labelStyle)
            drawText(
                textLayoutResult = tm,
                topLeft = Offset(
                    plotLeft - tm.size.width - with(density) { 5.dp.toPx() },
                    y - tm.size.height / 2f,
                ),
            )
        }

        // X 轴刻度
        repeat(xTickCount) { i ->
            val ms = xMaxMs * i / (xTickCount - 1).coerceAtLeast(1)
            val tm = measurer.measure(xLabel(ms), labelStyle)
            val tx = (xOf(ms) - tm.size.width / 2f)
                .coerceIn(plotLeft, (plotRight - tm.size.width).coerceAtLeast(plotLeft))
            drawText(
                textLayoutResult = tm,
                topLeft = Offset(tx, plotBottom + with(density) { 2.dp.toPx() }),
            )
        }

        if (points.isEmpty()) return@Canvas

        // 起点基准线：电量曲线几乎是一条缓降的斜线，没有基准就分不清
        // 「现在 94%」到底是刚掉下来还是从头就在这儿
        val baseY = yOf(points.first().value)
        drawLine(
            color = color.copy(alpha = 0.35f),
            start = Offset(plotLeft, baseY),
            end = Offset(plotRight, baseY),
            strokeWidth = with(density) { 1.dp.toPx() },
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(with(density) { 4.dp.toPx() }, with(density) { 4.dp.toPx() }),
            ),
        )

        if (points.size >= 2) {
            val area = Path().apply {
                moveTo(xOf(points.first().elapsedMs), plotBottom)
                points.forEach { lineTo(xOf(it.elapsedMs), yOf(it.value)) }
                lineTo(xOf(points.last().elapsedMs), plotBottom)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.02f)),
                    startY = plotTop,
                    endY = plotBottom,
                ),
            )
            val line = Path().apply {
                points.forEachIndexed { i, p ->
                    val x = xOf(p.elapsedMs)
                    val y = yOf(p.value)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                path = line,
                color = color,
                style = Stroke(
                    width = with(density) { 2.dp.toPx() },
                    join = StrokeJoin.Round,
                    cap = StrokeCap.Round,
                ),
            )
        }

        // 末端标记点
        drawCircle(
            color = color,
            radius = with(density) { 3.5.dp.toPx() },
            center = Offset(xOf(points.last().elapsedMs), yOf(points.last().value)),
        )
    }
}

/**
 * 时间轴上限：**默认 10 分钟，超出后按 5 分钟向上取整扩展**。
 *
 * 固定 10 分钟会让长录制的曲线全挤在右端；只按实际时长缩放又会让曲线
 * 永远铺满全宽——录 1 分钟和录 10 分钟看起来一样陡，反而丢了「跌落速度」。
 * 取整扩展能让轴在一段时间内保持稳定，曲线随时间自然向右生长。
 */
fun autoXMaxMs(durationMs: Long): Long {
    val base = 10 * 60_000L
    if (durationMs <= base) return base
    val step = 5 * 60_000L
    return ((durationMs / step) + 1) * step
}

/** [DualAxisChart] 的一条曲线：标签 + 颜色 + 数值序列 */
data class AxisSeries(
    val label: String,
    val color: Color,
    val values: List<Float>,
)

/**
 * 双 Y 轴叠图：左轴固定帧率，右轴可切换（温度 / 负载 / 电量）。
 *
 * ## 为什么要双轴
 *
 * 帧率是 0~120 的量、温度是 30~50 的量、负载是 0~100 的量。强行放到同一根轴上，
 * 温度曲线会被压成贴着底边的一条直线，完全看不出「温度上来了帧率就掉了」这种关联。
 * 双轴让两条曲线各占满自己的量程，**关联关系才读得出来**——这正是性能分析的核心。
 *
 * ## 左轴量程：贴到数据实际达到的面板挡位
 *
 * 量程钉在 60 / 90 / 120 / 144 / 165 / 185 这些**面板真实刷新率**上，
 * 「掉到一半」才在图上表现为掉到一半。
 *
 * 但**不是无脑向上取到 185**：早期实现每冲高一次就抬一档，120Hz 的机器
 * 只要瞬时有 121 就会被画成 144、再有 145 就变 165，纵轴顶部全是空的。
 * 现在由 [frameRateMax] 以面板上限封顶，120 面板就停在 120。
 *
 * 移植自 Scene5 Alpha 的 `FpsDataView`，但改用 Compose `Canvas` 纯绘制，
 * 数据由调用方传入——不在绘制函数里查数据库。
 */
@Composable
fun DualAxisChart(
    left: AxisSeries,
    right: AxisSeries?,
    xMaxMs: Long,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    leftUnit: String = "FPS",
    rightUnit: String = "",
    lowFpsThreshold: Float = 45f,
    /**
     * 面板标称刷新率上限，作为左轴的常驻参照（见 [frameRateMax]）。
     * 为 0 时只用数据峰值定轴。传它才能让「还没跑满」也看得出来。
     */
    leftPanelMax: Float = 0f,
    xLabel: (Long) -> String = { ms -> "%d:%02d".format(ms / 60_000, (ms / 1000) % 60) },
) {
    val c = osColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = c.textTertiary,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
    )
    val density = LocalDensity.current

    Canvas(modifier.fillMaxWidth().height(height)) {
        val leftPad = with(density) { 30.dp.toPx() }
        val rightPad = with(density) { 34.dp.toPx() }
        val bottomPad = with(density) { 15.dp.toPx() }
        val topPad = with(density) { 6.dp.toPx() }
        val plotLeft = leftPad
        val plotRight = size.width - rightPad
        val plotTop = topPad
        val plotBottom = size.height - bottomPad
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
        val safeX = xMaxMs.coerceAtLeast(1L).toFloat()

        // 左轴（帧率）：用与实时折线同一套规则 —— 面板上限封顶 + 峰值收窄。
        // 两条曲线（历史 / 实时）必须用同一个函数，否则同一台机器在两处画出不同的纵轴。
        val leftMax = frameRateMax(
            observedPeak = left.values.filter { it >= 0f }.maxOrNull() ?: 0f,
            panelMax = leftPanelMax,
        )
        // 右轴：温度给 30~60 的窄区间（否则曲线贴底），负载/电量给 0~100
        val rightRange = rightRangeOf(right?.values)
        val rightMax = rightRange.second
        val rightMin = rightRange.first

        fun xOfMs(ms: Long): Float = plotLeft + plotW * (ms / safeX).coerceIn(0f, 1f)
        fun yLeft(v: Float): Float = plotBottom - plotH * (v / leftMax).coerceIn(0f, 1f)
        fun yRight(v: Float): Float =
            plotBottom - plotH * ((v - rightMin) / (rightMax - rightMin).coerceAtLeast(0.001f))
                .coerceIn(0f, 1f)

        // ---- 网格：左侧帧率挡位刻度，虚线 ----
        val dash = PathEffect.dashPathEffect(
            floatArrayOf(with(density) { 3.dp.toPx() }, with(density) { 3.dp.toPx() }),
        )
        // 刻度标在真实面板挡位上（60/90/120/…），而不是把量程等分 5 份。
        // 等分出来的 33/66/99 对不上任何真实刷新率，用户没法拿它和屏幕对上号。
        val leftTicks = frameRateTicks(leftMax).filter { it > 0f }
        leftTicks.forEach { v ->
            val y = plotBottom - plotH * (v / leftMax).coerceIn(0f, 1f)
            drawLine(
                color = c.hairline,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1f,
                pathEffect = dash,
            )
            val tm = measurer.measure("%.0f".format(v), labelStyle)
            drawText(
                textLayoutResult = tm,
                topLeft = Offset(
                    (plotLeft - tm.size.width - with(density) { 4.dp.toPx() }).coerceAtLeast(0f),
                    (y - tm.size.height / 2f)
                        .coerceIn(plotTop, (plotBottom - tm.size.height).coerceAtLeast(plotTop)),
                ),
            )
        }

        // ---- 右侧刻度：每条曲线自己的量程 ----
        right?.let {
            repeat(5) { i ->
                val frac = i / 4f
                val v = rightMin + (rightMax - rightMin) * frac
                val tm = measurer.measure("%.0f".format(v), labelStyle)
                drawText(
                    textLayoutResult = tm,
                    topLeft = Offset(
                        plotRight + with(density) { 4.dp.toPx() },
                        (plotBottom - plotH * frac) - tm.size.height / 2f,
                    ),
                )
            }
        }

        // ---- 低帧参考线：一眼看出跌破阈值的时段 ----
        if (left.values.any { it > 0f } && lowFpsThreshold in 0f..leftMax) {
            val y = yLeft(lowFpsThreshold)
            drawLine(
                color = c.textTertiary.copy(alpha = 0.45f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = with(density) { 1.dp.toPx() },
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(with(density) { 5.dp.toPx() }, with(density) { 5.dp.toPx() }),
                ),
            )
        }

        // ---- X 轴刻度 ----
        repeat(3) { i ->
            val ms = xMaxMs * i / 2
            val tm = measurer.measure(xLabel(ms), labelStyle)
            val tx = (xOfMs(ms) - tm.size.width / 2f)
                .coerceIn(plotLeft, (plotRight - tm.size.width).coerceAtLeast(plotLeft))
            drawText(
                textLayoutResult = tm,
                topLeft = Offset(tx, plotBottom + with(density) { 2.dp.toPx() }),
            )
        }

        // ---- 曲线 ----
        // 右轴先画（在下层），左轴帧率后画（在上层）——帧率是主角
        right?.let { s ->
            drawSeries(
                values = s.values,
                color = s.color,
                count = s.values.size,
                xOf = { i -> plotLeft + plotW * (i.toFloat() / (s.values.size - 1).coerceAtLeast(1)) },
                yOf = ::yRight,
                densityScale = with(density) { 1.6.dp.toPx() },
            )
        }
        drawSeries(
            values = left.values,
            color = left.color,
            count = left.values.size,
            xOf = { i -> plotLeft + plotW * (i.toFloat() / (left.values.size - 1).coerceAtLeast(1)) },
            yOf = ::yLeft,
            densityScale = with(density) { 2.dp.toPx() },
        )
    }

    // ---- 图例 ----
    Spacer(Modifier.height(7.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendChip(left.label, left.color, leftUnit)
        right?.let { LegendChip(it.label, it.color, rightUnit) }
    }
}

@Composable
private fun LegendChip(label: String, color: Color, unit: String) {
    val c = osColors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(7.dp)) { drawCircle(color) }
        Spacer(Modifier.width(5.dp))
        Text(
            text = if (unit.isBlank()) label else "$label ($unit)",
            style = OsText.micro,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 画一条折线（含渐隐填充），X 按等距铺满 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    values: List<Float>,
    color: Color,
    count: Int,
    xOf: (Int) -> Float,
    yOf: (Float) -> Float,
    densityScale: Float,
) {
    if (count < 2) return
    val path = Path()
    var started = false
    values.forEachIndexed { i, v ->
        if (v < 0f) return@forEachIndexed
        val x = xOf(i)
        val y = yOf(v)
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
    }
    if (!started) return
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = densityScale, join = StrokeJoin.Round, cap = StrokeCap.Round),
    )
    // 末端标记
    val lastIdx = values.indexOfLast { it >= 0f }
    if (lastIdx >= 0) {
        drawCircle(color = color, radius = densityScale * 1.6f, center = Offset(xOf(lastIdx), yOf(values[lastIdx])))
    }
}

/**
 * 帧率轴顶：把峰值 [peak] 贴到真实面板挡位上。
 *
 * 单独抽出来是因为有两类调用方：实时折线手上只有「当前窗口峰值」这个数，
 * 拿不到完整序列，但需要**同一套**挡位规则，否则实时卡的轴顶会和历史图对不上。
 *
 * 注意本函数**只做贴档、不做封顶**。「不超过面板上限」由 [frameRateMax] 负责，
 * 两者分工不同：本函数回答「这个数落在哪一档」，上层回答「该用哪一档」。
 */
fun frameRateAxisMax(peak: Float): Float {
    val p = peak.coerceAtLeast(0f)
    // 峰值贴到的第一枚不小于它的挡位；留 0.5 的容差吸收浮点抖动，
    // 否则 119.999 会贴到 120，而 120.0001 又要贴到 144 —— 轴顶在临界点跳变
    val snapped = PANEL_REFRESH_LADDER.firstOrNull { p <= it + 0.5f }
    if (snapped != null) return snapped
    // 超过 185：按 10 的倍数上取整（187 → 190）
    return kotlin.math.ceil((p + 0.5f) / 10f) * 10f
}

/**
 * 面板刷新率挡位，从低到高。
 *
 * 这是用户唯一能和自己屏幕对上号的刻度集合 —— 等分刻度（165/5 = 33, 66, 99…）
 * 在帧率语境下读不出含义。
 */
private val PANEL_REFRESH_LADDER = listOf(60f, 90f, 120f, 144f, 165f, 185f)

/**
 * 把 `[0, axisMax]` 切成 [count] 档用于画 Y 轴刻度。
 *
 * 两个刻意的设计：
 *
 * 1. **返回原始浮点值，不是四舍五入后的整数**。`axisMax = 165`、`count = 5` 时
 *    档位是 0 / 41.25 / 82.5 / 123.75 / 165，标签会被四舍五入成 0 / 41 / 83 / 124 / 165，
 *    但曲线的 y 坐标必须用**原始值**去算——否则刻度线和曲线会错位。这里只负责出值，
 *    格式化交给调用方。
 * 2. **帧率量程改用面板挡位**（见 [frameRateTicks]），因为 165/5 = 33 这种等差档位
 *    在帧率语境下读不出含义；面板挡位是用户唯一能对上号的刻度。
 */
fun axisTicks(axisMax: Float, count: Int = 5): List<Float> {
    val n = count.coerceAtLeast(2)
    val max = axisMax.coerceAtLeast(0.001f)
    return List(n) { i -> max * i / (n - 1) }
}

/**
 * 帧率轴的取整档位：把当前量程 [axisMax] 按面板刷新率切档。
 *
 * 从高到低取第一枚 `<= axisMax` 的挡位，再兜底 0，得到形如
 * `165 → [0, 60, 90, 120, 144, 165]`、`120 → [0, 60, 90, 120]`、`60 → [0, 60]` 的刻度。
 *
 * 为什么不用等分：165Hz 面板上等分出 33/66/99/132 四枚刻度，用户没法把它们
 * 和自己屏幕上有哪些挡位对上；60/90/120/144 才是真实存在的刷新率，看一眼就知道
 * 「曲线贴着 120 那条线」意味着什么。
 *
 * **轴顶那一枚要标出来**（用 `<=` 而不是 `<`）：[frameRateAxisMax] 产出的轴顶
 * 本身就是挡位，如果刻度只取到下一枚（120 → 显示 90 封顶），顶部留白会被误读成
 * 「还有一段没画」。标出轴顶这枚，用户才能确认「这台机器的上限就是 120」。
 * 非挡位轴顶（>185 的取整值）不会命中任何刻度，此时的留白由 [axisTicks] 兜底情形处理。
 */
fun frameRateTicks(axisMax: Float): List<Float> {
    val ticks = PANEL_REFRESH_LADDER.filter { it <= axisMax + 0.5f }
    return listOf(0f) + ticks
}

/**
 * 右轴量程。
 *
 * 温度必须走窄区间（30~60），否则 0~100 的量程会把 38~45 的波动压成一条直线，
 * 「温度上来了」这个关键现象就看不见了。
 */
fun rightRangeOf(values: List<Float>?): Pair<Float, Float> {
    val vals = values?.filter { it >= 0f } ?: emptyList()
    if (vals.isEmpty()) return 0f to 100f
    val max = vals.max()
    val min = vals.min()
    return when {
        // 温度：夹到 30~60，并对实际范围留边
        max <= 70f -> (min - 3f).coerceAtLeast(20f) to (max + 3f).coerceAtMost(70f)
        // 其余（负载 / 电量 / 频率）按 0~100 或实际上限
        else -> 0f to max.coerceAtLeast(100f)
    }
}

/**
 * 当前屏幕的面板刷新率上限（Hz）。
 *
 * 取的是**同分辨率模式里最高的那个刷新率**，与 MainActivity 请求
 * `preferredDisplayModeId` 的挑法一致 —— 这样图上的「面板上限」和系统实际
 * 被要求跑的模式对得上。
 *
 * 用途：帧率折线的常驻参照。峰值还没跑到上限时，纵轴也至少到面板上限，
 * 用户才能看出「离满帧还差多少」；一旦峰值越过上限（有些机型会超发），
 * 轴顶再跟着数据走。
 *
 * 读不到时返回 0，调用方按「无参照」处理。
 */
@Composable
fun panelRefreshHz(): Float {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return@runCatching 0f
            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                wm.defaultDisplay
            } ?: return@runCatching 0f
            val modes = display.supportedModes
            if (modes.isEmpty()) return@runCatching 0f
            val w = display.mode.physicalWidth
            val h = display.mode.physicalHeight
            modes.filter { it.physicalWidth == w && it.physicalHeight == h }
                .maxOfOrNull { it.refreshRate }
                ?.coerceIn(0f, 240f)
                ?: 0f
        }.getOrDefault(0f)
    }
}

/**
 * 帧率折线的纵轴上限：**以面板上限封顶**，并在其内跟着数据峰值走。
 *
 * ## 为什么要用面板上限封顶（这是「Y 轴太短」的正解）
 *
 * 先前的实现用 `autoMax(floor = 60)`，它在 120 面板上会算出轴顶 **200**
 * （`base ≤ 1000 → step = 100 → ceil(120/100)*100`），165 的机器同样画到 200 ——
 * 曲线被压在下面 60% 里，正是用户反馈的「Y 轴太短」。
 *
 * 换成本函数后：
 *
 * - 120Hz 面板、峰值 118~120 → 轴顶 **120**；
 * - 165Hz 面板、峰值 160~165 → 轴顶 **165**；
 * - 60Hz 面板、峰值 58 → 轴顶 **60**。
 *
 * **瞬时超发不外扩**：有些机型会短暂报到面板之上（如 120 屏出现 121），
 * 若按峰值贴档就会跳到 144 并让后续每次都画 144。既然屏幕物理上不可能长期
 * 超过面板，超出的部分按封顶处理即可 —— 曲线会在顶边被裁平，这比整个纵轴
 * 被撑高一档更有信息量（「跑满了」一眼可见）。
 *
 * @param observedPeak 当前窗口的帧率峰值
 * @param panelMax 面板标称上限（取自 display.refreshRate）。为 0 或不可读时
 *   退化为「纯按峰值贴档」，此时仍不会超过峰值所在的那一档。
 */
fun frameRateMax(observedPeak: Float, panelMax: Float): Float {
    val panel = if (panelMax.isFinite() && panelMax in 1f..240f) panelMax else 0f
    val peak = observedPeak.coerceAtLeast(0f)
    // 面板可读：轴顶钉在面板挡位上，峰值只用于在面板内往下收窄
    if (panel > 0f) {
        val panelSnapped = frameRateAxisMax(panel)
        // 峰值明显低于面板（例如 120 面板只跑 60）时，收窄到峰值那一档，
        // 让波动占满整个高度；否则维持面板上限，保留「离满帧还差多少」的参照。
        // 阈值用 0.75：低到只剩四分之三时才收窄，避免日常小幅波动导致轴顶反复跳变。
        val peakSnapped = frameRateAxisMax(peak)
        return if (peakSnapped < panelSnapped && peak < panelSnapped * 0.75f) {
            peakSnapped
        } else {
            panelSnapped
        }
    }
    return frameRateAxisMax(peak)
}
