package com.qin.paperreader

import com.qin.paperreader.engine.PageAssembler
import com.qin.paperreader.engine.PartLines
import com.qin.paperreader.engine.PageData
import com.qin.paperreader.text.ParaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯逻辑分页装箱器测试：用假断行数据验证高度装箱、跨页拆段、兜底与偏移连续性。 */
class PageAssemblerTest {

    /** 构造 lines 行的假段落：每行 10 个字符，起点 base。 */
    private fun fakeLines(base: Int, lines: Int) = PartLines(
        lineCount = lines,
        starts = IntArray(lines) { base + it * 10 },
        ends = IntArray(lines) { base + it * 10 + 10 },
    )

    private fun parasOf(lineCounts: List<Int>): List<ParaRef> {
        var base = 0
        return lineCounts.map { n ->
            val p = ParaRef(base, base + n * 10)
            base += n * 10 + 2 // 段间换行 2 字符
            p
        }
    }

    private fun assemble(
        lineCounts: List<Int>,
        contentH: Float,
        lineH: Float = 10f,
        spacing: Float = 5f,
    ): List<PageData> {
        val paras = parasOf(lineCounts)
        return PageAssembler.assemble(
            chapterIdx = 0,
            paras = paras,
            paraFrom = 0,
            paraTo = paras.size,
            partCount = { 1 },
            linesOf = { pi, _ -> fakeLines(paras[pi].start, lineCounts[pi]) },
            contentHeight = contentH,
            lineHeight = lineH,
            paraSpacing = spacing,
        )
    }

    @Test
    fun `按高度装箱并计入段距`() {
        // 段0: 3 行；contentH=35 → 3 行(30)+段距5 恰好满页
        val pages = assemble(listOf(3), contentH = 35f)
        assertEquals(1, pages.size)
        assertEquals(1, pages[0].slices.size)
        assertEquals(3, pages[0].slices[0].lineCount)
        assertEquals(0, pages[0].startOffset)
        assertEquals(30, pages[0].endOffset)
    }

    @Test
    fun `段落跨页拆分`() {
        // 段0: 5 行，页容 3 行 → 3 + 2 两页
        val pages = assemble(listOf(5), contentH = 35f)
        assertEquals(2, pages.size)
        assertEquals(3, pages[0].slices[0].lineCount)
        assertEquals(2, pages[1].slices[0].lineCount)
        // 偏移连续：同一段继续，页2 从页1 终点无缝衔接（行界相等）
        assertEquals(pages[0].endOffset, pages[1].startOffset)
        // 第二页从第 4 行开始（0 起始索引为 3）
        assertEquals(3, pages[1].slices[0].startLine)
    }

    @Test
    fun `多段落依次填充`() {
        // contentH=35: 段0(3行) 满页 → 段1(5行) 3行后放不下 → 新页 2行+段1 → 段2(1行) 再进
        val pages = assemble(listOf(3, 5, 1), contentH = 35f)
        assertEquals(3, pages.size)
        // 第2页：段1 剩余 2 行 + 段2 的 1 行（20+5段距+10=35）
        assertEquals(1, pages[1].slices.size)
        assertEquals(2, pages[2].slices.size)
        assertEquals(1, pages[2].slices[0].paraIdx)
        assertEquals(2, pages[2].slices[1].paraIdx)
    }

    @Test
    fun `行高大于页高时每页兜底一行`() {
        val pages = assemble(listOf(3), contentH = 8f, lineH = 10f)
        assertEquals(3, pages.size)
        pages.forEach { assertEquals(1, it.slices[0].lineCount) }
    }

    @Test
    fun `空段落区间不产生页`() {
        val paras = emptyList<ParaRef>()
        val pages = PageAssembler.assemble(0, paras, 0, 0, { 1 }, { _, _ -> PartLines(0, IntArray(0), IntArray(0)) }, 100f, 10f, 5f)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun `页偏移覆盖全部文本不遗漏`() {
        val lineCounts = listOf(2, 4, 3, 6, 1)
        val pages = assemble(lineCounts, contentH = 35f)
        // 第一页从第一段行首开始，最后一页到最后一行行尾结束（含段间换行占位）
        assertEquals(0, pages.first().startOffset)
        val total = lineCounts.sum() * 10 + (lineCounts.size - 1) * 2
        assertEquals(total, pages.last().endOffset)
        // 相邻页偏移单调递增
        for (i in 1 until pages.size) {
            assertTrue(pages[i].startOffset >= pages[i - 1].endOffset)
        }
    }
}
