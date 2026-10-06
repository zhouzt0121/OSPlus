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

    /**
     * 会话统计：均值 / 最小 / 最大 / 方差、≥60FPS 占比、5% Low、
     * 平均温度、平均功耗、低帧占比、高温占比。
     *
     * ## 口径说明（与 Scene5/PerfDog 对齐）
     *
     * - **VARIANCE**：总体方差（除以 N，非样本方差 N-1）。分析卡上要表现
     *   「帧率抖动有多剧烈」，用总体方差更直观：全稳则 0，抖动越大越大。
     * - **5% Low**：把全部帧率升序排列后取第 5 百分位的值。它回答的是
     *   「最差的那 5% 坏到什么程度」——均值会被高帧率掩盖掉偶发卡顿，
     *   这个指标专门用来暴露卡顿。
     * - **≥60FPS (Smoothness)**：帧率 ≥60 的采样占比。注意分母是**全部**
     *   采样，不含无效值（fps<=0 的记录在建表侧就被过滤了，见 samples()）。
     *
     * 全部统计在**一次 SELECT** 里完成，避免多次查库；排序因为要算百分位
     * 必须拿到全量，但会话样本量级在几千条，内存里排完全没问题。
     */
    fun statsOf(sessionId: Long): FpsSessionStats = runCatching {
        var count = 0
        var sum = 0.0
        var min = Float.MAX_VALUE
        var max = 0f
        var lowCount = 0
        var hotCount = 0
        var tempCount = 0
        var sumSq = 0.0                       // Σfps² ，用于算方差
        var smoothCount = 0                   // ≥60FPS 采样数
        var tempSum = 0.0
        var powerSum = 0.0
        var powerCount = 0
        val fpsList = ArrayList<Float>()
        readableDatabase.use { db ->
            db.rawQuery(
                "select fps, battery_temp_c, power_mw from history where session = ?",
                arrayOf(sessionId.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val fps = c.getFloat(0)
                    count++
                    sum += fps
                    sumSq += fps.toDouble() * fps
                    fpsList.add(fps)
                    if (fps < min) min = fps
                    if (fps > max) max = fps
                    if (fps >= SMOOTH_FPS_THRESHOLD) smoothCount++
                    if (fps in 0.01f..LOW_FPS_THRESHOLD) lowCount++
                    if (!c.isNull(1)) {
                        tempCount++
                        tempSum += c.getFloat(1)
                        if (c.getFloat(1) > HOT_TEMP_THRESHOLD) hotCount++
                    }
                    if (!c.isNull(2)) {
                        val p = c.getFloat(2)
                        // 功耗列在无 root / 无电池节点时会是 0，不能当有效样本，
                        // 否则平均功耗被硬拉低，卡片上显示一个假的「省电」。
                        if (p > 0f) {
                            powerCount++
                            powerSum += p
                        }
                    }
                }
            }
        }
        val avg = if (count > 0) (sum / count).toFloat() else 0f
        // 总体方差 = E[x²] - (E[x])²。（浮点下可能算出极小负数，clamp 到 0）
        val variance = if (count > 0) {
            val v = (sumSq / count) - avg.toDouble() * avg
            if (v < 0.0) 0f else v.toFloat()
        } else 0f
        val fivePercentLow = if (fpsList.isEmpty()) {
            0f
        } else {
            fpsList.sort()
            // 第 5 百分位：位置 = ceil(0.05 * N) - 1，至少取第 0 位（最小值）。
            val idx = kotlin.math.ceil(fpsList.size * 0.05).toInt().coerceIn(1, fpsList.size) - 1
            fpsList[idx]
        }
        FpsSessionStats(
            count = count,
            avg = avg,
            min = if (count > 0 && min != Float.MAX_VALUE) min else 0f,
            max = max,
            variance = variance,
            smoothRatio = if (count > 0) smoothCount * 100f / count else 0f,
            fivePercentLow = fivePercentLow,
            avgTempC = if (tempCount > 0) (tempSum / tempCount).toFloat() else 0f,
            avgPowerMw = if (powerCount > 0) (powerSum / powerCount).toFloat() else 0f,
            lowFpsRatio = if (count > 0) lowCount * 100f / count else 0f,
            hotRatio = if (tempCount > 0) hotCount * 100f / tempCount else 0f,
        )
    }.getOrElse { e ->
        // 与 samples() 同样的理由：统计失败若静默返回全 0，卡片会画成
        // 一张「全是 0」的假图，比报错更误导。
        android.util.Log.e("OSPlusrFps", "statsOf($sessionId) 失败: ${e.message}", e)
        FpsSessionStats()
    }

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

        /**
         * 「流畅帧」阈值：帧率 ≥ 该值即计入 Smoothness（≥60FPS 占比）。
         *
         * 取 60 而非设备刷新率，是因为卡片要和 Scene5/PerfDog 的
         * `≥60FPS` 口径一致——它对「能不能稳住 60 帧」这个玩家最关心的
         * 问题最直观，也便于跨机型横向比较。
         */
        const val SMOOTH_FPS_THRESHOLD = 60f
    }
}

/** 一次记录会话的统计摘要。
 *
 * 前 6 个字段（count/avg/min/max/lowFpsRatio/hotRatio）是原有维度，
 * 后面 5 个是为「录制记录分析卡片」新增的——卡片版式对标 Scene5/PerfDog，
 * 需要方差、≥60FPS 占比、5% Low、平均温度、平均功耗。
 */
data class FpsSessionStats(
    val count: Int = 0,
    val avg: Float = 0f,
    val min: Float = 0f,
    val max: Float = 0f,
    /** 帧率**总体方差**（除以 N）。0 表示全程恒定，越大抖动越剧烈 */
    val variance: Float = 0f,
    /** 帧率 ≥60 的采样占比（%，卡片上标为 Smoothness） */
    val smoothRatio: Float = 0f,
    /** 第 5 百分位帧率（越小说明卡顿谷底越深） */
    val fivePercentLow: Float = 0f,
    /** 平均电池/结温（℃，无有效样本时为 0） */
    val avgTempC: Float = 0f,
    /** 平均功耗（mW，无有效样本时为 0） */
    val avgPowerMw: Float = 0f,
    /** 低于 [FpsWatchStore.LOW_FPS_THRESHOLD] 的采样占比（%） */
    val lowFpsRatio: Float = 0f,
    /** 高于 [FpsWatchStore.HOT_TEMP_THRESHOLD] 的采样占比（%） */
    val hotRatio: Float = 0f,
)
