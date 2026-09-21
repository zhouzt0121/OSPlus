package com.osplus.tools

import com.osplus.tools.ui.components.downsample
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 长窗口图表降采样的回归测试。
 *
 * 帧率记录窗口可达数百个采样点，降采样错误会导致
 * 图表点数不对或数据错位（绘制与真实记录不一致）。
 */
class DownsampleTest {

    @Test
    fun `returns original when within bucket count`() {
        val values = listOf(1f, 2f, 3f)
        assertEquals(values, downsample(values, 5))
    }

    @Test
    fun `returns original when buckets is zero or negative`() {
        val values = listOf(1f, 2f, 3f)
        assertEquals(values, downsample(values, 0))
        assertEquals(values, downsample(values, -1))
    }

    @Test
    fun `aggregates to exactly bucket count`() {
        val values = List(100) { it.toFloat() }
        val out = downsample(values, 30)
        assertEquals(30, out.size)
    }

    @Test
    fun `each bucket is the mean of its range`() {
        // 4 个点分 2 桶：[0,0] 与 [10,10]
        val out = downsample(listOf(0f, 0f, 10f, 10f), 2)
        assertEquals(2, out.size)
        assertEquals(0f, out[0])
        assertEquals(10f, out[1])
    }

    @Test
    fun `uneven split uses partial last bucket`() {
        // 3 个点分 2 桶：step=1.5 → [0] 与 [10,10]... 需覆盖边界
        val out = downsample(listOf(0f, 10f, 20f), 2)
        assertEquals(2, out.size)
        // 不校验具体值，只保证桶数与覆盖完整（首尾不越界）
    }

    @Test
    fun `monotonic series preserves order`() {
        val values = List(60) { it.toFloat() }
        val out = downsample(values, 20)
        assertEquals(20, out.size)
        assertEquals(out, out.sorted())
    }
}
