package com.qin.paperreader.engine

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.util.LruCache
import com.qin.paperreader.text.BookText
import kotlin.math.ceil

/**
 * 排版参数（全部像素）。测量与绘制共用同一个 LayoutEngine 实例，
 * 是「不漏字、不重复、不截行」一致性的保证来源。
 */
class PageSpec(
    val textSizePx: Float,
    val typeface: Typeface?,
    val lineSpacingMult: Float,
    val paraSpacingPx: Float,
    val indentChars: Int,
    val pageWidth: Int,
    val pageHeight: Int,
    val marginH: Float,
    val marginV: Float,
) {
    val contentWidth: Float =
        (pageWidth - 2f * marginH).coerceAtLeast(textSizePx * 4f)
    val contentWidthInt: Int = ceil(contentWidth.toDouble()).toInt()
    val contentHeight: Float =
        (pageHeight - 2f * marginV).coerceAtLeast(textSizePx * 2f)
}

/**
 * 测量引擎：
 * - 每个段落用 StaticLayout 测断行（首行缩进用 LeadingMarginSpan 参与断行计算，不改写原文）；
 * - 超长段落（无换行的病态文件）切成若干测量块，块间无缩进无段距，视觉上仍是连续一段；
 * - 章节标题段落居中绘制（同一画笔测量与绘制，metrics 不变）；
 * - linesOf 的结果同时供分页装箱与页面绘制使用，天然一致。
 */
class LayoutEngine(
    val book: BookText,
    val spec: PageSpec,
    titleParas: Set<Int>,
) {
    private val body: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = spec.textSizePx
        typeface = spec.typeface ?: Typeface.SANS_SERIF
    }
    private val fm = Paint.FontMetricsInt().also { body.getFontMetricsInt(it) }

    val bodyPaint: TextPaint get() = body
    val ascent: Float get() = fm.ascent.toFloat()
    val lineHeight: Float = (fm.descent - fm.ascent) * spec.lineSpacingMult
    val indentPx: Float = spec.indentChars * spec.textSizePx
    val titleParaSet: Set<Int> = titleParas

    private val linesCache = LruCache<String, PartLines>(64)

    fun isTitlePara(pi: Int): Boolean = titleParaSet.contains(pi)

    fun partCount(pi: Int): Int {
        val len = book.para(pi).length
        return if (len <= PART_THRESHOLD) 1 else (len + PART_TARGET - 1) / PART_TARGET
    }

    /** 把超长段落切成测量块；尽量在空白处断开，避免拆散英文单词。返回全书绝对区间。 */
    fun partRange(pi: Int, part: Int): Pair<Int, Int> {
        val p = book.para(pi)
        if (partCount(pi) == 1) return p.start to p.end
        val from = p.start + part * PART_TARGET
        val rawTo = minOf(p.end, from + PART_TARGET)
        if (rawTo >= p.end) return from to p.end
        var to = rawTo
        val searchFrom = maxOf(from + PART_TARGET / 2, rawTo - 120)
        var i = rawTo - 1
        while (i >= searchFrom) {
            val c = book.text[i]
            if (c == ' ' || c == '\t') {
                to = i + 1
                break
            }
            i--
        }
        return from to to
    }

    fun linesOf(pi: Int, part: Int): PartLines {
        val key = "$pi:$part"
        linesCache.get(key)?.let { return it }
        val (ps, pe) = partRange(pi, part)
        val sub = book.text.substring(ps, pe)
        val ss = SpannableString(sub)
        if (part == 0 && spec.indentChars > 0 && !isTitlePara(pi)) {
            ss.setSpan(
                LeadingMarginSpan.Standard(indentPx.toInt(), 0),
                0, sub.length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val layout = StaticLayout.Builder.obtain(ss, 0, ss.length, body, spec.contentWidthInt)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
        val n = layout.lineCount
        val starts = IntArray(n)
        val ends = IntArray(n)
        for (i in 0 until n) {
            starts[i] = ps + layout.getLineStart(i)
            ends[i] = ps + layout.getLineEnd(i)
        }
        val pl = PartLines(n, starts, ends)
        linesCache.put(key, pl)
        return pl
    }

    companion object {
        private const val PART_TARGET = 4000
        private const val PART_THRESHOLD = 6000
    }
}
