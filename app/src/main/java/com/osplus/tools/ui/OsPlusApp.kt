package com.osplus.tools.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.foundation.layout.size
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.NavigationEventInfo
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.osplus.tools.ui.components.BarItem
import com.osplus.tools.ui.components.GlassFloatingBottomBar
import com.osplus.tools.ui.components.GlassTopBar
import com.osplus.tools.ui.components.PageBackground
import com.osplus.tools.ui.screen.DashboardScreen
import com.osplus.tools.ui.screen.PerfScreen
import com.osplus.tools.ui.screen.PowerScreen
import com.osplus.tools.ui.screen.ProcessScreen
import com.osplus.tools.ui.screen.SettingsScreen
import com.osplus.tools.ui.theme.OSPlusTheme
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val TOP_BAR_CONTENT_HEIGHT = 62.dp

private enum class OsTab(val title: String, val subtitle: String) {
    Dashboard("概览", "实时性能监视"),
    Perf("性能", "CPU / GPU / 内存"),
    Power("电源", "耗电 · 充电"),
    Process("进程", "进程与帧率"),
    Settings("设置", "权限与关于"),
}

@Composable
fun OsPlusApp(viewModel: DeviceViewModel = viewModel()) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val monet by viewModel.monet.collectAsStateWithLifecycle()
    val autoRefresh by viewModel.autoRefresh.collectAsStateWithLifecycle()

    OSPlusTheme(mode = themeMode, monet = monet) {
        var selected by rememberSaveable { mutableIntStateOf(0) }
        val tabs = remember { OsTab.entries.toList() }
        val current = tabs[selected]

        val history by viewModel.history.collectAsStateWithLifecycle()
        val cpu by viewModel.cpu.collectAsStateWithLifecycle()
        val subtitle = remember(current, history, cpu) {
            when (current) {
                OsTab.Dashboard ->
                    if (history.isEmpty()) "正在采样…"
                    else "CPU ${"%.0f".format(history.last().cpuLoad)}% · 内存 ${
                        "%.0f".format(history.last().memUsedPercent)
                    }% · ${"%.0f".format(history.last().powerMw)} mW"
                OsTab.Perf -> cpu.soc.ifBlank { "读取中…" }
                OsTab.Power -> "耗电统计 · 充电控制"
                OsTab.Process -> "进程管理与帧率记录"
                OsTab.Settings -> "OSPlus 1.0.0"
            }
        }

        // 内容层先渲染并记录到 GraphicsLayer，供顶栏 / 底栏做真实背景模糊
        val backdrop = rememberLayerBackdrop()

        // 预测性返回：非「概览」页时接管返回手势，带系统预测动画回到概览；
        // 已在概览页则不拦截，交由系统执行退出动画。
        val backState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = backState,
            isBackEnabled = selected != 0,
            onBackCompleted = { selected = 0 },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            ) {
                PageBackground()
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    // 顶栏为覆盖层，这里预留其高度，避免内容被遮挡
                    Spacer(Modifier.height(TOP_BAR_CONTENT_HEIGHT))

                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                        },
                        label = "tabContent",
                    ) { index ->
                        Box(Modifier.fillMaxSize()) {
                            when (tabs[index]) {
                                OsTab.Dashboard -> DashboardScreen(viewModel)
                                OsTab.Perf -> PerfScreen(viewModel)
                                OsTab.Power -> PowerScreen(viewModel)
                                OsTab.Process -> ProcessScreen(viewModel)
                                OsTab.Settings -> SettingsScreen(viewModel)
                            }
                        }
                    }
                }
            }

            GlassTopBar(
                title = current.title,
                subtitle = subtitle,
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.TopCenter),
                trailing = {
                    IconButton(
                        onClick = { viewModel.setAutoRefresh(!autoRefresh) },
                        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Icon(
                            imageVector = if (autoRefresh) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (autoRefresh) "暂停采样" else "继续采样",
                            tint = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )

            GlassFloatingBottomBar(
                items = listOf(
                    BarItem("概览", Icons.Rounded.Dashboard),
                    BarItem("性能", Icons.Rounded.Speed),
                    BarItem("电源", Icons.Rounded.BatteryChargingFull),
                    BarItem("进程", Icons.Rounded.Memory),
                    BarItem("设置", Icons.Rounded.Settings),
                ),
                selectedIndex = selected,
                onSelect = { selected = it },
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

