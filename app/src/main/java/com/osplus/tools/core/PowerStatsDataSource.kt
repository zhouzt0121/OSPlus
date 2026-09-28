package com.osplus.tools.core

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * 「使用情况访问」权限的网关。
 *
 * 按应用的耗电明细由 [PowerRecorder] 在录制期间自行采集（前台时长差分 + CPU 累加），
 * 这里只保留权限判断——它是整条链路的前置条件：
 * 没授权就拿不到任何应用维度的数据。
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
}
