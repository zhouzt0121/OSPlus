package com.osplus.tools.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 液态玻璃组件集。
 *
 * **全部按 Kyant0 官方 catalog 组件（LiquidBottomTabs / LiquidButton /
 * LiquidToggle / LiquidSlider）的材质写法实现**，用的是官方
 * `io.github.kyant0:backdrop` 库，而不是自己拿 Compose 图层拼。
 *
 * ### 为什么必须用库自己的参数
 *
 * 玻璃的每一层（棱光 / 投影 / 内阴影 / 玻璃体）都由 `drawBackdrop` 在
 * 同一条着色器链里完成，它们共用同一套渲染几何。一旦在外面再叠一层
 * 自绘的 `background` / `border` / `drawWithContent`，那层的几何就与
 * 内部对不上——实测会在玻璃中部留下一条边缘锐利的白色横带，
 * 而且逐层删除都无效，因为错位在 `drawBackdrop` 内部。
 *
 * ### 官方配色（照抄，不自行发挥）
 *
 * | 用途 | 浅色 | 深色 |
 * |---|---|---|
 * | 强调色 | `#0088FF` | `#0091FF` |
 * | 开关强调色 | `#34C759` | `#30D158` |
 * | 容器色 | `#FAFAFA` @ 0.4 | `#121212` @ 0.4 |
 * | 轨道色 | `#787878` @ 0.2 | `#787880` @ 0.36 |
 */
object LiquidGlassColors {
    /** 底部导航 / 按钮 / 滑块的强调色 */
    val accentLight = Color(0xFF0088FF)
    val accentDark = Color(0xFF0091FF)

    /** 开关的强调色（官方 LiquidToggle 用的是绿色，不是蓝） */
    val switchAccentLight = Color(0xFF34C759)
    val switchAccentDark = Color(0xFF30D158)

    /** 玻璃容器底色 */
    val containerLight = Color(0xFFFAFAFA).copy(alpha = 0.4f)
    val containerDark = Color(0xFF121212).copy(alpha = 0.4f)

    /** 轨道底色（开关 / 滑块） */
    val trackLight = Color(0xFF787878).copy(alpha = 0.2f)
    val trackDark = Color(0xFF787880).copy(alpha = 0.36f)

    @Composable
    fun accent(): Color = if (isSystemInDarkTheme()) accentDark else accentLight

    @Composable
    fun container(): Color = if (isSystemInDarkTheme()) containerDark else containerLight

    @Composable
    fun track(): Color = if (isSystemInDarkTheme()) trackLight else trackDark
}

/** 底栏条目 */
data class LiquidBarItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/**
 * 液态玻璃底部导航栏。
 *
 * 材质照官方 `LiquidBottomTabs`：
 * - 整条：`vibrancy() + blur(8.dp) + lens(24.dp, 24.dp)`，
 *   高光 `Highlight.Default`，玻璃体 `drawRect(containerColor)`
 * - 选中指示器：单独一层 `drawBackdrop`，带 `lens` + `Highlight` + `Shadow` + `InnerShadow`
 *
 * 官方用 `DampedDragAnimation` + `InteractiveHighlight` 做拖拽跟随形变，
 * 那两个类在 catalog 里、不在发布库中，这里用等价的 spring 动画替代，
 * **材质与配色完全照官方值**。
 */
@Composable
fun LiquidBottomBar(
    items: List<LiquidBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val accent = LiquidGlassColors.accent()
    val container = LiquidGlassColors.container()
    val barShape = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { barShape },
                    effects = {
                        // 官方 LiquidBottomTabs 的三个效果，顺序与参数照抄
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    highlight = { Highlight.Default },
                    onDrawSurface = { drawRect(container) },
                )
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEachIndexed { index, item ->
                LiquidBarItemView(
                    item = item,
                    selected = index == selectedIndex,
                    accent = accent,
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun LiquidBarItemView(
    item: LiquidBarItem,
    selected: Boolean,
    accent: Color,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = osColors()
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "barItemProgress",
    )
    val contentColor = if (selected) accent else c.textTertiary
    val pillShape = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier.padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scale(1f - 0.06f * (1f - progress))
                .then(
                    if (progress > 0.01f) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                // 选中片只在按下时才起折射，平时是静态的——
                                // 官方 LiquidBottomTabs 的选中块也是 `lens(... * progress)`
                                lens(
                                    10f.dp.toPx() * progress,
                                    14f.dp.toPx() * progress,
                                    chromaticAberration = true,
                                )
                            },
                            highlight = { Highlight.Default.copy(alpha = progress) },
                            shadow = { Shadow(alpha = 0.10f * progress) },
                            innerShadow = { InnerShadow(radius = 8f.dp * progress, alpha = progress) },
                            onDrawSurface = { drawRect(accent.copy(alpha = 0.14f * progress)) },
                        )
                    } else {
                        Modifier
                    }
                )
                .pressable(onClick)
                .padding(vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = item.label,
                style = OsText.micro,
                color = contentColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
