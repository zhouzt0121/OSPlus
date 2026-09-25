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
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
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
import com.osplus.tools.ui.components.BarItem
import com.osplus.tools.ui.components.OsFloatingBottomBar
import com.osplus.tools.ui.components.OsTopBar
import com.osplus.tools.ui.components.OsTopBarAction
import com.osplus.tools.ui.components.PageBackground
import com.osplus.tools.ui.screen.CpuDetailScreen
import com.osplus.tools.ui.screen.FpsScreen
import com.osplus.tools.ui.screen.GpuDetailScreen
import com.osplus.tools.ui.screen.MemDetailScreen
import com.osplus.tools.ui.screen.OverviewDetail
import com.osplus.tools.ui.screen.OverviewScreen
import com.osplus.tools.ui.screen.PerfScreen
import com.osplus.tools.ui.screen.PowerScreen
import com.osplus.tools.ui.screen.ProcessDetailScreen
import com.osplus.tools.ui.screen.SettingsScreen
import com.osplus.tools.ui.theme.OSPlusTheme
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

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
        var route by rememberSaveable { mutableStateOf<String?>(null) }

        val detail = route?.let { name ->
            OverviewDetail.entries.firstOrNull { it.name == name }
        }
        val settingsOpen = route == RouteSettings

        // 顶栏动作需要知道权限与录制状态：清理类动作在无 Root 时置灰，
        // 记录按钮在录制中改红底以表达「再点一下是停止」。
        val rootAvailable by viewModel.rootAvailable.collectAsStateWithLifecycle()
        val fpsRecording by viewModel.fpsRecording.collectAsStateWithLifecycle()
        val c = osColors()

        // 预测性返回：优先退二级路由 → 其次回概览 → 已在概览则交还系统执行退出动画
        val backState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = backState,
            isBackEnabled = route != null || rootTab != RootTab.Overview,
            onBackCompleted = {
                if (route != null) route = null else rootTab = RootTab.Overview
            },
        )

        val screenKey = route ?: tabKey(rootTab)

        // 顶栏标题：二级页取枚举里的文案（与页面同一份定义，不会漂移），一级页取页签名
        val topTitle = when {
            settingsOpen -> "设置"
            detail != null -> detail.title
            rootTab == RootTab.Overview -> "概览"
            rootTab == RootTab.Perf -> "性能"
            rootTab == RootTab.Fps -> "帧率"
            else -> "电源"
        }

        // 内容层先渲染进 GraphicsLayer 并记录下来，悬浮控件据此做真实背景模糊（液态玻璃）。
        // 记录的是「背景 + 顶栏 + 页面内容」整层，不含底部悬浮条自身——否则它会把自己的高光也糊进去。
        val backdrop = rememberLayerBackdrop()

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                PageBackground()
                // 顶栏自带状态栏内边距，因此这里用 Column 而非 Box：
                // 顶栏占据固定高度，内容区吃掉剩余空间，页面内容永远不会钻到顶栏下面，
                // 也就不需要在每个页面里各写一遍顶部留白。
                Column(Modifier.fillMaxSize()) {
                    OsTopBar(
                        title = topTitle,
                        onBack = if (route != null) ({ route = null }) else null,
                        actions = {
                            if (rootTab == RootTab.Overview && route == null) {
                                OsTopBarAction(
                                    icon = Icons.Rounded.CleaningServices,
                                    contentDescription = "清理内存",
                                    enabled = rootAvailable,
                                    onClick = { viewModel.cleanMemCaches() },
                                )
                                OsTopBarAction(
                                    icon = Icons.Rounded.Refresh,
                                    contentDescription = "清理交换",
                                    enabled = rootAvailable,
                                    onClick = { viewModel.cleanSwap() },
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
                                )
                                OsTopBarAction(
                                    icon = Icons.Rounded.Settings,
                                    contentDescription = "设置",
                                    onClick = { route = RouteSettings },
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
                                    key == RouteSettings -> SettingsScreen(viewModel)

                                    keyDetail != null -> when (keyDetail) {
                                        OverviewDetail.Memory -> MemDetailScreen(viewModel)
                                        OverviewDetail.Gpu -> GpuDetailScreen(viewModel)
                                        OverviewDetail.Cpu -> CpuDetailScreen(viewModel)
                                        OverviewDetail.Process -> ProcessDetailScreen(viewModel)
                                    }

                                    keyTab == RootTab.Overview -> OverviewScreen(
                                        vm = viewModel,
                                        onOpen = { route = it.name },
                                        onOpenPower = { rootTab = RootTab.Power },
                                    )

                                    keyTab == RootTab.Perf -> PerfScreen(viewModel) { route = it.name }
                                    keyTab == RootTab.Fps -> FpsScreen(viewModel)
                                    else -> PowerScreen(viewModel)
                                }
                            }
                        }
                    }
                }
            }

            OsFloatingBottomBar(
                items = listOf(
                    BarItem("概览", Icons.Rounded.Dashboard),
                    BarItem("性能", Icons.Rounded.Speed),
                    BarItem("帧率", Icons.AutoMirrored.Rounded.ShowChart),
                    BarItem("电源", Icons.Rounded.BatteryFull),
                ),
                selectedIndex = rootTab.ordinal,
                onSelect = {
                    rootTab = RootTab.entries[it]
                    route = null
                },
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
