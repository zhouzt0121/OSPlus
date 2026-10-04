package com.osplus.tools.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.ui.liquid.LiquidBottomTab
import com.osplus.tools.ui.liquid.LiquidBottomTabs
import com.osplus.tools.ui.liquid.LiquidButton
import com.kyant.backdrop.backdrops.emptyBackdrop
import top.yukonga.miuix.kmp.basic.Text

/**
 * 单选胶囊（原版 `LiquidButton`）：选中状态用「底色 + 文字色」整体变化表达，
 * 而不是在文案前打勾。
 *
 * 用于调速器、核心选择这类「一组互斥选项」。打勾会改变文案宽度，
 * 导致同一行的胶囊在选中/取消时宽度跳动；改成变色后文案始终不变，行宽稳定，
 * 选中项也更容易一眼扫到。
 *
 * 现在是原版 `LiquidButton`，与全应用按钮同源。两点需要调用方知道：
 *
 * - **尺寸**：原版固定 48dp 高 + 水平 16dp 内边距（组件内写死），
 *   比原先 12dp 圆角、9dp 纵向内边距的紧凑 chip 高不少，密集选项行会变高。
 * - **禁用态**：`isInteractive = false` 不拦截点击，「不可选时不执行」的守卫
 *   要写在 `onClick` 里；半透明由外层 `modifier.alpha()` 提供。
 *
 * ### 表面层的双轨着色（重要的工程权衡）
 *
 * 选中 / 未选中走**不同**的原版着色参数，原因是用单一参数想两全会失败：
 *
 * - `surfaceColor`：在 backdrop 上 `drawRect` 不透明覆盖。设了就**永远**有底色。
 *   选中用 `primary @ 0.13` 是原 ChoiceChip 一贯的「点蓝一下」效果，保留。
 * - `tint`：`BlendMode.Hue` 染色 + 0.75 alpha 覆盖。**纯色 backdrop 上 Hue
 *   染色无效**（白底无色相可改），但 0.75 alpha 覆盖会把玻璃染成 tint 的灰。
 *
 * 原版没 surfaceColor 时整个 chip 在「卡片白底」上是完全透明的——实测
 * `LiquidGlassColors.container()`（`#FAFAFA` @ 0.4）叠白底约等于白色，未选中
 * 态只剩一行悬空文字。所以这里用 tint 浅灰来保底可见性。
 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = osColors()
    val content by animateColorAsState(
        targetValue = if (selected) c.primary else c.textSecondary,
        label = "chipContent",
    )
    // 未选中态的灰。原版 tint 的覆盖 alpha 固定 0.75（见下方注释），浓度只能靠 tint
    // 颜色本身调：`0xFF808080` 叠出来约 #A0A0A0，实机看偏浓；换成 `0xFFD0D0D0`
    // 后约 #DCDCDC，是「看得出是个按钮」与「不抢选中项」之间的平衡点。
    // 深色主题反向取深灰——浅灰 tint 叠在深色卡片上会亮得刺眼。
    val unselectedTint = if (c.isDark) Color(0xFF3C3C3C) else Color(0xFFD0D0D0)
    LiquidButton(
        onClick = onClick,
        backdrop = emptyBackdrop(),
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        isInteractive = enabled,
        // 未选中：tint 浅灰触发 0.75 alpha 覆盖；空 backdrop 上 Hue 染色无效但灰覆盖有效
        tint = if (selected) Color.Unspecified else unselectedTint,
        // 选中：浅蓝半透明覆盖
        surfaceColor = if (selected) {
            c.primary.copy(alpha = if (c.isDark) 0.26f else 0.13f)
        } else {
            Color.Unspecified
        },
    ) {
        Text(
            text = text,
            style = OsText.label,
            color = content,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 分组卡片：卡片本体 + 卡内内容。
 *
 * 卡片**上方**不再渲染小节标题。全应用的统一规则是「页面身份归顶栏，分组说明归卡内」：
 * 页面标题由根布局的 `OsTopBar` 承担，分组说明由 [CardSectionLabel] 写在卡片内部，
 * 于是卡与卡之间只靠留白分隔，纵向节奏干净，也不会出现「顶栏写着帧率、下面又挂一行帧率」的重复。
 *
 * 传入 [onClick] 表示该卡片可下钻到详情页；卡片本身不画任何角标。
 *
 * [contentHeight] 给卡内内容一个**最小高度**，用来让并排的卡片等高。
 * 网格里各格内容天然高度不同（环卡是 96dp 的环，折线卡是「表头 + 折线 + 时间轴」），
 * 不约束就会一格高、一格矮，2×2 的横线对不齐。
 * 用「约束内容高度」而不是「给卡片写死高度」：内容超出时卡片仍会自然长高，不会裁切。
 *
 * 这里必须 `fillMaxWidth()` —— 包装用的 `Box` 若不定宽，宽度会收紧到内容的自然宽度，
 * 于是整块内容贴到卡内左侧，调用方的 `Modifier.align(CenterHorizontally)` 也只能在
 * 那个收窄的宽度里居中（等于没居中），帧率卡这类 `fillMaxWidth()` 的内容更会只填到一半。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentHeight: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OsCard(onClick = onClick) {
            if (contentHeight != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = contentHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        content = content,
                    )
                }
            } else {
                content()
            }
        }
    }
}

/**
 * 卡片内的分组小标签。
 *
 * 二级详情页去掉了卡片外部的小节标题后，纯控制型卡片（调速器、频率上限等）
 * 只剩下一排胶囊和滑块，看不出这组控件是干什么的。这里在卡片内部补一行小字说明，
 * 它比原来的外部标题更轻——不额外占一行卡片外空间，视觉上仍是「一张卡一个整体」。
 */
