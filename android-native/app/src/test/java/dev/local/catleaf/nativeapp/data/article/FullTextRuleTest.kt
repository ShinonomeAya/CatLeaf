package dev.local.catleaf.nativeapp.data.article

import dev.local.catleaf.nativeapp.data.network.PublicFetch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class FullTextRuleTest {
    private val html = "<html><head><title>Story</title></head><body><article><div class=chosen>" +
        (1..12).joinToString("") { "<p>这是测试正文中的第${it}个段落，包含足够的文字以验证正文提取、规则失败回退和章节保留。</p>" } +
        "</div></article></body></html>"
    private fun extractor() = FullTextExtractor(null, PublicFetch(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
    }.build()))

    @Test fun matchingRulesUseSameSanitizedExtractionOutcome() = runBlocking {
        val outcome = extractor().extract("https://example.com/story", "Story", rules = WebsiteRules(body = ".chosen"))
        assertTrue(outcome is FullTextExtractor.Outcome.Extracted)
        outcome as FullTextExtractor.Outcome.Extracted
        assertTrue(outcome.html.contains("第12个段落"))
        assertNull(outcome.notice)
    }

    @Test fun nonmatchingRulesFallBackWithAnExplicitNotice() = runBlocking {
        val outcome = extractor().extract("https://example.com/story", "Story", rules = WebsiteRules(body = ".missing"))
        assertTrue(outcome is FullTextExtractor.Outcome.Extracted)
        outcome as FullTextExtractor.Outcome.Extracted
        assertTrue(outcome.notice!!.contains("规则未命中"))
        assertTrue(outcome.notice.contains("自动提取"))
        assertTrue(outcome.html.contains("测试正文"))
    }
}
