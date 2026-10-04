package com.osplus.tools.core

import android.os.SystemClock

/**
 * **系统级**帧率采集。
 *
 * ## 与 [FpsRecorder] 的分工（重要，别混用）
 *
 * - [FpsRecorder] 走 `Choreographer`，测的是**本应用自身**的渲染节奏。
 *   它对本应用的卡顿最敏感、精度最高，但**测不到别的应用**——
 *   切到游戏里，它依然是本应用（后台）的帧回调。
 * - 本对象读**整机显示控制器**的实测输出，测的是**屏幕上正在发生的事**，
 *   与前台是哪个应用无关。代价是刷新粒度受控件上报周期限制。
 *
 * 两者是互补关系，不是替代关系：回顾自己抄的界面用前者，
 * 看游戏/别的应用跑得怎么样用后者。
 *
 * ## 采集路径（四级降级，移植自 Scene5 Alpha 的 `FpsUtils`）
 *
 * ```
 * 1) /sys/class/drm/sde-crtc-0/measured_fps       取第 2 列
 * 2) /sys/class/graphics/fb0/measured_fps         取整行（该节点只有一列）
 * 3) find /sys -name measured_fps → 筛出含 "crtc" 的路径取字典序最小
 * 4) find /sys -name fps          → 同样筛 "crtc"
 * 5) 全失败才退回 service call SurfaceFlinger 1013，两次读数差商
 * ```
 *
 * 命中任意一级后把路径**缓存**下来，后续不再重复探测——否则每采样一次
 * 就要跑一遍 `find /sys`，在 /sys 上遍历几万个节点会直接把采样周期拖垮。
 *
 * ## 权限（**需要 Root**）
 *
 * `/sys/class/drm/` 与 `/sys/class/graphics/` 下各节点对应用 uid 通常不可读。
 * 实测（SM8750 / Android 15）在 shell 身份（uid 2000）下
 * `cat /sys/class/drm/card0-sde-crtc-0/measured_fps` 返回 `Permission denied`，
 * `service call SurfaceFlinger 1013` 返回 `Operation not permitted` ——
 * 这是 SELinux 限制，换任何提权通道都绕不过。因此本功能需要 Root。
 *
 * 具体调用走 [Shell.run]（`root = true`，即用当前已授权的 Root 身份跑）。
 *
 * ## 通道切换后必须 [reset]
 *
 * 探测结果（可用路径、`service call` 是否可用）是按「当时那条通道的能力」得出的。
 * 换通道后若不 [reset]，旧通道下记下的失败结论会被沿用——表现为
 * 「授权 root 后帧率却还是不可读」。这个调用挂在 [PrivilegeManager] 里。
 */
object SysFpsDataSource {

    private const val TAG = "SysFps"

    /**
     * 可信帧率上限。当前量产面板最高 165Hz，留 1.2 倍余量覆盖驱动上报抖动。
     *
     * 存在的意义不是「限制显示」而是「拒绝垃圾值」：兜底的
     * `service call SurfaceFlinger 1013` 在 Android 11+ 上返回的并非帧计数，
     * 不加上限会直接把无意义的数字当帧率写进记录。
     */
    private const val MAX_PLAUSIBLE_FPS = 200f

    /** 一帧的物理最短耗时（ms）。120Hz ≈ 8.3ms，取 5ms 作为硬下限 */
    private const val MIN_FRAME_INTERVAL_MS = 5f

    /** 会话级缓存：已确认可用的 sysfs 节点路径；空串表示「探测过，没有」 */
    @Volatile
    private var cachedPath: String? = null

    /** 缓存路径是否需要取第 2 列（sde-crtc-0 是两列，fb0 是一列） */
    @Volatile
    private var cachedTakeSecondColumn: Boolean = true

    /** `service call` 方案是否已确认不可用 */
    @Volatile
    private var surfaceFlingerUsable: Boolean = true

    /** 上一次 `service call` 的帧计数与时刻，用于差分求帧率 */
    private var lastFrameCount: Long = -1L
    private var lastFrameAtMs: Long = -1L

