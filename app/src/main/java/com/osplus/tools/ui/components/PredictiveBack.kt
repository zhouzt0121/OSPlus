package com.osplus.tools.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import com.osplus.tools.core.Preferences
import kotlinx.coroutines.CancellationException

/**
 * 预测性返回（Predictive Back）的跟手动画参数。
 *
 * 这些值**没有唯一正确解**：屏幕尺寸、刷新率、以及个人对「跟手」的容忍度
 * 都会影响观感。固定常量只能取一个折中值，所以全部开放到
 * 设置 → 预测性返回 里，改动即时生效。
 *
 * 每次手势开始时从持久化存储读一次并缓存到组合中；
 * 中途改设置不会打断正在进行的手势。
 */
data class BackAnimationTuning(
    /** 手势走满时的水平位移，占屏幕宽度的比例 */
    val translationRatio: Float,
    /** 手势走满时的缩放量（1 - 该值 = 最终缩放） */
    val scaleAmount: Float,
    /** 手势走满时的圆角上限（dp） */
    val cornerRadiusDp: Float,
    /** 取消后的回弹耗时（毫秒） */
    val settleDurationMs: Int,
    /** 手势走满时的压暗量 */
    val dimAmount: Float,
) {
    companion object {
        /** 与 Preferences 的出厂默认值保持一致；用作参数注入前的占位值。 */
        val Default = BackAnimationTuning(
            translationRatio = Preferences.DEFAULT_BACK_TRANSLATION_RATIO,
            scaleAmount = Preferences.DEFAULT_BACK_SCALE_AMOUNT,
            cornerRadiusDp = Preferences.DEFAULT_BACK_CORNER_DP,
            settleDurationMs = Preferences.DEFAULT_BACK_SETTLE_MS,
            dimAmount = Preferences.DEFAULT_BACK_DIM_AMOUNT,
        )

        fun from(context: android.content.Context) = BackAnimationTuning(
            translationRatio = Preferences.backTranslationRatio(context),
            scaleAmount = Preferences.backScaleAmount(context),
            cornerRadiusDp = Preferences.backCornerRadiusDp(context),
            settleDurationMs = Preferences.backSettleDurationMs(context),
            dimAmount = Preferences.backDimAmount(context),
        )
    }
}

/**
 * 预测性返回在 Compose 侧的手势进度状态。
 *
 * ### 为什么用 [PredictiveBackHandler] 而不是 `NavigationBackHandler`
 *
 * 这是 2.8.0 踩过的一个**方向性错误**，记录在此以免回退：
 *
 * 最初用的是 `androidx.navigationevent.compose.NavigationBackHandler`。
 * 它是**事件路由** API——只负责「这次返回该由谁处理」。用它有两个致命问题：
 *
 * 1. 它注册的是默认优先级的 `OnBackInvokedCallback`。官方文档明确说明：
 *    应用一旦注册了 `PRIORITY_DEFAULT` / `PRIORITY_OVERLAY` 的回调，
 *    **系统自己的预测性返回动画就不会再播**，必须由应用自己画完整个过程。
 * 2. 它没有面向动画的设计。进度虽能从 `InProgress.latestEvent.progress` 取到，
 *    但配合 `graphicsLayer` 写变换时极易踩「状态读取没触发 layer 失效」的坑，
 *    表现为**日志里 progress 在变、屏幕上却纹丝不动**，最终只剩松手瞬间的跳变。
 *
 * 官方给 Compose 动画场景提供的 API 是 `androidx.activity.compose.PredictiveBackHandler`，
 * 它直接给出 `Flow<BackEventCompat>`，`backEvent.progress` 就是跟手进度。
 *
 * ### 两个必须遵守的约束
 *
 * 1. **`progress` 只能在 `graphicsLayer` / `drawBehind` 的 lambda 里读**，
 *    不要在组合函数体里先 `val p = state.progress` 再传给 lambda——
 *    那样读到的是**本次组合快照的值**，后续变化不会让 layer 失效，
 *    屏幕就不会重绘（这正是 2.8.0 的 bug）。
 * 2. **收尾动画结束后必须把 `progress` 归零**，否则下一次手势会从上次残值开始。
 */
class PredictiveBackState internal constructor() {
    /**
     * 跟手进度：0f 完全展开 ~ 1f 已退回。
     *
     * 手势进行中由系统下发的 `BackEventCompat.progress` 直接写入（零延迟跟手）；
     * 手势取消后由 `Animatable` 平滑推回 0。
     *
     * **渲染侧务必在 `graphicsLayer` / `drawBehind` 的 lambda 内直接读本属性**。
     */
    var progress by mutableFloatStateOf(0f)
        internal set

    /** 手势是否进行中。用于区分「跟手绘制」与「常规进出场动画」。 */
    var isInProgress by mutableStateOf(false)
        internal set

    /** 手指当前坐标（屏幕坐标系，像素）。可驱动「返回箭头跟手」这类效果。 */
    var touch by mutableStateOf(Offset.Zero)
        internal set

    /** 当前生效的动画参数。由 [rememberPredictiveBackState] 注入。 */
    internal var tuning by mutableStateOf(BackAnimationTuning.Default)
        internal set
}

/**
 * 创建暴露手势进度的预测性返回状态，并读取设置的动画参数。
 *
 * @param isBackEnabled 当前是否**可以**返回。传 false 时不接管手势，
 *   系统会自己播「预测性返回桌面」——一级页就该是这个行为，不要在这里拦。
 * @param onBack 手势越过阈值、确认返回时执行，这里是**真正弹路由栈**的地方。
 */
@Composable
fun rememberPredictiveBackState(
    isBackEnabled: Boolean,
    onBack: () -> Unit,
): PredictiveBackState {
    val context = LocalContext.current
    val state = remember { PredictiveBackState() }

    // 注入参数。LaunchedEffect 在进入组合时跑一次；每次从设置页返回、
    // 组合重新进入时会再跑（remember 的 state 不变，但 tuning 会刷新）。
    // 这样「改完设置 → 返回 → 手势」立刻就是新参数。
    LaunchedEffect(Unit) {
        state.tuning = BackAnimationTuning.from(context)
    }

    // 收尾动画：手势取消后把进度平滑送回 0。
    val settle = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { state.isInProgress }.collect { inProgress ->
            if (!inProgress && state.progress > 0f) {
                settle.snapTo(state.progress)
                settle.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = state.tuning.settleDurationMs,
                        easing = FastOutSlowInEasing,
                    ),
                ) {
                    state.progress = value
                }
            }
        }
    }

    PredictiveBackHandler(enabled = isBackEnabled) { progressFlow ->
        // 每次手势开始重读参数，保证「设置里改完立刻生效」
        state.tuning = BackAnimationTuning.from(context)
        try {
            progressFlow.collect { backEvent ->
                state.progress = backEvent.progress.coerceIn(0f, 1f)
                state.touch = Offset(backEvent.touchX, backEvent.touchY)
                state.isInProgress = true
            }
            // Flow 正常结束 = 手势越过阈值，确认返回。
            state.progress = 0f
            state.isInProgress = false
            onBack()
        } catch (e: CancellationException) {
            // 手势取消：不动路由栈，翻标志位触发回弹动画。
            state.isInProgress = false
            throw e
        }
    }

    return state
}
