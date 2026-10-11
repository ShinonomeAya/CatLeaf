package dev.local.catleaf.nativeapp.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 位置锚点的规则（契约 §7）：列表位置按**稳定文章 id + 偏移**恢复，
 * 正文位置在内容换过时按比例回退。格式要在 DataStore、界面和单测三处一致，所以要钉住。
 */
class ScrollAnchorTest {

    private val ids = listOf("g:aaa", "l:bbb", "g:ccc")

    @Test
    fun list_anchor_round_trips_and_resolves_by_article_id() {
        val encoded = ScrollAnchor.encodeList("l:bbb", 240)
        val decoded = ScrollAnchor.decodeList(encoded)!!

        assertEquals("l:bbb", decoded.localId)
        assertEquals(240, decoded.offset)
        assertEquals(
            "按文章 id 找回下标（不是记住原来的序号）",
            1,
            ScrollAnchor.indexOfArticle(ids, "l:bbb"),
        )
    }

    @Test
    fun list_anchor_is_not_confused_by_reordered_or_shrunk_lists() {
        // 刷新后有新文章插到前面：同一个 id 的下标变了，但依然能定位
        val afterRefresh = listOf("g:new", "g:aaa", "l:bbb", "g:ccc")
        assertEquals(2, ScrollAnchor.indexOfArticle(afterRefresh, "l:bbb"))
        // 锚点文章被清掉（例如换了筛选）：返回 null，界面从头显示而不是跳到别人的位置
        assertNull(ScrollAnchor.indexOfArticle(listOf("g:aaa", "g:ccc"), "l:bbb"))
    }

    @Test
    fun malformed_list_anchor_is_ignored() {
        assertNull("空值不崩", ScrollAnchor.decodeList(null))
        assertNull("没有分隔符", ScrollAnchor.decodeList("g:aaa"))
        assertNull("偏移不是数字", ScrollAnchor.decodeList("g:aaa|abc"))
    }

    @Test
    fun reader_position_round_trips() {
        val anchor = ScrollAnchor.ReaderAnchor("g:aaa", "hash-1", 1200, 4000)
        val decoded = ScrollAnchor.decodeReader(ScrollAnchor.encodeReader("g:aaa", "hash-1", 1200, 4000))!!
        assertEquals(anchor, decoded)
        assertNull("格式不对就不认", ScrollAnchor.decodeReader("g:aaa|hash|1200"))
        assertNull("空值不崩", ScrollAnchor.decodeReader(null))
    }

    @Test
    fun reader_position_restores_proportionally_so_late_images_do_not_shift_it() {
        val anchor = ScrollAnchor.ReaderAnchor("g:aaa", "hash-1", 1000, 4000)
        assertEquals(
            "文档长度没变：比例换算回原位置",
            1000,
            ScrollAnchor.readerScrollTarget(anchor, "g:aaa", 4000),
        )
        assertEquals(
            "文档被图片撑高（4000→5000）：位置跟着放大，仍指向同一段文字",
            1250,
            ScrollAnchor.readerScrollTarget(anchor, "g:aaa", 5000),
        )
        assertEquals(
            "内容换短（例如只读到摘要）：按比例回到相应位置，不越界",
            250,
            ScrollAnchor.readerScrollTarget(anchor, "g:aaa", 1000),
        )
    }

    @Test
    fun reader_position_is_ignored_for_another_article() {
        val anchor = ScrollAnchor.ReaderAnchor("g:aaa", "hash-1", 1200, 4000)
        assertEquals(
            "换了一篇文章：从头开始",
            0,
            ScrollAnchor.readerScrollTarget(anchor, "g:zzz", 4000),
        )
        assertEquals(0, ScrollAnchor.readerScrollTarget(null, "g:aaa", 4000))
        assertEquals(
            "还没测出文档长度时先不滚",
            0,
            ScrollAnchor.readerScrollTarget(anchor, "g:aaa", 0),
        )
    }
}