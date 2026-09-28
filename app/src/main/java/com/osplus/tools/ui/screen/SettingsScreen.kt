package com.osplus.tools.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.LiveNotif
import com.osplus.tools.core.NotifMetric
import com.osplus.tools.core.Shell
import com.osplus.tools.ui.components.CardSectionLabel
import com.osplus.tools.ui.components.ChoiceChip
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SwitchRow
import com.osplus.tools.ui.theme.AppThemeMode
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import com.osplus.tools.ui.components.LiquidNavTabs
import top.yukonga.miuix.kmp.basic.Text
import com.osplus.tools.ui.components.bottomBarContentPadding
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 跳转「使用情况访问」系统授权页 */
private fun openUsageAccess(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 跳转本应用的「悬浮窗权限」系统授权页 */
private fun openOverlayAccess(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun SettingsScreen(vm: DeviceViewModel) {
    val context = LocalContext.current
    val c = osColors()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val usageAccess by vm.usageAccess.collectAsStateWithLifecycle()
    val autoRefresh by vm.autoRefresh.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val monet by vm.monet.collectAsStateWithLifecycle()
    val liveNotifEnabled by vm.liveNotifEnabled.collectAsStateWithLifecycle()
    val notifMetrics by vm.notifMetrics.collectAsStateWithLifecycle()
    val notifKeepAlive by vm.notifKeepAlive.collectAsStateWithLifecycle()
    // 悬浮窗权限：在系统授权页授予后返回本页要能立刻反映，
    // 因此每次回到前台都重读一次，而不是只在首次组合时读一次
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleResumeEffect(Unit) {
        overlayGranted = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }
    val suPath = remember(rootAvailable) {
        listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/su",
        ).firstOrNull { java.io.File(it).exists() } ?: "未找到"
    }

    var kernelVersion by remember { mutableStateOf("-") }
    LaunchedEffect(Unit) {
        kernelVersion = Shell.run("uname -r", root = false).stdout.ifBlank { "-" }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 统一走 bottomBarContentPadding：左右 14dp 对齐顶栏，底部避让悬浮底栏 + 系统导航栏
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            if (rootAvailable) {
                NoticeBanner(
                    text = "已获取 Root 权限，全部控制功能可用。",
                    accent = com.osplus.tools.ui.components.ChartColors.gpu,
                )
            } else {
                NoticeBanner("未获取 Root 权限：频率控制、进程管理、充电控制将不可用；信息读取仍可正常使用。")
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    InfoRow(
                        label = "Root",
                        value = if (rootAvailable) "已授权" else "未授权",
                        emphasis = true,
                    )
                    InfoRow("su 路径", suPath)
                    InfoRow(
                        label = "使用情况访问",
                        value = if (usageAccess) "已授权" else "未授权",
                    )
                    InfoRow(
                        label = "悬浮窗",
                        value = if (overlayGranted) "已授权" else "未授权",
                    )
                    InfoRow("通知", value = "安装后首次启动申请")
                    Spacer(Modifier.height(8.dp))
                    // 与底栏同语言的动作条，与页面背景明显区分
                    LiquidNavTabs(
                        items = listOf("重新检测 Root", "使用情况访问", "悬浮窗权限"),
                        selectedIndex = -1,
                        onSelect = {
                            when (it) {
                                0 -> vm.refreshRootState()
                                1 -> openUsageAccess(context)
                                else -> openOverlayAccess(context)
                            }
                        },
                    )
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 5.dp)) {
                    CardSectionLabel("主题")
                    Spacer(Modifier.height(8.dp))
                    LiquidNavTabs(
                        items = AppThemeMode.entries.map { it.label },
                        selectedIndex = AppThemeMode.entries.indexOf(themeMode),
                        onSelect = { vm.setThemeMode(AppThemeMode.entries[it]) },
                    )
                    Spacer(Modifier.height(10.dp))
                    SwitchRow(
                        label = "壁纸取色（Monet）",
                        summary = "从壁纸提取主题色；关闭后使用中性配色，数据可读性更好",
                        checked = monet,
                        onCheckedChange = { vm.setMonet(it) },
                    )
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    SwitchRow(
                        label = "实时采样",
                        summary = "每秒采样一次，实时趋势以折线绘制，记录时长随采样持续增长",
                        checked = autoRefresh,
                        onCheckedChange = { vm.setAutoRefresh(it) },
                    )
                    InfoRow("采样间隔", "${DeviceViewModelInterval()} 毫秒")
                    InfoRow("记录上限", "30 分钟（1800 条）")
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("实时任务通知")
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        label = "实时任务通知",
                        summary = "开启：以系统实时任务通知呈现，状态栏芯片常驻、通知抽屉置顶，无需悬浮窗权限；" +
                            "关闭：改用悬浮窗呈现实时数据",
                        checked = liveNotifEnabled,
                        onCheckedChange = { on ->
                            vm.setLiveNotifEnabled(on)
                            // 关闭后要落到悬浮窗，权限缺失时 ViewModel 不会切换；
                            // 这里直接把用户送到授权页，避免「点了开关却毫无反应」
                            if (!on && !overlayGranted) openOverlayAccess(context)
                        },
                    )

                    if (liveNotifEnabled) {
                        Spacer(Modifier.height(12.dp))
                        CardSectionLabel("通知显示项")
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "最多 3 项：这是系统对指标样式通知的限制，超出部分不会显示。" +
                                "排列顺序固定按下方网格顺序，与勾选先后无关。",
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                        Spacer(Modifier.height(9.dp))
                        MetricChipGrid(
                            selected = notifMetrics,
                            onToggle = { vm.toggleNotifMetric(it) },
                        )
                        Spacer(Modifier.height(11.dp))
                        LiquidNavTabs(
                            items = listOf("恢复默认显示项"),
                            selectedIndex = -1,
                            onSelect = { vm.resetNotifMetrics() },
                        )
                        Spacer(Modifier.height(9.dp))
                        Text(
                            text = "显示方式：帧率 FPS ｜ 占用率 % ｜ 频率 GHz/MHz ｜ 功耗 mW ｜ " +
                                "温度 ℃ ｜ 充电速度 W。读不到的项显示 --（多数厂商节点无 Root 时不可读）。\n" +
                                "默认：帧率、CPU 占用、GPU 占用（三项瞬时变化且不依赖 Root）。",
                            style = OsText.micro,
                            color = c.textTertiary,
                        )

                        Spacer(Modifier.height(14.dp))
                        CardSectionLabel("后台保活")
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "ColorOS 会在应用退到后台约 5~10 秒后冻结整个进程，前台服务不足以豁免、" +
                                "Root 也无法阻止（冻结发生在内核层，被冻期间进程执行不了任何代码）。" +
                                "冻结后通知会停在上一次读数、不再更新。",
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(
                            label = "保留保活锚点",
                            summary = "挂一个 1×1 的不可见悬浮窗，让进程保持「可见」而不被判定为后台应用；" +
                                "需要悬浮窗权限，不显示任何内容",
                            checked = notifKeepAlive,
                            onCheckedChange = { vm.setNotifKeepAlive(it) },
                        )
                        if (notifKeepAlive && !overlayGranted) {
                            Spacer(Modifier.height(8.dp))
                            NoticeBanner(
                                text = "悬浮窗权限未授予：锚点无法建立，通知在后台仍会停止更新。",
                            )
                        }
                    } else {
                        Spacer(Modifier.height(10.dp))
                        if (!overlayGranted) {
                            NoticeBanner(
                                text = "悬浮窗权限未授予：当前仍以通知呈现。授权后悬浮窗才会出现。",
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            text = "悬浮窗模式：显示项与上方多选一致（最多 3 项）；" +
                                "轻点切换记录，长按或拖动移动位置，位置与不透明度会被记住。",
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                    }
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    InfoRow("应用", "OSPlus")
                    InfoRow("版本", "2.0.0")
                    InfoRow("包名", context.packageName)
                    InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                    InfoRow("系统", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    InfoRow("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
                    InfoRow("内核", kernelVersion)
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("说明")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "· 数据全部来自 /proc、/sys 与系统 API，不做任何云端上报。\n" +
                            "· 频率/充电控制直接写入内核节点，不同机型可用项存在差异。\n" +
                            "· 耗电统计基于使用时长加权估算，非硬件实测功耗。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }
    }
}

private fun DeviceViewModelInterval(): Long = com.osplus.tools.vm.DeviceViewModel.SAMPLE_INTERVAL_MS

/**
 * 实时任务通知的指标项多选网格。
 *
 * 用三列等宽胶囊而不是自适应流式布局：9 个选项固定排成 3×3，行宽稳定、扫视路径短；
 * 标签长度不一（「帧率」对「充电速度」）时等宽比按内容宽度更整齐，
 * 也不会因为选中态改变字重而让行宽跳动。
 *
 * 达到数量边界（已选满 3 项 / 仅剩 1 项）时把不能再动的项置灰，
 * 而不是让点击静默失败——静默失败会让人以为点击没被识别。
 */
@Composable
private fun MetricChipGrid(
    selected: List<NotifMetric>,
    onToggle: (NotifMetric) -> Unit,
) {
    val atMax = selected.size >= LiveNotif.MAX_METRICS
    val atMin = selected.size <= LiveNotif.MIN_METRICS
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NotifMetric.entries.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { metric ->
                    val isOn = metric in selected
                    ChoiceChip(
                        text = metric.label,
                        selected = isOn,
                        enabled = if (isOn) !atMin else !atMax,
                        onClick = { onToggle(metric) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 末行不足 3 项时补空位，否则最后一格会被拉伸成整行宽
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
