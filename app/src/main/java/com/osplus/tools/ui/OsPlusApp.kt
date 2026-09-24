package com.osplus.tools.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.osplus.tools.ui.components.OsFloatingBackButton
import com.osplus.tools.ui.components.OsFloatingBottomBar
import com.osplus.tools.ui.components.PageBackground
import com.osplus.tools.ui.screen.CpuDetailScreen
import com.osplus.tools.ui.screen.FpsScreen
import com.osplus.tools.ui.screen.GpuDetailScreen
import com.osplus.tools.ui.screen.MemDetailScreen
import com.osplus.tools.ui.screen.OverviewDetail
import com.osplus.tools.ui.screen.OverviewScreen
import com.osplus.tools.ui.screen.PowerDetailScreen
import com.osplus.tools.ui.screen.ProcessDetailScreen
import com.osplus.tools.ui.screen.RealtimeScreen
import com.osplus.tools.ui.screen.SettingsScreen
import com.osplus.tools.ui.theme.OSPlusTheme
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/** 底部悬浮导航的一级页面 */
private enum class RootTab {
    Overview,
    Realtime,
    Fps,
    Settings,
}

/**
 * 应用根布局。
 *
 * 结构：唯一主页「概览」+ 底部悬浮导航（概览 / 实时 / 帧率 / 设置）
 * + 从概览 / 实时卡片下钻的二级详情页（内存 / GPU / CPU / 进程 / 电源）。
 *
 * 顶部标题栏已移除：页面身份由底部导航的高亮图标承担，实时摘要交给各页卡片自身，
 * 二级页则在左上角保留一个悬浮返回按钮。
 * 返回手势优先退二级页，其次回概览，最后交还系统执行退出动画。
 */
@Composable
fun OsPlusApp(viewModel: DeviceViewModel = viewModel()) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val monet by viewModel.monet.collectAsStateWithLifecycle()

    OSPlusTheme(mode = themeMode, monet = monet) {
        var rootTab by rememberSaveable { mutableIntStateOf(0) }
        var detailName by rememberSaveable { mutableStateOf<String?>(null) }
        val detail = detailName?.let { name ->
            OverviewDetail.entries.firstOrNull { it.name == name }
        }

        // 预测性返回：优先退二级详情页 → 其次回概览 → 已在概览则交还系统执行退出动画
        val backState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = backState,
            isBackEnabled = detail != null || rootTab != 0,
            onBackCompleted = {
                if (detail != null) detailName = null else rootTab = 0
            },
        )

        val screenKey = detail?.name ?: "root-$rootTab"

        // 内容层先渲染进 GraphicsLayer 并记录下来，底部导航据此做真实背景模糊（液态玻璃）。
        // 记录的是「背景 + 页面内容」整层，不含导航条自身——否则导航条会把自己的高光也糊进去。
        val backdrop = rememberLayerBackdrop()

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                PageBackground()
                // 无顶栏后内容直接顶到状态栏下方；背景仍铺满整屏（含状态栏区域）
                Box(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    AnimatedContent(
                        targetState = screenKey,
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(140))
                        },
                        label = "screen",
                    ) { key ->
                        // 内容完全由动画的 key 决定，避免过渡期间新旧页面串帧
                        val keyDetail = OverviewDetail.entries.firstOrNull { it.name == key }
                        Box(Modifier.fillMaxSize()) {
                            when {
                                keyDetail != null -> when (keyDetail) {
                                    OverviewDetail.Memory -> MemDetailScreen(viewModel)
                                    OverviewDetail.Gpu -> GpuDetailScreen(viewModel)
                                    OverviewDetail.Cpu -> CpuDetailScreen(viewModel)
                                    OverviewDetail.Process -> ProcessDetailScreen(viewModel)
                                    OverviewDetail.Power -> PowerDetailScreen(viewModel)
                                }

                                key == "root-0" -> OverviewScreen(viewModel) { detailName = it.name }
                                key == "root-1" -> RealtimeScreen(viewModel) { detailName = it.name }
                                key == "root-2" -> FpsScreen(viewModel)
                                else -> SettingsScreen(viewModel)
                            }
                        }
                    }
                }
            }

            // 二级详情页的唯一可见返回入口（顶栏已移除）
            if (detail != null) {
                OsFloatingBackButton(
                    onClick = { detailName = null },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }

            OsFloatingBottomBar(
                items = listOf(
                    BarItem("概览", Icons.Rounded.Dashboard),
                    BarItem("实时", Icons.Rounded.Speed),
                    BarItem("帧率", Icons.AutoMirrored.Rounded.ShowChart),
                    BarItem("设置", Icons.Rounded.Settings),
                ),
                selectedIndex = rootTab,
                onSelect = {
                    rootTab = it
                    detailName = null
                },
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
