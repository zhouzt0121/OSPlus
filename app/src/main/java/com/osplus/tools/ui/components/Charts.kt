package com.osplus.tools.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * 圆环进度图。
 *
 * 参考图的核心视觉元素：粗圆头圆环 + 居中标签，
 * 用极简的几何形状表达一个百分比，比数字堆叠更易读。
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

/**
 * 圆角柱状图。
 *
 * 只保留一根基线，不画网格与轨道——参考图的图表语言靠柱体本身的高低对比表达，
 * 多余的装饰线会在小尺寸下变成噪声。
 *
 * @param values 由旧到新的数值，长度决定柱数
 */
@Composable
fun BarChart(
    values: List<Float>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    slots: Int = 5,
    showLabels: Boolean = true,
    barRatio: Float = 0.30f,
    labelFormatter: (Float) -> String = { "%.0f".format(it) },
) {
    val c = osColors()
    val safeMax = maxValue.coerceAtLeast(0.001f)
    val data = remember(values, slots) {
        if (values.size >= slots) values.takeLast(slots)
        else List(slots - values.size) { 0f } + values
    }
    val animated = data.map { v ->
        val f = (v / safeMax).coerceIn(0f, 1f)
        val a by animateFloatAsState(f, tween(420), label = "bar")
        a
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = c.textTertiary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    val density = LocalDensity.current

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val slotW = size.width / slots
        // 柱宽上限收窄：宽卡片上 5 根柱若按比例铺满会退化成圆角方块，
        // 限制绝对宽度后柱体才有"细高"的柱状观感。
        val barW = minOf(slotW * barRatio, with(density) { 12.dp.toPx() })
        val minBarH = with(density) { 4.dp.toPx() }
        val radius = CornerRadius(barW / 2f, barW / 2f)
        val labelH = if (showLabels) with(density) { 14.dp.toPx() } else 0f
        val chartTop = labelH
        val chartBottom = size.height
        val chartH = (chartBottom - chartTop).coerceAtLeast(1f)

        // 基线
        drawLine(
            color = c.hairline,
            start = Offset(0f, chartBottom - 0.5f),
            end = Offset(size.width, chartBottom - 0.5f),
            strokeWidth = 1f,
        )

        data.forEachIndexed { i, v ->
            val left = slotW * i + (slotW - barW) / 2f
            val centerX = left + barW / 2f
            val f = animated.getOrElse(i) { 0f }
            val h = (chartH * f).coerceAtLeast(minBarH)
            val top = chartBottom - h

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(color, color.copy(alpha = 0.62f)),
                    startY = top,
                    endY = chartBottom,
                ),
                topLeft = Offset(left, top),
                size = Size(barW, h),
                cornerRadius = radius,
            )

            if (showLabels) {
                val measured = measurer.measure(labelFormatter(v), labelStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(centerX - measured.size.width / 2f, 0f),
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
 */
@Composable
fun LineChart(
    values: List<Float>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 68.dp,
    valueFormatter: (Float) -> String = { "%.0f".format(it) },
) {
    val c = osColors()
    val safeMax = maxValue.coerceAtLeast(0.001f)
    val data = remember(values) { if (values.isEmpty()) listOf(0f) else values }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = c.textTertiary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val density = LocalDensity.current

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val labelH = with(density) { 14.dp.toPx() }
        val chartTop = labelH
        val chartBottom = size.height
        val chartH = (chartBottom - chartTop).coerceAtLeast(1f)
        val stepX = if (data.size > 1) size.width / (data.size - 1) else size.width

        fun yOf(v: Float): Float {
            val f = (v / safeMax).coerceIn(0f, 1f)
            return chartBottom - chartH * f
        }

        // 基线
        drawLine(
            color = c.hairline,
            start = Offset(0f, chartBottom - 0.5f),
            end = Offset(size.width, chartBottom - 0.5f),
            strokeWidth = 1f,
        )

        // 面积填充
        val area = Path().apply {
            moveTo(0f, chartBottom)
            data.forEachIndexed { i, v -> lineTo(stepX * i, yOf(v)) }
            lineTo(stepX * (data.size - 1), chartBottom)
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
                val x = stepX * i
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

        // 顶部标注：只标峰值，当前值由卡片头部承担，避免同一数字出现两次
        val peak = data.maxOrNull() ?: 0f
        val pm = measurer.measure("峰值 " + valueFormatter(peak), labelStyle)
        drawText(textLayoutResult = pm, topLeft = Offset(0f, 0f))
    }
}

/**
 * 指标卡片：标题 + 当前值 + 柱状图/折线图 + 时间轴。
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
    /** 柱数：默认 60（长趋势窗口按此数量降采样后绘制） */
    slots: Int = 60,
    /** 时间轴左端文案；调用方按实际窗口长度传入 [axisSpanLabel] 的结果 */
    axisStartLabel: String = "最早",
    /** true 时改用折线趋势图（长窗口观察波动更直观） */
    line: Boolean = false,
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
        if (line) {
            LineChart(
                values = values,
                maxValue = maxValue,
                color = color,
                valueFormatter = valueFormatter,
            )
        } else {
            BarChart(
                values = values,
                maxValue = maxValue,
                color = color,
                slots = slots,
                labelFormatter = valueFormatter,
            )
        }
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
@Composable
fun RowScope.MiniBars(
    values: List<Float>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    height: Dp = 20.dp,
) {
    val safeMax = maxValue.coerceAtLeast(0.001f)
    val data = if (values.isEmpty()) List(5) { 0f } else values.takeLast(5)
    Canvas(modifier = modifier.width(width).height(height)) {
        val n = 5
        val slotW = size.width / n
        val barW = slotW * 0.5f
        data.forEachIndexed { i, v ->
            val f = (v / safeMax).coerceIn(0f, 1f)
            val left = slotW * i + (slotW - barW) / 2f
            val h = (size.height * f).coerceAtLeast(barW)
            drawRoundRect(
                color = color.copy(alpha = 0.9f),
                topLeft = Offset(left, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}
