package com.qin.paperreader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.qin.paperreader.ui.AppRoot

/** 音量键翻页的桥：阅读界面激活时注册回调，由 Activity 派发按键。 */
object ReaderHotKeys {
    @Volatile
    var active: Boolean = false

    /** 参数 true = 音量下键；返回 true 表示已消费。 */
    var onVolume: ((Boolean) -> Boolean)? = null
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 全应用强制深色模式（阅读页主题仍由用户在阅读设置中选择）
            val scheme = darkColorScheme(
                primary = Color(0xFFB08D6A),
                onPrimary = Color(0xFF2A2118),
                secondary = Color(0xFFB08D6A),
                onSecondary = Color(0xFF2A2118),
                background = Color(0xFF141210),
                onBackground = Color(0xFFE8E0D4),
                surface = Color(0xFF141210),
                onSurface = Color(0xFFE8E0D4),
                surfaceVariant = Color(0xFF262019),
                onSurfaceVariant = Color(0xFFB9AE9E),
                outline = Color(0xFF6E6459),
                error = Color(0xFFE57373),
            )
            MaterialTheme(colorScheme = scheme) {
                AppRoot()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (ReaderHotKeys.active) {
            val forward = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> true
                KeyEvent.KEYCODE_VOLUME_UP -> false
                else -> null
            }
            if (forward != null) {
                val handled = ReaderHotKeys.onVolume?.invoke(forward) ?: false
                if (handled) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (ReaderHotKeys.active &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            ReaderHotKeys.onVolume != null
        ) {
            // onKeyDown 已消费，KeyUp 直接吞掉，避免触发音量条
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
