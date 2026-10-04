package com.osplus.tools.core

/**
 * 提权通道。
 *
 * OSPlus 的全部控制能力最终都要落到一条「以更高身份执行 shell 命令」的通道上。
 * 目前只剩一条通道：[ROOT]。
 *
 * ## 为什么去掉了 Shizuku 与 ADB
 *
 * 2.2.0 曾把 Shizuku（uid 2000）与 ADB（无线配对 / 电脑脚本 daemon）作为
 * 非 Root 设备的替代通道引入，期望覆盖「能解锁但不想装 Magisk」的机型。
 * **实测证明这两个通道在 OSPlus 的实际功能需求下不可用**：
 *
 * 1. **调频节点写不了**。CPU 调频节点（`scaling_governor` /
 *    `scaling_min_freq` / `scaling_max_freq`）属主 root、SELinux 上下文为
 *    `sysfs_devices_system_cpu`，shell 域没有 write 权限，实测返回
 *    `Permission denied`。频率锁定与调速器切换因此完全失效。
 * 2. **Magisk 模块目录读不到**。性能调度页对接的 Uperf / A-SOUL 状态文件在
 *    `/data/adb` 下，该目录对 shell 身份不可搜索，整个页面必然失效。
 * 3. **帧率节点同样读不到**。`measured_fps` 在 shell 域下是 `Permission denied`，
 *    `service call SurfaceFlinger` 是 `Operation not permitted` —— 与 root
 *    下的行为完全一致地失败。
 *
 * 这三点都不是实现问题，而是权限模型本身的结果：换任何 daemon 机制都绕不过
 * SELinux。保留一条「点得动但功能全都不生效」的通道，只会让用户以为应用坏了。
 * 因此把两条通道连同其依赖一并移除，只保留真正可用的 [ROOT]。
 *
 * 历史存储值（Shizuku / ADB 等）由 [fromName] 安全地落回 null，
 * 老用户升级后不会被卡在一条已不存在的通道上。
 */
enum class PrivilegeMode(val label: String, val shortLabel: String) {
    ROOT("Root 权限", "Root"),
    ;

    /** 是否具备不受限的全域读写（只有 root 是） */
    val isUnrestricted: Boolean get() = this == ROOT

    companion object {
        /**
         * 历史存储值兼容。
         *
         * 曾存在过 `SHIZUKU` / `ADB` / `ADB_WIFI` / `ADB_USB` 四个名字，
         * 现已全部移除。这里把它们显式映射到 null（而非报错），
         * 使升级用户的选择安全地失效并回落到「未提权」，
         * 再由界面引导重新选择 —— 总好过让枚举反序列化直接抛异常。
         */
        private val REMOVED_NAMES = setOf(
            "SHIZUKU",
            "ADB",
            "ADB_WIFI",
            "ADB_USB",
        )

        fun fromName(name: String?): PrivilegeMode? {
            if (name.isNullOrBlank()) return null
            val trimmed = name.trim().uppercase()
            if (trimmed in REMOVED_NAMES) return null
            return entries.firstOrNull { it.name == trimmed }
        }
    }
}

/**
 * 当前模式下各项功能的可用性。
 *
 * 界面按这份能力表决定控件是否可点，而不是到处写 `if (isRoot)`——
 * 后者在新增模式时必然漏改，且无法解释「为什么这个按钮点不动」。
 *
 * 只保留 Root 通道后，[mode] 非 null 即意味着 root 已授权，
 * 全部能力都可用；各字段仍各自判断，是为了让界面逻辑不依赖
 * 「只有一种模式」这一当前事实 —— 将来若再引入通道，这里不用改。
 */
data class PrivilegeCapabilities(
    val mode: PrivilegeMode?,
    /** 通道是否已就绪（root 已授权） */
    val available: Boolean,
) {
    /** 信息读取：/proc/stat、/sys 下各节点 */
    val canReadSystemNodes: Boolean get() = available

    /**
     * 写入 CPU 调频节点（`scaling_governor` / `scaling_min_freq` / `scaling_max_freq`）。
     *
     * **只有 Root 可用。** 这曾在设计阶段被误判为「shell 身份即可」，
     * 实测（SM8750 / Android 15 / uid 2000）为 `Permission denied`：
     *
     * ```
     * -rw-r--r-- 1 root root  ... scaling_governor
     * -r--r--r-- 1 root root  ... scaling_max_freq
     * u:object_r:sysfs_devices_system_cpu:s0
     * ```
     *
     * 节点属主是 root、SELinux 上下文是 `sysfs_devices_system_cpu`，
     * shell 域对该标签没有 write 权限。这也是移除 Shizuku / ADB 通道的
     * 首要原因之一 —— 见 [PrivilegeMode] 的说明。
     */
    val canWriteSysfs: Boolean get() = available && mode == PrivilegeMode.ROOT

    /** 结束进程 / 强制停止应用 */
    val canManageProcesses: Boolean get() = available

    /**
     * 性能调度（Uperf / A-SOUL 模块状态读写）。
     *
     * **只有 Root 可用**——读写的是 `/data/adb/modules`、`/data/adb/naki`，
     * 这些路径属于 root 专属域，shell 身份读不到。
     */
    val canControlPerfSched: Boolean get() = available && mode == PrivilegeMode.ROOT

    /** 频率控制不可用时给用户的解释文案 */
    fun reasonForFreqControl(): String = when {
        !available -> "提权通道未就绪：请先在「设置 → 提权管理」中完成 Root 授权"
        else -> "CPU 调频节点的 SELinux 标签为 sysfs_devices_system_cpu，属主 root，" +
            "非 root 身份没有写权限（实测写入返回 Permission denied）。" +
            "因此频率锁定与调速器切换需要 Root。"
    }

    /** 该功能不可用时给用户的解释文案，直接拿去显示，不做二次加工 */
    fun reasonForPerfSched(): String = when {
        !available -> "提权通道未就绪：请先在「设置 → 提权管理」中完成 Root 授权"
        else -> "该功能读写 Magisk 模块文件（/data/adb/modules、/data/adb/naki），" +
            "这些路径属于 root 专属域，需要 Root 权限。"
    }

    companion object {
        val NONE = PrivilegeCapabilities(null, false)
    }
}
