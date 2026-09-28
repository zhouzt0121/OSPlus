package com.osplus.tools.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.osplus.tools.model.BatteryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 电池与充电信息读取 */
object BatteryDataSource {

    private const val PS = "/sys/class/power_supply"
    private val batteryDirs = listOf("$PS/battery", "$PS/battery-1", "$PS/Battery")

    /** 一次 root 命令内的分段标记 */
    private const val SEP = "@@OSPLUS-BATT@@"

    /**
     * 一次性需要读取的全部电池节点。
     *
     * 列在这里是为了**合并成一次 root 命令**：逐节点各起一次 `su` 时，
     * 8 个逻辑节点 × 3 个候选目录 = 24 次 `su`，每 5 秒一轮，开销不可接受。
     */
    private val READ_NODES = listOf(
        "current_now", "battery_current_now", "current_avg",
        "temp", "capacity",
        "charge_counter", "battery_charge_counter",
        "charge_full_design", "charge_full",
        "energy_full_design",
        "charging_enabled", "battery_charging_enabled", "input_suspend",
        "cycle_count", "battery_cycle", "cycle_counts",
    )

    /**
     * 读取全部电池节点，返回「节点名 → 值」。
     *
     * **不能再用 `File.exists()` 做前置判断。** Android 12+ 对厂商 sysfs 目录的
     * SELinux 拒绝会让 `exists()` 返回 false，节点被整个跳过、永不提权——
     * 这正是「电池能量显示 0.0 Wh」的根因（`charge_full_design` 被静默跳过）。
     * `Shell.readNode` 早已修掉这个模式，这里此前漏了。
     *
     * 改用 root shell 内部的 `[ -f ]` 判断 + `cat`，既绕开 SELinux，
     * 又只需要一次 `su`。
     */
    private suspend fun readNodes(): Map<String, String> {
        val script = buildString {
            READ_NODES.forEach { name ->
                appendLine(
                    "for d in ${batteryDirs.joinToString(" ")}; do " +
                        "if [ -f \$d/$name ]; then cat \$d/$name 2>/dev/null; break; fi; " +
                        "done"
                )
                appendLine("echo '$SEP'")
            }
        }
        val out = Shell.run(script, root = true).stdout
        if (out.isBlank()) return emptyMap()
        val parts = out.split(SEP)
        return READ_NODES.mapIndexedNotNull { i, name ->
            val v = parts.getOrNull(i)?.trim().orEmpty()
            if (v.isEmpty()) null else name to v
        }.toMap()
    }

    /** 从节点表里按候选顺序取第一个有值的 */
    private fun pick(nodes: Map<String, String>, vararg names: String): String? =
        names.firstNotNullOfOrNull { nodes[it]?.takeIf { v -> v.isNotBlank() } }

    suspend fun read(context: Context): BatteryInfo = withContext(Dispatchers.IO) {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val status = when (sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "未知"
        }
        val plugged = when (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "交流充电器"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
            else -> "未连接"
        }
        val health = when (sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
            BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
            else -> "未知"
        }

        val nodes = readNodes()

        BatteryInfo(
            levelPercent = percent,
            status = status,
            health = health,
            tempC = readTempC(sticky, nodes),
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1,
            currentNowUa = pick(nodes, "current_now", "battery_current_now", "current_avg")
                ?.toIntOrNull() ?: 0,
            currentAvgUa = nodes["current_avg"]?.toIntOrNull() ?: 0,
            chargeCounterUah = readChargeCounter(sticky, nodes),
            capacityPercent = nodes["capacity"]?.toIntOrNull() ?: percent,
            technology = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty(),
            plugged = plugged,
            chargingEnabled = nodes["charging_enabled"]?.let { it == "1" }
                ?: nodes["battery_charging_enabled"]?.let { it == "1" }
                ?: nodes["input_suspend"]?.let { it == "0" },
            // 这两个节点本身就是 **µAh**，直接存，不要再除 1000。
            //
            // 曾经在这里 `?.div(1000)`，于是「设计容量」在界面上显示成 5 mAh
            // （真机 raw 为 5,000,000 µAh = 5000 mAh，被除了两次 1000）。
            // 判据：同一族的 `charge_counter` 就是 µAh（功耗求导一直按 µAh 算），
            // 而 `charge_full` 报出的 raw 值与之同量级。
            chargeFullDesignUah = nodes["charge_full_design"]?.toLongOrNull() ?: -1L,
            chargeFullUah = nodes["charge_full"]?.toLongOrNull() ?: -1L,
            energyFullDesignUwh = nodes["energy_full_design"]?.toLongOrNull() ?: -1L,
            cycleCount = pick(nodes, "cycle_count", "battery_cycle", "cycle_counts")
                ?.toIntOrNull() ?: -1,
        )
    }

