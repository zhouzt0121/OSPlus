package com.osplus.tools.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.PerfSchedDataSource
import com.osplus.tools.ui.components.ActionButton
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.Hairline
import com.osplus.tools.ui.components.LiquidNavTabs
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.bottomBarContentPadding
import com.osplus.tools.ui.components.pressable
import androidx.compose.foundation.shape.RoundedCornerShape
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.basic.Text

/**
 * 调优（一级页）：所有**会改变设备行为**的控件集中于此。
 *
 * 职责边界（2.9.0 信息架构重构时划定）：这一页只做「改参数」。
 * 实时数据全部留在「监测」页，两者不互相穿透。
 *
 * 为什么单独成页：这些操作会立刻写内核节点、改变整机功耗与温度，
 * 有的还不可逆（频率上限写错会锁死性能）。把四个入口从一屏长趋势图下面
 * 挪到独立页面，换来两个好处：
 *
 * 1. **意图纯净** —— 用户在监测页滑动时不可能误触到写节点的控件；
 * 2. **风险可承载** —— 这一页顶部就能放一条常驻的权限/风险提示，
 *    而不是散落在四个详情页里各说各话。
 *
 * 每行统一结构（与详情页内部保持一致）：
 * 当前值 → 可调范围 → 进入修改 → 生效状态。
 * 一级页只到「当前值 + 能否调」，真正的取值范围与控件留给详情页——
 * 这样一级页不会因机型差异而长度不一。
 */
