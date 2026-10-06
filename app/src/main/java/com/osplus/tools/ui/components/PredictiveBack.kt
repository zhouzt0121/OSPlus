package com.osplus.tools.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * 预测性返回（Predictive Back）在 Compose 侧的手势进度状态。
 *
 * ### 为什么需要这层封装
 *
 * `NavigationBackHandler` 只给「返回完成 / 返回取消」两个终态回调，
 * 滑动过程中的**进度**不在它的参数里。而预测性返回的全部价值恰恰在于过程：
 * 手指从屏幕边缘往右拖时，界面要跟手让开，用户能看到上一页正从左侧回来。
 * 拿不到进度，就退化成「松手才切换」，与 Android 13 之前没有区别。
 *
 * 进度实际藏在 `state.transitionState` 里——
 * `InProgress.latestEvent.progress`（0f~1f）以及 `touchX / touchY / swipeEdge`。
 * 本类型把它抽出来并统一负责两件事：
 *
 * 1. 订阅 `transitionState`，把进度写进 [progress]；
 * 2. 手势终止后，用 `Animatable` 把进度**平滑**送回 0（取消）或 1（完成），
 *    不做瞬变，避免「啪」地跳回去。
 *
 * ### 与系统的分工
 *
 * `isBackEnabled = false` 时系统不会把手势交给本处理器，
 * 一级页边缘右滑会回落给系统执行「预测性返回桌面」——这正是官方期望的行为，
 * 不要在这里拦。
 *
 * 用 [rememberPredictiveBackState] 创建，生命周期由组合管理，无需手动清理。
 */
class PredictiveBackState internal constructor() {
    /**
     * 对外暴露的进度：0f 完全展开 ~ 1f 已退回。
     *
     * 只有**一个**状态源，不设「手势值 + 动画值」两份。
     * 手势进行中由 [rememberPredictiveBackState] 直接写入系统给的实时值（零延迟跟手）；
     * 手势结束后由 `Animatable` 把同一个值平滑推回 0（取消）或 1（完成）。
     * 两份分开写会让两个写入方互相打架——收尾动画会把实时值往回拽，
     * 表现为跟手迟滞。这是 2.8.0 初版的错误写法，此处已收敛。
     */
    var progress by mutableFloatStateOf(0f)
        internal set

    /** 手势是否进行中。决定覆盖层是**跟手绘制**还是走常规进出场动画。 */
    var isInProgress by mutableStateOf(false)
        internal set

    /** 手指当前坐标（屏幕坐标系，像素）。可驱动「返回箭头跟手」这类效果。 */
    var touch by mutableStateOf(Offset.Zero)
        internal set
}

/**
 * 创建与 [NavigationBackHandler] 联动、并额外暴露手势进度的返回状态。
 *
 * @param isBackEnabled 当前是否**可以**返回。传 false 时系统不把返回手势
 *   交给本处理器——一级页且无二级路由时，手势会回落给系统做「预测性返回桌面」。
 * @param onBack 手势越过阈值、确认返回时执行，这里是**真正弹路由栈**的地方。
 */
@Composable
fun rememberPredictiveBackState(
    isBackEnabled: Boolean,
    onBack: () -> Unit,
): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val navState = rememberNavigationEventState(NavigationEventInfo.None)

    // 订阅进度。LaunchedEffect 的协程随组合销毁而取消，
    // snapshotFlow 会一直收集 transitionState 的变化。
    //
    // 这里**只写实时值，不碰动画**：写入方与收尾方必须是两条互斥的路径，
    // 否则同一帧内两个写入者会互相覆盖（详见 PredictiveBackState.progress 注释）。
    LaunchedEffect(navState) {
        snapshotFlow { navState.transitionState }.collect { transition ->
            if (transition is NavigationEventTransitionState.InProgress) {
                val event = transition.latestEvent
                state.progress = event.progress.coerceIn(0f, 1f)
                state.touch = Offset(event.touchX, event.touchY)
                state.isInProgress = true
            } else if (state.isInProgress) {
                // Idle：手势终止但**没有**走 onBackCompleted（即取消）。
                // 只翻标志位，实际的回弹动画交给下面那个 LaunchedEffect。
                state.isInProgress = false
            }
        }
    }

    // 收尾动画：手势结束后把进度平滑送回 0。
    //
    // 用动画器（而非直接 snapTo(0f)）是因为取消时覆盖层要「弹」回原位，
    // 瞬变会显得突兀。key 是 isInProgress，只在收尾那一刻启动一次。
    //
    // 注意 0f 是**恒定目标**：真正的「返回」走 onBackCompleted，
    // 那条路径会直接弹路由栈、覆盖层随之被移除，不需要这里动画。
    val settle = remember { Animatable(0f) }
    LaunchedEffect(state.isInProgress) {
        if (!state.isInProgress && state.progress > 0f) {
            settle.snapTo(state.progress)
            // 动画期间持续把插值写回 state.progress，让读取方（UI）看到中间帧。
            settle.animateTo(
                targetValue = 0f,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
            ) {
                state.progress = value
            }
        }
    }

    NavigationBackHandler(
        state = navState,
        isBackEnabled = isBackEnabled,
        onBackCompleted = {
            // 真正返回：进度归零，交给上层走路由弹栈。
            // 覆盖层会因为路由变化直接被移除，不需要收尾动画。
            state.progress = 0f
            state.isInProgress = false
            onBack()
        },
        onBackCancelled = {
            // 取消：不动路由栈。翻标志位即触发上面的收尾动画。
            state.isInProgress = false
        },
    )

    return state
}
