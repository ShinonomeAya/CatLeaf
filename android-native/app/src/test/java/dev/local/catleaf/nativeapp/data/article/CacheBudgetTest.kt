package dev.local.catleaf.nativeapp.data.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 淘汰计划的纯逻辑（契约 4.3 / T18 验证：小预算注入 + 生产默认值核对）。
 * 收藏、keep 集合永不进删除列表；取消收藏（starred=false）后回到普通预算。
 */
class CacheBudgetTest {

    private fun content(id: String, bytes: Long = 10, starred: Boolean = false, accessed: Long = 0) =
        CacheBudget.ContentRow(id, bytes, starred, accessed)

    private fun ai(key: String, bytes: Long = 10, protectedItem: Boolean = false, accessed: Long = 0) =
        CacheBudget.AiRow(key, bytes, protectedItem, accessed)

    @Test
    fun production_defaults_are_the_contract_numbers() {
        assertEquals("契约 4.3：2,000 篇", 2_000, CacheBudget.MAX_ARTICLES)
        assertEquals("契约 4.3：100 MiB", 100L * 1024 * 1024, CacheBudget.MAX_BYTES)
    }

    @Test
    fun under_budget_changes_nothing() {
        val plan = CacheBudget.plan(
            contents = (1..3).map { content("c$it", accessed = it.toLong()) },
            ai = listOf(ai("a1")),
            maxArticles = 5, maxBytes = 1_000,
        )
        assertTrue("未超限不该有任何删除：$plan", plan.isEmpty)
    }

    @Test
    fun article_cap_evicts_oldest_first_and_protects_starred_and_keep() {
        val plan = CacheBudget.plan(
            contents = listOf(
                content("newest", accessed = 100),
                content("mid", accessed = 50),
                content("oldest", accessed = 1),
                content("starred-one", accessed = 0, starred = true),
                content("reading", accessed = 0),
            ),
            ai = emptyList(),
            keepLocalIds = setOf("reading"),
            maxArticles = 2,
            maxBytes = Long.MAX_VALUE,
        )
        assertEquals("2,000→2 时按最旧先删：oldest+mid", setOf("oldest", "mid"), plan.contentLocalIds.toSet())
        assertFalse("收藏永不进删除集", "starred-one" in plan.contentLocalIds)
        assertFalse("当前正文避开", "reading" in plan.contentLocalIds)
    }

    @Test
    fun byte_budget_evicts_content_and_plain_ai_by_shared_recency() {
        // 收藏正文 900 不计入普通预算；普通 = old(200) + new(250) + digest(100) = 550 > 300，
        // 受保护译文 100 不计入也不删。混合 LRU：删 old(1) → 350 还超 → 删 digest-1(2) → 250 ≤ 300 停
        val plan = CacheBudget.plan(
            contents = listOf(
                content("old", bytes = 200, accessed = 1),
                content("new", bytes = 250, accessed = 90),
                content("starred", bytes = 900, accessed = 0, starred = true),
            ),
            ai = listOf(
                ai("digest-1", bytes = 100, accessed = 2),
                ai("trans-starred", bytes = 100, protectedItem = true, accessed = 0),
            ),
            maxArticles = 100,
            maxBytes = 300,
        )
        assertEquals("最旧的普通内容先淘汰", listOf("old"), plan.contentLocalIds)
        assertEquals("日摘要属普通缓存，按共享 LRU 可被淘汰", listOf("digest-1"), plan.aiCacheKeys)
    }

    @Test
    fun protected_ai_and_starred_survive_even_when_bytes_cannot_fit() {
        // 受保护项超预算：候选删空后如实留在超限（不删保护项、不死循环）
        val plan = CacheBudget.plan(
            contents = listOf(content("starred", bytes = 500, starred = true)),
            ai = listOf(ai("trans-starred", bytes = 500, protectedItem = true)),
            maxArticles = 100, maxBytes = 10,
        )
        assertTrue("保护项永不被删：$plan", plan.isEmpty)
    }

    @Test
    fun unstarred_article_returns_to_normal_budget() {
        // 取消收藏后（starred=false）同一篇就是普通候选——T18 验证「取消收藏后的预算归属」
        val starred = CacheBudget.plan(
            contents = listOf(content("art", accessed = 1, starred = true)),
            ai = emptyList(), maxArticles = 0, maxBytes = Long.MAX_VALUE,
        )
        assertTrue("收藏时不进候选", starred.contentLocalIds.isEmpty())

        val unstarred = CacheBudget.plan(
            contents = listOf(content("art", accessed = 1, starred = false)),
            ai = emptyList(), maxArticles = 0, maxBytes = Long.MAX_VALUE,
        )
        assertEquals("取消收藏后回普通预算", listOf("art"), unstarred.contentLocalIds)
    }

    @Test
    fun keep_set_never_evicts_even_as_oldest() {
        val plan = CacheBudget.plan(
            contents = listOf(content("keep-me", accessed = 0), content("rest", accessed = 1)),
            ai = emptyList(),
            keepLocalIds = setOf("keep-me"),
            maxArticles = 1,
            maxBytes = 15, // keep-me(10) + rest(10) = 20 > 15，但 rest 已被篇数删了 → 只剩 keep-me=10 ≤ 15
        )
        assertEquals(listOf("rest"), plan.contentLocalIds)
    }
}