@Composable
fun CardSectionLabel(text: String) {
    Text(
        text = text,
        style = OsText.value,
        color = osColors().textPrimary,
    )
}

/** 左标签右数值的信息行 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    labelColor: Color? = null,
    emphasis: Boolean = false,
) {
    val c = osColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = OsText.label,
            color = labelColor ?: c.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = if (emphasis) OsText.valueStrong else OsText.value,
            color = valueColor ?: if (emphasis) c.textPrimary else c.textSecondary,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/**
 * 规格网格：两列「标签在上、数值在下」的小格。
 *
 * ### 为什么不用 [InfoRow] 的「标签左、数值右」长列表
 *
 * 设备概况有 9 项。用行式排下来会得到一条又长又平的列表：
 * 每行的标签左对齐、数值右对齐，中间是一大片空白，
 * 九行之间没有任何分组关系——读者只能一行行扫，且「芯片规格」「内存容量」
 * 「电池损耗」这三类完全混在一起。
 *
 * 改成两列网格后：
 * - 标签与数值**上下相邻**，视线不用横扫整屏；
 * - 一行两格，同样的信息量只占 5 行，卡更矮、页面更透气；
 * - 配合分组标题（芯片 / 存储 / 电池），三类信息各归其位。
 *
 * 数值用 `maxLines = 1` + 省略号：网格宽度固定，长文案（如 SoC 型号）
 * 溢出时必须截断而不是换行——换行会把整行的两格撑成不等高。
 */
