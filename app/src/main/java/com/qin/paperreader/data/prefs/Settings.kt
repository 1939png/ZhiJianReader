package com.qin.paperreader.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 阅读器全部设置项。 */
data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    val lineSpacingMult: Float = 1.25f,
    val paraSpacingDp: Float = 8f,
    val marginHDp: Float = 22f,
    val marginVDp: Float = 26f,
    val indentChars: Int = 2,
    val fontKey: String = "default",
    val themeId: String = "night",
    val customBg: Long = 0xFFFAF6EE,
    val customText: Long = 0xFF3B3128,
    val paperTexture: Boolean = false,
    val animMode: String = "cover",
    val keepScreenOn: Boolean = false,
    val volumeTurn: Boolean = true,
    val sortMode: String = "recent",
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object K {
        val SIZE = floatPreferencesKey("font_size_sp")
        val LINE = floatPreferencesKey("line_spacing_mult")
        val PARA = floatPreferencesKey("para_spacing_dp")
        val MARGIN_H = floatPreferencesKey("margin_h_dp")
        val MARGIN_V = floatPreferencesKey("margin_v_dp")
        val INDENT = intPreferencesKey("indent_chars")
        val FONT = stringPreferencesKey("font_key")
        val THEME = stringPreferencesKey("theme_id")
        val CUSTOM_BG = longPreferencesKey("custom_bg")
        val CUSTOM_TEXT = longPreferencesKey("custom_text")
        val TEXTURE = booleanPreferencesKey("paper_texture")
        val ANIM = stringPreferencesKey("anim_mode")
        val KEEP_ON = booleanPreferencesKey("keep_screen_on")
        val VOLUME = booleanPreferencesKey("volume_turn")
        val SORT = stringPreferencesKey("sort_mode")
    }

    val flow: Flow<ReaderSettings> = context.dataStore.data.map { p ->
        ReaderSettings(
            fontSizeSp = p[K.SIZE] ?: 18f,
            lineSpacingMult = p[K.LINE] ?: 1.25f,
            paraSpacingDp = p[K.PARA] ?: 8f,
            marginHDp = p[K.MARGIN_H] ?: 22f,
            marginVDp = p[K.MARGIN_V] ?: 26f,
            indentChars = p[K.INDENT] ?: 2,
            fontKey = p[K.FONT] ?: "default",
            themeId = p[K.THEME] ?: "night",
            customBg = p[K.CUSTOM_BG] ?: 0xFFFAF6EE,
            customText = p[K.CUSTOM_TEXT] ?: 0xFF3B3128,
            paperTexture = p[K.TEXTURE] ?: false,
            animMode = p[K.ANIM] ?: "cover",
            keepScreenOn = p[K.KEEP_ON] ?: false,
            volumeTurn = p[K.VOLUME] ?: true,
            sortMode = p[K.SORT] ?: "recent",
        )
    }

    suspend fun update(block: (ReaderSettings) -> ReaderSettings) {
        context.dataStore.edit { prefs ->
            val cur = ReaderSettings(
                fontSizeSp = prefs[K.SIZE] ?: 18f,
                lineSpacingMult = prefs[K.LINE] ?: 1.25f,
                paraSpacingDp = prefs[K.PARA] ?: 8f,
                marginHDp = prefs[K.MARGIN_H] ?: 22f,
                marginVDp = prefs[K.MARGIN_V] ?: 26f,
                indentChars = prefs[K.INDENT] ?: 2,
                fontKey = prefs[K.FONT] ?: "default",
                themeId = prefs[K.THEME] ?: "night",
                customBg = prefs[K.CUSTOM_BG] ?: 0xFFFAF6EE,
                customText = prefs[K.CUSTOM_TEXT] ?: 0xFF3B3128,
                paperTexture = prefs[K.TEXTURE] ?: false,
                animMode = prefs[K.ANIM] ?: "cover",
                keepScreenOn = prefs[K.KEEP_ON] ?: false,
                volumeTurn = prefs[K.VOLUME] ?: true,
                sortMode = prefs[K.SORT] ?: "recent",
            )
            val next = block(cur)
            prefs[K.SIZE] = next.fontSizeSp
            prefs[K.LINE] = next.lineSpacingMult
            prefs[K.PARA] = next.paraSpacingDp
            prefs[K.MARGIN_H] = next.marginHDp
            prefs[K.MARGIN_V] = next.marginVDp
            prefs[K.INDENT] = next.indentChars
            prefs[K.FONT] = next.fontKey
            prefs[K.THEME] = next.themeId
            prefs[K.CUSTOM_BG] = next.customBg
            prefs[K.CUSTOM_TEXT] = next.customText
            prefs[K.TEXTURE] = next.paperTexture
            prefs[K.ANIM] = next.animMode
            prefs[K.KEEP_ON] = next.keepScreenOn
            prefs[K.VOLUME] = next.volumeTurn
            prefs[K.SORT] = next.sortMode
        }
    }

    /** 备份恢复：写入一组键值。 */
    suspend fun restore(map: Map<String, Any?>) {
        context.dataStore.edit { prefs ->
            (map["fontSizeSp"] as? Number)?.let { prefs[K.SIZE] = it.toFloat() }
            (map["lineSpacingMult"] as? Number)?.let { prefs[K.LINE] = it.toFloat() }
            (map["paraSpacingDp"] as? Number)?.let { prefs[K.PARA] = it.toFloat() }
            (map["marginHDp"] as? Number)?.let { prefs[K.MARGIN_H] = it.toFloat() }
            (map["marginVDp"] as? Number)?.let { prefs[K.MARGIN_V] = it.toFloat() }
            (map["indentChars"] as? Number)?.let { prefs[K.INDENT] = it.toInt() }
            (map["fontKey"] as? String)?.let { prefs[K.FONT] = it }
            (map["themeId"] as? String)?.let { prefs[K.THEME] = it }
            (map["customBg"] as? Number)?.let { prefs[K.CUSTOM_BG] = it.toLong() }
            (map["customText"] as? Number)?.let { prefs[K.CUSTOM_TEXT] = it.toLong() }
            (map["paperTexture"] as? Boolean)?.let { prefs[K.TEXTURE] = it }
            (map["animMode"] as? String)?.let { prefs[K.ANIM] = it }
            (map["keepScreenOn"] as? Boolean)?.let { prefs[K.KEEP_ON] = it }
            (map["volumeTurn"] as? Boolean)?.let { prefs[K.VOLUME] = it }
            (map["sortMode"] as? String)?.let { prefs[K.SORT] = it }
        }
    }

    fun snapshot(s: ReaderSettings): Map<String, Any?> = mapOf(
        "fontSizeSp" to s.fontSizeSp,
        "lineSpacingMult" to s.lineSpacingMult,
        "paraSpacingDp" to s.paraSpacingDp,
        "marginHDp" to s.marginHDp,
        "marginVDp" to s.marginVDp,
        "indentChars" to s.indentChars,
        "fontKey" to s.fontKey,
        "themeId" to s.themeId,
        "customBg" to s.customBg,
        "customText" to s.customText,
        "paperTexture" to s.paperTexture,
        "animMode" to s.animMode,
        "keepScreenOn" to s.keepScreenOn,
        "volumeTurn" to s.volumeTurn,
        "sortMode" to s.sortMode,
    )
}
