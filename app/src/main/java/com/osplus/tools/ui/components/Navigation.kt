package com.osplus.tools.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.osplus.tools.ui.liquid.LiquidButton

/**
 * 液态玻璃的背景模糊半径。
 *
 * **不能一味调大**：折射要能被看见，背景必须留下可辨认的结构当参照物。
 * 26dp 时一行小字、一条 1px 的趋势线都已被糊成匀质底噪，
 * 采样点位移多少都没有对比，真机上表现为「折射完全没生效」——
 * 实际是模糊把参照物抹掉了。14dp 是「背景仍可辨形」与「足够奶油」的平衡点。
 */
private val GlassBlurRadius = 14.dp

/**
 * 顶栏内容区高度。
 *
 * 所有页面共用同一个常量，且顶栏只在根布局里渲染一次，
 * 因此「概览 / 性能 / 帧率 / 电源 / 四个详情页 / 设置」的顶栏高度、内边距、
 * 字号与按钮尺寸完全一致——不会出现某页高一点、某页矮一点的错位。
 *
 * 按钮 36dp + 上下各 10dp 留白 = 56dp。顶栏按钮是原版 `LiquidButton`，
 * 但尺寸由 [TopBarActionSize] 显式传入（原版默认 48dp 是 iOS 规格，对手机顶栏偏大）。
 */
val TopBarHeight = 56.dp

/**
 * 顶栏按钮的边长。
 *
 * 图标按钮传成正方形（配合原版 `LiquidButton` 的 `CapsuleShape`）就是**正圆**；
 * 胶囊按钮用它当高度，宽度由内容撑开。
 */
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
    backdrop: Backdrop? = null,
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
                backdrop = backdrop,
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
 * 顶栏图标按钮（原版 `LiquidButton`）——**正圆**。
 *
 * 玻璃体、按压放大、拖动位移全部由原版组件提供，这里只负责三件「原版不关心」的事：
 *
 * - **图标与语义**：原版 `content` 是 `RowScope`，图标直接放进去即可。
 * - **禁用态**：原版没有 `enabled`，用 `isInteractive = enabled`；它不会拦截点击，
 *   所以调用方仍要自己守卫（顶栏的「清内存 / 清交换」在 `OsPlusApp` 里已判过）。
 *   半透明是外加的：`modifier.alpha()` 在原版 `modifier` 链最外层，不改原版行为。
 * - **backdrop 兜底**：顶栏拿到的是独立录制的 `topBarBackdrop`（真实可折射），
 *   取不到时退 `emptyBackdrop()`。
 *
 * ### 为什么必须显式传尺寸才能是正圆
 *
 * 原版 `LiquidButton` 默认 48dp 高、内容区左右各 16dp 内边距。图标按钮的内容只有
 * 一个 19dp 图标 → 宽度 `19 + 16×2 = 51dp`、高度 48dp，`CapsuleShape`（圆角 = 短边
 * 的一半）会把 51×48 画成**椭圆**。
 *
 * 外部 `Modifier` 压不回来：`.height(48.dp)` 在链尾永远最后生效，`requiredSize()`
 * 也只能压高度，内部 16dp 内边距仍会把内容区挤没。所以这里走原版新增的
 * `height` / `horizontalPadding` 参数：正方形 + 零内边距 = 正圆。
 */
@Composable
fun OsTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
) {
    val c = osColors()
    LiquidButton(
        onClick = onClick,
        backdrop = backdrop ?: emptyBackdrop(),
        modifier = modifier.size(TopBarActionSize).alpha(if (enabled) 1f else 0.4f),
        isInteractive = enabled,
        surfaceColor = LiquidGlassColors.container(),
        height = TopBarActionSize,
        // 零内边距：宽度完全由 36dp 的正方形约束决定，多一分都会破圆
        horizontalPadding = 0.dp,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: c.textSecondary,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * 顶栏胶囊按钮：图标 + 短文字标签。
 *
 * 概览页顶栏原本给「清理内存 / 清理交换」用的是与「记录帧率 / 设置」完全相同的
 * 圆形图标按钮，两个动作只有一个 19dp 的图标可辨，「清理交换分区」用 Refresh
 * 图标更是让人误以为是刷新。重绘成带文字的胶囊：动作含义一眼可读，
 * 与右侧的圆形图标按钮在视觉上也区分出「一键执行类」与「导航类」。
 *
 * 玻璃材质与 [OsTopBarAction] 完全一致：两者现在都是原版 `LiquidButton`，
 * 差别只在 `content`（本组件多一个文字标签）与宽度（图标按钮是定宽正圆，
 * 本组件高度固定 36dp、宽度由内容撑开）。
 */
@Composable
fun OsTopBarPillAction(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
) {
    val c = osColors()
    LiquidButton(
        onClick = onClick,
        backdrop = backdrop ?: emptyBackdrop(),
        modifier = modifier.alpha(if (enabled) 1f else 0.4f),
        isInteractive = enabled,
        surfaceColor = LiquidGlassColors.container(),
        height = TopBarActionSize,
        horizontalPadding = 11.dp,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = c.textSecondary,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            style = OsText.caption,
            color = c.textPrimary,
            maxLines = 1,
        )
    }
}