@Composable
fun SpecGrid(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val c = osColors()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        items.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = OsText.micro,
                            color = c.textTertiary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = value,
                            style = OsText.value,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 奇数项时补一个空位，否则最后一行的那一格会被拉伸成整行宽
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 进度行：标签 + 数值 + 圆角进度条。
 * 对应参考图中「物理内存 / 交换分区」那一类的行式用量展示。
 *
 * [trailing] 用于在数值右侧挂一个操作按钮（如内存行的「清理」）。
 */
/** 带开关的设置行 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val c = osColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = OsText.label,
                color = c.textPrimary,
            )
            summary?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = OsText.caption,
                    color = c.textSecondary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // 换成官方 LiquidToggle 的材质。
        //
        // 全应用的开关都走这一行，所以改这里就等于把 6 处 SwitchRow 全换了。
        // 不传 backdrop：开关坐在不透明卡片上，折射一片纯色没有意义，
        // 反而会让它去采样包含自己的内容层、逐帧累积成拖影。
        LiquidToggle(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** 指标磁贴：小标签 + 大数值 + 单位 + 说明 */
/** 横向占用条：圆角轨道 + 圆角填充 */
@Composable
fun UsageBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 7.dp,
) {
    val c = osColors()
    val shape = RoundedCornerShape(barHeight / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(c.track, shape)
    ) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(f)
                    .height(barHeight)
                    .background(
                        Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.72f), color)
                        ),
                        shape,
                    )
            )
        }
    }
}

/**
 * 分段控件。
 *
 * 浅色凹槽 + 白色滑块，选中项用品牌色文字；
 * 比 Miuix TabRow 更贴合本应用的视觉语言，也与卡片系统同源。
 */
/** 提示条：用于展示权限缺失、写入未生效等状态 */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFF0A868),
) {
    val c = osColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = if (c.isDark) 0.16f else 0.13f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(6.dp)
                .height(6.dp)
                .background(accent, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            style = OsText.caption,
            color = c.textPrimary,
        )
    }
}

/** 健康结论的严重程度 */
enum class HealthLevel { Ok, Warn, Danger }

/**
 * 各级页面 LazyColumn 的统一 contentPadding。
 *
 * 底部 = 悬浮底栏的让位高度（栏体 64dp + 上下 10dp padding + 余量 ≈ 104dp）
 * **再叠加系统导航栏内边距**——底栏自身用 `windowInsetsPadding(navigationBars)`
 * 避让系统导航栏后整体上移，列表的让位高度也要跟着加，否则三键导航的设备上
 * 最后一张卡片仍会被底栏遮住。
 */
@Composable
fun bottomBarContentPadding(): PaddingValues {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp + navBottom)
}

/**
 * 健康结论条。
 *
 * 概览页的第一行不再是某张具体卡片，而是一句「系统现在到底怎么样」的判断。
 * 用户打开监控应用时最常问的是「有没有问题」，先给结论、再给数字，
 * 比让他自己从四张卡的百分比里反推要快得多——四张卡负责「是多少」，
 * 这一条负责「算不算正常」。
 */