    private fun readTempC(sticky: Intent?, nodes: Map<String, String>): Float? {
        val fromApi = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10f)
        if (fromApi != null) return fromApi
        val raw = nodes["temp"]?.toFloatOrNull() ?: return null
        return if (raw > 1000f) raw / 1000f else if (raw > 100f) raw / 10f else raw
    }

    /** 累计电量计数（µAh），不可用时返回 -1 */
    private fun readChargeCounter(sticky: Intent?, nodes: Map<String, String>): Long {
        pick(nodes, "charge_counter", "battery_charge_counter")?.toLongOrNull()?.let { return it }
        return -1L
    }

    /**
     * 锂离子电池的标称电压。
     *
     * 只作为**最后兜底**：优先用「剩余能量 ÷ 电量%」反推额定能量，
     * 那样得到的口径与厂商上报一致；标称电压法会因电芯体系（3.85 / 3.87 / 3.9 V）
     * 不同而引入几个百分点的系统偏差。
     */
    private const val NOMINAL_VOLTAGE_V = 3.85f

    /**
     * 电池能量（Wh）。
     *
     * [BatteryEnergy.remainWh] 优先直接取
     * `BatteryManager.getLongProperty(BATTERY_PROPERTY_ENERGY_COUNTER)`——
     * 该属性以 **nWh** 为单位、API 21+ 可用，是系统侧唯一「直接测量」的能量读数。
     * 注意必须用 `getLongProperty`：`getIntProperty` 返回 int，
     * 28 Wh 折合 2.8e10 nWh 会**溢出**，拿到的是一个垃圾值。
     *
     * [BatteryEnergy.fullWh] 按可靠性依次回落：
     * ① 剩余能量 ÷ 电量% ② `energy_full_design`（µWh）③ 设计容量 × 标称电压
     * ④ 当前满电容量 × 标称电压。任一条能算出就返回，全都算不出才给 0。
     */
    fun readEnergy(context: Context, battery: com.osplus.tools.model.BatteryInfo): BatteryEnergy {
        val levelPercent = battery.levelPercent
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val counterNwh = runCatching {
            bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) ?: 0L
        }.getOrDefault(0L)
        val measured = counterNwh > 0L
        val remainFromCounter = if (measured) counterNwh / 1_000_000_000f else 0f

        var fullWh = if (remainFromCounter > 0f && levelPercent > 0) {
            remainFromCounter * 100f / levelPercent
        } else {
            0f
        }
        // ② 直接的能量节点（µWh → Wh），比容量 × 标称电压更准
        if (fullWh <= 0f && battery.energyFullDesignUwh > 0L) {
            fullWh = battery.energyFullDesignUwh / 1_000_000f
        }
        // ③ 设计容量 × 标称电压
        if (fullWh <= 0f && battery.chargeFullDesignUah > 0L) {
            fullWh = battery.chargeFullDesignUah / 1_000_000f * NOMINAL_VOLTAGE_V
        }
        // ④ 当前满电容量 × 标称电压（电池老化后比设计容量更贴近实际）
        if (fullWh <= 0f && battery.chargeFullUah > 0L) {
            fullWh = battery.chargeFullUah / 1_000_000f * NOMINAL_VOLTAGE_V
        }
        val remainWh = if (remainFromCounter > 0f) {
            remainFromCounter
        } else {
            fullWh * levelPercent.coerceAtLeast(0) / 100f
        }
        return BatteryEnergy(fullWh = fullWh, remainWh = remainWh, measured = measured)
    }
}

