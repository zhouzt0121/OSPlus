package com.osplus.tools.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设计令牌：全套 UI 的唯一配色来源。
 *
 * 视觉基调为「浅灰底 + 白卡片 + 圆环/圆角条」的清爽仪表盘语言：
 * 不用重投影与浓渐变，靠留白、圆角与克制的品牌色建立层级，
 * 让密集的数据本身成为视觉主体。
 */
data class OsColors(
    /** 页面底色 */
    val background: Color,
    /** 卡片表面 */
    val card: Color,
    /** 卡片内的次级填充（瓦片、分段控件槽） */
    val cardAlt: Color,
    /** 抬升表面（分段控件选中态、悬浮元素） */
    val cardElevated: Color,
    /** 发丝分隔线 / 卡片描边 */
    val hairline: Color,
    /** 进度条、圆环的未填充轨道 */
    val track: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** 品牌主色 */
    val primary: Color,
    /** 品牌色的浅色变体（柱状图、次要进度条） */
    val primarySoft: Color,
    val orange: Color,
    val orangeSoft: Color,
    val green: Color,
    val red: Color,
    val purple: Color,
    val cyan: Color,
    val isDark: Boolean,
)

private val LightColors = OsColors(
    background = Color(0xFFF1F3F6),
    card = Color(0xFFFFFFFF),
    cardAlt = Color(0xFFF6F8FA),
    cardElevated = Color(0xFFFFFFFF),
    hairline = Color(0xFFE7EBF0),
    track = Color(0xFFEDF0F4),
    textPrimary = Color(0xFF1B1F24),
    textSecondary = Color(0xFF7C848E),
    textTertiary = Color(0xFFA7AEB7),
    primary = Color(0xFF3B7BE0),
    primarySoft = Color(0xFFA9CDEE),
    orange = Color(0xFFF0A868),
    orangeSoft = Color(0xFFF8D3B0),
    green = Color(0xFF3DBE6B),
    red = Color(0xFFF0524A),
    purple = Color(0xFF9B6BE8),
    cyan = Color(0xFF4CB8E8),
    isDark = false,
)

private val DarkColors = OsColors(
    background = Color(0xFF0E1013),
    card = Color(0xFF1A1D22),
    cardAlt = Color(0xFF23272E),
    cardElevated = Color(0xFF2C323A),
    hairline = Color(0x1FFFFFFF),
    track = Color(0x17FFFFFF),
    textPrimary = Color(0xFFEEF1F5),
    textSecondary = Color(0xFF9AA2AC),
    textTertiary = Color(0xFF6D757F),
    primary = Color(0xFF6BA5F0),
    primarySoft = Color(0xFF7FB6E8),
    orange = Color(0xFFF2B173),
    orangeSoft = Color(0xFFC98F5A),
    green = Color(0xFF4FD07D),
    red = Color(0xFFFF6B60),
    purple = Color(0xFFB18BF5),
    cyan = Color(0xFF5FC8F0),
    isDark = true,
)

/** 主题深浅判定：以 Miuix 背景色的感知亮度为准，可同时覆盖浅色/深色/跟随系统三种模式。 */
private fun Color.perceivedLuminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

/** 当前生效的配色令牌 */
@Composable
fun osColors(): OsColors {
    val bg = MiuixTheme.colorScheme.background
    return if (bg.perceivedLuminance() < 0.5f) DarkColors else LightColors
}

/** 当前是否为深色主题 */
@Composable
fun osIsDark(): Boolean = osColors().isDark

/**
 * 字号层级。
 *
 * 统一走这套常量，避免各页面各写一个 sp 值导致层级混乱。
 */
object OsText {
    /** 页面主标题 */
    val pageTitle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold)
    /** 卡片内的大数值（MHz、百分比、温度） */
    val metric = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    /** 卡片内次级大数值 */
    val metricSmall = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    /** 卡片外的小节标题 */
    val sectionTitle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
    /** 行标签 */
    val label = TextStyle(fontSize = 13.sp)
    /** 行数值 */
    val value = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    /** 强调数值 */
    val valueStrong = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    /** 说明文字 */
    val caption = TextStyle(fontSize = 11.sp)
    /** 最小号说明文字 */
    val micro = TextStyle(fontSize = 10.sp)
    /** 导航标签 */
    val navLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
}
