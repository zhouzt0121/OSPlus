package com.osplus.tools.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.ui.liquid.LiquidBottomTab as OriginalLiquidBottomTab
import com.osplus.tools.ui.liquid.LiquidBottomTabs as OriginalLiquidBottomTabs
import com.osplus.tools.ui.liquid.LiquidSlider as OriginalLiquidSlider
import com.osplus.tools.ui.liquid.LiquidToggle as OriginalLiquidToggle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 液态玻璃组件集 —— **原版组件的适配层**。
 *
 * 底栏 / 开关 / 滑块（[LiquidBottomBar] / [LiquidToggle] / [LiquidSlider]）
 * 本身不再自绘玻璃，材质与手势全部交给 `com.osplus.tools.ui.liquid` 下的
 * Kyant0 原版移植件（LiquidBottomTabs / LiquidToggle / LiquidSlider）。
 *
 * **按钮没有适配层**：全应用的按钮——页面内按钮、顶栏圆形 / 胶囊按钮、单选
 * ChoiceChip——一律由调用点直接引用原版 `LiquidButton`。原先的
 * `LiquidGlassButton` 包装已删除。原版签名有两处与旧适配层不同，调用点自己补齐：
 *
 * 1. **`backdrop` 非空**：拿不到根 backdrop 的卡片内按钮传官方 `emptyBackdrop()`，
 *    并显式给 `surfaceColor` 一层 `LiquidGlassColors.container()` —— 空 backdrop
 *    没有可折射的内容，不补底色按钮会退化成一行普通文字，看不出可点。
 * 2. **禁用态用 `isInteractive = false`**：原版没有 `enabled`，且该模式下它仍会
 *    照常回调 `onClick`，所以「无 Root 不执行」这类守卫必须写在 `onClick` 里，
 *    不能指望 `isInteractive` 把点击拦下来。
 *
 * ### 适配层做三件事
 *
 * 1. **补 backdrop**：卡片内的控件拿不到根 backdrop（它们位于被录制的
 *    内容层里，采样根 backdrop 会自采样、逐帧累积成拖影），统一传
 *    官方的 `emptyBackdrop()`。
 * 2. **补 enabled / onValueChangeFinished**：原版没有这两个参数，
 *    不改原版文件（保持与上游一致），在适配层模拟。
 * 3. **固定 lambda 身份**：原版把 `selectedTabIndex` / `value()` 这类
 *    lambda 当作 `remember` / `LaunchedEffect` 的 key，直接透传会在每次
 *    重组时重建内部状态，拖动一松手就丢。适配层用
 *    `rememberUpdatedState` + `remember` 把它们钉成稳定实例。
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

    // 深浅判定必须跟「应用主题」而不是系统主题：
    // 手动选择深色而系统是浅色时，玻璃若仍按系统取浅色会出现发灰的割裂感
    @Composable
    fun accent(): Color = if (osColors().isDark) accentDark else accentLight

    @Composable
    fun container(): Color = if (osColors().isDark) containerDark else containerLight

    @Composable
    fun track(): Color = if (osColors().isDark) trackDark else trackLight
}

/** 底栏条目 */
data class LiquidBarItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/**
 * 液态玻璃开关 → 原版 `LiquidToggle`。
 *
 * - [backdrop] 为 null 时传 `emptyBackdrop()`：开关坐在不透明卡片上，
 *   折射一片纯色没有意义，传根 backdrop 反而会让它去采样包含自己的内容层。
 *   thumb 仍然会折射自己的轨道层（`rememberCombinedBackdrop`），那是原版自带的。
 * - `enabled = false` 走 [StaticLiquidToggle]：原版没有 enabled 参数，
 *   而把回调吞掉会让内部 `fraction` 动画与外部状态失步（点一下动画动、
 *   状态不变，再点又从旧值起跳），所以禁用态干脆不渲染交互组件。
 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    enabled: Boolean = true,
) {
    if (!enabled) {
        StaticLiquidToggle(checked = checked, modifier = modifier)
        return
    }

    val checkedState = rememberUpdatedState(checked)
    val onChangeState = rememberUpdatedState(onCheckedChange)
    // 稳定身份：原版用 selected 作为 LaunchedEffect 的 key，
    // 每次重组换一个新 lambda 会把内部的 LaunchedEffect 全部重启。
    val stableSelected = remember { { checkedState.value } }
    val stableOnSelect = remember { { value: Boolean -> onChangeState.value(value) } }

    OriginalLiquidToggle(
        selected = stableSelected,
        onSelect = stableOnSelect,
        backdrop = backdrop ?: emptyBackdrop(),
        modifier = modifier,
    )
}

/** 无 Root 时开关的静态外观（保持与旧实现一致的观感）。 */
@Composable
private fun StaticLiquidToggle(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = osColors().isDark
    val accent = if (dark) LiquidGlassColors.switchAccentDark else LiquidGlassColors.switchAccentLight
    val trackColor = if (dark) LiquidGlassColors.trackDark else LiquidGlassColors.trackLight
    val shape = RoundedCornerShape(percent = 50)

    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
        label = "toggleFraction",
    )

    Box(
        modifier = modifier
            .size(width = 64.dp, height = 28.dp)
            .clip(shape)
            .background(lerp(trackColor, accent, fraction)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 2.dp)
                .offset(x = (20.dp * fraction))
                .size(width = 40.dp, height = 24.dp)
                .clip(shape)
                .background(Color.White)
                .shadow(2.dp, shape)
        )
    }
}

