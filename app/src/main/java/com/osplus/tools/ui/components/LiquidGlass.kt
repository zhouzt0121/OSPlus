package com.osplus.tools.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
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

/**
 * 液态玻璃开关。
 *
 * 材质照官方 `LiquidToggle`：
 * - 轨道 64×28dp，`lerp(trackColor, accentColor, fraction)`
 * - 滑块 40×24dp，`drawBackdrop` + `Highlight.Ambient` + `Shadow` + `InnerShadow`，
 *   `onDrawSurface = drawRect(White.copy(alpha = 1f - progress))`
 * - 开关强调色是**绿色**（`#34C759` / `#30D158`），不是导航栏那个蓝
 *
 * 官方用 `DampedDragAnimation` 做拖拽，这里用可拖拽的 `draggable` + spring 替代，
 * 材质与配色完全照官方值。
 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    enabled: Boolean = true,
) {
    val dark = isSystemInDarkTheme()
    val accent = if (dark) LiquidGlassColors.switchAccentDark else LiquidGlassColors.switchAccentLight
    val trackColor = if (dark) LiquidGlassColors.trackDark else LiquidGlassColors.trackLight
    val shape = RoundedCornerShape(percent = 50)

    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
        label = "toggleFraction",
    )

    var didDrag by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(width = 64.dp, height = 28.dp)
            .clip(shape)
            .background(lerp(trackColor, accent, fraction))
            .then(
                if (enabled) {
                    Modifier
                        // draggable 写在 pressable **外面**：
                        // Compose 的修饰符是外层先拿事件，draggable 只有在越过
                        // touchSlop 之后才会消费，没越过的「点一下」会继续落到 pressable。
                        // 写反了点按会被 draggable 吞掉。
                        //
                        // 【踩过的坑】之前只挂了 draggable、没挂点击，
                        // 结果开关只能拖不能点——绝大多数人是用点的。
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                if (kotlin.math.abs(delta) > 6f) {
                                    didDrag = true
                                    onCheckedChange(delta > 0f)
                                }
                            },
                            onDragStarted = { didDrag = false },
                            onDragStopped = { didDrag = false },
                        )
                        .pressable {
                            // 拖过就不再当作点击，避免「拖到开」之后又被点回关
                            if (!didDrag) onCheckedChange(!checked)
                        }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 2.dp)
                .offset(x = (20.dp * fraction))
                .size(width = 40.dp, height = 24.dp)
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                // 官方：未按下时 blur 全额、按下时折射全额
                                val p = fraction
                                blur(8f.dp.toPx() * (1f - p))
                                lens(
                                    5f.dp.toPx() * p,
                                    10f.dp.toPx() * p,
                                    chromaticAberration = true,
                                )
                            },
                            highlight = {
                                Highlight.Ambient.copy(
                                    width = Highlight.Ambient.width / 1.5f,
                                    blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                    alpha = fraction,
                                )
                            },
                            shadow = {
                                Shadow(
                                    radius = 4f.dp,
                                    color = Color.Black.copy(alpha = 0.05f),
                                )
                            },
                            innerShadow = {
                                InnerShadow(radius = 4f.dp * fraction, alpha = fraction)
                            },
                            onDrawSurface = {
                                drawRect(Color.White.copy(alpha = 1f - fraction))
                            },
                        )
                    } else {
                        Modifier
                            .clip(shape)
                            .background(Color.White)
                            .shadow(2.dp, shape)
                    }
                )
        )
    }
}

/**
 * 液态玻璃滑块。
 *
 * 材质照官方 `LiquidSlider`：
 * - 轨道高 6dp，`trackColor` 打底 + `accentColor` 填充到当前值
 * - 滑块 40×24dp，`drawBackdrop` + `Highlight.Ambient` + `Shadow` + `InnerShadow`，
 *   `onDrawSurface = drawRect(White.copy(alpha = 1f - progress))`
 * - 强调色 `#0088FF` / `#0091FF`
 *
 * 官方给滑块配了 `rememberCombinedBackdrop(backdrop, trackBackdrop)`——
 * 滑块同时采主背景与轨道层。这里 slider 多数坐在卡片上，取不到主背景，
 * 因此只在传入 [backdrop] 时才挂 drawBackdrop，否则用静态玻璃体。
 */
