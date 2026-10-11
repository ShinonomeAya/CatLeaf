package dev.local.catleaf.nativeapp.data.rss

import android.util.Xml
import dev.local.catleaf.nativeapp.data.article.ArticleIdentity
import dev.local.catleaf.nativeapp.data.article.DateNormalizer
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import org.xmlpull.v1.XmlPullParser

/**
 * RSS 2.0 / Atom 解析（契约 3.2 的 HistoryItem 字段形状）。
 *
 * 与 React 参考实现（`services/rssService.ts` 的 parseXML）行为对齐，但一处**刻意不同**：
 * 参考实现把 guid 回退成 link，而契约 4.2 要求两个键分开匹配（先 GUID 再 URL），
 * 所以这里保持 guid 与 link 独立；两者都缺由调用方计入“缺少标识未保存”。
 *
 * 命名空间由 XML 解析器维护作用域；字段按局部名匹配
 * （`content:encoded`、`media:content`、`media:thumbnail`），同时接受无前缀写法。
 * 文本一律用 [readElementText] 取整个子树文本——它能正确处理 `<author><name>X</name></author>`
 * 这种带子标签的情况，而 `nextText()` 遇到子标签会抛错。
 */
object FeedParser {

    data class ParsedItem(
        val title: String,
        val link: String?,
        val guid: String?,
        /** 已规范化为 ISO-8601 UTC；无法解析为 null（进“日期未知”） */
        val publishedAt: String?,
        val author: String?,
        val description: String?,
        val content: String?,
        /** 图片地址：media:thumbnail / 安全的图片 enclosure；只接受 https 无凭据（T22 F02） */
        val thumbnail: String?,
    )

    data class ParsedFeed(
        val title: String,
        val description: String,
        val image: String,
        val items: List<ParsedItem>,
        /** 缺少任何标识（GUID 与 link 都没有）的条目数，界面要显示“X 条缺少标识未保存” */
        val unidentifiedCount: Int,
    )

    sealed class FeedParseError(message: String) : Exception(message) {
        /** 拿到 HTML 而不是 XML（多为被拦截或反爬页）：必须明确报错，不能当成空源 */
        class HtmlInsteadOfXml : FeedParseError("收到的是 HTML 而不是 XML（可能被上游拦截）")
        class Malformed(detail: String) : FeedParseError("XML 解析失败：$detail")
        class NotAFeed : FeedParseError("不是有效的 RSS/Atom 结构")
    }

    private const val TEXT = XmlPullParser.TEXT
    private const val CDSECT = XmlPullParser.CDSECT

    /** Atom content type="xhtml" 子树的合法命名空间（V07：只有解析到它才去元素前缀） */
    private const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"

    fun parse(xml: String, baseUrl: String? = null): Result<ParsedFeed> {
        val head = xml.take(1024).trim().lowercase()
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            return Result.failure(FeedParseError.HtmlInsteadOfXml())
        }

