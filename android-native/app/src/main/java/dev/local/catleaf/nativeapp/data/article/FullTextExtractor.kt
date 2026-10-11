package dev.local.catleaf.nativeapp.data.article

import dev.local.catleaf.nativeapp.data.network.FeedApi
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import java.nio.charset.Charset

/**
 * 按需全文提取（契约 3.3）。顺序是硬要求：
 * 1. **本机直连**原站取 HTML（[PublicFetch]：仅 HTTPS、逐跳校验、5 MiB 上限、无凭据）；
 * 2. 本机取不到时，让自有后端取原始 HTML（`/api/article/extract?mode=raw`）；
 * 3. JVM 侧的 Readability（Readability4J，Apache-2.0）提取正文；
 * 4. 提取结果仍走 [ArticleSanitizer] 这唯一净化入口。
 *
 * 只有用户点击「获取全文」才会进到这里；任何失败都只是返回原因，
 * 由界面保留 RSS 正文并提供「原网页」入口。
 */
class FullTextExtractor(
    /**
     * 实例原始 HTML 兜底（契约 8.3）：**仅实例范围传入**。
     * null = 本机范围——直连/Readability/净化独立工作，直连失败即返回本机错误与原网页指引，
     * 不要求先连接实例，也绝不为本机伪造 FeedApi。
     */
    private val api: FeedApi?,
    private val publicFetch: PublicFetch = PublicFetch(),
) {

    sealed class Outcome {
        data class Extracted(
            val html: String,
            /** 提取到的正文标题/作者：只在本次会话内显示，不新增数据库字段 */
            val title: String?,
            val byline: String?,
            /** 正文来源：本机直连还是后端兜底，便于验收时区分 */
            val fromBackend: Boolean,
            val thumbnail: String? = null,
        ) : Outcome()

        data class Failed(val reason: String) : Outcome()
    }

    suspend fun extract(
        articleUrl: String,
        fallbackTitle: String?,
        /** 当前正在显示的正文长度：用来判断提取结果是不是比原文还少 */
        currentTextLength: Int = 0,
    ): Outcome = withContext(Dispatchers.IO) {
        // 抓取与 HTML 解析都是阻塞 I/O 与 CPU 工作：调用方可能在主线程（ViewModel），
        // 因此在这里统一切到 IO；设备实测证明放在主线程会直接抛 NetworkOnMainThreadException。
        val source = when (val fetched = fetchHtml(articleUrl)) {
            is HtmlSource.Failed -> return@withContext Outcome.Failed(fetched.reason)
            is HtmlSource.Ok -> fetched
        }

        val article = try {
            Readability4J(articleUrl, source.html).parse()
        } catch (e: Exception) {
            // 解析库对畸形 HTML 会抛异常：算提取失败，不能把异常抛给界面
            return@withContext Outcome.Failed("正文解析失败：${e.message ?: e.javaClass.simpleName}")
        } ?: return@withContext Outcome.Failed("没有从原网页中识别出正文")

        val sanitized = ArticleSanitizer.sanitize(article.content, articleUrl, listOfNotNull(fallbackTitle, article.title))
        if (sanitized.isEmpty) return@withContext Outcome.Failed("提取到的正文为空")

        // ponytail: 固定下限 + “不比现有正文更少”的启发式，挡住把导航/提示条当正文的页面；
        // 若真遇到长期被误判的站点，再按域名放行或改成可配置阈值。
        if (sanitized.textLength < MIN_USEFUL_TEXT && sanitized.textLength <= currentTextLength) {
            return@withContext Outcome.Failed("提取到的正文过短（${sanitized.textLength} 字）")
        }

        Outcome.Extracted(
            html = sanitized.html,
            title = article.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            byline = article.byline?.replace(Regex("^发邮件[\\s\\u00a0]+"), "")?.trim()?.takeIf { it.isNotBlank() },
            fromBackend = source.fromBackend,
            thumbnail = coverFromHtml(source.html, articleUrl),
        )
    }

    private sealed class HtmlSource {
        class Ok(val html: String, val fromBackend: Boolean) : HtmlSource()

        class Failed(val reason: String) : HtmlSource()
    }

    private suspend fun fetchHtml(articleUrl: String): HtmlSource {
        val direct = publicFetch.fetch(articleUrl, PublicFetch.MAX_BYTES)
        direct.getOrNull()?.let { fetched ->
            return HtmlSource.Ok(
                absolutizeUrls(decodeHtml(fetched.bytes, fetched.contentType), articleUrl),
                fromBackend = false,
            )
        }

        val directReason = describe(direct)
        val viaBackend = api?.fetchRawHtml(articleUrl)
            ?: return HtmlSource.Failed("本机抓取失败（$directReason）；可用「原网页」在浏览器打开")
        viaBackend.getOrNull()?.takeIf { it.isNotBlank() }?.let { html ->
            return HtmlSource.Ok(absolutizeUrls(html, articleUrl), fromBackend = true)
        }

        val backendReason = viaBackend.exceptionOrNull()?.message ?: "后端返回空响应"
        return HtmlSource.Failed("本机抓取失败（$directReason）；后端兜底也失败（$backendReason）")
    }

    /**
     * 提取前先把相对地址按**文章地址**转成绝对地址。
     * 提取库自己也有一套解析，但实测会丢掉端口（`https://host:8443/a` → `https://host/…`），
     * 与其在事后辨认它算错的地址，不如让它拿到的 HTML 里已经没有相对地址。
     * 净化入口仍会对结果再复核一遍（见 ArticleSanitizer）。
     */
    private fun absolutizeUrls(html: String, articleUrl: String): String {
        val doc = org.jsoup.Jsoup.parse(html, articleUrl)
        // 真实站点常用懒加载：先补出图片地址，否则提取库拿到的可能是空 src
        ArticleSanitizer.fillImageSources(doc)
        for (attribute in listOf("href", "src", "cite", "poster")) {
            doc.select("[$attribute]").forEach { element ->
                val absolute = element.absUrl(attribute)
                if (absolute.isNotEmpty()) element.attr(attribute, absolute)
            }
        }
        return doc.html()
    }

    private fun describe(result: Result<PublicFetch.Fetched>): String =
        when (val error = result.exceptionOrNull()) {
            is PublicFetch.FetchFailure -> error.message ?: "抓取失败"
            null -> "抓取失败"
            else -> error.message ?: error.javaClass.simpleName
        }

    companion object {
        internal fun coverFromHtml(html: String, articleUrl: String): String? {
            val doc = org.jsoup.Jsoup.parse(html, articleUrl)
            return listOf("meta[property=og:image]", "meta[name=twitter:image]", "meta[property=twitter:image]", "link[rel=image_src]")
                .firstNotNullOfOrNull { selector ->
                    doc.select(selector).firstNotNullOfOrNull { element ->
                        val raw = element.attr(if (element.tagName() == "link") "href" else "content")
                        val absolute = ArticleSanitizer.absoluteUrl(raw, articleUrl)
                        dev.local.catleaf.nativeapp.data.network.EndpointPolicy.requirePublicFetchTarget(absolute).getOrNull()
                    }
                }
        }

        /** 嗅探用的头部字节数：`<meta charset>` 通常在最前面 */
        private const val SNIFF_BYTES = 4096

        /** 低于这个字数且不比现有正文多，就不当作“全文”（见 extract 里的说明） */
        internal const val MIN_USEFUL_TEXT = 200

        private val CHARSET_IN_META = Regex(
            """charset\s*=\s*["']?\s*([A-Za-z0-9_\-]+)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 决定 HTML 的字符集：先看响应头，再看文档里的 meta，最后按 UTF-8。
         * 中文源里 GBK/GB2312 很常见，猜错会整页乱码，所以嗅探放在这里而不是交给库。
         */
        internal fun decodeHtml(bytes: ByteArray, contentType: String?): String {
            val fromHeader = contentType?.let { CHARSET_IN_META.find(it)?.groupValues?.get(1) }
            val fromMeta = if (fromHeader == null) {
                // 头部按 Latin-1 解码再找 charset：meta 标签本身一定是 ASCII
                val head = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.ISO_8859_1)
                CHARSET_IN_META.find(head)?.groupValues?.get(1)
            } else {
                null
            }
            val charset = sequenceOf(fromHeader, fromMeta)
                .filterNotNull()
                .map { name -> runCatching { Charset.forName(name.trim()) }.getOrNull() }
                .firstOrNull()
                ?: Charsets.UTF_8
            return String(bytes, charset)
        }
    }
}
