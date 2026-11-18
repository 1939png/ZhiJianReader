package com.qin.paperreader.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qin.paperreader.App
import com.qin.paperreader.ReaderHotKeys
import com.qin.paperreader.reader.AnimMode
import com.qin.paperreader.reader.ReaderView
import com.qin.paperreader.reader.ReaderViewModel
import kotlinx.coroutines.launch

private enum class ReaderSheet { TOC, BOOKMARK, SEARCH, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val vm: ReaderViewModel = viewModel(key = "reader_$bookId", factory = ReaderViewModel.factory(app, bookId))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var chrome by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val readerViewRef = remember { mutableStateOf<ReaderView?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { snackbar.showSnackbar(it) }
    }

    // 沉浸式：阅读时隐藏系统栏
    val activity = LocalContext.current as? Activity
    val composeView = LocalView.current
    DisposableEffect(Unit) {
        activity?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            val controller = WindowCompat.getInsetsController(w, composeView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            activity?.window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, true)
                WindowCompat.getInsetsController(w, composeView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 屏幕常亮
    DisposableEffect(settings.keepScreenOn) {
        activity?.window?.let { w ->
            if (settings.keepScreenOn) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 音量键翻页
    DisposableEffect(settings.volumeTurn) {
        ReaderHotKeys.active = true
        ReaderHotKeys.onVolume = { forward ->
            readerViewRef.value?.turnByKey(forward) ?: false
        }
        onDispose {
            ReaderHotKeys.active = false
            ReaderHotKeys.onVolume = null
        }
    }

    // 进入后台（ON_STOP）时兑底强存阅读进度：中途划掉后台也不丢进度
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.flushProgressNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler {
        when {
            sheet != null -> sheet = null
            chrome -> chrome = false
            else -> onBack()
        }
    }

    val (bgL, _) = Themes.resolveBgText(settings)
    val pageBg = Color(bgL)

    Box(Modifier.fillMaxSize().background(pageBg)) {
        AndroidView(
            factory = { c ->
                ReaderView(c).also { readerViewRef.value = it }
            },
            update = { v ->
                v.host = object : ReaderView.Host {
                    override fun client() = vm.client
                    override fun animMode(): AnimMode = AnimMode.fromId(settings.animMode)
                    override fun onTapCenter() { chrome = !chrome }
                    override fun onEdge(message: String) {
                        scope.launch { snackbar.showSnackbar(message) }
                    }
                }
                v.invalidate()
            },
            onRelease = { v -> if (readerViewRef.value == v) readerViewRef.value = null },
            modifier = Modifier.fillMaxSize(),
        )

        // 顶部工具栏
        AnimatedVisibility(
            visible = chrome,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = pageBg.copy(alpha = 0.97f),
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回书架")
                    }
                    Text(
                        ui.title,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.addBookmark() }) {
                        Icon(Icons.Default.Star, contentDescription = "添加书签")
                    }
                    TextButton(onClick = { sheet = ReaderSheet.TOC }) { Text("目录") }
                    IconButton(onClick = { sheet = ReaderSheet.SEARCH }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = { sheet = ReaderSheet.SETTINGS }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            }
        }

        // 底部工具栏：章节 + 进度
        AnimatedVisibility(
            visible = chrome,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color = pageBg.copy(alpha = 0.97f),
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.stepChapter(-1) }) { Text("上一章") }
                        Text(
                            ui.chapterTitle.ifBlank { " " },
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        TextButton(onClick = { vm.stepChapter(1) }) { Text("下一章") }
                    }
                    var sliderVal by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = sliderVal ?: ui.percent,
                        onValueChange = { sliderVal = it },
                        onValueChangeFinished = {
                            sliderVal?.let { vm.jumpToFraction(it) }
                            sliderVal = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        Text(
                            "${ui.chapterPageIndex}/${ui.chapterPageCount.takeIf { it > 0 } ?: "…"} 页",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "%.1f%%".format((sliderVal ?: ui.percent) * 100f),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 排版进度条
        ui.paginationProgress?.let { p ->
            LinearProgressIndicator(
                progress = { p },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 4.dp)
                    .fillMaxWidth(0.5f),
            )
        }

        // 加载 / 错误
        if (ui.loading) {
            Box(Modifier.fillMaxSize().clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在打开《${ui.title}》…", fontSize = 14.sp)
                }
            }
        }
        if (!ui.loading && ui.error != null && !ui.textReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.error ?: "", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onBack) { Text("返回书架") }
                }
            }
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    // 各面板
    when (sheet) {
        ReaderSheet.TOC -> TocSheet(vm, onDismiss = { sheet = null })
        ReaderSheet.BOOKMARK -> BookmarkSheet(vm, onDismiss = { sheet = null })
        ReaderSheet.SEARCH -> SearchSheet(vm, onDismiss = { sheet = null })
        ReaderSheet.SETTINGS -> SettingsSheet(vm, onDismiss = { sheet = null })
        null -> {}
    }
}
