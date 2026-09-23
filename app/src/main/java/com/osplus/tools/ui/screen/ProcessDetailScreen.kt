package com.osplus.tools.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SwitchRow
import com.osplus.tools.ui.components.UsageBar
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

/** 进程详情：全量进程列表、按 CPU/内存排序、强制停止与结束进程 */
@Composable
fun ProcessDetailScreen(vm: DeviceViewModel) {
    val processes by vm.processes.collectAsStateWithLifecycle()
    val icons by vm.appIcons.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val c = osColors()
    var sortByCpu by rememberSaveable { mutableStateOf(true) }
    var autoRefresh by rememberSaveable { mutableStateOf(true) }

    // 用户触摸列表期间跳过刷新：自动刷新会重排列表，
    // 若在长按手势进行中重排，手势会被取消，长按操作就点不出来
    var lastTouchAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(autoRefresh) {
        while (autoRefresh) {
            val idle = System.currentTimeMillis() - lastTouchAtMs > 1200
            if (idle) vm.refreshProcesses()
            delay(2000)
        }
    }

    // 进程列表变化后按需补齐缺失的应用图标
    LaunchedEffect(processes) {
        vm.loadAppIcons(processes.mapNotNull { it.packageName })
    }

    val sorted = remember(processes, sortByCpu) {
        if (sortByCpu) processes.sortedByDescending { it.cpuPercent }
        else processes.sortedByDescending { it.rssKb }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.pressed }) {
                            lastTouchAtMs = System.currentTimeMillis()
                        }
                    }
                }
            },
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 2.dp)) {
                    SwitchRow(
                        label = "自动刷新",
                        summary = "每 2 秒重新采样一次进程占用",
                        checked = autoRefresh,
                        onCheckedChange = { autoRefresh = it },
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (sortByCpu) "按 CPU 占用排序" else "按内存占用排序",
                            style = OsText.caption,
                            color = c.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { sortByCpu = !sortByCpu }) {
                            Text(if (sortByCpu) "内存" else "CPU")
                        }
                    }
                    if (!rootAvailable) {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner("结束进程需要 Root 权限")
                    }
                }
            }
        }

        items(sorted.take(120), key = { it.pid }) { p ->
            SectionCard {
                Column(Modifier.padding(vertical = 2.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val icon = p.packageName?.let { icons[it] }
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(9.dp)),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            text = p.name,
                            style = OsText.value,
                            color = c.textPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "%.1f%%".format(p.cpuPercent),
                            style = OsText.metricSmall,
                            color = if (p.cpuPercent > 50f) ChartColors.power else ChartColors.cpu,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    UsageBar(
                        fraction = (p.cpuPercent / 100f).coerceIn(0f, 1f),
                        color = ChartColors.cpu,
                        barHeight = 5.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "PID ${p.pid} · ${p.user} · 内存 ${p.rssKb / 1024} MB · 状态 ${p.state}",
                        style = OsText.micro,
                        color = c.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    p.packageName?.let { pkg ->
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.forceStop(pkg) }, enabled = rootAvailable) {
                                Text("强制停止")
                            }
                            Button(onClick = { vm.killProcess(p.pid) }, enabled = rootAvailable) {
                                Text("结束进程")
                            }
                        }
                    }
                }
            }
        }
    }
}
