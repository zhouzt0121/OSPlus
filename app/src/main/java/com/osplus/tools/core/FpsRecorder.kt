package com.osplus.tools.core

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.osplus.tools.model.FpsSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 基于 Choreographer 的帧率记录器。
 *
 * Choreographer 回调必须在主线程注册，因此 [start] / [stop] 需在主线程调用。
 */
object FpsRecorder {

    private const val NOMINAL_FRAME_MS = 16.67f

    private val _sample = MutableStateFlow(FpsSample())
    val sample: StateFlow<FpsSample> = _sample.asStateFlow()

    @Volatile
    var running: Boolean = false
        private set

    /**
     * 是否由应用内的「帧率记录」持有。
     *
     * 悬浮窗服务与录制功能共用同一个 Choreographer 实例：
     * 服务销毁时只有在应用未录制的情况下才停止统计，避免互相干扰。
     */
    @Volatile
    var appRetained: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var choreographer: Choreographer? = null

    private var windowStartNs = 0L
    private var frames = 0
    private var jank = 0
    private var bigJank = 0
    private var sumFrameMs = 0f
    private var maxFrameMs = 0f
    private var lastFrameNs = 0L

    private val callback: Choreographer.FrameCallback = Choreographer.FrameCallback { ns ->
        if (!running) return@FrameCallback
        if (windowStartNs == 0L) {
            windowStartNs = ns
            lastFrameNs = ns
        } else {
            val deltaMs = (ns - lastFrameNs) / 1_000_000f
            lastFrameNs = ns
            if (deltaMs > 0f && deltaMs < 2000f) {
                frames++
                sumFrameMs += deltaMs
                maxFrameMs = maxOf(maxFrameMs, deltaMs)
                if (deltaMs > NOMINAL_FRAME_MS * 2f) jank++
                if (deltaMs > NOMINAL_FRAME_MS * 4f) bigJank++
            }
        }
        val elapsedMs = (ns - windowStartNs) / 1_000_000f
        if (elapsedMs >= 1000f) {
            _sample.value = FpsSample(
                fps = if (elapsedMs > 0f) frames * 1000f / elapsedMs else 0f,
                jankCount = jank,
                bigJankCount = bigJank,
                avgFrameMs = if (frames > 0) sumFrameMs / frames else 0f,
                maxFrameMs = maxFrameMs,
                totalFrames = frames,
            )
            windowStartNs = ns
            frames = 0
            jank = 0
            bigJank = 0
            sumFrameMs = 0f
            maxFrameMs = 0f
        }
        choreographer?.postFrameCallback(callback)
    }

    fun start() {
        if (running) return
        running = true
        windowStartNs = 0L
        frames = 0
        jank = 0
        bigJank = 0
        sumFrameMs = 0f
        maxFrameMs = 0f
        handler.post {
            choreographer = Choreographer.getInstance()
            choreographer?.postFrameCallback(callback)
        }
    }

    /** 清零当前窗口的统计，不改变运行状态 */
    fun reset() {
        handler.post {
            windowStartNs = 0L
            lastFrameNs = 0L
            frames = 0
            jank = 0
            bigJank = 0
            sumFrameMs = 0f
            maxFrameMs = 0f
            _sample.value = FpsSample()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.post {
            choreographer?.removeFrameCallback(callback)
            choreographer = null
        }
        _sample.value = FpsSample()
    }
}
