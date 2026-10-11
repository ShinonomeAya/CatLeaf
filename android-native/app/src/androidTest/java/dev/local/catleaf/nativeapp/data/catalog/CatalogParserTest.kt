package dev.local.catleaf.nativeapp.data.catalog

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** 内置目录解析（契约 8.6）：结构错误整份拒绝；preferred 顺序固定引用。 */
@RunWith(AndroidJUnit4::class)
class CatalogParserTest {

    @Test fun bundledCatalogIncludesRecheckedFeeds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = CatalogRepository(context).load()
        assertEquals("2026-10-09.2", catalog.version)
        assertEquals("2026-10-09", catalog.checkedAt)
        assertEquals(298, catalog.entries.size)
        assertEquals(81, catalog.available.size)
        assertEquals(217, catalog.needsConfig.size)
        assertEquals(listOf("少数派", "爱范儿", "IT之家", "阮一峰的网络日志", "The Verge", "橘鸦AI早报"), catalog.preferred.map { it.name })
        assertTrue(catalog.entries.all { dev.local.catleaf.nativeapp.data.network.EndpointPolicy.requirePublicFetchTarget(it.feedUrl).isSuccess })
        assertEquals("https://daily.juya.uk/rss.xml", catalog.preferred.last().feedUrl)
        assertTrue(catalog.available.any { it.feedUrl == "https://coolshell.cn/feed" })
        assertTrue(catalog.available.any { it.feedUrl == "https://tumutanzi.com/feed" })
        assertTrue(catalog.available.any { it.feedUrl == "https://wangyurui.com/feed.xml" })
        val expectedCategories = mapOf(
            "架构师之路" to "dev",
            "丁香医生" to "science",
            "少数派 -- Matrix" to "productivity",
            "参考消息" to "news",
            "太隐" to "blog",
        )
        expectedCategories.forEach { (name, categoryId) ->
            assertTrue("$name 应归入 $categoryId", catalog.entries.any { it.name == name && it.categoryId == categoryId })
        }
        assertTrue(catalog.needsConfig.count { it.categoryId == "news" } < 192)
    }

    @Test fun verified_brand_and_config_required_variant_share_category() {
        val catalog = CatalogRepository(InstrumentationRegistry.getInstrumentation().targetContext).load()
        listOf("机核", "今日话题 - 雪球", "虎嗅", "极客公园", "理想生活实验室", "人人都是产品经理", "钛媒体：引领未来商业与生活新知")
            .forEach { name ->
                val matches = catalog.entries.filter { it.name == name }
                assertTrue(name, matches.size >= 2)
                assertEquals(name, 1, matches.map { it.categoryId }.distinct().size)
            }
        assertTrue(catalog.entries.none { it.feedUrl.startsWith("http://feeds.feedburner.com/") })
        assertTrue(catalog.preferred.any { it.name == "阮一峰的网络日志" && it.categoryId == "dev" })
    }

    private val sample = """
    {
     "catalogVersion":"2026-09-25.1",
     "checkedAt":"2026-09-25",
     "snapshot":"top-rss-list@3892c301",
     "categories":[{"id":"tech","name":"科技数码"},{"id":"dev","name":"编程技术"}],
     "preferredIds":["id-verge","id-sspai"],
     "entries":[
      {"id":"id-verge","name":"The Verge","feedUrl":"https://www.theverge.com/rss/index.xml","siteUrl":"https://www.theverge.com",
       "categoryId":"tech","language":"en","plainDescription":"Tech news.","sourceType":"origin","status":"ok",
       "checkedAt":"2026-09-25","provenance":"preferred-five"},
      {"id":"id-rsshub","name":"知乎热榜","feedUrl":"https://rsshub.app/zhihu/hotlist","siteUrl":"https://rsshub.app",
       "categoryId":"news","language":"zh","plainDescription":"","sourceType":"third_party","status":"needs_config",
       "checkedAt":"2026-09-25","provenance":"top-rss-list@3892c301","aliases":["知乎热榜 2"]}
     ]
    }
    """.trimIndent()

    @Test fun parsesEntriesCategoriesAndPreferredOrder() {
        val catalog = CatalogParser.parse(sample).getOrThrow()
        assertEquals(2, catalog.entries.size)
        assertEquals(listOf("id-verge", "id-sspai"), catalog.preferredIds)
        // preferred 引用不存在的 id 时不崩，只取得到的部分（五源缺项如实显示）
        assertEquals(listOf("id-verge"), catalog.preferred.map { it.id })
        assertEquals("科技数码", catalog.categories.first().second)
        assertEquals("third_party", catalog.needsConfig.single().sourceType)
        assertEquals(listOf("知乎热榜 2"), catalog.needsConfig.single().aliases)
    }

    @Test fun missingVersionOrEntriesRejectsWholeFile() {
        assertTrue(CatalogParser.parse("""{"entries":[]}""").isFailure)
        assertTrue(CatalogParser.parse("""{"catalogVersion":"x"}""").isFailure)
        assertTrue(CatalogParser.parse("""{"catalogVersion":"x","entries":[{"name":"缺id与url"}]}""").isFailure)
    }

    @Test fun searchMatchesNameDomainDescriptionAndAlias() {
        val catalog = CatalogParser.parse(sample).getOrThrow()
        val repo = CatalogRepositoryForTest
        assertEquals(listOf("id-verge"), repo.search(catalog, "verge").map { it.id })
        assertEquals(listOf("id-verge"), repo.search(catalog, "THEVERGE.COM").map { it.id })
        assertEquals(listOf("id-rsshub"), repo.search(catalog, "热榜 2").map { it.id })
        assertTrue("空词返回全部", repo.search(catalog, "  ").size == 2)
    }
}

/** JVM 侧用不上 Android assets：搜索是纯函数，直接复用对象方法 */
private object CatalogRepositoryForTest {
    fun search(catalog: Catalog, query: String): List<CatalogEntry> {
        val q = query.trim()
        if (q.isEmpty()) return catalog.entries
        val lower = q.lowercase()
        return catalog.entries.filter { e ->
            e.name.lowercase().contains(lower) ||
                e.siteUrl.lowercase().contains(lower) ||
                e.plainDescription.lowercase().contains(lower) ||
                e.aliases.any { it.lowercase().contains(lower) }
        }
    }
}
