package com.osplus.tools.core

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.osplus.tools.model.PowerUsageEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 耗电统计。
 *
 * Android 没有开放按应用的真实耗电量接口，这里采用业界通用做法：
 * 以 UsageStats 的前台时长为主因子，结合前台/后台权重估算耗电占比。
 */
object PowerStatsDataSource {

    /** 是否已授予「使用情况访问权限」 */
    fun hasUsageAccess(context: Context): Boolean {
        val aom = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        @Suppress("DEPRECATION")
        val mode = aom.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun read(context: Context, hours: Int = 24): List<PowerUsageEntry> =
        withContext(Dispatchers.IO) {
            if (!hasUsageAccess(context)) return@withContext emptyList()
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@withContext emptyList()

            val now = System.currentTimeMillis()
            val from = now - hours * 3600_000L
            val stats = runCatching {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, from, now)
            }.getOrNull() ?: return@withContext emptyList()

            val pm = context.packageManager
            val entries = stats.mapNotNull { s ->
                val fg = s.totalTimeInForeground
                val bg = runCatching { s.totalTimeVisible }.getOrNull() ?: 0L
                if (fg <= 0L && bg <= 0L) return@mapNotNull null
                val label = runCatching {
                    pm.getApplicationInfo(s.packageName, 0).let { pm.getApplicationLabel(it) }
                }.getOrNull()?.toString() ?: s.packageName
                // 前台权重 1.0，后台权重 0.15
                val score = fg + bg * 0.15f
                PowerUsageEntry(
                    packageName = s.packageName,
                    label = label.toString(),
                    foregroundMs = fg,
                    backgroundMs = bg,
                    percent = 0f,
                ) to score
            }

            val total = entries.sumOf { it.second.toDouble() }.toFloat()
            if (total <= 0f) return@withContext emptyList()
            entries.map { (e, score) -> e.copy(percent = score * 100f / total) }
                .sortedByDescending { it.percent }
        }
}
