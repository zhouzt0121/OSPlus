package com.osplus.tools.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分组卡片：卡片外的小标题 + 数据卡片本体。
 * 标题放在卡片外，避免与卡片内的首行内容争夺视觉层级。
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
            )
        }
        GlassCard { content() }
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = labelColor ?: MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = if (emphasis) MiuixTheme.textStyles.body2 else MiuixTheme.textStyles.footnote1,
            color = valueColor
                ?: if (emphasis) MiuixTheme.colorScheme.onBackground
                else MiuixTheme.colorScheme.onBackgroundVariant,
            fontWeight = if (emphasis) FontWeight.Medium else FontWeight.Normal,
        )
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackground,
            )
            summary?.let {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
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
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MiuixTheme.textStyles.title4,
                color = accent ?: MiuixTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        hint?.let {
            Text(
                text = it,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1,
            )
        }
    }
}

/** 横向占用条 */
@Composable
fun UsageBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 6.dp,
) {
    val shape = RoundedCornerShape(barHeight / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.07f), shape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(barHeight)
                .background(
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = 0.85f), color)
                    ),
                    shape,
                )
        )
    }
}

/**
 * 分段控件（Segmented Control）。
 *
 * 比 Miuix TabRow 更贴合本应用的视觉语言：胶囊容器内滑动高亮，
 * 选中项使用品牌色文字，未选中项使用次级文字色。
 */
@Composable
fun SegmentedTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MiuixTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .softCard(RoundedCornerShape(15.dp), elevation = 1.dp)
            .padding(4.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val bg by androidx.compose.animation.animateColorAsState(
                    targetValue = if (selected) primary.copy(alpha = 0.14f) else Color.Transparent,
                    label = "segBg",
                )
                val fg by androidx.compose.animation.animateColorAsState(
                    targetValue = if (selected) primary else MiuixTheme.colorScheme.onBackgroundVariant,
                    label = "segFg",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(bg)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = fg,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 提示条：用于展示权限缺失等状态 */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFFF9F0A),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                accent.copy(alpha = 0.13f),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackground,
        )
    }
}
