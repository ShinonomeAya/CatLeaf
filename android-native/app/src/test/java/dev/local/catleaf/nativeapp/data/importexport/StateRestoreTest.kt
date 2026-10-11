package dev.local.catleaf.nativeapp.data.importexport

import dev.local.catleaf.nativeapp.data.article.ArticleIdentity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 恢复合并计划（契约 §6、验收①③的纯逻辑部分）。 */
class StateRestoreTest {

    private val instance = "inst-a"

    private fun feed(id: String, url: String) = FeedEntity(instance, id, url, "源 $id", "", "ok", 1L)

    private fun article(
        feedId: String,
        guid: String?,
        link: String? = null,
        read: Boolean = false,
        starred: Boolean = false,
    ) = ArticleEntity(
        localId = ArticleIdentity.localIdFor(instance, feedId, guid, link)!!,
        instanceKey = instance,
        feedId = feedId,
        guid = guid,
        link = link,
        title = "文章 $guid",
        publishedAt = null,
        feedTitle = "源",
        feedUrl = "https://feeds.example.org/$feedId.xml",
        isRead = read,
        isStarred = starred,
        stateUpdatedAt = 0L,
    )

    private fun doc(vararg entries: StateArticle) = StateDocument("instance", "https://backup.example", 1L, emptyList(), entries.toList(), emptyList())

    private fun entry(feedUrl: String, guid: String?, link: String? = null, read: Boolean = false, starred: Boolean = false) =
        StateArticle(feedUrl, guid, link, "标题", "备份源名", read, starred)

    @Test
    fun feeds_missing_locally_go_to_import_list_url_normalized() {
        val d = StateDocument(
            "instance", "https://old.example", 1,
            feeds = listOf(
                // 大小写/默认端口规范化后相等 → 已存在
                StateFeed("https://FEEDS.example.org:443/a.xml", "A", "组"),
                StateFeed("https://new.example/b.xml", "B", ""),
            ),
            articles = emptyList(), invalidItems = emptyList(),
        )
        val local = listOf(feed("f1", "https://feeds.example.org/a.xml"), feed("f2", "https://other.example/x.xml"))
        val toImport = StateRestore.feedsToImport(d, local)
        assertEquals("只有真缺的进待导入", listOf("https://new.example/b.xml"), toImport.map { it.xmlUrl })
        assertEquals("已存在 1 / 总 2", 1 to 2, StateRestore.feedPreview(d, local))
    }

    @Test
    fun duplicate_urls_in_a_state_file_create_one_feed() {
        val d = StateDocument(
            "instance", "https://old.example", 1,
            feeds = listOf(
                StateFeed("https://FEEDS.example.org:443/a.xml", "first", ""),
                StateFeed("https://feeds.example.org/a.xml", "second", ""),
            ),
            articles = emptyList(), invalidItems = emptyList(),
        )
        assertEquals(1, StateRestore.feedsToImport(d, emptyList()).size)
    }

    @Test
    fun trailing_slash_is_not_merged_deliberately() {
        // normalizeFeedUrl 有意不动 path（含尾斜杠）："宁可把两个其实相同的地址当成不同，
        // 也不要把不同内容合并"——恢复映射沿用同一规则，这里钉住行为
        val d = StateDocument(
            "instance", "https://old.example", 1,
            feeds = listOf(StateFeed("https://feeds.example.org/b.xml/", "B", "")),
            articles = emptyList(), invalidItems = emptyList(),
        )
        val local = listOf(feed("f1", "https://feeds.example.org/b.xml"))
        assertEquals("尾斜杠差异不视为同一源", 1, StateRestore.feedsToImport(d, local).size)
    }

    @Test
    fun union_merge_only_turns_flags_on_never_off() {
        val d = doc(
            entry("https://feeds.example.org/f1.xml", "g1", read = true),        // 本机未读 → 标已读
            entry("https://feeds.example.org/f1.xml", "g2", read = false),       // 备份 false，本机 true → 不动
            entry("https://feeds.example.org/f1.xml", "g3", starred = true),     // 本机未收藏 → 加收藏
        )
        val localArticles = listOf(
            article("f1", "g1", read = false),
            article("f1", "g2", read = true), // 本机已读
            article("f1", "g3", starred = false),
        )
        val plan = StateRestore.planLocal(d, instance, listOf(feed("f1", "https://feeds.example.org/f1.xml")), localArticles)

        assertEquals("只有 false→true 的 g1 进已读更新", listOf(article("f1", "g1", read = false).localId), plan.readUpdates)
        assertEquals("g2 不被取消", 0, plan.readUpdates.count { it == article("f1", "g2", read = true).localId })
        assertEquals("g3 加收藏", listOf(article("f1", "g3", starred = false).localId), plan.starUpdates)
        assertEquals("无书签要新建", 0, plan.bookmarks.size)
    }

