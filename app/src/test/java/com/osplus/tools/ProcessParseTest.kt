package com.osplus.tools

import com.osplus.tools.core.ProcessDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `top` 输出解析的回归测试。
 *
 * 不同 ROM 的 toybox top 输出在 RES 单位（K/M/G）、USER 命名、
 * ARGS 是否含空格上差异很大，这里用真实格式的样本锁住解析行为。
 */
class ProcessParseTest {

    private fun parse(line: String) = ProcessDataSource.parseLine(line)

    // ---------- parseRes：内存字段的单位换算 ----------

    @Test
    fun `res plain kb`() {
        assertEquals(9160L, ProcessDataSource.parseRes("9160"))
    }

    @Test
    fun `res with K suffix`() {
        assertEquals(9160L, ProcessDataSource.parseRes("9160K"))
        assertEquals(9160L, ProcessDataSource.parseRes("9160k"))
    }

    @Test
    fun `res with M suffix converts to kb`() {
        assertEquals(145408L, ProcessDataSource.parseRes("142M")) // 142 * 1024
        assertEquals(3788L, ProcessDataSource.parseRes("3.7M"))  // 3.7 * 1024 = 3788.8 → 3788
    }

    @Test
    fun `res with G suffix converts to kb`() {
        assertEquals(1_048_576L, ProcessDataSource.parseRes("1G"))
    }

    @Test
    fun `res invalid falls back to zero`() {
        assertEquals(0L, ProcessDataSource.parseRes(""))
        assertEquals(0L, ProcessDataSource.parseRes("abc"))
    }

    // ---------- uidFromUser：USER 列反推 uid ----------

    @Test
    fun `well known users map to fixed uid`() {
        assertEquals(0, ProcessDataSource.uidFromUser("root"))
        assertEquals(1000, ProcessDataSource.uidFromUser("system"))
        assertEquals(2000, ProcessDataSource.uidFromUser("shell"))
        assertEquals(1001, ProcessDataSource.uidFromUser("radio"))
    }

    @Test
    fun `app user pattern u0_a525 maps to 10525`() {
        assertEquals(10_525, ProcessDataSource.uidFromUser("u0_a525"))
    }

    @Test
    fun `multi digit user id is preserved`() {
        // u10_a123 → 10 * 100000 + 10000 + 123
        assertEquals(1_010_123, ProcessDataSource.uidFromUser("u10_a123"))
    }

    @Test
    fun `numeric user passes through`() {
        assertEquals(10500, ProcessDataSource.uidFromUser("10500"))
    }

    @Test
    fun `unknown user yields negative one`() {
        assertEquals(-1, ProcessDataSource.uidFromUser("nobody_exotic"))
    }

    // ---------- parseLine：整行解析 ----------

    @Test
    fun `normal app process line`() {
        val r = parse("12.5 142M 1234 u0_a525 S com.example.app")!!
        assertEquals(12.5f, r.cpu)
        assertEquals(145_408L, r.resKb)
        assertEquals(1234, r.pid)
        assertEquals("u0_a525", r.user)
        assertEquals("S", r.state)
        assertEquals("com.example.app", r.args)
    }

    @Test
    fun `args containing spaces are kept intact`() {
        val r = parse("0.0 9160K 567 root R /system/bin/some_daemon --flag value")!!
        assertEquals("/system/bin/some_daemon --flag value", r.args)
        assertEquals("root", r.user)
    }

    @Test
    fun `extra whitespace between columns is tolerated`() {
        val r = parse("  12.5   142M    1234   u0_a525   S   com.example.app  ")!!
        assertEquals(12.5f, r.cpu)
        assertEquals("com.example.app", r.args)
    }

    @Test
    fun `malformed lines return null`() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("12.5 142M 1234"))          // 列数不足
        assertNull(parse("abc 142M 1234 root S foo")) // cpu 非数字
        assertNull(parse("12.5 142M abc root S foo")) // pid 非数字
    }
}
