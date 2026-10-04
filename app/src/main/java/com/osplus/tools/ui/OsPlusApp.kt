package com.osplus.tools.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.osplus.tools.ui.components.LiquidBarItem
import com.osplus.tools.ui.components.LiquidBottomBar
import com.osplus.tools.ui.components.OsTopBar
import com.osplus.tools.ui.components.OsTopBarAction
import com.osplus.tools.ui.components.OsTopBarPillAction
import com.osplus.tools.ui.components.PageBackground
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

        // 预测性返回：优先退二级路由 → 其次回概览 → 已在概览则交还系统执行退出动画
        val backState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = backState,
            isBackEnabled = route != null || rootTab != RootTab.Overview,
            onBackCompleted = {
                if (routeStack.isNotEmpty()) popRoute() else rootTab = RootTab.Overview
            },
        )

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
        val backdrop = rememberLayerBackdrop()
        // 背景层单独记一次，只给顶栏按钮采样。
        //
        // 顶栏**在被记录的内容层内部**，如果直接采样 backdrop，
        // 采到的就是「包含顶栏自己」的上一帧，逐帧累积成拖影。
        // 官方 LiquidToggle 遇到同样问题时也是这么解的——它给轨道单独开一个
        // `trackBackdrop`，滑块再采样 `rememberCombinedBackdrop(backdrop, trackBackdrop)`。
        // 这里更简单：把纯背景单独录一层，顶栏按钮只采它。
        val topBarBackdrop = rememberLayerBackdrop()

        Box(modifier = Modifier.fillMaxSize()) {
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
                // 顶栏自带状态栏内边距，因此这里用 Column 而非 Box：
                // 顶栏占据固定高度，内容区吃掉剩余空间，页面内容永远不会钻到顶栏下面，
                // 也就不需要在每个页面里各写一遍顶部留白。
                Column(Modifier.fillMaxSize()) {
                    OsTopBar(
                        title = topTitle,
                        onBack = if (route != null) ({ popRoute() }) else null,
                        backdrop = topBarBackdrop,
                        actions = {
                            if (rootTab == RootTab.Overview && route == null) {
                                OsTopBarPillAction(
                                    icon = Icons.Rounded.CleaningServices,
                                    label = "清内存",
                                    contentDescription = "清理物理内存",
                                    enabled = rootAvailable,
                                    // 原版 isInteractive=false 不拦截点击，守卫写在 onClick 里
                                    onClick = { if (rootAvailable) viewModel.cleanMemCaches() },
                                    backdrop = topBarBackdrop,
                                )
                                OsTopBarPillAction(
                                    icon = Icons.Rounded.SwapHoriz,
                                    label = "清交换",
                                    contentDescription = "清理交换分区",
                                    enabled = rootAvailable,
                                    // 原版 isInteractive=false 不拦截点击，守卫写在 onClick 里
                                    onClick = { if (rootAvailable) viewModel.cleanSwap() },
                                    backdrop = topBarBackdrop,
                                )
                                OsTopBarAction(
                                    icon = Icons.Rounded.FiberManualRecord,
                                    contentDescription = if (fpsRecording) "停止记录帧率" else "记录帧率",
                                    tint = if (fpsRecording) c.red else null,
                                    onClick = {
                                        if (fpsRecording) {
                                            viewModel.stopFpsRecording()
                                        } else {
                                            // 开录后直接切到帧率页：那一页有实时条数与时长，
                                            // 用户能立刻确认「确实在记了」，而不是只有按钮变了个色
                                            viewModel.startFpsRecording()
                                            rootTab = RootTab.Fps
                                        }
                                    },
                                    backdrop = topBarBackdrop,
                                )
                                OsTopBarAction(
                                    icon = Icons.Rounded.Settings,
                                    contentDescription = "设置",
                                    onClick = { pushRoute(RouteSettings) },
                                    backdrop = topBarBackdrop,
                                )
                            }
                            // 电源页的「耗电统计」页签上，把复制 / 删除放在顶栏右侧：
                            // 这两个动作针对「本次记录」这个全局对象，属于页面级操作，
                            // 而页面内的操作条会随列表滚动跑出屏幕
                            if (rootTab == RootTab.Power && route == null && powerTab == 0) {
                                OsTopBarAction(
                                    icon = Icons.Rounded.ContentCopy,
                                    contentDescription = "复制本次耗电记录",
                                    enabled = powerHasRecord,
                                    // 原版 isInteractive=false 不拦截点击，守卫写在 onClick 里
                                    onClick = { if (powerHasRecord) viewModel.copyPowerRecord() },
                                    backdrop = topBarBackdrop,
                                )
                                OsTopBarAction(
                                    icon = Icons.Rounded.DeleteOutline,
                                    contentDescription = "删除本次耗电记录",
                                    enabled = powerHasRecord,
                                    // 原版 isInteractive=false 不拦截点击，守卫写在 onClick 里
                                    onClick = { if (powerHasRecord) viewModel.clearPowerRecord() },
                                    backdrop = topBarBackdrop,
                                )
                            }
                        },
                    )

                    Box(Modifier.weight(1f)) {
                        AnimatedContent(
                            targetState = screenKey,
                            transitionSpec = {
                                fadeIn(tween(200)) togetherWith fadeOut(tween(140))
                            },
                            label = "screen",
                        ) { key ->
                            // 内容完全由动画的 key 决定，避免过渡期间新旧页面串帧
                            val keyDetail = OverviewDetail.entries.firstOrNull { it.name == key }
                            val keyTab = RootTab.entries.firstOrNull { tabKey(it) == key }
                            Box(Modifier.fillMaxSize()) {
                                when {
                                    key == RouteSettings -> SettingsScreen(
                                        viewModel,
                                        onOpenLiquidLab = { pushRoute(RouteLiquidLab) },
                                        onOpenSystemToggles = { pushRoute(RouteSystemToggles) },
                                        onOpenPrivilege = { pushRoute(RoutePrivilege) },
                                    )

                                    key == RouteLiquidLab -> LiquidLabScreen()

                                    key == RouteSystemToggles -> SystemTogglesScreen(viewModel)

                                    key == RoutePrivilege -> PrivilegeScreen(viewModel)

                                    keyDetail != null -> when (keyDetail) {
                                        OverviewDetail.Memory -> MemDetailScreen(viewModel)
                                        OverviewDetail.Gpu -> GpuDetailScreen(viewModel)
                                        OverviewDetail.Cpu -> CpuDetailScreen(viewModel)
                                        OverviewDetail.Process -> ProcessDetailScreen(viewModel)
                                        OverviewDetail.Sched -> PerfSchedScreen(viewModel)
                                    }

                                    keyTab == RootTab.Overview -> OverviewScreen(
                                        vm = viewModel,
                                        onOpen = { pushRoute(it.name) },
                                        onOpenPower = { rootTab = RootTab.Power },
                                        onOpenFps = { rootTab = RootTab.Fps },
                                    )

                                    keyTab == RootTab.Perf -> PerfScreen(viewModel) { pushRoute(it.name) }
                                    keyTab == RootTab.Fps -> FpsScreen(viewModel)
                                    else -> PowerScreen(viewModel)
                                }
                            }
                        }
                    }
                }
            }

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
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
