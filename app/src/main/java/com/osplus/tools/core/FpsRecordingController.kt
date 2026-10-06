package com.osplus.tools.core

import android.content.Context
import android.util.Log
import com.osplus.tools.model.FpsSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 录制生命周期的**唯一入口**。
 *
 * ## 为什么必须抽出来
 *
 * 原先「开始录制」的完整流程（查前台包名 → 建会话 → 打开 [FpsRecorder]）
 * 写在 [com.osplus.tools.vm.DeviceViewModel.startFpsRecording] 里，而录制开关
 * 有三处入口：帧率页的开关、实时任务通知上的按钮、以及新加的「点监视器悬浮窗」。
 * 后两者由**服务**触发，服务拿不到 ViewModel 实例。
 *
 * 若服务退而调用 `FpsRecorder.toggleRecording()`，只会翻转采样开关、**不建会话**，
 * 录到的数据因为 `session <= 0` 被丢弃、永不落库——用户点一下悬浮窗看到
 * 「记录中」，录完却一条历史都没有，这比不能点更糟。
 *
 * 因此把流程收归到这里：一个进程内单例，持有 `activeSessionId` 与建会话作业，
 * ViewModel 与服务都通过它启停。会话状态只有一份，天然不会重复建会话。
 *
 * ## 线程
 *
 * [start] 会先做 IO（查前台包、建会话）再切主线程开 Choreographer；
 * [stop] 反之。全部在主线程被调用时也能安全等待（内部用挂起函数，
 * 不阻塞调用线程）。
 */
object FpsRecordingController {

    private const val TAG = "OSPlusrFps"

    /** 独立的协程作用域，生命周期随进程，不依赖任何 Activity/Service */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前活动会话 id；-1 表示没有活动会话 */
    @Volatile
    private var activeSessionId: Long = -1L

    /** 正在建会话的作业，用于幂等与取消 */
    private var startJob: Job? = null

    private var store: FpsWatchStore? = null

    /**
     * 最近一次「开始/停止」动作后的会话列表是否已变化。
     *
     * UI 层（FpsScreen）在录制状态翻转时会调 `refreshFpsSessions()`，
     * 但服务触发的停止没有 UI 参与。这里发一个版本号，UI 可以据此
     * 重新拉取会话列表（可选消费，不影响功能）。
     */
    private val _sessionRevision = MutableStateFlow(0L)
    val sessionRevision: StateFlow<Long> = _sessionRevision.asStateFlow()

    /** 录制状态透传（真值在 [FpsRecorder]，这里只是给调用方一个便捷入口） */
    val recording: StateFlow<Boolean> = FpsRecorder.recording

    private fun ensureStore(context: Context): FpsWatchStore =
        store ?: synchronized(this) {
            store ?: FpsWatchStore(context.applicationContext).also { store = it }
        }

    /**
     * 开始一次录制：建会话 → 打开采样。
     *
     * 幂等：已在建会话 / 已在录制时直接返回，不会开出第二个会话。
     * 建会话失败（id <= 0）时**拒绝打开采样开关**——否则会录一段只在内存里、
     * 永不落库的数据。
     *
     * @return 是否最终处于录制中（异步结果，调用方以 [FpsRecorder.recording] 为准）
     */
    fun start(context: Context) {
        if (startJob?.isActive == true) return
        val app = context.applicationContext
        startJob = scope.launch {
            val pkg = withContext(Dispatchers.IO) {
                runCatching { ProcessDataSource.foregroundPackage(app) }.getOrNull()
            }
            val s = ensureStore(app)
            val sid = withContext(Dispatchers.IO) { s.createSession(pkg) }
            activeSessionId = sid
            if (sid > 0L) {
                FpsRecorder.startRecording()
                _sessionRevision.value = System.currentTimeMillis()
            } else {
                Log.e(TAG, "建会话失败，拒绝开始记录")
            }
        }
    }

    /**
     * 停止录制并收尾会话（写入 `time_end` 与样本条数）。
     *
     * 先取消可能仍在进行的建会话作业：否则它稍后会把 `activeSessionId`
     * 写回来并重新打开录制，表现为「明明停了，过一会儿自己又开始录」。
     */
    fun stop(context: Context) {
        val app = context.applicationContext
        startJob?.cancel()
        startJob = null
        FpsRecorder.stopRecording()
        val sid = activeSessionId
        activeSessionId = -1L
        if (sid > 0L) {
            scope.launch {
                val s = ensureStore(app)
                withContext(Dispatchers.IO) { s.endSession(sid) }
                _sessionRevision.value = System.currentTimeMillis()
            }
        }
    }

    /** 翻转录制状态。返回翻转**后**的状态（即为 true 表示这次是开始）。 */
    fun toggle(context: Context): Boolean {
        val now = FpsRecorder.recording.value
        if (now) stop(context) else start(context)
        return !now
    }

    /** 当前活动会话 id（供需要落库的调用方判断） */
    fun activeSessionId(): Long = activeSessionId

    /**
     * 录制中途追加一条采样。
     *
     * 由 ViewModel 的每秒节拍调用；会话不存在时静默丢弃（不落库），
     * 这是有意的——避免给不存在的 session 写孤儿数据。
     */
    fun addSample(context: Context, record: com.osplus.tools.model.FpsRecord) {
        val sid = activeSessionId
        if (sid <= 0L) return
        val s = ensureStore(context.applicationContext)
        scope.launch {
            withContext(Dispatchers.IO) { s.addSample(sid, record) }
        }
    }

    /** 会话数据源，供 UI 层查询列表/样本/统计（避免各处各自 new 一个 store） */
    fun store(context: Context): FpsWatchStore = ensureStore(context.applicationContext)

    /** 供测试或「清空记录」后重置内存态 */
    fun resetState() {
        activeSessionId = -1L
    }

    /** 便于 UI 展示：当前会话 id 是否有效 */
    fun hasActiveSession(): Boolean = activeSessionId > 0L

    /** 未使用，仅为保持 [FpsSession] 导入语义清晰（会话元数据由 store 提供） */
    @Suppress("unused")
    private fun typeAnchor(s: FpsSession) = s.id
}
