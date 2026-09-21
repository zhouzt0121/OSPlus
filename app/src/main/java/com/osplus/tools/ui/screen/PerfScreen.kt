package com.osplus.tools.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.CpuDataSource
import com.osplus.tools.core.GpuDataSource
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.CoreBarsChart
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.MetricChartCard
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.SegmentedTabs
import com.osplus.tools.ui.components.UsageBar
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PerfScreen(vm: DeviceViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember { listOf("CPU", "GPU", "内存") }

    Column(Modifier.fillMaxSize()) {
        SegmentedTabs(
            tabs = tabs,
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        when (tab) {
            0 -> CpuTab(vm)
            1 -> GpuTab(vm)
            else -> MemoryTab(vm)
        }
    }
}

@Composable
private fun CpuTab(vm: DeviceViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val cpu by vm.cpu.collectAsStateWithLifecycle()
    val coreIndexes by vm.coreIndexes.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val latest = history.lastOrNull()

    var freqOptions by remember { mutableStateOf<List<Long>>(emptyList()) }
    var selectedCore by remember { mutableIntStateOf(0) }
    // 每次锁定/恢复后触发一次回读
    var freqApplyTick by remember { mutableIntStateOf(0) }
    // 每个核心所属的调频策略组（同组共享频率参数）
    var policyGroups by remember { mutableStateOf<Map<Int, List<Int>>>(emptyMap()) }

    LaunchedEffect(coreIndexes) {
        if (coreIndexes.isEmpty()) return@LaunchedEffect
        policyGroups = coreIndexes.associateWith { CpuDataSource.policyGroup(it) }
    }

    // 频率表按所选核心加载：大小簇的上限不同（本机小簇 3532MHz / 大簇 4320MHz），
    // 若固定用核心 0 的频率表，切到大核时上限会被压低。
    LaunchedEffect(coreIndexes, selectedCore) {
        freqOptions = CpuDataSource.availableFrequencies(selectedCore)
    }

    // 回读该簇当前真正生效的上下限（用于判断写入是否被内核接受）
    var effectiveLimits by remember { mutableStateOf(-1L to -1L) }
    LaunchedEffect(selectedCore, cpu, freqApplyTick) {
        effectiveLimits = CpuDataSource.readLimits(selectedCore)
    }
    val applyResult by vm.freqApplyState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 2.dp, bottom = 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(title = "CPU 负载 · 5 秒窗口") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    MetricChartCard(
                        title = "总占用",
                        values = history.map { it.cpuLoad },
                        maxValue = 100f,
                        color = ChartColors.cpu,
                        unit = "%",
                    )
                    Spacer(Modifier.height(10.dp))
                    CoreBarsChart(
                        coreIndexes = coreIndexes,
                        loads = latest?.coreLoads ?: emptyList(),
                        freqs = latest?.coreFreqs ?: emptyList(),
                    )
                }
            }
        }

        item {
            SectionCard(title = "核心簇频率") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    if (cpu.clusters.isEmpty()) {
                        Text(
                            text = "读取中…",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                    cpu.clusters.forEach { cluster ->
                        InfoRow(
                            label = cluster.name,
                            value = "核心 ${cluster.cores.joinToString(",")}",
                        )
                        InfoRow(
                            label = "当前频率",
                            value = "${mhz(cluster.curKhz)} MHz",
                            emphasis = true,
                        )
                        InfoRow(
                            label = "范围",
                            value = "${mhz(cluster.minKhz)} ~ ${mhz(cluster.maxKhz)} MHz",
                        )
                        if (cluster.maxKhz > 0) {
                            UsageBar(
                                fraction = if (cluster.maxKhz > 0) {
                                    cluster.curKhz.toFloat() / cluster.maxKhz
                                } else 0f,
                                color = ChartColors.cpu,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }

        item {
            SectionCard(title = "调速器（Governor）") {
                Column(Modifier.padding(vertical = 5.dp)) {
                    if (!rootAvailable) {
                        NoticeBanner("修改调速器需要 Root 权限")
                        Spacer(Modifier.height(8.dp))
                    }
                    val governors = cpu.governors
                    if (governors.isEmpty()) {
                        Text(
                            text = "未读取到可用调速器",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val currentGov = cpu.cores.firstOrNull()?.governor
                            governors.forEach { gov ->
                                Button(
                                    onClick = {
                                        val core = coreIndexes.firstOrNull() ?: 0
                                        vm.setCpuGovernor(core, gov)
                                    },
                                    enabled = rootAvailable,
                                ) {
                                    Text(
                                        text = if (gov == currentGov) "✓ $gov" else gov,
                                        style = MiuixTheme.textStyles.footnote1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "核心频率控制") {
                Column(Modifier.padding(vertical = 5.dp)) {
                    Text(
                        text = "选择要调节的核心",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        coreIndexes.forEach { c ->
                            Button(
                                onClick = { selectedCore = c },
                                enabled = true,
                            ) {
                                Text(
                                    text = if (c == selectedCore) "\u2713 \u6838\u5fc3 $c" else "\u6838\u5fc3 $c",
                                    style = MiuixTheme.textStyles.footnote1,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    val idx = coreIndexes.indexOf(selectedCore).coerceAtLeast(0)
                    val curKhz = latest?.coreFreqs?.getOrElse(idx) { -1L } ?: -1L
                    val curLoad = latest?.coreLoads?.getOrElse(idx) { 0f } ?: 0f
                    val hw = remember(selectedCore) { CpuDataSource.hardwareRange(selectedCore) }
                    val group = policyGroups[selectedCore]

                    InfoRow("当前频率", "${mhz(curKhz)} MHz", emphasis = true)
                    InfoRow("当前占用", "%.0f%%".format(curLoad))
                    InfoRow("硬件范围", "${mhz(hw.first)} ~ ${mhz(hw.second)} MHz")
                    InfoRow(
                        "调频策略组",
                        group?.let { g ->
                            if (g.size > 1) "核心 ${g.joinToString(", ")}（共 ${g.size} 核）"
                            else "仅该核心"
                        } ?: "读取中…",
                    )

                    if (group != null && group.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner(
                            text = "该 SoC 以簇为单位管理 CPU 频率：核心 " +
                                group.joinToString(", ") +
                                " 共用同一组调频参数（内核只暴露 policy 级节点，不存在可写的单核频率节点）。" +
                                "因此调节任意一个核心，实际会同时作用于本组全部核心。",
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    InfoRow(
                        "当前生效上限",
                        if (effectiveLimits.second > 0) "${effectiveLimits.second / 1000} MHz" else "-",
                    )
                    InfoRow(
                        "当前生效下限",
                        if (effectiveLimits.first > 0) "${effectiveLimits.first / 1000} MHz" else "-",
                    )

                    applyResult?.let { r ->
                        Spacer(Modifier.height(8.dp))
                        if (r.restored && r.appliedMaxKhz > 0L) {
                            NoticeBanner(
                                text = "已恢复自动调频：" +
                                    "${r.appliedMinKhz / 1000} ~ ${r.appliedMaxKhz / 1000} MHz",
                                accent = ChartColors.gpu,
                            )
                        } else if (r.accepted) {
                            NoticeBanner(
                                text = "已生效：本簇锁定在 ${r.appliedMaxKhz / 1000} MHz",
                                accent = ChartColors.gpu,
                            )
                        } else {
                            NoticeBanner(
                                text = "未生效：请求 ${r.requestedKhz / 1000} MHz，" +
                                    "但内核回读仍为 ${r.appliedMaxKhz / 1000} MHz。" +
                                    "该簇频率被内核或厂商策略接管，cpufreq 写入会被静默忽略。",
                                accent = ChartColors.power,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    if (freqOptions.isEmpty()) {
                        Text(
                            text = "该设备未暴露可调频率列表",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    } else {
                        val lastIndex = freqOptions.lastIndex
                        val refKhz = effectiveLimits.second.takeIf { it > 0 } ?: freqOptions.last()
                        var stepIndex by remember(selectedCore, freqOptions) {
                            mutableIntStateOf(
                                freqOptions.indexOfLast { it <= refKhz }.coerceIn(0, lastIndex)
                            )
                        }
                        val target = freqOptions[stepIndex.coerceIn(0, lastIndex)]

                        Text(
                            text = "频率挡位：" + (target / 1000) + " MHz",
                            style = MiuixTheme.textStyles.title4,
                            color = MiuixTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "第 " + (stepIndex + 1) + " / " + freqOptions.size +
                                " 挡（" + (freqOptions.first() / 1000) + " ~ " +
                                (freqOptions.last() / 1000) + " MHz）",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        Slider(
                            value = stepIndex.toFloat(),
                            onValueChange = { stepIndex = it.toInt().coerceIn(0, lastIndex) },
                            valueRange = 0f..lastIndex.toFloat(),
                            steps = (lastIndex - 1).coerceAtLeast(0),
                            enabled = rootAvailable,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    vm.lockCpuFrequency(selectedCore, target)
                                    freqApplyTick++
                                },
                                enabled = rootAvailable,
                            ) { Text("锁定此挡位") }
                            Button(
                                onClick = {
                                    vm.restoreCpuAuto(selectedCore)
                                    freqApplyTick++
                                },
                                enabled = rootAvailable,
                            ) { Text("恢复自动") }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "锁定会把该簇上下限同时设为所选挡位，CPU 稳定运行在该频率；" +
                                "恢复自动则放开为硬件允许的最低~最高。写入后会回读校验，未生效会明确提示。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GpuTab(vm: DeviceViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val gpu by vm.gpu.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val gpuApplyState by vm.gpuApplyState.collectAsStateWithLifecycle()
    val gpuFreqState by vm.gpuFreqState.collectAsStateWithLifecycle()
    var gpuGovernors by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { gpuGovernors = GpuDataSource.availableGovernors() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 2.dp, bottom = 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(title = "GPU · 5 秒窗口") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    MetricChartCard(
                        title = "频率",
                        values = history.map { if (it.gpuMhz > 0) it.gpuMhz.toFloat() else 0f },
                        maxValue = autoMax(history.map { it.gpuMhz.toFloat() }, gpu.maxMhz.toFloat(), 800f),
                        color = ChartColors.gpu,
                        unit = "MHz",
                    )
                    Spacer(Modifier.height(10.dp))
                    MetricChartCard(
                        title = "负载",
                        values = history.map { if (it.gpuLoad >= 0) it.gpuLoad.toFloat() else 0f },
                        maxValue = 100f,
                        color = ChartColors.cpu,
                        unit = "%",
                    )
                }
            }
        }

        item {
            SectionCard(title = "GPU 详情") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    InfoRow("型号", gpu.name.ifBlank { "-" })
                    InfoRow("当前频率", gpuFreq(gpu.curMhz), emphasis = true)
                    InfoRow("最小 / 最大", "${gpuFreq(gpu.minMhz)} / ${gpuFreq(gpu.maxMhz)}")
                    InfoRow("调速器", gpu.governor.ifBlank { "-" })
                    InfoRow("负载", if (gpu.loadPercent >= 0) "${gpu.loadPercent}%" else "不可读")
                    if (gpu.availableFreqs.isNotEmpty()) {
                        InfoRow(
                            "可用频率",
                            gpu.availableFreqs.joinToString(", ") { "${it / 1_000_000}" } + " MHz",
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "GPU 调速器") {
                Column(Modifier.padding(vertical = 5.dp)) {
                    if (!rootAvailable) {
                        NoticeBanner("修改 GPU 调速器需要 Root 权限")
                        Spacer(Modifier.height(8.dp))
                    }
                    val governors = gpuGovernors
                        .ifEmpty { listOfNotNull(gpu.governor.takeIf { it.isNotBlank() }) }
                    if (gpuGovernors.isEmpty() && gpu.governor.isBlank()) {
                        NoticeBanner("无法读取可用调速器列表")
                        Spacer(Modifier.height(8.dp))
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        governors.forEach { gov ->
                            Button(
                                onClick = { vm.setGpuGovernor(gov) },
                                enabled = rootAvailable,
                            ) {
                                Text(
                                    text = if (gov == gpu.governor) "✓ " + gov else gov,
                                    style = MiuixTheme.textStyles.footnote1,
                                )
                            }
                        }
                    }
                    gpuApplyState?.let { (req, applied) ->
                        Spacer(Modifier.height(8.dp))
                        if (applied.equals(req, ignoreCase = true)) {
                            NoticeBanner(
                                text = "已生效：调速器已切换为 $applied",
                                accent = ChartColors.gpu,
                            )
                        } else {
                            NoticeBanner(
                                text = "未生效：请求 $req，但内核回读仍为 ${applied.ifBlank { "空" }}。" +
                                    "该节点被内核或厂商策略接管，写入会被静默忽略。",
                                accent = ChartColors.power,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "当前：" + gpu.governor.ifBlank { "不可读" } +
                            "。不同内核支持的调速器不同，无效值不会生效。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "GPU 频率上限") {
                Column(Modifier.padding(vertical = 5.dp)) {
                    if (!rootAvailable) {
                        NoticeBanner("修改 GPU 频率需要 Root 权限")
                        Spacer(Modifier.height(8.dp))
                    }
                    if (gpu.availableFreqs.isEmpty()) {
                        Text(
                            text = "该设备未暴露 GPU 可调频率列表",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    } else {
                        var value by remember(gpu.availableFreqs) {
                            mutableStateOf(gpu.availableFreqs.last().toFloat())
                        }
                        Text(
                            // 此处 value 取自 availableFreqs，单位是 Hz
                            text = "上限：" + (value / 1_000_000).toInt() + " MHz",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onBackground,
                        )
                        Slider(
                            value = value,
                            onValueChange = { value = it },
                            valueRange = gpu.availableFreqs.first().toFloat()..gpu.availableFreqs.last().toFloat(),
                            onValueChangeFinished = { vm.setGpuMaxFreq(value.toLong()) },
                            enabled = rootAvailable,
                        )
                        gpuFreqState?.let { (req, applied) ->
                            Spacer(Modifier.height(8.dp))
                            if (applied > 0 && kotlin.math.abs(applied - req) <= req / 10) {
                                NoticeBanner(
                                    text = "已生效：GPU 上限 " + (applied / 1_000_000) + " MHz",
                                    accent = ChartColors.gpu,
                                )
                            } else {
                                NoticeBanner(
                                    text = "未生效：请求 " + (req / 1_000_000) +
                                        " MHz，内核回读 " + (applied / 1_000_000) +
                                        " MHz。该节点被内核或厂商策略接管。",
                                    accent = ChartColors.power,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryTab(vm: DeviceViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val mem by vm.mem.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val swapApplyState by vm.swapApplyState.collectAsStateWithLifecycle()
    val swapPercent = if (mem.swapTotalKb > 0) {
        mem.swapUsedKb * 100f / mem.swapTotalKb
    } else 0f

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 2.dp, bottom = 104.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(title = "内存 · 5 秒窗口") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    MetricChartCard(
                        title = "占用率",
                        values = history.map { it.memUsedPercent },
                        maxValue = 100f,
                        color = ChartColors.mem,
                        unit = "%",
                    )
                    Spacer(Modifier.height(10.dp))
                    MetricChartCard(
                        title = "SWAP 占用",
                        values = history.map { swapPercent },
                        maxValue = 100f,
                        color = ChartColors.gpu,
                        unit = "%",
                        subtitle = "${fmtGb(mem.swapUsedKb)} / ${fmtGb(mem.swapTotalKb)}",
                    )
                }
            }
        }

        item {
            SectionCard(title = "内存明细") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    InfoRow("总容量", fmtGb(mem.totalKb), emphasis = true)
                    InfoRow("已用", "${fmtGb(mem.usedKb)}（${percent(mem.usedKb, mem.totalKb)}）")
                    InfoRow("可用", fmtGb(mem.availKb))
                    InfoRow("空闲", fmtGb(mem.freeKb))
                    InfoRow("缓存", fmtGb(mem.cachedKb))
                    InfoRow("缓冲区", fmtGb(mem.buffersKb))
                }
            }
        }

        item {
            SectionCard(title = "ZRAM 容量调整") {
                Column(Modifier.padding(vertical = 5.dp)) {
                    NoticeBanner(
                        text = "调整会重建 zram 交换分区，可能导致正在使用交换区的应用短暂卡顿，请谨慎操作。",
                    )
                    Spacer(Modifier.height(10.dp))
                    var sizeGb by remember { mutableStateOf(4f) }
                    Text(
                        text = "目标容量：%.1f GB".format(sizeGb),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Slider(
                        value = sizeGb,
                        onValueChange = { sizeGb = it },
                        valueRange = 1f..8f,
                        enabled = rootAvailable,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.resizeZram((sizeGb * 1024f * 1024f).toLong()) },
                            enabled = rootAvailable,
                        ) { Text("应用并重建") }
                        Button(
                            onClick = { sizeGb = (mem.zramTotalKb / 1024f / 1024f).coerceIn(1f, 8f) },
                        ) { Text("取当前值") }
                    }
                }
            }
        }

        item {
            SectionCard(title = "SWAP / ZRAM") {
                Column(Modifier.padding(vertical = 3.dp)) {
                    InfoRow("SWAP 总量", fmtGb(mem.swapTotalKb))
                    InfoRow("SWAP 已用", "${fmtGb(mem.swapUsedKb)}（${"%.0f".format(swapPercent)}%）")
                    InfoRow("ZRAM 设备", mem.zramDevices.joinToString(", ").ifBlank { "未启用" })
                    InfoRow("ZRAM 容量", fmtGb(mem.zramTotalKb))
                    InfoRow("ZRAM 已用", fmtGb(mem.zramUsedKb))
                    InfoRow("原始数据量", fmtGb(mem.zramOrigKb))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "swappiness",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    var swappiness by remember { mutableStateOf(60f) }
                    Slider(
                        value = swappiness,
                        onValueChange = { swappiness = it },
                        valueRange = 0f..200f,
                        onValueChangeFinished = { vm.setSwappiness(swappiness.toInt()) },
                        enabled = rootAvailable,
                    )
                    swapApplyState?.let { (req, applied) ->
                        Spacer(Modifier.height(8.dp))
                        if (applied == req) {
                            NoticeBanner(
                                text = "已生效：swappiness = $applied",
                                accent = ChartColors.gpu,
                            )
                        } else {
                            NoticeBanner(
                                text = "未生效：请求 $req，内核回读 ${if (applied >= 0) applied.toString() else "不可读"}。" +
                                    "该节点被内核参数或厂商策略接管。",
                                accent = ChartColors.power,
                            )
                        }
                    }
                    Text(
                        text = "当前设置 ${swappiness.toInt()}（0~200，越高越倾向使用 SWAP）",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }
    }
}

/**
 * GPU 频率已是 MHz，直接输出，不能再套用 CPU 的 kHz 格式化函数。
 */
private fun gpuFreq(mhz: Long): String = if (mhz > 0) "$mhz MHz" else "-"

private fun mhz(khz: Long): String = if (khz > 0) (khz / 1000).toString() else "-"

private fun percent(part: Long, total: Long): String =
    if (total > 0) "%.0f%%".format(part * 100f / total) else "-"
