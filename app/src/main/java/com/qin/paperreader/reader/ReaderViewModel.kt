package com.qin.paperreader.reader

import android.app.Application
import android.graphics.Canvas
import android.net.Uri
import android.util.TypedValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.qin.paperreader.App
import com.qin.paperreader.data.db.BookEntity
import com.qin.paperreader.data.db.BookmarkEntity
import com.qin.paperreader.data.prefs.ReaderSettings
import com.qin.paperreader.engine.LayoutEngine
import com.qin.paperreader.engine.PageData
import com.qin.paperreader.engine.PageRenderer
import com.qin.paperreader.engine.PageSpec
import com.qin.paperreader.text.BookLoader
import com.qin.paperreader.text.BookText
import com.qin.paperreader.text.EncodingDetector
import com.qin.paperreader.ui.FontManager
import com.qin.paperreader.ui.Themes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

data class TocEntry(val title: String, val offset: Int)

data class ReaderUiState(
    val bookId: String,
    val title: String = "",
    val loading: Boolean = true,
    val textReady: Boolean = false,
    val error: String? = null,
    val charCount: Int = 0,
    val chapterCount: Int = 0,
    val currentOffset: Int = 0,
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val chapterPageIndex: Int = 0,
    val chapterPageCount: Int = 0,
    val percent: Float = 0f,
    val paginationProgress: Float? = null,
    val searching: Boolean = false,
    val toc: List<TocEntry> = emptyList(),
)

data class SearchHit(val offset: Int, val snippet: String, val matchStart: Int, val matchLength: Int)

