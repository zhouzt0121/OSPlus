package com.osplus.tools.core

import android.util.Base64

/**
 * 分应用电源模式规则（Uperf 的 `perapp_powermode.txt` 一行）。
 *
 * [target] 为包名，或两个特殊标记：`*` 默认规则、`-` 息屏规则。
 */
data class PerAppRule(val target: String, val mode: String)

/** 分游戏线程亲和规则（A-SOUL 的 `asopt.conf` 一行：包名 mode rt） */
data class AsoulGame(val pkg: String, val mode: String, val rt: String)

/** Uperf Game Turbo 的运行状态快照 */
data class UperfState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val pid: String = "",
    val mode: String = "",
    val socName: String = "",
    val rules: List<PerAppRule> = emptyList(),
)

/** A-SOUL Games Optimization 的运行状态快照 */
data class AsoulState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val pid: String = "",
    val mode: String = "0",
    val rt: String = "0",
    val games: List<AsoulGame> = emptyList(),
)

/** 一次调度操作的执行结果，用于界面回执 */
data class SchedResult(val ok: Boolean, val message: String)

/**
 * 性能调度数据源：对接 Uperf Game Turbo 与 A-SOUL Games Optimization 两个 Magisk 模块。
 *
 * 设计原则是**复用模块自身的状态文件，而不是另起一套控制通道**：
 *
 * | 目标 | 落点 | 依据 |
 * |---|---|---|
 * | Uperf 电源档位 | `Android/yc/uperf/cur_powermode.txt` | 该文件即 uperf.json 的 `switcher.switchInode`，守护进程用 inotify 监听它；模块自带 `script/powercfg_main.sh` 的全部逻辑也只是往它写一行 |
 * | Uperf 分应用规则 | `Android/yc/uperf/perapp_powermode.txt` | uperf.json 的 `switcher.perapp` |
 * | A-SOUL 全局与分游戏 | `/data/adb/naki/asopt.conf` | 模块 `customize.sh` 生成的同一份配置，`AsoulOpt` 启动时读取 |
 *
 * 因此本应用改动的是模块**原本就在用的**配置文件，与刷入的模块、以及模块自身的
 * WebUI 三者互相兼容，不存在两套配置打架的问题。
 *
 * 读取用一次合并的 root 命令（带 `##` 段标记）完成，避免为每个字段各起一次 `su`。
 */
object PerfSchedDataSource {

    /** uperf 用户目录。`/data/media/0` 是 `/sdcard` 背后的真实路径，root 直读更稳。 */
    private const val UPERF_DIR = "/data/media/0/Android/yc/uperf"
    private const val UPERF_DIR_ALT = "/sdcard/Android/yc/uperf"

    private const val UPERF_MODULE = "/data/adb/modules/uperf"
    private const val UPERF_BIN = "/data/adb/modules/uperf/bin/uperf"

    private const val ASOUL_CONF = "/data/adb/naki/asopt.conf"
    private const val ASOUL_MODULE = "/data/adb/modules/asoul_affinity_opt"

    /**
     * Uperf 电源档位。
     *
     * 取值与模块 `script/powercfg_main.sh` 的 case 分支严格一致
     * （powersave / balance / performance / fast / auto，以及 pedestal 对应的 crazy）——
     * 不能凭常见调频器名字臆造，否则写进去的状态文件 uperf 认不出来，档位会静默不变。
     */
    val UPERF_MODES = listOf(
        "powersave" to "省电",
        "balance" to "均衡",
        "performance" to "性能",
        "fast" to "极速",
        "auto" to "自动",
        "crazy" to "疯狂",
    )

    /** 分应用规则可用档位：crazy 属全局 pedestal 专用档，不下放到单应用 */
    val PER_APP_MODES = listOf("powersave", "balance", "performance", "fast", "auto")

    /** A-SOUL 运行模式 */
    val ASOUL_MODES = listOf("0" to "硬亲和", "1" to "软迁移", "2" to "硬迁移")

    /** A-SOUL 实时模式 */
    val ASOUL_RTS = listOf("0" to "默认", "1" to "实时")

    fun uperfModeLabel(id: String): String =
        UPERF_MODES.firstOrNull { it.first == id }?.second ?: id

    fun asoulModeLabel(id: String): String =
        ASOUL_MODES.firstOrNull { it.first == id }?.second ?: id

    fun asoulRtLabel(id: String): String =
        ASOUL_RTS.firstOrNull { it.first == id }?.second ?: id

    // ------------------------------------------------------------------ 读取

