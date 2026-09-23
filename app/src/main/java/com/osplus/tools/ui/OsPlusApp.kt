package com.osplus.tools.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.osplus.tools.ui.components.OsActionButton
import com.osplus.tools.ui.components.OsFloatingBottomBar
import com.osplus.tools.ui.components.OsTopBar
import com.osplus.tools.ui.components.PageBackground
import com.osplus.tools.ui.components.spanText
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
import com.osplus.tools.ui.screen.fmtGb
import com.osplus.tools.ui.theme.OSPlusTheme
import com.osplus.tools.vm.DeviceViewModel

/** 底部悬浮导航的一级页面 */
private enum class RootTab(val title: String) {
    Overview("概览"),
    Realtime("实时"),
    Fps("帧率"),
    Settings("设置"),
}

/**
 * 应用根布局。
 *
 * 结构：唯一主页「概览」+ 底部悬浮导航（概览 / 实时 / 帧率 / 设置）
 * + 从概览卡片下钻的二级详情页（内存 / GPU / CPU / 进程 / 电源）。
 * 二级页顶部带返回箭头；返回手势优先退二级页，其次回概览，最后交还系统退出。
 */
@Composable
fun OsPlusApp(viewModel: DeviceViewModel = viewModel()) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val monet by viewModel.monet.collectAsStateWithLifecycle()
    val autoRefresh by viewModel.autoRefresh.collectAsStateWithLifecycle()

    OSPlusTheme(mode = themeMode, monet = monet) {
        var rootTab by rememberSaveable { mutableIntStateOf(0) }
        var detailName by rememberSaveable { mutableStateOf<String?>(null) }
        val detail = detailName?.let { name ->
            OverviewDetail.entries.firstOrNull { it.name == name }
        }

        val history by viewModel.history.collectAsStateWithLifecycle()
        val cpu by viewModel.cpu.collectAsStateWithLifecycle()
        val gpu by viewModel.gpu.collectAsStateWithLifecycle()
        val mem by viewModel.mem.collectAsStateWithLifecycle()
        val processes by viewModel.processes.collectAsStateWithLifecycle()

        val current = RootTab.entries[rootTab]
        val title = detail?.let {
            when (it) {
                OverviewDetail.Memory -> "内存详情"
                OverviewDetail.Gpu -> "GPU 详情"
                OverviewDetail.Cpu -> "CPU 详情"
                OverviewDetail.Process -> "进程详情"
                OverviewDetail.Power -> "电源详情"
            }
        } ?: current.title

        val subtitle = remember(detail, current, history, cpu, gpu, mem, processes) {
            when (detail) {
                OverviewDetail.Memory -> "已用 ${fmtGb(mem.usedKb)} / 共 ${fmtGb(mem.totalKb)}"
                OverviewDetail.Gpu -> gpu.name.ifBlank { "读取中…" }
                OverviewDetail.Cpu -> cpu.soc.ifBlank { "读取中…" }
                OverviewDetail.Process -> "共 ${processes.size} 个进程"
                OverviewDetail.Power -> "耗电统计 · 充电控制"
                null -> when (current) {
                    RootTab.Overview ->
                        if (history.isEmpty()) "正在采样…"
                        else "CPU ${"%.0f".format(history.last().cpuLoad)}% · 内存 ${
                            "%.0f".format(history.last().memUsedPercent)
                        }% · ${"%.0f".format(history.last().powerMw)} mW"
                    RootTab.Realtime ->
                        if (history.isEmpty()) "正在采样…"
                        else "已累积 ${spanText(history.size)} · 每秒 1 次采样"
                    RootTab.Fps -> "帧率记录与跨应用悬浮窗"
                    RootTab.Settings -> "OSPlus 1.1.0"
                }
            }
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

        Box(modifier = Modifier.fillMaxSize()) {
            PageBackground()
            Column(modifier = Modifier.fillMaxSize()) {
                OsTopBar(
                    title = title,
                    subtitle = subtitle,
                    onBack = if (detail != null) ({ detailName = null }) else null,
                ) {
                    OsActionButton(
                        icon = if (autoRefresh) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (autoRefresh) "暂停采样" else "继续采样",
                        onClick = { viewModel.setAutoRefresh(!autoRefresh) },
                    )
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
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

            OsFloatingBottomBar(
                items = listOf(
                    BarItem("概览", Icons.Rounded.Dashboard),
                    BarItem("实时", Icons.Rounded.ShowChart),
                    BarItem("帧率", Icons.Rounded.Speed),
                    BarItem("设置", Icons.Rounded.Settings),
                ),
                selectedIndex = rootTab,
                onSelect = {
                    rootTab = it
                    detailName = null
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
