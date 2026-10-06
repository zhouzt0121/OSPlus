package com.osplus.tools.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.osplus.tools.model.FpsSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把一次记录会话的统计摘要绘制成 4:3 分析卡位图。
 *
 * ## 为什么不截 Compose 的图
 *
 * 界面上的卡片是 Compose 画的，理论上可以 `View.draw(Canvas)` 抓位图，但那要求
 * 视图正处于已布局状态、且要处理状态栏/圆角裁剪/密度差异，跨页面复用很脆。
 * 这里直接用 Android Canvas 精绘一份**像素级等价**的版本：尺寸固定
 * 1200×900（严格 4:3），不受屏幕密度影响，导出的图在任何设备上完全一致。
 *
 * ## 版式
 *
 * 与 [com.osplus.tools.ui.screen.FpsAnalysisCard] 对齐：顶栏三列
 * （日期 / 应用名 / crop 尺寸），中间两组四列统计格，底部样本量与时长。
 * 两组之间用发丝线分隔。
 *
 * @param session 会话元数据
 * @param stats 统计摘要
 * @param cropLabel 形如 `540x1200` 的屏幕尺寸（不带 `crop:` 前缀）
 * @param dark 是否按深色主题绘制（背景深、文字浅）
 */
object FpsCardRenderer {

    private const val W = 1200
    private const val H = 900

