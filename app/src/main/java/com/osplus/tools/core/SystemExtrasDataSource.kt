package com.osplus.tools.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * shell 身份可读、但此前未采集的「扩展系统指标」。
 *
 * ## 为什么单独一个数据源
 *
 * 这些节点有一个共同点：**应用身份读不到，shell 身份读得到**
 * （实测 uid 10548 对 `/proc/loadavg`、`/proc/net/dev`、`/proc/diskstats`
 * 一律 Permission denied，而 shell 全部可读）。因此它们必须走提权通道，
 * 也就必须和 [CpuDataSource] 里那些「应用可直读」的节点分开——
 * 后者没有通道也能出数据，前者没有通道只能显示「不可读」。
 *
 * ## 覆盖范围（均实测 shell 可读）
 *
 * | 节点 | 指标 |
 * |---|---|
 * | `/proc/loadavg` | 1/5/15 分钟平均负载 + 运行进程数 |
 * | `/proc/net/dev` | 各网卡累计收发字节 → 换算实时速率 |
 * | `/proc/pressure/io` | IO 压力（PSI，内核 5.13+） |
 * | `/proc/diskstats` | 磁盘累计读写扇区 → 换算实时速率 |
 *
 * 注：`/proc/pressure/cpu` 与 `/proc/pressure/memory` 在 Android 上对 shell
 * 同样拒绝访问，故不在采集范围内。
 *
 * ## 速率为什么需要两次采样
 *
 * 这些节点给的都是**累计值**（开机以来的总字节/总扇区），单次读取没有意义。
 * 速率由「本次累计 − 上次累计」除以时间差得到，因此本对象持有上次快照。
 * 首次调用时没有基准，速率返回 [UNREADABLE]。
 */
object SystemExtrasDataSource {

    /** 不可读哨兵，与 [LiveMetrics.UNREADABLE] 语义一致 */
    const val UNREADABLE: Long = -1L

    data class Extras(
        /** 1 分钟平均负载；负数表示不可读 */
        val load1: Float = -1f,
        val load5: Float = -1f,
        val load15: Float = -1f,
        /** 可运行进程数 / 总进程数 */
        val runningProcs: Int = -1,
        val totalProcs: Int = -1,
        /** 网络实时速率（字节/秒）；负数表示不可读（含首次采样无基准） */
        val netRxBps: Long = UNREADABLE,
        val netTxBps: Long = UNREADABLE,
        /** IO 压力 some avg10（百分比）；负数表示不可读 */
        val ioPressure10: Float = -1f,
        /** 磁盘实时读写（字节/秒） */
        val diskReadBps: Long = UNREADABLE,
        val diskWriteBps: Long = UNREADABLE,
    ) {
        val anyAvailable: Boolean
            get() = load1 >= 0f || netRxBps >= 0L || ioPressure10 >= 0f || diskReadBps >= 0L
    }

    private const val SEP = "###OSPLUS_SEP###"

    /** 上一次的累计值与时刻，用于差分出速率 */
    private var lastAt = 0L
    private var lastNetRx = -1L
    private var lastNetTx = -1L
    private var lastDiskRead = -1L
    private var lastDiskWrite = -1L

