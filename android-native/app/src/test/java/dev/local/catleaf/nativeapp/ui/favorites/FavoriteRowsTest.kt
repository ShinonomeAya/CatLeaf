package dev.local.catleaf.nativeapp.ui.favorites

import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.StarredRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P06 收藏列表的判定规则（纯映射，无需设备）：
 * 「仅书签」要能识别出来、删源的行要标成「来源已删除」、别人的实例不能串进来。
 */
class FavoriteRowsTest {

    private fun article(
        localId: String,
        feedId: String = "feed-1",
        instanceKey: String = "inst-a",
        starredAt: Long? = 1_000,
    ) = ArticleEntity(
        localId = localId,
        instanceKey = instanceKey,
        feedId = feedId,
        guid = "g-$localId",
        link = "https://example.invalid/$localId",
        title = "标题 $localId",
        publishedAt = "2026-09-23T00:00:00Z",
        feedTitle = "示例源",
        feedUrl = "https://example.invalid/feed.xml",
        isRead = true,
        isStarred = true,
        starredAt = starredAt,
        stateUpdatedAt = 1_000,
    )

    @Test
    fun bookmark_only_rows_are_marked_not_saved() {
        val rows = favoriteRowsOf(
            rows = listOf(
                StarredRow(article("has-text"), hasText = true),
                StarredRow(article("bookmark-only"), hasText = false),
            ),
            knownFeedIds = setOf("feed-1"),
            instanceKey = "inst-a",
        )

        assertEquals("两行都应出现", 2, rows.size)
        assertEquals("有正文的行标为已保存", true, rows.first { it.localId == "has-text" }.hasText)
        assertEquals("没有正文的行必须能识别成仅书签", false, rows.first { it.localId == "bookmark-only" }.hasText)
    }

    @Test
    fun rows_with_deleted_source_are_marked_but_kept() {
        val rows = favoriteRowsOf(
            rows = listOf(
                StarredRow(article("kept", feedId = "feed-gone"), hasText = true),
                StarredRow(article("live", feedId = "feed-1"), hasText = true),
            ),
            knownFeedIds = setOf("feed-1"),
            instanceKey = "inst-a",
        )

        assertEquals("删源后收藏必须还在", 2, rows.size)
        assertEquals(
            "来源已不在本地镜像里的行要标出来",
            true,
            rows.first { it.localId == "kept" }.sourceDeleted,
        )
        assertEquals(
            "来源还在的行不标记",
            false,
            rows.first { it.localId == "live" }.sourceDeleted,
        )
        assertEquals("删源行仍要保留来源名快照", "示例源", rows.first { it.localId == "kept" }.feedTitle)
    }

    @Test
    fun other_instances_are_filtered_out() {
        val rows = favoriteRowsOf(
            rows = listOf(
                StarredRow(article("mine", instanceKey = "inst-a"), hasText = true),
                StarredRow(article("other", instanceKey = "inst-b"), hasText = true),
            ),
            knownFeedIds = setOf("feed-1"),
            instanceKey = "inst-a",
        )

        assertEquals("只显示当前实例的收藏", listOf("mine"), rows.map { it.localId })
    }

    @Test
    fun feed_mirror_being_empty_does_not_hide_favorites() {
        // 离线冷启动时源镜像可能还没同步；此时收藏照常列出，只是会被标成来源已删除
        val rows = favoriteRowsOf(
            rows = listOf(StarredRow(article("only"), hasText = false)),
            knownFeedIds = emptySet(),
            instanceKey = "inst-a",
        )

        assertEquals("收藏不因镜像为空而消失", 1, rows.size)
        assertEquals(true, rows.single().sourceDeleted)
    }
}