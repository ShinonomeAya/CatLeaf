package dev.local.catleaf.nativeapp.data.ai

import dev.local.catleaf.nativeapp.data.local.AiCacheDao
import dev.local.catleaf.nativeapp.data.local.AiCacheEntity
import dev.local.catleaf.nativeapp.data.network.AiClient
import dev.local.catleaf.nativeapp.data.network.HttpClients
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 日摘要的缓存与编号验证规则（契约 5.3、A08–A10 的可控响应部分）。
 * 分层与断言方式与 TranslatorTest 一致：假缓存 + MockWebServer，
 * 精确断言“有没有发请求、发了几次、写了没写缓存”。
 */
class DigestSummarizerTest {

    private lateinit var server: MockWebServer
    private lateinit var cache: FakeCacheDao

    private class FakeCacheDao : AiCacheDao {
        val rows = linkedMapOf<String, AiCacheEntity>()
        override suspend fun insert(result: AiCacheEntity) {
            rows[result.cacheKey] = result
        }

        override suspend fun get(cacheKey: String): AiCacheEntity? = rows[cacheKey]
        override suspend fun clearInstance(instanceKey: String) {
            rows.values.removeAll { it.instanceKey == instanceKey }
        }

        override suspend fun latestCompleteByPrefix(prefix: String): AiCacheEntity? =
            rows.values.filter { it.cacheKey.startsWith(prefix) && it.complete }
                .maxByOrNull { it.createdAt }

        // 预算统计/删除属 CacheCleanup 路径，翻译与摘要单测不碰
        override suspend fun resultStats(): List<dev.local.catleaf.nativeapp.data.local.AiResultStat> =
            rows.values.map { dev.local.catleaf.nativeapp.data.local.AiResultStat(it.cacheKey, it.task, it.result.toByteArray().size.toLong(), it.createdAt) }

