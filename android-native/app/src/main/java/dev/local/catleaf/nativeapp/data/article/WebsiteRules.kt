package dev.local.catleaf.nativeapp.data.article

import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.rss.FeedParser
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Local, declarative selectors only; no headers, credentials, scripts or URL rewriting. */
data class WebsiteRules(
    val body: String = "",
    val exclude: String = "",
    val items: String = "",
    val title: String = "",
    val link: String = "",
    val date: String = "",
    val summary: String = "",
) {
    private fun values() = linkedMapOf("body" to body, "exclude" to exclude, "items" to items, "title" to title, "link" to link, "date" to date, "summary" to summary)

    fun requireValid(sourceType: String) {
        require(sourceType == "rss" || sourceType == "html") { "未知订阅类型" }
        validateSelectors()
        if (sourceType == "rss") require(body.isNotBlank()) { "请填写正文选择器" }
        else require(items.isNotBlank() && title.isNotBlank() && link.isNotBlank()) { "网页订阅需要条目、标题和链接选择器" }
    }

    private fun validateSelectors() {
        values().forEach { (name, selector) ->
            require(selector.length <= 512 && selector.count { it == '(' } <= 4) { "$name 选择器过长或嵌套过深" }
            require('\\' !in selector && !REGEX_SELECTOR.containsMatchIn(selector)) { "首版规则不支持正则或转义选择器" }
            if (selector.isNotBlank()) try {
                Jsoup.parse("").select(selector)
            } catch (_: Exception) { throw IllegalArgumentException("$name 选择器语法无效") }
        }
    }

    fun extractBody(raw: String, baseUrl: String): ArticleSanitizer.Sanitized {
        validateSelectors()
        require(body.isNotBlank()) { "请填写正文选择器" }
        val doc = parseDocument(raw, baseUrl)
        if (exclude.isNotBlank()) doc.select(exclude).remove()
        val selected = doc.select(body)
        require(selected.isNotEmpty()) { "正文规则未命中" }
        require(selected.size <= 256) { "正文匹配过多，请缩小选择器" }
        // Parent and child matches must not repeat the same paragraph.
        val roots = selected.filter { node -> node.parents().none { it in selected } }
        val result = ArticleSanitizer.sanitize(roots.joinToString("\n") { it.outerHtml() }, baseUrl)
        require(!result.isEmpty) { "规则提取的正文为空" }
        return result
    }

    fun extractList(raw: String, baseUrl: String): FeedParser.ParsedFeed {
        requireValid("html")
        val doc = parseDocument(raw, baseUrl)
        val selected = doc.select(items)
        require(selected.isNotEmpty()) { "列表规则未命中；网页可能变化或需要JavaScript/登录" }
        require(selected.size <= 2000) { "列表超过2000项，请缩小选择器；本次未保存" }
        val seen = mutableSetOf<String>()
        var invalid = 0
        val parsed = selected.mapNotNull { node ->
            val headline = node.selectFirst(title)?.text()?.trim().orEmpty()
            val href = node.selectFirst(link)?.absUrl("href")?.substringBefore('#')
            val safe = EndpointPolicy.requirePublicFetchTarget(href).getOrNull()
            if (headline.isBlank() || headline.length > 1000 || safe == null) { invalid++; return@mapNotNull null }
            if (!seen.add(safe)) return@mapNotNull null
            val published = date.takeIf { it.isNotBlank() }?.let { node.selectFirst(it) }
                ?.let { DateNormalizer.toIsoUtc(it.attr("datetime").ifBlank { it.text() }) }
            val description = summary.takeIf { it.isNotBlank() }?.let { node.selectFirst(it)?.outerHtml() }
                ?.let { ArticleSanitizer.sanitize(it, baseUrl).html }
            FeedParser.ParsedItem(headline, safe, safe, published, null, description, null, null)
        }
        require(parsed.isNotEmpty()) { "没有有效文章，请检查标题与链接规则；旧文章保留" }
        return FeedParser.ParsedFeed(doc.title(), "", "", parsed, invalid)
    }

    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        values().forEach { (key, value) -> put(key, value) }
    }.toString()

    companion object {
        const val MAX_JSON_BYTES = 16 * 1024
        private val REGEX_SELECTOR = Regex(":matches|\\[[^\\]]*~=", RegexOption.IGNORE_CASE)

        internal fun parseDocument(raw: String, baseUrl: String): Document {
            require(raw.length <= PublicFetch.MAX_BYTES) { "网页超过解析预算" }
            require(EndpointPolicy.requirePublicFetchTarget(baseUrl).isSuccess) { "网页地址不符合公开HTTPS规则" }
            val doc = Jsoup.parse(raw, baseUrl)
            var nodes = 0
            doc.traverse { _, depth ->
                nodes++
                require(nodes <= 100_000 && depth <= 256) { "网页节点过多或嵌套过深" }
            }
            doc.outputSettings().prettyPrint(false)
            return doc
        }

        fun fromJson(raw: String): WebsiteRules {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "规则超过16KiB" }
            val json = try { JSONObject(raw) } catch (_: Exception) { throw IllegalArgumentException("规则JSON无效") }
            require(json.opt("version") == null || json.opt("version") == 1) { "规则版本不支持" }
            val keys = setOf("version", "body", "exclude", "items", "title", "link", "date", "summary")
            require(json.keys().asSequence().all { it in keys }) { "规则含不支持字段" }
            fun field(name: String): String {
                val value = json.opt(name) ?: return ""
                require(value is String) { "$name 必须为文本" }
                return value
            }
            return WebsiteRules(field("body"), field("exclude"), field("items"), field("title"), field("link"), field("date"), field("summary"))
                .also { it.validateSelectors() }
        }
    }
}
