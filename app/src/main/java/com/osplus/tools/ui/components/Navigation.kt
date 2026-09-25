package com.osplus.tools.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
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
 * 顶栏内容区高度。
 *
 * 所有页面共用同一个常量，且顶栏只在根布局里渲染一次，
 * 因此「概览 / 性能 / 帧率 / 电源 / 四个详情页 / 设置」的顶栏高度、内边距、
 * 字号与按钮尺寸完全一致——不会出现某页高一点、某页矮一点的错位。
 */
val TopBarHeight = 56.dp

/** 顶栏圆形按钮的直径 */
private val TopBarActionSize = 36.dp

/**
 * 统一顶栏。
 *
 * 左侧是页面标题，二级页在标题左侧多一个返回按钮；右侧是页面级动作。
 * 动作区用 [RowScope] 暴露，调用方按页传入内容，但按钮尺寸与间距由这里统一，
 * 避免各页各写一套导致同一排图标大小不一。
 *
 * 概览页把「清理内存 / 清理交换 / 记录帧率 / 设置」四个动作都放在这一排：
 * 它们都属于「看到水位、顺手处理一下」，原先散在页面底部，需要滚到末尾才点得到；
 * 移到顶栏后无论滚到哪一屏都够得着，也把首屏整块让给了数据本身。
 */
@Composable
fun OsTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = osColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(TopBarHeight)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            OsTopBarAction(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = title,
            style = OsText.pageTitle,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * 顶栏圆形按钮。
 *
 * 用浅色圆底 + 发丝描边，而不是纯图标：概览页顶栏并排放着四个动作，
 * 无底色的图标会糊成一条，圆底给每个动作划出明确的点击范围，
 * 也让「这里可以点」在一条没有文字的顶栏里仍然成立。
 */
@Composable
fun OsTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val c = osColors()
    Box(
        modifier = modifier
            .size(TopBarActionSize)
            .clip(CircleShape)
            .background(c.cardAlt)
            .border(0.7.dp, c.hairline, CircleShape)
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: c.textSecondary,
            modifier = Modifier.size(19.dp),
        )
    }
}