    fun render(
        session: FpsSession,
        stats: FpsSessionStats,
        cropLabel: String,
        dark: Boolean = false,
        /** 应用图标位图；为 null 时只画应用名 */
        appIcon: Bitmap? = null,
        /** 解析好的应用显示名（优先于 session 自带的 label/包名） */
        appName: String? = null,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // ---- 配色（与 OsTokens 的明暗两套一致）----
        val bg = if (dark) Color.parseColor("#1A1D22") else Color.WHITE
        val textPrimary = if (dark) Color.parseColor("#EEF1F5") else Color.parseColor("#1B1F24")
        val textSecondary = if (dark) Color.parseColor("#9AA2AC") else Color.parseColor("#7C848E")
        val textTertiary = if (dark) Color.parseColor("#6D757F") else Color.parseColor("#A7AEB7")
        val hairline = if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#E7EBF0")
        val accent = if (dark) Color.parseColor("#6BA5F0") else Color.parseColor("#3B7BE0")

        val pad = 56f
        // 圆角卡片底
        val cardRect = RectF(0f, 0f, W.toFloat(), H.toFloat())
        canvas.drawColor(if (dark) Color.parseColor("#0E1013") else Color.parseColor("#F1F3F6"))
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        canvas.drawRoundRect(cardRect, 40f, 40f, cardPaint)

        val left = pad
        val right = W - pad
        val contentW = right - left

        // ---- 顶栏：三列 ----
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textPrimary
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textSecondary
            textSize = 28f
        }
        val topY = pad + 40f
        canvas.drawText(fmtDate(session.timeBegin), left, topY, titlePaint)
        // 中间：图标 + 应用名居中。图标画在文字左侧，二者作为一个整体居中。
        val midName = appName?.ifBlank { null }
            ?: session.label.ifBlank { session.packageName ?: "未知应用" }
        val namePaint = Paint(titlePaint).apply { textAlign = Paint.Align.CENTER }
        val nameW = namePaint.measureText(midName)
        val iconSize = 34f
        val gap = 10f
        // 有图标时整体左移半个图标宽，保持「图标+文字」组合居中
        val groupW = if (appIcon != null) iconSize + gap + nameW else nameW
        val groupLeft = W / 2f - groupW / 2f
        if (appIcon != null) {
            val iconRect = RectF(groupLeft, topY - iconSize + 6f, groupLeft + iconSize, topY + 6f)
            canvas.drawBitmap(appIcon, null, iconRect, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
            })
        }
        namePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(midName, groupLeft + (if (appIcon != null) iconSize + gap else 0f), topY, namePaint)
        // 右侧 crop
        val cropPaint = Paint(smallPaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("crop: $cropLabel", right, topY, cropPaint)

        var y = topY + 34f

        // 发丝线 1
        drawHairline(canvas, left, right, y, hairline)
        y += 46f

        // ---- 组 1：MAX / MIN / AVG / VARIANCE / FPS ----
        drawStatRow(
            canvas, left, contentW, y,
            values = listOf(
                "%.1f".format(stats.max),
                "%.0f".format(stats.min),
                "%.1f".format(stats.avg),
                "%.1f".format(stats.variance),
            ),
            labels = listOf("MAX", "MIN", "AVG", "VARIANCE"),
            units = listOf("FPS", "FPS", "FPS", "FPS"),
            textPrimary = textPrimary, textSecondary = textSecondary, textTertiary = textTertiary,
        )
        y += 176f

        // 发丝线 2
        drawHairline(canvas, left, right, y, hairline)
        y += 46f

        // ---- 组 2：≥60FPS / 5% Low / MAX / AVG ----
        drawStatRow(
            canvas, left, contentW, y,
            values = listOf(
                "%.2f%%".format(stats.smoothRatio),
                "%.1f".format(stats.fivePercentLow),
                "%.1f".format(stats.avgTempC),
                "%.0f".format(stats.avgPowerMw / 1000f),
            ),
            labels = listOf("≥60FPS", "5% Low AVG", "Temp AVG", "Power AVG"),
            units = listOf("Smoothness", "FPS", "℃", "W"),
            textPrimary = textPrimary, textSecondary = textSecondary, textTertiary = textTertiary,
        )
        y += 176f

        // 发丝线 3
        drawHairline(canvas, left, right, y, hairline)
        y += 46f

        // ---- 底栏：样本量 / 时长 / 署名 ----
        val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textTertiary
            textSize = 26f
        }
        canvas.drawText("样本 ${stats.count}", left, y, footPaint)
        canvas.drawText(
            if (session.durationMs > 0) fmtElapsed(session.durationMs) else "未正常结束",
            left + 220f, y, footPaint,
        )
        val brandPaint = Paint(footPaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("OSPlus", right, y, brandPaint)
        // 品牌小圆点
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        canvas.drawCircle(right - measureText(brandPaint, "OSPlus") - 26f, y - 9f, 8f, dot)

        return bmp
    }

    /** 画一组四列统计格：每列是「数值 → 标签 → 单位」竖排三段。 */
    private fun drawStatRow(
        canvas: Canvas,
        left: Float,
        contentW: Float,
        topY: Float,
        values: List<String>,
        labels: List<String>,
        units: List<String>,
        textPrimary: Int,
        textSecondary: Int,
        textTertiary: Int,
    ) {
        val colW = contentW / values.size
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textPrimary
            textSize = 62f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textSecondary
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textTertiary
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }
        values.forEachIndexed { i, v ->
            val cx = left + colW * i + colW / 2f
            canvas.drawText(v, cx, topY + 62f, valuePaint)
            canvas.drawText(labels.getOrElse(i) { "" }, cx, topY + 102f, labelPaint)
            canvas.drawText(units.getOrElse(i) { "" }, cx, topY + 132f, unitPaint)
        }
    }

    /** 两端淡出的发丝分隔线，与 Compose 版的 Hairline 视觉一致。 */
    private fun drawHairline(canvas: Canvas, left: Float, right: Float, y: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                left, y, right, y,
                intArrayOf(Color.TRANSPARENT, color, color, Color.TRANSPARENT),
                floatArrayOf(0f, 0.08f, 0.92f, 1f),
                Shader.TileMode.CLAMP,
            )
            strokeWidth = 2f
        }
        canvas.drawLine(left, y, right, y, p)
    }

    private fun measureText(p: Paint, s: String): Float = p.measureText(s)

    private fun fmtDate(ms: Long): String =
        if (ms <= 0L) "未知时间"
        else SimpleDateFormat("yyyy/M/d HH:mm", Locale.US).format(Date(ms))

    private fun fmtElapsed(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