@Composable
fun TuningScreen(
    vm: DeviceViewModel,
    onOpen: (OverviewDetail) -> Unit,
    onOpenPrivilege: () -> Unit = {},
) {
    val cpu by vm.cpu.collectAsStateWithLifecycle()
    val gpu by vm.gpu.collectAsStateWithLifecycle()
    val mem by vm.mem.collectAsStateWithLifecycle()
    val rootAvailable by vm.rootAvailable.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val uperf by vm.uperfState.collectAsStateWithLifecycle()
    val c = osColors()

    // 「所有可写节点」与「实际能写」不是一回事：有 root 但单个节点被 SELinux 拒绝
    // 的情况真实存在（本项目在调频节点上踩过）。用能力表而非 rootAvailable 判据，
    // 与概览页的健康结论保持同一套真值来源。
    val canTune = capabilities.available

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- 权限与风险提示（常驻置顶）----------
        item {
            if (!canTune) {
                NoticeBanner(
                    "未启用提权通道：CPU / GPU 频率、ZRAM 容量、性能调度等都需要 " +
                        "root 身份写入内核节点。可在设置 → 提权管理中完成授权。",
                    accent = ChartColors.power,
                )
            } else {
                SectionCard {
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Text(
                            text = "提权通道正常",
                            style = OsText.value,
                            color = c.textPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "以下操作会立即写入内核节点，改变整机功耗与温度表现。" +
                                "所有可调项都支持「取当前值」回读确认；改动前建议先记下原值。",
                            style = OsText.micro,
                            color = c.textTertiary,
                        )
                    }
                }
            }
        }

        // ---------- CPU ----------
        item {
            TuningGroupCard(
                title = "CPU",
                accent = ChartColors.cpu,
                summary = cpu.soc.ifBlank { "读取中…" },
                rows = listOf(
                    TuningRowData(
                        label = "频率与调速器",
                        // 当前值摘要：调速器名 + 最高簇频率，一眼看出跑在什么档位
                        current = buildString {
                            append(cpu.governors.firstOrNull { it.isNotBlank() } ?: "-")
                            cpu.clusters.maxOfOrNull { it.curKhz }?.let {
                                append(" · 最高 ${it / 1000} MHz")
                            }
                        },
                        range = "${cpu.coreCount} 核心 · ${cpu.clusters.size} 簇",
                        // 不再跟 canTune 置灰（见 TuningRowData.needsRoot 注释）：
                        // 无 root 时用户更需要进详情页看清「能调什么 / 为什么调不了 / 去哪开权限」。
                        needsRoot = true,
                        onClick = { onOpen(OverviewDetail.Cpu) },
                    ),
                ),
            )
        }

        // ---------- GPU ----------
        item {
            TuningGroupCard(
                title = "GPU",
                accent = ChartColors.gpu,
                summary = gpu.name.ifBlank { "读取中…" },
                rows = listOf(
                    TuningRowData(
                        label = "频率与调速器",
                        current = buildString {
                            append(gpu.governor.ifBlank { "-" })
                            if (gpu.curMhz > 0) append(" · ${gpu.curMhz} MHz")
                        },
                        range = if (gpu.maxMhz > 0) "上限 ${gpu.maxMhz} MHz" else "-",
                        // 不再跟 canTune 置灰（见 TuningRowData.needsRoot 注释）：
                        // 无 root 时用户更需要进详情页看清「能调什么 / 为什么调不了 / 去哪开权限」。
                        needsRoot = true,
                        onClick = { onOpen(OverviewDetail.Gpu) },
                    ),
                ),
            )
        }

        // ---------- 内存与 ZRAM ----------
        item {
            TuningGroupCard(
                title = "内存与 ZRAM",
                accent = ChartColors.mem,
                summary = if (mem.totalKb > 0) "物理内存 ${fmtGb(mem.totalKb)}" else "读取中…",
                rows = listOf(
                    TuningRowData(
                        label = "ZRAM 容量",
                        current = if (mem.zramTotalKb > 0) {
                            val used = mem.zramUsedKb.takeIf { it > 0L } ?: mem.zramOrigKb
                            "已用 ${fmtGb(used)} / ${fmtGb(mem.zramTotalKb)}"
                        } else {
                            "未启用"
                        },
                        range = if (mem.swapTotalKb > 0) "SWAP ${fmtGb(mem.swapTotalKb)}" else "无 SWAP",
                        // 不再跟 canTune 置灰（见 TuningRowData.needsRoot 注释）：
                        // 无 root 时用户更需要进详情页看清「能调什么 / 为什么调不了 / 去哪开权限」。
                        needsRoot = true,
                        onClick = { onOpen(OverviewDetail.Memory) },
                    ),
                ),
            )
        }

        // ---------- 性能调度 ----------
        item {
            TuningGroupCard(
                title = "性能调度",
                accent = ChartColors.fps,
                summary = when {
                    !uperf.installed -> "未安装 Uperf"
                    uperf.mode.isBlank() -> "Uperf 已安装"
                    else -> "Uperf · ${PerfSchedDataSource.uperfModeLabel(uperf.mode)}"
                },
                rows = listOf(
                    TuningRowData(
                        label = "电源档位与分应用策略",
                        current = if (uperf.installed) {
                            uperf.mode.ifBlank { "未选择档位" }
                        } else {
                            "不可用"
                        },
                        range = "分应用覆盖 · A-SOUL 优化",
                        // 不跟 `uperf.installed` 置灰：原写法在未装 Uperf 时把整行变成死行，
                        // 用户看得见「性能调度」却点不动，也不知道该装什么、从哪装（被报为「按钮无效」）。
                        // 详情页自身会按 `uperf.installed` 决定展示控件还是安装指引。
                        // 性能调度是唯一不需要 root 的调优项（Uperf 用自己的服务写 sched 参数），
                        // 所以 needsRoot = false，只走「装没装」这条判据。
                        needsRoot = false,
                        onClick = { onOpen(OverviewDetail.Sched) },
                    ),
                ),
            )
        }

        // ---------- 收尾说明 ----------
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text(
                        text = "关于可调项",
                        style = OsText.value,
                        color = c.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "· 可选的取值范围来自内核实际暴露的节点，机型之间差异很大；\n" +
                            "· 写不进去通常是 SELinux 拒绝而非实现问题，详情页会给出回读校验结果；\n" +
                            "· 每个详情页都有「取当前值」用于确认改动是否真的生效。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    // 之前这里是 `onSelect = { /* 空 */ }` + `enabled = false`：
                    // 一个明摆着写出来但点了什么都不做的按钮，是纯粹的界面负债。
                    // 现在直连提权管理页（根布局 pushRoute）。
                    ActionButton(
                        text = "查看提权管理",
                        onClick = onOpenPrivilege,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 一个部件分组卡：标题 + 部件名 + 若干调优行 */
private data class TuningRowData(
    val label: String,
    /** 当前值 —— 这一列是提案要求统一结构的核心：进页面先看到「现在是什么」 */
    val current: String,
    /** 可调范围 / 相关规格 */
    val range: String,
    /**
     * 该行是否需要 root 才能**实际写入**。
     *
     * 注意与「行能不能点」是两件事：需要 root 也**允许点进详情页**
     * （用户得先知道为什么调不了、去哪开权限），角标只负责如实告知前提。
     */
    val needsRoot: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun TuningGroupCard(
    title: String,
    accent: androidx.compose.ui.graphics.Color,
    summary: String,
    rows: List<TuningRowData>,
) {
    val c = osColors()
    SectionCard {
        Column(Modifier.padding(vertical = 2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 左侧色条：一眼区分四个部件分组，比四个不同颜色的标题字清楚
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = OsText.value,
                    color = c.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = summary,
                    style = OsText.micro,
                    color = c.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            rows.forEachIndexed { i, r ->
                if (i > 0) Hairline(verticalPadding = 6.dp)
                TuningRowView(r)
            }
        }
    }
}

/**
 * 统一结构的调优行：左标签、右侧「当前值 / 可调范围」两行、最右箭头。
 *
 * 提案要求「每个调优页统一结构：当前值 → 可选范围 → 修改控件 → 应用/恢复 → 生效状态」。
 * 一级页承担前三项的前半段（当前值 + 范围 + 入口），
 * 控件与应用/回读放在详情页——一级页放控件会因机型差异导致页面长度不可控。
 */
@Composable
private fun TuningRowView(r: TuningRowData) {
    val c = osColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // 行恒可点：`needsRoot` 只影响右侧角标，不影响可达性。
            // 需要 root 的行同样能进详情页看清前提与开通路径。
            .pressable(onClick = r.onClick)
            .padding(vertical = 9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = r.label,
                style = OsText.value,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            // 角标只如实告知前提，不阻止进入
            if (r.needsRoot) {
                Text(
                    text = "需 Root",
                    style = OsText.caption,
                    color = c.textTertiary,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = "›",
                style = OsText.caption,
                color = c.textTertiary,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = r.current,
            style = OsText.micro,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = r.range,
            style = OsText.micro,
            color = c.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
