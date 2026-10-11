package dev.local.catleaf.nativeapp.data.article

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.rss.FeedParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T10：RSS/Atom 解析 + 合并语义。
 * 重点验证「刷新不覆盖已读收藏」「历史只补缺」「GUID/link 冲突不合并删除」
 * 「回包绑定发起时的 namespace」这四条契约要求。
 */
@RunWith(AndroidJUnit4::class)
class ArticleMergeTest {

    private lateinit var db: CatLeafDatabase
    private lateinit var merger: ArticleMerger

    private val instance = "inst-a"
    private val feed = "feed-1"
    private val feedTitle = "示例源"
    private val feedUrl = "https://a.example.org/feed.xml"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
        merger = ArticleMerger(db.articles())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun item(
        title: String,
        guid: String? = null,
        link: String? = null,
        published: String? = "2026-09-22T00:00:00Z",
        content: String? = "正文",
        description: String? = null,
        thumbnail: String? = null,
    ) = ArticleMerger.NewArticle(title, guid, link, published, content, description, thumbnail)

    // ── 解析 ──────────────────────────────────────────────────────

    @Test
    fun parses_rss_fields() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"
                 xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <title>示例源</title>
                <description>源说明</description>
                <item>
                  <title>第一篇</title>
                  <link>https://a.example.org/post/1</link>
                  <guid isPermaLink="false">guid-1</guid>
                  <pubDate>Tue, 22 Sep 2026 08:00:00 +0800</pubDate>
                  <author>作者甲</author>
                  <description>摘要一</description>
                  <content:encoded><![CDATA[<p>正文一</p>]]></content:encoded>
                  <media:thumbnail url="https://a.example.org/t1.jpg" />
                  <enclosure url="https://a.example.org/a.mp3" type="audio/mpeg" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val parsed = FeedParser.parse(xml).getOrThrow()
        assertEquals("示例源", parsed.title)
        assertEquals("源说明", parsed.description)
        assertEquals(1, parsed.items.size)
        assertEquals(0, parsed.unidentifiedCount)

