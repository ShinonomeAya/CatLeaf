package dev.local.catleaf.nativeapp.data.importexport

import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import org.json.JSONArray
import org.json.JSONObject

/** 状态文件的一个订阅条目（契约 §6：真实 URL、名称、分组） */
data class StateFeed(val url: String, val title: String, val category: String)

/** 一个文章的阅读状态条目——只有身份与状态；**没有正文、AI 结果或密钥**（契约 §6） */
data class StateArticle(
    val feedUrl: String,
    val guid: String?,
    val link: String?,
    val title: String,
    val feedTitle: String,
    val isRead: Boolean,
    val isStarred: Boolean,
)

/** 解析结果：合法条目 + 逐条的无效原因（预览要能展开"无效条目 · 查看"） */
data class StateDocument(
    /** 来源类型（v2）：local / instance；v1 文件一律按 instance 解析（契约 8.5：不擅自当作本机） */
    val sourceKind: String,
    /** 来源实例 origin（kind=instance 时非空；本机来源为空——不把 local:v1 伪装成 URL） */
    val sourceOrigin: String,
    val exportedAt: Long,
    val feeds: List<StateFeed>,
    val articles: List<StateArticle>,
    val invalidItems: List<String>,
)

/** 超限时抛出：调用方必须拒绝，**不能生成不完整文件**（契约 §6） */
class StateLimitExceeded(message: String) : Exception(message)

/**
 * 本产品状态文件 `catweb-state` v1（契约 §6）。
 *
 * 写侧手写 JSON（转义只有引号/反斜杠/控制字符，且**纯 Kotlin 可在 JVM 测**）；
 * 读侧用平台自带的 org.json——它只处理"别人给的字节"这一信任边界，
 * 且要求 Android 运行时（往返测试在 instrumented 侧钉住）。
 */
object StateFile {

    const val FORMAT = "catweb-state"

    /** v1（实例 origin 字符串）与 v2（source 对象，契约 8.5）；读取两者兼容 */
    const val VERSION = 2
    private const val V1 = 1

    /** 契约 §6：最多 10 MiB、50,000 个状态条目 */
    const val MAX_BYTES = 10L * 1024 * 1024
    const val MAX_ENTRIES = 50_000

    /**
     * v2 写侧（契约 8.5）：`source` 为 {kind:local} 或 {kind:instance, origin}；
     * 不导出内部 scope key 或设备标识；不含正文/AI/密钥。
     */
    fun write(sourceKind: String, sourceOrigin: String?, exportedAt: Long, feeds: List<StateFeed>, articles: List<StateArticle>): ByteArray {
        require(sourceKind == "local" || sourceKind == "instance") { "未知来源类型：$sourceKind" }
        if (sourceKind == "instance" && sourceOrigin.isNullOrBlank()) {
            throw IllegalArgumentException("实例来源缺少 origin")
        }
        if (feeds.size + articles.size > MAX_ENTRIES) {
            throw StateLimitExceeded("状态条目 ${feeds.size + articles.size} 个，超过上限 $MAX_ENTRIES：本次不生成文件")
        }
        val json = buildString {
            append('{')
            field("format", FORMAT)
            append(",\"version\":").append(VERSION)
            comma().append("\"exportedAt\":").append(exportedAt)
            comma()
            if (sourceKind == "local") {
                append("\"source\":{\"kind\":\"local\"}")
            } else {
                append("\"source\":{\"kind\":\"instance\",\"origin\":\"")
                append(escape(sourceOrigin!!.trimEnd('/')))
                append("\"}")
            }
            append(",\"feeds\":[")
            feeds.forEachIndexed { i, f ->
                if (i > 0) append(',')
                append('{')
                field("url", f.url); comma(); field("title", f.title); comma(); field("category", f.category)
                append('}')
            }
            append("],\"articles\":[")
            articles.forEachIndexed { i, a ->
                if (i > 0) append(',')
                append('{')
                field("feedUrl", a.feedUrl)
                a.guid?.let { comma(); field("guid", it) }
                a.link?.let { comma(); field("link", it) }
                comma(); field("title", a.title)
                comma(); field("feedTitle", a.feedTitle)
                comma(); append("\"read\":").append(a.isRead)
                comma(); append("\"starred\":").append(a.isStarred)
                append('}')
            }
            append("]}")
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) {
            throw StateLimitExceeded("状态文件 ${bytes.size} 字节，超过上限 10 MiB：本次不生成文件")
        }
        return bytes
    }

    private fun StringBuilder.field(key: String, value: String) {
        append('"').append(key).append("\":\"").append(escape(value)).append('"')
    }

