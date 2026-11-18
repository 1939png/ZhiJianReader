package com.qin.paperreader.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextUtils
import android.text.TextPaint
import com.qin.paperreader.text.BookText
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/** 页面主题（含纸纹开关）；颜色均为 ARGB int。 */
class PageTheme(
    val bg: Int,
    val text: Int,
    val accent: Int,
    val footer: Int,
    val textureEnabled: Boolean,
)

/** 页脚信息。 */
class FooterInfo(
    val chapterTitle: String,
    val pageInChapter: Int,
    val chapterPageCount: Int,
    val percent: Float,
)

/**
 * 页面渲染器：背景（含可选纸纹）→ 正文逐行绘制 → 页脚。
 * 绘制使用的 linesOf 与分页装箱完全同源，行位、行界、段距三处一致。
 */
class PageRenderer(
    val engine: LayoutEngine,
    private val spec: PageSpec,
) {
    private val textPaint = engine.bodyPaint
    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = engine.bodyPaint.textSize * 0.55f
    }
    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paperPaint = Paint()

    @Volatile
    var theme: PageTheme = PageTheme(0xFFFAF6EE.toInt(), 0xFF3B3128.toInt(), 0xFF8C6E4A.toInt(), 0x8E3B3128.toInt(), false)
        private set

    private var textureBitmap: Bitmap? = null
    private var textureKey: Int = 0

    fun setTheme(t: PageTheme) {
        theme = t
        textureBitmap = if (t.textureEnabled) buildTexture(t.bg) else null
        textureKey = t.bg
    }

    /** 页背色（仿真卷页的纸背使用，取背景色略微加深）。 */
    fun paperBack(): Int = darken(theme.bg, 0.94f)

    fun drawPage(canvas: Canvas, page: PageData?, footer: FooterInfo?) {
        val w = spec.pageWidth.toFloat()
        val h = spec.pageHeight.toFloat()
        val t = theme
        canvas.drawColor(t.bg)
        val tex = textureBitmap
        if (tex != null && textureKey == t.bg) {
            texturePaint.shader = BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            texturePaint.color = speckleColor(t.bg)
            canvas.drawRect(0f, 0f, w, h, texturePaint)
            texturePaint.shader = null
        }
        if (page == null) {
            footer?.let { drawFooter(canvas, it) }
            return
        }
        val book = engine.book
        textPaint.color = t.text
        var y = spec.marginV
        val ascentF = engine.ascent
        val lineH = engine.lineHeight
        val contentW = spec.contentWidth

        for (slice in page.slices) {
            val pl = engine.linesOf(slice.paraIdx, slice.partIdx)
            val isTitle = engine.isTitlePara(slice.paraIdx)
            for (li in slice.startLine until slice.startLine + slice.lineCount) {
                val ls = pl.starts[li]
                val le = pl.ends[li]
                val baseline = y - ascentF
                if (isTitle) {
                    val tw = textPaint.measureText(book.text, ls, le)
                    val x = spec.marginH + (contentW - tw) / 2f
                    textPaint.color = t.accent
                    canvas.drawText(book.text, ls, le, x, baseline, textPaint)
                    textPaint.color = t.text
                } else {
                    val x = spec.marginH + if (li == 0 && slice.partIdx == 0) engine.indentPx else 0f
                    canvas.drawText(book.text, ls, le, x, baseline, textPaint)
                }
                y += lineH
            }
            // 段后距：仅当该段在本次绘制里收尾（与装箱端条件一致）
            val paraFinished = slice.startLine + slice.lineCount >= pl.lineCount &&
                slice.partIdx == engine.partCount(slice.paraIdx) - 1
            if (paraFinished) y += spec.paraSpacingPx
        }
        footer?.let { drawFooter(canvas, it) }
    }

    private fun drawFooter(canvas: Canvas, f: FooterInfo) {
        val t = theme
        footerPaint.color = t.footer
        val bottomY = spec.pageHeight - spec.marginV * 0.42f
        val left = spec.marginH * 0.6f
        val right = spec.pageWidth - spec.marginH * 0.6f
        val maxSide = (right - left) * 0.34f

        val title = TextUtils.ellipsize(f.chapterTitle, footerPaint, maxSide, TextUtils.TruncateAt.END).toString()
        canvas.drawText(title, left, bottomY, footerPaint)

        val percent = String.format("%.1f%%", f.percent * 100f)
        val pw = footerPaint.measureText(percent)
        canvas.drawText(percent, right - pw, bottomY, footerPaint)

        if (f.chapterPageCount > 0) {
            val num = "${f.pageInChapter}/${f.chapterPageCount}"
            val nw = footerPaint.measureText(num)
            canvas.drawText(num, (spec.pageWidth - nw) / 2f, bottomY, footerPaint)
        }
    }

    private fun buildTexture(bg: Int): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val px = ByteArray(size * size)
        val rnd = Random(20261023L)
        for (i in px.indices) {
            val r = rnd.nextFloat()
            px[i] = when {
                r < 0.55f -> 0
                r < 0.85f -> (2 + rnd.nextInt(6)).toByte()
                else -> (7 + rnd.nextInt(9)).toByte()
            }
        }
        val buffer = java.nio.ByteBuffer.wrap(px)
        bmp.copyPixelsFromBuffer(buffer)
        return bmp
    }

    private fun speckleColor(bg: Int): Int =
        if (luminance(bg) > 0.5f) 0x54372A14.toInt() else 0x40FFE9C9.toInt()

    private fun luminance(c: Int): Float {
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun darken(c: Int, k: Float): Int {
        val r = ((c shr 16 and 0xFF) * k).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 0xFF) * k).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * k).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        fun clampOffset(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))
    }
}
