package dev.local.catleaf.nativeapp.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException

/**
 * 订阅增删改（契约 3.2）。所有请求走实例 client（附 `x-admin-secret`），
 * 且只打向已配置的实例 origin。
 *
 * 服务端语义要点（T08 已在生产验证）：
 * - 同 id 或同规范化 URL 的新增 → 409，**不会覆盖**已有源；
 * - 编辑只改名称与分组，换 URL → 409（必须另建源）；
 * - 删除不存在 → 404。
 */
class FeedApi(
    private val origin: String,
    private val secret: String,
    /** 仅供测试注入 */
    private val clientFactory: (String) -> OkHttpClient = HttpClients::forInstance,
) {

    /** 历史条目：只取 T11 需要的扁平字段 */
    data class RemoteHistoryItem(
        val title: String,
        val guid: String?,
        val link: String?,
        val pubDate: String?,
        val author: String?,
        val description: String?,
        val content: String?,
    )

    data class RemoteHistoryPage(
        val items: List<RemoteHistoryItem>,
        val total: Int,
        val lastUpdated: Long?,
    )

    data class RemoteFeed(
        val id: String,
        val url: String,
        val category: String,
        val customTitle: String,
        val displayOrder: Int,
        val allowedMediaHosts: List<String>,
    ) {
        /** 显示名：自定义名称优先，其次主机名（契约 P03：无名称时用源名，再退主机名） */
        val displayName: String
            get() = customTitle.ifBlank { runCatching { java.net.URI(url).host }.getOrNull() ?: url }
    }

    suspend fun list(): Result<List<RemoteFeed>> = request(
        path = "/api/feeds/list/admin",
        method = "GET",
    ) { body -> Result.success(parseFeedList(body)) }

    suspend fun add(
        id: String,
        url: String,
        category: String,
        customTitle: String,
    ): Result<Unit> = request(
        path = "/api/feeds/add",
        method = "POST",
        json = """
            {"id":${id.q()},"url":${url.q()},"category":${category.q()},
             "isSub":false,"customTitle":${customTitle.q()}}
        """.trimIndent().replace("\n", ""),
    ) { Result.success(Unit) }

    /** 只提交名称与分组；即使传入原 URL 也不会改变地址 */
    suspend fun updateMeta(id: String, url: String, category: String, customTitle: String): Result<Unit> = request(
        path = "/api/feeds/update",
        method = "POST",
        json = """
            {"id":${id.q()},"url":${url.q()},"category":${category.q()},
             "customTitle":${customTitle.q()}}
        """.trimIndent().replace("\n", ""),
    ) { Result.success(Unit) }

    suspend fun delete(id: String): Result<Unit> = request(
        path = "/api/feeds/delete",
        method = "POST",
        json = """{"id":${id.q()}}""",
    ) { Result.success(Unit) }

    /** 抓取该源以验证可用性；失败时返回原因，源本身仍保留（契约 P03） */
    suspend fun verify(id: String): Result<Unit> = request(
        path = "/api/feed?id=$id",
        method = "GET",
    ) { Result.success(Unit) }

    /** 抓取该源的 RSS/Atom 原文，交给 [dev.local.catleaf.nativeapp.data.rss.FeedParser] 解析 */
    suspend fun fetchFeedXml(feedId: String): Result<String> = request(
        path = "/api/feed?id=$feedId",
        method = "GET",
    ) { body -> Result.success(body) }

    /**
     * 历史分页。limit 由服务端夹取到 1–200、默认 100（T07 已在生产验证）。
     * 分页用于“加载更多历史”，不是补全某天内容。
     */
    suspend fun fetchHistory(feedId: String, limit: Int, offset: Int): Result<RemoteHistoryPage> = request(
        path = "/api/history/get?id=$feedId&limit=$limit&offset=$offset",
        method = "GET",
    ) { body -> Result.success(parseHistory(body)) }

    /**
     * 历史尽力写入（L07/契约 3.2）：解析出的条目 upsert 到实例历史。
     * 服务端幂等（同 guid/link 重复写不重复计数）、单批 ≤100 条（413）。
     * 404 = 该源已在实例上被删：静默放弃（本地照常阅读）。
     */
    suspend fun upsertHistory(feedId: String, items: List<HistoryUpsert>): Result<Unit> = request(
        path = "/api/history/upsert",
        method = "POST",
        json = buildUpsertBody(feedId, items),
    ) { Result.success(Unit) }

    /**
     * 全文兜底（契约 3.3）：本机直连原站失败时，让自有后端取原始 HTML（`mode=raw`），
     * 客户端仍自己用 Readability 解析并净化。后端只允许已登记源主机，
     * 403 时按契约保留 RSS 正文并给「原网页」入口。
     */
    suspend fun fetchRawHtml(articleUrl: String): Result<String> = request(
        // '+' 在 query 里表示空格：编码后换回 %20，避免服务端把地址解错
        path = "/api/article/extract?url=${encodeQuery(articleUrl)}&mode=raw",
        method = "GET",
    ) { body -> Result.success(body) }

    private fun encodeQuery(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun parseHistory(json: String): RemoteHistoryPage {
        val items = arrayValue(json, "items").mapNotNull { obj ->
            val title = obj.stringValue("title") ?: return@mapNotNull null
            RemoteHistoryItem(
                title = title,
                guid = obj.stringValue("guid"),
                link = obj.stringValue("link"),
                pubDate = obj.stringValue("pubDate"),
                author = obj.stringValue("author"),
                description = obj.stringValue("description"),
                content = obj.stringValue("content"),
            )
        }
        return RemoteHistoryPage(
            items = items,
            total = json.intValue("total") ?: items.size,
            lastUpdated = json.longValue("lastUpdated"),
        )
    }

    private suspend fun <T> request(
        path: String,
        method: String,
        json: String? = null,
        onSuccess: (String) -> Result<T>,
    ): Result<T> = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$origin$path")
        when (method) {
            "GET" -> builder.get()
            else -> builder.post((json ?: "{}").toRequestBody(JSON_MEDIA))
        }
        builder.header("Accept", "application/json")

        try {
            clientFactory(secret).newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                // 2xx 空响应不是合法载荷：本 API 每个成功响应都有内容（列表 [] / {"success":true} / RSS 原文）。
                // 中断或截断的连接会表现成“200 空体”，若当成合法空载荷：
                // 历史会被读成“没有更多”，而订阅列表会被读成“源全没了”——
                // FeedStore.refresh 对空列表会 deleteAll，等于把本地源镜像清空。
                if (response.code in 200..299 && body.isBlank()) {
                    return@withContext Result.failure(EndpointError.Unreachable("服务返回空响应"))
                }
                when (response.code) {
                    in 200..299 -> onSuccess(body)
                    // 后端用 401 表示密钥问题、403 表示“这个抓取目标不在允许集合里”（契约 3.3），
                    // 两者分开报，否则用户会看到“密钥失效”而去换一个其实没问题的密钥。
                    401 -> Result.failure(EndpointError.Unauthorized())
                    403 -> Result.failure(EndpointError.Forbidden())
                    404 -> Result.failure(FeedApiError.NotFound)
                    409 -> Result.failure(FeedApiError.conflictFrom(body))
                    400 -> Result.failure(FeedApiError.invalidFrom(body))
                    503 -> Result.failure(EndpointError.NotConfigured())
                    else -> Result.failure(EndpointError.Unreachable("服务返回 ${response.code}"))
                }
            }
        } catch (e: InterruptedIOException) {
            Result.failure(EndpointError.Timeout())
        } catch (e: UnknownHostException) {
            Result.failure(EndpointError.Unreachable("地址无法解析"))
        } catch (e: IOException) {
            Result.failure(EndpointError.Unreachable("网络不可用"))
        }
    }

    /**
     * 极简解析：只取 feed 列表里的固定字段。
     * ponytail: 手写取值而非引入序列化库——字段少且形状固定；T16/T17 需要结构化摘要时再统一选一套。
     */
    private fun parseFeedList(json: String): List<RemoteFeed> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val objects = splitTopLevelObjects(trimmed)
        return objects.mapNotNull { obj ->
            val id = obj.stringValue("id") ?: return@mapNotNull null
            RemoteFeed(
                id = id,
                url = obj.stringValue("url").orEmpty(),
                category = obj.stringValue("category").orEmpty(),
                customTitle = obj.stringValue("customTitle").orEmpty(),
                displayOrder = obj.intValue("displayOrder") ?: 0,
                allowedMediaHosts = emptyList(),
            )
        }
    }

    /** 按花括号深度切分顶层对象，避免被字符串里的括号带偏 */
    private fun splitTopLevelObjects(json: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        json.forEachIndexed { i, c ->
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                c == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        result += json.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return result
    }

    private fun String.stringValue(key: String): String? {
        val at = indexOf("\"$key\"")
        if (at < 0) return null
        val colon = indexOf(':', at + key.length + 2)
        if (colon < 0) return null
        var i = colon + 1
        while (i < length && this[i].isWhitespace()) i++
        if (i >= length || this[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < length) {
            val c = this[i]
            when {
                c == '\\' && i + 1 < length -> {
                    when (val esc = this[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'u' -> {
                            if (i + 5 < length) {
                                this.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                i += 4
                            }
                        }
                        else -> sb.append(esc)
                    }
                    i += 2
                }
                c == '"' -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    private fun String.intValue(key: String): Int? {
        val at = indexOf("\"$key\"")
        if (at < 0) return null
        val colon = indexOf(':', at + key.length + 2)
        if (colon < 0) return null
        val rest = substring(colon + 1).trimStart()
        val digits = rest.takeWhile { it.isDigit() || it == '-' }
        return digits.toIntOrNull()
    }

    /** 取出 `"key": [ ... ]` 的数组原文（按括号深度配对，跳过字符串内的括号） */
    private fun arrayValue(json: String, key: String): List<String> {
        val at = json.indexOf("\"$key\"")
        if (at < 0) return emptyList()
        val open = json.indexOf('[', at)
        if (open < 0) return emptyList()
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in open until json.length) {
            val c = json[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '[' -> depth++
                c == ']' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return emptyList()
        return splitTopLevelObjects(json.substring(open + 1, end))
    }

    private fun String.longValue(key: String): Long? {
        val at = indexOf("\"$key\"")
        if (at < 0) return null
        val colon = indexOf(':', at + key.length + 2)
        if (colon < 0) return null
        val rest = substring(colon + 1).trimStart()
        if (rest.startsWith("null")) return null
        val digits = rest.takeWhile { it.isDigit() || it == '-' }
        return digits.toLongOrNull()
    }

    private fun String.q(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** 订阅管理的可识别错误：服务端返回的 code 直接映射，便于界面给出可操作的提示 */
sealed class FeedApiError(message: String) : Exception(message) {
    object NotFound : FeedApiError("源不存在")
    class Conflict(val code: String, message: String) : FeedApiError(message)
    class Invalid(val code: String, message: String) : FeedApiError(message)

    companion object {
        fun conflictFrom(body: String): Conflict {
            val code = extractCode(body).orEmpty()
            return Conflict(code, messageFor(code))
        }

        fun invalidFrom(body: String): Invalid {
            val code = extractCode(body).orEmpty()
            return Invalid(code, messageFor(code))
        }

        private fun extractCode(body: String): String? =
            Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)

        private fun messageFor(code: String): String = when (code) {
            "id_exists" -> "已有同 id 的源，未做改动"
            "url_exists" -> "这个地址已经订阅过了"
            "url_change_requires_new_feed" -> "不能直接改订阅地址，请作为新订阅添加"
            "feed_limit_reached" -> "订阅数量已达上限，请先删除一些"
            "invalid_url" -> "只接受有效的 HTTPS RSS/Atom 地址"
            "invalid_id" -> "订阅标识不合法"
            "invalid_name" -> "名称过长"
            "invalid_category" -> "分组名过长"
            else -> "服务端拒绝了这次操作"
        }
    }
}