        val items = mutableListOf<ParsedItem>()
        var unidentified = 0
        var feedTitle = ""
        var feedDescription = ""
        var feedImage = ""
        var sawFeedElement = false
        // 根元素是否正常闭合：解析器容忍在 EOF 处截断而**不报错**，
        // 那样会把只下载了一半的源当成成功（条目静默变少）。这里显式判定。
        var closedRoot = false
        var current: ItemBuilder? = null
        var itemDepth = 0

        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(xml.reader())
            }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    // 按局部名派发，保留 RSS 扩展字段与无前缀写法。
                    val name = parser.name?.lowercase().orEmpty().substringAfterLast(':')
                    val item = current
                    when {
                        name == "channel" || name == "feed" -> sawFeedElement = true

                        name == "item" || name == "entry" -> {
                            current = ItemBuilder()
                            itemDepth = parser.depth
                        }

                        // ── 条目内的字段 ──
                        item != null && (name == "title") -> item.title = readElementText(parser).trim()
                        item != null && (name == "link") -> {
                            // Atom 里可能出现 <link rel="self">（指向 feed 自身）：只认 alternate 或无 rel 的
                            val rel = attribute(parser, "rel")
                            val href = attribute(parser, "href")
                            val value = href ?: readElementText(parser).trim()
                            if (item.linkRaw.isNullOrEmpty() && (rel == null || rel.equals("alternate", ignoreCase = true))) {
                                item.linkRaw = value
                            }
                        }
                        item != null && (name == "guid" || name == "id") ->
                            if (item.guidRaw.isNullOrEmpty()) item.guidRaw = readElementText(parser).trim()
                        item != null && (name == "pubdate" || name == "published" || name == "updated" || name == "date") ->
                            if (item.publishedRaw.isNullOrEmpty()) item.publishedRaw = readElementText(parser).trim()
                        item != null && (name == "creator" || name == "author") ->
                            if (item.author.isNullOrEmpty()) item.author = readElementText(parser).trim()
                        item != null && (name == "description" || name == "summary") -> {
                            val type = attribute(parser, "type")?.trim()?.lowercase()
                            val value = if (name == "summary" && type == "xhtml") readElementMarkup(parser) else readElementText(parser)
                            if (item.description.isNullOrEmpty()) item.description = value.trim()
                        }
                        item != null && (name == "encoded" || name == "content") -> {
                            // media:content 带 url 属性、Atom 的 <content> 是文本：用有无 url 区分
                            val mediaUrl = attribute(parser, "url")
                            if (mediaUrl != null) {
                                if (item.thumbnail.isNullOrEmpty() && looksLikeImage(mediaUrl)) item.thumbnail = mediaUrl
                            } else if (item.content.isNullOrEmpty()) {
                                // Atom 的 <content type="xhtml"> 里嵌的是真 XML 子树（p/img/a/table）：
                                // 序列化保留标签，交唯一净化入口处理（T22 F19）。
                                // text/html 仍是纯文本读取——html 的转义标签会由解析器还原成 markup 文本。
                                val type = attribute(parser, "type")?.trim()?.lowercase()
                                item.content = if (type == "xhtml") {
                                    readElementMarkup(parser).trim()
                                } else {
                                    readElementText(parser).trim()
                                }
                            }
                        }
                        item != null && name == "image" && parser.depth == itemDepth + 1 -> {
                            val image = attribute(parser, "url") ?: readElementText(parser).trim()
                            if (item.thumbnail.isNullOrEmpty()) item.thumbnail = image
                        }
                        item != null && name == "thumbnail" ->
                            if (item.thumbnail.isNullOrEmpty()) item.thumbnail = attribute(parser, "url")
                        item != null && name == "enclosure" -> {
                            // 图片类 enclosure 映射为封面（T22 F02：Cloudflare 的封面只在 image/png enclosure 里）。
                            // 音频/视频附件不能当图片：有 type 就必须是 image/*；无 type 才回退看扩展名。
                            val url = attribute(parser, "url")
                            val type = attribute(parser, "type").orEmpty().substringBefore(';').trim().lowercase()
                            val isImage = type.startsWith("image/") ||
                                (type.isEmpty() && url != null && looksLikeImage(url))
                            if (item.thumbnail.isNullOrEmpty() && url != null && isImage) item.thumbnail = url
                        }

                        // ── 源级字段 ──
                        item == null && name == "title" -> if (feedTitle.isEmpty()) feedTitle = readElementText(parser).trim()
                        item == null && (name == "subtitle" || name == "description") ->
                            if (feedDescription.isEmpty()) feedDescription = readElementText(parser).trim()
                        item == null && (name == "icon" || name == "logo") ->
                            if (feedImage.isEmpty()) feedImage = readElementText(parser).trim()
                        item == null && name == "url" ->
                            if (feedImage.isEmpty()) feedImage = readElementText(parser).trim()
                    }
                }

                if (event == XmlPullParser.END_TAG) {
                    val name = parser.name?.lowercase().orEmpty().substringAfterLast(':')
                    if (name == "rss" || name == "feed" || name == "rdf") closedRoot = true
                    if (name == "item" || name == "entry") {
                        current?.let { builder ->
                            val built = builder.build(baseUrl)
                            if (built.guid == null && built.link == null) unidentified++ else items += built
                        }
                        current = null
                    }
                }

                event = parser.next()
            }
        } catch (e: Exception) {
            val raw = e.message ?: e.javaClass.simpleName
            return Result.failure(FeedParseError.Malformed(raw.substringBefore(" (position:").trim()))
        }

        if (!sawFeedElement) return Result.failure(FeedParseError.NotAFeed())
        if (!closedRoot) return Result.failure(FeedParseError.Malformed("文档在结束前被截断"))

        return Result.success(
            ParsedFeed(
                title = feedTitle.ifBlank { "未命名源" },
                description = feedDescription,
                image = feedImage,
                items = items,
                unidentifiedCount = unidentified,
            ),
        )
    }

    /**
     * 读取当前元素**整个子树**的文本，并把解析器停在它的 END_TAG 上。
     * 用深度计数而不是 `nextText()`：后者遇到子标签会抛错，而 Atom 的
     * `<author><name>…</name></author>`、`content:encoded` 里嵌 CDATA 都很常见。
     */
    private fun readElementText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                TEXT, CDSECT -> sb.append(parser.text ?: "")
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    /**
     * 把当前元素的子树**序列化回 HTML 片段**，并把解析器停在它的 END_TAG 上。
     * 只给 Atom `content type="xhtml"` 用：那里面的 `<div xmlns=…>` 子树是真标记，
     * [readElementText] 会把标签剥掉只剩文字（T22 F19）。
     * 产物与其他正文一样走 [dev.local.catleaf.nativeapp.data.article.ArticleSanitizer]，
     * 这里不假设内容可信：文本与属性都重新转义，命名空间声明属性丢弃。
     */
    private fun readElementMarkup(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val name = htmlName(parser)
                    sb.append('<').append(name).append(openAttributes(parser)).append('>')
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth > 0) sb.append("</").append(htmlName(parser)).append('>')
                }
                TEXT, CDSECT -> sb.append(escapeMarkupText(parser.text ?: ""))
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    /** 只有真正属于 XHTML 的元素进入 HTML 白名单；其他命名空间交净化器剥标签。 */
    private fun htmlName(parser: XmlPullParser): String =
        if (parser.namespace == XHTML_NAMESPACE) parser.name.lowercase()
        else "nonxhtml:${parser.name.lowercase()}"

    private fun openAttributes(parser: XmlPullParser): String {
        val sb = StringBuilder()
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributePrefix(i)?.takeIf { it.isNotEmpty() }
                ?.let { "$it:${parser.getAttributeName(i)}" } ?: parser.getAttributeName(i)
            sb.append(' ').append(name).append("=\"")
                .append(escapeMarkupAttr(parser.getAttributeValue(i)))
                .append('"')
        }
        return sb.toString()
    }

    private fun escapeMarkupText(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeMarkupAttr(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")

    private fun attribute(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun looksLikeImage(url: String): Boolean =
        Regex("\\.(jpg|jpeg|png|gif|webp)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private class ItemBuilder {
        var title: String = ""
        var guidRaw: String? = null
        var linkRaw: String? = null
        var publishedRaw: String? = null
        var author: String? = null
        var description: String? = null
        var content: String? = null
        var thumbnail: String? = null

        fun build(baseUrl: String?): ParsedItem = ParsedItem(
            title = title.ifBlank { "(无标题)" },
            link = ArticleIdentity.normalizeLink(linkRaw),
            guid = ArticleIdentity.normalizeGuid(guidRaw),
            publishedAt = DateNormalizer.toIsoUtc(publishedRaw),
            author = author?.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            // 只把 content:encoded/content 当正文。曾经在这里做“没有正文就用摘要”的回退，
            // 结果是只有摘要的源在详情页被标成“RSS 正文”，用户分不清自己看的是全文还是摘要；
            // 契约 P04 要求“没有正文则展示摘要和获取全文”，所以摘要留在 description，
            // 由阅读详情按真实来源显示（T12 修正）。
            content = content?.takeIf { it.isNotBlank() },
            // 统一在出口做地址核验：media:thumbnail / media:content / 图片 enclosure 三条来源同一规则，
            // 非 https、带凭据、内网目标一律不要（图片抓取本来也会在拦截层被拒，这里不留脏数据）
            thumbnail = EndpointPolicy.requirePublicFetchTarget(
                dev.local.catleaf.nativeapp.data.article.ArticleSanitizer.absoluteUrl(
                    dev.local.catleaf.nativeapp.data.article.ArticleSanitizer.wechatImageUrl(thumbnail) ?: thumbnail, baseUrl ?: linkRaw),
            ).getOrNull(),
        )
    }
}
