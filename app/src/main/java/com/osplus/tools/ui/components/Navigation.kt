package com.osplus.tools.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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

/**
 * 液态玻璃的背景模糊半径。
 *
 * **不能一味调大**：折射要能被看见，背景必须留下可辨认的结构当参照物。
 * 26dp 时一行小字、一条 1px 的趋势线都已被糊成匀质底噪，
 * 采样点位移多少都没有对比，真机上表现为「折射完全没生效」——
 * 实际是模糊把参照物抹掉了。14dp 是「背景仍可辨形」与「足够奶油」的平衡点。
 */
private val GlassBlurRadius = 14.dp

/** 导航胶囊的圆角，与 `barShape` 保持一致；SDF 折射需要它来对齐真实轮廓 */
private val GlassCornerRadius = 30.dp

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
 * 由下往上叠五层：
 *
 * 1. **背景采样** —— miuix 的图层背板，取本条**下方**的真实内容
 * 2. **高斯模糊** —— `blur(26dp)`，把内容糊成柔底
 * 3. **折射** —— AGSL 着色器（`buildGlassRefraction`），中心无畸变、边缘最强
 * 4. **玻璃着色 + 掠光 + 非均匀描边** —— `liquidGlass`，上缘亮 / 下缘暗，做出厚度
 * 5. **图标** —— 选中态为主色 + 浅底圆
 *
 * 关键在于第 3 层。只有 1+2+4 是「毛玻璃」：背景被糊成匀质底噪，控件像磨砂塑料；
 * 加上折射后，边缘会把背景挤出一圈透镜环，才有了玻璃的实体感。
 *
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
    val barShape = RoundedCornerShape(GlassCornerRadius)
    val blurPx = with(LocalDensity.current) { GlassBlurRadius.toPx() }
    val cornerRadiusPx = with(LocalDensity.current) { GlassCornerRadius.toPx() }
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
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { barShape },
                            effects = {
                                // blur() 先跑一遍：它除了写 renderEffect 之外还会设置
                                // padding / downscaleFactor 等采样参数，这些副作用要保留。
                                blur(blurPx)
                                // 再用「模糊 → 折射」整链覆盖掉它写进去的纯模糊。
                                // 构造失败（AGSL 编译不过）时保留 blur() 的结果降级为毛玻璃。
                                buildLiquidGlassEffect(blurPx, size.width, size.height, cornerRadiusPx)
                                    ?.let { renderEffect = it }
                            },
                        )
                    } else {
                        Modifier
                    }
                )
                .liquidGlass(
                    shape = barShape,
                    cornerRadius = GlassCornerRadius,
                    // 有真实模糊时着色要压到 0.4 附近：玻璃盖得越实、背景越暗，
                    // 折射位移就越没有参照物，整条折射链会白做。
                    // 取不到背景（旧机型 / backdrop 为 null）时反而要提高不透明度，
                    // 否则没有底色的玻璃会让图标糊在页面内容上。
                    alpha = if (backdrop != null) 0.40f else 0.94f,
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val contentColor by animateColorAsState(
        targetValue = if (selected) c.primary else c.textSecondary,
        label = "barContent",
    )
    // 选中块自身也是一小块玻璃：有色、有掠光、有描边。
    // 之前它是一个纯色圆——压在玻璃条上像贴了张不干胶，缺了同一套材质语言。
    val tint by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "barTint",
    )
    // 选中态轻微放大、未选中收紧；再叠一层按下下沉。两级 spring 各管一件事：
    // 前者管「切换到了哪个」，后者管「手指按在哪」。
    // dampingRatio < 1（欠阻尼）是为了留一点回弹——规格书里的「动态形变」
    // 在这么小的控件上，就体现在这几分之一秒的过冲里。
    val selectScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.90f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "barSelectScale",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "barPressScale",
    )

    Box(
        modifier = modifier.padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .scale(selectScale * pressScale)
                .then(
                    if (tint > 0.01f) {
                        Modifier
                            .shadow(6.dp * tint, CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        c.primary.copy(alpha = tint * (if (c.isDark) 0.36f else 0.20f)),
                                        c.primary.copy(alpha = tint * (if (c.isDark) 0.20f else 0.10f)),
                                    )
                                )
                            )
                            .border(
                                width = 0.9.dp,
                                // 与外层玻璃条共用同一条受光方向：上亮、下暗。
                                // 光源不一致的两块材质叠在一起，人眼立刻会觉得「脏」。
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = tint * 0.60f),
                                        Color.Transparent,
                                        c.primary.copy(alpha = tint * 0.20f),
                                    )
                                ),
                                shape = CircleShape,
                            )
                    } else {
                        Modifier
                    }
                )
                .pressable(interactionSource = interaction, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // 图标垫一层下移的软阴影：直接压在玻璃上会「陷」进去，
            // 因为它与玻璃之间没有厚度差。在正下方 1dp 处先画一遍
            // 近乎全黑、低透明度的自身，图标才像浮在玻璃表面之上。
            val iconPainter = rememberVectorPainter(item.icon)
            val iconShadowOffset = with(LocalDensity.current) { 1.0.dp.toPx() }
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = contentColor,
                modifier = Modifier
                    .size(23.dp)
                    .drawWithContent {
                        val iconSize = size
                        translate(top = iconShadowOffset) {
                            with(iconPainter) {
                                draw(
                                    size = iconSize,
                                    alpha = 0.30f,
                                    colorFilter = ColorFilter.tint(Color.Black),
                                )
                            }
                        }
                        drawContent()
                    },
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
