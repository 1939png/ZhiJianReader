package com.qin.paperreader.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qin.paperreader.App
import com.qin.paperreader.data.db.BookEntity
import com.qin.paperreader.data.library.LibraryRepository
import com.qin.paperreader.data.prefs.ReaderSettings
import com.qin.paperreader.text.EncodingDetector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShelfViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as App
    private val repo = graph.repo

    val settings: StateFlow<ReaderSettings> =
        graph.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    val books: StateFlow<List<BookEntity>> =
        combine(repo.bookDao.observeAll(), graph.settings.flow) { list, s ->
            when (s.sortMode) {
                "added" -> list.sortedByDescending { it.addedAt }
                "title" -> list.sortedWith(compareBy(titleComparator()) { it.title })
                else -> list.sortedWith(
                    compareByDescending<BookEntity> { it.lastReadAt > 0 }
                        .thenByDescending { if (it.lastReadAt > 0) it.lastReadAt else it.addedAt }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: MutableSharedFlow<String> = _events

    val importPreview = MutableStateFlow<LibraryRepository.ImportPreview?>(null)
    val previewIsReencode = MutableStateFlow(false)

    /** 字体列表变更信号：导入后递增，设置面板据此刷新。 */
    val fontsTick = MutableStateFlow(0)

    /** 待设置封面的书。 */
    val coverTarget = MutableStateFlow<BookEntity?>(null)

    private var pendingImportUri: Uri? = null
    private var pendingReencodeBook: BookEntity? = null

    fun onFilePicked(uri: Uri) {
        pendingImportUri = uri
        pendingReencodeBook = null
        viewModelScope.launch {
            runCatching { repo.previewImport(uri) }
                .onSuccess { importPreview.value = it }
                .onFailure { _events.tryEmit("无法读取所选文件：${it.message ?: "未知错误"}") }
        }
    }

    fun confirmImport(charsetId: EncodingDetector.Id) {
        val uri = pendingImportUri ?: return
        viewModelScope.launch {
            repo.import(uri, charsetId)
                .onSuccess { _events.tryEmit("导入成功：${it.title}") }
                .onFailure { _events.tryEmit("导入失败：${it.message ?: "未知错误"}") }
            importPreview.value = null
            pendingImportUri = null
        }
    }

    fun onReencodeRequested(book: BookEntity) {
        viewModelScope.launch {
            runCatching {
                val head = repo.headOf(book)
                val det = EncodingDetector.detect(head)
                val previews = EncodingDetector.Id.entries.associateWith { id ->
                    EncodingDetector.preview(head, id, 160).ifBlank { "（预览为空）" }
                }
                LibraryRepository.ImportPreview(book.title, book.sizeBytes, det, previews)
            }.onSuccess {
                pendingReencodeBook = book
                importPreview.value = it
                previewIsReencode.value = true
            }.onFailure {
                _events.tryEmit("无法读取本书文件：${it.message ?: "未知错误"}")
            }
        }
    }

    fun confirmReencode(charsetId: EncodingDetector.Id) {
        val book = pendingReencodeBook ?: return
        viewModelScope.launch {
            repo.reencode(book, charsetId)
                .onSuccess { _events.tryEmit("编码已切换，请重新打开本书") }
                .onFailure { _events.tryEmit("切换失败：${it.message ?: "未知错误"}") }
            importPreview.value = null
            pendingReencodeBook = null
        }
    }

    fun dismissPreview() {
        importPreview.value = null
        previewIsReencode.value = false
        pendingImportUri = null
        pendingReencodeBook = null
    }

    fun rename(book: BookEntity, title: String) {
        viewModelScope.launch { repo.rename(book, title) }
    }

    fun delete(book: BookEntity) {
        viewModelScope.launch {
            repo.delete(book)
            _events.tryEmit("已删除《${book.title}》")
        }
    }

    fun backup(uri: Uri) {
        viewModelScope.launch {
            graph.backup.exportTo(uri)
                .onSuccess { _events.tryEmit("备份完成：$it 本书") }
                .onFailure { _events.tryEmit("备份失败：${it.message ?: "未知错误"}") }
        }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch {
            graph.backup.restoreFrom(uri)
                .onSuccess { _events.tryEmit(it) }
                .onFailure { _events.tryEmit("恢复失败：${it.message ?: "未知错误"}") }
        }
    }

    fun setSortMode(mode: String) {
        viewModelScope.launch { graph.settings.update { it.copy(sortMode = mode) } }
    }

    fun updateSettings(block: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { graph.settings.update(block) }
    }

    fun importFont(uri: Uri) {
        viewModelScope.launch {
            FontManager.import(getApplication(), uri)
                .onSuccess {
                    _events.tryEmit("字体已导入：$it")
                    fontsTick.value = fontsTick.value + 1
                }
                .onFailure { _events.tryEmit("导入失败：${it.message ?: "未知错误"}") }
        }
    }

    fun onCoverPicked(uri: Uri) {
        val book = coverTarget.value ?: return
        coverTarget.value = null
        viewModelScope.launch {
            repo.setCover(book, uri)
                .onSuccess { _events.tryEmit("封面已更新：《${it.title}》") }
                .onFailure { _events.tryEmit("设置封面失败：${it.message ?: "未知错误"}") }
        }
    }

    fun clearCover(book: BookEntity) {
        viewModelScope.launch {
            repo.clearCover(book)
            _events.tryEmit("已恢复默认封面")
        }
    }

    fun coverFileOf(book: BookEntity): java.io.File? = repo.coverFileOf(book)

    companion object {
        fun factory(app: Application) = viewModelFactory {
            initializer { ShelfViewModel(app) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(onOpenBook: (String) -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as App
    val vm: ShelfViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = ShelfViewModel.factory(app))
    val books by vm.books.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val preview by vm.importPreview.collectAsStateWithLifecycle()
    val previewIsReencode by vm.previewIsReencode.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        vm.events.collect { snackbar.showSnackbar(it) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.onFilePicked(uri)
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) vm.backup(uri) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restore(uri)
    }
    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onCoverPicked(uri)
    }

    var menuOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<BookEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }
    var restoreConfirm by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("纸间阅读器") },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("排序：最近阅读" + if (settings.sortMode == "recent") " ✓" else "") },
                                onClick = { vm.setSortMode("recent"); menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("排序：加入时间") },
                                onClick = { vm.setSortMode("added"); menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("排序：书名") },
                                onClick = { vm.setSortMode("title"); menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("备份书架") },
                                onClick = {
                                    menuOpen = false
                                    val name = "纸间备份-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
                                    backupLauncher.launch("$name.zip")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("恢复备份") },
                                onClick = { menuOpen = false; restoreConfirm = true },
                            )
                            DropdownMenuItem(
                                text = { Text("阅读设置（字体/动画/主题）") },
                                onClick = { menuOpen = false; showSettings = true },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("导入 TXT") },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (books.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("书架空空如也", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点击右下角「导入 TXT」开始阅读\n支持 UTF-8 / UTF-16 / GB18030 编码",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(books, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            coverFile = vm.coverFileOf(book),
                            onOpen = { onOpenBook(book.id) },
                            onRename = { renameTarget = book },
                            onDelete = { deleteTarget = book },
                            onReencode = { vm.onReencodeRequested(book) },
                            onSetCover = {
                                vm.coverTarget.value = book
                                coverLauncher.launch(arrayOf("image/*"))
                            },
                            onClearCover = { vm.clearCover(book) },
                        )
                    }
                }
            }
        }
    }

    preview?.let { p ->
        EncodingPickerDialog(
            title = if (previewIsReencode) "重新指定编码" else "导入书籍",
            displayName = p.displayName,
            sizeBytes = p.sizeBytes,
            detected = p.detected.id,
            previews = p.previews,
            confirmLabel = if (previewIsReencode) "确定切换" else "导入",
            onConfirm = { id -> if (previewIsReencode) vm.confirmReencode(id) else vm.confirmImport(id) },
            onDismiss = { vm.dismissPreview() },
        )
    }

    renameTarget?.let { b ->
        TextInputDialog(
            title = "重命名",
            initial = b.title,
            onConfirm = { vm.rename(b, it) },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { b ->
        ConfirmDialog(
            title = "删除书籍",
            message = "确定删除《${b.title}》吗？\n正文文件与该书书签都会被删除，无法恢复。",
            onConfirm = { vm.delete(b) },
            onDismiss = { deleteTarget = null },
        )
    }

    if (restoreConfirm) {
        ConfirmDialog(
            title = "恢复备份",
            message = "将导入备份中的书籍、进度、书签与设置；相同 id 的书会被覆盖。",
            confirmLabel = "选择文件",
            onConfirm = {
                restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
            onDismiss = { restoreConfirm = false },
        )
    }

    if (showSettings) {
        val s by vm.settings.collectAsStateWithLifecycle()
        val tick by vm.fontsTick.collectAsStateWithLifecycle()
        SettingsSheetCore(
            settings = s,
            fontsTick = tick,
            onUpdate = vm::updateSettings,
            onImportFont = vm::importFont,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun BookRow(
    book: BookEntity,
    coverFile: java.io.File?,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onReencode: () -> Unit,
    onSetCover: () -> Unit,
    onClearCover: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        BookCover(title = book.title, seed = book.id, modifier = Modifier.size(width = 52.dp, height = 72.dp), coverFile = coverFile)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val progress = if (book.charCount > 0) book.lastOffset * 100f / book.charCount else 0f
            Text(
                if (book.lastReadAt > 0) "读到 %.1f%% · %s".format(progress, formatTimeAgo(book.lastReadAt)) else "未读 · %s 加入".format(formatTimeAgo(book.addedAt)),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${formatSize(book.sizeBytes)} · ${book.chapterCount} 章 · ${book.charset}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("阅读") },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    onClick = { menuOpen = false; onOpen() },
                )
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("重新指定编码") },
                    onClick = { menuOpen = false; onReencode() },
                )
                DropdownMenuItem(
                    text = { Text("更换封面") },
                    onClick = { menuOpen = false; onSetCover() },
                )
                if (book.coverPath != null) {
                    DropdownMenuItem(
                        text = { Text("移除自定义封面") },
                        onClick = { menuOpen = false; onClearCover() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}
