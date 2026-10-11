package dev.local.catleaf.nativeapp.data.rss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 契约 8.2：XML 按 BOM/声明/响应编码处理，不能把所有 XML 强解 UTF-8。 */
class FeedXmlDecoderTest {

    @Test fun `utf-8 without any declaration decodes as utf-8`() {
        val xml = "<?xml version=\"1.0\"?><rss><channel><title>中文标题</title></channel></rss>"
        assertEquals(xml, FeedXmlDecoder.decode(xml.toByteArray(Charsets.UTF_8)).getOrThrow())
    }

    @Test fun `utf-8 bom is stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "<?xml version=\"1.0\"?><rss/>".toByteArray(Charsets.UTF_8)
        assertEquals("<?xml version=\"1.0\"?><rss/>", FeedXmlDecoder.decode(bytes).getOrThrow())
    }

    @Test fun `gbk declared in xml declaration decodes chinese`() {
        val xml = "<?xml version=\"1.0\" encoding=\"gbk\"?><rss><channel><title>猫页中文</title></channel></rss>"
        assertEquals(xml, FeedXmlDecoder.decode(xml.toByteArray(charset("GBK"))).getOrThrow())
    }

    @Test fun `content-type charset wins when no declaration`() {
        val xml = "<rss><title>响应头编码</title></rss>"
        assertEquals(xml, FeedXmlDecoder.decode(xml.toByteArray(charset("GBK")), "text/xml; charset=GBK").getOrThrow())
    }

    @Test fun `content-type beats nothing but bom beats content-type`() {
        // BOM 优先级最高：即使 Content-Type 声明别的，BOM 的 UTF-8 也要被剥掉
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<rss/>".toByteArray(Charsets.UTF_8)
        assertEquals("<rss/>", FeedXmlDecoder.decode(bytes, "text/xml; charset=GBK").getOrThrow())
    }

    @Test fun `unsupported declared charset fails clearly`() {
        val xml = "<?xml version=\"1.0\" encoding=\"not-a-charset\"?><rss/>"
        val result = FeedXmlDecoder.decode(xml.toByteArray(Charsets.UTF_8))
        assertTrue(result.exceptionOrNull() is FeedXmlDecoder.DecodeError.UnsupportedCharset)
    }

    @Test fun `invalid bytes for declared charset fail instead of silent replacement`() {
        // 声明 UTF-8 但内容是 GBK 的中文：必须报错，不能解出乱码蒙混
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><title>中文</title>"
        val gbkBytes = xml.toByteArray(charset("GBK"))
        assertTrue(FeedXmlDecoder.decode(gbkBytes).isFailure)
    }
}
