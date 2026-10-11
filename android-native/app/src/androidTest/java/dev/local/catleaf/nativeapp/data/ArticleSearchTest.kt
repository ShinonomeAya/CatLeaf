package dev.local.catleaf.nativeapp.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.article.ArticleFilter
import dev.local.catleaf.nativeapp.data.article.ArticleIdentity
import dev.local.catleaf.nativeapp.data.article.SearchQuery
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Q01（契约 9.1）：搜索 DAO——第 201 条以后可查、中文无空格子串、LIKE 转义字面匹配、
 * 筛选组合、范围隔离、分页。
 */
@RunWith(AndroidJUnit4::class)
class ArticleSearchTest {

    private lateinit var db: CatLeafDatabase
    private val scope = "search-scope"
    private val other = "search-other"

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
    }

    @After fun tearDown() = runBlocking {
        db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE instanceKey IN (?, ?)", arrayOf(scope, other))
        db.feeds().deleteAll(scope)
        db.feeds().deleteAll(other)
        db.close()
    }

    private suspend fun seed(count: Int, prefix: String = "文章", scopeKey: String = scope, feedId: String = "f1") {
        db.feeds().upsertAll(listOf(FeedEntity(scopeKey, feedId, "https://s.example.org/rss", "源", "", "ok", null)))
        val merger = dev.local.catleaf.nativeapp.data.article.ArticleMerger(db.articles())
        merger.mergeRefresh(
            instanceKey = scopeKey, feedId = feedId, feedTitle = "源", feedUrl = "https://s.example.org/rss",
            items = (1..count).map { i ->
                dev.local.catleaf.nativeapp.data.article.ArticleMerger.NewArticle(
                    title = "$prefix $i", link = "https://s.example.org/$i", guid = "$prefix-$i",
                    publishedAt = "2026-09-%02dT00:00:00Z".format((i % 28) + 1),
                    description = if (i % 2 == 0) "第 $i 篇的摘要包含关键词 Worker" else null,
                    content = null, thumbnail = null,
                )
            },
        )
    }

    @Test fun search_finds_beyond_first_200_and_pages() = runBlocking {
        seed(210)
        val pattern = SearchQuery.likePattern("文章")
        val page1 = db.articles().searchTitles(scope, pattern, null, 0, 0, 0, "all", null, null, 50, 0)
        val page5 = db.articles().searchTitles(scope, pattern, null, 0, 0, 0, "all", null, null, 50, 200)
        assertEquals("分页 50/页", 50, page1.size)
        assertEquals("第 201 条以后可查（第 5 页仍有）", 10, page5.size)
        assertEquals("总数", 210, db.articles().countSearch(scope, pattern, null, 0, 0, 0, "all", null, null))
    }

    @Test fun chinese_substring_without_space_matches() = runBlocking {
        seed(3, prefix = "Compose 发布说明")
        // 中文连续子串：无需分词
        val hits = db.articles().searchTitles(scope, SearchQuery.likePattern("发布说明"), null, 0, 0, 0, "all", null, null, 50, 0)
        assertEquals(3, hits.size)
    }

    @Test fun like_wildcards_are_literal() = runBlocking {
        seed(2)
        db.articles().upsertArticle(
            dev.local.catleaf.nativeapp.data.local.ArticleEntity(
                localId = ArticleIdentity.localIdFor(scope, "f1", "x", null)!!,
                instanceKey = scope, feedId = "f1", guid = "x", link = null,
                title = "100%通过_测试", publishedAt = null, feedTitle = "源", feedUrl = "https://s.example.org/rss",
                isRead = false, isStarred = false, starredAt = null, stateUpdatedAt = 1L,
            ),
        )
        // % 和 _ 是字面量：搜 "100%" 只命中那篇，"全通"（_ 通配场景）不命中额外
        assertEquals(1, db.articles().countSearch(scope, SearchQuery.likePattern("100%"), null, 0, 0, 0, "all", null, null))
        assertEquals(0, db.articles().countSearch(scope, SearchQuery.likePattern("通过测试"), null, 0, 0, 0, "all", null, null))
    }

    @Test fun filters_combine_scope_unread_starred_date() = runBlocking {
        seed(4, prefix = "筛选")
        val target = db.articles().listByFeed(scope, "f1", 10, 0).first()
        db.articles().setRead(target.localId, true, 1L)
        val starred = db.articles().listByFeed(scope, "f1", 10, 0).last()
        db.articles().setStarred(starred.localId, true, 1L, 1L)

        val pattern = SearchQuery.likePattern("筛选")
        assertEquals("未读筛后少 1", 3, db.articles().countSearch(scope, pattern, null, 0, 1, 0, "all", null, null))
        assertEquals("收藏筛只 1", 1, db.articles().countSearch(scope, pattern, null, 0, 0, 1, "all", null, null))
        assertEquals(
            "未读+收藏组合",
            1 - (if (starred.isRead) 1 else 0),
            db.articles().countSearch(scope, pattern, null, 0, 1, 1, "all", null, null),
        )
        assertEquals(
            "今日（发布都在 9 月，今天界外为 0——参数路径通）",
            0,
            db.articles().countSearch(scope, pattern, null, 0, 0, 0, "on", "2020-01-01T00:00:00Z", "2020-01-02T00:00:00Z"),
        )
        assertEquals("未知日期档", 0, db.articles().countSearch(scope, pattern, null, 0, 0, 0, "unknown", null, null))
    }

    @Test fun scope_isolation_old_results_stay_out() = runBlocking {
        seed(2, scopeKey = scope)
        seed(3, prefix = "其他", scopeKey = other)
        assertEquals(
            "本范围搜不到别的范围",
            0,
            db.articles().countSearch(other, SearchQuery.likePattern("文章"), null, 0, 0, 0, "all", null, null),
        )
        assertEquals(2, db.articles().countSearch(scope, SearchQuery.likePattern("文章"), null, 0, 0, 0, "all", null, null))
    }

    @Test fun search_text_generated_with_content_and_cleared_with_it() = runBlocking {
        seed(2)
        val rows = db.articles().listByFeed(scope, "f1", 10, 0)
        val withSummary = rows.first { it.guid!!.endsWith("2") } // 偶数篇有摘要
        val content = db.articles().getContent(withSummary.localId)!!
        assertTrue("searchText 随写入生成", content.searchText?.contains("Worker") == true)

        // 正文清理 → 搜索索引同步消失（契约 9.1：索引是派生缓存）
        db.articles().deleteContent(listOf(withSummary.localId))
        assertTrue(db.articles().getContent(withSummary.localId) == null)
    }

    @Test fun saved_body_requires_switch_and_matches_after_200000_chars() = runBlocking {
        db.feeds().upsertAll(listOf(FeedEntity(scope, "f1", "https://s.example.org/rss", "源", "", "ok", null)))
        val body = "<p>" + "文".repeat(200_100) + "独特尾词 &amp; 已解码</p>"
        dev.local.catleaf.nativeapp.data.article.ArticleMerger(db.articles()).mergeRefresh(
            scope, "f1", "源", "https://s.example.org/rss",
            listOf(dev.local.catleaf.nativeapp.data.article.ArticleMerger.NewArticle(
                title = "普通标题", guid = "long", link = null, publishedAt = null,
                content = body, description = "不含目标的摘要", thumbnail = null,
            )),
        )
        val term = SearchQuery.likePattern("独特尾词 & 已解码")
        assertEquals(0, db.articles().countSearch(scope, term, null, 0, 0, 0, "all", null, null))
        assertEquals(1, db.articles().countSearch(scope, term, null, 1, 0, 0, "all", null, null))
        val result = db.articles().searchTitles(scope, term, null, 1, 0, 0, "all", null, null, 50, 0).single()
        val snippet = db.articles().searchSnippets(listOf(result.localId), "独特尾词 & 已解码", 1).single()
        assertEquals("body", snippet.location)
        assertTrue(snippet.position > 200_000)
        assertTrue(snippet.snippet.contains("独特尾词 & 已解码"))
        db.articles().deleteContent(listOf(result.localId))
        assertEquals(0, db.articles().countSearch(scope, term, null, 1, 0, 0, "all", null, null))
    }

    @Test fun body_search_escapes_percent_underscore_and_backslash() = runBlocking {
        db.feeds().upsertAll(listOf(FeedEntity(scope, "f1", "https://s.example.org/rss", "源", "", "ok", null)))
        dev.local.catleaf.nativeapp.data.article.ArticleMerger(db.articles()).mergeRefresh(
            scope, "f1", "源", "https://s.example.org/rss",
            listOf(dev.local.catleaf.nativeapp.data.article.ArticleMerger.NewArticle(
                title = "占位", guid = "escape", link = null, publishedAt = null,
                content = "<p>alpha%beta_under\\path 中文连续</p>", description = null, thumbnail = null,
            )),
        )
        for (term in listOf("alpha%beta", "beta_under", "under\\path", "中文连续")) {
            assertEquals(term, 1, db.articles().countSearch(scope, SearchQuery.likePattern(term), null, 1, 0, 0, "all", null, null))
        }
        assertEquals(0, db.articles().countSearch(scope, SearchQuery.likePattern("alphaXbeta"), null, 1, 0, 0, "all", null, null))
    }
}
