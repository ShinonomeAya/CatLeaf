package dev.local.catleaf.nativeapp.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UIR06/07（原型 26/27）：精选/全部/分类/来源状态/搜索的组合过滤 */
class CatalogQueryTest {

    private fun entry(id: String, category: String, status: String, name: String = id) = CatalogEntry(
        id = id, name = name, feedUrl = "https://$id.example/rss", siteUrl = "https://$id.example",
        categoryId = category, language = "zh", plainDescription = "", sourceType = "origin",
        status = status, checkedAt = "", provenance = "", aliases = emptyList(), note = null,
    )

    private val catalog = Catalog(
        version = "t", checkedAt = "",
        categories = listOf("tech" to "科技数码", "dev" to "编程技术", "empty" to "空分类"),
        preferredIds = listOf("b", "a"),
        entries = listOf(
            entry("a", "tech", Catalog.STATUS_OK, "Alpha"),
            entry("b", "dev", Catalog.STATUS_OK),
            entry("c", "dev", Catalog.STATUS_NEEDS_CONFIG, "Alpha Gen"),
            entry("d", "tech", Catalog.STATUS_NEEDS_CONFIG),
        ),
    )

    private fun ids(q: CatalogQuery) = filterCatalog(catalog, q).map { it.id }

    @Test
    fun featured_keeps_preferred_order() {
        assertEquals(listOf("b", "a"), ids(CatalogQuery()))
    }

    @Test
    fun search_in_featured_searches_whole_catalog() {
        assertEquals(listOf("a", "c"), ids(CatalogQuery(text = "alpha")))
    }

    @Test
    fun category_and_availability_combine() {
        val q = CatalogQuery().toggleCategory("dev").toggleAvailability(Availability.NeedsConfig)
        assertEquals(CatalogView.All, q.view)
        assertEquals(listOf("c"), ids(q))
        assertEquals(listOf("a", "c"), ids(q.copy(text = "alpha", categoryId = null, availability = null)))
        assertEquals(listOf("c"), ids(q.copy(text = "alpha", categoryId = null)))
    }

    @Test
    fun toggling_same_category_clears_it_and_all_keeps_availability() {
        val q = CatalogQuery().toggleCategory("tech").toggleCategory("tech")
        assertNull(q.categoryId)
        val withStatus = CatalogQuery().toggleAvailability(Availability.Ready).toggleCategory("dev").all()
        assertNull(withStatus.categoryId)
        assertEquals(Availability.Ready, withStatus.availability)
        assertEquals(listOf("a", "b"), ids(withStatus))
    }

    @Test
    fun featured_resets_category_and_availability() {
        val q = CatalogQuery(text = "x").toggleCategory("dev").toggleAvailability(Availability.Ready).featured()
        assertEquals(CatalogView.Featured, q.view)
        assertNull(q.categoryId)
        assertNull(q.availability)
        assertEquals("x", q.text)
    }

    @Test
    fun cleared_drops_conditions_but_keeps_view() {
        val q = CatalogQuery(text = "x").toggleCategory("dev")
        assertTrue(q.hasConditions)
        val cleared = q.cleared()
        assertFalse(cleared.hasConditions)
        assertEquals(CatalogView.All, cleared.view)
    }

    @Test
    fun empty_categories_hidden_and_counts_per_category() {
        assertEquals(listOf("tech", "dev"), nonEmptyCategories(catalog).map { it.first })
        assertEquals(mapOf(Availability.Ready to 2, Availability.NeedsConfig to 2), availabilityCounts(catalog, null))
        assertEquals(mapOf(Availability.Ready to 1, Availability.NeedsConfig to 1), availabilityCounts(catalog, "dev"))
    }
}
