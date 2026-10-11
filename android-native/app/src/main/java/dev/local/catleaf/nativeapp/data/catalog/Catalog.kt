package dev.local.catleaf.nativeapp.data.catalog

import android.content.Context
import org.json.JSONObject

/**
 * 内置发现目录（契约 8.6）：随 APK 发版的静态 JSON（assets/catalog/catalog.json），
 * 由 tools/catalog/（快照核验 → dispositions → build-outputs）生成，字段与台账可追溯。
 *
 * 只做本地解析与过滤，不发网络、不抓 favicon/文章；「已订阅」状态由界面按活动范围
 * 的规范化 URL 比对（[dev.local.catleaf.nativeapp.data.network.EndpointPolicy.isSameFeedUrl]）。
 * 名称/描述是数据不是执行指令，按纯文本显示。
 */
data class CatalogEntry(
    val id: String,
    val name: String,
    val feedUrl: String,
    val siteUrl: String,
    val categoryId: String,
    val language: String,
    val plainDescription: String,
    /** origin = 原站；third_party = RSSHub/anyfeeder 等第三方生成源（需配置） */
    val sourceType: String,
    /** ok = 可收录；needs_config = 需要第三方生成实例（预填配置，不默默用陌生公共代理） */
    val status: String,
    val checkedAt: String,
    val provenance: String,
    val aliases: List<String>,
    val note: String?,
)

data class Catalog(
    val version: String,
    val checkedAt: String,
    val categories: List<Pair<String, String>>,
    /** 精选固定顺序引用；首次引导沿用前五源。 */
    val preferredIds: List<String>,
    val entries: List<CatalogEntry>,
) {
    val available: List<CatalogEntry> get() = entries.filter { it.status == STATUS_OK }
    val needsConfig: List<CatalogEntry> get() = entries.filter { it.status == STATUS_NEEDS_CONFIG }
    val preferred: List<CatalogEntry> get() = preferredIds.mapNotNull { id -> entries.firstOrNull { it.id == id } }

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_NEEDS_CONFIG = "needs_config"
    }
}

/** 纯解析（JSON 字符串 → [Catalog]），JVM 可测；字段缺失按空处理，结构错误整份拒绝 */
object CatalogParser {

    fun parse(text: String): Result<Catalog> = runCatching {
        val root = JSONObject(text)
        val version = root.optString("catalogVersion")
        if (version.isBlank()) error("catalogVersion 缺失")
        val categories = mutableListOf<Pair<String, String>>()
        val catArray = root.optJSONArray("categories")
        for (i in 0 until (catArray?.length() ?: 0)) {
            val o = catArray!!.getJSONObject(i)
            categories += o.getString("id") to o.getString("name")
        }
        val preferred = mutableListOf<String>()
        val prefArray = root.optJSONArray("preferredIds")
        for (i in 0 until (prefArray?.length() ?: 0)) preferred += prefArray!!.getString(i)
        val entries = mutableListOf<CatalogEntry>()
        val entryArray = root.optJSONArray("entries") ?: error("entries 缺失")
        for (i in 0 until entryArray.length()) {
            val o = entryArray.getJSONObject(i)
            val id = o.optString("id")
            val feedUrl = o.optString("feedUrl")
            if (id.isBlank() || feedUrl.isBlank()) error("第 $i 条缺 id/feedUrl")
            val aliases = mutableListOf<String>()
            val aliasArray = o.optJSONArray("aliases")
            for (j in 0 until (aliasArray?.length() ?: 0)) aliases += aliasArray!!.getString(j)
            entries += CatalogEntry(
                id = id,
                name = o.optString("name"),
                feedUrl = feedUrl,
                siteUrl = o.optString("siteUrl"),
                categoryId = o.optString("categoryId", "other"),
                language = o.optString("language", "zh"),
                plainDescription = o.optString("plainDescription"),
                sourceType = o.optString("sourceType", "origin"),
                status = o.optString("status", "ok"),
                checkedAt = o.optString("checkedAt"),
                provenance = o.optString("provenance"),
                aliases = aliases,
                note = o.optString("note").ifBlank { null },
            )
        }
        Catalog(
            version = version,
            checkedAt = root.optString("checkedAt"),
            categories = categories,
            preferredIds = preferred,
            entries = entries,
        )
    }
}

/** 进程内加载一次；搜索/分类过滤都是本地纯查询 */
class CatalogRepository(private val context: Context) {

    @Volatile
    private var cached: Catalog? = null

    fun load(): Catalog = cached ?: synchronized(this) {
        cached ?: run {
            val text = context.assets.open("catalog/catalog.json").bufferedReader().use { it.readText() }
            CatalogParser.parse(text).getOrElse { throw IllegalStateException("内置目录损坏：${it.message}", it) }
        }.also { cached = it }
    }

    fun search(catalog: Catalog, query: String): List<CatalogEntry> = searchCatalog(catalog, query)

    fun byCategory(catalog: Catalog, categoryId: String?): List<CatalogEntry> =
        if (categoryId.isNullOrBlank()) catalog.entries else catalog.entries.filter { it.categoryId == categoryId }
}

/** 本地搜索：名称、域名、简介、别名（Q 未做分词承诺；子串匹配，英文不区分大小写）；纯函数，JVM 可测 */
fun searchCatalog(catalog: Catalog, query: String): List<CatalogEntry> {
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
