package com.osplus.tools

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.osplus.tools.core.PrivilegeManager

class OsPlusApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 提权后端必须在任何采样启动之前装好：Shell 依赖它分发命令，
        // 晚装一步首屏的 CPU/内存读取就会走空通道
        PrivilegeManager.init(this)
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // 清理历史遗留通道。
        //
        // 通知通道一旦创建就**常驻**：应用不再往它发通知、甚至不再注册它，
        // 系统也不会删除它，它会一直留在「设置 → 通知 → 类别」里。
        // 本应用因此攒下两条空壳：
        //   · fluid_cloud_task —— 初版实时任务通道，与现用的 v2 同名「实时任务流体云」，
        //     在类别列表里并排出现两条同名项，用户无法判断该关哪一条；
        //   · osplus_fps —— 1.x 的「帧率悬浮窗」通道，2.0.0 改用实时任务通知后已无通知。
        // 二者都不再承载任何通知，删除即可。若将来回滚到旧版本继续往这些 id 发通知，
        // 系统会按默认配置自动重建，不会丢通知。
        manager.deleteNotificationChannel(LEGACY_CHANNEL_FLUID)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_FPS)

        // 实时任务状态通知（仿流体云卡片）：
        // 高优先级 + 锁屏公开 + 静音免振动，确保状态卡片始终可见且不打扰
        val channel = NotificationChannel(
            CHANNEL_FLUID,
            "实时任务流体云",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "实时任务状态，仿流体云通知卡片"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        // v2：初版通道曾被用户在通知栏降级锁定（user-locked LOW），换新 id 恢复高优先级
        const val CHANNEL_FLUID = "fluid_cloud_task_v2"

        /** 初版实时任务通道 id；当前版本不再注册，仅用于启动时清理残留 */
        private const val LEGACY_CHANNEL_FLUID = "fluid_cloud_task"

        /** 1.x 的帧率悬浮窗通道 id；2.0.0 起不再使用，仅用于启动时清理残留 */
        private const val LEGACY_CHANNEL_FPS = "osplus_fps"
    }
}
