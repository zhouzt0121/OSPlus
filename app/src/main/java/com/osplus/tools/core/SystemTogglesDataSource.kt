package com.osplus.tools.core

/**
 * 系统开关数据源 —— `settings get/put` 类开关的读写。
 *
 * ## 为什么走 shell 而不是 [android.provider.Settings]
 *
 * 应用身份只能写自己拥有的 namespace 里的少量键，而且要 `WRITE_SETTINGS`
 * 运行时授权。而这里的键分属三个 namespace：
 *
 * - `system`：动画缩放、旋转锁定、自由窗口
 * - `secure`：状态栏图标黑名单、悬浮通知（部分 ROM）
 * - `global`：悬浮通知、夜间模式、过度绘制
 *
 * 逐个判断「这个键属于哪个 namespace、应用身份能不能写」既繁琐又不可靠
 * （不同 ROM 归属不同）。**统一交给 shell 身份执行 `settings put`**，
 * shell 对三个 namespace 都有写权限，且不需要任何运行时授权。
 *
 * 读同理：`settings get` 一次拿回字符串，比反射 `Settings.System.getString`
 * 更稳（后者对 `secure` 需要额外的权限声明）。
 *
 * ## 合并读取
 *
 * 每个开关独立 `settings get` 会产生 N 次命令往返（daemon 通道下每次约 0.1 秒）。
 * 这里沿用 [SystemExtrasDataSource] 的做法：**一条命令读全部**，用分隔符切开。
 */
object SystemTogglesDataSource {

    private const val SEP = "###OSPLUS_SEP###"

    /** 动画缩放档位。值直接就是 `settings` 里存的浮点字符串 */
    val ANIMATION_SCALES = listOf("0.5" to "0.5x", "1.0" to "1x", "1.5" to "1.5x", "2.0" to "2x")

