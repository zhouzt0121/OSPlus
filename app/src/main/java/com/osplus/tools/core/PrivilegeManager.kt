package com.osplus.tools.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 提权通道的统一门面。
 *
 * 负责三件事，全应用只有这一处做这些判断：
 * 1. **探测**设备上是否有可用的 root；
 * 2. **选择并安装**后端到 [Shell]，之后所有命令都自动走该通道；
 * 3. **对外暴露**当前模式与能力表 [PrivilegeCapabilities]，供界面决定控件是否可点。
 *
 * ## 只剩下 Root 一条通道
 *
 * 2.2.0 曾同时支持 Shizuku 与 ADB（无线配对 / 电脑脚本 daemon）。
 * 实测证明这两条 shell 身份通道在 OSPlus 的功能需求下**全部不可用**
 * （调频节点写不了、`/data/adb` 读不到、帧率节点读不到），
 * 因此连同其依赖被整体移除。详见 [PrivilegeMode] 的说明。
 *
 * 保留 [selection] / [probe] 这套结构而不是简化掉，是因为
 * 「用户点选」与「实际生效」在 root 上同样是两回事：
 * 用户点 Root 但未授权时，[mode] 会回退到 null，界面需要这一区分。
 */
object PrivilegeManager {

    private const val TAG = "PrivilegeManager"

    private val _mode = MutableStateFlow<PrivilegeMode?>(null)
    val mode: StateFlow<PrivilegeMode?> = _mode.asStateFlow()

    /**
     * 用户在设置页**点选**的模式 —— 与 [mode] 不是一回事。
     *
     * [mode] 是「当前真正装上的通道」，由 [refresh] 探测后决定：点了但不可用
     * （比如没授权 root 就点 Root），[mode] 会**回退**到 null。如果界面直接用
     * [mode] 驱动选中态，就会出现「点了没反应、胶囊不跟过去」——用户以为点击无效。
     *
     * [selection] 记录的是**用户的选择**（与 `Preferences` 里持久化的那份一致），
     * 点了就立刻反映到界面；至于该选择当前能不能用，由 [probe] / 提示文案去说明。
     */
    private val _selection = MutableStateFlow<PrivilegeMode?>(null)
    val selection: StateFlow<PrivilegeMode?> = _selection.asStateFlow()

    private val _capabilities = MutableStateFlow(PrivilegeCapabilities.NONE)
    val capabilities: StateFlow<PrivilegeCapabilities> = _capabilities.asStateFlow()

    /** 各模式在本机的可探测状态，用于界面展示「能不能用」 */
    private val _probe = MutableStateFlow(ModeProbe())
    val probe: StateFlow<ModeProbe> = _probe.asStateFlow()

    private var rootBackend: RootBackend? = null

    private var appContext: Context? = null

