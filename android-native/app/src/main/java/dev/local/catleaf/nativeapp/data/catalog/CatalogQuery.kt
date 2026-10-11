package dev.local.catleaf.nativeapp.data.catalog

/**
 * 发现页查询条件（原型 26/27，UIR06/07）：纯数据与纯函数，JVM 可测。
 * 精选 = 目录固定顺序；全部 = 分类 × 来源状态 × 本地搜索组合过滤。
 * 在精选下输入关键词即明确的全库搜索（页面提示「在全部目录中搜索」）。
 */
enum class CatalogView { Featured, All }

/** 来源状态（契约 8.6）：与分类独立的一维，不用一个「可用」覆盖全部条目 */
enum class Availability { Ready, NeedsConfig }

data class CatalogQuery(
    val text: String = "",
    val view: CatalogView = CatalogView.Featured,
    val categoryId: String? = null,
    val availability: Availability? = null,
) {
    val isSearching: Boolean get() = text.isNotBlank()

    /** 空结果时「清除条件」是否有意义 */
    val hasConditions: Boolean get() = isSearching || categoryId != null || availability != null

    /** 切到精选：清空分类与来源状态（精选不受二者影响），保留关键词 */
    fun featured(): CatalogQuery = copy(view = CatalogView.Featured, categoryId = null, availability = null)

    /** 切到全部：去掉分类，保留来源状态与关键词 */
    fun all(): CatalogQuery = copy(view = CatalogView.All, categoryId = null)

    /** 选分类；再点当前分类即取消 */
    fun toggleCategory(id: String): CatalogQuery =
        copy(view = CatalogView.All, categoryId = if (categoryId == id) null else id)

    /** 选来源状态；再点当前状态即取消 */
    fun toggleAvailability(value: Availability): CatalogQuery =
        copy(view = CatalogView.All, availability = if (availability == value) null else value)

    fun cleared(): CatalogQuery = CatalogQuery(view = view)
}

fun Availability.matches(entry: CatalogEntry): Boolean = when (this) {
    Availability.Ready -> entry.status == Catalog.STATUS_OK
    Availability.NeedsConfig -> entry.status == Catalog.STATUS_NEEDS_CONFIG
}

fun filterCatalog(catalog: Catalog, query: CatalogQuery): List<CatalogEntry> {
    if (query.view == CatalogView.Featured && !query.isSearching) return catalog.preferred
    val base = if (query.isSearching) searchCatalog(catalog, query.text) else catalog.entries
    return base.filter { e ->
        (query.categoryId == null || e.categoryId == query.categoryId) &&
            (query.availability?.matches(e) ?: true)
    }
}

/** 有内容的分类（无内容分类不显示，页面说明 §27），保持目录给定顺序 */
fun nonEmptyCategories(catalog: Catalog): List<Pair<String, String>> =
    catalog.categories.filter { c -> catalog.entries.any { it.categoryId == c.first } }

/** 某分类（null=全部）下各来源状态的条目数，用于分类面板的状态筛选标签 */
fun availabilityCounts(catalog: Catalog, categoryId: String?): Map<Availability, Int> {
    val scoped = catalog.entries.filter { categoryId == null || it.categoryId == categoryId }
    return Availability.entries.associateWith { a -> scoped.count { a.matches(it) } }
}
