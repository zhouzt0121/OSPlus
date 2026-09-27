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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight

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
    backdrop: Backdrop? = null,
) {
    val c = osColors()
    val actionShape = RoundedCornerShape(percent = 50)
    // 必须在 composable 上下文里先取好颜色。
    // onDrawSurface 的 lambda 是 DrawScope 作用域、不是可组合作用域，
    // 在里面调 @Composable 的 LiquidGlassColors.container() 会编译不过。
    val container = LiquidGlassColors.container()
    Box(
        modifier = modifier
            .size(TopBarActionSize)
            .then(
                if (backdrop != null) {
                    // 按官方 LiquidButton 的写法：vibrancy + blur + lens，
                    // 高光与玻璃体都交给 drawBackdrop，不在外面自绘任何棱光/描边。
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { actionShape },
                        effects = {
                            vibrancy()
                            blur(2f.dp.toPx())
                            lens(12f.dp.toPx(), 24f.dp.toPx())
                        },
                        highlight = { Highlight.Default },
                        onDrawSurface = { drawRect(container) },
                    )
                } else {
                    // 拿不到背景时兜底：只铺官方容器色，不画自绘棱光。
                    Modifier
                        .clip(actionShape)
                        .background(LiquidGlassColors.container())
                }
            )
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
