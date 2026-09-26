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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
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
 *
 * 带 [interactionSource] 的重载供调用方读取按压状态。拆成两个重载而不是给
 * [interactionSource] 加默认值，是因为 Kotlin 的尾随 lambda 绑定的是**最后一个**参数：
 * 若签名写成 `pressable(onClick, interactionSource = null)`，那么既有的
 * `pressable { ... }` 会把 lambda 塞给 `interactionSource` 而直接编译失败。
 * 让双参重载不带默认值，`pressable { }` 与 `pressable(onClick)` 就都只会匹配单参重载。
 *
 * @param interactionSource 传入后调用方可用 `collectIsPressedAsState()` 读取按压状态
 *   （例如做按压缩放）；传 null 时内部自建一个。
 */
@Composable
fun Modifier.pressable(
    interactionSource: MutableInteractionSource?,
    onClick: () -> Unit,
): Modifier {
    // 兜底 source 无条件 remember：若写成 `interactionSource ?: remember { ... }`，
    // 一旦调用方在两次组合之间切换了传/不传，remember 就会变成条件调用。
    val fallback = remember { MutableInteractionSource() }
    val interaction = interactionSource ?: fallback
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.62f else 1f,
        label = "pressAlpha",
    )
    return this
        .alpha(alpha)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** 单参重载：不需要读取按压状态时用这个，[interactionSource] 由内部自建。 */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier =
    pressable(interactionSource = null, onClick = onClick)

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
 * 五层叠加模拟玻璃（模糊与折射由 [Modifier.drawBackdrop] 在更下层完成，不在这里）：
 * 1. 半透明着色层（叠在模糊 + 折射之后）
 * 2. 竖向掠光：上缘最亮 → 快速衰减 → 中段透明 → 下缘回一点反光。
 *    多给一个 0.14f 的中间停靠点是为了让高光**集中在顶缘一小条**上，
 *    而不是从顶部一路渐变下来——后者看起来是塑料反光，前者才是玻璃。
 * 3. **非均匀描边**：上缘白、中段透明、下缘暗。
 *    这是玻璃「厚度」的来源，也是最容易被做错的一层：四周等亮的描边
 *    只能画出一条塑料边框，而真实玻璃的上棱受光、下棱背光。
 * 4. **内高光**：在轮廓内侧 1.6dp 处再描一圈极淡的白，只在上缘可见。
 *    光穿过玻璃后会在内壁反射一次，这一圈内反射是「一块平板」与
 *    「一块实体」之间的分界。少了它，玻璃看着就是贴在屏上的一张色纸。
 * 5. 大而软的投影，把玻璃从背景中托起
 *
 * ### 关于 [alpha]：这是折射能否被看见的前提
 *
 * 玻璃盖得越实，背景越暗，折射位移就越没有参照物。
 * 实测 `alpha` 到 0.55 以上时，[com.osplus.tools.ui.components.buildLiquidGlassEffect]
 * 的位移在真机上**完全看不出来**——不是着色器没生效，而是背景已经被压到看不见了。
 * 因此有真实 backdrop 时取 0.40 附近；取不到背景时才提到 0.9 以上保证图标可辨。
 *
 * 深色主题下白色高光的 alpha 大幅降低，否则玻璃会发白发灰、压不住底色。
 *
 * @param cornerRadius 与 [shape] 的圆角保持一致；用于把内高光按轮廓内缩，
 *   做成参数而不是从 [shape] 反解，是因为 [Shape] 是任意接口，无法安全取半径。
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(30.dp),
    alpha: Float = 0.40f,
    elevation: Dp = 18.dp,
    cornerRadius: Dp = 30.dp,
): Modifier {
    val c = osColors()
    val sheenTop = if (c.isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.40f)
    val sheenBottom = if (c.isDark) Color.White.copy(alpha = 0.02f) else Color.White.copy(alpha = 0.06f)
    val innerHighlight = if (c.isDark) 0.10f else 0.22f

    return this
        .shadow(elevation, shape, clip = false)
        .clip(shape)
        .background(c.card.copy(alpha = alpha))
        .background(
            Brush.verticalGradient(
                0f to sheenTop,
                0.14f to Color.White.copy(alpha = if (c.isDark) 0.03f else 0.10f),
                0.46f to Color.Transparent,
                0.86f to Color.Transparent,
                1f to sheenBottom,
            )
        )
        .drawWithContent {
            drawContent()
            // 内高光描边。用 drawWithContent 而不是再套一个 border：
            // border 永远贴在轮廓上，而这一圈必须**内缩**，才能与第 3 层的外描边
            // 拉开距离、形成「棱 + 壁」的两级结构。
            val inset = 1.6.dp.toPx()
            val w = size.width - inset * 2f
            val h = size.height - inset * 2f
            if (w > 0f && h > 0f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = innerHighlight),
                        0.42f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    topLeft = Offset(inset, inset),
                    size = Size(w, h),
                    cornerRadius = CornerRadius((cornerRadius - 1.6.dp).toPx().coerceAtLeast(0f)),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
        .border(
            width = 1.2.dp,
            // 上亮下暗：受光的顶棱与背光的底棱，这一圈才是「厚度」
            brush = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (c.isDark) 0.26f else 0.72f),
                    Color.Transparent,
                    Color.Transparent,
                    Color.Black.copy(alpha = if (c.isDark) 0.16f else 0.14f),
                )
            ),
            shape = shape,
        )
}

