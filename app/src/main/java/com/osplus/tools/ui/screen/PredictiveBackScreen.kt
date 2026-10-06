package com.osplus.tools.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.osplus.tools.core.Preferences
import com.osplus.tools.ui.components.ActionButton
import com.osplus.tools.ui.components.CardSectionLabel
import com.osplus.tools.ui.components.LiquidNavTabs
import com.osplus.tools.ui.components.NumberInputField
import com.osplus.tools.ui.components.SectionCard
import com.osplus.tools.ui.components.bottomBarContentPadding
import com.osplus.tools.ui.theme.OsText
import com.osplus.tools.ui.theme.osColors
import top.yukonga.miuix.kmp.basic.Text

/**
 * 预测性返回动画参数。
 *
 * 这些数值没有客观最优解——屏幕尺寸、刷新率、以及个人对跟手的偏好都会影响观感。
 * 与其在代码里拍一个常量，不如开放出来让用户按自己的手感调。
 *
 * 改动**不需要重启**：参数在手势开始时重读，返回本页滑一次就是新值。
 *
 * 每项都用 [NumberInputField] 收数值 + 一段说明，说明里写清「调大/调小分别是什么观感」，
 * 因为纯数字（比如「位移 0.32」）脱离上下文毫无意义。
 */
@Composable
fun PredictiveBackScreen() {
    val context = LocalContext.current
    val c = osColors()

    // 用文本状态存输入框内容：直接绑 Float 会让「输入 0.」这类中间态被吞掉，
    // 用户打到一半就跳变。保存时再解析，解析失败则保持不动。
    var translationText by remember {
        mutableStateOf(fmt(Preferences.backTranslationRatio(context)))
    }
    var scaleText by remember {
        mutableStateOf(fmt(Preferences.backScaleAmount(context)))
    }
    var cornerText by remember {
        mutableStateOf(fmtInt(Preferences.backCornerRadiusDp(context)))
    }
    var settleText by remember {
        mutableStateOf(Preferences.backSettleDurationMs(context).toString())
    }
    var dimText by remember {
        mutableStateOf(fmt(Preferences.backDimAmount(context)))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = bottomBarContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard {
                Column(Modifier.padding(vertical = 5.dp)) {
                    CardSectionLabel("跟手位移")
                    Spacer(Modifier.height(8.dp))
                    NumberInputField(
                        label = "位移比例（默认 0.32）",
                        value = translationText,
                        onValueChange = { t ->
                            translationText = t
                            t.toFloatOrNull()?.let { v ->
                                Preferences.setBackTranslationRatio(context, v)
                            }
                        },
                        numeric = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "手势走满时覆盖层右移的距离，按屏幕宽度取比例。" +
                            "调大：底层露出更多、更「跟手」；调小：位移收敛、观感更克制。" +
                            "建议 0.2~0.45。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )

                    Spacer(Modifier.height(14.dp))
                    CardSectionLabel("缩放")
                    Spacer(Modifier.height(8.dp))
                    NumberInputField(
                        label = "缩小量（默认 0.06）",
                        value = scaleText,
                        onValueChange = { t ->
                            scaleText = t
                            t.toFloatOrNull()?.let { v ->
                                Preferences.setBackScaleAmount(context, v)
                            }
                        },
                        numeric = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "覆盖层随进度缩小的幅度（0.06 表示最终缩到 94%）。" +
                            "调大：后退层次更强；调小：偏「平移」。建议 0~0.12，超过 0.15 会显得夸张。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )

                    Spacer(Modifier.height(14.dp))
                    CardSectionLabel("圆角")
                    Spacer(Modifier.height(8.dp))
                    NumberInputField(
                        label = "圆角上限 / dp（默认 28）",
                        value = cornerText,
                        onValueChange = { t ->
                            cornerText = t
                            t.toFloatOrNull()?.let { Preferences.setBackCornerRadiusDp(context, it) }
                        },
                        numeric = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "手势走满时覆盖层四角的圆角半径。静止时恒为 0（不切角），" +
                            "只有真的开始推才长出来。建议 16~40。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 5.dp)) {
                    CardSectionLabel("取消回弹")
                    Spacer(Modifier.height(8.dp))
                    NumberInputField(
                        label = "回弹耗时 / 毫秒（默认 200）",
                        value = settleText,
                        onValueChange = { t ->
                            settleText = t
                            t.toIntOrNull()?.let { Preferences.setBackSettleDurationMs(context, it) }
                        },
                        numeric = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "手指拖回原位松手后，覆盖层弹回原位的时长。" +
                            "调大：回弹更柔和但在感上偏慢；调小：干脆利落但可能显生硬。" +
                            "建议 120~350。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )

                    Spacer(Modifier.height(14.dp))
                    CardSectionLabel("压暗")
                    Spacer(Modifier.height(8.dp))
                    NumberInputField(
                        label = "压暗量（默认 0.12）",
                        value = dimText,
                        onValueChange = { t ->
                            dimText = t
                            t.toFloatOrNull()?.let { Preferences.setBackDimAmount(context, it) }
                        },
                        numeric = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "覆盖层让开时的透明度衰减（0.12 表示最终降到 88%）。" +
                            "调大：被推走的层次感更强；调小：更通透。建议 0~0.3。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 5.dp)) {
                    CardSectionLabel("恢复")
                    Spacer(Modifier.height(8.dp))
                    ActionButton(
                        text = "全部恢复默认",
                        onClick = {
                            Preferences.setBackTranslationRatio(
                                context, Preferences.DEFAULT_BACK_TRANSLATION_RATIO,
                            )
                            Preferences.setBackScaleAmount(
                                context, Preferences.DEFAULT_BACK_SCALE_AMOUNT,
                            )
                            Preferences.setBackCornerRadiusDp(
                                context, Preferences.DEFAULT_BACK_CORNER_DP,
                            )
                            Preferences.setBackSettleDurationMs(
                                context, Preferences.DEFAULT_BACK_SETTLE_MS,
                            )
                            Preferences.setBackDimAmount(
                                context, Preferences.DEFAULT_BACK_DIM_AMOUNT,
                            )
                            // 同步刷新输入框
                            translationText = fmt(Preferences.DEFAULT_BACK_TRANSLATION_RATIO)
                            scaleText = fmt(Preferences.DEFAULT_BACK_SCALE_AMOUNT)
                            cornerText = fmtInt(Preferences.DEFAULT_BACK_CORNER_DP)
                            settleText = Preferences.DEFAULT_BACK_SETTLE_MS.toString()
                            dimText = fmt(Preferences.DEFAULT_BACK_DIM_AMOUNT)
                        },
                        filled = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            SectionCard {
                Column(Modifier.padding(vertical = 5.dp)) {
                    CardSectionLabel("说明")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "· 改动立即生效，无需重启：参数会在下一次手势开始时重新读取。\n" +
                            "· 若完全看不到跟手动画，先确认系统已开启预测性返回。" +
                            "部分国产 ROM 有独立开关（如 ColorOS 的「第三方应用预测性返回」），" +
                            "关闭时系统不下发手势进度。\n" +
                            "· Android 13 及以下不下发手势进度，此时退化为无跟手的常规返回。",
                        style = OsText.micro,
                        color = c.textTertiary,
                    )
                }
            }
        }
    }
}

/** 去掉多余的小数尾巴：0.32 保留，28.0 显示成 28 */
private fun fmt(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

private fun fmtInt(v: Float): String = v.toLong().toString()
