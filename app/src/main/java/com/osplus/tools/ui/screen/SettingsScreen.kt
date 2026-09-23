package com.osplus.tools.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.Shell
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SegmentedTabs
import com.osplus.tools.ui.components.SwitchRow
import com.osplus.tools.ui.theme.AppThemeMode
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(vm: DeviceViewModel) {
    val context = LocalContext.current
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val usageAccess by vm.usageAccess.collectAsStateWithLifecycle()
    val autoRefresh by vm.autoRefresh.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val monet by vm.monet.collectAsStateWithLifecycle()
    val overlayGranted = remember { Settings.canDrawOverlays(context) }
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 2.dp, bottom = 104.dp,
        ),
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
            SectionCard(title = "权限") {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.refreshRootState() }) { Text("重新检测 Root") }
                        Button(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("使用情况访问") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    .setData(Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("悬浮窗权限") }
                }
            }
        }

        item {
            SectionCard(title = "外观") {
                Column(Modifier.padding(vertical = 5.dp)) {
                    SegmentedTabs(
                        tabs = AppThemeMode.entries.map { it.label },
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
            SectionCard(title = "采样") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    SwitchRow(
                        label = "实时采样",
                        summary = "每秒采样一次，柱状图展示最近 5 秒数据",
                        checked = autoRefresh,
                        onCheckedChange = { vm.setAutoRefresh(it) },
                    )
                    InfoRow("采样间隔", "${DeviceViewModelInterval()} 毫秒")
                    InfoRow("窗口长度", "5 秒")
                }
            }
        }

        item {
            SectionCard(title = "关于") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    InfoRow("应用", "OSPlus")
                    InfoRow("版本", "1.1.0")
                    InfoRow("包名", context.packageName)
                    InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                    InfoRow("系统", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    InfoRow("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
                    InfoRow("内核", kernelVersion)
                }
            }
        }

        item {
            SectionCard(title = "说明") {
                Column(Modifier.padding(vertical = 3.dp)) {
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
