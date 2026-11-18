package com.qin.paperreader.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.qin.paperreader.data.db.AppDatabase
import com.qin.paperreader.data.db.BookEntity
import com.qin.paperreader.data.db.BookmarkEntity
import com.qin.paperreader.text.BookLoader
import com.qin.paperreader.text.EncodingDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 书库操作：导入（复制进私有目录）、重命名、删除、重新指定编码、字体目录。 */
class LibraryRepository(private val context: Context, private val db: AppDatabase) {

    val bookDao = db.bookDao()
    val bookmarkDao = db.bookmarkDao()
    val booksDir: File = File(context.filesDir, "books").apply { mkdirs() }
    val fontsDir: File = File(context.filesDir, "fonts").apply { mkdirs() }
    val coversDir: File = File(context.filesDir, "covers").apply { mkdirs() }

    fun fileOf(book: BookEntity): File = File(booksDir, book.fileName)

    fun coverFileOf(book: BookEntity): File? =
        book.coverPath?.let { name ->
            if (name.contains("..") || name.contains('/') || name.contains('\\')) null
            else File(coversDir, name).takeIf { it.exists() }
        }

    /** 设置自定义封面：选图缩到最长边 ≤ 720px 后存入私有 covers 目录。 */
    suspend fun setCover(book: BookEntity, uri: Uri): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取所选图片")
            val bmp = decodeScaled(bytes, 720) ?: error("不是有效的图片文件")
            val name = "${book.id}.jpg"
            val f = File(coversDir, name)
            f.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
            val updated = book.copy(coverPath = name)
            bookDao.upsert(updated)
            updated
        }
    }

    /** 移除自定义封面，恢复生成的默认封面。 */
    suspend fun clearCover(book: BookEntity): BookEntity {
        book.coverPath?.let { File(coversDir, it).delete() }
        val updated = book.copy(coverPath = null)
        bookDao.upsert(updated)
        return updated
    }

    private fun decodeScaled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= maxSide || h / 2 >= maxSide) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        return if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
    }

    /** 读书文件头部字节，用于重新指定编码时的预览。 */
    suspend fun headOf(book: BookEntity, max: Int = 64 * 1024): ByteArray = withContext(Dispatchers.IO) {
        val f = fileOf(book)
        check(f.exists()) { "书籍文件丢失" }
        f.inputStream().use { ins ->
            val buf = ByteArray(max)
            var off = 0
            while (off < max) {
                val r = ins.read(buf, off, max - off)
                if (r < 0) break
                off += r
            }
            buf.copyOf(off)
        }
    }

    data class ImportPreview(
        val displayName: String,
        val sizeBytes: Long,
        val detected: EncodingDetector.Result,
        val previews: Map<EncodingDetector.Id, String>,
    )

    suspend fun previewImport(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val head = readHead(uri, 64 * 1024)
        val det = EncodingDetector.detect(head)
        val previews = EncodingDetector.Id.entries.associateWith { id ->
            EncodingDetector.preview(head, id, 160).ifBlank { "（预览为空）" }
        }
        ImportPreview(queryDisplayName(uri) ?: "未命名", querySize(uri), det, previews)
    }

    suspend fun import(uri: Uri, charset: EncodingDetector.Id): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取所选文件")
            check(bytes.isNotEmpty()) { "文件为空" }
            check(bytes.size <= 300L * 1024 * 1024) { "文件过大（上限 300MB）" }
            val text = EncodingDetector.decode(bytes, charset)
            check(text.isNotBlank()) { "没有可读的文本内容，请尝试其他编码" }
            val parsed = BookLoader.parseText(text)

            val id = UUID.randomUUID().toString()
            val fileName = "$id.txt"
            File(booksDir, fileName).writeBytes(bytes)

            val name = queryDisplayName(uri)
            val title = guessTitle(name, text)
            val entity = BookEntity(
                id = id,
                title = title,
                fileName = fileName,
                charset = charset.name,
                sizeBytes = bytes.size.toLong(),
                charCount = parsed.charCount,
                chapterCount = parsed.chapters.size,
                addedAt = System.currentTimeMillis(),
                lastReadAt = 0L,
                lastOffset = 0,
            )
            bookDao.upsert(entity)
            entity
        }
    }

    /** 重新指定编码：重新解码并解析，正文偏移会变化，进度与书签清零。 */
    suspend fun reencode(book: BookEntity, charset: EncodingDetector.Id): Result<BookEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val f = fileOf(book)
            check(f.exists()) { "书籍文件丢失" }
            val bytes = f.readBytes()
            val text = EncodingDetector.decode(bytes, charset)
            val parsed = BookLoader.parseText(text)
            val updated = book.copy(
                charset = charset.name,
                charCount = parsed.charCount,
                chapterCount = parsed.chapters.size,
                lastOffset = 0,
            )
            bookDao.upsert(updated)
            bookmarkDao.deleteForBook(book.id)
            updated
        }
    }

    suspend fun rename(book: BookEntity, title: String) {
        bookDao.rename(book.id, title.trim().ifEmpty { book.title })
    }

    suspend fun delete(book: BookEntity) {
        withContext(Dispatchers.IO) {
            fileOf(book).delete()
        }
        bookmarkDao.deleteForBook(book.id)
        bookDao.delete(book.id)
    }

    suspend fun deleteAllForRestore() {
        // 恢复备份前不清空书库（按 id 覆盖），保留未在备份中的书
    }

    private fun guessTitle(displayName: String?, text: String): String {
        val fromName = displayName
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 40 }
        if (fromName != null) return fromName
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: "未命名"
        return firstLine.trim().take(20)
    }

    private fun readHead(uri: Uri, max: Int): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(max)
            var off = 0
            while (off < max) {
                val r = ins.read(buf, off, max - off)
                if (r < 0) break
                off += r
            }
            buf.copyOf(off)
        } ?: error("无法读取所选文件")
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
        }
        return -1L
    }
}
