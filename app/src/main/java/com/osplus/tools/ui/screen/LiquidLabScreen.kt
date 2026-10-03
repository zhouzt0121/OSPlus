package com.osplus.tools.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.osplus.tools.ui.liquid.LiquidBottomTab
import com.osplus.tools.ui.liquid.LiquidBottomTabs
import com.osplus.tools.ui.liquid.LiquidButton
import com.osplus.tools.ui.liquid.LiquidSlider
import com.osplus.tools.ui.liquid.LiquidToggle
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Icon

/**
 * Kyant 原版 Liquid Glass 组件实验室。
 *
 * 四个组件（LiquidButton / LiquidBottomTabs / LiquidSlider / LiquidToggle）全部是
 * Kyant0/AndroidLiquidGlass 的原版实现，未做任何逻辑改动，用来对照 OSPlus 自实现版本的手感。
 *
 * 玻璃折射必须有「有内容的背景」才看得出来，所以这里铺了渐变 + 色块，
 * 并把它们通过 layerBackdrop 记录成 backdrop 供组件采样。
 */
@Composable
fun LiquidLabScreen() {
    val c = osColors()
    val backdrop = rememberLayerBackdrop()

    var toggleOn by remember { mutableStateOf(true) }
    var sliderValue by remember { mutableFloatStateOf(0.62f) }
    var tabIndex by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {

        // ---------- 背景层：被 backdrop 记录，玻璃采样它做折射 ----------
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF1B2A4A),
                            Color(0xFF3D2B57),
                            Color(0xFF6B3350),
                            Color(0xFF8A4A3C),
                        )
                    )
                )
        ) {
            // 几块高对比色斑，让折射/模糊效果看得清楚
            Box(
                Modifier
                    .padding(start = 24.dp, top = 120.dp)
                    .size(180.dp)
                    .background(Color(0xFF00C2FF).copy(alpha = 0.55f))
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .size(140.dp)
                    .background(Color(0xFFFFD60A).copy(alpha = 0.5f))
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 60.dp, bottom = 220.dp)
                    .size(200.dp)
                    .background(Color(0xFF30D158).copy(alpha = 0.45f))
            )
        }

        // ---------- 内容层 ----------
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            LabText(
                text = "Kyant 原版组件",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            LabText(
                text = "LiquidButton / LiquidBottomTabs / LiquidSlider / LiquidToggle\n"
                    + "全部为原版实现，长按拖动可看形变与高光",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
            )

            // ---------- LiquidButton ----------
            LabBlock("LiquidButton") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidButton(onClick = {}, backdrop = backdrop) {
                        LabText("普通", color = Color.White, fontSize = 14.sp)
                    }
                    LiquidButton(
                        onClick = {},
                        backdrop = backdrop,
                        tint = Color(0xFF0088FF),
                    ) {
                        LabText("着色", color = Color.White, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidButton(
                        onClick = {},
                        backdrop = backdrop,
                        surfaceColor = Color.White.copy(alpha = 0.18f),
                    ) {
                        LabText("自定义表面", color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            // ---------- LiquidToggle ----------
            LabBlock("LiquidToggle") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiquidToggle(
                        selected = { toggleOn },
                        onSelect = { toggleOn = it },
                        backdrop = backdrop,
                    )
                    Spacer(Modifier.width(14.dp))
                    LabText(
                        text = if (toggleOn) "已开启" else "已关闭",
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
            }

            // ---------- LiquidSlider ----------
            LabBlock("LiquidSlider") {
                LiquidSlider(
                    value = { sliderValue },
                    onValueChange = { sliderValue = it },
                    valueRange = 0f..1f,
                    visibilityThreshold = 0.001f,
                    backdrop = backdrop,
                )
                Spacer(Modifier.height(8.dp))
                LabText(
                    text = "值 %.2f".format(sliderValue),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                )
            }

            // ---------- LiquidBottomTabs ----------
            LabBlock("LiquidBottomTabs") {
                LiquidBottomTabs(
                    selectedTabIndex = { tabIndex },
                    onTabSelected = { tabIndex = it },
                    backdrop = backdrop,
                    tabsCount = 4,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val tabs = listOf(
                        "概览" to Icons.Filled.Dashboard,
                        "性能" to Icons.Filled.Speed,
                        "帧率" to Icons.AutoMirrored.Filled.ShowChart,
                        "电源" to Icons.Filled.BatteryFull,
                    )
                    tabs.forEachIndexed { index, (label, icon) ->
                        LiquidBottomTab(onClick = { tabIndex = index }) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                            LabText(
                                text = label,
                                color = Color.White,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                LabText(
                    text = "当前选中：${listOf("概览", "性能", "帧率", "电源")[tabIndex]}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 实验室里每个组件的小标题 + 内容块。 */
@Composable
private fun LabBlock(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        LabText(
            text = title,
            color = Color.White.copy(alpha = 0.95f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * 演示页专用文本：直接用 Compose 的 BasicText + 显式 TextStyle，
 * 不依赖 Miuix 主题（本页背景是深色渐变，文字统一用白色系）。
 */
@Composable
private fun LabText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight? = null,
) {
    BasicText(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
        ),
    )
}
