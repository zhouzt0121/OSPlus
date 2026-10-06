package com.osplus.tools.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.osplus.tools.ui.components.LiquidBarItem
import com.osplus.tools.ui.components.LiquidBottomBar
import com.osplus.tools.ui.components.OsTopBar
import com.osplus.tools.ui.components.OsTopBarAction
import com.osplus.tools.ui.components.OsTopBarPillAction
import com.osplus.tools.ui.components.PageBackground
import com.osplus.tools.ui.components.rememberPredictiveBackState
import com.osplus.tools.ui.screen.CpuDetailScreen
import com.osplus.tools.ui.screen.FpsScreen
import com.osplus.tools.ui.screen.LiquidLabScreen
import com.osplus.tools.ui.screen.GpuDetailScreen
import com.osplus.tools.ui.screen.MemDetailScreen
import com.osplus.tools.ui.screen.OverviewDetail
import com.osplus.tools.ui.screen.OverviewScreen
import com.osplus.tools.ui.screen.PerfScreen
import com.osplus.tools.ui.screen.PerfSchedScreen
import com.osplus.tools.ui.screen.PowerScreen
import com.osplus.tools.ui.screen.PrivilegeScreen
import com.osplus.tools.ui.screen.ProcessDetailScreen
import com.osplus.tools.ui.screen.SettingsScreen
import com.osplus.tools.ui.screen.SystemTogglesScreen
import com.osplus.tools.ui.theme.OSPlusTheme
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop

/**
 * 底部悬浮导航的一级页面。
 *
 * 四页全部留给监控数据：概览 / 性能 / 帧率 / 电源。
 * 设置被移出导航栏，改挂顶栏右侧的动作区——它是低频入口，
 * 占用底栏的一格等于把 25% 的导航面积交给了一个月可能只点一次的功能。
 */
private enum class RootTab { Overview, Perf, Fps, Power }

/** 一级页之外的二级路由。设置与详情页共用同一层，保证返回手势行为一致。 */
private const val RouteSettings = "Settings"
private const val RouteLiquidLab = "LiquidLab"
private const val RouteSystemToggles = "SystemToggles"
private const val RoutePrivilege = "Privilege"

private fun tabKey(tab: RootTab) = "tab-${tab.name}"

/**
 * 应用根布局。
 *
 * 结构：**统一顶栏** + 底部悬浮导航（概览 / 性能 / 帧率 / 电源）
 * + 从概览与性能卡片下钻的二级详情页（内存 / GPU / CPU / 进程）+ 设置页。
 *
 * 顶栏由根布局渲染**唯一一次**，各页面只负责内容，不再自带标题。
 * 这样做的直接收益是尺寸天然一致：标题字号、按钮直径、左右内边距、
 * 状态栏内边距都来自 [OsTopBar] 内部的同一组常量，
 * 不会出现「概览页 56dp、帧率页 60dp」这类只在真机上才看得出来的错位。
 *
 * 概览页的顶栏右侧并排放四个动作：清理内存 / 清理交换 / 记录帧率 / 设置。
 * 它们原先散在页面底部，需要滚到末尾才点得到；移到顶栏后无论滚到哪一屏都够得着，
 * 也把首屏整块让给了数据本身。
 *
 * 返回手势优先退二级路由，其次回概览，最后交还系统执行退出动画。
 */
