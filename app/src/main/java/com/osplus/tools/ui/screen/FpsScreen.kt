package com.osplus.tools.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.FpsOverlayState
import com.osplus.tools.core.FpsRecorder
import com.osplus.tools.core.Preferences
import com.osplus.tools.core.Shell
import com.osplus.tools.service.FpsOverlayService
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.CoreBarsChart
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.MetricChartCard
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SegmentedTabs
import com.osplus.tools.ui.components.SwitchRow
import com.osplus.tools.ui.components.downsample
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text

/** 帧率记录的分析窗口 */
private enum class FpsWindow(val label: String, val seconds: Int) {
    S30("30 秒", 30),
    M1("1 分钟", 60),
    M5("5 分钟", 300),
    M10("10 分钟", 600),
    All("全部", 0),
}

/**
 * 把毫秒时长格式化为时钟样式：
 * 不足 1 小时用 `mm:ss`，超过则用 `h:mm:ss`。
 */
private fun fmtElapsed(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 帧率（一级页）：记录开关、悬浮窗、多档分析窗口与逐项趋势。
 *
 * 录制期间刻意不绘制图表，避免绘图开销污染正在测量的帧率数据。
 */
@Composable
fun FpsScreen(vm: DeviceViewModel) {
    val context = LocalContext.current
    val c = osColors()
    val liveSample by FpsRecorder.sample.collectAsStateWithLifecycle()
    val records by vm.fpsRecords.collectAsStateWithLifecycle()
    val recording by vm.fpsRecording.collectAsStateWithLifecycle()
    val exportPath by vm.lastExportPath.collectAsStateWithLifecycle()
    val coreIndexes by vm.coreIndexes.collectAsStateWithLifecycle()

    // 开关状态取服务真实运行状态，而不是偏好值——否则应用被强停后
    // 开关会显示「开启」但服务已死，用户一点反而把它关掉
    val overlayRunning by vm.overlayRunning.collectAsStateWithLifecycle()
    val overlayAlpha by vm.overlayAlpha.collectAsStateWithLifecycle()
    val overlayGranted = remember { Settings.canDrawOverlays(context) }
    var window by rememberSaveable { mutableIntStateOf(1) }

    // 注意：这里**不能**在 onDispose 里停止录制。
    // 切到其他页面 / 其他应用都会让本 Composable 离开组合，
    // 那样会导致「一离开本页记录就断」——录制状态由 ViewModel 持有，
    // 只应在用户显式关闭开关时停止。

    val selected = FpsWindow.entries[window]
    val windowed = remember(records, selected) {
        if (selected.seconds == 0) {
            records
        } else {
            val cutoff = System.currentTimeMillis() - selected.seconds * 1000L
            records.filter { it.timeMs >= cutoff }
        }
    }
    val latest = records.lastOrNull()

    // 记录时长 = 最新一条与首条的时间差。
    // 录制中必须用「当前时刻」而不是「最后一条的时间戳」来算，
    // 否则每秒才前进一格、且中间停顿无法体现；配合下面的一秒心跳，
    // 时长会在开始记录后立刻开始连续增长。
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording) {
        while (recording) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedMs = records.firstOrNull()?.let { first ->
        val end = if (recording) nowMs else records.last().timeMs
        (end - first.timeMs).coerceAtLeast(0L)
    } ?: 0L
    val elapsedText = fmtElapsed(elapsedMs)

    // 折线降采样槽位：长窗口下按此数量抽稀后绘制
    val bars = 60
    // 时间轴左端文案随窗口变化
    val axisStart = when (selected) {
        FpsWindow.All -> "最早"
        else -> "${selected.seconds} 秒前"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "记录控制") {
                Column(Modifier.padding(vertical = 2.dp)) {
                    SwitchRow(
                        label = "开始记录",
                        summary = "每秒留档一条：帧率 + 每核占用/频率 + GPU + 内存 + 功耗 + 温度" +
                            "（也可轻点桌面悬浮窗开关）",
                        checked = recording,
                        onCheckedChange = {
                            if (it) vm.startFpsRecording() else vm.stopFpsRecording()
                        },
                    )
                    SwitchRow(
                        label = "悬浮窗显示并跨应用记录",
                        summary = if (overlayGranted) {
                            "在其他应用上层显示帧率；开启后即使切到别的应用，" +
                                "帧率与系统指标仍会持续记录（前台服务保活）"
                        } else {
                            "需先授予悬浮窗权限"
                        },
                        checked = overlayRunning,
                        enabled = overlayGranted,
                        onCheckedChange = { on ->
                            // 不直接改本地状态：状态由服务真实运行情况回写，
                            // 否则启动失败时开关会显示成「已开启」的假状态
                            Preferences.setFpsOverlayEnabled(context, on)
                            if (on) FpsOverlayService.start(context)
                            else FpsOverlayService.stop(context)
                        },
                    )
                    if (overlayGranted) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "悬浮窗不透明度",
                                style = OsText.caption,
                                color = c.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "%.0f%%".format(overlayAlpha * 100f),
                                style = OsText.value,
                                color = c.textPrimary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Slider(
                            value = overlayAlpha,
                            onValueChange = { vm.setOverlayAlpha(it) },
                            valueRange = FpsOverlayState.MIN_ALPHA..1f,
                        )
                        Text(
                            text = "悬浮窗上轻点一下即可开始 / 停止记录（记录中数值前会显示红点），" +
                                "长按或拖动可移动到任意位置，位置与不透明度都会被记住。",
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.exportFpsCsv() }, enabled = records.isNotEmpty()) {
                            Text("导出 CSV")
                        }
                        Button(onClick = { vm.clearFpsRecords() }, enabled = records.isNotEmpty()) {
                            Text("清空记录")
                        }
                    }
                    if (recording && !overlayRunning) {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner(
                            text = "建议同时开启悬浮窗：未开启时切到其他应用，本应用可能被系统冻结，" +
                                "帧率与系统指标会停止采集。开启后由前台服务保活，可跨应用持续记录。",
                        )
                    }
                    if (!overlayGranted) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                        .setData(Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("授权悬浮窗") }
                    }
                    exportPath?.let {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner(text = "已导出到 $it", accent = ChartColors.gpu)
                    }
                }
            }
        }

        item {
            SectionCard(title = "分析窗口") {
                Column(Modifier.padding(vertical = 4.dp)) {
                    SegmentedTabs(
                        tabs = FpsWindow.entries.map { it.label },
                        selectedIndex = window,
                        onSelect = { window = it },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "已记录 ${records.size} 条 · 时长 $elapsedText" +
                            " · 当前窗口 ${windowed.size} 条 · 图中 ${bars} 个点",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }

        if (recording) {
            // 记录期间刻意不渲染图表：每秒重绘 7 个 Canvas 会占用 GPU/CPU，
            // 既让本应用自身卡顿，也污染正在测量的帧率数据。
            // 停止记录后再统一绘图分析。
            item {
                SectionCard(title = "正在记录") {
                    Column(Modifier.padding(vertical = 3.dp)) {
                        InfoRow("已记录", "${records.size} 条", emphasis = true)
                        InfoRow("记录时长", elapsedText, emphasis = true)
                        InfoRow("当前帧率", "%.1f FPS".format(liveSample.fps))
                        InfoRow("当前 CPU", "%.0f%%".format(latest?.cpuLoad ?: 0f))
                        InfoRow("当前内存", "%.0f%%".format(latest?.memUsedPercent ?: 0f))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "记录期间不绘制图表，避免绘图开销污染帧率数据；" +
                                "停止记录后会自动生成折线 / 柱状分析图。",
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                    }
                }
            }
        } else if (records.isEmpty()) {
            item {
                SectionCard {
                    Text(
                        text = "开启「开始记录」后每秒留档一条完整指标；也可以直接轻点桌面悬浮窗开始记录。" +
                            "记录期间不绘图，避免绘图开销污染帧率数据，停止后再统一看分析图。",
                        style = OsText.label,
                        color = c.textSecondary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        } else {
            item {
                SectionCard(title = "帧率") {
                    Column(Modifier.padding(vertical = 2.dp)) {
                        MetricChartCard(
                            title = "实时帧率",
                            values = downsample(windowed.map { it.fps }, bars),
                            maxValue = autoMax(windowed.map { it.fps }, 0f, 60f),
                            color = ChartColors.fps,
                            unit = "FPS",
                            axisStartLabel = axisStart,
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricChartCard(
                            title = "平均帧耗时",
                            values = downsample(windowed.map { it.avgFrameMs }, bars),
                            maxValue = autoMax(windowed.map { it.avgFrameMs }, 0f, 33f),
                            color = ChartColors.mem,
                            unit = "ms",
                            valueFormatter = { "%.1f".format(it) },
                            axisStartLabel = axisStart,
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricChartCard(
                            title = "最大帧耗时",
                            values = downsample(windowed.map { it.maxFrameMs }, bars),
                            maxValue = autoMax(windowed.map { it.maxFrameMs }, 0f, 50f),
                            color = ChartColors.power,
                            unit = "ms",
                            valueFormatter = { "%.1f".format(it) },
                            axisStartLabel = axisStart,
                        )
                    }
                }
            }

            item {
                SectionCard(title = "同期系统指标") {
                    Column(Modifier.padding(vertical = 2.dp)) {
                        MetricChartCard(
                            title = "CPU 总占用",
                            values = downsample(windowed.map { it.cpuLoad }, bars),
                            maxValue = 100f,
                            color = ChartColors.cpu,
                            unit = "%",
                            axisStartLabel = axisStart,
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricChartCard(
                            title = "GPU 频率",
                            values = downsample(
                                windowed.map { if (it.gpuMhz > 0) it.gpuMhz.toFloat() else 0f },
                                bars,
                            ),
                            maxValue = autoMax(windowed.map { it.gpuMhz.toFloat() }, 0f, 800f),
                            color = ChartColors.gpu,
                            unit = "MHz",
                            axisStartLabel = axisStart,
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricChartCard(
                            title = "内存占用",
                            values = downsample(windowed.map { it.memUsedPercent }, bars),
                            maxValue = 100f,
                            color = ChartColors.mem,
                            unit = "%",
                            axisStartLabel = axisStart,
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricChartCard(
                            title = "整机功耗",
                            values = downsample(windowed.map { it.powerMw }, bars),
                            maxValue = autoMax(windowed.map { it.powerMw }, 0f, 500f),
                            color = ChartColors.power,
                            unit = "mW",
                            axisStartLabel = axisStart,
                        )
                    }
                }
            }

            item {
                SectionCard(title = "各核心占用（最新一条）") {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        CoreBarsChart(
                            coreIndexes = coreIndexes,
                            loads = latest?.coreLoads ?: emptyList(),
                            freqs = latest?.coreFreqsKhz ?: emptyList(),
                        )
                    }
                }
            }

            item {
                SectionCard(title = "记录汇总") {
                    Column(Modifier.padding(vertical = 3.dp)) {
                        val fpsValues = windowed.map { it.fps }.filter { it > 0 }
                        val avgFps = if (fpsValues.isEmpty()) 0.0 else fpsValues.average()
                        val jankTotal = windowed.sumOf { it.jank }
                        val bigJankTotal = windowed.sumOf { it.bigJank }
                        val lowFpsCount = windowed.count { it.fps in 0.01f..40f }
                        InfoRow("采样条数", "${windowed.size}")
                        InfoRow("平均帧率", "%.1f FPS".format(avgFps), emphasis = true)
                        InfoRow("卡顿帧合计", "$jankTotal")
                        InfoRow("严重卡顿合计", "$bigJankTotal")
                        InfoRow(
                            "低于 40 FPS 的秒数",
                            "$lowFpsCount 秒",
                            valueColor = if (lowFpsCount > 0) ChartColors.power else null,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "导出 CSV 后可结合每核占用与频率，判断卡顿来自 CPU 降频、" +
                                "GPU 瓶颈还是内存压力。",
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "当前实时统计") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    InfoRow("帧率", "%.1f FPS".format(liveSample.fps), emphasis = true)
                    InfoRow("平均帧耗时", "%.2f ms".format(liveSample.avgFrameMs))
                    InfoRow("最大帧耗时", "%.2f ms".format(liveSample.maxFrameMs))
                    InfoRow("卡顿帧(>2 帧)", "${liveSample.jankCount}")
                    InfoRow("严重卡顿(>4 帧)", "${liveSample.bigJankCount}")
                    InfoRow("本轮帧数", "${liveSample.totalFrames}")
                }
            }
        }

        item {
            SectionCard(title = "系统级帧率") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    var gfxInfo by remember { mutableStateOf("") }
                    LaunchedEffect(Unit) {
                        gfxInfo = Shell.run(
                            "dumpsys gfxinfo ${context.packageName} 2>/dev/null | head -n 24",
                            root = false,
                        ).stdout
                    }
                    if (gfxInfo.isBlank()) {
                        Text(
                            text = "无法读取系统帧率信息",
                            style = OsText.label,
                            color = c.textSecondary,
                        )
                    } else {
                        Text(
                            text = gfxInfo,
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                    }
                }
            }
        }
    }
}
