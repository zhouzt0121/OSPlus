package com.osplus.tools.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.osplus.tools.core.PrivilegeCapabilities
import com.osplus.tools.core.PrivilegeMode
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Text

/**
 * 提权相关的共享组件。
 *
 * 目前只有两块：状态横幅 [PrivilegeBanner] 与能力速查表 [PrivilegeCapabilityCard]。
 * 它们同时被「提权管理」页与「概览」页使用，因此提取到这里。
 *
 * ## 曾经存在过的组件
 *
 * 2.2.0 期间这里还有模式选择卡（三通道选择 + Shizuku 授权引导 +
 * ADB 配对表单 + 电脑脚本生成）与 ADB 配对区 / 输入框。
 * Shizuku 与 ADB 通道移除后这些一并删掉——见
 * [com.osplus.tools.core.PrivilegeMode] 的说明。
 */

/**
 * 顶部状态横幅。
 *
 * 文案跟着**能力**走而不是跟着模式走：能力表才是控件可用性的真值来源，
 * 按模式名硬编码的说法在新增 / 移除通道时必然漏改。
 */
@Composable
fun PrivilegeBanner(caps: PrivilegeCapabilities) {
    val mode = caps.mode
    when {
        mode == null -> NoticeBanner(
            text = "未获取 Root 权限：CPU 频率控制、进程管理、性能调度不可用；" +
                "基础信息读取仍可正常使用。可在下方查看并完成 Root 授权。",
        )
        mode == PrivilegeMode.ROOT -> NoticeBanner(
            text = "已通过 Root 提权，全部控制功能可用。",
            accent = ChartColors.gpu,
        )
        else -> NoticeBanner(
            text = "已通过 ${mode.label} 提权。",
            accent = ChartColors.cpu,
        )
    }
}

/**
 * 系统能力速查表。
 *
 * 把 [PrivilegeCapabilities] 里的每一项摊平成「功能 → 可用性」两列，
 * 用户不用去猜「为什么这个按钮是灰的」。
 */
@Composable
fun PrivilegeCapabilityCard(caps: PrivilegeCapabilities, modifier: Modifier = Modifier) {
    val c = osColors()
    SectionCard(modifier = modifier) {
        Column(Modifier.padding(vertical = 3.dp)) {
            CardSectionLabel("能力速查")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "按当前模式实际通过的能力。判据是各功能调用的节点路径与 SELinux 上下文，" +
                    "不是按模式名硬编码的假设。",
                style = OsText.micro,
                color = c.textTertiary,
            )
            Spacer(Modifier.height(9.dp))
            CapabilityRow("读取系统节点", caps.canReadSystemNodes)
            CapabilityRow("写入调频节点", caps.canWriteSysfs)
            CapabilityRow("进程管理", caps.canManageProcesses)
            CapabilityRow("性能调度（Magisk 模块）", caps.canControlPerfSched)

            if (!caps.canWriteSysfs) {
                Spacer(Modifier.height(10.dp))
                NoticeBanner(text = caps.reasonForFreqControl(), accent = ChartColors.cpu)
            }
            if (!caps.canControlPerfSched && caps.available) {
                Spacer(Modifier.height(8.dp))
                NoticeBanner(text = caps.reasonForPerfSched(), accent = ChartColors.cpu)
            }
        }
    }
}

@Composable
private fun CapabilityRow(label: String, ok: Boolean) {
    val c = osColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = OsText.label,
            color = c.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (ok) "可用" else "不可用",
            style = OsText.value,
            color = if (ok) ChartColors.gpu else c.textTertiary,
        )
    }
}
