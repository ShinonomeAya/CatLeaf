package dev.local.catleaf.nativeapp.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 最小 AI 请求的协议检查：端点拼接不重复 `/v1`、请求体形态、错误分类、响应解析。
 * 真实供应商连通属待外部输入（需要用户自己的 Key），这里只验证可离线确定的部分。
 */
@RunWith(AndroidJUnit4::class)
class AiClientTest {

    private val fakeKey = "FAKE-AI-KEY-protocol-test"

    private fun clientReturning(code: Int, body: String, unknownLength: Boolean = false) = AiClient {
        okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(code)
                    .message("stub")
                    .body(if (!unknownLength) body.toResponseBody("application/json".toMediaType()) else object : okhttp3.ResponseBody() {
                        override fun contentType() = "application/json".toMediaType()
                        override fun contentLength() = -1L
                        override fun source() = okio.Buffer().writeUtf8(body)
                    })
                    .build()
            }
            .build()
    }

    @Test fun oversized_declared_and_unknown_length_responses_are_rejected() = kotlinx.coroutines.runBlocking {
        for (unknown in listOf(false, true)) {
            val result = clientReturning(200, "x".repeat(AiClient.MAX_RESPONSE_BYTES.toInt() + 1), unknown)
                .chat("https://api.example.org", "model", fakeKey, "test")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("1 MiB"))
        }
    }

    @Test
    fun endpoint_appends_chat_completions_without_duplicating_v1() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            AiClient.endpointFor("https://api.example.com/v1"),
        )
        assertEquals(
            "https://api.example.com/chat/completions",
            AiClient.endpointFor("https://api.example.com"),
        )
        // 用户已经带上 /v1 时不得变成 /v1/v1
        assertFalse(
            AiClient.endpointFor("https://api.example.com/v1").contains("/v1/v1"),
        )
    }

    @Test
    fun request_body_is_non_streaming_and_carries_the_model() {
        var seenUrl: String? = null
        var seenBody: String? = null
        var seenContentType: String? = null

        val client = AiClient {
            okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    seenUrl = chain.request().url.toString()
                    seenContentType = chain.request().body?.contentType().toString()
                    val buffer = okio.Buffer()
                    chain.request().body?.writeTo(buffer)
                    seenBody = buffer.readUtf8()
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("ok")
                        .body("""{"choices":[{"message":{"content":"ok"}}]}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()
        }

        val result = kotlinx.coroutines.runBlocking {
            client.chat("https://api.example.com/v1", "gpt-test", fakeKey, "把这段翻译成简体中文：hello")
        }

        assertTrue("应成功", result.isSuccess)
        assertEquals("https://api.example.com/v1/chat/completions", seenUrl)
        assertTrue("必须显式非流式", seenBody!!.contains("\"stream\":false"))
        assertTrue("必须带模型 id", seenBody!!.contains("\"model\":\"gpt-test\""))
        assertTrue("必须带用户内容", seenBody!!.contains("hello"))
        assertTrue("必须是 JSON", seenContentType!!.contains("application/json"))
    }

    @Test
    fun http_errors_map_to_readable_categories() {
        assertEquals(
            EndpointError.Unauthorized::class,
            kotlinx.coroutines.runBlocking {
                clientReturning(401, "{}").chat("https://api.example.com", "m", fakeKey, "x").exceptionOrNull()
            }!!::class,
        )
        assertEquals(
            EndpointError.Unauthorized::class,
            kotlinx.coroutines.runBlocking {
                clientReturning(403, "{}").chat("https://api.example.com", "m", fakeKey, "x").exceptionOrNull()
            }!!::class,
        )
        val limited = kotlinx.coroutines.runBlocking {
            clientReturning(429, "{}").chat("https://api.example.com", "m", fakeKey, "x").exceptionOrNull()
        }
        assertTrue("429 应提示限流", limited is EndpointError.Unreachable && limited.message!!.contains("429"))
    }

    @Test
    fun empty_or_unparsable_response_is_not_reported_as_success() {
        val empty = kotlinx.coroutines.runBlocking {
            clientReturning(200, "{}").chat("https://api.example.com", "m", fakeKey, "x")
        }
        assertTrue("结构不可解析不得当作成功", empty.isFailure)

        val blank = kotlinx.coroutines.runBlocking {
            clientReturning(200, """{"choices":[{"message":{"content":""}}]}""")
                .chat("https://api.example.com", "m", fakeKey, "x")
        }
        assertEquals("空内容按原文返回，仍可从结构读出", "", blank.getOrNull()?.content)
    }

    @Test
    fun invalid_inputs_are_rejected_before_any_request() {
        val c = clientReturning(200, "{}")
        assertTrue(
            kotlinx.coroutines.runBlocking { c.chat("http://api.example.com", "m", fakeKey, "x") }
                .exceptionOrNull() is EndpointError.Invalid,
        )
        assertTrue(
            kotlinx.coroutines.runBlocking { c.chat("https://api.example.com", "", fakeKey, "x") }
                .exceptionOrNull() is EndpointError.Invalid,
        )
        assertTrue(
            kotlinx.coroutines.runBlocking { c.chat("https://api.example.com", "m", "  ", "x") }
                .exceptionOrNull() is EndpointError.Invalid,
        )
    }

    @Test
    fun real_deepseek_shape_with_reasoning_content_still_reads_content() {
        // 2026-09-23 对 DeepSeek 实测得到的真实结构：message 里同时有 reasoning_content 与 content，
        // 且 reasoning_content 在前。手写解析必须取到 content 而不是被推理字段带偏。
        val realShape =
            """{"id":"x","object":"chat.completion","model":"deepseek-flash","choices":[""" +
                """{"index":0,"message":{"role":"assistant","content":"收到",""" +
                """ "reasoning_content":"The user asks me to reply with only two characters."},""" +
                """"finish_reason":"stop"}],"usage":{"prompt_tokens":35,"completion_tokens":9}}"""

        val result = kotlinx.coroutines.runBlocking {
            clientReturning(200, realShape).chat("https://api.deepseek.com", "deepseek-flash", fakeKey, "x")
        }
        assertEquals("收到", result.getOrNull()?.content)
    }

    @Test
    fun content_is_unescaped_from_json_string() {
        val result = kotlinx.coroutines.runBlocking {
            clientReturning(200, """{"choices":[{"message":{"content":"第一行\n第二行 \"引用\""}}]}""")
                .chat("https://api.example.com", "m", fakeKey, "x")
        }
        assertEquals("第一行\n第二行 \"引用\"", result.getOrNull()?.content)
    }

    @Test
    fun ai_key_is_not_written_to_ordinary_storage() {
        // T05 只验证隔离：AI Key 走凭据存储，不落在 DataStore
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        CredentialStore(context).put(CredentialStore.AI_KEY, fakeKey)
        try {
            val settingsText = java.io.File(
                dev.local.catleaf.nativeapp.data.settings.SettingsStore(context).storagePath(),
            ).takeIf { it.exists() }?.readBytes()?.toString(Charsets.ISO_8859_1).orEmpty()
            assertFalse("普通设置里不得出现 AI Key", settingsText.contains(fakeKey))
        } finally {
            CredentialStore(context).delete(CredentialStore.AI_KEY)
        }
    }
}
