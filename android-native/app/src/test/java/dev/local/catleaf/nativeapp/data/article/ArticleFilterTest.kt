package dev.local.catleaf.nativeapp.data.article

import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 列表筛选与计数的纯逻辑检查（JVM）。
 * 关键点：日期按**设备本地时区**判定；未知日期单独一档；未读数只数已加载条目。
 */
class ArticleFilterTest {

    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    private fun entity(localId: String, publishedAt: String?, isRead: Boolean = false) = ArticleEntity(
        localId = localId,
        instanceKey = "inst-a",
        feedId = "feed-1",
        guid = "g-$localId",
        link = "https://a.example.org/$localId",
        title = "标题 $localId",
        publishedAt = publishedAt,
        feedTitle = "源",
        feedUrl = "https://a.example.org/feed.xml",
        isRead = isRead,
        stateUpdatedAt = 0,
    )

    private fun rows(vararg pairs: Pair<String?, Boolean>): List<ArticleFilter.LoadedRow> =
        pairs.mapIndexed { i, (iso, read) ->
            ArticleFilter.toLoadedRow(entity("a$i", iso, read), zone = shanghai)
        }

    @Test
    fun all_scope_keeps_everything_loaded() {
        val loaded = rows("2026-09-22T00:00:00Z" to false, null to false)
        assertEquals(
            2,
            ArticleFilter.apply(loaded, ArticleFilter.Criteria()).size,
        )
    }

    @Test
    fun unknown_date_forms_its_own_bucket_and_never_mixes_into_a_day() {
        val loaded = rows(
            "2026-09-21T16:00:00Z" to false, // 东八区是 22 日
            null to false,
        )

        val onDate = ArticleFilter.apply(loaded, ArticleFilter.Criteria(ArticleFilter.DateScope.On("2026-09-22")))
        assertEquals("只有可解析日期的条目进该日", 1, onDate.size)
        assertEquals("a0", onDate[0].entity.localId)

        val unknown = ArticleFilter.apply(loaded, ArticleFilter.Criteria(ArticleFilter.DateScope.Unknown))
        assertEquals("未知日期单独一档", 1, unknown.size)
        assertEquals("a1", unknown[0].entity.localId)

        val all = ArticleFilter.apply(loaded, ArticleFilter.Criteria(ArticleFilter.DateScope.All))
        assertEquals("全部已获取包含未知日期", 2, all.size)
    }

    @Test
    fun date_boundary_uses_device_timezone_not_utc() {
        // 东八区与 UTC 对同一时刻可能给出不同“本地日期”
        val loaded = listOf(
            ArticleFilter.toLoadedRow(entity("late", "2026-09-22T23:00:00Z"), zone = shanghai), // 东八区 23 日 07:00
            ArticleFilter.toLoadedRow(entity("early", "2026-09-21T16:00:00Z"), zone = shanghai), // 东八区 22 日 00:00
        )

        assertEquals("22 日只有 early", 1,
            ArticleFilter.apply(loaded, ArticleFilter.Criteria(ArticleFilter.DateScope.On("2026-09-22"))).size)
        assertEquals("23 日只有 late", 1,
            ArticleFilter.apply(loaded, ArticleFilter.Criteria(ArticleFilter.DateScope.On("2026-09-23"))).size)

        // 换 UTC 时区判定，同一天会落到 22/21
        val utcRows = listOf(
            ArticleFilter.toLoadedRow(entity("late", "2026-09-22T23:00:00Z"), zone = TimeZone.getTimeZone("UTC")),
        )
        assertEquals("UTC 下是 22 日", 1,
            ArticleFilter.apply(utcRows, ArticleFilter.Criteria(ArticleFilter.DateScope.On("2026-09-22"))).size)
    }

    @Test
    fun unread_only_filters_on_local_read_state() {
        val loaded = rows("2026-09-22T00:00:00Z" to true, "2026-09-22T01:00:00Z" to false)
        val unread = ArticleFilter.apply(loaded, ArticleFilter.Criteria(unreadOnly = true))
        assertEquals(1, unread.size)
        assertEquals("a1", unread[0].entity.localId)
        assertEquals("未读数只数已加载条目", 1, ArticleFilter.unreadCount(loaded))
    }

