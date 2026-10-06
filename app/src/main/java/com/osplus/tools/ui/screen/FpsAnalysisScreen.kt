package com.osplus.tools.ui.screen

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osplus.tools.core.FpsSessionStats
import com.osplus.tools.ui.components.ActionButton
import com.osplus.tools.ui.components.CardSectionLabel
import com.osplus.tools.ui.components.ChartColors
import com.osplus.tools.ui.components.InfoRow
import com.osplus.tools.ui.components.LiquidNavTabs
import com.osplus.tools.ui.components.NoticeBanner
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.bottomBarContentPadding
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import com.osplus.tools.vm.DeviceViewModel
import top.yukonga.miuix.kmp.basic.Text

/**
 * 录制记录分析（独立整页）。
 *
 * ## 为什么是整页而不是内嵌卡片
 *
 * 之前分析卡塞在帧率页的会话列表下方，宽度受卡片内边距挤压，
 * 4:3 的卡片只能铺到屏幕宽度的 ~80%，两组四列统计挤在一起。改为独立页后：
 * 卡片可以铺满页面宽度，4:3 的纵横比下高度随之充足，数字与标签的层级
 * 才真正立得起来；同时「看分析」是一个明确的查看动作，本就该有自己的页面，
 * 而不是和会话列表抢同一块滚动区域。
 *
 * ## 内容
 *
 * - 顶栏：日期时间 / 应用图标 + 应用名 / crop 尺寸
 * - 组 1：MAX / MIN / AVG / VARIANCE（FPS）
 * - 组 2：≥60FPS / 5% Low / MAX / AVG（Smoothness · FPS · Temperature · Power）
 * - 操作：保存为图片 / 查看原始曲线（回到帧率页的会话视图）
 */
@Composable
fun FpsAnalysisScreen(vm: DeviceViewModel) {
    val c = osColors()
    val context = LocalContext.current

    val analysisId by vm.analysisSessionId.collectAsStateWithLifecycle()
    val stats by vm.analysisStats.collectAsStateWithLifecycle()
    val sessions by vm.fpsSessions.collectAsStateWithLifecycle()
    val icons by vm.appIcons.collectAsStateWithLifecycle()
    val cardPath by vm.lastCardPath.collectAsStateWithLifecycle()

    val session = sessions.firstOrNull { it.id == analysisId }

    // 应用图标：会话带有包名时懒加载（同 PerfScreen 的做法）
    LaunchedEffect(session?.packageName) {
        session?.packageName?.let { vm.loadAppIcons(listOf(it)) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (session == null || stats == null) {
            item {
                SectionCard {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        CardSectionLabel("录制记录分析")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (analysisId <= 0L) {
                                "没有选中的记录。请回到「帧率」页，在历史会话里点「分析」。"
                            } else {
                                "正在读取统计…"
                            },
                            style = OsText.label,
                            color = c.textSecondary,
                        )
                    }
                }
            }
            return@LazyColumn
        }

        val s = stats!!
        val pkg = session.packageName
        val appName = vm.appLabel(pkg)

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    CardSectionLabel("分析卡片")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "固定 4:3 版式，可保存为图片分享。统计口径：" +
                            "VARIANCE 为帧率总体方差；5% Low 取升序第 5 百分位；" +
                            "≥60FPS 为流畅帧占比；Temperature/Power 为全段均值。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                    Spacer(Modifier.height(12.dp))
                    // 整页宽度下，卡片铺满；4:3 让它自然获得足够高度
                    FpsAnalysisCard(
                        session = session,
                        stats = s,
                        cropLabel = "crop: ${screenCropLabel(context)}",
                        icon = pkg?.let { icons[it] },
                        appName = appName,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    ActionButton(
                        text = "保存为图片",
                        onClick = { vm.exportFpsCard(session, s, screenCropLabel(context)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    cardPath?.let {
                        Spacer(Modifier.height(8.dp))
                        NoticeBanner(text = "分析卡已导出到 $it", accent = ChartColors.fps)
                    }
                }
            }
        }

        // ---- 明细：把卡片上的汇总值摊开，便于核对 ----
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 3.dp)) {
                    CardSectionLabel("统计明细")
                    Spacer(Modifier.height(6.dp))
                    InfoRow("帧率最大值", "%.1f FPS".format(s.max))
                    InfoRow("帧率最小值", "%.1f FPS".format(s.min))
                    InfoRow("帧率平均值", "%.1f FPS".format(s.avg))
                    InfoRow("帧率方差", "%.1f".format(s.variance))
                    InfoRow("≥60FPS 占比", "%.2f%%".format(s.smoothRatio))
                    InfoRow("5% Low", "%.1f FPS".format(s.fivePercentLow))
                    InfoRow("平均温度", "%.1f ℃".format(s.avgTempC))
                    InfoRow("平均功耗", "%.0f mW".format(s.avgPowerMw))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "样本 ${s.count} 条" +
                            if (session.durationMs > 0) {
                                " · 时长 ${fmtDur(session.durationMs)}"
                            } else {
                                " · 未正常结束"
                            },
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }
    }
}

/** 毫秒 → `mm:ss` / `h:mm:ss`（本页独立实现，避免跨文件可见性问题） */
private fun fmtDur(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
