package com.qin.paperreader.data.library

import android.content.Context
import android.net.Uri
import com.qin.paperreader.data.db.BookEntity
import com.qin.paperreader.data.db.BookmarkEntity
import com.qin.paperreader.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地备份/恢复：zip = backup.json（书架元数据 + 书签 + 设置）+ books/<fileName> 原文。
 * 恢复时按书籍 id 覆盖；设置整体覆盖。
 */
class BackupManager(
    private val context: Context,
    private val repo: LibraryRepository,
    private val settings: SettingsRepository,
) {

    suspend fun exportTo(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val books = repo.bookDao.getAll()
            val bookmarks = repo.bookmarkDao.getAll()
            val current = settings.flow.first()
            val json = JSONObject()
            json.put("version", 1)
            json.put("exportedAt", System.currentTimeMillis())
            json.put("settings", JSONObject(settings.snapshot(current)))

            val arr = JSONArray()
            for (b in books) {
                arr.put(
                    JSONObject()
                        .put("id", b.id)
                        .put("title", b.title)
                        .put("fileName", b.fileName)
                        .put("charset", b.charset)
                        .put("sizeBytes", b.sizeBytes)
                        .put("charCount", b.charCount)
                        .put("chapterCount", b.chapterCount)
                        .put("addedAt", b.addedAt)
                        .put("lastReadAt", b.lastReadAt)
                        .put("lastOffset", b.lastOffset)
                        .put("coverPath", b.coverPath ?: "")
                )
            }
            json.put("books", arr)
            val barr = JSONArray()
            for (m in bookmarks) {
                barr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("bookId", m.bookId)
                        .put("offset", m.offset)
                        .put("preview", m.preview)
                        .put("note", m.note)
                        .put("createdAt", m.createdAt)
                )
            }
            json.put("bookmarks", barr)

            val out = context.contentResolver.openOutputStream(uri) ?: error("无法写入所选位置")
            ZipOutputStream(out.buffered()).use { zos ->
                zos.putNextEntry(ZipEntry("backup.json"))
                zos.write(json.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                for (b in books) {
                    val f = repo.fileOf(b)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry("books/" + b.fileName))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    // 自定义封面
                    val cover = repo.coverFileOf(b)
                    if (cover != null && b.coverPath != null) {
                        zos.putNextEntry(ZipEntry("covers/" + b.coverPath))
                        cover.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            books.size
        }
    }

    suspend fun restoreFrom(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var json: JSONObject? = null
            val ins = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
            ZipInputStream(ins.buffered()).use { zis ->
                var e: ZipEntry? = zis.nextEntry
                while (e != null) {
                    when {
                        e.name == "backup.json" -> {
                            json = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                        }
                        e.name.startsWith("books/") && !e.isDirectory -> {
                            val name = e.name.removePrefix("books/")
                            // 防路径穿越
                            if (name.isNotEmpty() && !name.contains("..") && !name.contains('/') && !name.contains('\\')) {
                                File(repo.booksDir, name).outputStream().use { zis.copyTo(it) }
                            }
                        }
                        e.name.startsWith("covers/") && !e.isDirectory -> {
                            val name = e.name.removePrefix("covers/")
                            if (name.isNotEmpty() && !name.contains("..") && !name.contains('/') && !name.contains('\\')) {
                                File(repo.coversDir, name).outputStream().use { zis.copyTo(it) }
                            }
                        }
                    }
                    e = zis.nextEntry
                }
            }
            val root = json ?: error("不是有效的纸间阅读器备份（缺少 backup.json）")

            // 1) 设置
            val sobj = root.optJSONObject("settings")
            if (sobj != null) {
                val map = HashMap<String, Any?>()
                for (key in sobj.keys()) map[key] = sobj.opt(key)
                settings.restore(map)
            }

            // 2) 书籍（按 id 覆盖）
            val arr = root.optJSONArray("books") ?: JSONArray()
            var restored = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val fileName = o.optString("fileName")
                if (fileName.isEmpty() || fileName.contains("..")) continue
                if (!File(repo.booksDir, fileName).exists()) continue
                repo.bookDao.upsert(
                    BookEntity(
                        id = o.getString("id"),
                        title = o.optString("title", "未命名"),
                        fileName = fileName,
                        charset = o.optString("charset", "UTF-8"),
                        sizeBytes = o.optLong("sizeBytes"),
                        charCount = o.optInt("charCount"),
                        chapterCount = o.optInt("chapterCount"),
                        addedAt = o.optLong("addedAt"),
                        lastReadAt = o.optLong("lastReadAt"),
                        lastOffset = o.optInt("lastOffset"),
                        coverPath = o.optString("coverPath").takeIf { it.isNotEmpty() },
                    )
                )
                restored++
            }

            // 3) 书签（书存在则先清后插）
            val brr = root.optJSONArray("bookmarks") ?: JSONArray()
            var bmCount = 0
            for (i in 0 until brr.length()) {
                val o = brr.getJSONObject(i)
                val bookId = o.optString("bookId")
                if (repo.bookDao.get(bookId) == null) continue
                repo.bookmarkDao.deleteForBook(bookId)
                repo.bookmarkDao.add(
                    BookmarkEntity(
                        bookId = bookId,
                        offset = o.optInt("offset"),
                        preview = o.optString("preview"),
                        note = o.optString("note"),
                        createdAt = o.optLong("createdAt"),
                    )
                )
                bmCount++
            }
            "恢复完成：$restored 本书、$bmCount 条书签"
        }
    }
}