    /** 一次性读取两个模块的状态 */
    suspend fun read(): Pair<UperfState, AsoulState> {
        val script = buildString {
            append("U=").append(UPERF_DIR).append('\n')
            append("[ -d \"\$U\" ] || U=").append(UPERF_DIR_ALT).append('\n')

            append("echo '##UP_INST'\n")
            append("[ -d ").append(UPERF_MODULE).append(" ] && echo 1 || echo 0\n")
            append("echo '##UP_PID'\n")
            append("pidof uperf 2>/dev/null\n")
            append("echo '##UP_MODE'\n")
            append("cat \"\$U/cur_powermode.txt\" 2>/dev/null\n")
            append("echo '##UP_SOC'\n")
            // uperf.json 是缩进格式，"name" 独占一行，取 meta.name（SoC 型号）
            append("grep -m1 '\"name\"' \"\$U/uperf.json\" 2>/dev/null")
            append(" | sed 's/.*: *\"//; s/\".*//'\n")
            append("echo '##UP_RULES'\n")
            append("cat \"\$U/perapp_powermode.txt\" 2>/dev/null\n")
            append("echo '##UP_END'\n")

            append("echo '##AS_INST'\n")
            append("[ -d ").append(ASOUL_MODULE).append(" ] && echo 1 || echo 0\n")
            append("echo '##AS_PID'\n")
            append("pidof AsoulOpt 2>/dev/null\n")
            append("echo '##AS_CONF'\n")
            append("cat ").append(ASOUL_CONF).append(" 2>/dev/null\n")
            append("echo '##AS_END'\n")
        }

        val sections = parseSections(Shell.run(script).stdout)

        val upPid = firstLine(sections["UP_PID"])
        val upMode = firstToken(firstLine(sections["UP_MODE"]))
        val upSoc = firstLine(sections["UP_SOC"])

        val uperf = UperfState(
            installed = firstLine(sections["UP_INST"]) == "1",
            running = upPid.isNotEmpty(),
            pid = firstToken(upPid),
            mode = upMode,
            socName = upSoc,
            rules = parsePerApp(sections["UP_RULES"].orEmpty()),
        )

        val conf = sections["AS_CONF"].orEmpty()
        val asoulPid = firstLine(sections["AS_PID"])
        val parsed = parseAsoulConf(conf)

        val asoul = AsoulState(
            installed = firstLine(sections["AS_INST"]) == "1",
            running = asoulPid.isNotEmpty(),
            pid = firstToken(asoulPid),
            mode = parsed.first,
            rt = parsed.second,
            games = parsed.third,
        )

        return uperf to asoul
    }

    // ------------------------------------------------------------------ Uperf

    /**
     * 切换 Uperf 电源档位。
     *
     * 双路径写入：先写 `/data/media/0/...`（uperf 实际监听的那个 inode），
     * 再写 `/sdcard/...` 映射路径；两条路径在 FUSE 与直连两种挂载视图下都覆盖到，
     * 保证 inotify 一定被触发。写后立即回读校验，不一致时如实报告，
     * 避免出现「点了按钮但档位没变」的静默失败。
     */
    suspend fun setUperfMode(mode: String): SchedResult {
        if (UPERF_MODES.none { it.first == mode }) {
            return SchedResult(false, "未知档位：$mode")
        }
        val cmd = buildString {
            append("printf '%s\\n' '").append(mode).append("' > ").append(UPERF_DIR)
                .append("/cur_powermode.txt 2>/dev/null\n")
            append("printf '%s\\n' '").append(mode).append("' > ").append(UPERF_DIR_ALT)
                .append("/cur_powermode.txt 2>/dev/null\n")
            append("cat ").append(UPERF_DIR).append("/cur_powermode.txt 2>/dev/null")
            append(" || cat ").append(UPERF_DIR_ALT).append("/cur_powermode.txt 2>/dev/null")
        }
        val back = firstToken(firstLine(Shell.run(cmd).stdout))
        return when {
            back == mode -> SchedResult(true, "已切换到「${uperfModeLabel(mode)}」")
            back.isEmpty() -> SchedResult(false, "写入失败：状态文件不可写，请确认 Uperf 已安装并授予 root")
            else -> SchedResult(false, "未生效：内核回读仍为「${uperfModeLabel(back)}」")
        }
    }

    /** 写回分应用规则文件 */
    suspend fun setUperfPerApp(rules: List<PerAppRule>): SchedResult {
        val path = resolveUperfPath("perapp_powermode.txt")
        val ok = writeRootFile(path, serializePerApp(rules))
        return if (ok) SchedResult(true, "分应用规则已保存（${rules.size} 条）")
        else SchedResult(false, "保存失败：$path 不可写")
    }