/**
 * 阅读页 ViewModel：书籍装载、排版引擎生命周期、渐进分页、进度/书签/搜索/设置。
 * 全部进度与书签保存「正文字符偏移」，换字号/旋转后由 locate() 重新定位到页。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(app: Application, private val bookId: String) : AndroidViewModel(app) {

    private val graph = app as App
    private val repo = graph.repo
    private val settingsRepo = graph.settings

    private val _ui = MutableStateFlow(ReaderUiState(bookId = bookId))
    val ui: StateFlow<ReaderUiState> = _ui.asStateFlow()

    val settings: StateFlow<ReaderSettings> =
        settingsRepo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        repo.bookmarkDao.observeForBook(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchResults = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchResults: StateFlow<List<SearchHit>> = _searchResults

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events

    /** 字体列表变更信号：导入字体后递增，设置面板据此刷新字体选项。 */
    private val _fontsTick = MutableStateFlow(0)
    val fontsTick: StateFlow<Int> = _fontsTick

    private val paginationFlow = MutableStateFlow<PaginationController?>(null)

    private var entity: BookEntity? = null
    var book: BookText? = null
        private set
    private var engine: LayoutEngine? = null
    private var renderer: PageRenderer? = null
    private var pagination: PaginationController? = null

    private var canvasW = 0
    private var canvasH = 0
    private var savedOffset = 0
    private var initialRefValue: PageRef? = null
    private var pendingJumpValue: PageRef? = null
    private var lastSettings: ReaderSettings? = null
    private var searchJob: Job? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        viewModelScope.launch {
            val e = repo.bookDao.get(bookId)
            if (e == null) {
                _ui.update { it.copy(loading = false, error = "书籍不存在") }
                return@launch
            }
            entity = e
            savedOffset = e.lastOffset
            _ui.update { it.copy(title = e.title, loading = true) }
            repo.bookDao.updateProgress(e.id, e.lastOffset, System.currentTimeMillis())
            val loaded = withContext(Dispatchers.IO) { runCatching { loadBook(e) } }
            loaded.fold(
                onSuccess = { bt ->
                    book = bt
                    _ui.update {
                        it.copy(
                            loading = false,
                            textReady = true,
                            charCount = bt.charCount,
                            chapterCount = bt.chapters.size,
                            toc = bt.chapters.map { ch -> TocEntry(ch.title, ch.charStart) },
                        )
                    }
                    buildEngineIfReady()
                },
                onFailure = { ex ->
                    _ui.update { it.copy(loading = false, error = "无法读取本书：${ex.message ?: "未知错误"}") }
                }
            )
        }
        viewModelScope.launch {
            paginationFlow.flatMapLatest { it?.progress ?: flowOf<Float?>(null) }
                .collect { p -> _ui.update { it.copy(paginationProgress = p) } }
        }
        viewModelScope.launch {
            settingsRepo.flow.collect { onSettings(it) }
        }
    }

    private fun loadBook(e: BookEntity): BookText {
        val f = repo.fileOf(e)
        check(f.exists()) { "书籍文件已丢失" }
        val bytes = f.readBytes()
        val id = EncodingDetector.Id.entries.firstOrNull { it.name == e.charset }
            ?: EncodingDetector.Id.UTF8
        return BookLoader.load(bytes, id)
    }

    private fun onSettings(s: ReaderSettings) {
        val old = lastSettings
        lastSettings = s
        renderer?.setTheme(Themes.buildPageTheme(s))
        val relayout = old != null && layoutSignature(old) != layoutSignature(s)
        if (relayout && book != null && canvasW > 0) {
            rebuildEngine()
        } else {
            invalidateListeners()
        }
    }

    private fun layoutSignature(s: ReaderSettings): String =
        "${s.fontSizeSp}|${s.lineSpacingMult}|${s.paraSpacingDp}|${s.marginHDp}|${s.marginVDp}|${s.indentChars}|${s.fontKey}"

    /** 由阅读视图报告画布尺寸（px）。变化后触发（重）排版。 */
    fun setCanvasSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == canvasW && h == canvasH) return
        canvasW = w
        canvasH = h
        buildEngineIfReady()
    }

    private fun buildEngineIfReady() {
        val bt = book ?: return
        if (canvasW <= 0 || canvasH <= 0) return
        val s = settings.value
        val dm = getApplication<Application>().resources.displayMetrics
        val spec = PageSpec(
            textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, s.fontSizeSp, dm),
            typeface = FontManager.typeface(getApplication(), s.fontKey),
            lineSpacingMult = s.lineSpacingMult,
            paraSpacingPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, s.paraSpacingDp, dm),
            indentChars = s.indentChars,
            pageWidth = canvasW,
            pageHeight = canvasH,
            marginH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, s.marginHDp, dm),
            marginV = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, s.marginVDp, dm),
        )
        val titleSet = bt.chapters.map { it.paraStart }.toSet()
        engine = LayoutEngine(bt, spec, titleSet)
        renderer = PageRenderer(engine!!, spec).also { it.setTheme(Themes.buildPageTheme(s)) }
        pagination = PaginationController(viewModelScope, bt) { engine!! }
        paginationFlow.value = pagination
        pagination!!.invalidateAll()
        val keep = savedOffset.coerceIn(0, max(0, bt.charCount - 1))
        viewModelScope.launch {
            val ref = runCatching { pagination!!.locate(keep) }.getOrNull() ?: return@launch
            initialRefValue = ref
            pendingJumpValue = ref
            invalidateListeners()
            pagination!!.startProgressive(ref.chapter) { invalidateListeners() }
        }
    }

    private fun rebuildEngine() = buildEngineIfReady()

    // ---------- ReaderClient ----------

    val client: ReaderClient = object : ReaderClient {

        override fun addInvalidateListener(l: () -> Unit) {
            listeners.add(l)
        }

        override fun initialRef(): PageRef? = initialRefValue

        override fun consumeJump(): PageRef? = pendingJumpValue.also { pendingJumpValue = null }

        override fun normalizeRef(ref: PageRef): PageRef {
            val bt = book ?: return ref
            val pg = pagination ?: return ref
            val ch = ref.chapter.coerceIn(0, bt.chapters.size - 1)
            val list = pg.pagesOf(ch) ?: return PageRef(ch, 0)
            return PageRef(ch, ref.page.coerceIn(0, list.size - 1))
        }

        override fun page(ref: PageRef): PageData? =
            pagination?.pagesOf(ref.chapter)?.getOrNull(ref.page)

        override fun neighbor(cur: PageRef, forward: Boolean): Neighbor {
            val bt = book ?: return Neighbor.None
            val pg = pagination ?: return Neighbor.None
            if (cur.chapter !in bt.chapters.indices) return Neighbor.None
            val list = pg.pagesOf(cur.chapter) ?: return Neighbor.Pending(cur.chapter)
            val npg = cur.page + if (forward) 1 else -1
            if (npg in list.indices) return Neighbor.Ready(PageRef(cur.chapter, npg))
            val nch = cur.chapter + if (forward) 1 else -1
            if (nch !in bt.chapters.indices) return Neighbor.None
            val nlist = pg.pagesOf(nch)
            return if (nlist == null) {
                Neighbor.Pending(nch)
            } else if (nlist.isEmpty()) {
                Neighbor.None
            } else {
                Neighbor.Ready(PageRef(nch, if (forward) 0 else nlist.size - 1))
            }
        }

        override fun requestChapter(chapterIdx: Int) {
            viewModelScope.launch {
                runCatching { pagination?.ensureChapter(chapterIdx) }
                invalidateListeners()
            }
        }

        override fun draw(canvas: Canvas, ref: PageRef, width: Int, height: Int) {
            val r = renderer ?: return
            val fixed = normalizeRef(ref)
            val p = page(fixed)
            r.drawPage(canvas, p, footerFor(fixed, p))
        }

        override fun onSettled(ref: PageRef) {
            val bt = book ?: return
            val pg = pagination ?: return
            val list = pg.pagesOf(ref.chapter) ?: return
            val page = list.getOrNull(ref.page) ?: return
            savedOffset = page.startOffset
            val chapter = bt.chapters[ref.chapter]
            _ui.update {
                it.copy(
                    currentOffset = page.startOffset,
                    chapterIndex = ref.chapter,
                    chapterTitle = chapter.title,
                    chapterPageIndex = ref.page + 1,
                    chapterPageCount = list.size,
                    percent = if (bt.charCount > 0) page.startOffset.toFloat() / bt.charCount else 0f,
                )
            }
            persistProgress()
        }

        override fun pageBgColor(): Int = renderer?.theme?.bg ?: 0xFFFAF6EE.toInt()

        override fun onCanvasSize(width: Int, height: Int) {
            setCanvasSize(width, height)
        }
    }

    private fun footerFor(ref: PageRef, page: PageData?): com.qin.paperreader.engine.FooterInfo? {
        val bt = book ?: return null
        val ch = bt.chapters.getOrNull(ref.chapter) ?: return null
        val count = pagination?.pageCount(ref.chapter) ?: 0
        val percent = when {
            bt.charCount <= 0 -> 0f
            page != null -> page.startOffset.toFloat() / bt.charCount
            else -> savedOffset.toFloat() / bt.charCount
        }
        return com.qin.paperreader.engine.FooterInfo(ch.title, ref.page + 1, count, percent)
    }

    /**
     * 进度存档：每次页面落定（翻页/跳转）立即写库，不再防抖。
     * 若用防抖，翻页后立刻划掉后台会丢失最后一次写入，导致“中途杀后台进度丢失”。
     */
    private fun persistProgress() {
        val e = entity ?: return
        val off = savedOffset
        viewModelScope.launch(Dispatchers.IO) {
            repo.bookDao.updateProgress(e.id, off, System.currentTimeMillis())
        }
    }

    /**
     * 兕底强存（同步、限时）：页面进入后台（ON_STOP）时调用。
     * 小型 UPDATE 通常几毫秒完成；限时 150ms，避免阻塞主线程。
     * 划掉后台前系统必先触发 ON_STOP，这条路径能接住几乎所有中途退出场景。
     */
    fun flushProgressNow() {
        val e = entity ?: return
        val off = savedOffset
        runBlocking {
            withTimeoutOrNull(150) {
                withContext(Dispatchers.IO) {
                    repo.bookDao.updateProgress(e.id, off, System.currentTimeMillis())
                }
            }
        }
    }

    private fun invalidateListeners() {
        for (l in listeners) runCatching { l() }
    }

    // ---------- 跳转 ----------

    fun jumpToOffset(offset: Int) {
        val bt = book ?: return
        val pg = pagination ?: return
        viewModelScope.launch {
            val target = offset.coerceIn(0, max(0, bt.charCount - 1))
            val ref = runCatching { pg.locate(target) }.getOrNull() ?: return@launch
            pendingJumpValue = ref
            invalidateListeners()
            pg.startProgressive(ref.chapter) { invalidateListeners() }
        }
    }

    fun jumpToFraction(f: Float) {
        val bt = book ?: return
        jumpToOffset((f.coerceIn(0f, 1f) * bt.charCount).toInt())
    }

    fun jumpToChapter(index: Int) {
        val bt = book ?: return
        val ch = bt.chapters.getOrNull(index) ?: return
        jumpToOffset(ch.charStart)
    }

    fun stepChapter(delta: Int) = jumpToChapter(_ui.value.chapterIndex + delta)

    // ---------- 书签 ----------

    fun addBookmark(note: String = "") {
        val bt = book ?: return
        val off = savedOffset.coerceIn(0, max(0, bt.charCount - 1))
        viewModelScope.launch {
            repo.bookmarkDao.add(
                BookmarkEntity(
                    bookId = bookId,
                    offset = off,
                    preview = bt.text.substring(off, minOf(bt.charCount, off + 64))
                        .replace(Regex("\\s+"), " ").trim(),
                    note = note,
                    createdAt = System.currentTimeMillis(),
                )
            )
            _events.tryEmit("已添加书签")
        }
    }

    fun updateBookmark(b: BookmarkEntity, note: String) {
        viewModelScope.launch { repo.bookmarkDao.update(b.copy(note = note)) }
    }

    fun deleteBookmark(b: BookmarkEntity) {
        viewModelScope.launch {
            repo.bookmarkDao.delete(b)
            _events.tryEmit("已删除书签")
        }
    }

    // ---------- 搜索 ----------

    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            _searchResults.value = emptyList()
            _ui.update { it.copy(searching = false) }
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            _ui.update { it.copy(searching = true) }
            val bt = book
            if (bt == null) {
                _ui.update { it.copy(searching = false) }
                return@launch
            }
            val text = bt.text
            val hits = ArrayList<SearchHit>()
            var from = 0
            while (hits.size < 200) {
                val idx = text.indexOf(q, from, ignoreCase = true)
                if (idx < 0) break
                val cs = max(0, idx - 42)
                val ce = minOf(text.length, idx + q.length + 42)
                hits.add(
                    SearchHit(
                        offset = idx,
                        snippet = text.substring(cs, ce).replace(Regex("\\s+"), " "),
                        matchStart = idx - cs,
                        matchLength = q.length,
                    )
                )
                from = idx + q.length
            }
            _searchResults.value = hits
            _ui.update { it.copy(searching = false) }
        }
    }

    // ---------- 设置 ----------

    fun updateSettings(block: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settingsRepo.update(block) }
    }

    fun importFont(uri: Uri) {
        viewModelScope.launch {
            FontManager.import(getApplication(), uri)
                .onSuccess {
                    _events.tryEmit("字体已导入：$it")
                    _fontsTick.value = _fontsTick.value + 1
                }
                .onFailure { _events.tryEmit("导入失败：${it.message ?: "未知错误"}") }
        }
    }

    companion object {
        fun factory(app: Application, bookId: String) = viewModelFactory {
            initializer { ReaderViewModel(app, bookId) }
        }
    }
}
