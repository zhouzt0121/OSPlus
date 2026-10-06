package com.osplus.tools.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import com.osplus.tools.core.MonitorKind
import com.osplus.tools.core.MonitorState
import com.osplus.tools.core.Preferences
import com.osplus.tools.service.MonitorOverlayService
import com.osplus.tools.ui.components.CardSectionLabel
import com.osplus.tools.ui.components.LiquidNavTabs
import com.osplus.tools.ui.components.LiquidSlider
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SwitchRow
import com.osplus.tools.ui.components.bottomBarContentPadding
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Text

/** 跳转本应用的「悬浮窗权限」系统授权页 */
private fun openOverlaySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * 悬浮窗管理器：6 个**互相独立**的监视器悬浮窗的统一开关面板。
 *
 * ## 为什么是「各自独立窗口」
 *
 * 每个监视器单独开关、单独拖动、单独摆位。合并成一个窗口时，
 * 「只想要帧率」的用户也得拖着包含另外 5 张卡片的大面板走，
 * 这与「迷你监视器几乎不遮挡内容」的诉求直接冲突。
 *
 * ## 状态真值只有一份
 *
 * 开关存在 [MonitorState] 单例里（背后是 [Preferences]），
 * 服务订阅它。本页**不做本地状态副本**——否则会出现「页面上显示开着、
 * 窗口却没出来」的撕裂。开关的 onCheckedChange 只调用
 * [MonitorState.setEnabled]，界面回显完全由 collectAsState 驱动。
 *
 * ## 服务生命周期跟着开关走
 *
 * - 从「一个都没有」变成「有」时启动服务；
 * - 从「有」变成「一个都没有」时停止服务（常驻前台通知不该在
 *   用户没开任何监视器时挂着）。
 *
 * 这个判断必须在**每次开关变化后**做，而不是只在页面首次组合时做。
 */
@Composable
fun OverlayManagerScreen() {
    val context = LocalContext.current
    val c = osColors()

    val enabled by MonitorState.enabled.collectAsStateWithLifecycle()
    val running by MonitorState.running.collectAsStateWithLifecycle()
    val alpha by MonitorState.alpha.collectAsStateWithLifecycle()

    // 悬浮窗权限：在系统授权页授予后返回本页要能立刻反映，
    // 因此挂在生命周期 ON_RESUME 上重读，而不是只在首次组合时读一次
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 不透明度用本地 float 状态做即时回显，落盘走 MonitorState。
    // 直接绑 alpha 会因 StateFlow 的发射节流在滑块上表现为「拖不动」。
    var alphaLocal by remember(alpha) { mutableFloatStateOf(alpha) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── 权限与服务状态 ─────────────────────────────────────────
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    if (!overlayGranted) {
                        NoticeBanner(
                            text = "未授予「悬浮窗权限」，监视器窗口无法显示。" +
                                "点下方按钮前往系统授权页开启。",
                        )
                        Spacer(Modifier.height(10.dp))
                        LiquidNavTabs(
                            items = listOf("前往授权"),
                            selectedIndex = -1,
                            onSelect = { openOverlaySettings(context) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    val status = when {
                        !overlayGranted -> "未授权"
                        enabled.isEmpty() -> "未开启"
                        running -> "运行中 · ${enabled.size} 个监视器"
                        else -> "正在启动…"
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("服务状态", style = OsText.label, color = c.textPrimary)
                        Spacer(Modifier.weight(1f))
                        Text(status, style = OsText.value, color = c.textSecondary)
                    }
                    Spacer(Modifier.height(10.dp))
                    LiquidNavTabs(
                        items = listOf("全部开启", "全部关闭"),
                        selectedIndex = -1,
                        // 全开时仍可点：重复点是无害的幂等操作，
                        // 置灰反而让人以为按钮坏了
                        onSelect = { idx ->
                            if (idx == 0) {
                                MonitorState.setAll(context, MonitorKind.entries.toSet())
                                if (Settings.canDrawOverlays(context)) MonitorOverlayService.start(context)
                            } else {
                                MonitorOverlayService.stopAll(context)
                            }
                        },
                    )
                }
            }
        }

        // ── 6 个监视器开关 ─────────────────────────────────────────
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("监视器")
                    Spacer(Modifier.height(4.dp))
                    MonitorKind.entries.forEach { kind ->
                        SwitchRow(
                            label = kind.title,
                            summary = kind.summary,
                            checked = kind in enabled,
                            enabled = overlayGranted,
                            onCheckedChange = { on ->
                                MonitorState.setEnabled(context, kind, on)
                                syncService(context, on)
                            },
                        )
                    }
                }
            }
        }

        // ── 外观 ────────────────────────────────────────────────
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 5.dp)) {
                    CardSectionLabel("外观")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "不透明度 %.0f%%".format(alphaLocal * 100f),
                        style = OsText.micro,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    LiquidSlider(
                        value = alphaLocal,
                        valueRange = MonitorState.MIN_ALPHA..1f,
                        onValueChange = {
                            alphaLocal = it
                            MonitorState.setAlpha(context, it)
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "6 个窗口共用一个不透明度。低于 30% 时文字在亮色背景上会难以辨认；" +
                            "拖动窗口期间会自动提到 100%，松手恢复。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }

        // ── 位置 ────────────────────────────────────────────────
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 5.dp)) {
                    CardSectionLabel("位置")
                    Spacer(Modifier.height(8.dp))
                    LiquidNavTabs(
                        items = listOf("重置全部位置"),
                        selectedIndex = -1,
                        onSelect = {
                            MonitorKind.entries.forEach { kind ->
                                Preferences.clearMonitorPosition(context, kind)
                            }
                            // 位置在窗口创建时读取，想让重置立刻生效必须重建窗口：
                            // 用「全部关掉再全部开」触发服务的增量同步逻辑。
                            val was = MonitorState.enabled.value
                            MonitorState.setAll(context, emptySet())
                            MonitorState.setAll(context, was)
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "窗口可直接拖动摆位，位置自动保存。重置后回到默认落点，" +
                            "并在下次开启窗口时生效。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }

        // ── 说明 ────────────────────────────────────────────────
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 5.dp)) {
                    CardSectionLabel("说明")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "· 每个监视器都是独立的悬浮窗，可任意组合同时开启。\n" +
                            "· 进程 / 线程监视器需要 Root：Android 12+ 起应用身份读不到" +
                            "其他进程的 /proc 节点，未提权时窗口会显示「需要 Root」。\n" +
                            "· 温度取自内核 thermal zone 的语义选择（CPU 结温优先），" +
                            "而不是固定读 thermal_zone0——编号由内核注册顺序决定，\n" +
                            "  该节点在部分机型上是 Always-On 子系统，数值偏低。\n" +
                            "· 本管理器与「帧率」页的跨应用实时监视是两套独立开关，可同时开启。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }
    }
}

/**
 * 开关变化后的服务生命周期同步。
 *
 * 抽成函数是因为「开」和「关」的判断不对称：
 * - 开启任意一个 → 必须确保服务在跑（可能在之前已经被关掉了）；
 * - 关闭任意一个 → 只有**一个都不剩**时才停服务，否则会把还在用的窗口一起干掉。
 *
 * 这个「只有一个都不剩才停」的规则很容易写成「一关就停」，
 * 那样用户关掉第一个监视器时其余 5 个会全部消失。
 */
private fun syncService(context: Context, justTurnedOn: Boolean) {
    if (justTurnedOn) {
        MonitorOverlayService.start(context)
    } else if (MonitorState.enabled.value.isEmpty()) {
        MonitorOverlayService.stop(context)
    }
}
