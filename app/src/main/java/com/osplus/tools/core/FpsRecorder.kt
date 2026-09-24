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
     * 悬浮窗服务是否持有统计器。
     *
     * 悬浮窗与录制功能共用同一个 Choreographer 实例：结束录制时若悬浮窗仍在运行，
     * 必须保留统计器（否则悬浮窗上的数字会冻结）；服务销毁时若仍在录制，
     * 同样不能停。两边各自置位，由这里的标志位仲裁。
     */
    @Volatile
    var overlayHolds: Boolean = false

    /**
     * 是否处于记录会话中。
     *
     * 放在这个单例里而不是 ViewModel 中：记录可以由应用内开关发起，
     * 也可以由桌面悬浮窗轻点发起，而悬浮窗在应用退到后台后仍然存在，
     * 两处必须看到同一个状态。
     */
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

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

    /**
     * 开始一次记录会话：清零统计窗口并确保逐帧回调在跑。
     *
     * 可由应用内开关或悬浮窗轻点调用，二者共享同一个状态。
     */
    fun startRecording() {
        reset()
        start()
        _recording.value = true
    }

    /**
     * 结束记录会话。
     *
     * 悬浮窗仍在运行时保留统计器——它还要靠逐帧回调刷新窗上的实时数字；
     * 否则帧率、CPU、GPU 三个数值会停在被冻结的最后一帧。
     */
    fun stopRecording() {
        _recording.value = false
        if (!overlayHolds) stop()
    }

    /** 切换记录会话，返回切换后的状态 */
    fun toggleRecording(): Boolean {
        if (_recording.value) stopRecording() else startRecording()
        return _recording.value
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
