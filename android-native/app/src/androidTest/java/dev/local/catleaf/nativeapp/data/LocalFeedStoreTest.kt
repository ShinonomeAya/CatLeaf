package dev.local.catleaf.nativeapp.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.network.EndpointError
import dev.local.catleaf.nativeapp.data.network.FeedApiError
import dev.local.catleaf.nativeapp.data.opml.Opml
import dev.local.catleaf.nativeapp.data.opml.OpmlImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L02（契约 8.2）：本机订阅 Room-first——先写本机后验证、失败保留源、
 * 重复 URL 去重、同站不同路径独立、编辑/删除离线可完成、200 源上限、删源留收藏、OPML 本机导入。
 */
@RunWith(AndroidJUnit4::class)
class LocalFeedStoreTest {

    private lateinit var db: CatLeafDatabase
    private lateinit var store: LocalFeedStore
    private val scopeKey = dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY

    /** 假抓取通道：URL → RSS XML 或错误 */
    private val fakeResults = mutableMapOf<String, Result<String>>()

    private fun rss(title: String, vararg guids: String): String = buildString {
        append("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>$title</title>")
        guids.forEach { g ->
            append("<item><title>文章 $g</title><guid>$g</guid><link>https://a.example.org/$g</link>")
            append("<description>摘要 $g</description></item>")
        }
        append("</channel></rss>")
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
        store = LocalFeedStore(
            db = db,
            scopeKey = scopeKey,
            cleanup = dev.local.catleaf.nativeapp.data.local.CacheCleanup(db, context),
            fetchXml = { row -> fakeResults[row.url] ?: Result.failure(EndpointError.Unreachable("无假响应")) },
        )
    }

    @After
    fun tearDown() = runBlocking {
        db.feeds().deleteAll(scopeKey)
        db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE instanceKey = ?", arrayOf(scopeKey))
        db.close()
    }

    @Test fun register_writes_room_then_verify_marks_ok_and_merges_articles() = runBlocking {
        fakeResults["https://a.example.org/rss"] = Result.success(rss("示例源", "g1", "g2"))
        val id = store.add("https://a.example.org/rss", "科技", "").getOrThrow().feedId

        val row = db.feeds().list(scopeKey).single()
        assertEquals(id, row.feedId)
        assertEquals("a.example.org", row.title) // 名称以 URL 主机兜底
        assertEquals(FeedStore.STATUS_OK, row.status)
        assertNotNull(row.lastSuccessAt)
        assertEquals(2, db.articles().listByFeed(scopeKey, id, 10, 0).size)
    }

    @Test fun verify_failure_keeps_source_with_reason_and_retry_can_recover() = runBlocking {
        fakeResults["https://a.example.org/rss"] = Result.failure(EndpointError.Unreachable("网络不可用"))
        val added = store.add("https://a.example.org/rss", "", "我的源").getOrThrow()
        // L06/C05：登记与验证结果分开——登记成功但验证失败，源保留且原因在结果里
        assertTrue("登记成功", added.feedId.isNotBlank())
        assertTrue("验证应失败", !added.verified)
        assertNotNull(added.verifyError)
        assertEquals("登记成功，但验证失败保留源", 1, db.feeds().list(scopeKey).size)
        assertEquals(FeedStore.STATUS_FAILED, db.feeds().list(scopeKey).single().status)
        assertEquals("我的源", db.feeds().list(scopeKey).single().title)

        // 重试成功：状态翻正、文章合并进来
        fakeResults["https://a.example.org/rss"] = Result.success(rss("示例源", "g1"))
        store.verify(added.feedId).getOrThrow()
        assertEquals(FeedStore.STATUS_OK, db.feeds().list(scopeKey).single().status)
        assertEquals(1, db.articles().listByFeed(scopeKey, added.feedId, 10, 0).size)
    }