@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    onValueChangeFinished: () -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    val accent = if (dark) LiquidGlassColors.accentDark else LiquidGlassColors.accentLight
    val trackColor = if (dark) LiquidGlassColors.trackDark else LiquidGlassColors.trackLight
    val shape = RoundedCornerShape(percent = 50)

    val progress = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .fastCoerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        var didDrag by remember { mutableStateOf(false) }

        // 轨道：底色 + 已填充段。官方用 `layout` 把填充段裁到当前进度宽，
        // 比用 Modifier.width 好在不会触发布局重排。
        Box(
            Modifier
                .clip(shape)
                .background(trackColor)
                .pointerInput(enabled) {
                    detectTapGestures { pos ->
                        if (enabled) {
                            didDrag = true
                            // 不用 lerp()：当前作用域里 lerp 解析到的是 Color 版
                            // （给 LiquidToggle 的轨道混色用的），这里要的是 Float 版。
                            // 直接算比加别名 import 更不容易踩到重载歧义。
                            val posFraction = (pos.x / trackWidthPx).coerceIn(0f, 1f)
                            onValueChange(
                                valueRange.start +
                                    (valueRange.endInclusive - valueRange.start) * posFraction
                            )
                            onValueChangeFinished()
                        }
                    }
                }
                .height(6.dp)
                .fillMaxWidth()
        )
        Box(
            Modifier
                .clip(shape)
                .background(accent)
                .height(6.dp)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val width = (constraints.maxWidth * progress).fastRoundToInt()
                    layout(width, placeable.height) { placeable.place(0, 0) }
                }
        )

        // 滑块
        Box(
            Modifier
                .offset(x = ((maxWidth - 24.dp) * progress))
                .size(width = 40.dp, height = 24.dp)
                .then(
                    if (enabled) {
                        Modifier
                            .draggable(
                                orientation = Orientation.Horizontal,
                                state = rememberDraggableState { delta ->
                                    didDrag = true
                                    onValueChange(
                                        (value + delta / trackWidthPx.coerceAtLeast(1f) *
                                            (valueRange.endInclusive - valueRange.start))
                                            .coerceIn(valueRange)
                                    )
                                },
                                onDragStarted = { didDrag = false },
                                onDragStopped = {
                                    didDrag = false
                                    onValueChangeFinished()
                                },
                            )
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                blur(8f.dp.toPx() * (1f - progress))
                                lens(
                                    10f.dp.toPx() * progress,
                                    14f.dp.toPx() * progress,
                                    chromaticAberration = true,
                                )
                            },
                            highlight = {
                                Highlight.Ambient.copy(
                                    width = Highlight.Ambient.width / 1.5f,
                                    blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                    alpha = progress,
                                )
                            },
                            shadow = {
                                Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.05f))
                            },
                            innerShadow = {
                                InnerShadow(radius = 4f.dp * progress, alpha = progress)
                            },
                            onDrawSurface = {
                                drawRect(Color.White.copy(alpha = 1f - progress))
                            },
                        )
                    } else {
                        Modifier
                            .clip(shape)
                            .background(Color.White)
                            .shadow(2.dp, shape)
                    }
                )
        )
    }
}

/** 底栏条目 */
data class LiquidBarItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/**
 * 液态玻璃按钮（胶囊）。
 *
 * 材质照官方 `LiquidButton`：
 * - `vibrancy() + blur(2.dp) + lens(12.dp, 24.dp)`
 * - `onDrawSurface` 画玻璃体；`tint` 走 `BlendMode.Hue` + 0.75 不透明度
 * - 高 48dp、横向 padding 16dp
 *
 * ### 关于 [backdrop] 为 null
 *
 * 官方 `LiquidButton` 强制要求 backdrop。但我们的按钮多数坐在**不透明卡片**上，
 * 卡片是纯色，折射一片纯色得到的还是纯色——`blur` / `lens` / `vibrancy`
 * 三样都看不出差别，唯一有效果的是 `Highlight` 的棱光。
 * 所以这里允许传 null：此时退化成「静态棱光」，观感与官方的卡片内按钮一致，
 * 又不会让按钮去采样包含自己的内容层（那会逐帧累积成拖影）。
 */