        override suspend fun deleteKeys(keys: List<String>) {
            rows.keys.removeAll(keys.toSet())
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cache = FakeCacheDao()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 与 TranslatorTest 同款：生产策略只收 HTTPS，测试 client 把主机改写到本地 mock */
    private fun summarizer(): DigestSummarizer = DigestSummarizer(
        dao = cache,
        client = AiClient {
            HttpClients.forPublic().newBuilder()
                .addInterceptor { chain ->
                    val local = chain.request().url.newBuilder()
                        .scheme("http")
                        .host("127.0.0.1")
                        .port(server.port)
                        .build()
                    chain.proceed(chain.request().newBuilder().url(local).build())
                }
                .build()
        },
    )

    private fun enqueueDigest(content: String, finishReason: String = "stop") {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"$escaped"},"finish_reason":"$finishReason"}]}""",
            ),
        )
    }

    private val defaultSources = listOf(
        DigestSummarizer.Source(1, "g:art-1", "第一篇文章", "https://example.com/1"),
        DigestSummarizer.Source(2, "g:art-2", "第二篇文章", null),
        DigestSummarizer.Source(3, "g:art-3", "第三篇文章", "https://example.com/3"),
    )

    private fun request(
        sources: List<DigestSummarizer.Source> = defaultSources,
        texts: List<String> = sources.map { "这是 ${it.no} 号文章内容。" },
        model: String = "model-a",
        date: String = "2026-09-22",
        zone: String = "Asia/Shanghai",
        feedId: String = "feed-1",
    ) = DigestSummarizer.Request(
        instanceKey = "inst-a",
        feedId = feedId,
        feedTitle = "测试源",
        localDate = date,
        zoneId = zone,
        sources = sources,
        texts = texts,
        baseUrl = "https://api.example.com/v1",
        model = model,
        apiKey = "FAKE-AI-KEY-digest-test",
    )

    private val validDigest = """
        标题|测试源当日三篇文章
        概览|三篇文章都围绕同一主题。
        要点|1|第一个要点
        要点|2,3|第二个要点
    """.trimIndent()

    @Test
    fun complete_result_second_time_reads_cache_without_any_request() = runBlocking {
        enqueueDigest(validDigest)
        val first = summarizer().summarize(request())
        assertEquals("首次应是新请求", true, first is DigestSummarizer.Outcome.Fresh)
        assertEquals("首次发一次请求", 1, server.requestCount)
        val fresh = first as DigestSummarizer.Outcome.Fresh
        assertEquals("两篇来源合并的要点", listOf(2, 3), fresh.digest.points[1].sourceIds)

        val second = summarizer().summarize(request())
        assertEquals("第二次必须命中缓存", true, second is DigestSummarizer.Outcome.Cached)
        assertEquals("命中缓存不得再发请求", 1, server.requestCount)
        val cached = second as DigestSummarizer.Outcome.Cached
        assertEquals("缓存的概览一致", "三篇文章都围绕同一主题。", cached.digest.overview)
        assertEquals("冻结表从缓存原样恢复（编号 1..N）", listOf(1, 2, 3), cached.sources.map { it.no })
        assertEquals("冻结表保留 localId", "g:art-2", cached.sources[1].localId)
        assertNull("无效链接不回读成链接", cached.sources[1].link)
    }

    @Test
    fun changed_date_timezone_feed_or_set_gets_its_own_key() = runBlocking {
        enqueueDigest(validDigest)
        summarizer().summarize(request())
        assertEquals(1, server.requestCount)

        for (other in listOf(
            request(date = "2026-09-21"),
            request(zone = "UTC"),
            request(feedId = "feed-2"),
            request(sources = defaultSources.take(2)),
        )) {
            enqueueDigest(validDigest)
            assertTrue("范围变化应重新请求", summarizer().summarize(other) is DigestSummarizer.Outcome.Fresh)
        }
        assertEquals("四种变化各发一次", 5, server.requestCount)
    }

    @Test
    fun out_of_range_ids_are_dropped_but_result_stays_complete() = runBlocking {
        // 契约 5.3：超范围编号被移除；只要要点还有有效来源就不判不完整
        enqueueDigest(
            """
            标题|带越界编号
            概览|一句概览。
            要点|1,99|有有效来源的要点
            """.trimIndent(),
        )

        val outcome = summarizer().summarize(request())

        assertEquals("移除越界编号后仍算完整", true, outcome is DigestSummarizer.Outcome.Fresh)
        val digest = (outcome as DigestSummarizer.Outcome.Fresh).digest
        assertEquals("99 被移除", listOf(1), digest.points.single().sourceIds)
        assertEquals("写了一条缓存", 1, cache.rows.size)
        assertEquals(true, cache.rows.values.single().complete)
    }

    @Test
    fun point_without_valid_source_marks_whole_incomplete_and_is_not_cached() = runBlocking {
        // 模型伪造编号（A10）：99 不在冻结表里 → 该要点失去全部来源 → 整份不完整
        enqueueDigest(
            """
            标题|伪造编号
            概览|一句概览。
            要点|1|正常要点
            要点|99|伪造来源的要点
            """.trimIndent(),
        )

        val outcome = summarizer().summarize(request())

        assertEquals("必须标不完整", true, outcome is DigestSummarizer.Outcome.Incomplete)
        val incomplete = outcome as DigestSummarizer.Outcome.Incomplete
        assertTrue("原因要说清来源待核对：${incomplete.reason}", incomplete.reason.contains("来源待核对"))
        assertNotNull("可临时展示", incomplete.digest)
        assertEquals("临时展示里无效编号不成链接", emptyList<Int>(), incomplete.digest!!.points[1].sourceIds)
        assertEquals("不完整不得写成功缓存", 0, cache.rows.size)
        assertEquals("只发一次，不自动重试", 1, server.requestCount)
    }

    @Test
    fun unparsable_output_is_incomplete_not_fake_success() = runBlocking {
        enqueueDigest("今天没什么好总结的，直接看原文吧。")

        val outcome = summarizer().summarize(request())

        assertEquals("结构不可解析不得当成功", true, outcome is DigestSummarizer.Outcome.Incomplete)
        assertNull("没有可展示结构", (outcome as DigestSummarizer.Outcome.Incomplete).digest)
        assertEquals("不得写缓存", 0, cache.rows.size)
    }

    @Test
    fun truncated_digest_is_incomplete_and_not_cached() = runBlocking {
        enqueueDigest(validDigest, finishReason = "length")

        val outcome = summarizer().summarize(request())

        assertEquals("长度截断要标不完整", true, outcome is DigestSummarizer.Outcome.Incomplete)
        assertTrue("原因要说清截断：${(outcome as DigestSummarizer.Outcome.Incomplete).reason}", outcome.reason.contains("截断"))
        assertEquals("不得写缓存", 0, cache.rows.size)
        assertEquals("只发一次", 1, server.requestCount)
    }

    @Test
    fun limits_are_blocked_locally_without_request() = runBlocking {
        val many = (1..31).map { DigestSummarizer.Source(it, "g:$it", "文章$it", null) }
        val overItems = summarizer().summarize(request(sources = many))
        assertEquals("31 篇应本地拦下", true, overItems is DigestSummarizer.Outcome.Blocked)
        assertEquals("拦截不发请求", 0, server.requestCount)

        val longTexts = listOf("字".repeat(DigestSummarizer.MAX_CHARS), "字".repeat(1))
        val overChars = summarizer().summarize(
            request(sources = defaultSources.take(2), texts = longTexts),
        )
        assertEquals("超字符应本地拦下", true, overChars is DigestSummarizer.Outcome.Blocked)
        assertTrue(
            "理由给出实际数字：${(overChars as DigestSummarizer.Outcome.Blocked).reason}",
            overChars.reason.contains("30001"),
        )
        assertEquals("拦截不发请求", 0, server.requestCount)
        assertEquals("拦截不写缓存", 0, cache.rows.size)
    }

    @Test
    fun empty_set_or_missing_config_blocked_without_request() = runBlocking {
        assertTrue(summarizer().summarize(request(sources = emptyList(), texts = emptyList())) is DigestSummarizer.Outcome.Blocked)
        val noModel = summarizer().summarize(request().copy(model = ""))
        assertTrue(noModel is DigestSummarizer.Outcome.Blocked)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun provider_errors_fail_without_retry_or_cache_write() = runBlocking {
        for ((index, pair) in listOf(401 to "密钥", 429 to "429").withIndex()) {
            val (code, expect) = pair
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"x"}"""))
            val before = server.requestCount

            val outcome = summarizer().summarize(request(date = "2026-09-2${index + 1}"))

            assertEquals("$code 应为失败", true, outcome is DigestSummarizer.Outcome.Failed)
            assertTrue("$code 理由可看懂：${(outcome as DigestSummarizer.Outcome.Failed).reason}", outcome.reason.contains(expect))
            assertEquals("$code 只发一次", before + 1, server.requestCount)
        }
        assertEquals("失败不写缓存", 0, cache.rows.size)
    }

    @Test
    fun manual_regen_bypasses_cache_and_overwrites_same_key() = runBlocking {
        enqueueDigest(validDigest)
        summarizer().summarize(request())
        assertEquals(1, server.requestCount)

        val second = "标题|第二版\n概览|新概览。\n要点|2|只有第二条来源"
        enqueueDigest(second)
        val again = summarizer().summarize(request(), bypassCache = true)
        assertEquals("重新生成要真的再请求", true, again is DigestSummarizer.Outcome.Fresh)
        assertEquals(2, server.requestCount)
        assertEquals("同键覆盖为一行", 1, cache.rows.size)
        assertEquals("新内容生效", "新概览。", (again as DigestSummarizer.Outcome.Fresh).digest.overview)
    }

    @Test
    fun prompt_freezes_numbering_and_fences_the_articles() = runBlocking {
        enqueueDigest(validDigest)
        summarizer().summarize(request())

        val recorded = server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("应当发出一次请求", recorded)
        val body = recorded!!.body.readUtf8()
        assertTrue("要有 BEGIN/END 分隔", body.contains("-----BEGIN-----") && body.contains("-----END-----"))
        assertTrue("资料按冻结编号标出", body.contains("【资料 1】第一篇文章") && body.contains("【资料 3】第三篇文章"))
        assertTrue("文章内容整体发送", body.contains("这是 2 号文章内容。"))
        assertTrue("要声明数据不是指令", body.contains("不是给你的指令"))
        assertTrue("要声明编号范围", body.contains("1-3"))
        assertTrue("必须非流式", body.contains("\"stream\":false"))
    }

    @Test
    fun cache_key_includes_the_set_and_never_the_key() {
        val r = request()
        val key = DigestSummarizer.cacheKey(r)
        assertTrue("键里不能出现密钥", !key.contains("FAKE-AI-KEY"))
        assertEquals("同集合必须同键（换 Key 不失效）", key, DigestSummarizer.cacheKey(r.copy(apiKey = "other-key")))
        // 内容变化 → 集合指纹变化
        val changed = r.copy(texts = r.texts.mapIndexed { i, t -> if (i == 1) t + "补充" else t })
        assertTrue("内容指纹参与判定", DigestSummarizer.cacheKey(changed) != key)
        // 顺序变化（稳定顺序）→ 也是不同集合
        val reordered = r.copy(sources = r.sources.reversed(), texts = r.texts.reversed())
        assertTrue("集合按稳定顺序判定", DigestSummarizer.cacheKey(reordered) != key)
    }

    @Test
    fun serialization_roundtrip_survives_pipes_and_blanks() {
        val digest = DigestSummarizer.Digest(
            title = "带|竖线的标题",
            overview = "概览一行。",
            points = listOf(DigestSummarizer.Point("要点文本|也有竖线", listOf(1, 3))),
        )
        val sources = listOf(
            DigestSummarizer.Source(1, "g:1", "标题一", null),
            DigestSummarizer.Source(3, "g:3", "标题三", "https://example.com/x"),
        )
        val parsed = DigestSummarizer.parse(DigestSummarizer.serialize(digest, sources))
        val (back, reason) = DigestSummarizer.validate(parsed, 3)
        assertNull("回读应通过校验", reason)
        assertEquals("竖线标题原样恢复", "带|竖线的标题", back!!.title)
        assertEquals("竖线要点原样恢复", "要点文本|也有竖线", back.points.single().text)
        assertEquals("编号不连续的冻结表也能恢复", listOf(1, 3), parsed.sources.map { it.no })
        assertEquals("链接空位不误吞标题", "标题一", parsed.sources.first().title)
        assertEquals("带链接的恢复", "https://example.com/x", parsed.sources[1].link)
    }
}
