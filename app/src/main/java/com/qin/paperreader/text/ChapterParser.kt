package com.qin.paperreader.text

/**
 * 章节标题识别：识别「第一章」「第001章」「第九百二十章」「第 12 卷」「序章」「番外」
 * 「Chapter 3」等常见写法。只把「独立成行的短行」当标题，正文中提到“第一章”不会被误判。
 */
object ChapterParser {

    private val SPECIAL = hashSetOf(
        "序", "序章", "序幕", "序言", "序语", "自序", "题记", "凡例", "楔子", "引子", "前言",
        "番外", "番外篇", "外传", "后传", "终章", "终幕", "尾声", "后记", "代后记", "结局", "大结局",
        "最终章", "完结", "完本感言", "附录", "正文", "上部", "下部", "上篇", "下篇", "中部", "中篇",
    )

    private const val CN_NUM = "[0-9０-９]{1,7}|[零一二两三四五六七八九十百千万亿]{1,9}"
    private val numberedRegex = Regex("^第[ 　]*($CN_NUM)[ 　]*[章回节卷部篇集幕]")
    private val volumeRegex = Regex("^卷[ 　]*($CN_NUM)([ 　].*)?$")
    private val chapterEnRegex = Regex("(?i)^chapter[ 　]+(\\d{1,4}|[ivxlcdm]{1,10})([ 　]*[:：.．\\-—].*)?$")

    private const val MAX_TITLE_LEN = 50
    private const val MAX_TITLE_REST_LEN = 42
    private val forbidden = charArrayOf('。', '！', '？', '；', '，', '…', '!', '?', ';', ',')

    /** 该行是否像章节标题（传整段 trim 后的文本）。 */
    fun isTitle(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > MAX_TITLE_LEN) return false
        if (t in SPECIAL) return true
        if (t.startsWith("番外") && t.length <= 30 && !hasForbidden(t)) return true
        if (t.startsWith("第")) {
            val m = numberedRegex.find(t) ?: return false
            val rest = t.substring(m.value.length)
            if (rest.length > MAX_TITLE_REST_LEN) return false
            return !hasForbidden(rest)
        }
        if (t.startsWith("卷") && volumeRegex.matches(t)) return true
        if (chapterEnRegex.containsMatchIn(t)) return true
        return false
    }

    private fun hasForbidden(s: String): Boolean = s.indexOfAny(forbidden) >= 0

    /**
     * 由段落区间推导章节区间：
     * - 首个标题之前的正文作为「开篇」章节；
     * - 空章节（两标题间无正文段落）会被跳过；
     * - 完全没有标题时返回单个「全文」章节。
     */
    fun parse(text: String, paras: List<ParaRef>): List<ChapterSpan> {
        val marks = ArrayList<Triple<String, Int, Int>>() // title, paraIdx, charStart
        for (i in paras.indices) {
            val p = paras[i]
            val s = text.substring(p.start, p.end)
            if (isTitle(s)) marks.add(Triple(s.trim(), i, p.start))
        }
        if (marks.isEmpty()) {
            return listOf(ChapterSpan("全文", 0, paras.size, 0, text.length))
        }

        val out = ArrayList<ChapterSpan>(marks.size + 1)
        val first = marks.first()
        if (first.third > 0 && text.substring(0, first.third).any { !it.isWhitespace() }) {
            out.add(ChapterSpan("开篇", 0, first.second, 0, first.third))
        }
        for (j in marks.indices) {
            val (title, pi, cs) = marks[j]
            val pe = if (j + 1 < marks.size) marks[j + 1].second else paras.size
            val ce = if (j + 1 < marks.size) marks[j + 1].third else text.length
            if (pe > pi) out.add(ChapterSpan(title, pi, pe, cs, ce))
        }
        return out
    }
}