    /**
     * 重启 uperf 守护进程。
     *
     * 不能直接调用模块的 `script/initsvc.sh`：其中 `uperf_start()` 以**前台**方式
     * 执行 `$BIN_PATH/uperf`，会永久阻塞在该守护进程上（Magisk 的 service.sh 本身就是
     * 后台执行、允许阻塞），同步调用会挂死并且会再拉起第二个实例。
     * 这里只做「终止 + 分离重启」，等价于 `uperf_start` 去掉一次性的系统统一化步骤
     * （inotify 上限、cgroup 归置在开机时已生效，无需重做）。
     */
    suspend fun restartUperf(): SchedResult {
        val cmd = buildString {
            append("M=").append(UPERF_MODULE).append('\n')
            append("U=").append(UPERF_DIR).append('\n')
            append("[ -f \"\$U/uperf.json\" ] || U=").append(UPERF_DIR_ALT).append('\n')
            append("[ -x \"").append(UPERF_BIN).append("\" ] || { echo __NOBIN__; exit 0; }\n")
            append("killall uperf 2>/dev/null\n")
            append("sleep 1\n")
            append("cd \"\$M/bin\" || exit 1\n")
            append("S=\"\"\n")
            append("command -v setsid >/dev/null 2>&1 && S=setsid\n")
            append("PATH=\"\$M/bin/busybox:\$PATH\" nohup \$S ./uperf \"\$U/uperf.json\"")
            append(" -o \"\$U/uperf_log.txt\" >/dev/null 2>&1 &\n")
            append("sleep 2\n")
            append("pidof uperf 2>/dev/null\n")
        }
        val out = Shell.run(cmd).stdout
        if (out.contains("__NOBIN__")) {
            return SchedResult(false, "未找到 $UPERF_BIN，请确认模块已完整安装")
        }
        val pid = firstToken(firstLine(out))
        return if (pid.isNotEmpty()) SchedResult(true, "已重启 uperf（PID $pid）")
        else SchedResult(false, "重启命令已下发，但未检测到进程，请检查日志")
    }

    /** 读取 uperf 日志尾部，用于排障 */
    suspend fun tailUperfLog(lines: Int = 120): String {
        val n = lines.coerceIn(10, 500)
        val cmd = "tail -n $n $UPERF_DIR/uperf_log.txt 2>/dev/null " +
            "|| tail -n $n $UPERF_DIR_ALT/uperf_log.txt 2>/dev/null"
        return Shell.run(cmd).stdout.trim()
    }

    // ------------------------------------------------------------------ A-SOUL

    /** 写入 A-SOUL 全局档位与分游戏规则，并重启 AsoulOpt 使配置生效 */
    suspend fun setAsoul(mode: String, rt: String, games: List<AsoulGame>): SchedResult {
        if (ASOUL_MODES.none { it.first == mode }) return SchedResult(false, "未知运行模式：$mode")
        if (ASOUL_RTS.none { it.first == rt }) return SchedResult(false, "未知实时模式：$rt")

        val ok = writeRootFile(ASOUL_CONF, serializeAsoul(mode, rt, games))
        if (!ok) return SchedResult(false, "保存失败：$ASOUL_CONF 不可写")

        val restart = restartAsoul()
        return if (restart.ok) SchedResult(true, "已保存并重启 AsoulOpt（${games.size} 个游戏）")
        else SchedResult(true, "配置已保存；${restart.message}")
    }

    /**
     * 重启 AsoulOpt。
     *
     * 直接复用模块自带 `service.sh`——它会重新上锁 core_ctl、再以 nohup 后台拉起
     * AsoulOpt 后立即返回（脚本最后一行是 `... &`），因此可以同步调用而不会挂起。
     * 仍加一层 `[ -d /data/data/android ]` 前置判断，规避脚本内 `until` 等待循环在
     * 异常环境下的死等。
     */
    suspend fun restartAsoul(): SchedResult {
        val cmd = "[ -x $ASOUL_MODULE/service.sh ] || { echo __NOSVC__; exit 0; }\n" +
            "[ -d /data/data/android ] && sh $ASOUL_MODULE/service.sh >/dev/null 2>&1\n" +
            "sleep 1\n" +
            "pidof AsoulOpt 2>/dev/null"
        val out = Shell.run(cmd).stdout
        if (out.contains("__NOSVC__")) return SchedResult(false, "未找到 $ASOUL_MODULE/service.sh")
        val pid = firstToken(firstLine(out))
        return if (pid.isNotEmpty()) SchedResult(true, "AsoulOpt 已重启（PID $pid）")
        else SchedResult(false, "未检测到 AsoulOpt 进程，请确认模块已完整安装")
    }

