package dev.local.catleaf.nativeapp.data.article

import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.net.URI

/**
 * 正文净化的**唯一入口**（契约第 7 节）：RSS 正文与提取全文都必须经过这里，
 * 不允许任何调用方自己拼 HTML 给 WebView。React 参考侧对应 DOMPurify。
 *
 * 净化分三步，每步都可以单独验证：
 * 1. 连同内容删除危险容器（script/style/iframe/form/svg/media/…）——脚本与样式文本
 *    绝不能作为正文文本漏出来；
 * 2. jsoup Cleaner 按 allowlist 过滤标签与属性（事件属性 `on*` 不在 allowlist 内，
 *    因此被丢弃）；样式一律不保留，排版由 App 自己的模板提供；
 * 3. 逐条复核 URL 属性：只留 http/https、且无凭据的地址，其余（javascript:、data:、
 *    file:、intent:、相对地址）一律删除属性本身。这一步不依赖库内部对协议的判断，
 *    因此可以断言；相对地址按**已验证的文章地址**转成绝对地址。
 */
object ArticleSanitizer {

    data class Sanitized(
        val html: String,
        /** 净化后可见文本长度：用于判断“正文是否真的拿到了” */
        val textLength: Int,
        val imageCount: Int,
        /** 被删除的 URL 属性数（危险协议或无法解析），供验收统计 */
        val droppedUrlAttributes: Int,
        /**
         * 纯文本（保留段落换行）：翻译的输入用它。
         * 契约 5.2 要求「输入选当前已展示正文的纯文本」，所以只能从**净化后**的 HTML 取，
         * 不能拿原始 HTML——否则会把脚本/注释一起发给供应商。
         */
        val plainText: String = "",
    ) {
        val isEmpty: Boolean get() = textLength == 0 && imageCount == 0
    }

    private val ALLOWED_TAGS = arrayOf(
        "a", "abbr", "b", "blockquote", "br", "caption", "cite", "code", "dd", "del", "div",
        "dl", "dt", "em", "figcaption", "figure", "h1", "h2", "h3", "h4", "h5", "h6", "hr",
        "i", "img", "ins", "kbd", "li", "mark", "ol", "p", "pre", "q", "s", "samp", "small",
        "span", "strike", "strong", "sub", "sup", "table", "tbody", "td", "tfoot", "th",
        "thead", "tr", "u", "ul",
    )

    /** 连同内容一起删除：留着会在正文里渲染出脚本文本或嵌入内容。
     *  注意 picture/source 不在这里——它们只解包（见 [DROP_ELEMENT_ONLY]），
     *  否则 `<picture>` 里的 `<img>` 会被一起删掉。
     */
    private val DROP_WITH_CONTENT = arrayOf(
        "script", "style", "iframe", "frame", "frameset", "object", "embed", "applet", "noscript",
        "template", "svg", "math", "canvas", "audio", "video", "track",
        "link", "meta", "base", "head", "title", "portal", "dialog",
    )

    /**
     * 只删除元素本身、保留其中文字：表单控件在 RSS 正文里偶尔出现，
     * 整块丢掉会把正文一起吃掉；控件本身没有可读内容，删掉即可。
     */
    private val DROP_ELEMENT_ONLY = arrayOf(
        "form", "input", "button", "select", "option", "optgroup", "textarea", "fieldset", "datalist",
        // picture/source 只解包：要显示的是里面的 <img>，整块删掉会让图片彻底消失
        "picture", "source",
    )

    /** 懒加载图片的真实地址常放在这些属性里（见 [fillImageSources]） */
    private val LAZY_SOURCE_ATTRIBUTES = arrayOf(
        "data-src", "data-original", "data-lazy-src", "data-actualsrc", "data-echo", "data-url",
    )

    private val URL_ATTRIBUTES = arrayOf("href", "src", "cite")

    /** 一个属性里塞多个地址的写法：allowlist 里没有这些属性，出现即删除（不逐条改写） */
    private val MULTI_URL_ATTRIBUTES = arrayOf("srcset", "imagesrcset", "poster", "formaction")

    private val safelist: Safelist = Safelist()
        .addTags(*ALLOWED_TAGS)
        .addAttributes("a", "href", "title")
        .addAttributes("img", "src", "alt", "title", "width", "height")
        .addAttributes("blockquote", "cite")
        .addAttributes("q", "cite")
        .addAttributes("ol", "start")
        .addAttributes("td", "colspan", "rowspan")
        .addAttributes("th", "colspan", "rowspan", "scope")
        // 协议白名单：仅 http/https。第 3 步还会独立复核一遍，不依赖这里的判定。
        .addProtocols("a", "href", "http", "https")
        .addProtocols("img", "src", "http", "https")
        .addProtocols("blockquote", "cite", "http", "https")
        .addProtocols("q", "cite", "http", "https")
        // 相对地址由这里显式按已验证的文章地址解析，而不是依赖 jsoup 的隐式行为
        .preserveRelativeLinks(true)

