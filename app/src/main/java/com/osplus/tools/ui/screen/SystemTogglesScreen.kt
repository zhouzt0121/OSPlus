package com.osplus.tools.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.SystemTogglesDataSource
import com.osplus.tools.ui.components.CardSectionLabel
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.ChoiceChip
import com.osplus.tools.ui.components.LiquidNavTabs
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SwitchRow
import com.osplus.tools.ui.components.bottomBarContentPadding
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.basic.Text

/**
 * 系统开关页。
 *
 * 全部条目都是 `settings get/put`，覆盖 Scene5 在 `aosp.xml` / `developer.xml`
 * 里提供的同类功能。与 Scene5 的两点差别：
 *
 * 1. **不做机型适配分支**。Scene5 的 `visible="<脚本>"` 靠脚本判断某个开关在
 *    当前 ROM 上是否存在；这里改成「写入后如实回执」——写不进去就报失败，
 *    不假装成功。少一层猜测，多一层可验证。
 * 2. **合并读写**。Scene5 每个控件各自起一条脚本；这里一次命令读全部、
 *    写的时候才单独落一条，首屏只有一个往返。
 *
 * 页首的动画缩放是**单选组**而非开关：它只有几个离散档位，
 * 用开关表达不了「0.5x / 1x / 1.5x / 2x」这层信息。
 */
@Composable
fun SystemTogglesScreen(viewModel: DeviceViewModel) {
    val c = osColors()
    val toggles by viewModel.sysToggles.collectAsStateWithLifecycle()
    val scale by viewModel.animationScale.collectAsStateWithLifecycle()
    val blacklist by viewModel.iconBlacklist.collectAsStateWithLifecycle()
    val loaded by viewModel.sysTogglesLoaded.collectAsStateWithLifecycle()
    val notice by viewModel.sysToggleNotice.collectAsStateWithLifecycle()

    // 进页读一次。读失败时 loaded 保持 false，界面显示「读取中」而不是
    // 「全部已关闭」——后者会让用户以为开关真的被自己关掉了
    LaunchedEffect(Unit) { viewModel.refreshSystemToggles() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottomBarContentPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        notice?.let {
            NoticeBanner(text = it, accent = ChartColors.cpu)
        }

        // ---------------- 动画 ----------------
        SectionCard {
            CardSectionLabel("动画速度")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "同时修改窗口动画、过渡动画、动画时长三个倍率。调快手感更跟手，" +
                    "但部分应用的自定义动画可能出现跳帧。",
                style = com.osplus.tools.ui.theme.OsText.micro,
                color = c.textTertiary,
            )
            Spacer(Modifier.height(10.dp))
            LiquidNavTabs(
                items = SystemTogglesDataSource.ANIMATION_SCALES.map { it.second },
                selectedIndex = SystemTogglesDataSource.ANIMATION_SCALES
                    .indexOfFirst { it.first == scale },
                onSelect = { idx ->
                    viewModel.setAnimationScale(
                        SystemTogglesDataSource.ANIMATION_SCALES[idx].first,
                    )
                },
                enabled = loaded,
            )
        }

        // ---------------- 开发者选项 ----------------
        SectionCard {
            CardSectionLabel("开发者选项")
            Spacer(Modifier.height(4.dp))
            // 这些开关本身就在开发者选项里，写进去不会自动出现「开发者选项已开启」，
            // 但读取它们的代码路径与普通开关一致，因此放在这里说明，避免用户困惑
            Text(
                text = "以下条目对应开发者选项中的同名开关，直接写入即可生效，" +
                    "无需先在设置里打开开发者选项。",
                style = com.osplus.tools.ui.theme.OsText.micro,
                color = c.textTertiary,
            )
            Spacer(Modifier.height(10.dp))
            SystemTogglesDataSource.TOGGLES
                .filter { it.id in DEV_TOGGLE_IDS }
                .forEach { def ->
                    SwitchRow(
                        label = def.label,
                        checked = toggles[def.id] ?: false,
                        summary = def.desc,
                        enabled = loaded,
                        onCheckedChange = { viewModel.setSystemToggle(def, it) },
                    )
                }
        }

        // ---------------- 状态栏图标 ----------------
        SectionCard {
            CardSectionLabel("隐藏状态栏图标")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "选中的图标会从状态栏隐藏。该设置是**整体覆盖**——" +
                    "每次改动都会把完整集合写回去，因此不存在「漏选即丢失」的问题。",
                style = com.osplus.tools.ui.theme.OsText.micro,
                color = c.textTertiary,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SystemTogglesDataSource.ICON_BLACKLIST_ITEMS.forEach { (key, label) ->
                    ChoiceChip(
                        text = label,
                        selected = key in blacklist,
                        enabled = loaded,
                        onClick = { viewModel.toggleIconBlacklist(key) },
                    )
                }
            }
        }
    }
}

// 「界面行为」卡片（悬浮通知 / 深色模式 / 自动旋转 / 呼吸灯 / 显示刷新率）已于 2.6.5 移除。
//
// 移除原因：真机实测（ColorOS / SM8750）这 5 项 `settings put` 全部写入成功、
// 回读也正确（`uid=0(root)`，`which settings` = `/system/bin/settings`），
// 但 **系统不响应** —— 拨动开关后实际行为不变。原因是 ColorOS 用自家的
// 设置后端接管了这几个开关，AOSP 的 settings 键写进去不会触发系统状态变更。
// 界面「能拨但没效果」比不提供更糟：用户会以为是应用坏了。
// 保留一个诚实的做法是整体移除，而不是加一句「本机型可能无效」的免责声明。
//
// `SystemTogglesDataSource.TOGGLES` 里对应的定义一并删除。

/** 归属「开发者选项」卡的开关 id */
private val DEV_TOGGLE_IDS = setOf(
    "show_touches",
    "pointer_location",
    "force_gpu",
    "freeform",
    "force_resizable",
    "adb_over_network",
)
