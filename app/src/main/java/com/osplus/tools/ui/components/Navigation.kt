package com.osplus.tools.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop

/** 液态玻璃的背景模糊半径。半径越大越「奶油」，但边缘会糊掉导航条自身的轮廓 */
private val GlassBlurRadius = 26.dp

/** 导航条目 */
data class BarItem(
    val label: String,
    val icon: ImageVector,
)

/**
 * 底部悬浮导航（纯图标，不显示文字）。
 *
 * 文字标签移除后，选中态改由「主色图标 + 主色浅底圆」表达：
 * 四个页面的图标本身已能区分，再配一行 11sp 小字只会把悬浮条撑高、变重。
 * 中文名通过 contentDescription 保留，供无障碍朗读。
 *
 * 材质为**液态玻璃**：传入 [backdrop]（由根布局用 `rememberLayerBackdrop()` 记录的内容层）后，
 * 先对下方内容做真实高斯模糊，再叠半透明着色与高光描边，
 * 于是页面滚动时能透过导航条看到被模糊的内容，形成悬浮层次。
 * [backdrop] 为 null 时退回半透明实底，避免在无法取到背景的场合变成全透明。
 */
@Composable
fun OsFloatingBottomBar(
    items: List<BarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
) {
    val barShape = RoundedCornerShape(30.dp)
    val blurPx = with(LocalDensity.current) { GlassBlurRadius.toPx() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 64.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (backdrop != null) {
                        // 真实背景模糊：把下方内容模糊后画在本条之下
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { barShape },
                            effects = { blur(blurPx) },
                        )
                    } else {
                        Modifier
                    }
                )
                .liquidGlass(
                    shape = barShape,
                    // 有真实模糊时着色可以更淡（透出模糊后的内容）；没有则提高不透明度保证图标可辨
                    alpha = if (backdrop != null) 0.55f else 0.94f,
                )
                .padding(horizontal = 7.dp, vertical = 7.dp),
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
    val c = osColors()
    val highlight by animateColorAsState(
        targetValue = if (selected) {
            c.primary.copy(alpha = if (c.isDark) 0.26f else 0.13f)
        } else {
            Color.Transparent
        },
        label = "barHighlight",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) c.primary else c.textSecondary,
        label = "barContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = spring(),
        label = "barScale",
    )

    Box(
        modifier = modifier.padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(highlight)
                .scale(scale)
                .pressable(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = contentColor,
                modifier = Modifier.size(23.dp),
            )
        }
    }
}

/**
 * 悬浮返回按钮。
 *
 * 顶栏移除后二级详情页失去可见的返回入口（返回手势本身不可见，底部导航则会
 * 直接跳回一级页），因此在左上角保留一个悬浮圆形按钮作为显式出口。
 */
@Composable
fun OsFloatingBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = osColors()
    Box(
        modifier = modifier
            .statusBarsPadding()
            .padding(start = 12.dp, top = 8.dp)
            .size(38.dp)
            .osCard(CircleShape, elevation = 8.dp)
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "返回",
            tint = c.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}
