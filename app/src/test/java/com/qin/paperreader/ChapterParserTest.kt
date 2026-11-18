package com.qin.paperreader

import com.qin.paperreader.text.BookLoader
import com.qin.paperreader.text.ChapterParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterParserTest {

    private fun parse(text: String) = BookLoader.parseText(text)

    @Test
    fun `数字与中文数字章节标题`() {
        val text = """
            第一章 起点
            天色微明。
            第001章 回到最初
            又是一年。
            第九百二十一章 天劫
            雷云压顶。
        """.trimIndent()
        val book = parse(text)
        assertEquals(3, book.chapters.size)
        assertEquals("第一章 起点", book.chapters[0].title)
        assertEquals("第001章 回到最初", book.chapters[1].title)
        assertEquals("第九百二十一章 天劫", book.chapters[2].title)
    }

    @Test
    fun `特殊标题与番外`() {
        val text = """
            序章
            序幕拉开。
            楔子
            引子之后。
            番外一：春日宴
            番外内容。
            终章
            完。
        """.trimIndent()
        val book = parse(text)
        val titles = book.chapters.map { it.title }
        assertTrue(titles.contains("序章"))
        assertTrue(titles.contains("楔子"))
        assertTrue(titles.contains("番外一：春日宴"))
        assertTrue(titles.contains("终章"))
    }

    @Test
    fun `开篇章节`() {
        val text = """
            这是一个没有标题的开头。
            两行之后才是第一章。
            第一章 正式开始
            正文。
        """.trimIndent()
        val book = parse(text)
        assertEquals("开篇", book.chapters[0].title)
        assertEquals("第一章 正式开始", book.chapters[1].title)
        // 开篇覆盖到第一章标题行之前
        assertTrue(book.chapters[0].charEnd <= book.chapters[1].charStart)
    }

    @Test
    fun `正文里提到第一章不误判`() {
        val text = """
            第一章 山雨欲来
            老人说：第一章的规矩不能破，这是祖师爷定下的，我们都得遵守，谁也不能例外。
            正文继续。
        """.trimIndent()
        val book = parse(text)
        assertEquals(1, book.chapters.size)
        assertEquals("第一章 山雨欲来", book.chapters[0].title)
    }

    @Test
    fun `无章节全文单章`() {
        val text = """
            这是一本没有章节的书。
            第二段。
        """.trimIndent()
        val book = parse(text)
        assertEquals(1, book.chapters.size)
        assertEquals("全文", book.chapters[0].title)
        assertEquals(0, book.chapters[0].charStart)
    }

    @Test
    fun `英文章节`() {
        val text = """
            Chapter 1: The Beginning
            It was a dark night.
            chapter 2
            Another day.
        """.trimIndent()
        val book = parse(text)
        assertEquals(2, book.chapters.size)
        assertEquals("Chapter 1: The Beginning", book.chapters[0].title)
    }

    @Test
    fun `isTitle 的边界`() {
        assertTrue(ChapterParser.isTitle("第 12 章 夜航"))
        assertTrue(ChapterParser.isTitle("卷一"))
        assertTrue(ChapterParser.isTitle("第二章"))
        assertFalse(ChapterParser.isTitle("第一章的约定，那天下着雨。"))
        assertFalse(ChapterParser.isTitle("他说第3章还没写完，所以今天先发这一章，大家不要急。"))
        assertFalse(ChapterParser.isTitle(""))
        assertFalse(ChapterParser.isTitle("第一"))
    }

    @Test
    fun `空章节被跳过且偏移单调`() {
        val text = """
            第一章 A
            内容。
            第二章 B
            第二章 C
            又是内容。
        """.trimIndent()
        val book = parse(text)
        // 「第二章 B」与「第二章 C」之间没有正文段落，后者与前者标题段落重合，只保留有内容的章节
        assertTrue(book.chapters.size >= 2)
        for (i in 1 until book.chapters.size) {
            assertTrue(book.chapters[i].charStart >= book.chapters[i - 1].charStart)
        }
    }

    @Test
    fun `chapterIndexFor 二分定位`() {
        val text = """
            第一章 A
            aaa
            第二章 B
            bbb
        """.trimIndent()
        val book = parse(text)
        assertEquals(0, book.chapterIndexFor(0))
        assertEquals(1, book.chapterIndexFor(book.chapters[1].charStart))
        assertEquals(1, book.chapterIndexFor(book.charCount - 1))
    }
}
