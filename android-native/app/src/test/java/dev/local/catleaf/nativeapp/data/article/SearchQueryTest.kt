package dev.local.catleaf.nativeapp.data.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 搜索查询纯规则（契约 9.1）：空词不扫描、超限拒绝、LIKE 转义、ASCII 折叠。 */
class SearchQueryTest {

    @Test fun `blank query normalizes to null - no scan`() {
        assertNull(SearchQuery.normalize("   ").getOrNull())
        assertNull(SearchQuery.normalize("").getOrNull())
        assertNull(SearchQuery.normalize(null).getOrNull())
    }

    @Test fun `over limit rejected not silently truncated`() {
        assertTrue(SearchQuery.normalize("a".repeat(101)).isFailure)
        assertTrue(SearchQuery.normalize("中".repeat(100)).isSuccess)
    }

    @Test fun `trims whitespace`() {
        assertEquals("关键词", SearchQuery.normalize("  关键词  ").getOrNull())
    }

    @Test fun `like pattern escapes backslash percent underscore`() {
        assertEquals("plain", SearchQuery.likePattern("plain"))
        assertEquals("100\\%", SearchQuery.likePattern("100%"))
        assertEquals("a\\_b", SearchQuery.likePattern("a_b"))
        assertEquals("c:\\\\path", SearchQuery.likePattern("C:\\path"))
    }

    @Test fun `ascii lowercased - chinese kept as-is for substring match`() {
        assertEquals("compose", SearchQuery.likePattern("Compose"))
        assertEquals("猫页开发笔记", SearchQuery.likePattern("猫页开发笔记"))
        // 中文连续子串语义：pattern 原样进入 LIKE，SQL 匹配任意位置的连续子串
        assertEquals("workers", SearchQuery.likePattern("Workers"))
    }
}
