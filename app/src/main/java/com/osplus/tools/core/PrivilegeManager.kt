package com.osplus.tools.core

import android.content.Context
import com.osplus.tools.core.adb.AdbBackend
import com.osplus.tools.core.adb.AdbCertificate
import com.osplus.tools.core.adb.AdbDiscovery
import com.osplus.tools.core.adb.AdbKeyStore
import com.osplus.tools.core.adb.AdbPairClient
import com.osplus.tools.core.adb.AdbScriptDeployer
import com.osplus.tools.core.adb.Spake2
import com.osplus.tools.core.adb.DaemonBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 提权通道的统一门面。
 *
 * 负责三件事，全应用只有这一处做这些判断：
 * 1. **探测**设备上有哪些模式可用（root / Shizuku / ADB）；
 * 2. **选择并安装**后端到 [Shell]，之后所有命令都自动走该通道；
 * 3. **对外暴露**当前模式与能力表 [PrivilegeCapabilities]，供界面决定控件是否可点。
 *
 * 模式选择的优先级是「用户显式选择 > 自动挑最高权限」：
 * Preferences 里存了用户的选择就照做（哪怕它不是权限最高的），
 * 这样用户能主动降级（例如 root 机器上想用 Shizuku 复现问题）。
 */
object PrivilegeManager {

    private const val KEY_MODE = "privilege_mode"

    private const val TAG = "PrivilegeManager"

    private val _mode = MutableStateFlow<PrivilegeMode?>(null)
    val mode: StateFlow<PrivilegeMode?> = _mode.asStateFlow()

    /**
     * 用户在设置页**点选**的模式 —— 与 [mode] 不是一回事。
     *
     * [mode] 是「当前真正装上的通道」，由 [refresh] 探测后决定：点了不可用的模式
     * （比如没连 ADB 就点 ADB），[mode] 会**回退**到能用的那个。如果界面直接用
     * [mode] 驱动选中态，就会出现「点了没反应、胶囊不跟过去」——用户以为点击无效。
     *
     * [selection] 记录的是**用户的选择**（与 `Preferences` 里持久化的那份一致），
     * 点了就立刻反映到界面；至于该选择当前能不能用，由 [probe] / 提示文案去说明。
     */
    private val _selection = MutableStateFlow<PrivilegeMode?>(null)
    val selection: StateFlow<PrivilegeMode?> = _selection.asStateFlow()

    private val _capabilities = MutableStateFlow(PrivilegeCapabilities.NONE)
    val capabilities: StateFlow<PrivilegeCapabilities> = _capabilities.asStateFlow()

    /** 各模式在本机的可探测状态，用于设置页展示「哪些模式能用」 */
    private val _probe = MutableStateFlow(ModeProbe())
    val probe: StateFlow<ModeProbe> = _probe.asStateFlow()

    private var rootBackend: RootBackend? = null
    private var shizukuBackend: ShizukuBackend? = null
    private var adbBackend: AdbBackend? = null
    /** 「电脑 ADB」模式的通道：与 up.sh 拉起的常驻 daemon 对话 */
    private var daemonBackend: DaemonBackend? = null

    private var appContext: Context? = null

