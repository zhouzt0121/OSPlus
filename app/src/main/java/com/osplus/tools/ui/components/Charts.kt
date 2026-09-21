package com.osplus.tools.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
 * 折线趋势图。
 *
 * 用于观察长窗口下的波动：柱状图在几十个采样点时只能看出高低，
 * 折线能直观呈现起伏、持续低帧区间与回升过程。
 * 含渐变面积填充、虚线网格、极值与当前值标注。
 */
@Composable
fun LineChart(
    values: List<Float>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    valueFormatter: (Float) -> String = { "%.0f".format(it) },
) {
    val safeMax = maxValue.coerceAtLeast(0.001f)
    val data = remember(values) { if (values.isEmpty()) listOf(0f) else values }
    val measurer = rememberTextMeasurer()
    val onVar = MiuixTheme.colorScheme.onBackgroundVariant
    val gridColor = MiuixTheme.colorScheme.onBackground.copy(alpha = GRID_ALPHA)
    val labelStyle = TextStyle(color = onVar, fontSize = 10.sp, fontWeight = FontWeight.Medium)
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

        // 虚线网格
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
        listOf(0.25f, 0.5f, 0.75f).forEach { p ->
            val y = chartTop + chartH * p
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = dash)
        }
        // 基线
        drawLine(
            gridColor,
            Offset(0f, chartBottom - 1f),
            Offset(size.width, chartBottom - 1f),
            1.5f,
        )

        // 面积填充
        val area = Path().apply {
            moveTo(0f, chartBottom)
            data.forEachIndexed { i, v ->
                lineTo(stepX * i, yOf(v))
            }
            lineTo(stepX * (data.size - 1), chartBottom)
            close()
        }
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.02f)),
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
            style = Stroke(width = with(density) { 2.dp.toPx() }, join = StrokeJoin.Round, cap = StrokeCap.Round),
        )

        // 末端标记点
        val lastY = yOf(data.last())
        drawCircle(color = color, radius = with(density) { 3.5.dp.toPx() }, center = Offset(size.width, lastY))

        // 顶部标注：最大值 与 当前值
        val peak = data.maxOrNull() ?: 0f
        val peakText = "峰值 " + valueFormatter(peak)
        val pm = measurer.measure(peakText, labelStyle)
        drawText(textLayoutResult = pm, topLeft = Offset(0f, 0f))
        val curText = valueFormatter(data.last())
        val cm = measurer.measure(curText, labelStyle)
        drawText(
            textLayoutResult = cm,
            topLeft = Offset((size.width - cm.size.width).coerceAtLeast(0f), 0f),
        )
    }
}

/** 统一图表配色 */
object ChartColors {
    val cpu = Color(0xFF0A84FF)
    val gpu = Color(0xFF30D158)
    val mem = Color(0xFFFF9F0A)
    val power = Color(0xFFFF375F)
    val fps = Color(0xFFBF5AF2)
    val temp = Color(0xFFFF6482)
    val zram = Color(0xFF64D2FF)
}

private const val TRACK_ALPHA = 0.04f
private const val GRID_ALPHA = 0.10f

/**
 * 圆角柱状图。
 *
 * 等分槽位内居中绘制细柱；柱底带轨道体现量程；顶部渐变填充；
 * 柱顶悬浮数值标签；带虚线网格与基线。
 *
 * @param values 由旧到新的数值，长度决定柱数
 */
