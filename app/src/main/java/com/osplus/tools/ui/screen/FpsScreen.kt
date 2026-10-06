package com.osplus.tools.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.osplus.tools.core.SysFpsDataSource
import com.osplus.tools.service.FpsOverlayService
import com.osplus.tools.ui.components.CardSectionLabel
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.CoreBarsChart
import com.osplus.tools.ui.components.AxisSeries
import com.osplus.tools.ui.components.DualAxisChart
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.MetricChartCard
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SwitchRow
import com.osplus.tools.ui.components.downsample
import com.osplus.tools.ui.components.frameRateMax
import com.osplus.tools.ui.components.frameRateTicks
import com.osplus.tools.ui.components.panelRefreshHz
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import kotlinx.coroutines.delay
import com.osplus.tools.ui.components.LiquidNavTabs
import com.osplus.tools.ui.components.LiquidSlider
import com.osplus.tools.ui.components.bottomBarContentPadding
import top.yukonga.miuix.kmp.basic.Text

/** 帧率记录的分析窗口 */
private enum class FpsWindow(val label: String, val seconds: Int) {
    S30("30 秒", 30),
    M1("1 分钟", 60),
    M5("5 分钟", 300),
    M10("10 分钟", 600),
    All("全部", 0),
}

/** 双轴图右轴维度定义（显示名 / 颜色 / 取值 / 单位） */
private val RIGHT_DIMS: List<Quad<String, androidx.compose.ui.graphics.Color, (com.osplus.tools.model.FpsRecord) -> Float, String>> = listOf(
    Quad("温度", ChartColors.power, { r -> r.batteryTempC ?: -1f }, "°C"),
    Quad("CPU 负载", ChartColors.cpu, { r -> r.cpuLoad.coerceAtLeast(0f) }, "%"),
    Quad("GPU 负载", ChartColors.gpu, { r -> if (r.gpuLoad >= 0) r.gpuLoad.toFloat() else -1f }, "%"),
    Quad("内存", ChartColors.mem, { r -> r.memUsedPercent }, "%"),
)

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/** 时间戳 → `MM-dd HH:mm`，用于历史会话列表 */
private fun fmtClock(ms: Long): String =
    if (ms <= 0L) "未知时间" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))

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
 *
 * @param onOpenAnalysis 点会话列表的「分析」时调用，跳转到独立的分析整页
 */
