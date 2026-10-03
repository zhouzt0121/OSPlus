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
import com.osplus.tools.core.PrivilegeManager
import com.osplus.tools.core.PrivilegeMode
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
fun SettingsScreen(
    vm: DeviceViewModel,
    onOpenLiquidLab: () -> Unit = {},
) {
    val context = LocalContext.current
    val c = osColors()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    // 「提权方式」分段条用**用户点选**的模式驱动，而不是当前生效的通道：
    // 点一个当前不可用的模式时，实际通道会回退，但界面必须跟着用户走，
    // 否则表现为「点了没反应、胶囊不跟过去」
    val privilegeSelection by vm.privilegeSelection.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val probe by vm.privilegeProbe.collectAsStateWithLifecycle()
    val privilegeMessage by vm.privilegeMessage.collectAsStateWithLifecycle()
    // 电脑脚本激活：脚本落点与可复制的 adb 命令（展示在 ADB 分支内）
    val scriptInfo by vm.scriptInfo.collectAsStateWithLifecycle()
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
        // 从系统授权页返回时 Shizuku 授权状态可能已变化，重探一次
        vm.refreshRootState()
        onPauseOrDispose { }
    }

    var kernelVersion by remember { mutableStateOf("-") }
    LaunchedEffect(Unit) {
        kernelVersion = Shell.run("uname -r", root = false).stdout.ifBlank { "-" }
    }

    // 直接复用 Shell.findSu()，不要在这里再抄一份候选列表：
    // 原先此处独立维护了一份 6 项列表，漏了 /vendor/bin/su —— 若设备的 su
    // 恰好落在该路径，实际提权正常、界面却显示「未找到」，用户看到自相矛盾的信息。
    // 单一事实来源留给 Shell，UI 只负责展示。
    val suPath = remember(rootAvailable) { Shell.findSu() ?: "未找到" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 统一走 bottomBarContentPadding：左右 14dp 对齐顶栏，底部避让悬浮底栏 + 系统导航栏
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PrivilegeBanner(capabilities)
        }

        item {
            PrivilegeModeCard(
                mode = privilegeSelection,
                probe = probe,
                rootAvailable = rootAvailable,
                suPath = suPath,
                message = privilegeMessage,
                scriptInfo = scriptInfo,
                onDeployScript = { vm.deployActivationScript() },
                onSelect = { vm.selectPrivilegeMode(it) },
                onRequestShizuku = { vm.requestShizukuPermission() },
                onPairAdb = { port, code -> vm.pairAdb(port, code) },
                onConnectAdb = { port -> vm.connectAdb(port) },
                onDismissMessage = { vm.clearPrivilegeMessage() },
            )
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
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
                        items = listOf("使用情况访问", "悬浮窗权限"),
                        selectedIndex = -1,
                        onSelect = {
                            if (it == 0) openUsageAccess(context) else openOverlayAccess(context)
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

        // 功率校准（复刻 Metric 的 Battery 设置组）：
        // 内核电流节点口径因 SoC 而异，串联双电芯机型只报单节电压——
        // 两个开关修正所有 Power 展示（悬浮窗 PWR、耗电统计、充电速度）。
        // 判据来自 Metric 文档：Power 长期 0.00W、充放方向相反、功率明显偏大/偏小
        // 或双电芯机型功率只有预期一半时，先调这两项。
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 5.dp)) {
                    CardSectionLabel("功率校准")
                    Spacer(Modifier.height(8.dp))
                    var factorText by remember {
                        mutableStateOf(
                            run {
                                val v = com.osplus.tools.core.Preferences.powerCurrentFactor(context)
                                if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
                            },
                        )
                    }
                    AdbInputField(
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
                    CardSectionLabel("实验")
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenLiquidLab() }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Kyant 原版组件实验室",
                                style = MiuixTheme.textStyles.main,
                                color = MiuixTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "LiquidButton / LiquidBottomTabs / LiquidSlider / LiquidToggle 原版实现",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                        Text(
                            text = "›",
                            style = MiuixTheme.textStyles.title3,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
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
 * 顶部状态横幅。
 *
 * 文案跟着**能力**走而不是跟着模式走：同是 shell 身份（Shizuku / ADB），
 * 能力完全一样，没必要分两套说法；而 Root 与 shell 的差别是实质性的，
 * 必须让用户一眼看出「哪些功能用不了、为什么」。
 */
@Composable
private fun PrivilegeBanner(caps: PrivilegeCapabilities) {
    val mode = caps.mode
    when {
        mode == null -> NoticeBanner(
            text = "未获取任何提权通道：CPU 频率、进程管理、性能调度不可用；" +
                "基础信息读取仍可正常使用。可在下方选择 Root / Shizuku / ADB 任意一种。",
        )
        mode == PrivilegeMode.ROOT -> NoticeBanner(
            text = "已通过 Root 提权，全部控制功能可用。",
            accent = com.osplus.tools.ui.components.ChartColors.gpu,
        )
        else -> NoticeBanner(
            text = "已通过 ${mode.label} 提权（shell 身份）：CPU 频率控制、进程管理、GPU/内存读取可用；" +
                "「性能调度」读写 Magisk 模块目录（/data/adb）需要 Root，该页在本模式下不可用。",
            accent = com.osplus.tools.ui.components.ChartColors.cpu,
        )
    }
}

/**
 * 提权模式选择卡。
 *
 * 三种模式各自有不同的「就绪条件」，因此每一种都给出**具体的下一步动作**
 * （请求 Shizuku 授权 / 填配对码），而不是一个灰色的「不可用」——
 * 后者会让用户不知道该怎么办。
 */
@Composable
private fun PrivilegeModeCard(
    mode: PrivilegeMode?,
    probe: PrivilegeManager.ModeProbe,
    rootAvailable: Boolean,
    suPath: String,
    message: String?,
    /** 电脑脚本激活：脚本落点与可复制的 adb 命令；未部署时为 null */
    scriptInfo: DeviceViewModel.ScriptInfo?,
    onDeployScript: () -> Unit,
    onSelect: (PrivilegeMode) -> Unit,
    onRequestShizuku: () -> Unit,
    onPairAdb: (Int?, String) -> Unit,
    onConnectAdb: (Int) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val c = osColors()
    SectionCard {
        Column(Modifier.padding(vertical = 3.dp)) {
            CardSectionLabel("提权方式")
            Spacer(Modifier.height(5.dp))
            Text(
                text = "三种方式身份不同：Root 可读写全部节点；Shizuku 与 ADB 均以 shell 身份运行，"
                    + "足以读取频率、管理进程，但写不了调频节点、也读不到 /data/adb 下的模块文件。"
                    + "两种非 Root 方式能力完全一致，区别只在怎么激活。",
                style = OsText.micro,
                color = c.textTertiary,
            )
            Spacer(Modifier.height(10.dp))
            // 提权方式：与底部悬浮导航栏同一套原版组件（见 Common.kt 的 LiquidNavTabs）
            LiquidNavTabs(
                items = PrivilegeMode.entries.map { it.shortLabel },
                selectedIndex = mode?.let { PrivilegeMode.entries.indexOf(it) } ?: -1,
                onSelect = { onSelect(PrivilegeMode.entries[it]) },
            )
            Spacer(Modifier.height(10.dp))

            // 当前模式的就绪条件与状态
            when (mode) {
                PrivilegeMode.ROOT -> {
                    InfoRow("Root", value = if (rootAvailable) "已授权" else "未授权", emphasis = true)
                    InfoRow("su 路径", suPath)
                }
                PrivilegeMode.SHIZUKU -> {
                    InfoRow(
                        "Shizuku 服务",
                        value = if (probe.shizukuSupported) "运行中" else "未检测到",
                        emphasis = true,
                    )
                    InfoRow("授权", value = if (probe.shizukuReady) "已授权" else "未授权")
                    if (!probe.shizukuSupported) {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner("未检测到 Shizuku：请先安装并启动 Shizuku App，再回到本页。")
                    } else if (!probe.shizukuReady) {
                        Spacer(Modifier.height(8.dp))
                        LiquidNavTabs(
                            items = listOf("请求 Shizuku 授权"),
                            selectedIndex = -1,
                            onSelect = { onRequestShizuku() },
                        )
                    }
                }
                PrivilegeMode.ADB -> {
                    // 状态置顶：先让用户一眼看出「现在到底有没有通道」，
                    // 再看下面两种激活方式——原来的写法把状态和操作混在一堆
                    // InfoRow 里，用户读完仍不知道自己在哪一步。
                    val channel = when {
                        probe.adbReady -> "无线调试"
                        probe.daemonReady -> "电脑脚本（守护进程）"
                        else -> null
                    }
                    NoticeBanner(
                        text = if (channel != null) {
                            "已连接 · $channel\n以 shell 身份（uid 2000）执行命令"
                        } else {
                            "未连接。下方两种方式任选一种激活；激活后即可读取 CPU 占用/频率、" +
                                "内存、网络等需要 shell 身份的数据。"
                        },
                        accent = if (channel != null) {
                            com.osplus.tools.ui.components.ChartColors.gpu
                        } else {
                            com.osplus.tools.ui.components.ChartColors.cpu
                        },
                    )
                    Spacer(Modifier.height(12.dp))

                    CardSectionLabel("方式一 · 无线调试")
                    Spacer(Modifier.height(6.dp))
                    InfoRow("配对", if (probe.adbPaired) "已配对" else "未配对")
                    InfoRow("连接", if (probe.adbReady) "已连接" else "未连接")
                    Spacer(Modifier.height(8.dp))
                    AdbPairSection(
                        paired = probe.adbPaired,
                        ready = probe.adbReady,
                        onPair = onPairAdb,
                        onConnect = onConnectAdb,
                    )

                    Spacer(Modifier.height(14.dp))
                    CardSectionLabel("方式二 · 电脑脚本")
                    Spacer(Modifier.height(6.dp))
                    InfoRow(
                        "守护进程",
                        when {
                            probe.daemonReady -> "运行中"
                            probe.daemonProvisioned -> "已停止"
                            else -> "未激活"
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    scriptInfo?.let { info ->
                        // 等宽字体：命令行用比例字体容易把 l/1、O/0 看混
                        Text(
                            text = info.command,
                            style = OsText.caption,
                            color = c.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.cardAlt, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    LiquidNavTabs(
                        items = listOf(if (scriptInfo == null) "生成激活命令" else "重新生成"),
                        selectedIndex = -1,
                        onSelect = { onDeployScript() },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append("用数据线连电脑执行上面这条命令即可，不需要开无线调试。")
                            if (probe.daemonProvisioned && !probe.daemonReady) {
                                append("\n守护进程已停止（设备重启后一定会停），重新执行即可恢复。")
                            }
                        },
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
                null -> {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "尚未建立提权通道。点上方任一方式切换；" +
                            "若三种都不可用，请先按各方式的提示完成授权或配对。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }

            if (message != null) {
                Spacer(Modifier.height(10.dp))
                NoticeBanner(text = message)
                Spacer(Modifier.height(6.dp))
                LiquidNavTabs(
                    items = listOf("知道了"),
                    selectedIndex = -1,
                    onSelect = { onDismissMessage() },
                )
            }
        }
    }
}

/**
 * ADB 配对区。
 *
 * 只让用户填 6 位配对码与端口：地址固定走回环（adbd 就在本机），
 * 配对端口留空时由 mDNS 现查——那个端口只在「使用配对码配对设备」
 * 页面打开期间存在，所以必须现查而不是缓存。
 */
@Composable
private fun AdbPairSection(
    paired: Boolean,
    ready: Boolean,
    onPair: (Int?, String) -> Unit,
    onConnect: (Int) -> Unit,
) {
    val c = osColors()
    var code by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }

    InfoRow("配对状态", value = if (paired) "已配对" else "未配对", emphasis = true)
    InfoRow("通道", value = if (ready) "已连接" else "未连接（需开启无线调试）")
    Spacer(Modifier.height(8.dp))
    Text(
        text = "步骤：① 开发者选项 → 无线调试，打开开关；② 进入「使用配对码配对设备」页面，" +
            "记下 6 位配对码；③ 在下方填入配对码并点「开始配对」。端口可以留空，会自动查找。",
        style = OsText.micro,
        color = c.textTertiary,
    )
    Spacer(Modifier.height(9.dp))

    AdbInputField(
        label = "6 位配对码",
        value = code,
        onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
        numeric = true,
    )
    Spacer(Modifier.height(7.dp))
    AdbInputField(
        label = "配对端口（可留空）",
        value = portText,
        onValueChange = { v -> portText = v.filter { it.isDigit() }.take(5) },
        numeric = true,
    )
    Spacer(Modifier.height(9.dp))
    LiquidNavTabs(
        items = listOf("开始配对", "连接通道"),
        selectedIndex = -1,
        onSelect = {
            if (it == 0) {
                onPair(portText.toIntOrNull(), code)
            } else {
                // 连接端口与配对端口不同，这里让用户在已知时手填；
                // 留空传 0 由后端走 mDNS 发现
                onConnect(portText.toIntOrNull() ?: 0)
            }
        },
    )
    if (paired && !ready) {
        Spacer(Modifier.height(8.dp))
        NoticeBanner("已配对但通道未连通：请确认「无线调试」开关仍然打开（关掉后端口会消失）。")
    }
}

/** 简易数字输入框：项目里没有通用 TextField，这里用最小实现 */
@Composable
private fun AdbInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean,
) {
    val c = osColors()
    Column {
        Text(text = label, style = OsText.micro, color = c.textTertiary)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            } else {
                KeyboardOptions.Default
            },
            textStyle = MiuixTheme.textStyles.body1.copy(color = c.textPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .background(c.cardAlt, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
