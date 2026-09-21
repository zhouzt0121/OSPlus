package com.osplus.tools

import com.osplus.tools.model.FpsRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 帧率记录 CSV 的列一致性测试。
 *
 * 导出的 CSV 要求表头列数与数据行列数严格一致，
 * 任何一侧改动（比如增删核心数）都必须同步另一侧。
 */
class FpsRecordTest {

    private fun sample(coreCount: Int, temp: Float? = 30f) = FpsRecord(
        timeMs = 1_000L,
        fps = 60.5f,
        jank = 2,
        bigJank = 1,
        avgFrameMs = 16.6f,
        maxFrameMs = 20.1f,
        totalFrames = 61,
        cpuLoad = 55.5f,
        coreLoads = List(coreCount) { it * 10f },
        coreFreqsKhz = List(coreCount) { 1_000_000L + it },
        gpuMhz = 222L,
        gpuLoad = 30,
        memUsedPercent = 72.5f,
        powerMw = 678f,
        batteryTempC = temp,
    )

    @Test
    fun `header column count matches row column count`() {
        for (coreCount in listOf(4, 6, 8)) {
            val header = FpsRecord.csvHeader(coreCount).split(',').size
            val row = sample(coreCount).toCsvRow(coreCount).split(',').size
            assertEquals("coreCount=$coreCount", header, row)
        }
    }

    @Test
    fun `column count equals base plus per core fields`() {
        // 8 个基础列 + 每核 2 列 + GPU/内存/功耗/温度 5 列
        val coreCount = 8
        val expected = 8 + coreCount * 2 + 5
        assertEquals(expected, FpsRecord.csvHeader(coreCount).split(',').size)
        assertEquals(expected, sample(coreCount).toCsvRow(coreCount).split(',').size)
    }

    @Test
    fun `null battery temp still keeps last column`() {
        val row = sample(4, temp = null).toCsvRow(4)
        assertEquals("空温度导出为空字段", "", row.split(',').last())
    }

    @Test
    fun `missing cores degrade to zero instead of throwing`() {
        val record = sample(4)
        val row = record.toCsvRow(8) // 记录只有 4 核数据，但设备有 8 核
        assertEquals(FpsRecord.csvHeader(8).split(',').size, row.split(',').size)
    }

    @Test
    fun `numeric fields are deterministic`() {
        val a = sample(8).toCsvRow(8)
        val b = sample(8).toCsvRow(8)
        assertEquals(a, b)
    }
}