    /** 本机对可用模式的探测结果 */
    data class ModeProbe(
        /** 设备上存在可用的 su */
        val rootSupported: Boolean = false,
        /** root 已授权且当前可用 */
        val rootReady: Boolean = false,
        /** 装了 Shizuku 且服务在运行 */
        val shizukuSupported: Boolean = false,
        /** Shizuku 已授权 */
        val shizukuReady: Boolean = false,
        /** ADB 已完成配对（本机有身份文件） */
        val adbPaired: Boolean = false,
        /** 无线 ADB 通道当前可用（无线调试开着且认证通过） */
        val adbReady: Boolean = false,
        /** 交换目录已建立（用户至少跑过一次 up.sh） */
        val daemonProvisioned: Boolean = false,
        /** 常驻 daemon 还活着（心跳在刷新） */
        val daemonReady: Boolean = false,
    )

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        rootBackend = RootBackend(Shell::findSu)
        shizukuBackend = ShizukuBackend()
        adbBackend = AdbBackend(context.applicationContext)
        daemonBackend = DaemonBackend(context.applicationContext)
    }

    fun shizuku(): ShizukuBackend? = shizukuBackend
    fun adb(): AdbBackend? = adbBackend
    fun daemon(): DaemonBackend? = daemonBackend

    /**
     * 启动时的自动恢复。
     *
     * **分两类通道区别对待**——这是本方法存在的全部理由：
     *
     * - **不会弹授权框的**（Shizuku 服务、无线 ADB 配对、电脑 daemon 心跳）：
     *   探测只是读文件 / 查服务，代价极低且无副作用，因此**启动时一律自动探测**。
     *   daemon 已经在跑就自动用上，用户不必先去设置页选一次模式。
     * - **会弹授权框的**（Root 的 `su -c id -u`）：只有在用户此前已经存过
     *   ROOT 模式（说明授权流程早已走完）时才探测。否则开机自启
     *   （BOOT_COMPLETED）场景下弹框无人可点，进程会卡死在启动阶段。
     *
     * @return 是否恢复了某个提权模式
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
     * 探测各模式并选出一个后端装上。
     *
     * @param preferred 用户显式指定的模式；为 null 时按 root > Shizuku > ADB 自动挑选
     * @param probeRoot 是否探测 Root。传 false 可跳过 `su -c id -u`——
     *   那是唯一会弹授权框、进而可能卡住启动的探测，其余通道（Shizuku 服务、
     *   无线 ADB 配对、daemon 心跳）都只是读文件 / 查服务。
     */
    suspend fun refresh(preferred: PrivilegeMode? = null, probeRoot: Boolean = true) =
        withContext(Dispatchers.IO) {
            val ctx = appContext ?: return@withContext

            // 不探测 root 时，rootSupported / rootReady 一律按「不可用」上报，
            // 于是自动挑选会自动落到 Shizuku / ADB 上
            val rootSupported = if (probeRoot) Shell.findSu() != null else false
            val rootReady = if (probeRoot) rootBackend?.isAvailable() == true else false
            val shizukuSupported = shizukuBackend?.isSupported() == true
            val shizukuReady = shizukuBackend?.isAvailable() == true
            val adbPaired = AdbBackend.isPaired(ctx)
            val adbReady = adbBackend?.isAvailable() == true
            // daemon 的可用性只看心跳：交换目录存在只说明用户跑过脚本，
            // 进程可能已经随系统重启或被回收而消失
            val daemonProvisioned = daemonBackend?.isProvisioned() == true
            val daemonReady = daemonBackend?.isAvailable() == true

            _probe.value = ModeProbe(
                rootSupported = rootSupported,
                rootReady = rootReady,
                shizukuSupported = shizukuSupported,
                shizukuReady = shizukuReady,
                adbPaired = adbPaired,
                adbReady = adbReady,
                daemonProvisioned = daemonProvisioned,
                daemonReady = daemonReady,
            )

            val stored = preferred
                ?: PrivilegeMode.fromName(Preferences.privilegeMode(ctx))

            // 用户指定了模式就用它；否则自动挑当前可用的最高权限。
            // 顺序：root > Shizuku > 无线 ADB > 电脑 ADB。
            // 后两者都是 shell 身份、能力完全相同，把无线 ADB 排前面是因为它延迟低；
            // 电脑 ADB 走文件轮询（约 0.2 秒一次），定位是没有 WiFi 时的兜底。
            val chosen = stored?.takeIf { it.isReady() }
                ?: listOf(
                    PrivilegeMode.ROOT,
                    PrivilegeMode.SHIZUKU,
                    PrivilegeMode.ADB,
                ).firstOrNull { it.isReady() }

            _mode.value = chosen
            Shell.installBackend(backendFor(chosen))
            _capabilities.value = PrivilegeCapabilities(
                mode = chosen,
                available = chosen != null,
            )
        }

    private fun PrivilegeMode.isReady(): Boolean = when (this) {
        PrivilegeMode.ROOT -> _probe.value.rootReady
        PrivilegeMode.SHIZUKU -> _probe.value.shizukuReady
        // ADB 的两条激活路径（无线配对 / 电脑脚本）给的都是 shell 身份，
        // 任一可用即视为本模式就绪
        PrivilegeMode.ADB -> _probe.value.adbReady || _probe.value.daemonReady
    }

    private fun backendFor(mode: PrivilegeMode?): PrivilegeBackend? = when (mode) {
        PrivilegeMode.ROOT -> rootBackend
        PrivilegeMode.SHIZUKU -> ShellBackend { cmd, t ->
            shizukuBackend?.run(cmd, t) ?: CommandResult(false, "", "Shizuku 后端未初始化")
        }
        PrivilegeMode.ADB -> {
            // 两条通道能力完全相同，优先走无线（延迟低），没有再退回常驻 daemon。
            // 只在装后端这一刻判定一次，避免每条命令都重新探测。
            val preferWifi = _probe.value.adbReady
            ShellBackend { cmd, t ->
                if (preferWifi) {
                    adbBackend?.run(cmd, t)
                        ?: CommandResult(false, "", "ADB 后端未初始化")
                } else {
                    daemonBackend?.run(cmd, t)
                        ?: CommandResult(false, "", "守护进程通道未初始化")
                }
            }
        }
        null -> null
    }

    /**
     * 切换到指定模式。
     *
     * Shizuku 模式需要先授权、ADB 模式需要先配对，
     * 因此这里只负责「切过去并检测」，前置授权由设置页引导用户完成。
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

    // ------------------------------------------------------------------ ADB

    /**
     * 执行 ADB 无线调试配对。**阻塞**，内部切到 IO 线程。
     *
     * 配对成功后设备会把内置公钥写入白名单，但**通道仍需要用户保持无线调试开启**。
     * 因此这里配对成功后再 [redetect] 一次，让界面立刻反映新的可用状态。
     */
    suspend fun pairAdb(
        host: String?,
        pairPort: Int,
        code: String,
    ): AdbPairClient.Result = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext AdbPairClient.Result.Failed("应用未初始化")

        // 配对前先跑一遍 SPAKE2 自检。
        //
        // 这段密码学是自行移植的（Android 上不存在 BoringSSL 的 SPAKE2），
        // 一旦它在移植或 R8 混淆中出错，症状是「配对码正确但就是配不上」——
        // 从现象上完全区分不出是算法错、还是网络/设备问题。
        // 放在配对路径里跑，结果进日志可查；更重要的是让 selfTest 始终有调用点，
        // 否则它会被 R8 当死代码裁掉，发布版里连验证手段都没有。
        runCatching {
            val st = Spake2.selfTest()
            if (st.allPassed) {
                PrivilegeLog.d("Spake2", "配对前自检通过")
            } else {
                PrivilegeLog.e(
                    "Spake2",
                    "配对前自检失败：标量乘=${st.scalarMultVectorOk} " +
                        "密钥一致=${st.keyAgreementOk} " +
                        "错误口令分离=${st.wrongPasswordMismatchOk} " +
                        "2B=${st.twoBHex}",
                )
            }
        }

        val result = AdbPairClient.pair(
            context = ctx,
            host = host,
            pairPort = pairPort,
            code = code,
        )
        if (result is AdbPairClient.Result.Success) {
            // 配对完成即自动切到 ADB 模式并重探，
            // 省掉用户「配对成功还要再点一次切换」这一步。
            Preferences.setPrivilegeMode(ctx, PrivilegeMode.ADB.name)
            refresh(preferred = PrivilegeMode.ADB)
        }
        result
    }

    /** 让 ADB 后端手动连接指定端点（自动发现失败时的兜底） */
    suspend fun adbConnect(host: String, port: Int): Boolean =
        withContext(Dispatchers.IO) { adbBackend?.connect(host, port) == true }

    /**
     * 发现无线调试的配对端口。
     *
     * 注意：配对端口**只在「使用配对码配对设备」页面打开期间**才广播，
     * 用户关掉页面就消失，所以必须在用户点「开始配对」的那一刻现查。
     */
    suspend fun discoverPairingPort(): Int? = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext null
        AdbDiscovery.discover(ctx, AdbBackend.PAIRING_SERVICE)?.second
    }

    /** 发现无线调试的连接端口（已配对后建立会话时用） */
    suspend fun discoverConnectPort(): Int? = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext null
        AdbDiscovery.discover(ctx, AdbBackend.CONNECT_SERVICE)?.second
    }

    // ------------------------------------------------------------------
    // 脚本激活通道（照搬 Scene 的 up.sh 方式）
    //
    // 与上面的「配对 + 长连接」是两条互补路径：
    //   * 长连接：应用内自动，依赖无线调试已配对；
    //   * 脚本  ：用户从电脑执行一条 adb 命令，只要有 USB 就行，不需要配对。
    // 后者是 Scene 的原始做法，作为前者的兜底。
    // ------------------------------------------------------------------

    /**
     * 把 `up.sh` 部署到外部私有目录，返回落点。
     *
     * 必须在用户执行 adb 命令**之前**完成 —— 脚本要先存在于设备上。
     */
    fun deployActivationScript(): AdbScriptDeployer.DeployResult {
        val ctx = appContext ?: return AdbScriptDeployer.DeployResult.Failed("应用未初始化")
        return AdbScriptDeployer.deploy(ctx)
    }

    /** 生成给用户复制的 adb 命令；应用未初始化时返回 null */
    fun activationCommand(): String? =
        appContext?.let { AdbScriptDeployer.activationCommand(it) }

    /**
     * 通过已建立的 ADB 通道执行激活脚本。
     *
     * 这是相对 Scene 的增强：Scene 只能由用户从电脑手动执行，
     * 而本应用自带 ADB 客户端，无线调试已配对时可一键完成。
     */
    suspend fun runActivationScript(): CommandResult {
        val ctx = appContext ?: return CommandResult(false, "", "应用未初始化")
        val backend = adbBackend ?: return CommandResult(false, "", "ADB 后端未初始化")
        return AdbScriptDeployer.runViaAdb(backend, ctx)
    }

    /** 读取脚本实测出的 shell 能力表（见 up.sh 第 3 步） */
    suspend fun readScriptCapabilities(): Map<String, String> {
        val backend = adbBackend ?: return emptyMap()
        return AdbScriptDeployer.readCapabilities(backend)
    }
}
