package com.osplus.tools.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.osplus.tools.ui.theme.osColors

/** 卡片圆角：全套 UI 的统一曲率 */
val CardRadius = 18.dp

/**
 * 可点击元素的按压反馈。
 *
 * 不用默认水波纹（本工程未引入 Material 主题，回退观感偏灰），
 * 改为整体轻微淡出，与卡片式的静态界面更协调。
 */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.62f else 1f,
        label = "pressAlpha",
    )
    return this
        .alpha(alpha)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * 卡片表面：白底 + 发丝描边 + 极轻投影。
 *
 * 刻意不用高斯模糊/玻璃材质——密集数据场景下，实底卡片的分辨率与对比度
 * 明显更好，滚动时也不会因实时模糊带来额外 GPU 开销。
 */
@Composable
fun Modifier.osCard(
    shape: Shape = RoundedCornerShape(CardRadius),
    elevation: Dp = 2.dp,
): Modifier {
    val c = osColors()
    return this
        .shadow(elevation, shape, clip = false)
        .clip(shape)
        .background(c.card)
        .border(0.7.dp, c.hairline, shape)
}

/** 卡片内的次级填充块（瓦片、分段控件槽位） */
@Composable
fun Modifier.osTile(
    shape: Shape = RoundedCornerShape(14.dp),
): Modifier {
    val c = osColors()
    return this
        .clip(shape)
        .background(c.cardAlt)
        .border(0.7.dp, c.hairline, shape)
}

/**
 * 液态玻璃材质（Liquid Glass）。
 *
 * 与 [osCard] 是明确分工的两种表面，不要互相替换：
 * - `osCard` 承载密集数据，要的是**可读性**——实底 + 发丝描边，不透明；
 * - `liquidGlass` 只给**悬浮在内容之上的导航类控件**用，要的是**透光层次**——
 *   内容从下方滚过时能被隐约看见，控件因此「浮」起来而不是「贴」在页面上。
 *
 * 四层叠加模拟玻璃：
 * 1. 半透明着色层（叠在 [Modifier.drawBackdrop] 的真实背景模糊之上）
 * 2. 竖向顶光渐变：上缘亮、中部透明、下缘回一点反光，模拟光从上方掠过玻璃
 * 3. 线性渐变描边：左上最亮 → 中部最暗 → 右下回升，形成一圈被光照到的「棱」
 * 4. 大而软的投影，把玻璃从背景中托起
 *
 * 深色主题下白色高光的 alpha 大幅降低，否则玻璃会发白发灰、压不住底色。
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(30.dp),
    alpha: Float = 0.58f,
    elevation: Dp = 18.dp,
    borderAlpha: Float = 0.34f,
): Modifier {
    val c = osColors()
    val sheenTop = if (c.isDark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.60f)
    val sheenBottom = if (c.isDark) Color.White.copy(alpha = 0.02f) else Color.White.copy(alpha = 0.08f)

    return this
        .shadow(elevation, shape, clip = false)
        .clip(shape)
        .background(c.card.copy(alpha = alpha))
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
 * 标准数据卡片容器；传入 [onClick] 后整卡可点击（用于下钻详情页）。
 *
 * 不在卡片上叠加任何角标：概览页已取消卡片外的小节标题，
 * 卡内文字与圆环标签自成标识，右上角留白反而更干净。
 */
@Composable
fun OsCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardRadius,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .osCard(RoundedCornerShape(cornerRadius))
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** 卡片内的横向发丝分隔线 */
@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 0.dp,
) {
    val c = osColors()
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding)
            .height(0.7.dp)
            .background(c.hairline)
    )
}

/**
 * 页面底色。
 *
 * 以中性浅灰为底，仅在顶部叠一层几乎不可见的品牌色晕染，
 * 避免大面积纯色显得死板，同时不会干扰卡片与文字的对比度。
 */
@Composable
fun PageBackground(modifier: Modifier = Modifier) {
    val c = osColors()
    Box(
        modifier
            .fillMaxSize()
            .background(c.background)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            c.primary.copy(alpha = if (c.isDark) 0.07f else 0.05f),
                            c.background,
                        )
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            c.primary.copy(alpha = if (c.isDark) 0.05f else 0.04f),
                            androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        center = Offset(880f, -40f),
                        radius = 900f,
                    )
                )
        )
    }
}