/**
 * 液态玻璃滑块 → 原版 `LiquidSlider`。
 *
 * 原版没有 `onValueChangeFinished`，而 GPU 频率上限 / swappiness / 充电电流上限
 * 三处都要靠它落盘。适配层用「值变化置脏 + 手势抬起回调」来补：
 * - 脏标记写在包住原版的 `onValueChange` 里，拖拽与点按两条路径都会经过它；
 * - 手势结束在 **Final pass** 观察（只观察、不消费事件），确保原版的
 *   `detectTapGestures` 已经回调过 `onValueChange`，脏标记一定已经置上。
 *
 * `enabled = false` 走 [StaticLiquidSlider]，理由同 [LiquidToggle]。
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
    if (!enabled) {
        StaticLiquidSlider(value = value, valueRange = valueRange, modifier = modifier)
        return
    }

    val valueState = rememberUpdatedState(value)
    val changeState = rememberUpdatedState(onValueChange)
    val finishedState = rememberUpdatedState(onValueChangeFinished)
    var dirty by remember { mutableStateOf(false) }

    // 稳定身份：原版把 value() / onValueChange 捕获进 remember(animationScope)，
    // 直接透传会让回调永远停在第一次重组的那一版。
    val stableValue = remember { { valueState.value } }
    val stableOnValueChange = remember {
        { newValue: Float ->
            dirty = true
            changeState.value(newValue)
        }
    }

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)

    OriginalLiquidSlider(
        value = stableValue,
        onValueChange = stableOnValueChange,
        valueRange = valueRange,
        visibilityThreshold = (span * 0.001f).coerceAtLeast(1e-6f),
        backdrop = backdrop ?: emptyBackdrop(),
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                waitForUpOrCancellation(pass = PointerEventPass.Final)
                if (dirty) {
                    dirty = false
                    finishedState.value()
                }
            }
        },
    )
}

/** 无 Root 时滑块的静态外观（保持与旧实现一致的观感）。 */
@Composable
private fun StaticLiquidSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val dark = osColors().isDark
    val accent = if (dark) LiquidGlassColors.accentDark else LiquidGlassColors.accentLight
    val trackColor = if (dark) LiquidGlassColors.trackDark else LiquidGlassColors.trackLight
    val shape = RoundedCornerShape(percent = 50)

    val progress = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .clip(shape)
                .background(trackColor)
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
        Box(
            Modifier
                .offset(x = ((maxWidth - 24.dp) * progress))
                .size(width = 40.dp, height = 24.dp)
                .clip(shape)
                .background(Color.White)
                .shadow(2.dp, shape)
        )
    }
}

/**
 * 液态玻璃底部导航栏 → 原版 `LiquidBottomTabs`。
 *
 * ### 视觉契约
 *
 * - 左右留白 16dp：原版的胶囊按下会放大到 78/56 ≈ 1.396，末格胶囊右缘
 *   会顶到面板边。面板比原版窄，留白不足时末格会贴到甚至越过屏幕右缘。
 * - 垂直 10dp + `windowInsetsPadding(navigationBars)`：edge-to-edge 下必须
 *   避让系统导航栏，三键导航会把整条压在按键下面导致点不到。
 * - 面板高度仍是 64dp，`bottomBarContentPadding()` 的 104dp 让位高度不变。
 * - 可见 Row 的四个图标 / 文字统一用 `textTertiary`，**不按选中态变色**：
 *   原版的选中蓝来自胶囊折射背后那份被 `ColorFilter` 染成 accent 的隐藏 Row，
 *   可见 Row 若自己再染一遍，未选中项就会跟着变色。
 */
@Composable
fun LiquidBottomBar(
    items: List<LiquidBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val selectedState = rememberUpdatedState(selectedIndex)
    val selectState = rememberUpdatedState(onSelect)
    // 稳定身份：原版用 selectedTabIndex 同时做 remember 与 LaunchedEffect 的 key。
    // 直接透传 { selectedIndex } 会让每次重组都重建 currentIndex，
    // 表现是「拖动松手后页面不切换」。
    val stableSelected = remember { { selectedState.value } }
    val stableOnSelected = remember { { index: Int -> selectState.value(index) } }
    val c = osColors()

    OriginalLiquidBottomTabs(
        selectedTabIndex = stableSelected,
        onTabSelected = stableOnSelected,
        backdrop = backdrop,
        tabsCount = items.size,
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        items.forEachIndexed { index, item ->
            OriginalLiquidBottomTab(onClick = { selectState.value(index) }) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = c.textTertiary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = item.label,
                    style = OsText.micro,
                    color = c.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}
