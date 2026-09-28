package com.osplus.tools.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 供悬浮窗与前台服务通知显示的实时指标。
 *
 * 采样在 ViewModel 中每秒执行一次（那里已有完整的采集链路与 root 合并命令），
 * 这里只做跨组件的状态中转，避免悬浮窗服务 / 通知重复拉起 root 采样。
 */
object LiveMetrics {

    /** 一次完整快照；频率单位 MHz，内存单位 KB，功耗单位 mW */
    data class Snapshot(
        val cpuLoad: Float = 0f,
        val cpuFreqMhz: Int = 0,
        val gpuLoad: Int = -1,
        val gpuMhz: Int = -1,
        val memAvailKb: Long = 0L,
        val memTotalKb: Long = 0L,
        /** 整机功耗 mW；0 表示不可读 */
        val powerMw: Float = 0f,
        /** 电池温度 ℃；null 表示不可读 */
        val batteryTempC: Float? = null,
        /** 充电功率 W；0 表示未接充电器或不可读 */
        val chargeW: Float = 0f,
    ) {
        /** 内存占用百分比 0~100；由可用 / 全部推导，避免各显示端各算一遍 */
        val memUsedPercent: Float
            get() = if (memTotalKb > 0) {
                (memTotalKb - memAvailKb).coerceAtLeast(0L) * 100f / memTotalKb
            } else {
                0f
            }
    }

    private val _cpuLoad = MutableStateFlow(0f)
    val cpuLoad: StateFlow<Float> = _cpuLoad.asStateFlow()

    /** GPU 负载百分比，-1 表示不可读 */
    private val _gpuLoad = MutableStateFlow(-1)
    val gpuLoad: StateFlow<Int> = _gpuLoad.asStateFlow()

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun update(
        cpuLoad: Float,
        gpuLoad: Int,
        cpuFreqMhz: Int = _snapshot.value.cpuFreqMhz,
        gpuMhz: Int = _snapshot.value.gpuMhz,
        memAvailKb: Long = _snapshot.value.memAvailKb,
        memTotalKb: Long = _snapshot.value.memTotalKb,
        powerMw: Float = _snapshot.value.powerMw,
        batteryTempC: Float? = _snapshot.value.batteryTempC,
        chargeW: Float = _snapshot.value.chargeW,
    ) {
        _cpuLoad.value = cpuLoad
        _gpuLoad.value = gpuLoad
        _snapshot.value = Snapshot(
            cpuLoad = cpuLoad,
            cpuFreqMhz = cpuFreqMhz,
            gpuLoad = gpuLoad,
            gpuMhz = gpuMhz,
            memAvailKb = memAvailKb,
            memTotalKb = memTotalKb,
            powerMw = powerMw,
            batteryTempC = batteryTempC,
            chargeW = chargeW,
        )
    }
}
