package dev.local.catleaf.nativeapp.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.article.CacheBudget
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
 * T18 执行层（契约 4.3 / B02 / B03）：用真实 Room 验证
 * 「淘汰删什么、留什么」——小预算注入复现淘汰；身份/状态/收藏/受保护译文永不进删除集；
 * 当前正文避开；取消收藏后回普通预算。
 */
@RunWith(AndroidJUnit4::class)
class CacheCleanupTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dbName: String
    private lateinit var db: CatLeafDatabase
    private lateinit var cleanup: CacheCleanup

    @Before
    fun setUp() {
        dbName = "cache-test-${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, CatLeafDatabase::class.java, dbName).build()
        cleanup = CacheCleanup(db, context)
        CurrentBody.localId = null
    }

    @After
    fun tearDown() {
        CurrentBody.localId = null
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun article(id: String, starred: Boolean = false, read: Boolean = false) = ArticleEntity(
        localId = id,
        instanceKey = "inst-a",
        feedId = "feed-1",
        guid = "guid-$id",
        link = "https://a.example.org/$id",
        title = "标题 $id",
        publishedAt = "2026-09-22T01:00:00Z",
        feedTitle = "示例源",
        feedUrl = "https://a.example.org/feed.xml",
        isRead = read,
        isStarred = starred,
        starredAt = if (starred) 5L else null,
        stateUpdatedAt = 1L,
    )

    private fun content(id: String, bytes: Int, accessed: Long) = ArticleContentEntity(
        localId = id,
        content = "字".repeat(bytes / 3).ifEmpty { null }, // 每个汉字 3 字节，凑出可控 UTF-8 长度
        summary = null,
        thumbnail = null,
        contentSource = "rss",
        contentHash = "hash-$id",
        lastAccessedAt = accessed,
    )

    private fun aiRow(key: String, task: String, bytes: Int, created: Long) = AiCacheEntity(
        cacheKey = key,
        instanceKey = "inst-a",
        task = task,
        endpoint = "https://api.example.com",
        model = "model-a",
        contentHash = "h",
        language = "zh-Hans",
        promptVersion = "v1",
        result = "字".repeat(bytes / 3),
        complete = true,
        createdAt = created,
    )

    /** 与 Translator.cacheKey 同形：实例|localId|hash|baseURL|模型|语言|translation|v1 */
    private fun translationKey(localId: String) = "inst-a|$localId|h|https://api.example.com|model-a|zh-Hans|translation|v1"

    private fun seedInstanceAndFeed() = runBlocking {
        db.instances().upsert(InstanceEntity("inst-a", "https://inst.example", "实例", 1L))
        db.feeds().upsert(FeedEntity("inst-a", "feed-1", "https://a.example.org/feed.xml", "示例源", "", "ok", 1L))
    }

    @Test
    fun article_cap_evicts_oldest_normal_and_keeps_identity_and_state() = runBlocking {
        seedInstanceAndFeed()
        db.articles().merge(article("g:old", read = true), content("g:old", 30, accessed = 1))
        db.articles().merge(article("g:mid"), content("g:mid", 30, accessed = 2))
        db.articles().merge(article("g:new"), content("g:new", 30, accessed = 3))
        db.articles().merge(article("g:starred", starred = true), content("g:starred", 30, accessed = 0))

        val plan = cleanup.enforceBudget(maxArticles = 2, maxBytes = Long.MAX_VALUE)

        assertEquals("3 篇普通超上限 1：只清最旧的", listOf("g:old"), plan.contentLocalIds)
        assertNull("最旧的正文没了", db.articles().getContent("g:old"))
        assertNotNull("第二旧的仍在预算内", db.articles().getContent("g:mid"))
        assertNotNull("最新普通快照保留", db.articles().getContent("g:new"))
        assertNotNull("收藏正文受保护", db.articles().getContent("g:starred"))
        // 只删快照：身份与状态原样（B02「旧收藏/状态不丢」）
        val gone = db.articles().get("g:old")
        assertNotNull("身份行必须还在（只清正文）", gone)
        assertTrue("已读状态不因清理丢失", gone!!.isRead)
        assertNotNull("订阅与实例行不受影响", db.feeds().list("inst-a").firstOrNull())
    }

    @Test
    fun current_body_survives_even_when_oldest() = runBlocking {
        db.articles().merge(article("g:read-me", read = true), content("g:read-me", 30, accessed = 1))
        db.articles().merge(article("g:other"), content("g:other", 30, accessed = 2))
        CurrentBody.localId = "g:read-me"

        val plan = cleanup.enforceBudget(maxArticles = 1, maxBytes = Long.MAX_VALUE)

        assertEquals("当前正文即便最旧也不删", listOf("g:other"), plan.contentLocalIds)
        assertNotNull(db.articles().getContent("g:read-me"))
    }

    @Test
    fun byte_budget_treats_digest_as_normal_and_protects_starred_translation() = runBlocking {
        db.articles().merge(article("g:normal"), content("g:normal", 120, accessed = 100))
        db.articles().merge(article("g:starred", starred = true), content("g:starred", 120, accessed = 0))
        // 普通字节 = g:normal(120·≈123) + digest(120·≈123) > 200；译文受保护不计入也不被删
        db.aiCache().insert(aiRow("inst-a|feed-1|2026-09-22|UTC|fp|https://api.example.com|model-a|zh-Hans|digest|v1", task = "digest", bytes = 120, created = 50))
        db.aiCache().insert(aiRow(translationKey("g:starred"), task = "translation", bytes = 90, created = 1))

        val plan = cleanup.enforceBudget(maxArticles = 100, maxBytes = 200)

        assertEquals("最旧的普通 AI 结果（日摘要）进删除集", 1, plan.aiCacheKeys.size)
        assertTrue("受保护的收藏译文不删", plan.aiCacheKeys.none { it.contains("translation") })
        assertEquals("收藏正文不进删除集", emptyList<String>(), plan.contentLocalIds)
        assertEquals("普通 AI 只剩受保护译文", 1, db.aiCache().resultStats().size)
        assertNull("日摘要已删", db.aiCache().get(plan.aiCacheKeys.single()))
    }

    @Test
    fun clear_normal_cache_scope_matches_confirmation_and_differs_from_reset() = runBlocking {
        seedInstanceAndFeed()
        db.articles().merge(article("g:starred", starred = true, read = true), content("g:starred", 30, accessed = 0))
        db.articles().merge(article("g:normal", read = true), content("g:normal", 30, accessed = 0))
        db.aiCache().insert(aiRow(translationKey("g:starred"), task = "translation", bytes = 30, created = 0))
        db.aiCache().insert(aiRow(translationKey("g:normal"), task = "translation", bytes = 30, created = 0))
        db.aiCache().insert(aiRow("digest-key", task = "digest", bytes = 30, created = 0))
        CurrentBody.localId = "g:normal" // 正在读的普通文章也不清（P07 确认框列了这条）

        val cleared = cleanup.clearNormalCache()

        assertEquals("清 2 项：非收藏译文 + 日摘要（当前正文与其译文、收藏侧受保护）", 2, cleared)
        assertNotNull("收藏正文保留", db.articles().getContent("g:starred"))
        assertNotNull("收藏译文保留", db.aiCache().get(translationKey("g:starred")))
        assertNotNull("当前打开的普通正文保留", db.articles().getContent("g:normal"))
        assertNull("未收藏且非当前的译文被清", db.aiCache().get(translationKey("g:normal")))
        assertNull("日摘要属普通缓存被清", db.aiCache().get("digest-key"))
        // 与「重置此设备数据」范围不同：订阅、实例、状态、身份都还在
        assertEquals("订阅列表不受影响", 1, db.feeds().list("inst-a").size)
        assertNotNull("实例不受影响", db.instances().get("inst-a"))
        assertTrue("已读状态不受影响", db.articles().get("g:normal")!!.isRead)
        assertTrue("收藏标记不受影响", db.articles().get("g:starred")!!.isStarred)
    }

    @Test
    fun touch_on_read_protects_from_eviction() = runBlocking {
        db.articles().merge(article("g:read"), content("g:read", 30, accessed = 1))
        db.articles().merge(article("g:never"), content("g:never", 30, accessed = 1))

        db.articles().touchContent("g:read", 999)
        val plan = cleanup.enforceBudget(maxArticles = 1, maxBytes = Long.MAX_VALUE)

        assertEquals("刚读过的不算最旧", listOf("g:never"), plan.contentLocalIds)
        assertNotNull(db.articles().getContent("g:read"))
    }

    @Test
    fun stats_split_normal_starred_and_estimate_the_database() = runBlocking {
        db.articles().merge(article("g:starred", starred = true), content("g:starred", 90, accessed = 0))
        db.articles().merge(article("g:normal"), content("g:normal", 60, accessed = 0))
        db.aiCache().insert(aiRow("digest-key", task = "digest", bytes = 30, created = 0))

        val stats = cleanup.stats()

        assertEquals("普通快照计数只数未收藏", 1, stats.normalArticles)
        assertTrue("普通正文≈60+ 字节，实得 ${stats.normalBytes}", stats.normalBytes in 60..70)
        assertTrue("收藏文本≈90+ 字节，实得 ${stats.starredTextBytes}", stats.starredTextBytes in 90..100)
        assertTrue("日摘要计入普通 AI", stats.normalAiBytes >= 30)
        assertEquals("无受保护译文", 0, stats.protectedAiBytes)
        assertTrue("库文件估算 > 0", stats.databaseBytes > 0)
    }

    @Test
    fun production_defaults_noop_on_small_data() = runBlocking {
        db.articles().merge(article("g:a"), content("g:a", 30, accessed = 1))
        val plan = cleanup.enforceBudget()
        assertTrue("小数据在生产默认值（${CacheBudget.MAX_ARTICLES} 篇/100MiB）下零删除", plan.isEmpty)
    }
}
