package dev.local.catleaf.nativeapp.data.article

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

/**
 * 全文提取的字符集判定：中文源里 GBK/GB2312 很常见，猜错会整页乱码，
 * 而乱码会被当成“提取成功但内容不可读”。这里覆盖响应头、meta 嗅探与兜底三条路径。
 */
class FullTextExtractorTest {

    private val gbk = Charset.forName("GBK")
    private val chinese = "中文正文示例"

    @Test
    fun uses_charset_from_response_header() {
        val html = "<html><body><p>$chinese</p></body></html>"
        assertEquals(
            "声明了 charset=gbk 就应按 GBK 解码",
            html,
            FullTextExtractor.decodeHtml(html.toByteArray(gbk), "text/html; charset=gbk"),
        )
    }

    @Test
    fun sniffs_charset_from_meta_tag() {
        val html = """<html><head><meta charset="gbk"></head><body><p>$chinese</p></body></html>"""
        assertEquals(
            "没有响应头声明时按 meta 里的 charset 解码",
            html,
            FullTextExtractor.decodeHtml(html.toByteArray(gbk), "text/html"),
        )
    }

    @Test
    fun sniffs_charset_from_http_equiv_meta() {
        val html = """<html><head><meta http-equiv="Content-Type" content="text/html; charset=gb2312"></head>
            <body><p>$chinese</p></body></html>"""
        assertEquals(
            "http-equiv 形式的声明也要认",
            html,
            FullTextExtractor.decodeHtml(html.toByteArray(gbk), null),
        )
    }

    @Test
    fun falls_back_to_utf8_without_any_declaration() {
        val html = "<html><body><p>$chinese</p></body></html>"
        assertEquals(
            "没有任何声明时按 UTF-8",
            html,
            FullTextExtractor.decodeHtml(html.toByteArray(Charsets.UTF_8), null),
        )
    }

    @Test
    fun unknown_charset_name_falls_back_instead_of_crashing() {
        val html = "<html><body><p>$chinese</p></body></html>"
        assertEquals(
            "无法识别的字符集名不抛异常，退回 UTF-8",
            html,
            FullTextExtractor.decodeHtml(html.toByteArray(Charsets.UTF_8), "text/html; charset=not-a-charset"),
        )
    }
}