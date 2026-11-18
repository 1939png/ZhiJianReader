package com.qin.paperreader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/** 极简路由：书架 ↔ 阅读页。 */
@Composable
fun AppRoot() {
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    Surface {
        if (bookId == null) {
            ShelfScreen(onOpenBook = { bookId = it })
        } else {
            ReaderScreen(
                bookId = bookId!!,
                onBack = { bookId = null },
            )
        }
    }
}
