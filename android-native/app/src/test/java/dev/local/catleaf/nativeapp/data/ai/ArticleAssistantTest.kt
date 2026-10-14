package dev.local.catleaf.nativeapp.data.ai

import dev.local.catleaf.nativeapp.data.local.AiCacheDao
import dev.local.catleaf.nativeapp.data.local.AiCacheEntity
import dev.local.catleaf.nativeapp.data.local.AiResultStat
import dev.local.catleaf.nativeapp.data.network.AiClient
import dev.local.catleaf.nativeapp.data.network.HttpClients
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArticleAssistantTest {
    private class Cache : AiCacheDao {
        val rows = mutableMapOf<String, AiCacheEntity>()
        override suspend fun insert(result: AiCacheEntity) { rows[result.cacheKey] = result }
        override suspend fun get(cacheKey: String) = rows[cacheKey]
        override suspend fun latestCompleteByPrefix(prefix: String): AiCacheEntity? = null
        override suspend fun clearInstance(instanceKey: String) { rows.values.removeAll { it.instanceKey == instanceKey } }
        override suspend fun resultStats() = emptyList<AiResultStat>()
        override suspend fun deleteKeys(keys: List<String>) { rows.keys.removeAll(keys.toSet()) }
    }
    private fun request() = ArticleAssistant.Request("scope-a", "article-a",
        BilingualArticle.fromHtml("<p>The pilot covers two routes.</p><p>The budget is not specified.</p>", "Bus pilot"),
        "Rss", ArticleAssistant.Mode.Overview, baseUrl = "https://ai.example.org", model = "model-a", apiKey = "FAKE-AI-KEY")
    private fun answer(id: String = "catleaf-b1") = JSONObject().put("text", "试点覆盖两条线路。")
        .put("sourceIds", JSONArray(listOf(id))).put("notInArticle", false).put("background", "")
        .put("points", JSONArray().put(JSONObject().put("text", "覆盖两条线路").put("sourceIds", JSONArray(listOf(id)))))
    private fun response(content: String, finish: String = "stop") = MockResponse().setBody(JSONObject().put("choices",
        JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)).put("finish_reason", finish))).toString())

    @Test fun matching_cache_does_not_require_key_and_failed_regeneration_keeps_the_previous_result() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val cache = Cache()
            val assistant = ArticleAssistant(cache, AiClient { key -> HttpClients.forAi(key).newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/chat/completions")).build())
            }.build() })
            val request = request()
            server.enqueue(response(answer().toString()))
            assertTrue(assistant.run(request) is ArticleAssistant.Outcome.Complete)
            val received = server.takeRequest()
            assertEquals("Bearer FAKE-AI-KEY", received.getHeader("Authorization"))
            assertTrue(received.body.readUtf8().contains("数据"))
            val cached = assistant.run(request.copy(apiKey = "")) as ArticleAssistant.Outcome.Complete
            assertTrue(cached.cached)
            assertEquals(1, server.requestCount)
            val afterOtherTasks = request.copy(apiKey = "", question = "预算？", paragraphId = "catleaf-b2")
            assertEquals(cached, assistant.run(afterOtherTasks))
            assertEquals(1, server.requestCount)
            assertNull(assistant.cached(request.copy(scope = "scope-b")))
            assertNull(assistant.cached(request.copy(article = BilingualArticle.fromText("New body"))))
            server.enqueue(response(answer("catleaf-b99").toString()))
            assertTrue(assistant.run(request, true) is ArticleAssistant.Outcome.Incomplete)
            server.enqueue(response(answer().toString(), "length"))
            assertTrue(assistant.run(request, true) is ArticleAssistant.Outcome.Incomplete)
            server.enqueue(MockResponse().setResponseCode(429))
            assertTrue(assistant.run(request, true) is ArticleAssistant.Outcome.Failed)
            assertEquals(cached.answer, assistant.cached(request))
            assertEquals(1, cache.rows.size)
        } finally { server.shutdown() }
    }

    @Test fun invalid_references_missing_facts_and_explanations_are_checked_against_frozen_paragraphs() {
        val r = request()
        assertNull(ArticleAssistant.parse(answer("https://evil.example/execute").toString(), r))
        assertNull(ArticleAssistant.parse(answer().put("notInArticle", "false").toString(), r))
        assertNull(ArticleAssistant.parse(answer().toString() + " trailing text", r))
        val missing = answer().put("text", "本文未说明预算。").put("notInArticle", true)
            .put("sourceIds", JSONArray()).put("points", JSONArray()).toString()
        assertNull(ArticleAssistant.parse(missing, r))
        assertTrue(ArticleAssistant.parse(missing, r.copy(mode = ArticleAssistant.Mode.Question, question = "预算？"))!!.notInArticle)
        val explain = r.copy(mode = ArticleAssistant.Mode.Explain, paragraphId = "catleaf-b2")
        assertNull(ArticleAssistant.parse(answer().toString(), explain))
        assertEquals("词义说明", ArticleAssistant.parse(answer("catleaf-b2").put("background", "词义说明").toString(), explain)!!.background)
    }

    @Test fun oversize_and_empty_inputs_are_blocked_before_network() = runBlocking {
        val assistant = ArticleAssistant(Cache())
        val r = request()
        assertTrue(assistant.run(r.copy(article = BilingualArticle.fromText("x".repeat(20_001)))) is ArticleAssistant.Outcome.Blocked)
        assertTrue(assistant.run(r.copy(article = BilingualArticle.fromHtml("<img src='https://example.org/image'/>", "title"))) is ArticleAssistant.Outcome.Blocked)
        assertTrue(assistant.run(r.copy(mode = ArticleAssistant.Mode.Explain, paragraphId = "catleaf-b99")) is ArticleAssistant.Outcome.Blocked)
        assertTrue(assistant.run(r.copy(mode = ArticleAssistant.Mode.Question, question = "")) is ArticleAssistant.Outcome.Blocked)
    }

    @Test fun native_selection_must_match_one_current_block_and_has_its_own_cache() = runBlocking {
        val r = request().copy(mode = ArticleAssistant.Mode.Explain, paragraphId = "catleaf-b1", selection = "two routes")
        assertEquals("catleaf-b1", r.article.selectionBlock("two\u00a0routes")?.id)
        assertNull(r.article.selectionBlock("译文不存在"))
        assertNull(BilingualArticle.fromText("same\n\nsame").selectionBlock("same"))
        assertNull(r.article.selectionBlock("two routes. The budget"))
        assertNull(r.article.selectionBlock("x".repeat(2001)))
        assertTrue(ArticleAssistant.buildPrompt(r).contains("\"selectedText\":\"two routes\""))
        assertNotEquals(ArticleAssistant.cacheKey(r), ArticleAssistant.cacheKey(r.copy(selection = "The pilot")))
        assertNotEquals(ArticleAssistant.cacheKey(r), ArticleAssistant.cacheKey(r.copy(selection = "")))
        assertEquals(ArticleAssistant.cacheKey(request()), ArticleAssistant.cacheKey(request().copy(selection = "ignored")))
        val server = MockWebServer().apply { start() }
        try {
            val assistant = ArticleAssistant(Cache(), AiClient { key -> HttpClients.forAi(key).newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/chat/completions")).build())
            }.build() })
            assertTrue(assistant.run(r.copy(paragraphId = "catleaf-b2")) is ArticleAssistant.Outcome.Blocked)
            assertEquals(0, server.requestCount)
            server.enqueue(response(answer().toString()))
            assertTrue(assistant.run(r) is ArticleAssistant.Outcome.Complete)
            assertTrue(server.takeRequest().body.readUtf8().contains("selectedText"))
            assertTrue((assistant.run(r.copy(apiKey = "")) as ArticleAssistant.Outcome.Complete).cached)
            assertEquals(1, server.requestCount)
            assertNull(assistant.cached(r.copy(selection = "The pilot")))
        } finally { server.shutdown() }
    }
}
