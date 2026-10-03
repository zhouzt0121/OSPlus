package com.osplus.tools.core

/**
 * 提权通道。
 *
 * OSPlus 的全部控制能力最终都要落到一条「以更高身份执行 shell 命令」的通道上。
 * 不同设备能拿到的最高身份不同，这里把它建模成三种模式：
 *
 * | 模式 | 身份 | 能力边界 |
 * |---|---|---|
 * | [ROOT] | uid 0 | 无限制 |
 * | [SHIZUKU] | uid 2000 (shell) | 见下方边界 |
 * | [ADB] | uid 2000 (shell) | 同 [SHIZUKU] |
 *
 * **[ADB] 有两条激活路径，但它们是同一个模式** —— 拿到的都是 shell 身份、
 * 能力完全相同，区别只在「怎么连上」。因此不拆成两个模式，
 * 而是由 [com.osplus.tools.core.PrivilegeManager] 按可用性自动挑一条：
 *
 * 1. **无线**：应用内自行完成无线调试配对（SPAKE2 + TLS）。连上之后应用
 *    自己就是 ADB 客户端，延迟低，是首选。
 * 2. **电脑**：用数据线连电脑跑一次 `up.sh`，拉起一个常驻 daemon，
 *    应用通过文件与它通信（见 [com.osplus.tools.core.adb.DaemonBackend]）。
 *    约 0.2 秒延迟，用于没有 WiFi / 无法配对的场景。
 *
 * **关键差异（务必理解，否则会写出必然失败的功能）**：
 * shell 身份在 Android 上属于 `shell` SELinux 域，能读 `/sys`、`/proc`、跑 `dumpsys`，
 * 但没有 `su` 那样的全域豁免——**`/data/adb/` 整个目录对 shell 不可读**。
 * 而 OSPlus 的「性能调度」页对接的 Uperf / A-SOUL 两个 Magisk 模块，
 * 状态文件恰好都在 `/data/adb` 下，因此该页在非 Root 模式下**必然失效**。
 * 这不是可以绕过的实现问题，是权限模型本身的结果，只能如实降级。
 *
 * 另：CPU 调频节点的写入同样只有 Root 可行，见
 * [PrivilegeCapabilities.canWriteSysfs] 中记录的实测证据。
 */
enum class PrivilegeMode(val label: String, val shortLabel: String) {
    ROOT("Root 权限", "Root"),
    SHIZUKU("Shizuku", "Shizuku"),
    ADB("ADB 调试", "ADB"),
    ;

    /** 是否具备不受限的全域读写（只有 root 是） */
    val isUnrestricted: Boolean get() = this == ROOT

    companion object {
        /**
         * 历史存储值兼容。
         *
         * 中途曾把 ADB 拆成 `ADB_WIFI` / `ADB_USB` 两个枚举，后来合并回一个 ——
         * 两者身份与能力完全相同，拆开只是让界面多一个没有意义的选择。
         * 已存过旧名字的用户要能读回 [ADB]，否则会掉回「未提权」。
         */
        private val LEGACY_NAMES = mapOf(
            "ADB" to ADB,
            "ADB_WIFI" to ADB,
            "ADB_USB" to ADB,
        )

        fun fromName(name: String?): PrivilegeMode? {
            if (name.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: LEGACY_NAMES[name.trim().uppercase()]
        }
    }
}

/**
 * 当前模式下各项功能的可用性。
 *
 * 界面按这份能力表决定控件是否可点，而不是到处写 `if (isRoot)`——
 * 后者在新增模式时必然漏改，且无法解释「为什么这个按钮点不动」。
 */
data class PrivilegeCapabilities(
    val mode: PrivilegeMode?,
    /** 通道是否已就绪（root 已授权 / Shizuku 已运行且已授权 / ADB 已配对连接） */
    val available: Boolean,
) {
    /** 信息读取：/proc/stat、/sys 下各节点。shell 身份即可 */
    val canReadSystemNodes: Boolean get() = available

    /**
     * 写入 CPU 调频节点（`scaling_governor` / `scaling_min_freq` / `scaling_max_freq`）。
     *
     * **只有 Root 可用。** 这曾在设计阶段被误判为「shell 身份即可」，
     * 实测（SM8750 / Android 17 / uid 2000）为 `Permission denied`：
     *
     * ```
     * -rw-r--r-- 1 root root  ... scaling_governor
     * -r--r--r-- 1 root root  ... scaling_max_freq
     * u:object_r:sysfs_devices_system_cpu:s0
     * ```
     *
     * 节点属主是 root、SELinux 上下文是 `sysfs_devices_system_cpu`，
     * shell 域对该标签没有 write 权限。**频率锁定与调速器切换因此不能下放到 shell 模式。**
     */
    val canWriteSysfs: Boolean get() = available && mode == PrivilegeMode.ROOT

    /** 结束进程 / 强制停止应用。shell 身份即可（实测 `am force-stop` 可用） */
    val canManageProcesses: Boolean get() = available

    /**
     * 性能调度（Uperf / A-SOUL 模块状态读写）。
     *
     * **只有 Root 可用**——实测 shell 身份读 `/data/adb` 直接被拒。
     */
    val canControlPerfSched: Boolean get() = available && mode == PrivilegeMode.ROOT

    /** 频率控制不可用时给用户的解释文案 */
    fun reasonForFreqControl(): String = when {
        !available -> "提权通道未就绪：请先在「设置」中完成授权"
        else -> "CPU 调频节点的 SELinux 标签为 sysfs_devices_system_cpu，属主 root，" +
            "shell 域（uid 2000）没有写权限，实测写入返回 Permission denied。" +
            "因此频率锁定与调速器切换需要 Root；" +
            "${mode?.shortLabel ?: "当前模式"} 下仅能读取频率。"
    }

    /** 该功能不可用时给用户的解释文案，直接拿去显示，不做二次加工 */
    fun reasonForPerfSched(): String = when {
        !available -> "提权通道未就绪：请先在「设置」中完成授权"
        else -> "该功能读写 Magisk 模块文件（/data/adb/modules、/data/adb/naki），" +
            "这些路径属于 root 专属域，${mode?.shortLabel ?: "当前模式"} 的 shell 身份无法访问" +
            "（实测 shell 读取 /data/adb 返回 Permission denied）。" +
            "如需使用请切换到 Root 模式。"
    }

    companion object {
        val NONE = PrivilegeCapabilities(null, false)
    }
}