    private val cleaner = Cleaner(safelist)

    /**
     * @param rawHtml RSS 正文、提取全文或摘要；可为 null（无正文兜底）
     * @param baseUrl 已验证的文章地址；为空时相对地址一律删除，不合成假地址
     */
    fun sanitize(rawHtml: String?, baseUrl: String?, titles: List<String> = emptyList(), coverUrl: String? = null): Sanitized {
        val base = baseUrl?.trim()?.takeIf { EndpointPolicy.isSafePublicUrl(it) }
        if (rawHtml.isNullOrBlank()) return Sanitized("", 0, 0, 0)

        val dirty = Jsoup.parse(rawHtml, base.orEmpty())
        dirty.outputSettings().prettyPrint(false)
        dirty.select(DROP_WITH_CONTENT.joinToString(",")).remove()
        // 先补图片地址，再解包：<source srcset> 一旦解包就没有地方能取到真实地址了
        fillImageSources(dirty)
        restoreWordPressEmoji(dirty)
        // 只解包，不删内容：unwrap 会把子节点提升到原位
        dirty.select(DROP_ELEMENT_ONLY.joinToString(",")).unwrap()

        val clean = cleaner.clean(dirty)
        clean.outputSettings().prettyPrint(false)
        removeLeadingTitle(clean.body(), titles)

        var dropped = 0
        clean.body().select("*").forEach { element ->
            dropped += rescueUrls(element, base)
        }
        val firstImage = clean.body().selectFirst("img[src]")
        if (coverUrl != null && firstImage?.attr("src") == coverUrl) {
            val before = clean.body().clone()
            val image = before.selectFirst("img[src]")!!
            // Keep captions, and never remove a matching image after normal body text.
            val leading = before.select("*").takeWhile { it != image }
                .none { it.ownText().isNotBlank() && it.tagName() != "figcaption" && it.parents().none { p -> p.tagName() == "figcaption" } }
            if (leading) firstImage.remove()
        }

        return Sanitized(
            html = clean.body().html(),
            textLength = clean.body().text().trim().length,
            imageCount = clean.body().select("img").size,
            droppedUrlAttributes = dropped,
            // 合并连续空行：原文里的排版空行对翻译没意义，还会白占上限
            plainText = clean.body().wholeText().replace(Regex("\\n{3,}"), "\n\n").trim(),
        )
    }

    private fun normalizedTitle(value: String): String = org.jsoup.parser.Parser.unescapeEntities(value, false)
        .map { if (it.isWhitespace() || Character.isSpaceChar(it)) ' ' else it }.joinToString("")
        .replace(Regex(" +"), " ").trim()

    /** RSS replaces these Unicode characters with 72px images; old caches no longer have class/style. */
    private fun restoreWordPressEmoji(doc: org.jsoup.nodes.Document) {
        doc.select("img[src][alt]").forEach { img ->
            val url = runCatching { URI(img.attr("src")) }.getOrNull() ?: return@forEach
            if (url.scheme != "https" || url.host != "s.w.org" || url.userInfo != null ||
                url.port !in listOf(-1, 443) || url.query != null || url.fragment != null) return@forEach
            val name = Regex("^/images/core/emoji/[0-9]+(?:\\.[0-9]+)*/72x72/([0-9a-f]+(?:-[0-9a-f]+)*)\\.png$")
                .matchEntire(url.path)?.groupValues?.get(1) ?: return@forEach
            val expected = name.split('-').map { it.toIntOrNull(16) ?: return@forEach }.filter { it != 0xfe0f }
            val alt = img.attr("alt")
            if (expected.isNotEmpty() && alt.codePoints().toArray().filter { it != 0xfe0f } == expected) {
                img.replaceWith(TextNode(alt))
            }
        }
    }

    /** Only the first text block can be a duplicate title; images and their captions stay intact. */
    private fun removeLeadingTitle(body: Element, titles: List<String>) {
        val heading = body.select("*").firstOrNull { element ->
            element.tagName() != "figcaption" && element.parents().none { it.tagName() == "figcaption" } &&
                (element.tagName() in listOf("h1", "h2", "h3", "h4", "h5", "h6") || element.ownText().isNotBlank())
        } ?: return
        if (heading.tagName() !in listOf("h1", "h2", "h3", "h4", "h5", "h6")) return
        val text = normalizedTitle(heading.text())
        if (text.isNotEmpty() && titles.any { normalizedTitle(it) == text }) heading.remove()
    }

