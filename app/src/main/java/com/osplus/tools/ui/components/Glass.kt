package com.osplus.tools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 颜色亮度，用于判断当前主题深浅 */
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

@Composable
private fun isDarkTheme(): Boolean = MiuixTheme.colorScheme.background.luminance() < 0.5f

/**
 * 液态玻璃质感修饰符。
 *
 * 四层叠加：半透明着色层 + 顶光渐变高光 + 细描边 + 投影，
 * 模拟 iOS / HyperOS 的毛玻璃观感。
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color? = null,
    alpha: Float = 0.60f,
    elevation: Dp = 16.dp,
    borderAlpha: Float = 0.34f,
): Modifier {
    val base = tint ?: MiuixTheme.colorScheme.surface
    val dark = isDarkTheme()
    val sheenTop = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.62f)
    val sheenBottom = if (dark) Color.White.copy(alpha = 0.02f) else Color.White.copy(alpha = 0.08f)

    return this
        .shadow(elevation, shape, clip = false)
        .clip(shape)
        .background(base.copy(alpha = alpha))
        .background(
            Brush.verticalGradient(
                0f to sheenTop,
                0.5f to Color.Transparent,
                1f to sheenBottom,
            )
        )
        .border(
            width = 0.8.dp,
            brush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = (borderAlpha + 0.26f).coerceAtMost(1f)),
                    Color.White.copy(alpha = borderAlpha * 0.25f),
                    Color.White.copy(alpha = borderAlpha),
                )
            ),
            shape = shape,
        )
}

/**
 * 内容卡片修饰符：近不透明表面色 + 发丝描边 + 极轻投影。
 * 承载大量数据时优先保证可读性，而非透光感。
 */
@Composable
fun Modifier.softCard(
    shape: Shape = RoundedCornerShape(16.dp),
    elevation: Dp = 2.dp,
): Modifier {
    val dark = isDarkTheme()
    val surface = MiuixTheme.colorScheme.surface
    val outline = MiuixTheme.colorScheme.onBackground.copy(alpha = if (dark) 0.10f else 0.06f)
    return this
        .shadow(elevation, shape, clip = false)
        .clip(shape)
        .background(surface.copy(alpha = if (dark) 0.92f else 0.96f))
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (dark) 0.05f else 0.30f),
                1f to Color.Transparent,
            )
        )
        .border(0.7.dp, outline, shape)
}

/** 数据卡片容器 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .softCard(RoundedCornerShape(cornerRadius))
            .padding(contentPadding),
        content = content,
    )
}



/**
 * 页面背景：柔和竖向渐变 + 右上角品牌色晕染，
 * 让上层玻璃/半透明元素有内容可"透"，避免整体发灰。
 */
@Composable
fun PageBackground(modifier: Modifier = Modifier) {
    val dark = isDarkTheme()
    val base = MiuixTheme.colorScheme.background
    val accent = MiuixTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = if (dark) 0.12f else 0.08f),
                        base,
                        base,
                    )
                )
            )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = if (dark) 0.14f else 0.10f),
                            Color.Transparent,
                        ),
                        center = Offset(900f, 60f),
                        radius = 1000f,
                    )
                )
        )
    }
}