/**
 * 玻璃按钮表面：给**控件尺寸**的元件用的液态玻璃。
 *
 * ### 为什么按钮不用 `drawBackdrop` 的实时折射
 *
 * [liquidGlass] 那套是给**悬浮在滚动内容之上**的元件用的，靠实时采样背景才能折射。
 * 按钮不满足这个前提，硬上会有三个问题：
 *
 * 1. **没有可折射的东西**。按钮坐在不透明的卡片上，卡片是纯色，
 *    折射一片纯色得到的还是纯色——花了 GPU 却看不出任何差别。
 * 2. **会自采样出残影**。按钮在内容层内部，而内容层被记录成 backdrop，
 *    按钮于是采样到包含自己的上一帧，逐帧累积成拖影。
 * 3. **数量多**。全应用几十个按钮各做一次 backdrop 绘制，滚动时开销叠加。
 *
 * 所以按钮改用**静态光学处理**：玻璃的辨识度本来就不靠折射，
 * 而靠「上亮下暗的棱 + 顶光 + 内高光 + 投影」这四样——
 * 它们在纯色背景上同样成立，代价却只是几次 drawRoundRect。
 *
 * [body] 由调用方按状态给出（未选中 = 卡色，选中 = 主色淡染），
 * 因此选中态的颜色语言完全保留，玻璃只是叠在其上的一层光学。
 *
 * 内棱比外棱缩进 1px：玻璃是一块有厚度的板，光在上下两个面上各反射一次，
 * 只有一圈描边会看起来像贴纸而不是玻璃。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    cornerRadius: Dp,
    body: Color,
    elevation: Dp = 3.dp,
): Modifier {
    val c = osColors()
    val dark = c.isDark
    val radiusPx = with(LocalDensity.current) { cornerRadius.toPx() }

    // 顶光集中在顶缘一小条，中段压到接近透明——一路渐变下来是塑料反光，不是玻璃
    val sheenTop = Color.White.copy(alpha = if (dark) 0.10f else 0.32f)
    val sheenBottom = Color.White.copy(alpha = if (dark) 0.02f else 0.05f)
    // 外棱：受光的顶棱亮、背光的底棱暗，这一圈才是「厚度」
    val edgeTop = Color.White.copy(alpha = if (dark) 0.22f else 0.58f)
    val edgeBottom = Color.Black.copy(alpha = if (dark) 0.26f else 0.13f)
    val innerRing = Color.White.copy(alpha = if (dark) 0.07f else 0.13f)

    return this
        .shadow(elevation, shape, clip = false)
        .clip(shape)
        .drawWithContent {
            val cr = CornerRadius(radiusPx)

            // 玻璃体
            drawRoundRect(color = body, cornerRadius = cr)
            // 顶光
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0.0f to sheenTop,
                    0.45f to Color.Transparent,
                    1.0f to sheenBottom,
                ),
                cornerRadius = cr,
            )

            drawContent()

            val stroke = 1.dp.toPx()

            // 外棱
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(edgeTop, Color.Transparent, edgeBottom)
                ),
                cornerRadius = cr,
                size = size,
                style = Stroke(width = stroke),
            )
            // 内棱：缩进一圈的第二道反射
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(innerRing, Color.Transparent)
                ),
                topLeft = Offset(stroke, stroke),
                size = Size(size.width - stroke * 2, size.height - stroke * 2),
                cornerRadius = CornerRadius((radiusPx - stroke).coerceAtLeast(0f)),
                style = Stroke(width = stroke),
            )
        }
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