@Composable
fun BarChart(
    values: List<Float>,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    slots: Int = 5,
    showLabels: Boolean = true,
    labelFormatter: (Float) -> String = { "%.0f".format(it) },
) {
    val safeMax = maxValue.coerceAtLeast(0.001f)
    val data = remember(values, slots) {
        if (values.size >= slots) values.takeLast(slots)
        else List(slots - values.size) { 0f } + values
    }
    val fractions = data.map { (it / safeMax).coerceIn(0f, 1f) }
    val animated = fractions.map { f ->
        val a by animateFloatAsState(f, label = "bar")
        a
    }

    val measurer = rememberTextMeasurer()
    val labelColor = MiuixTheme.colorScheme.onBackgroundVariant
    val trackColor = MiuixTheme.colorScheme.onBackground.copy(alpha = TRACK_ALPHA)
    val gridColor = MiuixTheme.colorScheme.onBackground.copy(alpha = GRID_ALPHA)
    val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val density = LocalDensity.current

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val slotW = size.width / slots
        val barW = minOf(slotW * 0.30f, with(density) { 13.dp.toPx() })
        val minBarH = with(density) { 3.dp.toPx() }
        val radius = CornerRadius(barW / 2f, barW / 2f)
        val labelH = if (showLabels) with(density) { 15.dp.toPx() } else 0f
        val chartTop = labelH
        val chartBottom = size.height
        val chartH = (chartBottom - chartTop).coerceAtLeast(1f)

        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
        listOf(0.25f, 0.5f, 0.75f).forEach { p ->
            val y = chartTop + chartH * p
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
                pathEffect = dash,
            )
        }
        drawLine(
            color = gridColor,
            start = Offset(0f, chartBottom - 1f),
            end = Offset(size.width, chartBottom - 1f),
            strokeWidth = 1.5f,
        )

        data.forEachIndexed { i, v ->
            val left = slotW * i + (slotW - barW) / 2f
            val centerX = left + barW / 2f
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(left, chartTop),
                size = Size(barW, chartH),
                cornerRadius = radius,
            )
            val f = animated.getOrElse(i) { 0f }
            val h = (chartH * f).coerceAtLeast(minBarH)
            val top = chartBottom - h
            if (f > 0.001f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(color, color.copy(alpha = 0.55f)),
                        startY = top,
                        endY = chartBottom,
                    ),
                    topLeft = Offset(left, top),
                    size = Size(barW, h),
                    cornerRadius = radius,
                )
            }
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
 * 指标卡片：标题 + 当前值 + 5 秒柱状图 + 时间轴。
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
    /** 柱数：默认 5（对应 5 秒实时窗口），长窗口分析需显式传入 */
    slots: Int = 5,
    /** 时间轴左端文案，随窗口长度变化 */
    axisStartLabel: String = "5 秒前",
    /** true 时改用折线趋势图（长窗口观察波动更直观） */
    line: Boolean = false,
) {
    val current = values.lastOrNull() ?: 0f
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = valueFormatter(current),
                    style = MiuixTheme.textStyles.title4,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = unit,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
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
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = axisStartLabel,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                Text(
                    text = "现在",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
    }
}

/**
 * 每核一柱：柱高为占用率，柱底轨道体现量程，柱顶标注频率，柱下标注核心编号。
 */
@Composable
fun CoreBarsChart(
    coreIndexes: List<Int>,
    loads: List<Float>,
    freqs: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 86.dp,
) {
    if (coreIndexes.isEmpty()) {
        Box(modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "未读取到 CPU 核心",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val onVar = MiuixTheme.colorScheme.onBackgroundVariant
    val trackColor = MiuixTheme.colorScheme.onBackground.copy(alpha = TRACK_ALPHA)
    val labelStyle = TextStyle(color = onVar, fontSize = 9.sp)
    val indexStyle = TextStyle(color = onVar, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    val density = LocalDensity.current

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = coreIndexes.size
            val slotW = size.width / n
            val barW = minOf(slotW * 0.38f, with(density) { 16.dp.toPx() })
            val minBarH = with(density) { 3.dp.toPx() }
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

                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, chartTop),
                    size = Size(barW, chartH),
                    cornerRadius = radius,
                )

                val barColor = when {
                    load >= 80f -> Color(0xFFFF453A)
                    load >= 50f -> ChartColors.mem
                    load >= 20f -> ChartColors.cpu
                    else -> ChartColors.gpu
                }
                val h = (chartH * (load / 100f)).coerceAtLeast(minBarH)
                val top = chartBottom - h
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(barColor, barColor.copy(alpha = 0.5f)),
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
            text = "柱顶为当前频率 (MHz)，柱下为核心编号；颜色越红表示占用越高",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
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
