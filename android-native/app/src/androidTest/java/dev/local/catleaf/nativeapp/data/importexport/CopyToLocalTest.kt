package dev.local.catleaf.nativeapp.data.importexport

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.importexport.CopyToLocal.Budget
import dev.local.catleaf.nativeapp.data.importexport.CopyToLocal.SourceArticle
import dev.local.catleaf.nativeapp.data.importexport.CopyToLocal.TargetFeed
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L05/C08（契约 8.4）：复制计划与单事务执行——OR 合并、目标正文不覆盖、
 * 新建目标 localId 幂等、预算/数量预检拒绝、失败回滚。
 */
@RunWith(AndroidJUnit4::class)
class CopyToLocalTest {

    private lateinit var db: CatLeafDatabase
    private val src = "src-instance"

    private fun art(feedId: String, guid: String, read: Boolean = false, starred: Boolean = false,
                    content: String? = null, feedUrl: String = "https://$feedId.example.org/rss",
                    localId: String = "article-$feedId-$guid") = SourceArticle(
        localId, feedId, guid, "https://s.example.org/$guid", "标题 $guid", "源 $feedId",
        feedUrl, read, starred, if (starred) 7L else null,
        content, "rss", null,
    )

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
    }

    @After fun tearDown() = runBlocking { db.close() }

    @Test fun plan_maps_by_url_and_or_merges_state() {
        val plan = CopyToLocal.plan(
            sourceFeeds = listOf(TargetFeed("f1", "https://FEED.example.org/rss", "源一", "组")),
            sourceArticles = listOf(art("f1", "g1", read = true, starred = true, content = "正文A")),
            targetFeeds = listOf(TargetFeed("https://feed.example.org/rss", "https://feed.example.org/rss", "已存在", "")),
            targetArticles = listOf(art("t1", "g1", read = false, starred = false,
                feedUrl = "https://feed.example.org/rss", localId = "target-article-id")),
            selectedSourceFeedIds = setOf("f1"), includeOrphanedStarred = false,
            budget = Budget(200, 1, 100L * 1024 * 1024),
        ).getOrThrow()
        assertTrue("URL 规范化后已存在：不新建源", plan.newFeeds.isEmpty())
        assertEquals("目标文章已存在：不重复插入", 0, plan.newArticleCount)
        assertEquals("已读/收藏 OR 合并：一条状态更新", 1, plan.mergedCount)
        assertTrue(plan.stateMerges.single().isRead && plan.stateMerges.single().isStarred)
        assertEquals("target-article-id", plan.stateMerges.single().localId)
        assertEquals("目标已有收藏时间(null)→沿源(7)", 7L, plan.stateMerges.single().starredAt)
    }

    @Test fun plan_inserts_new_articles_with_hash_ids_and_counts_bookmarks() {
        val plan = CopyToLocal.plan(
            sourceFeeds = listOf(TargetFeed("f1", "https://feed.example.org/rss", "源一", "组")),
            sourceArticles = listOf(
                art("f1", "g1", content = "正文A"),
                art("f1", "g2", content = null),
            ),
            targetFeeds = emptyList(), targetArticles = emptyList(),
            selectedSourceFeedIds = setOf("f1"), includeOrphanedStarred = false,
            budget = Budget(200, 0, 100L * 1024 * 1024),
        ).getOrThrow()
        assertEquals(1, plan.newFeeds.size)
        assertEquals("一篇有正文一篇仅书签", 2, plan.newArticleCount)
        assertEquals(1, plan.bookmarkOnly)
        assertEquals("正文字节计入预算（正文A=UTF-8 7 字节）", 7L, plan.contentBytes)
        val ids = plan.articlesToInsert.map { it.localId }
        assertEquals("localId 现算且稳定（幂等键）", 2, ids.toSet().size)
    }

    @Test fun plan_rejects_when_budget_or_feed_cap_exceeded() {
        val overBytes = CopyToLocal.plan(
            listOf(TargetFeed("f1", "https://f.example.org/rss", "F", "")),
            listOf(art("f1", "g1", content = "x".repeat(100))),
            emptyList(), emptyList(), setOf("f1"), false,
            Budget(200, 0, remainingBytes = 50),
        )
        assertTrue("字节超预算：拒绝并提供缩小选择，不静默少复制", overBytes.isFailure)

        val overFeeds = CopyToLocal.plan(
            listOf(TargetFeed("f1", "https://f.example.org/rss", "F", "")),
            emptyList(), emptyList(), emptyList(), setOf("f1"), false,
            Budget(200, currentFeeds = 200, remainingBytes = 1000),
        )
        assertTrue("超出 200 源上限：拒绝", overFeeds.isFailure)

        val existingWithoutBody = CopyToLocal.plan(
            listOf(TargetFeed("f1", "https://f.example.org/rss", "F", "")),
            listOf(art("f1", "g1", content = "正文A", feedUrl = "https://f.example.org/rss")),
            listOf(TargetFeed("existing", "https://f.example.org/rss", "F", "")),
            listOf(art("existing", "g1", feedUrl = "https://f.example.org/rss", localId = "target")),
            setOf("f1"), false, Budget(200, 1, remainingBytes = 1),
        )
        assertTrue("补入已有文章缺失正文仍须计入预算", existingWithoutBody.isFailure)
    }

    @Test fun execute_is_idempotent_and_failure_keeps_old_content() = runBlocking {
        val plan = CopyToLocal.plan(
            listOf(TargetFeed("f1", "https://feed.example.org/rss", "源一", "组")),
            listOf(art("f1", "g1", content = "正文A")),
            emptyList(), emptyList(), setOf("f1"), false,
            Budget(200, 0, 100L * 1024 * 1024),
        ).getOrThrow()

        val first = CopyToLocal.execute(db, "local:v1", plan).getOrThrow()
        assertEquals(Triple(1, 1, 0), first)
        val copied = db.articles().listByInstance("local:v1").single()
        assertEquals("正文A", db.articles().getContent(copied.localId)?.content)

        // 幂等：重复复制 0 新增（计划阶段目标已有 → 全部走合并/跳过）
        val plan2 = CopyToLocal.plan(
            listOf(TargetFeed("f1", "https://feed.example.org/rss", "源一", "组")),
            listOf(art("f1", "g1", content = "正文A")),
            db.feeds().list("local:v1").map { TargetFeed(it.feedId, it.url, it.title, it.category) },
            db.articles().listByInstance("local:v1").map { CopyToLocal.SourceArticle(
                it.localId, it.feedId, it.guid, it.link, it.title, it.feedTitle, it.feedUrl,
                it.isRead, it.isStarred, it.starredAt,
                db.articles().getContent(it.localId)?.content, "rss", null,
            ) },
            setOf("f1"), false,
            Budget(200, 1, 100L * 1024 * 1024),
        ).getOrThrow()
        val second = CopyToLocal.execute(db, "local:v1", plan2).getOrThrow()
        assertEquals(0, second.second)
        assertEquals("仍是 1 篇", 1, db.articles().listByInstance("local:v1").size)
        assertEquals("目标正文未被来源覆盖", "正文A", db.articles().getContent(copied.localId)?.content)

        db.articles().deleteContent(listOf(copied.localId))

        val merge = CopyToLocal.plan(
            listOf(TargetFeed("f1", "https://feed.example.org/rss", "源一", "组")),
            listOf(art("f1", "g1", read = true, starred = true, content = "正文A",
                feedUrl = "https://feed.example.org/rss")),
            db.feeds().list("local:v1").map { TargetFeed(it.feedId, it.url, it.title, it.category) },
            listOf(art(copied.feedId, "g1", feedUrl = copied.feedUrl, localId = copied.localId)),
            setOf("f1"), false, Budget(200, 1, 100L * 1024 * 1024),
        ).getOrThrow()
        assertEquals(copied.localId, merge.stateMerges.single().localId)
        assertEquals(copied.localId, merge.contentUpdates.single().localId)
        CopyToLocal.execute(db, "local:v1", merge).getOrThrow()
        assertTrue(db.articles().get(copied.localId)!!.isRead)
        assertTrue(db.articles().get(copied.localId)!!.isStarred)
        assertEquals("正文A", db.articles().getContent(copied.localId)?.content)
    }

    @Test fun sqlite_full_during_copy_rolls_back_new_rows_and_keeps_old_favorite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "isolated-full-${java.util.UUID.randomUUID()}.db"
        val quotaDb = Room.databaseBuilder(context, CatLeafDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            val original = CopyToLocal.plan(
                listOf(TargetFeed("f1", "https://feed.example.org/rss", "保留源", "保留分组")),
                listOf(art("f1", "old", read = true, starred = true, content = "保留正文")),
                emptyList(), emptyList(), setOf("f1"), false, Budget(200, 0, 10_000_000),
            ).getOrThrow()
            CopyToLocal.execute(quotaDb, "local:v1", original).getOrThrow()
            val before = quotaDb.articles().listByInstance("local:v1").single()
            val incoming = CopyToLocal.plan(
                listOf(TargetFeed("f2", "https://other.example.org/rss", "新源", "")),
                listOf(art("f2", "large", content = "x".repeat(2 * 1024 * 1024))),
                emptyList(), emptyList(), setOf("f2"), false, Budget(200, 1, 10_000_000),
            ).getOrThrow()
            var writeFailure: Throwable? = null
            runCatching {
                quotaDb.withTransaction {
                    val sql = quotaDb.openHelper.writableDatabase
                    val pages = sql.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) }
                    val limit = sql.query("PRAGMA max_page_count = ${pages + 2}").use { it.moveToFirst(); it.getLong(0) }
                    assertEquals(pages + 2, limit)
                    val copied = CopyToLocal.execute(quotaDb, "local:v1", incoming)
                    writeFailure = copied.exceptionOrNull()
                    copied.getOrThrow()
                }
            }
            // SQLITE_FULL aborts SQLite's outer transaction too; retain the original write error
            // before Android's endTransaction can report "no transaction is active".
            assertTrue("fault must be real SQLITE_FULL: $writeFailure", writeFailure is android.database.sqlite.SQLiteFullException)
            assertEquals(listOf(before), quotaDb.articles().listByInstance("local:v1"))
            assertEquals("保留正文", quotaDb.articles().getContent(before.localId)?.content)
            assertEquals(listOf("保留源"), quotaDb.feeds().list("local:v1").map { it.title })
        } finally {
            quotaDb.close()
            context.deleteDatabase(name)
        }
    }
}
