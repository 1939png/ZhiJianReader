package com.qin.paperreader.engine

import com.qin.paperreader.text.ParaRef

/** 一页内的一个绘制片段：第 paraIdx 段的第 partIdx 测量块，从 startLine 起共 lineCount 行。 */
class Slice(val paraIdx: Int, val partIdx: Int, val startLine: Int, val lineCount: Int)

/** 一页：由若干片段组成，startOffset/endOffset 为全书绝对字符偏移（含首行起点、末行终点）。 */
class PageData(
    val chapterIdx: Int,
    val startOffset: Int,
    val endOffset: Int,
    val slices: List<Slice>,
)

/** 某段某测量块的断行信息；offsets 均为全书绝对字符下标。 */
class PartLines(val lineCount: Int, val starts: IntArray, val ends: IntArray)

/**
 * 纯逻辑分页装箱器（不依赖 Android，可单测）：
 * 给定每个段落测量块的断行信息，按可用高度逐行装箱成页。
 *
 * 高度模型：每行占 lineHeight；段落最后一行之后追加 paraSpacing（即使段落结束在页底也计入，
 * 与绘制端一致，视觉上不可见，保证两端不漂移）。
 */
object PageAssembler {

    fun assemble(
        chapterIdx: Int,
        paras: List<ParaRef>,
        paraFrom: Int,
        paraTo: Int,
        partCount: (Int) -> Int,
        linesOf: (Int, Int) -> PartLines,
        contentHeight: Float,
        lineHeight: Float,
        paraSpacing: Float,
    ): List<PageData> {
        require(contentHeight > 0f) { "contentHeight must be positive" }
        require(lineHeight > 0f) { "lineHeight must be positive" }

        val pages = ArrayList<PageData>()
        var slices = ArrayList<Slice>()
        var used = 0f
        var pageStart = -1
        var pageEnd = -1

        fun close() {
            if (slices.isNotEmpty()) {
                pages.add(PageData(chapterIdx, pageStart, pageEnd, slices))
            }
            slices = ArrayList()
            used = 0f
            pageStart = -1
            pageEnd = -1
        }

        for (pi in paraFrom until paraTo) {
            val parts = partCount(pi)
            for (part in 0 until parts) {
                val pl = linesOf(pi, part)
                var line = 0
                while (line < pl.lineCount) {
                    var fit = ((contentHeight - used) / lineHeight).toInt()
                    if (fit < 1) {
                        if (slices.isEmpty()) {
                            fit = 1 // 极端字号/极小页面的兜底：每页至少放一行，保证推进
                        } else {
                            close()
                            continue
                        }
                    }
                    val take = minOf(pl.lineCount - line, fit)
                    val ls = pl.starts[line]
                    val le = pl.ends[line + take - 1]
                    if (pageStart < 0) pageStart = ls
                    pageEnd = le
                    slices.add(Slice(pi, part, line, take))
                    used += take * lineHeight
                    line += take
                    if (line >= pl.lineCount && part == parts - 1) {
                        used += paraSpacing
                    }
                }
            }
        }
        close()
        return pages
    }
}