@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)

    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            // 官方 LiquidButton 的三个效果，参数照抄
                            vibrancy()
                            blur(2f.dp.toPx())
                            lens(12f.dp.toPx(), 24f.dp.toPx())
                        },
                        highlight = { Highlight.Default },
                        onDrawSurface = {
                            if (tint.isSpecified) {
                                drawRect(tint, blendMode = BlendMode.Hue)
                                drawRect(tint.copy(alpha = 0.75f))
                            }
                            if (surfaceColor.isSpecified) {
                                drawRect(surfaceColor)
                            }
                        },
                    )
                } else {
                    Modifier.glassStaticSurface(shape, tint, surfaceColor)
                }
            )
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .height(48.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 无 backdrop 时的静态玻璃表面。
 *
 * 官方 `LiquidButton` 的表面由 backdrop 提供，拿不到 backdrop 时它就没有底色。
 * 但按钮失去底色会退化成一行普通文字、完全看不出可点——所以这里必须兜底。
 *
 * 兜底色用官方的容器色 `#FAFAFA`@0.4 / `#121212`@0.4，而不是随便找个灰：
 * 这样它与官方带 backdrop 时的观感一致，只是少了折射与模糊。
 *
 * **不画任何自绘的棱光/描边**——自绘棱光与 `drawBackdrop` 的渲染几何对不上
 * （底栏那条白带就是这么来的），宁可少一层，也不要错位的一层。
 */
@Composable
private fun Modifier.glassStaticSurface(
    shape: androidx.compose.ui.graphics.Shape,
    tint: Color,
    surfaceColor: Color,
): Modifier = this
    .clip(shape)
    .background(
        when {
            surfaceColor.isSpecified -> surfaceColor
            tint.isSpecified -> tint.copy(alpha = 0.75f)
            else -> LiquidGlassColors.container()
        }
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
 *
 * ### 点击 + 横向拖动切换（统一手势）
 *
 * 一个 `detectHorizontalDragGestures` 同时承接「点按选 tab」与「横向拖动切 tab」：
 * - 拖动位移 < 12px 视为点按 → 按落点 x 算出命中的 tab
 * - 否则按拖动方向切到相邻 tab（左拖 → 右一个，右拖 → 左一个）
 * - 选中胶囊（单一覆盖层，非逐 item 自绘）用 `animatedOffset` 跟随手指，
 *   松手归零、由 spring 弹回新位置——这层「跟随」就是官方 `InteractiveHighlight` 的等价替代。
 *
 * 用 `rememberUpdatedState` 把 `selectedIndex` / `onSelect` 的最新值喂给手势闭包，
 * 这样 `pointerInput` 只需建一次（key = Unit），避免每次选中重建手势把进行中的拖动打断。
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
    val itemCount = items.size
    val density = LocalDensity.current
    val c = osColors()

    // 手势闭包要读的最新值（避免 pointerInput 重建打断拖动）
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // 拖动偏移（px），跟随手指；松手归零 → spring 弹回当前选中位
    var dragOffsetPx by remember { mutableStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = dragOffsetPx,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
        label = "barDragOffset",
    )
    // 点按落点的 x（px），用于判定命中哪个 tab
    var tapStartX by remember { mutableStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { tapStartX = it.x },
                    onHorizontalDrag = { _, dragAmount -> dragOffsetPx += dragAmount },
                    onDragEnd = {
                        val itemWidthPx = size.width / itemCount.toFloat()
                        if (kotlin.math.abs(dragOffsetPx) < 12f) {
                            // 视为点按：落点在第几个 tab
                            val idx = (tapStartX / itemWidthPx)
                                .toInt()
                                .coerceIn(0, itemCount - 1)
                            if (idx != currentSelected) currentOnSelect(idx)
                        } else {
                            // 视为拖动：左拖→右一个、右拖→左一个
                            val dir = if (dragOffsetPx < 0f) 1 else -1
                            val next = (currentSelected + dir).coerceIn(0, itemCount - 1)
                            if (next != currentSelected) currentOnSelect(next)
                        }
                        dragOffsetPx = 0f
                    },
                    onDragCancel = { dragOffsetPx = 0f },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val itemWidth = maxWidth / itemCount
        val itemWidthPx = with(density) { itemWidth.toPx() }
        val barHeight = 64.dp
        val totalWidthPx = itemWidthPx * itemCount

        // 玻璃底
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
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
                ),
        )

        // 选中胶囊（单一覆盖层，跟随手指）
        val pillX = (selectedIndex * itemWidthPx + animatedOffset)
            .coerceIn(0f, totalWidthPx - itemWidthPx)
        Box(
            Modifier
                .offset { IntOffset(pillX.toInt(), 0) }
                .width(itemWidth)
                .height(barHeight)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(percent = 50) },
                    effects = {
                        lens(10f.dp.toPx(), 14f.dp.toPx(), chromaticAberration = true)
                    },
                    highlight = { Highlight.Default },
                    shadow = { Shadow(alpha = 0.10f) },
                    innerShadow = { InnerShadow(radius = 8f.dp, alpha = 1f) },
                    onDrawSurface = { drawRect(accent.copy(alpha = 0.14f)) },
                ),
        )

        // 条目（图标 + 文字，纯展示，命中判定交给父层手势）
        Row(
            Modifier
                .fillMaxWidth()
                .height(barHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (selected) accent else c.textTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = item.label,
                        style = OsText.micro,
                        color = if (selected) accent else c.textTertiary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