@Composable
fun HealthBanner(
    level: HealthLevel,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val c = osColors()
    val accent = when (level) {
        HealthLevel.Ok -> c.green
        HealthLevel.Warn -> c.orange
        HealthLevel.Danger -> c.red
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = if (c.isDark) 0.18f else 0.12f))
            .border(0.7.dp, accent.copy(alpha = 0.34f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(accent, CircleShape))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = OsText.valueStrong,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = OsText.caption,
                    color = c.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 分段选项条 —— **原版 `LiquidBottomTabs`**，与底部悬浮导航栏同源。
 *
 * 全应用凡「一组互斥按钮」（性能页观察窗口、帧率分析窗口、电源页签、主题模式、
 * 提权方式等）统一用这一个组件，渲染全部交给 `com.osplus.tools.ui.liquid` 下的
 * Kyant0 原版移植件。保留本组件名与原签名，是为了让 13 处调用点一行都不用改
 * （与 [LiquidBottomBar] / [LiquidToggle] / [LiquidSlider] 的适配层同一手法）。
 *
 * 动作型按钮组（导出 CSV、权限授权等）也可复用：传 [selectedIndex] = -1，
 * 点按回调直接执行动作。
 *
 * ### 适配层做三件事
 *
 * 1. **backdrop 兜底**：调用点都在卡片内，拿不到根 backdrop，统一传 `emptyBackdrop()`。
 *    原版面板自带 `onDrawSurface` 的容器色兜底，没有折射也看得见。
 * 2. **钉死 lambda 身份**：原版把 `selectedTabIndex` 同时当作 `remember` 与
 *    `LaunchedEffect` 的 key，直接透传 `{ selectedIndex }` 会在每次重组时重建内部
 *    状态，表现是「切换后胶囊弹回原位」。这里用 `rememberUpdatedState` + `remember`
 *    把它们固定成稳定实例。
 * 3. **补 enabled、选中染色与动作组胶囊**：原版没有 `enabled`；且它的选中色来自
 *    「隐藏 Row 被 `ColorFilter` 染成 accent 后再被胶囊折射」，卡片内没有可折射的
 *    背景，这条链不成立——所以选中/禁用都由适配层按索引自己表达。
 *
 * ### 动作组（selectedIndex = -1）的胶囊必须自己跟
 *
 * 原版胶囊的位置**完全**由 `selectedTabIndex` 驱动。动作组恒传 -1 → 原版永远收到
 * 索引 0 → 胶囊永久停在第一格，点「导出 CSV / 清空记录」这种按钮时动作执行了、
 * 胶囊却不动（用户反馈：「点击有反应，但是胶囊不会过去」）。
 *
 * 旧的自绘版用 `lastPressed` 解决了这件事：动作组时胶囊跟随最近一次按下的项。
 * 这里在适配层补回同一语义——[lastPressed] 只在动作组路径上被写入，选择组仍由
 * 外部的 `selectedIndex` 决定。
 */
@Composable
fun LiquidNavTabs(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = osColors()
    val itemCount = items.size.coerceAtLeast(1)
    // 动作组（selectedIndex < 0）时原版需要一个合法索引；高亮另按 selectedIndex 判
    val hasSelection = selectedIndex >= 0
    val safeIndex = selectedIndex.coerceIn(0, itemCount - 1)

    // 动作组的胶囊落点：只在点击动作项时写入，选择组路径不碰它。
    // 初值 -1 = 尚未按过任何动作项，此时不高亮任何一项（与旧自绘版一致）。
    val lastPressed = remember { mutableIntStateOf(-1) }
    val pillIndex = if (hasSelection) safeIndex else lastPressed.intValue
    // 原版要求一个合法索引，未选/越界时钳到 0
    val effectiveIndex = pillIndex.coerceIn(0, itemCount - 1)

    val indexState = rememberUpdatedState(effectiveIndex)
    val selectState = rememberUpdatedState(onSelect)
    val enabledState = rememberUpdatedState(enabled)
    val hasSelectionState = rememberUpdatedState(hasSelection)
    val stableSelected = remember { { indexState.value } }
    // 禁用时吞掉回调：原版没有 enabled，只能在这一层拦
    val stableOnSelect = remember {
        { index: Int ->
            if (enabledState.value) {
                // 动作组：记下落点，胶囊跟着移过去（选择组由外部状态驱动，不写）
                if (!hasSelectionState.value) lastPressed.intValue = index
                selectState.value(index)
            }
        }
    }

    LiquidBottomTabs(
        selectedTabIndex = stableSelected,
        onTabSelected = stableOnSelect,
        backdrop = emptyBackdrop(),
        tabsCount = itemCount,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        items.forEachIndexed { index, label ->
            LiquidBottomTab(onClick = { stableOnSelect(index) }) {
                // 选择组高亮外部选中项；动作组高亮最近按下的项（pillIndex = -1 时都不亮）
                val highlighted = index == pillIndex
                Text(
                    text = label,
                    style = OsText.label,
                    color = if (highlighted) c.primary else c.textSecondary,
                    fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 简易输入框：项目里没有通用 TextField，这里用最小实现。
 *
 * 原先是 ADB 配对表单里的私有用具（名为 AdbInputField），
 * ADB 通道移除后被「功率校准」的电流倍率输入复用，
 * 因此移到这里并改成与用途无关的名字。
 */
@Composable
fun NumberInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
) {
    val c = osColors()
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = OsText.micro, color = c.textTertiary)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(c.cardAlt)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            if (value.isEmpty()) {
                Text("—", style = OsText.value, color = c.textTertiary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = OsText.value.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
