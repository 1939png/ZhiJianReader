package com.qin.paperreader.ui

import com.qin.paperreader.data.prefs.ReaderSettings
import com.qin.paperreader.engine.PageTheme

data class ThemePreset(val id: String, val label: String, val bg: Long, val text: Long)

/** 五个内置主题 + 自定义色。 */
object Themes {

    val presets = listOf(
        ThemePreset("warm", "暖白", 0xFFFAF6EE, 0xFF3B3128),
        ThemePreset("bean", "豆绿", 0xFFCCE8CF, 0xFF2F3B2F),
        ThemePreset("kraft", "牛皮纸", 0xFFD9BC8F, 0xFF4A3520),
        ThemePreset("gray", "浅灰", 0xFFE9E9E7, 0xFF333333),
        ThemePreset("night", "夜间", 0xFF121212, 0xFFB8AFA2),
    )

    fun resolveBgText(s: ReaderSettings): Pair<Long, Long> =
        if (s.themeId == "custom") {
            s.customBg to s.customText
        } else {
            presets.firstOrNull { it.id == s.themeId }?.let { it.bg to it.text }
                ?: (presets[0].bg to presets[0].text)
        }

    /** 标题/点缀色：文字色向背景色靠拢 30%，安静不抢眼。 */
    fun accentFor(bg: Int, text: Int): Int = mix(text, bg, 0.30f)

    fun mix(a: Int, b: Int, t: Float): Int {
        val ar = a shr 16 and 0xFF; val ag = a shr 8 and 0xFF; val ab = a and 0xFF
        val br = b shr 16 and 0xFF; val bg2 = b shr 8 and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg2 - ag) * t).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        return (a and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bl
    }

    fun buildPageTheme(s: ReaderSettings): PageTheme {
        val (bgL, textL) = resolveBgText(s)
        val bg = bgL.toInt()
        val text = textL.toInt()
        val footer = (0x8E shl 24) or (text and 0xFFFFFF)
        return PageTheme(
            bg = bg,
            text = text,
            accent = accentFor(bg, text),
            footer = footer,
            textureEnabled = s.paperTexture,
        )
    }
}
