package dev.local.catleaf.nativeapp.data.opml

import android.util.Xml
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * OPML 读取与导出（契约 §6）。
 *
 * 读取在**本机**完成：先解析成预览，用户确认后才逐条写入服务端。
 * 解析用平台自带的 [XmlPullParser]（`android.util.Xml`），不引入文件选择或 XML 大库。
 *
 * 拒绝项在写入前处理：超 2 MiB、含 DOCTYPE/实体声明、解析错误、非 HTTPS 的 `xmlUrl`。
 * 多级 outline 拼成「父 / 子」一级分组；分组超 80 字符**标记出来**而不是悄悄截断。
 */
object Opml {

    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ENTRIES = 2000
    const val MAX_BATCH_ENTRIES = 200
    const val MAX_GROUP_LENGTH = 80
    const val MAX_NAME_LENGTH = 120
    const val MAX_URL_LENGTH = 4096

    /** 一条待导入的订阅；[invalidReason] 非空表示该条在预览中不可勾选 */
    data class Entry(
        val title: String,
        val xmlUrl: String,
        val htmlUrl: String? = null,
        val group: String = "",
        val invalidReason: String? = null,
        val groupTooLong: Boolean = false,
        /** 与远端已有源或文件内前一条重复 */
        val duplicateOf: String? = null,
    ) {
        val selectable: Boolean get() = invalidReason == null && duplicateOf == null && !groupTooLong
    }

    data class Document(
        val title: String,
        val entries: List<Entry>,
        /** 文件里被判为不可导入的条数（无效或重复），用于预览汇总 */
        val invalidCount: Int,
    )

    sealed class OpmlError(message: String) : Exception(message) {
        class TooLarge(bytes: Int) :
            OpmlError("文件超过 ${MAX_BYTES / 1024 / 1024} MiB（实际 $bytes 字节）")
        class DoctypeForbidden : OpmlError("文件包含 DOCTYPE 或实体声明，已拒绝解析")
        class NotUtf8 : OpmlError("文件编码无效，仅支持 UTF-8 或明确字节序的 UTF-16")
        class Malformed(detail: String) : OpmlError("XML 解析失败：$detail")
        class TooManyEntries(count: Int) : OpmlError("订阅条目超过上限 $MAX_ENTRIES（实际 $count）")
    }