    @Test fun duplicate_url_rejected_but_same_site_different_path_independent() = runBlocking {
        fakeResults["https://a.example.org/rss"] = Result.success(rss("A", "g1"))
        store.add("https://A.example.org/rss", "", "").getOrThrow()

        // 规范化后同 URL（大小写/主机）→ 拒绝
        val dup = store.add("https://a.example.org/rss", "", "")
        assertTrue(dup.exceptionOrNull() is FeedApiError.Conflict)

        // 同站不同路径保持独立（契约 8.2）
        fakeResults["https://a.example.org/other"] = Result.success(rss("B", "g9"))
        store.add("https://a.example.org/other", "", "").getOrThrow()
        assertEquals(2, db.feeds().list(scopeKey).size)
    }

    @Test fun non_https_and_private_targets_rejected_before_any_write() = runBlocking {
        assertTrue(store.add("http://a.example.org/rss", "", "").isFailure)
        assertTrue(store.add("https://127.0.0.1/rss", "", "").isFailure)
        assertEquals("写入前拒绝：库里没有任何行", 0, db.feeds().list(scopeKey).size)
    }

    @Test fun edit_keeps_url_and_blank_title_keeps_old_name() = runBlocking {
        fakeResults["https://a.example.org/rss"] = Result.success(rss("A", "g1"))
        val id = store.add("https://a.example.org/rss", "旧分组", "我的源").getOrThrow().feedId

        store.updateMeta(id, "新分组", "").getOrThrow()
        val row = db.feeds().list(scopeKey).single()
        assertEquals("新分组", row.category)
        assertEquals("URL 不可通过编辑改变", "https://a.example.org/rss", row.url)
        assertEquals("我的源", row.title)
    }

    @Test fun delete_removes_row_but_keeps_favorites() = runBlocking {
        fakeResults["https://a.example.org/rss"] = Result.success(rss("A", "g1"))
        val id = store.add("https://a.example.org/rss", "", "").getOrThrow().feedId
        val article = db.articles().findByGuid(scopeKey, id, "g1") ?: error("merged article missing")
        db.articles().setStarred(article.localId, true, 1L, 1L)

        store.delete(id).getOrThrow()
        assertEquals(0, db.feeds().list(scopeKey).size)
        assertEquals("收藏仍保留", 1, db.articles().listStarredWithText().count { it.article.instanceKey == scopeKey })
    }

    @Test fun feed_cap_200_rejects_with_clear_error() = runBlocking {
        val rows = (1..200).map { i ->
            dev.local.catleaf.nativeapp.data.local.FeedEntity(
                scopeKey, "feed-$i", "https://src$i.example.org/rss", "源$i", "", "ok", null,
            )
        }
        db.feeds().upsertAll(rows)
        val result = store.add("https://new.example.org/rss", "", "")
        assertTrue(result.exceptionOrNull() is FeedApiError.Conflict)
        assertEquals(200, db.feeds().list(scopeKey).size)
    }

    @Test fun local_opml_import_dedupes_against_room_and_reports_per_item() = runBlocking {
        fakeResults["https://new.example.org/rss"] = Result.success(rss("新源", "g1"))
        fakeResults["https://a.example.org/rss"] = Result.success(rss("已有", "g0"))
        store.add("https://a.example.org/rss", "", "").getOrThrow()

        val opml = """
            <?xml version="1.0"?><opml version="2.0"><head><title>t</title></head><body>
            <outline type="rss" text="新源" xmlUrl="https://new.example.org/rss" />
            <outline type="rss" text="已有" xmlUrl="https://a.example.org/rss" />
            </body></opml>
        """.trimIndent().toByteArray()
        val entries = Opml.parse(opml, knownUrls = store.local().map { it.url }).getOrThrow().entries
        val report = OpmlImporter(store).import(entries)

        assertEquals(1, report.added)
        // 已有条目在解析阶段被判重（duplicateOf→不可勾选），导入时记 SkippedInvalid
        assertEquals(1, report.items.count { it.outcome is OpmlImporter.Outcome.SkippedInvalid })
        assertEquals("本机没有实例超时语义", 0, report.pending)
        assertEquals(2, db.feeds().list(scopeKey).size)
    }
}