    /**
     * 读取一次系统级帧率。
     *
     * @return 帧率；设备不支持或读取失败时返回 null（界面应显示「不可读」，
     *   而不是 0——0 会被误读成「卡死了」）
     */
    suspend fun read(): Float? {
        // 1) 已缓存的 sysfs 路径
        cachedPath?.let { path ->
            if (path.isNotEmpty()) return parseSysfs(readNodeValue(path), cachedTakeSecondColumn)
            // 空串 = 已确认 sysfs 不可用，继续往下走 service call
        }

        // 2) 首次探测 sysfs
        if (cachedPath == null) {
            val probe = probeSysfs()
            if (probe != null) {
                cachedPath = probe.first
                cachedTakeSecondColumn = probe.second
                PrivilegeLog.i(TAG, "系统帧率节点命中：${probe.first}（取${if (probe.second) "第2" else "第1"}列）")
                return parseSysfs(readNodeValue(probe.first), probe.second)
            }
            cachedPath = ""
            // 这一条很关键：真机上 sysfs 被 SELinux 挡住时**测不出来**，
            // 用户只会看到「系统帧率不可读」。日志要说清是「节点不可读」
            // 而不是「功能坏了」，否则会被当成 bug 反复排查。
            // 注意措辞：不要写死「shell 身份」——探测走的是当前提权身份
            // （多半是 root），写死会误导排查方向。真实身份见上面的 UID= 行。
            PrivilegeLog.i(
                TAG,
                "未找到可读的 measured_fps 节点（节点存在但当前身份读不到，或被 SELinux 拒绝）。" +
                    "退回 service call SurfaceFlinger。",
            )
        }

        // 3) sysfs 全不可用，退回 SurfaceFlinger
        if (surfaceFlingerUsable) {
            return readViaSurfaceFlinger()
        }
        return null
    }

    /** 当前是否拿到了可用的采集通道（供界面提示用） */
    fun hasSource(): Boolean = (cachedPath?.isNotEmpty() == true) || surfaceFlingerUsable

    /**
     * 当前实际使用的采集通道描述，供界面展示「数据从哪来」。
     *
     * 用户看到帧率数字时，最常问的就是「这数准不准 / 从哪读的」。
     * 把节点路径直接亮出来，比一句笼统的「系统级帧率」有用得多。
     */
    fun sourceLabel(): String? {
        val path = cachedPath ?: return null
        return when {
            path.isEmpty() && surfaceFlingerUsable -> null
            path.isEmpty() -> null
            else -> path
        }
    }

    /** 重置探测缓存。切换提权通道后应调用，否则会沿用旧通道下的失败结论 */
    fun reset() {
        cachedPath = null
        cachedTakeSecondColumn = true
        surfaceFlingerUsable = true
        lastFrameCount = -1L
        lastFrameAtMs = -1L
    }

    // ---------------- 内部实现 ----------------

    private suspend fun readNodeValue(path: String): String? =
        Shell.run("cat $path 2>/dev/null", root = true).stdout.takeIf { it.isNotBlank() }