    /**
     * 给懒加载图片补出 `src`。
     *
     * 大量站点（Ghost/Astro/WordPress 主题）只在 `srcset` 或 `data-src` 之类的属性里放真实地址，
     * `src` 要么为空、要么是内联的占位图；`srcset`/`data-*` 又都不在 allowlist 内，
     * 不补的话正文里就只剩一个空图片框。补出的地址同样要过后面的地址复核。
     *
     * `<picture>` 展开后（见 [DROP_ELEMENT_ONLY]）内部的 `<img>` 通常没有 `src`，
     * 真实候选在兄弟 `<source srcset>` 里，所以先处理 picture 再处理 img。
     */
    internal fun fillImageSources(doc: org.jsoup.nodes.Document) {
        doc.select("picture").forEach { picture ->
            val img = picture.selectFirst("img") ?: return@forEach
            if (img.attr("src").isNotBlank() && !isInlineImage(img.attr("src"))) return@forEach
            val candidate = picture.select("source").firstNotNullOfOrNull { firstSrcsetCandidate(it.attr("srcset")) }
            if (candidate != null) img.attr("src", candidate)
        }

        doc.select("img").forEach { img ->
            wechatImageUrl(img.attr("src"))?.let { img.attr("src", it) }
            if (img.attr("src").isNotBlank() && !isInlineImage(img.attr("src"))) return@forEach
            val candidate = LAZY_SOURCE_ATTRIBUTES.firstNotNullOfOrNull { name ->
                img.attr(name).takeIf { it.isNotBlank() && !isInlineImage(it) }
            } ?: firstSrcsetCandidate(img.attr("srcset").ifBlank { img.attr("data-srcset") })
            ?: return@forEach
            img.attr("src", candidate)
        }
    }

    /** Anyfeeder's public WeChat RSS wraps qpic images in an HTTP-only proxy. */
    internal fun wechatImageUrl(raw: String?): String? {
        var uri = runCatching { URI(raw?.trim().orEmpty()) }.getOrNull() ?: return null
        if (uri.userInfo != null || uri.scheme !in listOf("http", "https")) return null
        if (uri.host == "img2.jintiankansha.me" && uri.path == "/get" && uri.port == -1) {
            val target = uri.rawQuery?.takeIf { it.startsWith("src=") }?.removePrefix("src=") ?: return null
            uri = runCatching { URI(if (target.startsWith("http://") || target.startsWith("https://")) target else java.net.URLDecoder.decode(target, "UTF-8")) }.getOrNull() ?: return null
        }
        if (uri.host != "mmbiz.qpic.cn" || uri.userInfo != null || uri.port != -1 || uri.scheme !in listOf("http", "https")) return null
        if (!uri.path.startsWith("/mmbiz_") && !uri.path.startsWith("/sz_mmbiz_")) return null
        val https = "https:" + uri.toASCIIString().substringAfter(':', "")
        return EndpointPolicy.requirePublicFetchTarget(https).getOrNull()
    }

    /** `data:image/...` 这类内联图不是可抓取的地址（也不在 allowlist 内），视为没有 src */
    private fun isInlineImage(value: String): Boolean = value.trimStart().startsWith("data:", ignoreCase = true)

    /** 从 `srcset` 里取一个候选地址；取最后一个（描述符通常由小到大），质量优先 */
    private fun firstSrcsetCandidate(srcset: String): String? =
        srcset.split(',')
            .map { it.trim().substringBefore(' ') }
            .lastOrNull { it.isNotBlank() && !isInlineImage(it) }

    /** 删除危险/无法解析的 URL 属性与全部事件属性；返回删除的属性数 */
    private fun rescueUrls(element: Element, base: String?): Int {
        var removed = 0
        val attributes = element.attributes()
        for (attribute in attributes.asList().toList()) {
            val key = attribute.key.lowercase()
            if (key.startsWith("on")) {
                element.removeAttr(attribute.key)
                removed++
                continue
            }
            if (key in MULTI_URL_ATTRIBUTES) {
                element.removeAttr(attribute.key)
                removed++
                continue
            }
            if (key !in URL_ATTRIBUTES) continue
            val absolute = absoluteUrl(attribute.value, base)
            if (absolute == null) {
                element.removeAttr(attribute.key)
                removed++
            } else {
                element.attr(attribute.key, absolute)
            }
        }
        return removed
    }

    /** 只接受规范化后的 http/https 绝对地址；其余（含 javascript:、data:、相对地址）返回 null */
    internal fun absoluteUrl(raw: String?, base: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // 控制字符与空白会被浏览器忽略，"java\tscript:"/"java\nscript:" 这类写法必须先去掉再判断协议
        val stripped = value.filterNot { it.isISOControl() || it.isWhitespace() }
        val uri = runCatching {
            if (base != null) URI(base).resolve(stripped) else URI(stripped)
        }.getOrNull() ?: return null
        if (!uri.isAbsolute) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.userInfo != null) return null
        if (uri.host.isNullOrBlank()) return null
        return uri.toASCIIString()
    }
}
