package com.osplus.tools

import com.osplus.tools.core.GpuDataSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GPU 频率单位归一的回归测试。
 *
 * 这里曾经踩过一个真实 bug：用 File.exists() 判断节点是 Hz 还是 MHz，
 * 而厂商 sysfs 目录被 SELinux 拒绝列举导致误判，222 MHz 被当成 222 Hz。
 * 现在按数量级判定，本测试锁死这条规则。
 */
class GpuDataSourceTest {

    @Test
    fun `hz values convert to mhz`() {
        assertEquals(222L, GpuDataSource.toMhz(222_000_000L))
        assertEquals(1100L, GpuDataSource.toMhz(1_100_000_000L))
        assertEquals(160L, GpuDataSource.toMhz(160_000_000L))
    }

    @Test
    fun `mhz values pass through unchanged`() {
        assertEquals(222L, GpuDataSource.toMhz(222L))
        assertEquals(1100L, GpuDataSource.toMhz(1100L))
        assertEquals(3532L, GpuDataSource.toMhz(3532L))
    }

    @Test
    fun `boundary stays in mhz range`() {
        // 1e5 以上的值一律视为 Hz；99999 视为 MHz（实际 GPU 频率不会这么低）
        assertEquals(99_999L, GpuDataSource.toMhz(99_999L))
        assertEquals(100L, GpuDataSource.toMhz(100_000_000L))
    }

    @Test
    fun `non positive values are invalid`() {
        assertEquals(-1L, GpuDataSource.toMhz(0L))
        assertEquals(-1L, GpuDataSource.toMhz(-222L))
    }
}
