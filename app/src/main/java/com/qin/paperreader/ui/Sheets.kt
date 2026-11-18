package com.qin.paperreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qin.paperreader.data.db.BookmarkEntity
import com.qin.paperreader.reader.ReaderViewModel

// ---------- 目录 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(vm: ReaderViewModel, onDismiss: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(ui.chapterIndex, ui.toc.size) {
        if (ui.toc.isNotEmpty() && ui.chapterIndex < ui.toc.size) {
            runCatching { listState.scrollToItem(ui.chapterIndex) }
        }
    }
    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("目录 · ${ui.toc.size} 章", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
        }
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
            itemsIndexedSafe(ui.toc) { index, entry ->
                val current = index == ui.chapterIndex
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.jumpToOffset(entry.offset); onDismiss() }
                        .background(if (current) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        entry.title,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedSafe(
    items: List<T>,
    crossinline item: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int, T) -> Unit,
) {
    items(items.size) { i -> item(i, items[i]) }
}

// ---------- 书签 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkSheet(vm: ReaderViewModel, onDismiss: () -> Unit) {
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BookmarkEntity?>(null) }
    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("书签 · ${bookmarks.size} 条", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
        }
        if (bookmarks.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Text("还没有书签\n阅读时点击顶部 ☆ 即可在此处留下标记", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                items(bookmarks, key = { it.id }) { b ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.jumpToOffset(b.offset); onDismiss() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                b.preview,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 20.sp,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                (if (b.note.isNotBlank()) "备注：${b.note} · " else "") + formatTimeAgo(b.createdAt),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { editing = b }) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑备注")
                        }
                        IconButton(onClick = { vm.deleteBookmark(b) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除书签")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    editing?.let { b ->
        TextInputDialog(
            title = "书签备注",
            initial = b.note,
            placeholder = "给这个位置写点什么（可选）",
            confirmLabel = "保存",
            onConfirm = { vm.updateBookmark(b, it) },
            onDismiss = { editing = null },
        )
    }
}

// ---------- 搜索 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(vm: ReaderViewModel, onDismiss: () -> Unit) {
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.search(it)
                },
                placeholder = { Text("在本书中搜索全文") },
                singleLine = true,
                trailingIcon = {
                    if (ui.searching) CircularProgressIndicator(Modifier.height(20.dp))
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (query.isBlank()) "输入关键词开始搜索（离线，不联网）"
                else "共 ${results.size} 处" + if (results.size >= 200) "（已截断）" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            items(results, key = { it.offset }) { hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.jumpToOffset(hit.offset); onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    val annotated = androidx.compose.ui.text.buildAnnotatedString {
                        val s = hit.snippet
                        val ms = hit.matchStart.coerceIn(0, s.length)
                        val me = (hit.matchStart + hit.matchLength).coerceIn(0, s.length)
                        append(s.substring(0, ms))
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                        append(s.substring(ms, me))
                        pop()
                        append(s.substring(me))
                    }
                    Text(annotated, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