    /**
     * 采集一次。
     *
     * 用**一条** shell 命令读完四个节点再在本地解析，而不是四次独立调用：
     * 走 daemon 通道时每次往返约 0.1 秒，四次就是 0.4 秒，会拖慢整个采样周期。
     */
    suspend fun read(): Extras = withContext(Dispatchers.IO) {
        val cmd = buildString {
            append("cat /proc/loadavg 2>/dev/null\n")
            append("echo '$SEP'\n")
            append("cat /proc/net/dev 2>/dev/null\n")
            append("echo '$SEP'\n")
            append("cat /proc/pressure/io 2>/dev/null\n")
            append("echo '$SEP'\n")
            append("cat /proc/diskstats 2>/dev/null\n")
        }
        val out = Shell.run(cmd, root = true).stdout
        if (out.isBlank()) return@withContext Extras()

        val parts = out.split(SEP)
        val loadRaw = parts.getOrNull(0).orEmpty()
        val netRaw = parts.getOrNull(1).orEmpty()
        val psiRaw = parts.getOrNull(2).orEmpty()
        val diskRaw = parts.getOrNull(3).orEmpty()

        val now = SystemClock.elapsedRealtime()
        val dtMs = if (lastAt > 0L) now - lastAt else 0L

        // ---- loadavg ----
        val loadFields = loadRaw.trim().split(Regex("\\s+"))
        val load1 = loadFields.getOrNull(0)?.toFloatOrNull() ?: -1f
        val load5 = loadFields.getOrNull(1)?.toFloatOrNull() ?: -1f
        val load15 = loadFields.getOrNull(2)?.toFloatOrNull() ?: -1f
        // 第 4 段形如 "4/11595"：运行中 / 总数
        val procs = loadFields.getOrNull(3)?.split("/")
        val running = procs?.getOrNull(0)?.toIntOrNull() ?: -1
        val total = procs?.getOrNull(1)?.toIntOrNull() ?: -1

        // ---- net/dev：累加除 lo 外的所有接口 ----
        var netRx = 0L
        var netTx = 0L
        var netFound = false
        netRaw.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val iface = line.substring(0, idx).trim()
            // lo 是回环，计入会让速率虚高
            if (iface == "lo") return@forEach
            val nums = line.substring(idx + 1).trim().split(Regex("\\s+"))
            // 第 0 项 rx_bytes，第 8 项 tx_bytes
            val rx = nums.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val tx = nums.getOrNull(8)?.toLongOrNull() ?: return@forEach
            netRx += rx
            netTx += tx
            netFound = true
        }

        // ---- pressure/io：取 some avg10 ----
        val psi10 = Regex("some\\s+avg10=([0-9.]+)").find(psiRaw)
            ?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: -1f

        // ---- diskstats：累加所有物理盘的读写扇区 ----
        var diskReadSectors = 0L
        var diskWriteSectors = 0L
        var diskFound = false
        diskRaw.lineSequence().forEach { line ->
            val f = line.trim().split(Regex("\\s+"))
            // 字段：major minor name reads ... sectors_read(5) writes ... sectors_written(9)
            if (f.size < 10) return@forEach
            val name = f.getOrNull(2) ?: return@forEach
            // 只统计整盘，跳过分区（分区名是整盘名 + 数字后缀）
            if (name.lastOrNull()?.isDigit() == true) return@forEach
            if (name.startsWith("loop") || name.startsWith("ram") || name.startsWith("zram")) {
                return@forEach
            }
            val rs = f.getOrNull(5)?.toLongOrNull() ?: return@forEach
            val ws = f.getOrNull(9)?.toLongOrNull() ?: return@forEach
            diskReadSectors += rs
            diskWriteSectors += ws
            diskFound = true
        }

        // ---- 差分出速率（首次没有基准，留 UNREADABLE）----
        var netRxBps = UNREADABLE
        var netTxBps = UNREADABLE
        var diskReadBps = UNREADABLE
        var diskWriteBps = UNREADABLE
        if (dtMs > 0L) {
            val sec = dtMs / 1000f
            if (netFound && lastNetRx >= 0L && netRx >= lastNetRx) {
                netRxBps = ((netRx - lastNetRx) / sec).toLong()
                netTxBps = ((netTx - lastNetTx) / sec).toLong()
            }
            if (diskFound && lastDiskRead >= 0L && diskReadSectors >= lastDiskRead) {
                // 扇区按 512 字节计
                diskReadBps = ((diskReadSectors - lastDiskRead) * 512L / sec).toLong()
                diskWriteBps = ((diskWriteSectors - lastDiskWrite) * 512L / sec).toLong()
            }
        }

        lastAt = now
        if (netFound) {
            lastNetRx = netRx
            lastNetTx = netTx
        }
        if (diskFound) {
            lastDiskRead = diskReadSectors
            lastDiskWrite = diskWriteSectors
        }

        Extras(
            load1 = load1,
            load5 = load5,
            load15 = load15,
            runningProcs = running,
            totalProcs = total,
            netRxBps = netRxBps,
            netTxBps = netTxBps,
            ioPressure10 = psi10,
            diskReadBps = diskReadBps,
            diskWriteBps = diskWriteBps,
        )
    }
}
