package dev.local.catleaf.nativeapp.data.article

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T13 验收②与③的数据层：收藏的写入语义、收藏列表的文本标记、删源保留收藏。
 *
 * 界面只在“受影响行数 > 0”时才显示成功，所以这里把 DAO 的返回值也钉住：
 * 文章已不在库里时必须返回 0，否则用户会看到“已收藏”但其实什么都没写。
 */
@RunWith(AndroidJUnit4::class)
class FavoriteStateTest {

    private lateinit var db: CatLeafDatabase
    private val instance = "inst-a"
    private val feed = "feed-1"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedFeed(feedId: String = feed, title: String = "示例源") {
        db.feeds().upsert(
            FeedEntity(
                instanceKey = instance,
                feedId = feedId,
                url = "https://example.invalid/$feedId.xml",
                title = title,
                category = "",
                status = "ok",
                lastSuccessAt = null,
            ),
        )
    }

    private suspend fun seedArticle(
        localId: String,
        content: String?,
        summary: String? = null,
        contentSource: String = ArticleMerger.SOURCE_RSS,
        starred: Boolean = false,
        starredAt: Long? = null,
        feedId: String = feed,
    ) {
        db.articles().merge(
            ArticleEntity(
                localId = localId,
                instanceKey = instance,
                feedId = feedId,
                guid = "g-$localId",
                link = "https://example.invalid/$localId",
                title = "标题 $localId",
                publishedAt = "2026-09-23T00:00:00Z",
                feedTitle = "示例源",
                feedUrl = "https://example.invalid/$feedId.xml",
                isStarred = starred,
                starredAt = starredAt,
                stateUpdatedAt = 1_000,
            ),
            ArticleContentEntity(
                localId = localId,
                content = content,
                summary = summary,
                thumbnail = null,
                contentSource = contentSource,
                contentHash = "hash-$localId",
                lastAccessedAt = 1_000,
            ),
        )
    }

    @Test
    fun starred_list_orders_by_time_and_marks_saved_text_key() = runBlocking {
        seedFeed()
        seedArticle("older", content = "<p>正文</p>", starred = true, starredAt = 100)
        seedArticle("newer", content = "<p>正文</p>", starred = true, starredAt = 300)
        seedArticle("summary-only", content = null, summary = "只有摘要", starred = true, starredAt = 200)
        seedArticle("not-starred", content = "<p>正文</p>", starred = false)

        val rows = db.articles().listStarredWithText()

        assertEquals("按收藏时间倒序", listOf("newer", "summary-only", "older"), rows.map { it.article.localId })
        assertTrue("有正文的行标为已保存", rows.first { it.article.localId == "newer" }.hasText)
        assertFalse(
            "只有摘要、没有正文时不得说成“文本已保存”",
            rows.first { it.article.localId == "summary-only" }.hasText,
        )
    }

    @Test
    fun write_reports_zero_rows_when_article_is_gone() = runBlocking {
        seedFeed()
        seedArticle("a1", content = "<p>正文</p>")

        assertEquals("正常写入应影响 1 行", 1, db.articles().setStarred("a1", true, 10, 10))
        assertEquals("取消收藏同样应影响 1 行", 1, db.articles().setStarred("a1", false, null, 20))
        assertEquals("设为未读应影响 1 行", 1, db.articles().setRead("a1", false, 30))

        assertEquals("文章不存在时收藏必须报 0 行（界面据此不显示成功）", 0, db.articles().setStarred("gone", true, 40, 40))
        assertEquals("文章不存在时改已读也报 0 行", 0, db.articles().setRead("gone", false, 40))
    }

    @Test
    fun manual_unread_is_not_overwritten_by_the_next_refresh() = runBlocking {
        seedFeed()
        val first = ArticleMerger.NewArticle(
            title = "第一篇",
            guid = "g1",
            link = "https://example.invalid/1",
            publishedAt = "2026-09-23T00:00:00Z",
            content = "<p>旧正文</p>",
        )
        ArticleMerger(db.articles()).mergeRefresh(instance, feed, "示例源", "https://example.invalid/feed.xml", listOf(first))
        val localId = db.articles().findByGuid(instance, feed, "g1")!!.localId
        db.articles().setRead(localId, isRead = true, now = 100)

        // 用户手动设回未读
        db.articles().setRead(localId, isRead = false, now = 200)
        assertFalse("手动设为未读应立即生效", db.articles().get(localId)!!.isRead)

        // 之后这个源再刷新一次（正文更新），用户状态不能被刷掉
        ArticleMerger(db.articles()).mergeRefresh(
            instance,
            feed,
            "示例源",
            "https://example.invalid/feed.xml",
            listOf(first.copy(content = "<p>新正文</p>")),
        )

        val after = db.articles().get(localId)!!
        assertFalse("刷新不得把“手动未读”改回已读", after.isRead)
        assertEquals("正文仍应更新", "<p>新正文</p>", db.articles().getContent(localId)!!.content)
    }

    @Test
    fun deleting_a_feed_keeps_favorites_and_their_text() = runBlocking {
        seedFeed()
        seedArticle("kept", content = "<p>正文</p>", starred = true, starredAt = 100)

        db.feeds().delete(instance, feed)
        db.feeds().deleteMissing(instance, keepIds = emptyList())

        val kept = db.articles().get("kept")
        assertNotNull("删源后文章身份必须保留", kept)
        assertTrue("删源后收藏必须保留", kept!!.isStarred)
        assertEquals("来源身份快照保留（删源后仍能说明它来自哪）", "https://example.invalid/$feed.xml", kept.feedUrl)
        assertEquals("已保存正文保留", "<p>正文</p>", db.articles().getContent("kept")!!.content)
        assertEquals("收藏列表里仍然有它", 1, db.articles().listStarredWithText().size)
        assertEquals("本地源镜像里已经没有它了", 0, db.feeds().list(instance).size)
    }
}