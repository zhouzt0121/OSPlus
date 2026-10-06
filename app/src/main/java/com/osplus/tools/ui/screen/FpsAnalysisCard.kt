package com.osplus.tools.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osplus.tools.core.FpsSessionStats
import com.osplus.tools.model.FpsSession
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Text

/**
 * 录制记录分析卡片（固定 4:3）。
 *
 * ## 版式来源
 *
 * 对标 Scene5 / PerfDog 的单局分析卡：顶部一行「日期时间 / 应用名(版本) /
 * 裁剪尺寸」，下面两组四列统计格，每格是**数值在上、标签在下**的两段式
 * （标签还带一行单位）。具体：
 *
 * ```
 * 2026/2/7 16:56      星塔旅人(1.4.0)      crop: 540x1200
 * ─────────────────────────────────────────────────────
 *  MAX     MIN     AVG    VARIANCE
 *  60.7    10      55.7   68.5
 *  FPS     FPS     FPS    FPS
 * ─────────────────────────────────────────────────────
 *  ≥60FPS  5% Low  MAX    AVG
 *  90.80%  27.8    23     0
 *  Smoothness FPS Temperature Power(W)
 * ```
 *
 * ## 为什么固定 4:3
 *
 * 这是一个**要被截图分享/归档**的卡片，固定长宽比才能保证：不论录了 10 秒
 * 还是 2 小时、不论数据量多少，导出的图尺寸稳定、双方字形一致，便于横向
 * 对比与拼图。用 `aspectRatio(4f/3f)` 让高度由宽度推导，内部的统计格用
 * `weight(1f)` 平分宽度，任何宽度下都保持四列等分不塌陷。
 *
 * @param session 会话元数据（时间、应用名、时长）
 * @param stats 统计摘要（由 [com.osplus.tools.core.FpsWatchStore.statsOf] 算出）
 * @param cropLabel 裁剪尺寸标签，形如 `crop: 540x1200`；null 时不显示
 * @param modifier 外部尺寸约束（宽度决定整体大小）
 */
@Composable
fun FpsAnalysisCard(
    session: FpsSession,
    stats: FpsSessionStats,
    cropLabel: String? = null,
    /** 应用图标；为 null 时退回首字母占位块 */
    icon: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** 解析好的应用显示名（优先于 session.label / 包名） */
    appName: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = osColors()
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = modifier
            .aspectRatio(4f / 3f)
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.hairline, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        // ---- 顶栏：日期时间 / 应用名(版本) / 裁剪尺寸 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = fmtCardDate(session.timeBegin),
                style = OsText.label,
                color = c.textPrimary,
                modifier = Modifier.weight(1.05f),
                maxLines = 1,
            )
            // 中间：图标 + 应用名。图标与应用名成组居中，比纯文字更容易认。
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val name = appName?.ifBlank { null } ?: session.cardTitle()
                Box(
                    Modifier
                        .width(16.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        androidx.compose.foundation.Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier
                                .width(16.dp)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(c.cardAlt),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = name.take(1).uppercase(),
                                style = OsText.micro,
                                color = c.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text = name,
                    style = OsText.label,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = cropLabel ?: "",
                style = OsText.micro,
                color = c.textSecondary,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(0.95f),
            )
        }

        Spacer(Modifier.height(10.dp))
        Hairline(c)
        Spacer(Modifier.height(10.dp))

        // ---- 组 1：MAX / MIN / AVG / VARIANCE（单位 FPS）----
        StatRow(
            values = listOf(
                "%.1f".format(stats.max),
                "%.0f".format(stats.min),
                "%.1f".format(stats.avg),
                "%.1f".format(stats.variance),
            ),
            labels = listOf("MAX", "MIN", "AVG", "VARIANCE"),
            unit = "FPS",
            valueColor = c.textPrimary,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.height(6.dp))
        Hairline(c)
        Spacer(Modifier.height(10.dp))

        // ---- 组 2：≥60FPS / 5% Low / AVG / AVG（流畅度 · 帧率 · 温度 · 功耗）----
        // 第二组的四个格子量纲各不相同，因此逐格给单位。
        // 标签统一用「指标名（值语义）」的形式：第 1 格是占比、第 2 格是
        // 低帧分位、第 3/4 格都是**均值**——避免出现「温度那格写 MAX、
        // 功耗那格写 AVG」这类对不上数据的标签。
        StatRow(
            values = listOf(
                "%.2f%%".format(stats.smoothRatio),
                "%.1f".format(stats.fivePercentLow),
                "%.1f".format(stats.avgTempC),
                "%.0f".format(stats.avgPowerMw / 1000f),
            ),
            labels = listOf("≥60FPS", "5% Low AVG", "Temp AVG", "Power AVG"),
            units = listOf("Smoothness", "FPS", "℃", "W"),
            valueColor = c.textPrimary,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.height(6.dp))
        Hairline(c)
        Spacer(Modifier.height(8.dp))

        // ---- 底栏：样本量与时长，补足「这张卡代表多长的记录」----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "样本 ${stats.count}",
                style = OsText.micro,
                color = c.textTertiary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (session.durationMs > 0) fmtElapsed(session.durationMs) else "未正常结束",
                style = OsText.micro,
                color = c.textTertiary,
                modifier = Modifier.weight(1f),
            )
            // 右下角放一个品牌色的小圆点 + 产品名，作为「这张卡出自 OSPlus」的署名。
            Box(
                Modifier
                    .width(6.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(c.primary),
            )
            Spacer(Modifier.width(5.dp))
            Text(text = "OSPlus", style = OsText.micro, color = c.textTertiary)
        }
    }
}