    // ------------------------------------------------------------------ 内部

    /** 按 `##段名` 把合并命令的输出切成若干段 */
    private fun parseSections(out: String): Map<String, String> {
        val map = LinkedHashMap<String, StringBuilder>()
        var current: String? = null
        out.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith("##")) {
                val name = t.removePrefix("##")
                current = name
                map.getOrPut(name) { StringBuilder() }
            } else {
                val key = current
                if (key != null) {
                    map.getOrPut(key) { StringBuilder() }.append(line).append('\n')
                }
            }
        }
        return map.mapValues { it.value.toString() }
    }

    private fun firstLine(section: String?): String =
        section.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun firstToken(s: String): String =
        s.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

    private fun parsePerApp(text: String): List<PerAppRule> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val p = line.split(Regex("\\s+"))
                if (p.size >= 2) PerAppRule(p[0], p[1]) else null
            }
            .toList()

    /** 解析 asopt.conf：返回 (全局 mode, 全局 rt, 分游戏列表) */
    private fun parseAsoulConf(text: String): Triple<String, String, List<AsoulGame>> {
        var mode = "0"
        var rt = "0"
        val games = mutableListOf<AsoulGame>()

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            Regex("^mode\\s*=\\s*(\\d+)").find(line)?.let {
                mode = it.groupValues[1]
                return@forEach
            }
            Regex("^rt\\s*=\\s*(\\d+)").find(line)?.let {
                rt = it.groupValues[1]
                return@forEach
            }
            Regex("^(\\S+)\\s+(\\d+)\\s+(\\d+)$").find(line)?.let {
                games += AsoulGame(it.groupValues[1], it.groupValues[2], it.groupValues[3])
            }
        }
        return Triple(mode, rt, games)
    }

    /** 序列化分应用规则：普通规则在前，`*` / `-` 两条特殊规则固定在末尾 */
    private fun serializePerApp(rules: List<PerAppRule>): String = buildString {
        append("# 分应用性能模式配置 / Per-app dynamic power mode rule\n")
        append("# 由 OSPlus 写入；\"-\" 为息屏规则，\"*\" 为默认规则\n")
        append("# 格式：<包名> <模式>\n")
        rules.filterNot { it.target == "-" || it.target == "*" }.forEach {
            append(it.target).append(' ').append(it.mode).append('\n')
        }
        rules.filter { it.target == "-" || it.target == "*" }.forEach {
            append(it.target).append(' ').append(it.mode).append('\n')
        }
    }

    /** 序列化 asopt.conf，字段格式与模块 customize.sh 生成的完全一致 */
    private fun serializeAsoul(mode: String, rt: String, games: List<AsoulGame>): String = buildString {
        append("# A-SOUL Games Optimization 配置（由 OSPlus 写入）\n")
        append("# mode 0=硬亲和 1=软迁移 2=硬迁移；rt 0=默认 1=实时\n")
        append("# 分游戏格式：<包名> <mode> <rt>\n")
        append("mode=").append(mode).append('\n')
        append("rt=").append(rt).append('\n')
        games.forEach { append(it.pkg).append(' ').append(it.mode).append(' ').append(it.rt).append('\n') }
    }

    /** 在两条候选路径中挑出实际存在的那条（文件不存在时返回主路径） */
    private suspend fun resolveUperfPath(name: String): String {
        val probe = "for p in $UPERF_DIR $UPERF_DIR_ALT; do " +
            "[ -d \"\$p\" ] && { echo \"\$p/$name\"; exit 0; }; done; " +
            "echo \"$UPERF_DIR/$name\""
        val out = Shell.run(probe).stdout.trim().lineSequence().firstOrNull().orEmpty().trim()
        return out.ifEmpty { "$UPERF_DIR/$name" }
    }

    /**
     * 以 root 写入文本文件。
     *
     * 走 base64 中转而非直接拼 `echo`：配置文件含换行与中文注释，
     * 直接拼接会被 shell 的引号解析破坏。写入结果用哨兵字符串判定，
     * 而不是只看退出码——`sh -c` 的退出码在重定向失败时并不可靠。
     */
    private suspend fun writeRootFile(path: String, content: String): Boolean {
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val dir = path.substringBeforeLast('/')
        val cmd = "mkdir -p '$dir' 2>/dev/null; " +
            "{ printf %s '$b64' | base64 -d > '$path'; } 2>/dev/null " +
            "&& echo __OK__ || echo __FAIL__"
        return Shell.run(cmd).stdout.contains("__OK__")
    }
}
