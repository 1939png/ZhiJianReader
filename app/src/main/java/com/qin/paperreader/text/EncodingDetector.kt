package com.qin.paperreader.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 编码识别：BOM 优先，其次零字节密度判 UTF-16，
 * 再次严格 UTF-8 校验，失败则回退 GB18030（GB18030 几乎能解码任何字节流，无法正向确认）。
 */
object EncodingDetector {

    enum class Id(val label: String, val charsetName: String) {
        UTF8("UTF-8", "UTF-8"),
        UTF16LE("UTF-16LE", "UTF-16LE"),
        UTF16BE("UTF-16BE", "UTF-16BE"),
        GB18030("GB18030", "GB18030"),
    }

    data class Result(val id: Id, val hasBom: Boolean, val confident: Boolean)

    fun detect(bytes: ByteArray): Result {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Result(Id.UTF8, true, true)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Result(Id.UTF16BE, true, true)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Result(Id.UTF16LE, true, true)
        }

        val n = minOf(bytes.size, 8192)
        var zeroEven = 0
        var zeroOdd = 0
        for (i in 0 until n) {
            if (bytes[i].toInt() == 0) {
                if (i % 2 == 0) zeroEven++ else zeroOdd++
            }
        }
        val zeroTotal = zeroEven + zeroOdd
        if (n > 0 && zeroTotal * 8 > n) {
            // ASCII/中文文本里 0x00 成对出现；UTF-16LE 中 ASCII 字符的 0 在奇数位
            return if (zeroOdd >= zeroEven) Result(Id.UTF16LE, false, true) else Result(Id.UTF16BE, false, true)
        }

        if (isStrictUtf8(trimUtf8Tail(bytes))) return Result(Id.UTF8, false, true)
        return Result(Id.GB18030, false, false)
    }

    /**
     * 截掉样本尾部可能不完整的 UTF-8 序列。
     * 识别只看文件头部样本，若截断点落在多字节字符中间，严格校验会误报失败、
     * 进而误判成 GB18030 导致乱码；这里从尾部回退连续字节并截掉不完整的首字节，
     * 保证校验只面对完整字符。
     */
    fun trimUtf8Tail(b: ByteArray): ByteArray {
        val n = b.size
        if (n == 0) return b
        var i = n - 1
        var back = 0
        while (i >= 0 && back < 3 && (b[i].toInt() and 0xC0) == 0x80) {
            i--
            back++
        }
        if (i < 0) return b
        val v = b[i].toInt() and 0xFF
        val leadLen = when {
            v and 0x80 == 0 -> 1
            v and 0xE0 == 0xC0 -> 2
            v and 0xF0 == 0xE0 -> 3
            v and 0xF8 == 0xF0 -> 4
            else -> -1
        }
        return if (leadLen > 1 && (n - i) < leadLen) b.copyOf(i) else b
    }

    fun isStrictUtf8(bytes: ByteArray): Boolean {
        return try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            dec.decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }

    fun charsetFor(id: Id): Charset = when (id) {
        Id.UTF8 -> Charsets.UTF_8
        Id.UTF16LE -> Charsets.UTF_16LE
        Id.UTF16BE -> Charsets.UTF_16BE
        Id.GB18030 -> try {
            Charset.forName("GB18030")
        } catch (e: Exception) {
            Charsets.ISO_8859_1
        }
    }

    /** 宽松解码（非法字节替换为 U+FFFD），保证永远得到可显示文本。 */
    fun decode(bytes: ByteArray, id: Id): String {
        val dec = charsetFor(id).newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return dec.decode(ByteBuffer.wrap(bytes)).toString()
    }

    /** 取前若干字符用于手动选码时的预览。 */
    fun preview(bytes: ByteArray, id: Id, maxChars: Int): String {
        val head = if (id == Id.UTF16LE || id == Id.UTF16BE) {
            val even = bytes.size - (bytes.size % 2)
            bytes.copyOf(even)
        } else {
            bytes
        }
        return decode(head, id).replace("\u0000", "").take(maxChars)
    }
}
