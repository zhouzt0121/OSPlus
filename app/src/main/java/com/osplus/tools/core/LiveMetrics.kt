package com.osplus.tools.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 供悬浮窗显示的实时指标。
 *
 * 采样在 ViewModel 中每秒执行一次（那里已有完整的采集链路与 root 合并命令），
 * 这里只做跨组件的状态中转，避免悬浮窗服务重复拉起 root 采样。
 */
object LiveMetrics {

    private val _cpuLoad = MutableStateFlow(0f)
    val cpuLoad: StateFlow<Float> = _cpuLoad.asStateFlow()

    /** GPU 负载百分比，-1 表示不可读 */
    private val _gpuLoad = MutableStateFlow(-1)
    val gpuLoad: StateFlow<Int> = _gpuLoad.asStateFlow()

    fun update(cpuLoad: Float, gpuLoad: Int) {
        _cpuLoad.value = cpuLoad
        _gpuLoad.value = gpuLoad
    }
}