    /** 动画缩放对应的三个键，必须同时写，否则会出现「窗口动画正常、过渡动画卡顿」 */
    private val ANIMATION_KEYS = listOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
    )

    /**
     * 可隐藏的状态栏图标。
     *
     * `icon_blacklist` 是一个逗号分隔的集合，写进去的图标会被隐藏。
     * 取值来自 AOSP `StatusBarIconController` 的常量名，各 ROM 略有出入，
     * 这里只列出实测存在的常见项。
     */
    val ICON_BLACKLIST_ITEMS = listOf(
        "mobile" to "手机信号",
        "wifi" to "WiFi",
        "battery" to "电池",
        "clock" to "时钟",
        "alarm_clock" to "闹钟",
        "rotate" to "旋转",
        "headset" to "耳机",
        "volume" to "音量",
        "bluetooth" to "蓝牙",
        "location" to "定位",
        "cast" to "投屏",
        "hotspot" to "热点",
        "nfc" to "NFC",
        "vpn" to "VPN",
        "screenshot" to "截屏",
    )

    /**
     * 一个开关的完整定义：namespace + 键 + 默认值 + 写入方式。
     *
     * [writeMode] 区分三种写入语义，因为 `settings put` 对不同键的期望不一致：
     * - [WriteMode.BOOL]：写 `0` / `1`（多数开关）
     * - [WriteMode.RAW]：写原值（动画缩放写 `0.5`，是浮点串）
     * - [WriteMode.SET]：读改写集合（`icon_blacklist`），需要先拆再拼
     */
    enum class WriteMode { BOOL, RAW, SET }

    /**
     * 一个开关的静态定义。
     *
     * @param id 稳定标识，界面与状态 map 都用它做 key
     * @param label 显示名
     * @param desc 说明文案，直接显示，不做二次加工
     * @param namespace `system` / `secure` / `global`
     * @param key settings 里的键名
     * @param mode 写入语义
     * @param defaultValue 节点读不到时显示的默认值
     */
    data class ToggleDef(
        val id: String,
        val label: String,
        val desc: String,
        val namespace: String,
        val key: String,
        val mode: WriteMode = WriteMode.BOOL,
        val defaultValue: String = "0",
    )

    /**
     * 全部开关定义。
     *
     * `id` 与 `key` 分开，是为了界面能显示比键名更可读的标签，
     * 同时状态 map 用 id 做 key 不会和别的开关撞。
     */
    val TOGGLES: List<ToggleDef> = listOf(
        ToggleDef(
            id = "show_touches",
            label = "显示点按操作",
            desc = "触摸屏幕时显示一个小圆点，录屏或演示时用。",
            namespace = "system",
            key = "show_touches",
        ),
        ToggleDef(
            id = "pointer_location",
            label = "指针位置",
            desc = "在屏幕顶部显示触摸坐标，调试触控范围时用。",
            namespace = "system",
            key = "pointer_location",
        ),
        ToggleDef(
            id = "force_gpu",
            label = "强制 GPU 渲染",
            desc = "强制 2D 界面走 GPU 合成。可减轻 CPU 负担，但部分老应用可能显示异常，系统也可能自动忽略。",
            namespace = "global",
            key = "force_gpu_rendering",
        ),
        ToggleDef(
            id = "freeform",
            label = "自由窗口",
            desc = "允许应用以可拖动的浮动窗口打开（需要在最近任务里手动选择分屏/自由窗口模式）。",
            namespace = "global",
            key = "enable_freeform_support",
        ),
        ToggleDef(
            id = "force_resizable",
            label = "强制可调整大小",
            desc = "让未声明支持分屏的应用也能进入分屏或自由窗口。部分应用会以错误的布局呈现。",
            namespace = "global",
            key = "force_resizable_activities",
        ),
        ToggleDef(
            id = "adb_over_network",
            label = "网络 ADB",
            desc = "允许通过 TCP 连接 adb。调试完成后建议关闭，避免被同网段设备连接。",
            namespace = "global",
            key = "adb_enabled",
        ),
    )

    /** 读回的原始值：id → 字符串（未做类型解释） */
    typealias RawValues = Map<String, String>

    /**
     * 一次命令读回全部开关。
     *
     * 每条 `settings get` 单独一行、值也可能含空格，因此用「键:值」格式加分隔符输出：
     * `echo -n "<id>"; settings get <ns> <key>` 会有换行歧义，
     * 这里改成显式拼 `id=value`，再按行解析。
     */
    suspend fun readAll(): RawValues {
        val cmd = buildString {
            // 动画缩放单独处理（三个键，值是浮点）
            ANIMATION_KEYS.forEach { k ->
                append("printf '%s=%s\\n' '$k' \"\$(settings get global $k)\"\n")
            }
            TOGGLES.forEach { t ->
                append("printf '%s=%s\\n' '${t.id}' \"\$(settings get ${t.namespace} ${t.key})\"\n")
            }
            // 状态栏黑名单
            append("printf '%s=%s\\n' 'icon_blacklist' \"\$(settings get secure icon_blacklist)\"\n")
        }
        val out = Shell.run(cmd, root = true).stdout
        if (out.isBlank()) return emptyMap()

        val map = HashMap<String, String>()
        out.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val k = line.substring(0, idx).trim()
            var v = line.substring(idx + 1).trim()
            // `settings get` 对不存在的键会打印字面量 "null"
            if (v == "null" || v.isEmpty()) v = ""
            if (k.isNotEmpty()) map[k] = v
        }
        return map
    }

    /**
     * 读取原始值并解释成布尔。
     *
     * `settings` 里布尔值不统一：多数是 `0`/`1`，少数 ROM 写 `true`/`false`。
     * 两种都认。空串或 `null` 视为未设置，回落到 [ToggleDef.defaultValue]。
     */
    fun boolOf(raw: RawValues, def: ToggleDef): Boolean {
        val v = raw[def.id].orEmpty().ifBlank { def.defaultValue }
        return when (v.lowercase()) {
            "1", "true", "on", "yes" -> true
            else -> false
        }
    }

    /** 读当前动画缩放值；三个键不一致时取第一个非空值 */
    fun animationScale(raw: RawValues): String {
        ANIMATION_KEYS.forEach { k ->
            val v = raw[k].orEmpty()
            if (v.isNotBlank() && v != "null") return v
        }
        return "1.0"
    }

    /** 读状态栏黑名单，拆成集合 */
    fun iconBlacklist(raw: RawValues): Set<String> =
        raw["icon_blacklist"].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** 写一个布尔开关 */
    suspend fun writeBool(def: ToggleDef, enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val r = Shell.run("settings put ${def.namespace} ${def.key} $value", root = true)
        return r.success
    }

    /** 写动画缩放（三个键一起写） */
    suspend fun writeAnimationScale(scale: String): Boolean {
        val cmd = ANIMATION_KEYS.joinToString("\n") { "settings put global $it $scale" }
        return Shell.run(cmd, root = true).success
    }

    /**
     * 写状态栏黑名单。
     *
     * **必须整体写**：`icon_blacklist` 是一个集合，`settings put` 是覆盖语义，
     * 只写新增项会把原有项全部清掉。所以界面传进来的必须是完整的待隐藏集合。
     */
    suspend fun writeIconBlacklist(items: Set<String>): Boolean {
        val v = items.joinToString(",")
        val r = Shell.run("settings put secure icon_blacklist '$v'", root = true)
        return r.success
    }
}