    /** 不信任文件提供者的长度；最多读取预算加一字节即停止。 */
    fun readBytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - out.size()))
            if (count < 0) return out.toByteArray()
            if (count == 0) continue
            if (out.size() + count > MAX_BYTES) throw OpmlError.TooLarge(out.size() + count)
            out.write(buffer, 0, count)
        }
    }

    private fun decode(bytes: ByteArray): String {
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size &&
            prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
        val (charset, offset) = when {
            starts(0xef, 0xbb, 0xbf) -> StandardCharsets.UTF_8 to 3
            starts(0xff, 0xfe) -> StandardCharsets.UTF_16LE to 2
            starts(0xfe, 0xff) -> StandardCharsets.UTF_16BE to 2
            starts(0x3c, 0, 0x3f, 0) -> StandardCharsets.UTF_16LE to 0
            starts(0, 0x3c, 0, 0x3f) -> StandardCharsets.UTF_16BE to 0
            else -> StandardCharsets.UTF_8 to 0
        }
        val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        val declared = Regex("^<\\?xml\\s+[^?]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.uppercase()
        val allowed = if (charset == StandardCharsets.UTF_8) setOf("UTF-8")
            else setOf("UTF-16", charset.name().uppercase())
        if (declared != null && declared !in allowed) throw OpmlError.NotUtf8()
        if (charset != StandardCharsets.UTF_8 && offset == 0 && declared == null) throw OpmlError.NotUtf8()
        return text
    }

    /**
     * 解析 OPML。任何拒绝都在返回前发生，调用方因此在“写入前”就能给出结论。
     *
     * @param knownUrls 远端已有源的 URL（规范化后比较），用于标记重复
     */
    fun parse(bytes: ByteArray, knownUrls: List<String> = emptyList()): Result<Document> {
        if (bytes.size > MAX_BYTES) return Result.failure(OpmlError.TooLarge(bytes.size))

        val text = try {
            decode(bytes)
        } catch (e: Exception) {
            return Result.failure(OpmlError.NotUtf8())
        }

        // 先做文本级检查：实体声明可能引出外部实体，解析前就拒绝最稳妥
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) {
            return Result.failure(OpmlError.DoctypeForbidden())
        }

        val known = knownUrls.mapNotNull { EndpointPolicy.normalizeFeedUrl(it) }.toMutableSet()
        val seenInFile = mutableSetOf<String>()
        val entries = mutableListOf<Entry>()
        var docTitle = ""

        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(text.reader())
            }

            // 记录 outline 的祖先标题，用于拼「父 / 子」分组。
            // 每个 outline 都要成对入栈/出栈：条目节点压 null，分组节点压标题。
            // （只给分组节点入栈会让条目节点的 END_TAG 误弹掉外层分组。）
            val outlineStack = mutableListOf<String?>()
            // 分组节点是否出现过子节点：没有子节点且没有 xmlUrl 的 outline，
            // 按原型 10 应作为“地址缺失、无法导入”的条目列出，而不是当成空分组悄悄丢掉。
            val groupHasChild = mutableListOf<Boolean>()
            var rootSeen = false
            var bodySeen = false
            var inBody = false
            var inHead = false

            fun append(entry: Entry) {
                if (entries.size == MAX_ENTRIES) throw OpmlError.TooManyEntries(entries.size + 1)
                entries += entry
            }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name?.lowercase()
                        if (parser.depth == 1) {
                            if (rootSeen || tag != "opml") throw OpmlError.Malformed("文件不是 OPML")
                            rootSeen = true
                        }
                        if (parser.depth == 2 && tag == "head") inHead = true
                        if (parser.depth == 2 && tag == "body") {
                            if (bodySeen) throw OpmlError.Malformed("重复的 body")
                            bodySeen = true
                            inBody = true
                        }
                        if (tag == "title" && inHead && parser.depth == 3 && docTitle.isEmpty()) {
                            docTitle = parser.nextText().trim()
                            event = parser.eventType
                            continue
                        }
                        if (tag == "outline") {
                            if (!inBody || outlineStack.size >= 64) throw OpmlError.Malformed("outline 结构或层级无效")
                            val label = attr(parser, "title") ?: attr(parser, "text") ?: ""
                            val xmlUrl = attr(parser, "xmlurl")
                            // 任何子节点出现时，把当前最近的分组标记为“有子节点”
                            groupHasChild.indices.lastOrNull()?.let { groupHasChild[it] = true }
                            if (xmlUrl.isNullOrBlank()) {
                                outlineStack += label
                                groupHasChild += false
                            } else {
                                outlineStack += null
                                groupHasChild += true
                                val groupPath = outlineStack.filterNotNull()
                                append(buildEntry(label, xmlUrl, attr(parser, "htmlurl"), groupPath, attr(parser, "type")))
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.depth == 2 && parser.name.equals("body", ignoreCase = true)) inBody = false
                        if (parser.depth == 2 && parser.name.equals("head", ignoreCase = true)) inHead = false
                        if (parser.name?.lowercase() == "outline" && outlineStack.isNotEmpty()) {
                            val label = outlineStack.removeAt(outlineStack.lastIndex)
                            val hadChild = groupHasChild.removeAt(groupHasChild.lastIndex)
                            // 无子节点、无 xmlUrl 但有名字 → 视为“地址缺失”的无效条目
                            if (label != null && !hadChild && label.isNotBlank()) {
                                append(Entry(
                                    title = label,
                                    xmlUrl = "",
                                    group = outlineStack.filterNotNull().joinToString(" / "),
                                    invalidReason = "地址缺失，无法导入",
                                ))
                            }
                        }
                    }
                }
                event = parser.next()
            }
            if (!rootSeen || !bodySeen) throw OpmlError.Malformed("缺少 OPML body")
        } catch (e: OpmlError) {
            return Result.failure(e)
        } catch (e: Exception) {
            // 解析器的原始信息里带 position 与 Java 对象引用（如 StringReader@cdaf9f6），
            // 对用户没有意义；只保留冒号前的原因部分。
            val raw = e.message ?: e.javaClass.simpleName
            val concise = raw.substringBefore(" (position:").trim().ifBlank { "文件内容不是有效的 OPML" }
            return Result.failure(OpmlError.Malformed(concise))
        }

        if (entries.size > MAX_ENTRIES) return Result.failure(OpmlError.TooManyEntries(entries.size))

        // 标记重复：先与远端已有比较，再与文件内前一条比较
        val marked = entries.map { entry ->
            val normalized = EndpointPolicy.normalizeFeedUrl(entry.xmlUrl)
            when {
                normalized == null -> entry
                known.contains(normalized) -> entry.copy(duplicateOf = "已在订阅中")
                seenInFile.contains(normalized) -> entry.copy(duplicateOf = "文件内重复")
                else -> {
                    seenInFile += normalized
                    entry
                }
            }
        }

        return Result.success(
            Document(
                title = docTitle,
                entries = marked,
                invalidCount = marked.count { !it.selectable },
            ),
        )
    }

    private fun buildEntry(title: String, xmlUrl: String, htmlUrl: String?, groupPath: List<String>, type: String?): Entry {
        val group = groupPath.filter { it.isNotBlank() }.joinToString(" / ")
        val normalized = EndpointPolicy.normalizeFeedUrl(xmlUrl)
        val reason = when {
            !type.isNullOrBlank() && type.lowercase() !in setOf("rss", "atom") -> "该条目使用其他抓取配置，仅支持 RSS/Atom"
            xmlUrl.length > MAX_URL_LENGTH -> "地址过长"
            normalized == null -> "不是有效的 HTTPS 地址"
            // 与本机/实例新增同一条目标规则（契约：拒绝 localhost、私有/回环地址）：写入前就标出，不等导入时才失败
            EndpointPolicy.requirePublicFetchTarget(normalized).isFailure -> "不接受本机或内网地址"
            title.length > MAX_NAME_LENGTH -> "名称过长"
            else -> null
        }
        return Entry(
            title = title.ifBlank { normalized?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: xmlUrl },
            xmlUrl = xmlUrl.trim(),
            htmlUrl = htmlUrl,
            group = group,
            invalidReason = reason,
            groupTooLong = group.length > MAX_GROUP_LENGTH,
        )
    }

    /**
     * 属性查找**不区分大小写**：OPML 规范写 `xmlUrl`，但实际导出的文件里
     * `xmlurl`/`XMLURL` 都出现过，而 XmlPullParser 的属性名查找是区分大小写的。
     * 早先只查小写名，导致每个 outline 都被当成“分组”、条目一条都收集不到。
     */
    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    /** 导出结果：只有订阅与分组，不含任何密钥（契约 §6） */
    data class ExportFeed(val title: String, val xmlUrl: String, val htmlUrl: String? = null, val group: String = "")

    fun write(feeds: List<ExportFeed>, title: String = "CatLeaf 订阅"): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<opml version=\"2.0\">\n")
        sb.append("  <head>\n    <title>").append(escape(title)).append("</title>\n  </head>\n")
        sb.append("  <body>\n")

        // 按分组输出；分组原名保留，导出不拆分层次
        feeds.groupBy { it.group }
            .toList()
            .sortedBy { it.first }
            .forEach { (group, items) ->
                val indent = if (group.isBlank()) "    " else "      "
                if (group.isBlank()) {
                    items.forEach { sb.append(outline(it, indent)) }
                } else {
                    sb.append("    <outline text=\"").append(escape(group))
                        .append("\" title=\"").append(escape(group)).append("\">\n")
                    items.forEach { sb.append(outline(it, indent)) }
                    sb.append("    </outline>\n")
                }
            }

        sb.append("  </body>\n</opml>\n")
        return sb.toString()
    }

    private fun outline(feed: ExportFeed, indent: String): String = buildString {
        append(indent).append("<outline type=\"rss\" text=\"").append(escape(feed.title))
        append("\" title=\"").append(escape(feed.title))
        append("\" xmlUrl=\"").append(escape(feed.xmlUrl)).append("\"")
        feed.htmlUrl?.takeIf { it.isNotBlank() }?.let {
            append(" htmlUrl=\"").append(escape(it)).append("\"")
        }
        append(" />\n")
    }

    private fun escape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
