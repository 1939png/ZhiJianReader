package com.qin.paperreader.reader

import com.qin.paperreader.engine.LayoutEngine
import com.qin.paperreader.engine.PageAssembler
import com.qin.paperreader.engine.PageData
import com.qin.paperreader.text.BookText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * 分页控制器：
 * - 以章节为排版单元（无章节的书是单个「全文」章节；超大无换行段落在引擎内切块）；
 * - 渐进排版：先排当前章，再向后、向前铺开；背景线程执行，可取消、可换代；
 * - 引擎换代（字号/边距/屏幕尺寸变化）时清空全部页缓存并重新从定位页开始。
 */
class PaginationController(
    private val scope: CoroutineScope,
    private val book: BookText,
    private val engineProvider: () -> LayoutEngine,
) {
    private val pages = AtomicReferenceArray<List<PageData>>(book.chapters.size)
    private val chapterLocks = ConcurrentHashMap<Int, Mutex>()
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress

    @Volatile
    var generation: Long = 0
        private set

    private var bgJob: Job? = null

    fun pagesOf(ch: Int): List<PageData>? = pages.get(ch)

    fun pageCount(ch: Int): Int = pages.get(ch)?.size ?: 0

    fun isPaginated(ch: Int): Boolean = pages.get(ch) != null

    fun lockFor(ch: Int): Mutex = chapterLocks.getOrPut(ch) { Mutex() }

    /** 排版指定章节（幂等；同一章节并发调用只排一次）。 */
    suspend fun ensureChapter(ch: Int): List<PageData> {
        pages.get(ch)?.let { return it }
        return lockFor(ch).withLock {
            pages.get(ch)?.let { return it }
            val eng = engineProvider()
            val chapter = book.chapters[ch]
            val list = withContext(Dispatchers.Default) {
                PageAssembler.assemble(
                    chapterIdx = ch,
                    paras = book.paras,
                    paraFrom = chapter.paraStart,
                    paraTo = chapter.paraEnd,
                    partCount = eng::partCount,
                    linesOf = eng::linesOf,
                    contentHeight = eng.spec.contentHeight,
                    lineHeight = eng.lineHeight,
                    paraSpacing = eng.spec.paraSpacingPx,
                )
            }
            pages.set(ch, list)
            list
        }
    }

    /** 二分定位：字符偏移 → (章, 章内页)。会先确保所在章已排版。 */
    suspend fun locate(offset: Int): PageRef {
        val off = offset.coerceIn(0, maxOf(0, book.charCount - 1))
        val ch = book.chapterIndexFor(off)
        val list = ensureChapter(ch)
        var lo = 0
        var hi = list.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startOffset <= off) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return PageRef(ch, ans)
    }

    /** 从某章开始渐进排版全书：当前章 → 之后的章 → 之前的章。 */
    fun startProgressive(fromChapter: Int, onChapterDone: (Int) -> Unit) {
        bgJob?.cancel()
        bgJob = scope.launch(Dispatchers.Default) {
            val n = book.chapters.size
            val order = ArrayList<Int>(n)
            order.add(fromChapter.coerceIn(0, n - 1))
            for (i in fromChapter + 1 until n) order.add(i)
            for (i in 0 until fromChapter) order.add(i)
            var done = 0
            for (ch in order) {
                if (pages.get(ch) == null) {
                    runCatching { ensureChapter(ch) }
                    onChapterDone(ch)
                }
                done++
                _progress.value = done.toFloat() / n
            }
            _progress.value = null
        }
    }

    /** 引擎换代：清空页缓存，推进代计数，取消后台任务。 */
    fun invalidateAll() {
        generation++
        bgJob?.cancel()
        bgJob = null
        for (i in 0 until pages.length()) pages.set(i, null)
        _progress.value = null
    }
}
