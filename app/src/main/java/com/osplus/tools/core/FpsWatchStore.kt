package com.osplus.tools.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.osplus.tools.model.FpsRecord
import com.osplus.tools.model.FpsSession

/**
 * 帧率记录的持久化存储：SQLite 双表。
 *
 * ## 为什么不用内存环形缓冲
 *
 * 2.6.x 之前 [com.osplus.tools.vm.DeviceViewModel] 用 `takeLast(1800)` 存记录，
 * 即「最多 30 分钟」，且**杀掉进程就没了**。想做「记录一整局游戏再回头分析」
 * 完全不可能——这正是要引入会话化持久化的原因。
 *
 * ## 表结构
 *
 * ```
 * session(id, package_name, label, time_begin, time_end, sample_count)
 * history(id, time, session, fps, fps_sys, jank, big_jank,
 *         avg_frame_ms, max_frame_ms, cpu_load, gpu_mhz, gpu_load,
 *         mem_percent, power_mw, battery_temp_c,
 *         core_loads, core_freqs_khz)
 * ```
 *
 * 相比 Scene5 的 `fps_watch_log2` 做了三处修正：
 *
 * 1. **`time_end` 真正写入**。Scene5 建了这个字段但从不 UPDATE，会话结束时间
 *    永远查不出来，列表只能显示「开始于某时」。
 * 2. **删掉 `getSum()`**。Scene5 里它查的是 `charge_history` 表，而该表在
 *    本库的 `onCreate` 里根本没建——从耗电模块复制粘贴过来的死代码，必然抛异常。
 * 3. **内存 / 频率 / 功耗 / 温度一并落库**。Scene5 只存了 fps/cpu_load/gpu_load/
 *    capacity/temperature，OSPlus 的 CSV 导出字段更全，不能因为改存储就丢字段。
 */
class FpsWatchStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            create table session(
              id INTEGER primary key,
              package_name text,
              label text,
              time_begin INTEGER default(-1),
              time_end INTEGER default(-1),
              sample_count INTEGER default(0)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            create table history(
              id INTEGER primary key AUTOINCREMENT,
              time INTEGER,
              session INTEGER,
              fps REAL,
              fps_sys REAL,
              jank INTEGER,
              big_jank INTEGER,
              avg_frame_ms REAL,
              max_frame_ms REAL,
              cpu_load REAL,
              gpu_mhz INTEGER,
              gpu_load INTEGER,
              mem_percent REAL,
              power_mw REAL,
              battery_temp_c REAL,
              core_loads text,
              core_freqs_khz text
            )
            """.trimIndent(),
        )
        db.execSQL("create index idx_history_session on history(session)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 单版本起步，暂无迁移。后续加列请在此按版本号分支，不要 drop 用户数据。
    }

    // ---------------- 会话 ----------------

    /**
     * 开一个记录会话，返回会话 id（即当前时刻毫秒）。
     *
     * @param packageName 会话开始时处于前台的包名，用于按应用归类；未知时传 null
     */
    fun createSession(packageName: String?): Long {
        val id = System.currentTimeMillis()
        return runCatching {
            writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    db.insertOrThrow(
                        "session",
                        null,
                        ContentValues().apply {
                            put("id", id)
                            put("package_name", packageName)
                            put("time_begin", id)
                            put("sample_count", 0)
                        },
                    )
                    db.setTransactionSuccessful()
                    id
                } finally {
                    db.endTransaction()
                }
            }
        }.getOrElse { e ->
            // 建会话失败必须留痕：调用方（startFpsRecording）会因此拒绝开始记录，
            // 若这里静默返回 -1，用户只会看到「开关点了没反应」而查不到原因。
            android.util.Log.e("OSPlusrFps", "createSession 失败: ${e.message}", e)
            -1L
        }
    }

    /**
     * 结束会话：**写入 `time_end` 与实际采样条数**。
     *
     * Scene5 漏掉了这一步，导致会话时长无法回溯——这里必须补上。
     */
    fun endSession(sessionId: Long) {
        if (sessionId <= 0L) return
        runCatching {
            writableDatabase.use { db ->
                db.execSQL(
                    """
                    update session
                       set time_end = ?,
                           sample_count = (select count(*) from history where session = ?)
                     where id = ?
                    """.trimIndent(),
                    arrayOf(System.currentTimeMillis(), sessionId, sessionId),
                )
            }
        }
    }

    /** 全部会话，按开始时间倒序 */
    fun sessions(): List<FpsSession> = runCatching {
        val out = ArrayList<FpsSession>()
        readableDatabase.use { db ->
            db.rawQuery(
                "select id, package_name, time_begin, time_end, sample_count from session order by time_begin desc",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    out += FpsSession(
                        id = c.getLong(0),
                        packageName = if (c.isNull(1)) null else c.getString(1),
                        label = "",
                        timeBegin = c.getLong(2),
                        timeEnd = c.getLong(3),
                        sampleCount = c.getInt(4),
                    )
                }
            }
        }
        out
    }.getOrElse { e ->
        // 读失败必须留痕：调用方拿到空列表后会显示「还没有历史会话」，
        // 与「真的没录过」在界面上无法区分，很容易被误判成「数据丢了」。
        android.util.Log.e("OSPlusrFps", "sessions() 读取失败: ${e.message}", e)
        emptyList()
    }

    fun deleteSession(sessionId: Long) {
        runCatching {
            writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    db.delete("history", "session = ?", arrayOf(sessionId.toString()))
                    db.delete("session", "id = ?", arrayOf(sessionId.toString()))
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    fun clearAll() {
        runCatching {
            writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    db.delete("history", null, null)
                    db.delete("session", null, null)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    // ---------------- 采样 ----------------

    /** 追加一条采样。高频写入，失败静默（不打断采样循环） */
    fun addSample(sessionId: Long, r: FpsRecord) {
        if (sessionId <= 0L) return
        runCatching {
            writableDatabase.use { db ->
                db.insert("history", null, ContentValues().apply {
                    put("time", r.timeMs)
                    put("session", sessionId)
                    put("fps", r.fps)
                    put("fps_sys", r.fpsSys)
                    put("jank", r.jank)
                    put("big_jank", r.bigJank)
                    put("avg_frame_ms", r.avgFrameMs)
                    put("max_frame_ms", r.maxFrameMs)
                    put("cpu_load", r.cpuLoad)
                    put("gpu_mhz", r.gpuMhz)
                    put("gpu_load", r.gpuLoad)
                    put("mem_percent", r.memUsedPercent)
                    put("power_mw", r.powerMw)
                    put("battery_temp_c", r.batteryTempC)
                    put("core_loads", r.coreLoads.joinToString(","))
                    put("core_freqs_khz", r.coreFreqsKhz.joinToString(","))
                })
            }
        }
    }

    /** 读取某会话的全部采样，按时间正序 */
    fun samples(sessionId: Long): List<FpsRecord> = runCatching {
        val out = ArrayList<FpsRecord>()
        readableDatabase.use { db ->
            db.rawQuery(
                """
                select time, fps, fps_sys, jank, big_jank, avg_frame_ms, max_frame_ms,
                       cpu_load, gpu_mhz, gpu_load, mem_percent, power_mw, battery_temp_c,
                       core_loads, core_freqs_khz
                  from history where session = ? order by time asc
                """.trimIndent(),
                arrayOf(sessionId.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val storedFps = c.getFloat(1)
                    val storedSys = c.getFloat(2)
                    out += FpsRecord(
                        timeMs = c.getLong(0),
                        // 老版本录的数据里 fps 存的是「本应用自身帧率」，fps_sys 才是系统级；
                        // 新版本起 fps 存的已是有效值（系统级优先）。这里统一归一化，
                        // 让新旧数据在图表上口径一致——否则同一张图里前后两段含义不同。
                        fps = if (storedSys > 0f) storedSys else storedFps,
                        fpsApp = storedFps,
                        fpsSys = storedSys,
                        jank = c.getInt(3),
                        bigJank = c.getInt(4),
                        avgFrameMs = c.getFloat(5),
                        maxFrameMs = c.getFloat(6),
                        cpuLoad = c.getFloat(7),
                        gpuMhz = c.getLong(8),
                        gpuLoad = c.getInt(9),
                        memUsedPercent = c.getFloat(10),
                        powerMw = c.getFloat(11),
                        batteryTempC = if (c.isNull(12)) null else c.getFloat(12),
                        coreLoads = parseFloats(c.getString(13)),
                        coreFreqsKhz = parseLongs(c.getString(14)),
                    )
                }
            }
        }
        out
    }.getOrElse { e ->
        // 同 sessions()：这里静默返回空会让「打开历史会话」展示成一片空白，
        // 用户无法分辨是「这次没录到数据」还是「读取出错了」。
        android.util.Log.e("OSPlusrFps", "samples($sessionId) 读取失败: ${e.message}", e)
        emptyList()
    }

    /** 会话统计：均值 / 最小 / 最大帧率、低帧占比、高温占比 */
    fun statsOf(sessionId: Long): FpsSessionStats = runCatching {
        var count = 0
        var sum = 0.0
        var min = Float.MAX_VALUE
        var max = 0f
        var lowCount = 0
        var hotCount = 0
        var tempCount = 0
        readableDatabase.use { db ->
            db.rawQuery(
                "select fps, battery_temp_c from history where session = ?",
                arrayOf(sessionId.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val fps = c.getFloat(0)
                    count++
                    sum += fps
                    if (fps < min) min = fps
                    if (fps > max) max = fps
                    if (fps in 0.01f..LOW_FPS_THRESHOLD) lowCount++
                    if (!c.isNull(1)) {
                        tempCount++
                        if (c.getFloat(1) > HOT_TEMP_THRESHOLD) hotCount++
                    }
                }
            }
        }
        FpsSessionStats(
            count = count,
            avg = if (count > 0) (sum / count).toFloat() else 0f,
            min = if (count > 0 && min != Float.MAX_VALUE) min else 0f,
            max = max,
            lowFpsRatio = if (count > 0) lowCount * 100f / count else 0f,
            hotRatio = if (tempCount > 0) hotCount * 100f / tempCount else 0f,
        )
    }.getOrDefault(FpsSessionStats())

    private fun parseFloats(s: String?): List<Float> =
        s?.takeIf { it.isNotEmpty() }?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: emptyList()

    private fun parseLongs(s: String?): List<Long> =
        s?.takeIf { it.isNotEmpty() }?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    companion object {
        private const val DB_NAME = "osplus_fps_watch"
        private const val DB_VERSION = 1

        /** 低于该帧率计入「低帧占比」，与 Scene5 的 45 阈值对齐 */
        const val LOW_FPS_THRESHOLD = 45f

        /** 高于该温度计入「高温占比」，与 Scene5 的 46 阈值对齐 */
        const val HOT_TEMP_THRESHOLD = 46f
    }
}

/** 一次记录会话的汇总统计 */
data class FpsSessionStats(
    val count: Int = 0,
    val avg: Float = 0f,
    val min: Float = 0f,
    val max: Float = 0f,
    /** 低于 [FpsWatchStore.LOW_FPS_THRESHOLD] 的采样占比（%） */
    val lowFpsRatio: Float = 0f,
    /** 高于 [FpsWatchStore.HOT_TEMP_THRESHOLD] 的采样占比（%） */
    val hotRatio: Float = 0f,
)