    @Test
    fun starred_article_without_local_row_becomes_bookmark_read_only_is_skipped() {
        val d = doc(
            entry("https://feeds.example.org/f1.xml", "g-new", starred = true),
            entry("https://feeds.example.org/f1.xml", "g-unknown", read = true), // 只有已读、目标没有 → 不建身份行
        )
        val plan = StateRestore.planLocal(
            d, instance,
            listOf(feed("f1", "https://feeds.example.org/f1.xml")),
            listOf(article("f1", "g1")),
        )
        assertEquals("收藏建书签", 1, plan.bookmarks.size)
        assertEquals("书签 localId 用真实 feedId 现算", ArticleIdentity.localIdFor(instance, "f1", "g-new", null), plan.bookmarks.single().localId)
        assertEquals("只读的未知条目跳过", 1, plan.skippedUnknown)
    }

    @Test
    fun failed_feed_still_restores_favorites_as_orphan_bookmarks() {
        val d = doc(entry("https://gone.example/feed.xml", "g-orphan", link = "https://gone.example/post", starred = true))
        val plan = StateRestore.planLocal(d, instance, localFeeds = emptyList(), localArticles = emptyList())
        assertEquals("映射不上源也按孤立书签恢复", 1, plan.orphanBookmarks)
        assertEquals("孤立书签 feedId 用空串", ArticleIdentity.localIdFor(instance, StateRestore.ORPHAN_FEED_ID, "g-orphan", "https://gone.example/post"), plan.bookmarks.single().localId)
        assertEquals("保留链接与标题", "https://gone.example/post", plan.bookmarks.single().link)
    }

    @Test
    fun second_restore_is_a_noop_idempotent() {
        val d = doc(
            entry("https://feeds.example.org/f1.xml", "g1", read = true),
            entry("https://feeds.example.org/f1.xml", "g2", starred = true),
        )
        val feeds = listOf(feed("f1", "https://feeds.example.org/f1.xml"))
        // 第一次：g1 未读、g2 行不存在
        val first = StateRestore.planLocal(d, instance, feeds, listOf(article("f1", "g1")))
        assertEquals("第一次有 2 处写入", 2, first.writes)

        // 应用第一次后的状态再恢复（模拟第二次）：g1 已读、g2 书签已存在
        val after = listOf(
            article("f1", "g1", read = true),
            article("f1", "g2", starred = true),
        )
        val second = StateRestore.planLocal(d, instance, feeds, after)
        assertTrue("第二次恢复不再重复写入", second.isEmpty)
    }

    @Test
    fun orphan_bookmark_does_not_duplicate_after_feed_imported_later() {
        val d = doc(entry("https://feeds.example.org/f1.xml", "g1", starred = true))
        // 上次留了孤立书签（feedId=""）；这次源导入成功 → 按 guid 找到旧行加星标，而不是插第二条
        val orphan = ArticleEntity(
            localId = ArticleIdentity.localIdFor(instance, StateRestore.ORPHAN_FEED_ID, "g1", null)!!,
            instanceKey = instance,
            feedId = StateRestore.ORPHAN_FEED_ID,
            guid = "g1",
            link = null,
            title = "旧书签",
            publishedAt = null,
            feedTitle = "旧源",
            feedUrl = "https://feeds.example.org/f1.xml",
            isStarred = true,
            stateUpdatedAt = 0L,
        )
        val feeds = listOf(feed("f1", "https://feeds.example.org/f1.xml"))
        val plan = StateRestore.planLocal(d, instance, feeds, listOf(orphan))
        assertTrue("按 guid 认到旧书签：零写入，不插第二条", plan.isEmpty)

        // 旧行后来被取消收藏（用户手动）→ 恢复按备份把它重新加星，而不是插第二条
        val unstarred = orphan.copy(isStarred = false)
        val plan2 = StateRestore.planLocal(d, instance, feeds, listOf(unstarred))
        assertEquals("改成对既有行加星标", listOf(unstarred.localId), plan2.starUpdates)
        assertEquals(0, plan2.bookmarks.size)
    }

    @Test
    fun same_guid_in_another_feed_does_not_steal_restored_bookmark() {
        val d = doc(entry("https://feeds.example.org/f2.xml", "shared-guid", starred = true))
        val other = article("f1", "shared-guid", starred = true)
        val feeds = listOf(
            feed("f1", "https://feeds.example.org/f1.xml"),
            feed("f2", "https://feeds.example.org/f2.xml"),
        )
        val plan = StateRestore.planLocal(d, instance, feeds, listOf(other))
        assertEquals(1, plan.bookmarks.size)
        assertEquals("f2", plan.bookmarks.single().feedId)
    }

    @Test
    fun localId_is_recomputed_never_reused_from_backup() {
        // 备份里根本没有 localId 字段可复用；这里钉住"现算"：跨实例迁移时
        // 同一 (instanceKey, feedId, guid) 之外的一切旧 id 都不参与
        val entry = article("f9", "gx", starred = false)
        val d = doc(StateArticle("https://feeds.example.org/f9.xml", "gx", null, "t", "f", false, true))
        val plan = StateRestore.planLocal(d, instance, listOf(feed("f9", "https://feeds.example.org/f9.xml")), listOf(entry))
        assertEquals("命中的是现算 localId 的行", listOf(entry.localId), plan.starUpdates)
    }
}
