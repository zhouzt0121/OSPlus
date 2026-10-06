package com.osplus.tools.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.LiveNotif
import com.osplus.tools.core.NotifMetric
import com.osplus.tools.core.PrivilegeCapabilities
import com.osplus.tools.core.Shell
import com.osplus.tools.ui.components.ActionButton
import com.osplus.tools.ui.components.CardSectionLabel
import com.osplus.tools.ui.components.ChoiceChip
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.NumberInputField
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
fun SettingsScreen(
    vm: DeviceViewModel,
    onOpenLiquidLab: () -> Unit = {},
    onOpenSystemToggles: () -> Unit = {},
    onOpenPrivilege: () -> Unit = {},
    onOpenPredictiveBack: () -> Unit = {},
    onOpenOverlayManager: () -> Unit = {},
) {
    val context = LocalContext.current
    val c = osColors()
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
        // 从系统授权页返回时 root 授权状态可能已变化，重探一次。
        // 本页虽不再展示提权详情，但重探结果由 ViewModel 持有，
        // 「提权管理」页与概览页的横幅都读它，所以这里仍要刷新。
        vm.refreshRootState()
        onPauseOrDispose { }
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
        // ====================================================================
        // 信息架构 2.9.0：按提案的 9 类归并重排。
        //
        // 顺序即优先级：先「基础设置」（每天都要动的采样与主题），再「通知与后台」，
        // 然后「权限与 Root」——它被从原来的中段提到第三位，因为 2.6.1 起
        // Shizuku/ADB 通道整体移除后，"有没有 root" 直接决定后面一半页面是可用还是置灰。
        // 末尾放「实验室 / 关于 / 说明」：低频、且不需要和常用项混在一起。
        //
        // 每一类都是一张独立的 SectionCard + CardSectionLabel，扫视时按标题定位，
        // 不再出现原来「一张卡里塞十项、另一张卡没有标题」的参差。
        // ====================================================================

        // ---------------- 01 基础设置 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("基础设置")
                    Spacer(Modifier.height(3.dp))
                    SwitchRow(
                        label = "实时采样",
                        summary = "每秒采样一次，实时趋势以折线绘制，记录时长随采样持续增长",
                        checked = autoRefresh,
                        onCheckedChange = { vm.setAutoRefresh(it) },
                    )
                    InfoRow("采样间隔", "${DeviceViewModelInterval()} 毫秒")
                    InfoRow("记录上限", "30 分钟（1800 条）")

                    Spacer(Modifier.height(14.dp))
                    // 功率校准（复刻 Metric 的 Battery 设置组）：
                    // 内核电流节点口径因 SoC 而异，串联双电芯机型只报单节电压——
                    // 两个开关修正所有 Power 展示（悬浮窗 PWR、耗电统计、充电速度）。
                    // 判据来自 Metric 文档：Power 长期 0.00W、充放方向相反、功率明显偏大/偏小
                    // 或双电芯机型功率只有预期一半时，先调这两项。
                    //
                    // 归入「基础设置」而不是「调优」：它改的是**读数的换算口径**，
                    // 不是设备行为。放进调优页会和「写内核节点」的操作混为一谈，
                    // 让人以为改这里会影响功耗本身。
                    CardSectionLabel("功率校准")
                    Spacer(Modifier.height(3.dp))
                    var factorText by remember {
                        mutableStateOf(
                            run {
                                val v = com.osplus.tools.core.Preferences.powerCurrentFactor(context)
                                if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
                            },
                        )
                    }
                    NumberInputField(
                        label = "电流倍率（默认 1）",
                        value = factorText,
                        onValueChange = { t ->
                            factorText = t
                            t.toFloatOrNull()?.let { vm.setPowerCurrentFactor(it) }
                        },
                        numeric = true,
                    )
                    Text(
                        text = "功率整体偏大/偏小的机型按偏差倍数填写（如实测功率只有显示的一半填 0.5）",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        label = "串联双电芯",
                        summary = "双电芯机型总电压是读数两倍，功率需 ×2（表现为功率只有预期一半时开启）",
                        checked = com.osplus.tools.core.Preferences.powerSerialDualCell(context),
                        onCheckedChange = { vm.setPowerSerialDualCell(it) },
                    )
                }
            }
        }

        // ---------------- 02 权限与 Root ----------------
        //
        // 提权相关的一切都在「提权管理」页，本页不出现第二份。
        //
        // 演进过程值得记一笔：这里原先内联了完整的提权模式卡
        // （模式选择 + Shizuku 授权 + ADB 配对表单 + 电脑脚本生成），
        // 后来新增独立提权页时又渲染了同一张卡 —— 同一套流程出现在两处，
        // 用户不知道以哪边为准，改需求时也必然只改一边。
        // 现在只保留授权状态速览 + 一行入口。
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("权限与 Root")
                    Spacer(Modifier.height(3.dp))
                    InfoRow(
                        label = "使用情况访问",
                        value = if (usageAccess) "已授权" else "未授权",
                    )
                    InfoRow(
                        label = "悬浮窗",
                        value = if (overlayGranted) "已授权" else "未授权",
                    )
                    InfoRow("通知", value = "安装后首次启动申请")
                    SettingEntryRow(
                        title = "提权管理",
                        summary = "Root 授权状态、能力速查与探测明细",
                        onClick = onOpenPrivilege,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(
                            text = "使用情况访问",
                            onClick = { openUsageAccess(context) },
                            modifier = Modifier.weight(1f),
                        )
                        ActionButton(
                            text = "悬浮窗权限",
                            onClick = { openOverlayAccess(context) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // ---------------- 03 通知与后台 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("通知与后台")
                    Spacer(Modifier.height(3.dp))
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
                        ActionButton(
                            text = "恢复默认显示项",
                            onClick = { vm.resetNotifMetrics() },
                            modifier = Modifier.fillMaxWidth(),
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

        // ---------------- 04 主题与外观 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 5.dp)) {
                    CardSectionLabel("主题与外观")
                    Spacer(Modifier.height(3.dp))
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

        // ---------------- 05 系统开关 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("系统开关")
                    Spacer(Modifier.height(3.dp))
                    SettingEntryRow(
                        title = "系统开关",
                        summary = "动画缩放、状态栏图标、开发者选项等",
                        onClick = onOpenSystemToggles,
                    )
                }
            }
        }

        // ---------------- 06 预测性返回 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("预测性返回")
                    Spacer(Modifier.height(3.dp))
                    SettingEntryRow(
                        title = "预测性返回",
                        summary = "跟手位移、缩放、圆角、回弹耗时等动画参数",
                        onClick = onOpenPredictiveBack,
                    )
                }
            }
        }

        // ---------------- 07 悬浮窗管理 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("悬浮窗管理")
                    Spacer(Modifier.height(3.dp))
                    SettingEntryRow(
                        title = "悬浮窗管理器",
                        summary = "负载 / 进程 / 线程 / 迷你 / 帧率记录 / 温度，6 个独立监视器悬浮窗",
                        onClick = onOpenOverlayManager,
                    )
                }
            }
        }

        // ---------------- 08 实验室 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("实验室")
                    Spacer(Modifier.height(3.dp))
                    SettingEntryRow(
                        title = "Kyant 原版组件实验室",
                        summary = "LiquidButton / LiquidBottomTabs / LiquidSlider / LiquidToggle 原版实现",
                        onClick = onOpenLiquidLab,
                    )
                }
            }
        }

        // ---------------- 09 关于 ----------------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("关于")
                    Spacer(Modifier.height(3.dp))
                    InfoRow("应用", "OSPlus")
                    // 版本号读 BuildConfig，不写死：写死的版本号在每次发版后
                    // 都会和「设置 → 关于」以及安装包对不上，用户报障时给的是错信息
                    InfoRow("版本", com.osplus.tools.BuildConfig.VERSION_NAME)
                    InfoRow("包名", context.packageName)
                    InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                    InfoRow("系统", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    InfoRow("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
                    InfoRow("内核", kernelVersion)
                }
            }
        }

        // 提案把它单独列为第 9 类「说明」，与「关于」并列。
        // 这里仍拆成两张卡：关于是「事实」（可复制去报障），说明是「口径」（解释数据从哪来），
        // 混在一起会让报障时找版本号要多扫三行免责文字。
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("说明")
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "· 数据全部来自 /proc、/sys 与系统 API，不做任何云端上报。\n" +
                            "· 频率控制直接写入内核节点，不同机型可用项存在差异。\n" +
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
 * 设置页的可下钻行。
 *
 * 原先「实验」卡里内联写了这一整块 Row，新增三个入口后内联会出现四份
 * 完全相同的 20 行代码。提取成组件，顺便修掉了内联版本的一个问题：
 * 它硬编码了 `MiuixTheme.textStyles.main` 与 `onBackground`，
 * 而设置页其他文字用的是 `OsText` 语义字号——同一张卡里两种字号体系并存，
 * 在深色主题下还会出现色值不一致。这里统一到 [OsText]。
 */
@Composable
private fun SettingEntryRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val c = osColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = OsText.label,
                color = c.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                style = OsText.micro,
                color = c.textTertiary,
            )
        }
        Text(
            text = "›",
            style = OsText.value,
            color = c.textTertiary,
        )
    }
}


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
                    // 已选到下限不能再取消、未选到上限不能再加
                    val canToggle = if (isOn) !atMin else !atMax
                    ChoiceChip(
                        text = metric.label,
                        selected = isOn,
                        enabled = canToggle,
                        // 原版 isInteractive=false 不拦截点击，守卫写在 onClick 里
                        onClick = { if (canToggle) onToggle(metric) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 末行不足 3 项时补空位，否则最后一格会被拉伸成整行宽
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