    private fun StringBuilder.comma() = append(',')

    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 完整解析 + 校验在**写入前**完成：格式/版本不对、超字节、超条数、结构不可读
     * 都是整份拒绝；单条无效（非 HTTPS 源地址、guid/link 全缺）不连坐，进 [StateDocument.invalidItems]。
     */
    fun read(bytes: ByteArray): Result<StateDocument> = runCatching {
        if (bytes.size.toLong() > MAX_BYTES) throw StateLimitExceeded("文件超过 10 MiB 上限，已拒绝")
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的 JSON 状态文件")
        }
        if (root.optString("format") != FORMAT) throw IllegalArgumentException("不是 CatLeaf 状态文件（format 不符）")
        val version = root.optInt("version", -1)
        if (version != VERSION && version != V1) {
            throw IllegalArgumentException("无法读取的状态文件版本：${root.opt("version")}（本版支持 v$V1–v$VERSION；v2 文件需支持 v2 的猫页版本恢复）")
        }

        // 来源：v2 用 source 对象并校验 kind/origin 对应；v1 的 sourceOrigin 一律按实例来源解析（不当作本机）
        val sourceKind: String
        val sourceOrigin: String
        when (version) {
            V1 -> {
                sourceKind = "instance"
                sourceOrigin = root.optString("sourceOrigin")
                if (EndpointPolicy.normalizeInstanceOrigin(sourceOrigin).isFailure) {
                    throw IllegalArgumentException("v1 文件的来源 origin 无效：${sourceOrigin.take(60)}")
                }
            }
            else -> {
                val src = root.optJSONObject("source") ?: throw IllegalArgumentException("v2 文件缺少 source 字段")
                when (val kind = src.optString("kind")) {
                    "local" -> {
                        sourceKind = "local"
                        sourceOrigin = ""
                        if (src.has("origin") && src.optString("origin").isNotBlank()) {
                            throw IllegalArgumentException("来源矛盾：kind=local 却带 origin")
                        }
                    }
                    "instance" -> {
                        sourceKind = "instance"
                        sourceOrigin = src.optString("origin")
                        if (EndpointPolicy.normalizeInstanceOrigin(sourceOrigin).isFailure) {
                            throw IllegalArgumentException("v2 文件的来源 origin 无效：${sourceOrigin.take(60)}")
                        }
                    }
                    else -> throw IllegalArgumentException("未知来源类型：$kind")
                }
            }
        }

        val feedsArr = root.optJSONArray("feeds") ?: JSONArray()
        val articlesArr = root.optJSONArray("articles") ?: JSONArray()
        if (feedsArr.length() + articlesArr.length() > MAX_ENTRIES) {
            throw StateLimitExceeded("状态条目超过 $MAX_ENTRIES 上限，已拒绝")
        }

        val invalid = mutableListOf<String>()
        val feeds = mutableListOf<StateFeed>()
        for (i in 0 until feedsArr.length()) {
            val obj = feedsArr.optJSONObject(i) ?: run { invalid += "订阅条目 $i：不是对象"; continue }
            val url = obj.optString("url")
            if (EndpointPolicy.normalizeFeedUrl(url) == null) {
                invalid += "订阅「${obj.optString("title").take(40)}」：地址不是有效的 HTTPS 订阅"
                continue
            }
            feeds += StateFeed(url, obj.optString("title"), obj.optString("category"))
        }

        val articles = mutableListOf<StateArticle>()
        for (i in 0 until articlesArr.length()) {
            val obj = articlesArr.optJSONObject(i) ?: run { invalid += "文章条目 $i：不是对象"; continue }
            val guid = obj.optString("guid").trim().takeIf { it.isNotEmpty() && !obj.isNull("guid") }
            val link = obj.optString("link").trim().takeIf { it.isNotEmpty() && !obj.isNull("link") }
            if (guid == null && link == null) {
                invalid += "文章「${obj.optString("title").take(40)}」：guid 与 link 都缺失，无法对应任何文章"
                continue
            }
            val feedUrl = obj.optString("feedUrl")
            articles += StateArticle(
                feedUrl = feedUrl,
                guid = guid,
                link = link,
                title = obj.optString("title"),
                feedTitle = obj.optString("feedTitle"),
                isRead = obj.optBoolean("read", false),
                isStarred = obj.optBoolean("starred", false),
            )
        }

        StateDocument(
            sourceKind = sourceKind,
            sourceOrigin = sourceOrigin.trimEnd('/'),
            exportedAt = root.optLong("exportedAt", 0L),
            feeds = feeds,
            articles = articles,
            invalidItems = invalid,
        )
    }
}
