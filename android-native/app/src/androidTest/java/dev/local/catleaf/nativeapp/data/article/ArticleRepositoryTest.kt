package dev.local.catleaf.nativeapp.data.article

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.network.FeedApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T11：当前源阅读列表的数据行为。
 * 覆盖验收①②③：离线/失败保留旧列表、同源并发刷新去重、加载更多不重复。
 */
@RunWith(AndroidJUnit4::class)
class ArticleRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var db: CatLeafDatabase
    private val secret = "FAKE-INSTANCE-SECRET-list-test"
    private val instanceKey = "inst-a"

    private val feed = FeedEntity(
        instanceKey = instanceKey,
        feedId = "feed-1",
        url = "https://a.example.org/feed.xml",
        title = "示例源",
        category = "",
        status = "ok",
        lastSuccessAt = null,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun api() = FeedApi(origin = server.url("").toString().trimEnd('/'), secret = secret)

    @Test fun rotating_local_rss_retains_all_eight_identities_and_read_state() = runBlocking {
        var xml = rss("406", "407", "408")
        var clock = 1L
        val repository = ArticleRepository(db, null, instanceKey, now = { ++clock },
            cleanup = dev.local.catleaf.nativeapp.data.local.CacheCleanup(db, InstrumentationRegistry.getInstrumentation().targetContext),
            localFeedXml = { Result.success(xml) })
        repository.refreshNow(feed)
        val old = db.articles().findByGuid(instanceKey, feed.feedId, "406")!!
        db.articles().upsertArticle(old.copy(isRead = true, isStarred = true))
        for (window in listOf(arrayOf("409", "410", "411"), arrayOf("411", "412", "413"))) {
            xml = rss(*window)
            repository.refreshNow(feed)
        }
        assertEquals(8, repository.localCount(feed.feedId))
        assertTrue(db.articles().get(old.localId)!!.isRead)
        assertTrue(db.articles().get(old.localId)!!.isStarred)
        assertEquals(8, repository.loadLocal(feed.feedId).map { it.entity.guid }.distinct().size)
    }

    @Test fun remote_cursor_advances_even_when_history_duplicates_existing_rss() = runBlocking {
        val repository = repo()
        serve(history = historyJson(100, total = 250))
        val first = repository.loadMoreHistory(feed, 0)
        val overlap = repository.loadMoreHistory(feed, 0)
        assertEquals(0, overlap.appended)
        assertEquals(100, overlap.nextOffset)
        serve(history = historyJson(100, offsetBase = 100, total = 250))
        val second = repository.loadMoreHistory(feed, first.nextOffset)
        assertEquals(200, second.nextOffset)
        serve(history = historyJson(50, offsetBase = 200, total = 250))
        val last = repository.loadMoreHistory(feed, second.nextOffset)
        assertEquals(250, last.nextOffset)
        assertFalse(last.hasMore)
        assertEquals(250, repository.localCount(feed.feedId))
    }

    private fun repo(now: () -> Long = { System.currentTimeMillis() }) =
        ArticleRepository(
            db, api(), instanceKey, now,
            cleanup = dev.local.catleaf.nativeapp.data.local.CacheCleanup(
                db,
                InstrumentationRegistry.getInstrumentation().targetContext,
            ),
        )

    private fun rss(vararg guids: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>示例源</title>""")
        guids.forEachIndexed { i, g ->
            append("<item><title>文章 $g</title><guid>$g</guid>")
            append("<link>https://a.example.org/$g</link>")
            append("<pubDate>Tue, 22 Sep 2026 0${i}:00:00 +0800</pubDate>")
            append("<description>摘要 $g</description></item>")
        }
        append("</channel></rss>")
    }

    private fun historyJson(count: Int, offsetBase: Int = 0, total: Int = count): String {
        val items = (0 until count).joinToString(",") { i ->
            val n = offsetBase + i
            """{"guid":"h-$n","title":"历史 $n","link":"https://a.example.org/h-$n",
                "pubDate":"Tue, 22 Sep 2026 00:00:00 +0800","author":null,
                "description":"d","content":null,"thumbnail":{"url":"https://a/x.jpg"}}"""
        }
        return """{"feedId":"feed-1","items":[$items],"lastUpdated":1700000000000,"total":$total}"""
    }

    private fun serve(
        feedXml: String = rss("g1", "g2"),
        history: String = historyJson(0),
        failFeed: Boolean = false,
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/feed?") ->
                        if (failFeed) MockResponse().setResponseCode(500).setBody("boom")
                        else MockResponse().setResponseCode(200).setBody(feedXml)
                    path.startsWith("/api/history/get") -> MockResponse().setResponseCode(200)
                        .setBody(history).setHeader("Content-Type", "application/json")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    // ── 历史尽力写入（L07，契约 8.3）────────────────────────────

    @Test
    fun refresh_upserts_history_and_failure_does_not_break_refresh() = runBlocking {
        var upsertCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/feed?") -> MockResponse().setResponseCode(200).setBody(rss("g1", "g2"))
                    path.startsWith("/api/history/upsert") -> {
                        upsertCount++
                        if (upsertCount == 1) {
                            // 第一次失败：尽力写入，不影响刷新结果
                            MockResponse().setResponseCode(500).setBody("boom")
                        } else {
                            MockResponse().setResponseCode(200).setBody("{\"success\":true,\"added\":2,\"total\":2}")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val r = repo().refreshNow(feed)
        assertTrue("upsert 失败不破坏刷新结果：$r", r is ArticleRepository.RefreshOutcome.Refreshed)
        assertTrue("失败必须标记未同步", (r as ArticleRepository.RefreshOutcome.Refreshed).historyNotSynced)
        assertEquals("尽力写入失败不自动重试", 1, upsertCount)

        // 下次刷新照常尽力写（服务端幂等去重；第二次成功）
        val retried = repo().refreshNow(feed)
        assertTrue("下次刷新同步成功应清标记", !(retried as ArticleRepository.RefreshOutcome.Refreshed).historyNotSynced)
        assertEquals(2, upsertCount)
        assertTrue(db.articles().countByFeed(instanceKey, "feed-1") > 0)
    }

    // ── 本机抓取（L02，契约 8.2）────────────────────────────────

    @Test
    fun local_feed_xml_refresh_merges_and_failures_keep_old() = runBlocking {
        db.feeds().upsertAll(listOf(feed))
        var xml: Result<String> = Result.success(rss("g1"))
        val localRepo = ArticleRepository(
            db, null, instanceKey,
            cleanup = dev.local.catleaf.nativeapp.data.local.CacheCleanup(
                db, androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
            ),
            localFeedXml = { _ -> xml },
        )

        val ok = localRepo.refreshNow(feed)
        assertTrue("本机抓取应成功：$ok", ok is ArticleRepository.RefreshOutcome.Refreshed)
        assertEquals(1, db.articles().countByFeed(instanceKey, "feed-1"))

        // 抓取失败不清缓存：旧文章保留
        xml = Result.failure(dev.local.catleaf.nativeapp.data.network.EndpointError.Unreachable("网络不可用"))
        assertTrue(localRepo.refreshNow(feed) is ArticleRepository.RefreshOutcome.Failed)
        assertEquals(1, db.articles().countByFeed(instanceKey, "feed-1"))

        // 本机没有远端历史：hasMore=false 且不算错误
        val history = localRepo.loadMoreHistory(feed, 0)
        assertFalse(history.hasMore)
        assertNull(history.error)
    }

    // ── 刷新 ─────────────────────────────────────────────────────

    @Test
    fun refresh_parses_rss_and_lists_locally_first() = runBlocking {
        serve()
        val r = repo().refreshNow(feed)

        assertTrue("应刷新成功，实际：$r", r is ArticleRepository.RefreshOutcome.Refreshed)
        val loaded = repo().loadLocal(feed.feedId)
        assertEquals(2, loaded.size)
        assertEquals("标题来自 RSS", "文章 g1", loaded.first { it.entity.guid == "g1" }.entity.title)
        assertFalse("新条目默认未读", loaded.first().entity.isRead)
    }

    @Test
    fun refresh_failure_keeps_existing_list_intact() = runBlocking {
        serve()
        repo().refreshNow(feed)
        val before = repo().loadLocal(feed.feedId)
        db.articles().setRead(before[0].entity.localId, isRead = true, now = 1)

        // 之后源开始失败：不得清空本地
        serve(failFeed = true)
        val outcome = repo().refreshNow(feed)

        assertTrue("应是失败结果", outcome is ArticleRepository.RefreshOutcome.Failed)
        val after = repo().loadLocal(feed.feedId)
        assertEquals("旧列表必须保留", before.size, after.size)
        assertTrue("已读状态保留", db.articles().get(before[0].entity.localId)!!.isRead)
        assertNotNull("失败原因可读", describeRefreshError((outcome as ArticleRepository.RefreshOutcome.Failed).error))
    }

    @Test
    fun rss_that_is_actually_html_is_reported_not_silently_empty() = runBlocking {
        serve(feedXml = "<!DOCTYPE html><html><body>blocked</body></html>")
        val outcome = repo().refreshNow(feed)

        assertTrue(outcome is ArticleRepository.RefreshOutcome.Failed)
        val message = describeRefreshError((outcome as ArticleRepository.RefreshOutcome.Failed).error)
        assertTrue("应说明是网页而非订阅，实际：$message", message.contains("网页"))
    }

    @Test
    fun concurrent_refresh_of_same_feed_only_requests_once() = runBlocking {
        serve()
        val repository = repo()

        // 两个并发刷新：第二个应等锁并在拿到后发现自己已“新鲜”，只发一次 /api/feed
        val results = listOf(
            async { repository.refreshNow(feed) },
            async { repository.refreshNow(feed) },
        ).awaitAll()

        val feedRequests = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .count { it.path.orEmpty().startsWith("/api/feed?") }
        assertEquals("同源并发刷新只应发出一次抓取", 1, feedRequests)
        assertTrue("第一个应真的刷新", results[0] is ArticleRepository.RefreshOutcome.Refreshed)
        assertTrue(
            "第二个应复用第一次的结果（跳过），而不是再抓一次，实际：${results[1]}",
            results[1] is ArticleRepository.RefreshOutcome.SkippedFresh,
        )
    }

    @Test
    fun refresh_if_stale_respects_fifteen_minute_window() = runBlocking {
        serve()
        var clock = 1_000_000L
        val repository = repo(now = { clock })

        assertTrue("首次应视为过期", repository.isStale(feed.feedId))
        repository.refreshIfStale(feed)

        clock += ArticleRepository.REFRESH_INTERVAL_MS - 1
        assertFalse("15 分钟内不再自动刷新", repository.isStale(feed.feedId))
        assertTrue(
            "按需刷新应跳过",
            repository.refreshIfStale(feed) is ArticleRepository.RefreshOutcome.SkippedFresh,
        )

        clock += 2
        assertTrue("超过 15 分钟应再次视为过期", repository.isStale(feed.feedId))

        val feedRequests = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .count { it.path.orEmpty().startsWith("/api/feed?") }
        assertEquals("15 分钟窗口内只抓取过一次", 1, feedRequests)
    }

    // ── 历史分页 ─────────────────────────────────────────────────

    @Test
    fun load_more_appends_without_duplicates() = runBlocking {
        // 第一页 h-0..h-99，服务端合计 160 条
        serve(history = historyJson(count = 100, offsetBase = 0, total = 160))
        val repository = repo()

        val first = repository.loadMoreHistory(feed, offset = 0)
        assertEquals(100, first.appended)
        assertTrue("还有更多历史", first.hasMore)
        assertEquals(100, repository.localCount(feed.feedId))

        // 第二页故意与第一页重叠 10 条（h-90..h-99），另有 60 条新增
        serve(history = historyJson(count = 70, offsetBase = 90, total = 160))
        val second = repository.loadMoreHistory(feed, offset = 100)

        assertEquals("重叠的 10 条不得重复写入，只加 60 条", 60, second.appended)
        assertEquals("本地总量应为 160", 160, repository.localCount(feed.feedId))
        assertFalse("已取完服务端的 160 条", second.hasMore)

        // 逐页遍历不得出现重复 GUID
        val guids = repository.loadLocal(feed.feedId, limit = 500).map { it.entity.guid }
        assertEquals("无重复", guids.size, guids.toSet().size)
    }

    @Test
    fun history_paging_failure_keeps_loaded_items() = runBlocking {
        serve(history = historyJson(count = 50, total = 150))
        val repository = repo()
        repository.loadMoreHistory(feed, offset = 0)
        assertEquals(50, repository.localCount(feed.feedId))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        }

        // 先定性：连接中断绝不能当成“成功的空页”
        val direct = api().fetchHistory(feed.feedId, ArticleRepository.HISTORY_PAGE_SIZE, 50)
        assertTrue("连接中断必须报错，不能当成空页，实际：$direct", direct.isFailure)

        val failed = repository.loadMoreHistory(feed, offset = 50)

        assertEquals("失败不追加", 0, failed.appended)
        assertNotNull("应带回错误，实际 outcome=$failed", failed.error)
        assertEquals("已加载内容保留", 50, repository.localCount(feed.feedId))
    }

    @Test
    fun history_items_without_identifier_are_skipped_and_counted() = runBlocking {
        val body = """{"feedId":"feed-1","items":[
            {"title":"正常","guid":"h1","link":"https://a.example.org/h1"},
            {"title":"无标识","guid":null,"link":null},
            {"title":"链接无效","guid":null,"link":"not a url"}
        ],"total":3}"""
        serve(history = body)
        val outcome = repo().loadMoreHistory(feed, offset = 0)

        assertEquals("只有 1 条能存", 1, outcome.appended)
        assertEquals(1, repo().localCount(feed.feedId))
        assertEquals("三条响应均已消费，不能因无效身份反复请求同页", false, outcome.hasMore)
        assertEquals(3, outcome.nextOffset)
    }

    @Test
    fun feed_snapshot_uses_current_title_from_rss() = runBlocking {
        serve(feedXml = """<?xml version="1.0"?><rss version="2.0"><channel><title>解析出的源名</title>
            <item><title>一</title><guid>g1</guid><link>https://a.example.org/1</link></item>
            </channel></rss>""")
        repo().refreshNow(feed)

        val row = repo().loadLocal(feed.feedId).first()
        assertEquals("来源名快照取解析标题", "解析出的源名", row.entity.feedTitle)
        assertEquals("来源 URL 快照保留", feed.url, row.entity.feedUrl)
    }

    @Test
    fun list_order_is_stable_across_repeated_reads() = runBlocking {
        serve(feedXml = rss("g1", "g2", "g3"))
        val repository = repo()
        repository.refreshNow(feed)

        val first = repository.loadLocal(feed.feedId).map { it.entity.localId }
        val second = repository.loadLocal(feed.feedId).map { it.entity.localId }
        assertEquals("重复读取顺序一致", first, second)
        assertEquals("按发布时间倒序（最新在前）", "g3", repository.loadLocal(feed.feedId).first().entity.guid)
    }
}