    /** 本机对可用模式的探测结果 */
    data class ModeProbe(
        /** 设备上存在可用的 su */
        val rootSupported: Boolean = false,
        /** root 已授权且当前可用 */
        val rootReady: Boolean = false,
    )

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        rootBackend = RootBackend(Shell::findSu)
    }

    /**
     * 启动时的自动恢复。
     *
     * **Root 探测会弹授权框**（`su -c id -u`）：只有在用户此前已经存过
     * ROOT 模式（说明授权流程早已走完）时才探测。否则开机自启
     * （BOOT_COMPLETED）场景下弹框无人可点，进程会卡死在启动阶段。
     *
     * @return 是否恢复了提权模式
     */
    suspend fun restoreIfConfigured(): Boolean {
        val ctx = appContext ?: return false
        val stored = PrivilegeMode.fromName(Preferences.privilegeMode(ctx))
        // 上次的选择先回到界面上，避免启动瞬间选中态是空的
        _selection.value = stored
        // 存过 ROOT ⇒ 早已授权，探测不会再弹框；其余情况跳过 root 探测
        refresh(preferred = stored, probeRoot = stored == PrivilegeMode.ROOT)
        return _mode.value != null
    }

    /**
     * 探测并选出一个后端装上。
     *
     * @param preferred 用户显式指定的模式；为 null 时按可用性自动挑选
     * @param probeRoot 是否探测 Root。传 false 可跳过 `su -c id -u`——
     *   那是唯一会弹授权框、进而可能卡住启动的探测。
     */
    suspend fun refresh(preferred: PrivilegeMode? = null, probeRoot: Boolean = true) =
        withContext(Dispatchers.IO) {
            val ctx = appContext ?: return@withContext

            // 不探测 root 时，rootSupported / rootReady 一律按「不可用」上报
            val rootSupported = if (probeRoot) Shell.findSu() != null else false
            val rootReady = if (probeRoot) rootBackend?.isAvailable() == true else false

            _probe.value = ModeProbe(
                rootSupported = rootSupported,
                rootReady = rootReady,
            )

            // 探测结果必须落日志。
            //
            // 提权问题是**远程调试不了的**：用户说「用不了」时，我们既看不到他的界面
            // 也摸不到他的设备，唯一证据就是这一行。
            PrivilegeLog.i(
                "Probe",
                "root(支持=$rootSupported 就绪=$rootReady)",
            )

            val stored = preferred
                ?: PrivilegeMode.fromName(Preferences.privilegeMode(ctx))

            // 用户指定了模式且可用就用它，否则退回自动挑选（当前只有 ROOT）。
            val chosen = stored?.takeIf { it.isReady() }
                ?: entries.firstOrNull { it.isReady() }

            // 通道变了才重置采集侧的探测缓存。
            //
            // [SysFpsDataSource] 会缓存「哪条 sysfs 节点可读」以及在旧通道下得出的
            // 失败结论。这些结论**只对当时那条通道成立**：无 root 时走不通的路径，
            // 拿到 root 后可能就通。若不重置，用户授权 root 后帧率依旧显示不可读。
            //
            // 反过来，通道没变时**绝不能**重置：那会让每次 refresh 都丢掉缓存，
            // 于是每采样一轮就跑一遍 `find /sys`（遍历几万个节点），把采样周期拖垮。
            if (chosen != _mode.value) {
                SysFpsDataSource.reset()
            }

            _mode.value = chosen
            Shell.installBackend(backendFor(chosen))
            _capabilities.value = PrivilegeCapabilities(
                mode = chosen,
                available = chosen != null,
            )

            if (chosen == null) {
                PrivilegeLog.w(
                    "Select",
                    "未选中任何通道：Root 不可用。" +
                        "功能将退化为「只能读应用自身」。请检查 su 是否已授权本应用。",
                )
            } else {
                PrivilegeLog.i("Select", "已选中通道：$chosen（用户选择=$stored，回退=${stored != chosen}）")
            }
        }

    private fun PrivilegeMode.isReady(): Boolean = when (this) {
        PrivilegeMode.ROOT -> _probe.value.rootReady
    }

    /** 自动挑选时按这个顺序找第一个可用的通道 */
    private val entries: List<PrivilegeMode> = listOf(PrivilegeMode.ROOT)

    private fun backendFor(mode: PrivilegeMode?): PrivilegeBackend? = when (mode) {
        PrivilegeMode.ROOT -> rootBackend
        null -> null
    }

    /**
     * 切换到指定模式。
     *
     * Root 模式需要先授权，因此这里只负责「切过去并检测」，
     * 前置授权由界面引导用户完成。
     *
     * @return 切换后该模式是否可用
     */
    suspend fun select(mode: PrivilegeMode): Boolean {
        val ctx = appContext ?: return false
        // 先存选择再 refresh：refresh 会把 selected 作为 preferred 应用
        Preferences.setPrivilegeMode(ctx, mode.name)
        // 用户点选立刻反映到界面（即使该模式当前不可用）——
        // 否则界面会停在旧模式上，看起来像「点了没反应」
        _selection.value = mode
        refresh(preferred = mode)
        return _mode.value == mode
    }

    /** 完整重探一次（用户点了「重新检测」或从系统授权页返回） */
    suspend fun redetect() {
        val stored = appContext?.let { PrivilegeMode.fromName(Preferences.privilegeMode(it)) }
        refresh(preferred = stored)
    }
}
