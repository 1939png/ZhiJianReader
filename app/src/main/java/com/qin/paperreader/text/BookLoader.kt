package com.qin.paperreader.text

/** 书籍加载与解析：字节 → 解码 → 段落切分 → 章节识别。全部为纯 JVM 逻辑，可单测。 */
object BookLoader {

    /** 按指定编码把字节解码并解析结构。 */
    fun load(bytes: ByteArray, id: EncodingDetector.Id): BookText {
        val text = EncodingDetector.decode(bytes, id)
        return parseText(text)
    }

    /**
     * 段落切分规则：一行一个段落（TXT 小说通用格式）。
     * 兼容 \r\n、\n、\r 三种换行；只含空白的行不生成段落。
     * ParaRef 保留原始字符区间，正文绘制与进度定位都使用全书绝对偏移。
     */
    fun parseText(text: String): BookText {
        val paras = ArrayList<ParaRef>()
        var i = 0
        val n = text.length
        while (i < n) {
            var nl = text.indexOf('\n', i)
            if (nl < 0) nl = n
            var end = nl
            if (end > i && text[end - 1] == '\r') end--
            if (hasVisible(text, i, end)) paras.add(ParaRef(i, end))
            i = nl + 1
        }
        val chapters = ChapterParser.parse(text, paras)
        return BookText(text, paras, chapters)
    }

    private fun hasVisible(text: String, s: Int, e: Int): Boolean {
        for (i in s until e) {
            if (!text[i].isWhitespace()) return true
        }
        return false
    }
}