    @Test
    fun empty_reason_distinguishes_real_causes() {
        val loaded = rows("2026-09-22T00:00:00Z" to true)

        assertEquals(
            "一条都没加载 → 不是“还没有订阅”，而是该源还没有内容",
            ArticleFilter.EmptyReason.NoContentLoaded,
            ArticleFilter.emptyReason(emptyList(), ArticleFilter.Criteria()),
        )
        assertEquals(
            "全部已读 + 仅未读筛选 → 是“已加载范围内没有未读”",
            ArticleFilter.EmptyReason.NoUnreadInLoaded,
            ArticleFilter.emptyReason(loaded, ArticleFilter.Criteria(unreadOnly = true)),
        )
        assertEquals(
            "该日没有内容",
            ArticleFilter.EmptyReason.NoContentOnDate,
            ArticleFilter.emptyReason(loaded, ArticleFilter.Criteria(ArticleFilter.DateScope.On("2020-01-01"))),
        )
        assertEquals(
            "没有未知日期的条目",
            ArticleFilter.EmptyReason.NoUnknownDate,
            ArticleFilter.emptyReason(loaded, ArticleFilter.Criteria(ArticleFilter.DateScope.Unknown)),
        )
        assertEquals(
            "有内容且筛选命中 → 不应报空",
            ArticleFilter.EmptyReason.None,
            ArticleFilter.emptyReason(loaded, ArticleFilter.Criteria()),
        )
    }

    // ── 列表摘要与封面（T22 F09） ─────────────────────────────────

    @Test
    fun summary_preview_is_plaintext_truncated_and_never_empty_string() {
        val html = "<p>Hello <b>world</b> &amp; &lt;tags&gt;  <img src=\"https://a.example.org/i.png\"></p>" +
            "后面的内容".repeat(30)
        val preview = ArticleFilter.summaryPreview(html, limit = 40)!!
        assertEquals("40 字符 + 省略号", 41, preview.length)
        assertTrue("以省略号收尾", preview.endsWith("…"))
        assertFalse("真标签被剥掉（HTML 元素不残留）", Regex("</?(p|b|img)\\b").containsMatchIn(preview))
        assertTrue("实体解码为字面文字", preview.contains("&") && preview.contains("<tags>"))
        assertEquals("数字字符引用解码（真机发现 Cloudflare 用 &#x2019;）", "haven’t done", ArticleFilter.summaryPreview("haven&#x2019;t done"))
        assertEquals("十进制引用同样解", "’", ArticleFilter.summaryPreview("&#8217;"))
        assertEquals("astral 码点不拆坏", "A😀B", ArticleFilter.summaryPreview("A&#x1F600;B"))
        assertEquals("非法码点原样保留", "&#0;", ArticleFilter.summaryPreview("&#0;"))
        assertNull("纯标签没有文字 → null（不留空行）", ArticleFilter.summaryPreview("<p><br/></p>"))
        assertNull("原料为 null 就是 null", ArticleFilter.summaryPreview(null))
        assertEquals("短内容原样", "很短的一段摘要", ArticleFilter.summaryPreview("  很短的一段摘要  "))
    }

    @Test
    fun loaded_row_carries_preview_and_thumbnail() {
        val row = ArticleFilter.toLoadedRow(
            entity("r1", "2026-09-22T00:00:00Z"),
            previewRaw = "<p>一段摘要</p>",
            thumbnail = "https://a.example.org/cover.png",
            zone = shanghai,
        )
        assertEquals("一段摘要", row.summary)
        assertEquals("https://a.example.org/cover.png", row.thumbnail)
        val noCover = ArticleFilter.toLoadedRow(entity("r2", null), previewRaw = "<p>x</p>", thumbnail = "  ", zone = shanghai)
        assertNull("空白封面按无图处理，不留空框", noCover.thumbnail)
    }

    @Test
    fun same_timestamp_order_is_stable() {
        // 同一发布时间的两条：列表顺序必须稳定，不能因查询顺序变化而跳动
        val loaded = rows("2026-09-22T00:00:00Z" to false, "2026-09-22T00:00:00Z" to false)
        assertEquals(2, ArticleFilter.apply(loaded, ArticleFilter.Criteria()).size)
        assertEquals(
            "重复调用结果一致",
            ArticleFilter.apply(loaded, ArticleFilter.Criteria()).map { it.entity.localId },
            ArticleFilter.apply(loaded, ArticleFilter.Criteria()).map { it.entity.localId },
        )
    }

    @Test fun `summary preview strips tag cut in half by dao truncation`() {
        // 真机实测：DAO 600 字符截断把 <p style="..."> 切成两半，露出源码
        val raw = "前半段正文 <p style=\"margin: 7px 14px 0px; padding-left: 8px\""
        val preview = dev.local.catleaf.nativeapp.data.article.ArticleFilter.summaryPreview(raw)
        assertEquals("前半段正文", preview)
        // 完整标签仍然剥除
        assertEquals("正常正文 加粗 尾部", dev.local.catleaf.nativeapp.data.article.ArticleFilter.summaryPreview("正常正文 <b>加粗</b> 尾部"))
    }
}