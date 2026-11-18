package com.qin.paperreader.text

/** 一段正文在整本书字符流中的区间 [start, end)。偏移 = 解码后文本的 UTF-16 字符下标。 */
data class ParaRef(val start: Int, val end: Int) {
    val length: Int get() = end - start
}

/**
 * 章节区间：覆盖 [charStart, charEnd)，对应段落 [paraStart, paraEnd)。
 * 标题行本身属于本章第一个段落。
 */
data class ChapterSpan(
    val title: String,
    val paraStart: Int,
    val paraEnd: Int,
    val charStart: Int,
    val charEnd: Int,
)

/**
 * 解析完成的整本书。text 是唯一权威数据源：
 * 阅读进度、书签、搜索结果全部保存「字符偏移」（UTF-16 code unit 下标），
 * 与文件字节偏移的换算只发生在解码这一步（由 charset 决定），持久化一律使用字符偏移。
 */
class BookText(
    val text: String,
    val paras: List<ParaRef>,
    val chapters: List<ChapterSpan>,
) {
    val charCount: Int get() = text.length

    fun para(pi: Int): ParaRef = paras[pi]

    /** 二分查找字符偏移所属章节。 */
    fun chapterIndexFor(offset: Int): Int {
        var lo = 0
        var hi = chapters.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chapters[mid].charStart <= offset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