@Composable
fun FpsScreen(vm: DeviceViewModel, onOpenAnalysis: () -> Unit = {}) {
    // 面板刷新率上限，作为帧率纵轴的常驻参照（见 Charts.frameRateMax）。
    // 变量名带 Hz 后缀以免遮蔽同名 composable。
    val panelHz = panelRefreshHz()
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

    // 系统级帧率与历史会话
    val sysFps by vm.sysFps.collectAsStateWithLifecycle()
    // **有效帧率：系统级优先。**
    // 界面上的「帧率」统一用这个值——系统级读的是屏幕上真实发生的帧率，
    // 应用自身 Choreographer 只反映本应用渲染节奏（切到别的应用后基本无意义）。
    // 系统级读不到时才退回自身值，保证界面始终有数可看。
    // 自身值仍在「本应用帧率」那一行单独展示，便于对照排查。
    val effectiveFps = if (sysFps > 0f) sysFps else liveSample.fps
    val sessions by vm.fpsSessions.collectAsStateWithLifecycle()
    val viewingId by vm.viewingSessionId.collectAsStateWithLifecycle()
    val viewingSamples by vm.viewingSamples.collectAsStateWithLifecycle()
    // 双轴图右轴维度：0=温度 1=CPU负载 2=GPU负载 3=内存
    var rightDim by rememberSaveable { mutableIntStateOf(0) }

    // 页面可见时才采系统级帧率——它靠 shell 读 sysfs，每秒一次进程开销不小
    DisposableEffect(Unit) {
        vm.setSysFpsWanted(true)
        onDispose { vm.setSysFpsWanted(false) }
    }
    LaunchedEffect(Unit) { vm.refreshFpsSessions() }

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

    // **当前要展示的数据集**：查看历史会话时是历史采样，否则是实时窗口。
    //
    // 这里刻意做成一个统一下游数据源，而不是让每张图各自判断 —— 之前只有
    // 双轴图做了 `if (viewingSamples.isNotEmpty())` 的切换，其余图表
    // （帧率曲线 / 帧耗时 / CPU / GPU / 内存 / 功耗 / 记录汇总）全都直接读
    // `windowed`。结果是重启后打开历史会话：双轴图有数据、下面一堆图全是空的，
    // 而且整个图表区还会被 `records.isEmpty()` 判成空状态根本不渲染。
    val src = if (viewingSamples.isNotEmpty()) viewingSamples else windowed
    val viewing = viewingSamples.isNotEmpty()

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
    // 时长统计跟着当前数据集走：看历史时算的是那一段的时长，
    // 而不是空的内存列表（那样会一直显示 00:00）。
    val elapsedMs = src.firstOrNull()?.let { first ->
        val end = if (recording && !viewing) nowMs else src.last().timeMs
        (end - first.timeMs).coerceAtLeast(0L)
    } ?: 0L
    val elapsedText = fmtElapsed(elapsedMs)

    // 折线降采样槽位：长窗口下按此数量抽稀后绘制
    val bars = 60
    // 时间轴左端文案随窗口变化；看历史时左端就是那一段的起点
    val axisStart = when {
        viewing -> "会话起点"
        selected == FpsWindow.All -> "最早"
        else -> "${selected.seconds} 秒前"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 2.dp)) {
                    CardSectionLabel("记录控制")
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        label = "开始记录",
                        summary = "每秒留档一条：帧率 + 每核占用/频率 + GPU + 内存 + 功耗 + 温度" +
                            "（也可点实时任务通知上的按钮）",
                        checked = recording,
                        onCheckedChange = {
                            if (it) vm.startFpsRecording() else vm.stopFpsRecording()
                        },
                    )
                    SwitchRow(
                        label = "跨应用实时监视",
                        summary = "以前台服务常驻，切到其他应用后仍持续采集帧率与系统指标；" +
                            "呈现方式（实时任务通知 / 悬浮窗）与显示项在「设置」里配置",
                        checked = overlayRunning,
                        onCheckedChange = { on ->
                            // 不直接改本地状态：状态由服务真实运行情况回写，
                            // 否则启动失败时开关会显示成「已开启」的假状态
                            Preferences.setFpsOverlayEnabled(context, on)
                            if (on) FpsOverlayService.start(context)
                            else FpsOverlayService.stop(context)
                        },
                    )
                    Text(
                        text = "呈现方式见「设置 → 实时任务通知」：开启时状态栏芯片与通知抽屉实时展示，" +
                            "关闭时改用悬浮窗；两种方式下都可直接在通知上开始 / 停止记录。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    // 与底栏同语言的动作条：整条玻璃横条与页面背景明显区分
                    LiquidNavTabs(
                        items = listOf("导出 CSV", "清空记录"),
                        selectedIndex = -1,
                        onSelect = { if (records.isNotEmpty()) { if (it == 0) vm.exportFpsCsv() else vm.clearFpsRecords() } },
                        enabled = records.isNotEmpty(),
                    )
                    exportPath?.let {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner(text = "已导出到 $it", accent = ChartColors.gpu)
                    }
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    CardSectionLabel("分析窗口")
                    Spacer(Modifier.height(6.dp))
                    LiquidNavTabs(
                        items = FpsWindow.entries.map { it.label },
                        selectedIndex = window,
                        onSelect = { window = it },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        // 看历史会话时统计的是那一段（src），否则重启后这里会恒显示
                        // 「已记录 0 条」——而下面明明有历史曲线，自相矛盾。
                        text = if (viewing) {
                            "历史会话 ${src.size} 条 · 时长 $elapsedText · 图中 ${bars} 个点"
                        } else {
                            "已记录 ${records.size} 条 · 时长 $elapsedText" +
                                " · 当前窗口 ${windowed.size} 条 · 图中 ${bars} 个点"
                        },
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
                SectionCard {
                    Column(Modifier.padding(vertical = 3.dp)) {
                        CardSectionLabel("正在记录")
                        Spacer(Modifier.height(6.dp))
                        InfoRow("已记录", "${records.size} 条", emphasis = true)
                        InfoRow("记录时长", elapsedText, emphasis = true)
                        InfoRow("当前帧率", "%.1f FPS".format(effectiveFps))
                        InfoRow(
                            "当前 CPU",
                            (latest?.cpuLoad ?: -1f).let {
                                if (it < 0f) "不可读" else "%.0f%%".format(it)
                            },
                        )
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
        } else if (records.isEmpty() && viewingSamples.isEmpty()) {
            // 注意判据必须同时看 `viewingSamples`（历史会话的采样）。
            //
            // 原来只判 `records.isEmpty()`，而 `records` 是**内存里的实时记录** ——
            // 重启应用后它是空的，此时打开历史会话虽然 `viewingSamples` 已加载好，
            // 却因为 `records` 为空而走进这个空状态分支，整个图表区被
            // 「开启开始记录后…」的提示占位，历史曲线根本不渲染。
            // 这正是用户报的「重新打开 OSPlus 之后，之前的记录无法查看」。
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
                SectionCard {
                    Column(Modifier.padding(vertical = 2.dp)) {
                        CardSectionLabel("帧率与系统指标叠加")
                        Spacer(Modifier.height(6.dp))
                        // 右轴维度切换：帧率与温度/负载的量纲差一个数量级，
                        // 只有各自占满自己的轴，「温度上来帧率就掉」才看得出来
                        LiquidNavTabs(
                            items = RIGHT_DIMS.map { it.first },
                            selectedIndex = rightDim,
                            onSelect = { rightDim = it },
                        )
                        Spacer(Modifier.height(10.dp))
                        val t0 = src.firstOrNull()?.timeMs ?: 0L
                        val spanMs = ((src.lastOrNull()?.timeMs ?: t0) - t0).coerceAtLeast(1_000L)
                        val dim = RIGHT_DIMS[rightDim]
                        DualAxisChart(
                            left = AxisSeries(
                                label = "帧率",
                                color = ChartColors.fps,
                                values = downsample(src.map { it.fps }, bars),
                            ),
                            right = AxisSeries(
                                label = dim.first,
                                color = dim.second,
                                values = downsample(src.map { dim.third(it) }, bars),
                            ),
                            xMaxMs = spanMs,
                            leftUnit = "FPS",
                            rightUnit = dim.fourth,
                            leftPanelMax = panelHz,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "左轴为帧率（量程按 60/90/120/144/165/185 挡位对齐，跌一半就在图上一半）；" +
                                "右轴可切换温度 / 负载 / 内存。虚线为 45 FPS 低帧参考线。" +
                                (if (viewingSamples.isNotEmpty()) "当前展示的是历史会话，不是实时数据。" else ""),
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Column(Modifier.padding(vertical = 2.dp)) {
                        CardSectionLabel("帧率曲线")
                        Spacer(Modifier.height(6.dp))
                        MetricChartCard(
                            title = "实时帧率",
                            values = downsample(src.map { it.fps }, bars),
                            // 纵轴跟着窗口峰值走：120 面板画到 120，不再固定画到 185。
                            // 原因见 frameRateMax 的说明。
                            maxValue = frameRateMax(
                                observedPeak = src.maxOfOrNull { it.fps } ?: 0f,
                                panelMax = panelHz,
                            ),
                            color = ChartColors.fps,
                            unit = "FPS",
                            axisStartLabel = axisStart,
                            // 帧率的 Y 轴刻度走面板挡位（60/90/120/144/165/185），
                            // 不用等分——等分出来的 33/66/99/132 对不上任何真实刷新率
                            yTicks = { frameRateTicks(it) },
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricChartCard(
                            title = "平均帧耗时",
                            values = downsample(src.map { it.avgFrameMs }, bars),
                            maxValue = autoMax(src.map { it.avgFrameMs }, 0f, 33f),
                            color = ChartColors.mem,
                            unit = "ms",
                            valueFormatter = { "%.1f".format(it) },
                            axisStartLabel = axisStart,
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricChartCard(
                            title = "最大帧耗时",
                            values = downsample(src.map { it.maxFrameMs }, bars),
                            maxValue = autoMax(src.map { it.maxFrameMs }, 0f, 50f),
                            color = ChartColors.power,
                            unit = "ms",
                            valueFormatter = { "%.1f".format(it) },
                            axisStartLabel = axisStart,
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Column(Modifier.padding(vertical = 2.dp)) {
                        CardSectionLabel("同期系统指标")
                        Spacer(Modifier.height(6.dp))
                        MetricChartCard(
                            title = "CPU 总占用",
                            values = downsample(src.map { it.cpuLoad.coerceAtLeast(0f) }, bars),
                            maxValue = 100f,
                            color = ChartColors.cpu,
                            unit = "%",
                            axisStartLabel = axisStart,
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricChartCard(
                            title = "GPU 频率",
                            values = downsample(
                                src.map { if (it.gpuMhz > 0) it.gpuMhz.toFloat() else 0f },
                                bars,
                            ),
                            maxValue = autoMax(src.map { it.gpuMhz.toFloat() }, 0f, 800f),
                            color = ChartColors.gpu,
                            unit = "MHz",
                            axisStartLabel = axisStart,
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricChartCard(
                            title = "内存占用",
                            values = downsample(src.map { it.memUsedPercent }, bars),
                            maxValue = 100f,
                            color = ChartColors.mem,
                            unit = "%",
                            axisStartLabel = axisStart,
                        )
                        Spacer(Modifier.height(12.dp))
                        MetricChartCard(
                            title = "整机功耗",
                            values = downsample(src.map { it.powerMw }, bars),
                            maxValue = autoMax(src.map { it.powerMw }, 0f, 500f),
                            color = ChartColors.power,
                            unit = "mW",
                            axisStartLabel = axisStart,
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        CardSectionLabel("各核心占用（最新一条）")
                        Spacer(Modifier.height(6.dp))
                        CoreBarsChart(
                            coreIndexes = coreIndexes,
                            // 用当前数据集（src）的最后一条：历史模式下就是该会话的
                            // 末条采样。以前直接用 `latest`（内存实时记录的最后一条），
                            // 重启后打开历史会话时它是 null，整张图会是空的。
                            loads = src.lastOrNull()?.coreLoads ?: emptyList(),
                            freqs = src.lastOrNull()?.coreFreqsKhz ?: emptyList(),
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Column(Modifier.padding(vertical = 3.dp)) {
                        CardSectionLabel("记录汇总")
                        Spacer(Modifier.height(6.dp))
                        val fpsValues = src.map { it.fps }.filter { it > 0 }
                        val avgFps = if (fpsValues.isEmpty()) 0.0 else fpsValues.average()
                        val jankTotal = src.sumOf { it.jank }
                        val bigJankTotal = src.sumOf { it.bigJank }
                        val lowFpsCount = src.count { it.fps in 0.01f..40f }
                        InfoRow("采样条数", "${src.size}")
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
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("当前实时统计")
                    Spacer(Modifier.height(6.dp))
                    // 「帧率」= 有效值：系统级可用时就是系统级，读不到才退回自身值。
                    // 配一行「数据来源」把当前用的是哪一路讲明白，避免用户对着
                    // 两个数字猜哪个更可信。
                    InfoRow("帧率", "%.1f FPS".format(effectiveFps), emphasis = true)
                    InfoRow(
                        "数据来源",
                        if (sysFps > 0f) "系统级（显示控制器实测）" else "本应用（逐帧回调）",
                    )
                    // 系统级可用时，把本应用自身帧率**额外**列出来供对照：
                    // 系统级正常而自身偏低 → 问题在本应用的渲染，而非设备整体。
                    if (sysFps > 0f) {
                        InfoRow("本应用帧率", "%.1f FPS".format(liveSample.fps))
                    } else {
                        InfoRow(
                            "系统帧率",
                            "不可读",
                            valueColor = null,
                        )
                    }
                    InfoRow("平均帧耗时", "%.2f ms".format(liveSample.avgFrameMs))
                    InfoRow("最大帧耗时", "%.2f ms".format(liveSample.maxFrameMs))
                    InfoRow("卡顿帧(>2 帧)", "${liveSample.jankCount}")
                    InfoRow("严重卡顿(>4 帧)", "${liveSample.bigJankCount}")
                    InfoRow("本轮帧数", "${liveSample.totalFrames}")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "「帧率」优先取系统级实测值（整机显示控制器输出，切到游戏等" +
                            "别的应用后依然有效）；系统级读不到时才退回本应用的逐帧回调。" +
                            "两种值同时可用时会并列显示，便于判断问题出在应用还是设备。" +
                            "注意系统级值是约 0.5 秒窗口的平均，界面静止时会明显偏低。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("历史记录")
                    Spacer(Modifier.height(6.dp))
                    if (sessions.isEmpty()) {
                        Text(
                            text = "还没有历史会话。开始一次记录并停止后，这里会按次留档——" +
                                "记录落 SQLite 持久化，杀进程也不会丢，可以录完整局游戏再回头分析。",
                            style = OsText.label,
                            color = c.textSecondary,
                        )
                    } else {
                        Text(
                            text = "共 ${sessions.size} 次记录 · 点任意一条查看它的曲线（持久化在数据库里，重启仍可回看）",
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (viewingId > 0L) {
                            LiquidNavTabs(
                                items = listOf("返回实时视图"),
                                selectedIndex = -1,
                                onSelect = { vm.closeFpsSession() },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        sessions.take(20).forEach { s ->
                            val active = s.id == viewingId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = fmtClock(s.timeBegin),
                                        style = OsText.label,
                                        color = if (active) c.primary else c.textPrimary,
                                    )
                                    Text(
                                        text = buildString {
                                            append("${s.sampleCount} 条")
                                            if (s.durationMs > 0) append(" · ${fmtElapsed(s.durationMs)}")
                                            s.packageName?.let { append(" · $it") }
                                            if (s.timeEnd <= 0) append(" · 未正常结束")
                                        },
                                        style = OsText.micro,
                                        color = c.textTertiary,
                                    )
                                }
                                Text(
                                    text = if (active) "查看中" else "查看",
                                    style = OsText.micro,
                                    color = c.primary,
                                    modifier = Modifier.clickable { vm.openFpsSession(s.id) },
                                )
                                Spacer(Modifier.width(14.dp))
                                // 「分析」把该会话的统计摘要渲染成 4:3 卡片，
                                // 但用的是**独立整页**（4:3 在整页宽度下才有
                                // 足够高度，内嵌时会被卡片内边距挤扁）。
                                // 统计由分析页通过 fpsSessionStats 单独查库获取，
                                // 不复用 viewingSamples（那是降采样后的窗口数据，
                                // 会算出偏小的方差与 5% Low）。
                                Text(
                                    text = "分析",
                                    style = OsText.micro,
                                    color = c.purple,
                                    modifier = Modifier.clickable {
                                        vm.openFpsAnalysis(s.id)
                                        onOpenAnalysis()
                                    },
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    text = "删除",
                                    style = OsText.micro,
                                    color = ChartColors.power,
                                    modifier = Modifier.clickable { vm.deleteFpsSession(s.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("系统级帧率")
                    Spacer(Modifier.height(6.dp))
                    // 这里以前读的是 `dumpsys gfxinfo <自己>` —— 名不副实：
                    // 那玩意是**本应用自身**的渲染统计，不是系统级帧率；而且
                    // Android 10+ 起非 root 应用读它会被拒，卡片长期只显示
                    // 「无法读取」。
                    //
                    // 现在改为把**真正的系统级采集通道**摊开给用户看：
                    // 当前读数、数据来源节点、命名空间。用户看帧率时最常问
                    // 「这数从哪来的、准不准」，把节点路径亮出来最有说服力。
                    val srcPath = remember(sysFps) { SysFpsDataSource.sourceLabel() }
                    InfoRow(
                        "当前读数",
                        if (sysFps > 0f) "%.1f FPS".format(sysFps) else "不可读",
                        valueColor = if (sysFps > 0f) ChartColors.fps else null,
                    )
                    InfoRow(
                        "数据来源",
                        if (srcPath != null) "显示控制器实测" else "无可用通道",
                    )
                    if (srcPath != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = srcPath,
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "「系统帧率」读的是显示控制器实际输出，与前台是哪个应用无关，" +
                            "切到游戏里依然有效。它是过去约 0.5 秒窗口的平均值，" +
                            "界面静止时会明显偏低，属正常现象。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }
    }
}
