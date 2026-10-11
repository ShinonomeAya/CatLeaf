package dev.local.catleaf.nativeapp.data.opml

import android.util.Xml
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import org.xmlpull.v1.XmlPullParser
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
    const val MAX_ENTRIES = 200
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
        class NotUtf8 : OpmlError("文件不是有效的 UTF-8 文本")
        class Malformed(detail: String) : OpmlError("XML 解析失败：$detail")
        class TooManyEntries(count: Int) : OpmlError("订阅条目超过上限 $MAX_ENTRIES（实际 $count）")
    }

    /**
     * 解析 OPML。任何拒绝都在返回前发生，调用方因此在“写入前”就能给出结论。
     *
     * @param knownUrls 远端已有源的 URL（规范化后比较），用于标记重复
     */
    fun parse(bytes: ByteArray, knownUrls: List<String> = emptyList()): Result<Document> {
        if (bytes.size > MAX_BYTES) return Result.failure(OpmlError.TooLarge(bytes.size))

        val text = try {
            // 契约要求 UTF-8：非法字节直接判失败，不用替换字符蒙混过去
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: Exception) {
            return Result.failure(OpmlError.NotUtf8())
        }

        // 先做文本级检查：实体声明可能引出外部实体，解析前就拒绝最稳妥
        val head = text.take(4096).uppercase()
        if (head.contains("<!DOCTYPE") || head.contains("<!ENTITY")) {
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

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name?.lowercase()
                        if (tag == "title" && docTitle.isEmpty()) {
                            docTitle = parser.nextText().trim()
                            event = parser.eventType
                            continue
                        }
                        if (tag == "outline") {
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
                                entries += buildEntry(label, xmlUrl, attr(parser, "htmlurl"), groupPath)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name?.lowercase() == "outline" && outlineStack.isNotEmpty()) {
                            val label = outlineStack.removeAt(outlineStack.lastIndex)
                            val hadChild = groupHasChild.removeAt(groupHasChild.lastIndex)
                            // 无子节点、无 xmlUrl 但有名字 → 视为“地址缺失”的无效条目
                            if (label != null && !hadChild && label.isNotBlank()) {
                                entries += Opml.Entry(
                                    title = label,
                                    xmlUrl = "",
                                    group = outlineStack.filterNotNull().joinToString(" / "),
                                    invalidReason = "地址缺失，无法导入",
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
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

    private fun buildEntry(title: String, xmlUrl: String, htmlUrl: String?, groupPath: List<String>): Entry {
        val group = groupPath.filter { it.isNotBlank() }.joinToString(" / ")
        val normalized = EndpointPolicy.normalizeFeedUrl(xmlUrl)
        val reason = when {
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