    /**
     * 探测可用的 sysfs 帧率节点。
     *
     * 返回 `路径 to 是否取第 2 列`，全部失败返回 null。
     *
     * ## 路径候选为什么是这几条（以真机实测为准，不是照抄 Scene5）
     *
     * Scene5 只硬编码 `/sys/class/drm/sde-crtc-0/measured_fps`。
     * 在本机（ColorOS / SM8750）实测该路径**不存在**——drm 下的符号链接是
     * `card0-sde-crtc-0`、`card0-sde-crtc-1` …（前缀 `card0-`），
     * 照抄会因为路径不存在而直接跳过 sysfs、落到更不可靠的 `service call`。
     * 因此这里把两种命名都列上，并补 `card1-` 以兼容多卡设备。
     *
     * crtc 取 0/1/2 三档：0 通常是主显示，1/2 可能是外接或虚拟屏。
     * 多探测几次的成本很低（一次 `cat`），但能覆盖「主 crtc 编号不是 0」的机型。
     *
     * ## 为什么不再用 `find /sys` 兜底
     *
     * Scene5 的第三级是 `find /sys -name measured_fps`。**本机实测该命令
     * 在 shell 身份下永远返回空**（`/sys` 的多数子树对 shell 不可遍历），
     * 退出码 1、零输出。留在这里只会每轮白跑一次进程创建，还把采样周期拖长。
     * 取而代之的是上面的固定候选枚举——它覆盖的机型足够多，且是确定性的。
     */
    private suspend fun probeSysfs(): Pair<String, Boolean>? {
        // 一次 shell 调用把所有候选都试完，避免「每个路径一个来回」。
        // 输出约定：命中行形如 `HIT|<路径>|<列>`；末尾统一追加分隔符便于解析。
        val candidates = buildList {
            // 主显示优先：card0 的各个 crtc，编号 0 排最前
            listOf(0, 1, 2).forEach { i -> add("/sys/class/drm/card0-sde-crtc-$i/measured_fps" to true) }
            // 兼容 Scene5 的老命名（部分机型确实长这样）
            listOf(0, 1).forEach { i -> add("/sys/class/drm/sde-crtc-$i/measured_fps" to true) }
            // 单帧缓冲设备只有一列
            add("/sys/class/graphics/fb0/measured_fps" to false)
            add("/sys/class/graphics/fb1/measured_fps" to false)
        }

        // 一次 shell 调用把「真实身份 + 每个候选的原始内容」全部收集回来。
        // 拆成多次调用会把采样周期拖垮（每次都是 fork/exec + su），而且首次探测
        // 恰好是帧率页刚打开的时刻，多一次几百毫秒的调用就会被用户看见。
        //
        // **shell 侧不做格式判定**：早期版本在脚本里用 `case "$v" in [0-9]*)`
        // 筛「首字符是数字」，结果本机节点返回的是 `fps: 98.6 duration:500000 …`
        // （首字符是 `f`），可用节点被守卫直接挡掉，白白退化成不可读。
        // 现在 shell 只负责把原始内容带回来，判定统一交给 [parseSysfs]——
        // 解析逻辑只有一处，不会再出现「守卫与解析器对格式的假设不一致」。
        //
        // 输出约定：
        //   UID|<id -u 的结果>          —— 确认这条通道的**真实身份**
        //   P|<路径>|missing            —— 节点不存在
        //   P|<路径>|<原始内容或报错>    —— 存在；能否解析交给 Kotlin 侧
        // **不要用 `[ -e ]` 做存在性前置判断**（2.6.5 实测踩坑记录）
        //
        // 在 ColorOS / SM8750 上，`/sys/class/drm/card0-sde-crtc-N/` 是符号链接，
        // 链接本身可读（`ls -ld` 能正常列出），但它指向的目标在 SELinux 下
        // 对当前身份不可达 —— 此时 **`[ -e ]` 返回假**，节点被误判为「不存在」。
        //
        // 后果是整条 sysfs 链路被跳过、直接落到 `service call SurfaceFlinger 1013`
        // 兜底；而该 code 在 Android 11+ 已失效，返回的并不是帧计数，
        // 解析出来的「帧率」是垃圾值（实测同一段静止画面得到 119.9 → 7.1 → 1.5）。
        //
        // 正确判据只有一个：**直接 try-read**。读成功且能解析出合法帧率即为可用；
        // 读失败（不存在 / Permission denied）都归为不可用，由 Kotlin 侧统一处理。
        // 用 `[ -L ]` 也不行 —— 它只证明链接在，不证明目标可读，仍会把
        // 「链接在但读不到」错判成可用，把探测退化成每轮一次无效的读数。
        val script = buildString {
            append("echo \"UID=\$(id -u)\"; ")
            candidates.forEach { (path, _) ->
                append("printf 'P|%s|' '$path'; ")
                // 2>&1 + head -1：把 Permission denied / No such file 这类报错
                // 也一并带回来，便于日志区分「节点不存在」与「存在但读不到」。
                // 两者的修法完全不同：前者要改候选路径，后者要改提权方式。
                append("cat '$path' 2>&1 | head -1; ")
            }
            append("echo '###OSPLUS_END###'")
        }

        val out = Shell.run(script, root = true).stdout
        if (out.isBlank()) {
            PrivilegeLog.i(TAG, "探测脚本无输出 —— 提权通道可能未真正执行")
            return null
        }

        // 把身份与逐节点的真实结果记进日志。没有这段，出问题只能看到一句笼统的
        // 「未找到可读节点」，无法区分「su 没生效」和「节点格式对不上」——
        // 而这两者的修法完全不同。
        out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("UID=") || it.startsWith("P|") }
            .forEach { PrivilegeLog.i(TAG, "探测：$it") }

