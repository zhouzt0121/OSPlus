package com.osplus.tools.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.PrivilegeMode
import com.osplus.tools.core.Shell
import com.osplus.tools.ui.components.CardSectionLabel
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.LiquidNavTabs
import com.osplus.tools.ui.components.PrivilegeBanner
import com.osplus.tools.ui.components.PrivilegeCapabilityCard
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.bottomBarContentPadding
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.basic.Text

/**
 * 提权管理独立页。
 *
 * ## 为什么不复用设置页
 *
 * 设置页里的提权入口是「顺带一提」——它和主题、通知、功率校准挤在同一列里，
 * 而提权其实是一个**有完整流程**的子功能：探测 → 授权 → 验证能力 → 排障。
 * 把流程塞进一张卡片的结果是：要么卡片长到看不见底，
 * 要么排障信息被砍掉，用户遇到「切了没反应」时无处可查。
 *
 * 本页把流程完整摊开，并补上设置页里没有的两块：
 * - **能力速查**：当前模式实际通过哪些能力、为什么某些不可用
 * - **探测明细**：原始探测结果，排障时直接看这一屏
 *
 * ## 注意：只剩 Root 一条通道
 *
 * 2.2.0 的 Shizuku / ADB 选择卡与配对表单已随通道一并移除。
 * 原因见 [PrivilegeMode] 的说明。
 */
@Composable
fun PrivilegeScreen(viewModel: DeviceViewModel) {
    val c = osColors()
    val rootAvailable by viewModel.rootAvailable.collectAsStateWithLifecycle()
    val selection by viewModel.privilegeSelection.collectAsStateWithLifecycle()
    val mode by viewModel.privilegeMode.collectAsStateWithLifecycle()
    val probe by viewModel.privilegeProbe.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val message by viewModel.privilegeMessage.collectAsStateWithLifecycle()

    val suPath = remember(rootAvailable) { Shell.findSu() ?: "未找到" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PrivilegeBanner(capabilities) }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("Root 授权")
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "OSPlus 的全部控制功能（CPU 调频、进程管理、性能调度、帧率读取）" +
                            "都需要 Root 身份。请在系统 root 管理器（Magisk / KernelSU / APatch）" +
                            "中允许本应用取得 su。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    LiquidNavTabs(
                        items = listOf("请求 Root 授权"),
                        selectedIndex = -1,
                        onSelect = {
                            viewModel.selectPrivilegeMode(PrivilegeMode.ROOT)
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    InfoRow("Root", value = if (rootAvailable) "已授权" else "未授权", emphasis = true)
                    InfoRow("su 路径", suPath)
                }
            }
        }

        item { PrivilegeCapabilityCard(capabilities) }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("探测明细")
                    Spacer(Modifier.height(4.dp))
                    InfoRow("当前生效模式", mode?.label ?: "无", emphasis = true)
                    InfoRow("用户点选", selection?.label ?: "未选择")
                    InfoRow("Root 可用", if (rootAvailable) "是" else "否")
                    InfoRow("su 路径", suPath)
                    InfoRow("通道就绪", if (probe.rootReady) "是" else "否")
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("重新探测")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "刚在 root 管理器里改过授权、或重新装过 Magisk 之后，" +
                            "探测结果可能已经过期，点下方按钮重新跑一次完整探测。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    LiquidNavTabs(
                        items = listOf("重新探测提权通道"),
                        selectedIndex = -1,
                        onSelect = { viewModel.reprobePrivilege() },
                    )
                }
            }
        }

        if (message != null) {
            item {
                SectionCard {
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            text = message.orEmpty(),
                            style = OsText.caption,
                            color = c.textPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        LiquidNavTabs(
                            items = listOf("知道了"),
                            selectedIndex = -1,
                            onSelect = { viewModel.clearPrivilegeMessage() },
                        )
                    }
                }
            }
        }
    }
}