@Composable
fun OsPlusApp(viewModel: DeviceViewModel = viewModel()) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val monet by viewModel.monet.collectAsStateWithLifecycle()

    OSPlusTheme(mode = themeMode, monet = monet) {
        var rootTab by rememberSaveable { mutableStateOf(RootTab.Overview) }
        // 二级路由栈。**必须是栈而不是单个值**：
        // 「设置 → 系统开关」是三级导航，用一个 `route` 变量会把「设置」覆盖掉，
        // 于是从系统开关按返回直接落到一级页（概览/性能…），而不是回到设置页。
        // 同理「设置 → 提权管理」「设置 → Kyant 实验室」也一样。
        // 用一个 List 记录完整路径，返回时弹栈，层级语义才对得上。
        var routeStack by rememberSaveable { mutableStateOf(listOf<String>()) }
        val route: String? = routeStack.lastOrNull()
        fun pushRoute(r: String) { routeStack = routeStack + r }
        fun popRoute() { if (routeStack.isNotEmpty()) routeStack = routeStack.dropLast(1) }

        val detail = route?.let { name ->
            OverviewDetail.entries.firstOrNull { it.name == name }
        }
        val settingsOpen = route == RouteSettings

        // 顶栏动作需要知道权限与录制状态：清理类动作在无 Root 时置灰，
        // 记录按钮在录制中改红底以表达「再点一下是停止」。
        val rootAvailable by viewModel.rootAvailable.collectAsStateWithLifecycle()
        val fpsRecording by viewModel.fpsRecording.collectAsStateWithLifecycle()
        // 电源页顶栏动作需要知道当前页签与是否已有记录。
        // 只订阅布尔量：直接订阅每秒变化的样本列表会让整个根布局跟着每秒重组
        val powerTab by viewModel.powerTab.collectAsStateWithLifecycle()
        val powerHasRecord by viewModel.powerHasRecord.collectAsStateWithLifecycle()
        val c = osColors()

        // 预测性返回：优先退二级路由 → 其次回概览 → 已在概览则交还系统执行退出动画。
        //
        // 这里用 rememberPredictiveBackState 而不是裸的 NavigationBackHandler，
        // 因为需要**手势进度**来驱动覆盖层的跟手位移（见下方 backProgress）。
        val backState = rememberPredictiveBackState(
            isBackEnabled = route != null || rootTab != RootTab.Overview,
        ) {
            if (routeStack.isNotEmpty()) popRoute() else rootTab = RootTab.Overview
        }
        // 0f = 完全展开（覆盖层铺满），1f = 已退回（覆盖层让开）。
        // 手势进行中由系统给的实时进度驱动，松手后由内部动画器平滑收尾。
        val backProgress = backState.progress

        // 二级/三级页的 AnimatedContent key。
        // 取栈顶路由即可：栈内切换（设置 → 系统开关）也走这个 key，
        // 与一级页的 rootTab key 是两套独立的动画槽。
        val screenKey = route ?: tabKey(rootTab)

        // 顶栏标题：二级页取枚举里的文案（与页面同一份定义，不会漂移），一级页取页签名
        val topTitle = when {
            settingsOpen -> "设置"
            route == RouteSystemToggles -> "系统开关"
            route == RoutePrivilege -> "提权管理"
            detail != null -> detail.title
            rootTab == RootTab.Overview -> "概览"
            rootTab == RootTab.Perf -> "性能"
            rootTab == RootTab.Fps -> "帧率"
            else -> "电源"
        }

        // 内容层先渲染进 GraphicsLayer 并记录下来，悬浮控件据此做真实背景模糊（液态玻璃）。
        // 记录的是「背景 + 顶栏 + 页面内容」整层，不含底部悬浮条自身——否则它会把自己的高光也糊进去。
        //
        // **一个 LayerBackdrop 实例只能被一处 layerBackdrop 绑定。**
        // 库的 LayerBackdropNode 在 onDetach 时会把共享的 layerCoordinates 置 null，
        // 而 LayerBackdrop.drawBackdrop 对坐标**不做空校验**（直接 positionInWindow），
        // 两个节点绑同一实例时，任一节点卸载就会让另一个在绘制期间 NPE。
        // 2.8.0 初次实现曾在底层与浮层各绑一次 backdrop，route 非空（rememberSaveable
        // 恢复上次页面）时启动即闪退，就是踩了这条。因此下面按层各建独立实例。
        val backdrop = rememberLayerBackdrop()
        // 背景层单独记一次，只给顶栏按钮采样。
        //
        // 顶栏**在被记录的内容层内部**，如果直接采样 backdrop，
        // 采到的就是「包含顶栏自己」的上一帧，逐帧累积成拖影。
        // 官方 LiquidToggle 遇到同样问题时也是这么解的——它给轨道单独开一个
        // `trackBackdrop`，滑块再采样 `rememberCombinedBackdrop(backdrop, trackBackdrop)`。
        // 这里更简单：把纯背景单独录一层，顶栏按钮只采它。
        val topBarBackdrop = rememberLayerBackdrop()

        // 路由浮层**不建 backdrop**。
        //
        // 浮层内的玻璃控件（页面里的按钮 / 开关）一律传 emptyBackdrop() 兜底，
        // 这是项目既有约定（README §16），因此没有任何消费者需要录制。
        // 更重要的是**不能录**：浮层根节点带 graphicsLayer 变换，
        // 把 layerBackdrop 挂在变换子树里会让 RenderNode 树自我引用、
        // prepareTree 无限递归直至 RenderThread 栈溢出（已实测，见下方注释）。

        Box(modifier = Modifier.fillMaxSize()) {
            // ── 底层：一级页（概览 / 性能 / 帧率 / 电源）────────────────────
            //
            // 它**始终**铺在最底下，二级页是盖在它上面的浮层。
            // 这是预测性返回能成立的结构前提：手势往右拖时，覆盖层让开，
            // 底下这一页必须已经在渲染着，用户才看得到「上一页正回来」。
            // 若两级页面共用一个 AnimatedContent 插槽（改造前的写法），
            // 让开之后下面什么都没有，动画会露黑底。
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(topBarBackdrop),
                ) {
                    PageBackground()
                }

                Column(Modifier.fillMaxSize()) {
                    RootTopBar(
                        rootTab = rootTab,
                        onOpenSettings = { pushRoute(RouteSettings) },
                        onStartFps = {
                            viewModel.startFpsRecording()
                            rootTab = RootTab.Fps
                        },
                        backdrop = topBarBackdrop,
                        rootAvailable = rootAvailable,
                        fpsRecording = fpsRecording,
                        powerTab = powerTab,
                        powerHasRecord = powerHasRecord,
                        onCleanMem = { viewModel.cleanMemCaches() },
                        onCleanSwap = { viewModel.cleanSwap() },
                        onStopFps = { viewModel.stopFpsRecording() },
                        onCopyPowerRecord = { viewModel.copyPowerRecord() },
                        onClearPowerRecord = { viewModel.clearPowerRecord() },
                    )

                    Box(Modifier.weight(1f)) {
                        AnimatedContent(
                            targetState = rootTab,
                            transitionSpec = {
                                fadeIn(tween(200)) togetherWith fadeOut(tween(140))
                            },
                            label = "rootTab",
                        ) { tab ->
                            Box(Modifier.fillMaxSize()) {
                                when (tab) {
                                    RootTab.Overview -> OverviewScreen(
                                        vm = viewModel,
                                        onOpen = { pushRoute(it.name) },
                                        onOpenPower = { rootTab = RootTab.Power },
                                        onOpenFps = { rootTab = RootTab.Fps },
                                    )

                                    RootTab.Perf -> PerfScreen(viewModel) { pushRoute(it.name) }
                                    RootTab.Fps -> FpsScreen(viewModel)
                                    RootTab.Power -> PowerScreen(viewModel)
                                }
                            }
                        }
                    }
                }
            }

            // 底栏与上面的录制层是**兄弟节点**，不是子节点。
            //
            // 这一点是硬约束：底栏自己采样 `backdrop` 做折射，
            // 如果它同时位于 `layerBackdrop(backdrop)` 的录制子树内，
            // 录制层就包含了一个采样自己的组件 —— RenderNode 树自我引用，
            // prepareTree 遍历时无限递归，RenderThread 栈溢出直接 SIGSEGV
            // （实测 tombstone: LinearAllocator::allocImpl ← RenderNode::prepareTreeImpl ×500）。
            // 改造前底栏本来就在录制层外面，是 2.8.0 把它挪进 Box 时踩到的。
            //
            // 位置靠 align 保持底部居中，视觉与之前完全一致。
            LiquidBottomBar(
                items = listOf(
                    // 用 Filled 而不是 Rounded：底栏图标只有 22dp，
                    // 描边式在这么小的尺寸上线条会细到发虚、四个图标粗细也不一致；
                    // 填充式在 22dp 下的辨识度明显更高。
                    LiquidBarItem("概览", Icons.Filled.Dashboard),
                    LiquidBarItem("性能", Icons.Filled.Speed),
                    LiquidBarItem("帧率", Icons.AutoMirrored.Filled.ShowChart),
                    LiquidBarItem("电源", Icons.Filled.BatteryFull),
                ),
                selectedIndex = rootTab.ordinal,
                onSelect = {
                    rootTab = RootTab.entries[it]
                    // 切一级页签时清空整条路由栈：栈里是上一个页签的二级/三级页面，
                    // 留着它们会让新页签一进去就直接显示旧页签的子页面
                    routeStack = emptyList()
                },
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = 1f - backProgress },
            )

            // ── 上层：二级/三级路由浮层 ───────────────────────────────────
            //
            // 只在存在路由时存在。整层跟手右移 + 圆角化 + 轻微缩小，
            // 缩小量给底层一点点「露出感」，是 Android 14 原生返回预览的观感。
            //
            // 位移量是屏幕宽度的 32%：走满 32% 时覆盖层基本让开、
            // 底层页面主体完全可见，同时手指不需要拖到屏幕另一头。
            if (route != null) {
                // 位移基准取屏幕宽度。用 LocalConfiguration 的 dp 值换算，
                // 而不是读 Layout 尺寸——后者在首帧拿不到，会导致第一次手势位移为 0。
                val density = LocalDensity.current
                val screenWidthPx = with(density) {
                    LocalConfiguration.current.screenWidthDp.dp.toPx()
                }
                val maxCorner = remember { 28.dp }

                // **录制节点必须留在变换子树之外。**
                //
                // 这里踩过一次 RenderThread 栈溢出（SIGSEGV in LinearAllocator::allocImpl ←
                // RenderNode::prepareTreeImpl 无限递归 500+ 帧，见 tombstone_23）：
                // 最初的写法是 `Box(graphicsLayer{...}) { Box(layerBackdrop(sheet)) {...} }`，
                // 把一个持有 GraphicsLayer 的录制节点套进了带 `graphicsLayer` 变换的父节点里，
                // RenderNode 树因此自我引用，prepareTree 遍历时无限递归直至栈溢出。
                //
                // 正确结构：**外层只负责变换、内层只负责录制**，两者是兄弟语义而非嵌套，
                // 且录制层的坐标不参与父层变换的反算。
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = backProgress * screenWidthPx * 0.32f
                            val scale = 1f - backProgress * 0.06f
                            scaleX = scale
                            scaleY = scale
                            // 圆角随进度长出来。静止态（progress=0）圆角为 0，
                            // 页面四角不会被切；只有手势真的开始推才逐渐变圆。
                            shape = RoundedCornerShape(maxCorner * backProgress)
                            clip = backProgress > 0.001f
                            // 让开时轻微压暗，强化「它在被推走」的层次
                            alpha = 1f - backProgress * 0.12f
                        },
                ) {
                    // 内容层：**不再挂 layerBackdrop**。
                    // 浮层内的玻璃控件一律传 emptyBackdrop() 兜底（项目既有约定），
                    // 因此这里没有任何消费者需要录制。
                    Box(Modifier.fillMaxSize()) {
                        PageBackground()
                        Column(Modifier.fillMaxSize()) {
                            OsTopBar(
                                title = topTitle,
                                onBack = { popRoute() },
                                backdrop = emptyBackdrop(),
                            )
                            Box(Modifier.weight(1f)) {
                                AnimatedContent(
                                    targetState = screenKey,
                                    transitionSpec = {
                                        fadeIn(tween(200)) togetherWith fadeOut(tween(140))
                                    },
                                    label = "routeScreen",
                                ) { key ->
                                    val keyDetail = OverviewDetail.entries
                                        .firstOrNull { it.name == key }
                                    Box(Modifier.fillMaxSize()) {
                                        when {
                                            key == RouteSettings -> SettingsScreen(
                                                viewModel,
                                                onOpenLiquidLab = { pushRoute(RouteLiquidLab) },
                                                onOpenSystemToggles = {
                                                    pushRoute(RouteSystemToggles)
                                                },
                                                onOpenPrivilege = { pushRoute(RoutePrivilege) },
                                            )

                                            key == RouteLiquidLab -> LiquidLabScreen()

                                            key == RouteSystemToggles ->
                                                SystemTogglesScreen(viewModel)

                                            key == RoutePrivilege -> PrivilegeScreen(viewModel)

                                            keyDetail != null -> when (keyDetail) {
                                                OverviewDetail.Memory -> MemDetailScreen(viewModel)
                                                OverviewDetail.Gpu -> GpuDetailScreen(viewModel)
                                                OverviewDetail.Cpu -> CpuDetailScreen(viewModel)
                                                OverviewDetail.Process ->
                                                    ProcessDetailScreen(viewModel)

                                                OverviewDetail.Sched -> PerfSchedScreen(viewModel)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一级页顶栏。
 *
 * 从 [OsPlusApp] 主体里抽出来，有两个原因：
 *
 * 1. 主体现在要同时处理「底层一级页」与「上层路由浮层」两套布局，
 *    顶栏动作那一大段（四个概览动作 + 两个电源动作）再内联会把结构彻底埋掉；
 * 2. 一级页顶栏与二级页顶栏的**动作区完全不同**——一级页有清理/录制/设置，
 *    二级页只有一个返回箭头。分开写比在一个 `OsTopBar` 里塞条件分支清楚。
 *
 * 注意这里**不接收 route**：一级页顶栏只在底层出现，
 * 路由是否打开由 [OsPlusApp] 决定用哪一层顶栏。
 */
@Composable
private fun RootTopBar(
    rootTab: RootTab,
    onOpenSettings: () -> Unit,
    onStartFps: () -> Unit,
    onStopFps: () -> Unit,
    onCleanMem: () -> Unit,
    onCleanSwap: () -> Unit,
    onCopyPowerRecord: () -> Unit,
    onClearPowerRecord: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop,
    rootAvailable: Boolean,
    fpsRecording: Boolean,
    powerTab: Int,
    powerHasRecord: Boolean,
) {
    val c = osColors()
    OsTopBar(
        title = when (rootTab) {
            RootTab.Overview -> "概览"
            RootTab.Perf -> "性能"
            RootTab.Fps -> "帧率"
            RootTab.Power -> "电源"
        },
        backdrop = backdrop,
        actions = {
            if (rootTab == RootTab.Overview) {
                OsTopBarPillAction(
                    icon = Icons.Rounded.CleaningServices,
                    label = "清内存",
                    contentDescription = "清理物理内存",
                    enabled = rootAvailable,
                    // 原版 isInteractive=false 不拦截点击，守卫写在 onClick 里
                    onClick = { if (rootAvailable) onCleanMem() },
                    backdrop = backdrop,
                )
                OsTopBarPillAction(
                    icon = Icons.Rounded.SwapHoriz,
                    label = "清交换",
                    contentDescription = "清理交换分区",
                    enabled = rootAvailable,
                    // 原版 isInteractive=false 不拦截点击，守卫写在 onClick 里
                    onClick = { if (rootAvailable) onCleanSwap() },
                    backdrop = backdrop,
                )
                OsTopBarAction(
                    icon = Icons.Rounded.FiberManualRecord,
                    contentDescription = if (fpsRecording) "停止记录帧率" else "记录帧率",
                    tint = if (fpsRecording) c.red else null,
                    onClick = { if (fpsRecording) onStopFps() else onStartFps() },
                    backdrop = backdrop,
                )
                OsTopBarAction(
                    icon = Icons.Rounded.Settings,
                    contentDescription = "设置",
                    onClick = onOpenSettings,
                    backdrop = backdrop,
                )
            }
            // 电源页的「耗电统计」页签上，把复制 / 删除放在顶栏右侧：
            // 这两个动作针对「本次记录」这个全局对象，属于页面级操作，
            // 而页面内的操作条会随列表滚动跑出屏幕
            if (rootTab == RootTab.Power && powerTab == 0) {
                OsTopBarAction(
                    icon = Icons.Rounded.ContentCopy,
                    contentDescription = "复制本次耗电记录",
                    enabled = powerHasRecord,
                    // 原版 isInteractive=false 不拦截点击，守卫写在 onClick 里
                    onClick = { if (powerHasRecord) onCopyPowerRecord() },
                    backdrop = backdrop,
                )
                OsTopBarAction(
                    icon = Icons.Rounded.DeleteOutline,
                    contentDescription = "删除本次耗电记录",
                    enabled = powerHasRecord,
                    // 原版 isInteractive=false 不拦截点击，守卫写在 onClick 里
                    onClick = { if (powerHasRecord) onClearPowerRecord() },
                    backdrop = backdrop,
                )
            }
        },
    )
}