        // 按候选表的原始顺序逐个尝试解析：顺序即优先级（crtc-0 主显示排最前）。
        //
        // 注意这里**不预筛**「是不是报错行」：`cat` 失败的输出（`Permission denied`
        // / `No such file or directory`）不含可解析的帧率，[parseSysfs] 自然返回
        // null，与「读到但格式不认识」走同一条路径。少一处分支就少一处
        // 「守卫与解析器假设不一致」的机会（这正是上一版用 `[ -e ]` 踩的坑）。
        val secondByPath = candidates.toMap()
        out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("P|") }
            .forEach { line ->
                val path = line.removePrefix("P|").substringBeforeLast('|')
                val second = secondByPath[path] ?: return@forEach
                val raw = line.substringAfter("P|$path|")
                if (parseSysfs(raw, second) != null) return path to second
            }
        return null
    }

    /**
     * 解析 sysfs 节点内容。
     *
     * 真机上见过**两种**格式，必须都认：
     *
     * | 来源 | 样例 | 取法 |
     * |---|---|---|
     * | Scene5 原版假设 | `1234.56 60.01` | 第 2 列 |
     * | 本机实测（ColorOS / SM8750） | `fps: 98.6 duration:500000 frame_count:50` | `fps:` 之后那个数 |
     *
     * 早期只按「取第 2 列」实现，碰上带标签的格式纯属巧合能取对
     * （`split` 后 `cols[1] == "98.6"`），但一旦驱动改成 `duration|fps` 顺序
     * 或加字段就会静默取错值。因此这里**优先认标签**，标签缺失才退回按列取。
     *
     * 若整行是报错（`Permission denied` 等），各分支都取不到浮点，自然返回 null。
     */
    private fun parseSysfs(raw: String?, takeSecondColumn: Boolean): Float? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 1) 带标签格式：`fps: 98.6 …` / `fps=98.6` / `fps 98.6` 都覆盖
        Regex("""fps\s*[:=]?\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.let { v -> return v.takeIf { it > 0f && it <= MAX_PLAUSIBLE_FPS } }

        // 2) 纯数字列表：按既定列取（crtc 取第 2 列，fb 取第 1 列）
        val cols = text.split(Regex("\\s+"))
        val token = if (takeSecondColumn) cols.getOrNull(1) ?: return null else cols.firstOrNull() ?: return null
        val v = token.toFloatOrNull() ?: return null
        // 明显离谱的值（负数 / 超出面板物理上限）视为坏数据
        return v.takeIf { it > 0f && it <= MAX_PLAUSIBLE_FPS }
    }

    /**
     * 退回 `service call SurfaceFlinger 1013`。
     *
     * 该调用返回 `Result: Parcel(00000000 00002a3f ...)` 之类，
     * **帧计数是首个 `(` 之后 8 个十六进制字符**。两次读数做差、除以时间差得到帧率。
     *
     * 注意：`1013` 这个 code 不是稳定 ABI，Android 11+ 部分机型已失效。
     * 所以它只作为最后兜底，且失败后要把 [surfaceFlingerUsable] 置 false，
     * 避免每轮都白跑一次进程创建。
     */
    private suspend fun readViaSurfaceFlinger(): Float? {
        val res = Shell.run("service call SurfaceFlinger 1013", root = true)
        val text = res.stdout.trim()
        // 把原始返回记下来。Android 11+ 上这个 code 基本已失效，root 身份下
        // 也可能拿到 "Operation not permitted"（SELinux 对 service 调用的限制
        // 不因 uid=0 而放开），没有原始返回就无法判断到底是哪种失败。
        PrivilegeLog.i(TAG, "service call SurfaceFlinger 1013 返回：${text.ifEmpty { "(空)" }}")
        // 返回 "error" 或含 "Parcel" 之外的内容，直接判定不可用
        if (text.isEmpty() || text == "error") {
            surfaceFlingerUsable = false
            PrivilegeLog.i(TAG, "service call SurfaceFlinger 1013 不可用（空/error），系统帧率将显示为不可读")
            return null
        }
        // 非 root 的 shell 身份会拿到 "Operation not permitted" —— 本机实测如此
        if (text.contains("not permitted", ignoreCase = true) ||
            text.contains("Permission denied", ignoreCase = true)
        ) {
            surfaceFlingerUsable = false
            PrivilegeLog.i(TAG, "service call SurfaceFlinger 1013 被拒绝（$text），系统帧率将显示为不可读")
            return null
        }
        val open = text.indexOf('(')
        if (open < 0 || open + 9 > text.length) {
            surfaceFlingerUsable = false
            PrivilegeLog.i(TAG, "service call 返回格式无法解析：$text")
            return null
        }
        val hex = text.substring(open + 1, open + 9)
        val count = hex.toLongOrNull(16)
        if (count == null) {
            surfaceFlingerUsable = false
            return null
        }

        val now = SystemClock.elapsedRealtime()
        val prevCount = lastFrameCount
        val prevAt = lastFrameAtMs
        lastFrameCount = count
        lastFrameAtMs = now

        if (prevCount < 0L || prevAt < 0L || now <= prevAt) return null
        val deltaFrames = count - prevCount
        // 计数器回绕或异常跳变
        if (deltaFrames < 0L) return null

        // **合理性闸门（2.6.5 新增，实测必需）**
        //
        // 在 Android 11+ 的多数机型（含本机 Android 17）上，`1013` 这个 code
        // 早已不是「取帧计数」——返回的 Parcel 里那 8 位十六进制是别的含义。
        // 直接拿它做差分会得到毫无物理意义的数字：本机实测同一段**静止画面**
        // 连续读出 119.9 → 7.1 → 1.5，被当成有效帧率写进了记录。
        //
        // 与其把垃圾值喂给界面和落库（比「不可读」更糟——用户会拿它去分析卡顿），
        // 不如在这里做两道物理约束，不满足就判为不可读：
        //
        //   1. 帧率必须 > 0 且 <= [MAX_PLAUSIBLE_FPS]（面板 165Hz 机型留 1.2 倍余量）
        //   2. 差分窗口内的平均帧间隔不能小于 MIN_FRAME_INTERVAL_MS
        //      —— 相当于给帧率设一个与时间差无关的硬上限，挡住「时间差很小、
        //      计数差很大」这种典型的时间基准错配
        //
        // 这两条都不会误杀正常数据：真实帧率上限由面板决定，而下限 0 已由
        // 调用方的 `> 0f` 判断兜住。
        val dtMs = (now - prevAt).toFloat()
        val fps = deltaFrames * 1000f / dtMs
        if (fps <= 0f || fps > MAX_PLAUSIBLE_FPS) {
            PrivilegeLog.i(
                TAG,
                "service call 差分结果不可信（dFrames=$deltaFrames dt=${dtMs.toInt()}ms " +
                    "fps=%.1f），判定为不可读".format(fps),
            )
            // 注意**不置 surfaceFlingerUsable = false**：
            // 返回格式本身是能解析的（有 Parcel），只是数值不可信。
            // 关掉它会导致整条兜底通道被永久废弃；而真实原因可能是
            // 恰好这一次窗口异常。让它继续参与后续采样即可。
            return null
        }
        // 帧间隔物理下限：一帧至少要花这么久，否则说明计数/时间基准不匹配
        if (fps > 0f && 1000f / fps < MIN_FRAME_INTERVAL_MS) return null
        return fps
    }
}
