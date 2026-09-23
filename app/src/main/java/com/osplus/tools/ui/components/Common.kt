package com.osplus.tools.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text

/**
 * 单选胶囊：选中状态用「底色 + 描边 + 文字色」整体变化表达，而不是在文案前打勾。
 *
 * 用于调速器、核心选择这类「一组互斥选项」。打勾会改变文案宽度，
 * 导致同一行的胶囊在选中/取消时宽度跳动；改成变色后文案始终不变，行宽稳定，
 * 选中项也更容易一眼扫到。
 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = osColors()
    val shape = RoundedCornerShape(12.dp)
    val background by animateColorAsState(
        targetValue = if (selected) c.primary.copy(alpha = if (c.isDark) 0.26f else 0.13f) else c.cardAlt,
        label = "chipBackground",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) c.primary.copy(alpha = 0.62f) else c.hairline,
        label = "chipStroke",
    )
    val content by animateColorAsState(
        targetValue = if (selected) c.primary else c.textSecondary,
        label = "chipContent",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(if (selected) 1.2.dp else 0.7.dp, stroke, shape)
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = OsText.label,
            color = content,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 分组卡片：可选的小节标题 + 卡片本体。
 *
 * [title] 传 null 时完全不渲染标题行 —— 概览页的卡片内部已有「内存 / GPU / CPU / 电池」
 * 圆环标签，再叠一行同名标题属于重复信息，去掉后卡片直接承担全部语义。
 *
 * 传入 [onClick] 表示该卡片可下钻到详情页；卡片本身不画任何角标。
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = osColors()
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 5.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = OsText.sectionTitle,
                    color = c.textTertiary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        OsCard(onClick = onClick) { content() }
    }
}

/** 左标签右数值的信息行 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    labelColor: Color? = null,
    emphasis: Boolean = false,
) {
    val c = osColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = OsText.label,
            color = labelColor ?: c.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = if (emphasis) OsText.valueStrong else OsText.value,
            color = valueColor ?: if (emphasis) c.textPrimary else c.textSecondary,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/**
 * 进度行：标签 + 数值 + 圆角进度条。
 * 对应参考图中「物理内存 / 交换分区」那一类的行式用量展示。
 */
@Composable
fun ProgressRow(
    label: String,
    value: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 7.dp,
) {
    val c = osColors()
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = OsText.label,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                style = OsText.value,
                color = c.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        UsageBar(fraction = fraction, color = color, barHeight = barHeight)
    }
}

/** 带开关的设置行 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val c = osColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = OsText.label,
                color = c.textPrimary,
            )
            summary?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = OsText.caption,
                    color = c.textSecondary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 指标磁贴：小标签 + 大数值 + 单位 + 说明 */
@Composable
fun StatTile(
    label: String,
    value: String,
    unit: String = "",
    accent: Color? = null,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val c = osColors()
    Column(modifier = modifier.padding(vertical = 5.dp)) {
        Text(
            text = label,
            style = OsText.caption,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = OsText.metric,
                color = accent ?: c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = OsText.caption,
                    color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        hint?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = it,
                style = OsText.micro,
                color = c.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 横向占用条：圆角轨道 + 圆角填充 */
@Composable
fun UsageBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 7.dp,
) {
    val c = osColors()
    val shape = RoundedCornerShape(barHeight / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(c.track, shape)
    ) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(f)
                    .height(barHeight)
                    .background(
                        Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.72f), color)
                        ),
                        shape,
                    )
            )
        }
    }
}

/**
 * 分段控件。
 *
 * 浅色凹槽 + 白色滑块，选中项用品牌色文字；
 * 比 Miuix TabRow 更贴合本应用的视觉语言，也与卡片系统同源。
 */
@Composable
fun SegmentedTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = osColors()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(c.track)
            .padding(3.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val bg by animateColorAsState(
                    targetValue = if (selected) c.cardElevated else Color.Transparent,
                    label = "segBg",
                )
                val fg by animateColorAsState(
                    targetValue = if (selected) c.primary else c.textSecondary,
                    label = "segFg",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = OsText.label,
                        color = fg,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 提示条：用于展示权限缺失、写入未生效等状态 */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFF0A868),
) {
    val c = osColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = if (c.isDark) 0.16f else 0.13f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(6.dp)
                .height(6.dp)
                .background(accent, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            style = OsText.caption,
            color = c.textPrimary,
        )
    }
}