/**
 * 一组四列统计格。
 *
 * 每格竖排三段：**数值 → 标签 → 单位**（与示例卡片一致）。
 * 数字用等宽感的 SemiBold，标签/单位用小号灰，形成「大数字小注解」的
 * 信息层级，扫一眼就能读到关键指标。
 *
 * @param units 每格独立的单位行；给 null 时所有格共用 [unit]
 */
@Composable
private fun StatRow(
    values: List<String>,
    labels: List<String>,
    unit: String? = null,
    units: List<String>? = null,
    valueColor: Color,
    modifier: Modifier = Modifier,
    valueSizeSp: Float = 22f,
) {
    val c = osColors()
    Row(modifier = modifier.fillMaxWidth()) {
        values.forEachIndexed { i, v ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = v,
                    fontSize = valueSizeSp.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = valueColor,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = labels.getOrElse(i) { "" },
                    style = OsText.micro,
                    fontSize = 10.sp,
                    color = c.textSecondary,
                    maxLines = 1,
                )
                Text(
                    text = units?.getOrNull(i) ?: unit.orEmpty(),
                    style = OsText.micro,
                    color = c.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 组间细分隔线，用渐变让两端淡出，比实线更轻。 */
@Composable
private fun Hairline(c: com.osplus.tools.ui.theme.OsColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, c.hairline, c.hairline, Color.Transparent),
                ),
            ),
    )
}

/**
 * 卡片顶栏的应用标题：`应用名(版本)`。
 *
 * [FpsSession.label] 是 UI 层解析好的显示名；拿不到时退回包名，
 * 再拿不到就显示「未知应用」——总之不能留空，否则顶栏看起来像排版错位。
 */
private fun FpsSession.cardTitle(): String {
    val name = label.ifBlank { packageName ?: "" }
    return name.ifBlank { "未知应用" }
}

/** 卡片日期格式 `yyyy/M/d HH:mm`，与示例卡片的 `2026/2/7 16:56` 一致。 */
private fun fmtCardDate(ms: Long): String =
    if (ms <= 0L) "未知时间"
    else java.text.SimpleDateFormat("yyyy/M/d HH:mm", java.util.Locale.US).format(java.util.Date(ms))

/**
 * 顶栏右侧的裁剪尺寸标签，形如 `crop: 540x1200`。
 *
 * 取的是**屏幕实际分辨率**（`displayMetrics.widthPixels x heightPixels`），
 * 而不是渲染缓冲尺寸。理由：这张卡是给用户看/分享的，用户关心的是
 * 「我用多大的屏录的」，屏幕分辨率最直观；Scene5 的 crop 也是这个语义。
 * 注意 Android 返回的是当前方向下的像素（竖屏就是窄边 x 长边）。
 */
internal fun screenCropLabel(context: android.content.Context): String {
    val m = context.resources.displayMetrics
    return "${m.widthPixels}x${m.heightPixels}"
}

/**
 * 毫秒时长 → 时钟样式。`mm:ss`，超过 1 小时用 `h:mm:ss`。
 *
 * 与 FpsScreen.kt 里的同名函数各留一份：那边是 `private`（文件级可见性），
 * 无法跨文件import；而两边都需要这个格式化，与其把它提成公共工具类引发
 * 额外耦合，不如各留一份——实现只有 6 行且语义完全稳定。
 */
private fun fmtElapsed(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