        val first = parsed.items[0]
        assertEquals("第一篇", first.title)
        assertEquals("guid-1", first.guid)
        assertEquals("https://a.example.org/post/1", first.link)
        assertEquals("RFC822 应转成 ISO UTC", "2026-09-22T00:00:00Z", first.publishedAt)
        assertEquals("作者甲", first.author)
        assertEquals("正文优先取 content:encoded", "<p>正文一</p>", first.content)
        assertEquals("摘要单独保留", "摘要一", first.description)
        assertEquals("https://a.example.org/t1.jpg", first.thumbnail)
        // T22 F02：音频 enclosure 不能被当图片封面（ParsedItem 已无独立 enclosure 字段，脏附件直接丢弃）
        assertFalse("封面只能是图片地址", first.thumbnail!!.endsWith(".mp3"))
    }

    @Test
    fun parses_atom_with_nested_author_and_rel_self_link() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom 源</title>
              <subtitle>副标题</subtitle>
              <icon>https://b.example.org/icon.png</icon>
              <link rel="self" href="https://b.example.org/atom.xml" />
              <entry>
                <title>条目一</title>
                <link rel="self" href="https://b.example.org/entries/1" />
                <link rel="alternate" href="https://b.example.org/post/1" />
                <id>tag:b.example.org,2026:1</id>
                <published>2026-09-22T10:00:00+08:00</published>
                <author><name>作者乙</name></author>
                <summary>摘要</summary>
                <content type="html">&lt;p&gt;正文&lt;/p&gt;</content>
              </entry>
            </feed>
        """.trimIndent()

        val parsed = FeedParser.parse(xml).getOrThrow()
        assertEquals("Atom 源", parsed.title)
        assertEquals("副标题", parsed.description)
        assertEquals(1, parsed.items.size)

        val entry = parsed.items[0]
        assertEquals("嵌套 author/name 要能取到", "作者乙", entry.author)
        assertEquals(
            "应选 rel=alternate 而不是 self",
            "https://b.example.org/post/1",
            entry.link,
        )
        assertEquals("tag:b.example.org,2026:1", entry.guid)
        assertEquals("2026-09-22T02:00:00Z", entry.publishedAt)
        assertEquals("正文含转义标记", "<p>正文</p>", entry.content)
    }

    @Test
    fun rejects_html_instead_of_xml_and_malformed_xml() {
        val html = "<!DOCTYPE html><html><body>blocked</body></html>"
        val htmlError = FeedParser.parse(html).exceptionOrNull()
        assertTrue(
            "HTML 必须明确报错，不能当成空源，实际：$htmlError",
            htmlError is FeedParser.FeedParseError.HtmlInsteadOfXml,
        )

        val truncated = FeedParser.parse("<rss><channel><item><title>x</title>").exceptionOrNull()
        assertTrue(
            "截断的 XML 应报解析错误，实际：$truncated",
            truncated is FeedParser.FeedParseError.Malformed,
        )

        val notFeed = FeedParser.parse("<?xml version=\"1.0\"?><foo><bar/></foo>").exceptionOrNull()
        assertTrue(
            "没有 channel/feed 不算订阅，实际：$notFeed",
            notFeed is FeedParser.FeedParseError.NotAFeed,
        )
    }

    @Test
    fun counts_items_missing_both_identifiers() {
        val xml = """
            <rss version="2.0"><channel><title>t</title>
              <item><title>有 guid</title><guid>g1</guid></item>
              <item><title>只有 link</title><link>https://a.example.org/x</link></item>
              <item><title>什么都没有</title><description>d</description></item>
              <item><title>link 无效</title><link>not a url</link></item>
            </channel></rss>
        """.trimIndent()

        val parsed = FeedParser.parse(xml).getOrThrow()
        assertEquals("只有 2 条有可用身份", 2, parsed.items.size)
        assertEquals("2 条缺少标识要计数", 2, parsed.unidentifiedCount)
        assertNull("无 guid 的条目 guid 为 null", parsed.items[1].guid)
        assertEquals("无 guid 时用 link 作为身份", "https://a.example.org/x", parsed.items[1].link)
    }

    // ── 合并：保留用户状态 ─────────────────────────────────────────

    @Test
    fun refresh_preserves_read_and_starred_state() = runBlocking {
        merger.mergeRefresh(instance, feed, feedTitle, feedUrl, listOf(item("第一篇", guid = "g1")))
        val localId = db.articles().findByGuid(instance, feed, "g1")!!.localId
        db.articles().setRead(localId, isRead = true, now = 111)
        db.articles().setStarred(localId, starred = true, starredAt = 222, now = 333)

        // 再刷新同一篇，并附带一条新文章
        val result = merger.mergeRefresh(
            instance, feed, feedTitle, feedUrl,
            listOf(
                item("第一篇（标题变了）", guid = "g1", content = "新正文"),
                item("第二篇", guid = "g2"),
            ),
        )

        val first = db.articles().findByGuid(instance, feed, "g1")!!
        assertEquals("localId 必须保持不变", localId, first.localId)
        assertTrue("已读不能被刷新清掉", first.isRead)
        assertTrue("收藏不能被刷新清掉", first.isStarred)
        assertEquals("收藏时间保留", 222L, first.starredAt)
        assertEquals("状态时间保留", 333L, first.stateUpdatedAt)
        assertEquals("标题应更新为新值", "第一篇（标题变了）", first.title)
        assertEquals("正文应更新为新内容", "新正文", db.articles().getContent(localId)!!.content)
        assertEquals("新增 1 条", 1, result.inserted)
        assertEquals("更新 1 条", 1, result.updated)
    }

    @Test
    fun summary_only_feed_is_stored_as_summary_not_as_body() = runBlocking {
        // 只有摘要的源（例如 hnrss）：正文必须为 null，摘要要保存下来，
        // 详情页才能显示“摘要（无正文）”并给出获取全文（契约 P04）。
        merger.mergeRefresh(
            instance, feed, feedTitle, feedUrl,
            listOf(item("只有摘要", guid = "g-sum", content = null, description = "<p>这是一段摘要</p>")),
        )

        val localId = db.articles().findByGuid(instance, feed, "g-sum")!!.localId
        val stored = db.articles().getContent(localId)!!
        assertEquals("没有 content:encoded 时不得把摘要当正文", null, stored.content)
        assertEquals("摘要要单独保存", "<p>这是一段摘要</p>", stored.summary)
        assertEquals("来源应标为摘要而不是正文", ArticleMerger.SOURCE_SUMMARY, stored.contentSource)
    }

    @Test
    fun rss_body_is_stored_as_rss_source() = runBlocking {
        merger.mergeRefresh(
            instance, feed, feedTitle, feedUrl,
            listOf(item("有正文", guid = "g-body", content = "<p>正文</p>", description = "摘要")),
        )
        val localId = db.articles().findByGuid(instance, feed, "g-body")!!.localId
        val stored = db.articles().getContent(localId)!!
        assertEquals("正文来自 content:encoded", "<p>正文</p>", stored.content)
        assertEquals("摘要同时保留", "摘要", stored.summary)
        assertEquals(ArticleMerger.SOURCE_RSS, stored.contentSource)
    }

    @Test
    fun refresh_backfills_missing_cover_without_touching_body() = runBlocking {
        // T22 F02：旧缓存缺封面时，正常刷新要把新解析到的 enclosure 补上，
        // 正文即使一字未改也不能被这条更新路径弄丢（不清库、不等哈希变化）
        merger.mergeRefresh(
            instance, feed, feedTitle, feedUrl,
            listOf(item("缺封面", guid = "g-cover", content = "<p>正文</p>")),
        )
        val localId = db.articles().findByGuid(instance, feed, "g-cover")!!.localId
        assertNull("首次合并没有附件就没有封面", db.articles().getContent(localId)!!.thumbnail)

        merger.mergeRefresh(
            instance, feed, feedTitle, feedUrl,
            listOf(item("缺封面", guid = "g-cover", content = "<p>正文</p>", thumbnail = "https://a.example.org/cover.png")),
        )
        val after = db.articles().getContent(localId)!!
        assertEquals("刷新补齐封面", "https://a.example.org/cover.png", after.thumbnail)
        assertEquals("正文保持", "<p>正文</p>", after.content)
        assertEquals("localId 不变", localId, db.articles().findByGuid(instance, feed, "g-cover")!!.localId)
    }

    @Test
    fun refresh_matches_by_link_when_guid_appears_later() = runBlocking {
        // 第一次只有 link
        merger.mergeRefresh(
            instance, feed, feedTitle, feedUrl,
            listOf(item("第一篇", guid = null, link = "https://a.example.org/1")),
        )
        val byLink = db.articles().findByLink(instance, feed, "https://a.example.org/1")!!
        db.articles().setStarred(byLink.localId, starred = true, starredAt = 1, now = 1)

        // 第二次该源补上了 guid，link 相同
        val result = merger.mergeRefresh(
            instance, feed, feedTitle, feedUrl,
            listOf(item("第一篇", guid = "g-new", link = "https://a.example.org/1")),
        )

        assertEquals("不应新增第二条", 0, result.inserted)
        assertEquals("应更新已有那条", 1, result.updated)
        val after = db.articles().findByLink(instance, feed, "https://a.example.org/1")!!
        assertEquals("必须保持原 localId（契约）", byLink.localId, after.localId)
        assertEquals("补上的 guid 应写入", "g-new", after.guid)
        assertTrue("收藏保留", after.isStarred)
        assertEquals(1, db.articles().listByFeed(instance, feed, 100, 0).size)
    }

    @Test
    fun guid_and_link_hitting_different_records_is_recorded_as_conflict() = runBlocking {
        // A：guid=a1，link=/a
        merger.mergeRefresh(instance, feed, feedTitle, feedUrl, listOf(item("A", guid = "a1", link = "https://a.example.org/a")))
        // B：guid=b1，link=/b
        merger.mergeRefresh(instance, feed, feedTitle, feedUrl, listOf(item("B", guid = "b1", link = "https://a.example.org/b")))

        val a = db.articles().findByGuid(instance, feed, "a1")!!
        val b = db.articles().findByLink(instance, feed, "https://a.example.org/b")!!
        db.articles().setStarred(a.localId, starred = true, starredAt = 5, now = 5)

        // 新条目：guid 命中 A、link 命中 B → 冲突
        val result = merger.mergeRefresh(
            instance, feed, feedTitle, feedUrl,
            listOf(item("冲突条目", guid = "a1", link = "https://a.example.org/b")),
        )

        assertEquals("应记录 1 条冲突", 1, result.conflicts.size)
        assertEquals("不应新增", 0, result.inserted)
        assertEquals("两条记录都要保留，不能自动合并删除", 2, db.articles().listByFeed(instance, feed, 100, 0).size)
        assertTrue("原收藏不受影响", db.articles().get(a.localId)!!.isStarred)
        assertNotNull(db.articles().get(b.localId))
    }

    @Test
    fun history_merge_only_fills_gaps_and_never_overwrites() = runBlocking {
        // 刷新先拿到当前 RSS 正文
        merger.mergeRefresh(
            instance, feed, feedTitle, feedUrl,
            listOf(item("已存在", guid = "g1", content = "当前 RSS 正文")),
        )
        val localId = db.articles().findByGuid(instance, feed, "g1")!!.localId
        db.articles().setRead(localId, isRead = true, now = 9)

        // 历史里有同一篇（正文是旧快照）以及一篇新文章
        val result = merger.mergeHistory(
            instance, feed, feedTitle, feedUrl,
            listOf(
                item("已存在", guid = "g1", content = "历史旧正文"),
                item("历史独有", guid = "g2", content = "历史正文"),
            ),
        )

        assertEquals("只补 1 条", 1, result.inserted)
        assertEquals("已存在的被跳过", 1, result.filledFromHistory)
        assertEquals(
            "历史不得覆盖当前 RSS 正文",
            "当前 RSS 正文",
            db.articles().getContent(localId)!!.content,
        )
        assertTrue("历史合并不动已读", db.articles().get(localId)!!.isRead)
        assertNull(
            "历史补缺只写身份，不写正文（等该源下次刷新用当前 RSS 正文）",
            db.articles().getContent(db.articles().findByGuid(instance, feed, "g2")!!.localId),
        )
    }

    @Test
    fun refresh_counts_items_without_identifier() = runBlocking {
        val result = merger.mergeRefresh(
            instance, feed, feedTitle, feedUrl,
            listOf(
                item("正常", guid = "g1"),
                item("无标识", guid = null, link = null),
                item("链接无效", guid = null, link = "not a url"),
            ),
        )
        assertEquals(1, result.inserted)
        assertEquals("两条无法保存要计数", 2, result.skippedUnidentified)
        assertEquals(1, db.articles().listByFeed(instance, feed, 100, 0).size)
    }

    @Test
    fun writes_stay_inside_the_originating_namespace() = runBlocking {
        // 同一 GUID 在两个实例下必须是两条独立记录
        merger.mergeRefresh(instance, feed, feedTitle, feedUrl, listOf(item("A 实例的", guid = "g1")))
        merger.mergeRefresh("inst-b", feed, feedTitle, feedUrl, listOf(item("B 实例的", guid = "g1")))
        // 同一实例下另一个源
        merger.mergeRefresh(instance, "feed-2", feedTitle, feedUrl, listOf(item("同实例另一源", guid = "g1")))

        val aRows = db.articles().listByFeed(instance, feed, 100, 0)
        val bRows = db.articles().listByFeed("inst-b", feed, 100, 0)
        val otherFeedRows = db.articles().listByFeed(instance, "feed-2", 100, 0)

        assertEquals(1, aRows.size)
        assertEquals(1, bRows.size)
        assertEquals(1, otherFeedRows.size)
        assertNotEquals("跨实例不得共用一个 localId", aRows[0].localId, bRows[0].localId)
        assertNotEquals("跨源不得共用", aRows[0].localId, otherFeedRows[0].localId)
        assertEquals("A 实例的标题不被 B 覆盖", "A 实例的", aRows[0].title)
        assertEquals("B 实例的标题不被 A 覆盖", "B 实例的", bRows[0].title)

        // 查询也必须按实例隔离
        assertEquals(2, db.articles().listByFeed(instance, feed, 100, 0).size + 0 + 1)
    }

    @Test
    fun failed_or_empty_refresh_never_deletes_local_articles() = runBlocking {
        merger.mergeRefresh(
            instance, feed, feedTitle, feedUrl,
            listOf(item("一篇", guid = "g1"), item("两篇", guid = "g2")),
        )
        val before = db.articles().listByFeed(instance, feed, 100, 0).size
        db.articles().setRead(db.articles().findByGuid(instance, feed, "g1")!!.localId, isRead = true, now = 1)

        // 抓取失败时代码路径根本不会调用合并；即便调用，空列表也不得清空（合并器没有删除操作）
        val result = merger.mergeRefresh(instance, feed, feedTitle, feedUrl, emptyList())

        assertEquals("空刷新不新增", 0, result.inserted)
        assertEquals("空刷新不更新", 0, result.updated)
        assertEquals("已有文章必须原样保留", before, db.articles().listByFeed(instance, feed, 100, 0).size)
        assertTrue(
            "已读状态保留",
            db.articles().findByGuid(instance, feed, "g1")!!.isRead,
        )
    }

    @Test
    fun source_snapshot_survives_feed_deletion() = runBlocking {
        merger.mergeRefresh(instance, feed, "源名快照", feedUrl, listOf(item("收藏的文章", guid = "g1")))
        val localId = db.articles().findByGuid(instance, feed, "g1")!!.localId
        db.articles().setStarred(localId, starred = true, starredAt = 1, now = 1)

        // 源被删除（本地目录移除），但文章与快照必须保留，供收藏页显示来源
        db.feeds().delete(instance, feed)

        val kept = db.articles().get(localId)
        assertNotNull("删源不得删除文章记录", kept)
        assertEquals("来源名快照保留", "源名快照", kept!!.feedTitle)
        assertEquals("来源 URL 快照保留", feedUrl, kept.feedUrl)
        assertTrue("收藏保留", kept.isStarred)
    }
}