package com.qin.paperreader

import com.qin.paperreader.text.EncodingDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class EncodingDetectorTest {

    @Test
    fun `BOM 识别`() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好".toByteArray(Charsets.UTF_8)
        assertEquals(EncodingDetector.Id.UTF8, EncodingDetector.detect(utf8Bom).id)

        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "你好".toByteArray(Charsets.UTF_16LE)
        assertEquals(EncodingDetector.Id.UTF16LE, EncodingDetector.detect(le).id)

        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "你好".toByteArray(Charsets.UTF_16BE)
        assertEquals(EncodingDetector.Id.UTF16BE, EncodingDetector.detect(be).id)
    }

    @Test
    fun `GB18030 中文识别为非 UTF-8`() {
        val gb = "白日依山尽，黄河入海流。".toByteArray(charset("GB18030"))
        val r = EncodingDetector.detect(gb)
        assertEquals(EncodingDetector.Id.GB18030, r.id)
        assertFalse(EncodingDetector.isStrictUtf8(gb))
        // 解码回中文
        assertEquals("白日依山尽，黄河入海流。", EncodingDetector.decode(gb, EncodingDetector.Id.GB18030))
    }

    @Test
    fun `UTF8 严格校验`() {
        val utf8 = "春眠不觉晓，处处闻啼鸟。".toByteArray(Charsets.UTF_8)
        assertTrue(EncodingDetector.isStrictUtf8(utf8))
        assertEquals(EncodingDetector.Id.UTF8, EncodingDetector.detect(utf8).id)
    }

    @Test
    fun `UTF16 无 BOM 通过零字节密度识别`() {
        val le = "abc一段中文".toByteArray(Charsets.UTF_16LE)
        assertEquals(EncodingDetector.Id.UTF16LE, EncodingDetector.detect(le).id)
        val be = "abc一段中文".toByteArray(Charsets.UTF_16BE)
        assertEquals(EncodingDetector.Id.UTF16BE, EncodingDetector.detect(be).id)
    }

    @Test
    fun `纯 ASCII 归为 UTF-8`() {
        val ascii = "Hello, world! 123".toByteArray(Charsets.US_ASCII)
        assertEquals(EncodingDetector.Id.UTF8, EncodingDetector.detect(ascii).id)
    }

    @Test
    fun `UTF8 头部样本截断在多字节字符中间仍判为 UTF8`() {
        // 复现线上问题：识别只看前 64KB，纯中文 UTF-8 每字 3 字节，
        // 65536 % 3 = 1，截断点几乎必然落在字中间，旧逻辑会误判 GB18030 导致全文乱码
        val sb = StringBuilder()
        repeat(30000) { sb.append("纸间阅读器测试文本。") } // 9 万字节 > 64KB
        val head = sb.toString().toByteArray(Charsets.UTF_8).copyOf(65536)
        assertEquals(EncodingDetector.Id.UTF8, EncodingDetector.detect(head).id)
        // trim 工具本身：尾部不完整序列被截掉，截后样本严格合法
        val trimmed = EncodingDetector.trimUtf8Tail(head)
        assertTrue(EncodingDetector.isStrictUtf8(trimmed))
        assertTrue(trimmed.size in head.size - 4..head.size)
    }

    @Test
    fun `UTF8 样本截断在字符边界不受影响`() {
        val sb = StringBuilder()
        repeat(21845) { sb.append("好") } // 65535 字节，恰为 3 字节字符的整数倍
        val head = sb.toString().toByteArray(Charsets.UTF_8).copyOf(65535)
        assertEquals(EncodingDetector.Id.UTF8, EncodingDetector.detect(head).id)
    }

    @Test
    fun `GB18030 样本截断仍回退 GB18030`() {
        val gb = "白日依山尽，黄河入海流。".toByteArray(charset("GB18030"))
        val head = gb.copyOf(gb.size - 1) // 截断半个 GB 字符
        assertEquals(EncodingDetector.Id.GB18030, EncodingDetector.detect(head).id)
    }

    @Test
    fun `preview 不越界`() {
        val gb = "一二三四五六七八九十".toByteArray(charset("GB18030"))
        val p = EncodingDetector.preview(gb, EncodingDetector.Id.GB18030, 5)
        assertEquals(5, p.length)
    }

    @Test
    fun `四种编码都能宽松解码不抛异常`() {
        val gb = "混排文本 mixed text 123。".toByteArray(charset("GB18030"))
        for (id in EncodingDetector.Id.entries) {
            EncodingDetector.decode(gb, id)
        }
    }
}
