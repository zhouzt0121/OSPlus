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


    /**
     * 读取电池 sysfs 节点。
     *
     * vendor_sysfs_battery_supply 在 Android 12+ 对普通应用不可读，
     * 因此这里走 [Shell.readNode]（必要时自动使用 root）。
     */
    suspend fun node(name: String): String? {
        for (dir in batteryDirs) {
            val path = "$dir/$name"
            if (!File(path).exists()) continue
            val v = Shell.readNode(path)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

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

        BatteryInfo(
            levelPercent = percent,
            status = status,
            health = health,
            tempC = readTempC(sticky),
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1,
            currentNowUa = readCurrentNowUa(),
            currentAvgUa = node("current_avg")?.toIntOrNull() ?: 0,
            chargeCounterUah = readChargeCounter(sticky),
            capacityPercent = node("capacity")?.toIntOrNull() ?: percent,
            technology = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty(),
            plugged = plugged,
            chargingEnabled = node("charging_enabled")?.let { it == "1" },
            chargeFullDesignUah = node("charge_full_design")?.toLongOrNull()?.div(1000) ?: -1L,
            chargeFullUah = node("charge_full")?.toLongOrNull()?.div(1000) ?: -1L,
            cycleCount = readCycleCount(),
        )
    }

    private suspend fun readTempC(sticky: Intent?): Float? {
        val fromApi = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10f)
        if (fromApi != null) return fromApi
        val raw = node("temp")?.toFloatOrNull() ?: return null
        return if (raw > 1000f) raw / 1000f else if (raw > 100f) raw / 10f else raw
    }

    /** 读取瞬时电流（µA）；放电为负值 */
    private suspend fun readCurrentNowUa(): Int {
        listOf("current_now", "battery_current_now", "current_avg").forEach { key ->
            val v = node(key)?.toIntOrNull()
            if (v != null) return v
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            0
        } else 0
    }

    /** 累计电量计数（µAh），从 sysfs 节点读取，不可用时返回 -1 */
    private suspend fun readChargeCounter(sticky: Intent?): Long {
        listOf("charge_counter", "battery_charge_counter").forEach { key ->
            val v = node(key)?.toLongOrNull()
            if (v != null) return v
        }
        return -1L
    }

    private suspend fun readCycleCount(): Int {
        return listOf("cycle_count", "battery_cycle", "cycle_counts")
            .firstNotNullOfOrNull { node(it)?.toIntOrNull() } ?: -1
    }
}

/** 充电控制（需要 root，且依赖内核是否暴露对应节点） */
object ChargeController {

    private const val PS = "/sys/class/power_supply"
    private val batteryDirs = listOf("$PS/battery", "$PS/battery-1", "$PS/Battery")

    private fun findNode(name: String): String? {
        for (dir in batteryDirs) {
            val f = File("$dir/$name")
            if (f.exists()) return f.absolutePath
        }
        return null
    }

    /**
     * 开关充电。部分内核使用 input_suspend / battery_charging_enabled 命名。
     *
     * 写入后**回读节点值**校验是否真正生效——充电节点常被厂商充电策略接管，
     * echo 可能正常返回但值不变（与 CPU/GPU 频率节点同样的静默失败模式）。
     */
    suspend fun setChargingEnabled(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        val candidates = listOf("charging_enabled", "battery_charging_enabled", "input_suspend")
        for (name in candidates) {
            val path = findNode(name) ?: continue
            val target = if (name == "input_suspend") {
                if (enabled) "0" else "1"
            } else value
            Shell.writeNode(path, target)
            val applied = Shell.readNode(path)?.trim()
            if (applied != null && applied == target) return true
        }
        return false
    }

    /**
     * 限制充电电流上限（µA），回读校验。
     * 内核可能把请求值归一到支持的电流挡位，允许 10% 偏差。
     */
    suspend fun setChargeCurrentLimit(ua: Int): Boolean {
        val candidates = listOf(
            "constant_charge_current_max",
            "battery_charge_current_limit",
            "current_max",
        )
        for (name in candidates) {
            val path = findNode(name) ?: continue
            Shell.writeNode(path, ua.toString())
            val applied = Shell.readNode(path)?.toIntOrNull() ?: continue
            if (applied > 0 && kotlin.math.abs(applied - ua) <= ua / 10) return true
        }
        return false
    }

    /** 读取当前充电电流上限 */
    suspend fun readChargeCurrentLimit(): Int? {
        val candidates = listOf(
            "constant_charge_current_max",
            "battery_charge_current_limit",
            "current_max",
        )
        for (name in candidates) {
            val v = batteryDirs.firstNotNullOfOrNull {
                runCatching { File("$it/$name").readText().trim().toIntOrNull() }.getOrNull()
            }
            if (v != null) return v
        }
        return null
    }

    /** 可用的充电控制节点名称 */
    fun availableNodes(): List<String> {
        val names = listOf(
            "charging_enabled",
            "battery_charging_enabled",
            "input_suspend",
            "constant_charge_current_max",
            "battery_charge_current_limit",
            "current_max",
        )
        return names.filter { name -> batteryDirs.any { File("$it/$name").exists() } }
    }
}
