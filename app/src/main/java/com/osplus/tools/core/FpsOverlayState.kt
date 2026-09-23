package com.osplus.tools.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 帧率悬浮窗服务的真实运行状态与外观参数。
 *
 * 界面不能只看偏好设置里的开关值：应用被强停或服务被系统回收后，
 * 偏好仍是「开启」，但服务其实已经不在运行，此时开关会显示错误状态、
 * 用户一点反而把「本就没开」的服务关掉。这里由服务自身在
 * onCreate / onDestroy 中写入真实状态，界面订阅它。
 *
 * 不透明度同理：界面改的是这里的值，服务订阅后立刻作用到窗口上，
 * 无需重启服务即可看到效果。
 */
object FpsOverlayState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal fun set(value: Boolean) {
        _running.value = value
    }

    /** 悬浮窗不透明度，取值 [MIN_ALPHA] ~ 1.0 */
    private val _alpha = MutableStateFlow(0.92f)
    val alpha: StateFlow<Float> = _alpha.asStateFlow()

    fun setAlpha(value: Float) {
        _alpha.value = value.coerceIn(MIN_ALPHA, 1f)
    }

    /** 低于该值文字会难以辨认，因此作为下限 */
    const val MIN_ALPHA = 0.25f
}
