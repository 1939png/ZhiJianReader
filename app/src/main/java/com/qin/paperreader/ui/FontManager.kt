package com.qin.paperreader.ui

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 字体管理：内置（默认无衬线/衬线）+ 用户导入的 TTF/OTF。 */
object FontManager {

    data class FontOption(val key: String, val label: String)

    fun fontsDir(context: Context): File = File(context.filesDir, "fonts").apply { mkdirs() }

    fun options(context: Context): List<FontOption> {
        val list = mutableListOf(
            FontOption("default", "默认字体"),
            FontOption("serif", "衬线字体"),
        )
        fontsDir(context).listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true)) }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { list.add(FontOption("file:${it.name}", it.name.removeSuffix(".ttf").removeSuffix(".otf"))) }
        return list
    }

    fun typeface(context: Context, key: String): Typeface? = when {
        key == "default" -> null
        key == "serif" -> Typeface.SERIF
        key.startsWith("file:") -> runCatching {
            Typeface.createFromFile(File(fontsDir(context), key.removePrefix("file:")))
        }.getOrNull()
        else -> null
    }

    suspend fun import(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: "font.ttf"
            name = name.substringAfterLast('/')
            if (!name.endsWith(".ttf", true) && !name.endsWith(".otf", true)) name += ".ttf"
            name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            var target = File(fontsDir(context), name)
            var k = 1
            while (target.exists()) {
                val base = name.removeSuffix(".ttf").removeSuffix(".otf")
                val ext = if (name.endsWith(".otf", true)) ".otf" else ".ttf"
                target = File(fontsDir(context), "$base-$k$ext")
                k++
            }
            context.contentResolver.openInputStream(uri)?.use { ins ->
                target.outputStream().use { ins.copyTo(it) }
            } ?: error("无法读取所选文件")
            // 验证可加载
            runCatching { Typeface.createFromFile(target) }
                .onFailure {
                    target.delete()
                    error("该文件不是有效的 TTF/OTF 字体")
                }
            target.name
        }
    }
}
