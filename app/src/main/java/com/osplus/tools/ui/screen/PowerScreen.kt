package com.osplus.tools.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.RingChart
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.basic.Text

/**
 * 电源（一级页）。
 *
 * 从二级页提升为一级页的理由：电池是手机上唯一会「耗尽」的资源，
 * 用户对它的焦虑频率高于 CPU 频率，而它此前藏在概览页的电池卡之后。
 *
 * 版面按「先给剩余能力，再给消耗明细，最后给控制手段」组织：
 * 顶部态势卡回答「还能用多久」，其下三个页签依次回答
 * 「谁在耗电」「充电情况如何」「能怎么限制」。
 */
@Composable
fun PowerScreen(vm: DeviceViewModel) {
    val battery by vm.battery.collectAsStateWithLifecycle()
    val c = osColors()

    val charging = battery.status.contains("充电") || battery.plugged.contains("USB") ||
        battery.plugged.contains("AC") || battery.plugged.contains("无线")
    val currentMa = battery.currentNowUa / 1000f
    val voltText = if (battery.voltageMv > 0) "%.2f V".format(battery.voltageMv / 1000f) else "-"
    val tempText = battery.tempC?.let { "%.1f ℃".format(it) } ?: "-"
    val statusText = listOf(battery.status, battery.plugged)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { "-" }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp)) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingChart(
                        progress = if (battery.levelPercent >= 0) battery.levelPercent / 100f else 0f,
                        color = if (charging) ChartColors.gpu else ChartColors.power,
                        label = "电池",
                        value = if (battery.levelPercent >= 0) "${battery.levelPercent}" else "-",
                        unit = "%",
                        size = 92.dp,
                        stroke = 13.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = batteryRemainingText(battery, charging),
                            style = OsText.valueStrong,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "$voltText · $tempText · ${"%.0f".format(currentMa)} mA",
                            style = OsText.caption,
                            color = c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = statusText,
                            style = OsText.micro,
                            color = c.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // 三个页签沿用原有实现：耗电统计 / 充电统计 / 充电控制
        PowerDetailScreen(vm)
    }
}
