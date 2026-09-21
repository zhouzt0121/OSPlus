package com.osplus.tools.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏：毛玻璃材质。
 *
 * 通栏贴合状态栏，半透明表面 + 顶光渐变，底部一条发丝分隔线；
 * 内容从下方穿过时形成透光层次。
 */
@Composable
fun GlassTopBar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val surface = MiuixTheme.colorScheme.surface
    val outline = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.07f)
    val density = LocalDensity.current
    val blurPx = with(density) { 30.dp.toPx() }
    Box(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (backdrop != null) {
                        // 真实背景模糊：模糊下方滚动内容，再叠加半透明着色层
                        Modifier
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RectangleShape },
                                effects = { blur(blurPx) },
                            )
                            .background(surface.copy(alpha = 0.55f))
                    } else {
                        Modifier
                            .background(surface.copy(alpha = 0.74f))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.42f),
                                        Color.Transparent,
                                    )
                                )
                            )
                    }
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.30f),
                            Color.Transparent,
                        )
                    )
                )
                .statusBarsPadding()
                .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let {
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                trailing?.invoke()
            }
        }
        // 底部发丝线
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(0.7.dp)
                .background(outline)
        )
    }
}

/** 底栏条目定义 */
data class BarItem(
    val label: String,
    val icon: ImageVector,
)

/**
 * Apple 风格悬浮底栏 + 液态玻璃。
 *
 * 独立胶囊容器悬浮于内容之上；选中项展开为图标 + 文字的高亮胶囊，
 * 未选中项仅显示图标，容器带高光描边与投影，形成"液态玻璃"观感。
 */
@Composable
fun GlassFloatingBottomBar(
    items: List<BarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
) {
    val barBlurPx = with(LocalDensity.current) { 30.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(30.dp) },
                            effects = { blur(barBlurPx) },
                        )
                    } else {
                        Modifier
                    }
                )
                .liquidGlass(
                    shape = RoundedCornerShape(30.dp),
                    alpha = if (backdrop != null) 0.62f else 0.92f,
                    elevation = 20.dp,
                    borderAlpha = 0.42f,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                FloatingBarItem(
                    item = item,
                    selected = index == selectedIndex,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun FloatingBarItem(
    item: BarItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val primary = MiuixTheme.colorScheme.primary
    val highlight by animateColorAsState(
        targetValue = if (selected) primary.copy(alpha = 0.16f) else Color.Transparent,
        label = "barHighlight",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) primary else MiuixTheme.colorScheme.onBackgroundVariant,
        label = "barContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = spring(),
        label = "barScale",
    )
    val capsuleWidth by animateDpAsState(
        targetValue = if (selected) 86.dp else 44.dp,
        label = "barWidth",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .width(capsuleWidth)
                .height(36.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(highlight)
                .scale(scale),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = contentColor,
                modifier = Modifier.size(21.dp),
            )
            if (selected) {
                Spacer(Modifier.width(5.dp))
                Text(
                    text = item.label,
                    style = MiuixTheme.textStyles.footnote2,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

