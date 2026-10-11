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
 * 首版 AI 只走一套 OpenAI 兼容的 Chat Completions（契约 5.1）。
 * T05 只做“最小请求”以验证协议与网络，AI 设置页与翻译/摘要属 T15/T16/T17。
 *
 * 约定：base URL 由用户按供应商文档填写，这里只追加 `/chat/completions`，
 * 不猜是否要补 `/v1`。手动任务非流式，一次请求只发一次。
 */
class AiClient(
    private val clientFactory: (String) -> OkHttpClient = HttpClients::forAi,
) {

    /**
     * @param truncated 供应商 `finish_reason` 表明被长度截断：调用方必须提示“结果不完整”，
     *   不能当成完整成功（契约 5.1）
     */
    data class ChatResult(val content: String, val truncated: Boolean = false)

    suspend fun chat(baseUrlRaw: String, model: String, apiKey: String, prompt: String): Result<ChatResult> =
        withContext(Dispatchers.IO) {
            val base = EndpointPolicy.normalizeAiBaseUrl(baseUrlRaw).getOrElse {
                return@withContext Result.failure(it)
            }
            if (model.isBlank()) return@withContext Result.failure(EndpointError.Invalid("模型 id 不能为空"))
            if (apiKey.isBlank()) return@withContext Result.failure(EndpointError.Invalid("API Key 不能为空"))

            val body = buildBody(model, prompt)
            val request = Request.Builder()
                .url(endpointFor(base))
                .post(body.toRequestBody(JSON_MEDIA))
                .header("Accept", "application/json")
                .build()

            try {
                clientFactory(apiKey).newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.code == 401 || response.code == 403 ->
                            Result.failure(EndpointError.Unauthorized())
                        response.code == 429 ->
                            Result.failure(EndpointError.Unreachable("请求受限（429），请稍后重试"))
                        response.code !in 200..299 ->
                            Result.failure(EndpointError.Unreachable("供应商返回 ${response.code}"))
                        else -> {
                            val content = extractContent(text)
                            if (content == null) {
                                Result.failure(EndpointError.Unreachable("响应结构无法解析"))
                            } else {
                                Result.success(ChatResult(content, truncated = isTruncated(text)))
                            }
                        }
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

    /** `finish_reason` 为 length 表示被长度截断（契约 5.1） */
    private fun isTruncated(json: String): Boolean =
        TRUNCATED.find(json) != null

    /**
     * 极简 JSON 读取：只取 `choices[0].message.content`。
     * 首版不引入序列化库，等 T16/T17 需要结构化摘要时再统一选一套并锁定。
     */
    private fun extractContent(json: String): String? {
        val marker = "\"content\""
        val at = json.indexOf(marker)
        if (at < 0) return null
        val colon = json.indexOf(':', at + marker.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    when (val esc = json[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'u' -> {
                            if (i + 5 >= json.length) return sb.toString()
                            val hex = json.substring(i + 2, i + 6)
                            hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            i += 4
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

    private fun buildBody(model: String, prompt: String): String {
        // 原文只作为待处理数据传入，与任务指令分隔（契约 5.1）
        val escapedPrompt = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        val escapedModel = model.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"model":"$escapedModel","stream":false,"messages":[{"role":"user","content":"$escapedPrompt"}]}"""
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 允许键值之间有空格：`"finish_reason": "length"` */
        private val TRUNCATED = Regex("\"finish_reason\"\\s*:\\s*\"length\"")

        /** base URL 只追加路径，不重复已有的 /v1 */
        fun endpointFor(normalizedBaseUrl: String): String =
            "${normalizedBaseUrl.trimEnd('/')}/chat/completions"
    }
}