/** 电池能量读数，见 [BatteryDataSource.readEnergy] */
data class BatteryEnergy(
    /** 额定总能量 Wh；0 表示不可推算 */
    val fullWh: Float = 0f,
    /** 当前剩余能量 Wh */
    val remainWh: Float = 0f,
    /** true = 剩余能量来自 ENERGY_COUNTER 直接测量，而非按电量比例折算 */
    val measured: Boolean = false,
)

/** 充电控制（需要 root，且依赖内核是否暴露对应节点） */
object ChargeController {

    private const val PS = "/sys/class/power_supply"
    private val batteryDirs = listOf("$PS/battery", "$PS/battery-1", "$PS/Battery")

    private val SWITCH_NODES = listOf(
        "charging_enabled",
        "battery_charging_enabled",
        "input_suspend",
    )
    private val CURRENT_NODES = listOf(
        "constant_charge_current_max",
        "battery_charge_current_limit",
        "current_max",
    )

    /**
     * 定位节点的真实路径。
     *
     * **不能用 `File.exists()` 判断**：Android 12+ 对厂商 sysfs 目录的 SELinux 拒绝
     * 会让 `exists()` 返回 false，节点被当成「不存在」直接跳过——充电控制会整体失效，
     * 而界面上只表现为「未检测到可用节点」。改为交给 root shell 里的 `[ -f ]` 判断。
     */
    private suspend fun findNode(name: String): String? {
        val script = "for d in ${batteryDirs.joinToString(" ")}; do " +
            "if [ -f \$d/$name ]; then echo \$d/$name; break; fi; done"
        val path = Shell.run(script, root = true).stdout.trim()
        return path.takeIf { it.isNotEmpty() }
    }

    /**
     * 开关充电。部分内核使用 input_suspend / battery_charging_enabled 命名。
     *
     * 写入后**回读节点值**校验是否真正生效——充电节点常被厂商充电策略接管，
     * echo 可能正常返回但值不变（与 CPU/GPU 频率节点同样的静默失败模式）。
     */
    suspend fun setChargingEnabled(enabled: Boolean): Boolean {
        for (name in SWITCH_NODES) {
            val path = findNode(name) ?: continue
            val target = if (name == "input_suspend") {
                if (enabled) "0" else "1"
            } else {
                if (enabled) "1" else "0"
            }
            Shell.writeNode(path, target)
            if (Shell.readNode(path)?.trim() == target) return true
        }
        return false
    }

    /**
     * 限制充电电流上限（µA），回读校验。
     * 内核可能把请求值归一到支持的电流挡位，允许 10% 偏差。
     */
    suspend fun setChargeCurrentLimit(ua: Int): Boolean {
        for (name in CURRENT_NODES) {
            val path = findNode(name) ?: continue
            Shell.writeNode(path, ua.toString())
            val applied = Shell.readNode(path)?.toIntOrNull() ?: continue
            if (applied > 0 && kotlin.math.abs(applied - ua) <= ua / 10) return true
        }
        return false
    }

    /** 读取当前充电电流上限 */
    suspend fun readChargeCurrentLimit(): Int? {
        for (name in CURRENT_NODES) {
            val path = findNode(name) ?: continue
            Shell.readNode(path)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    /**
     * 可用的充电控制节点名称。
     *
     * 用**一次** root 命令把所有候选节点探测一遍，而不是每个节点各起一次 `su`
     * ——这个函数在进入「充电控制」页时就会调用，逐节点探测会让首屏明显变慢。
     */
    suspend fun availableNodes(): List<String> {
        val names = SWITCH_NODES + CURRENT_NODES
        val script = buildString {
            names.forEach { n ->
                appendLine(
                    "for d in ${batteryDirs.joinToString(" ")}; do " +
                        "if [ -e \$d/$n ]; then echo $n; break; fi; done"
                )
            }
        }
        val found = Shell.run(script, root = true).stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return names.filter { it in found }
    }
}
