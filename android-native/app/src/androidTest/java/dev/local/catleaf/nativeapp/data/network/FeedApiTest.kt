package dev.local.catleaf.nativeapp.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 订阅 API 的状态码→错误映射与请求形态。
 * 服务端返回的稳定 code 必须映射成可操作提示，界面才能说清“为什么没成”。
 */
@RunWith(AndroidJUnit4::class)
class FeedApiTest {

    private lateinit var server: MockWebServer
    private val fakeSecret = "FAKE-INSTANCE-SECRET-feed-api-test"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // 不注入 clientFactory：走生产默认的 HttpClients.forInstance，
    // 这样才真正验证“实例密钥由 client 附加”这条路径。
    // （先前注入裸 client 导致 x-admin-secret 缺失——凭据不在 FeedApi 里手拼。）
    private fun api() = FeedApi(
        origin = server.url("").toString().trimEnd('/'),
        secret = fakeSecret,
    )

    private fun enqueue(code: Int, body: String = "{}") {
        server.enqueue(
            MockResponse().setResponseCode(code).setBody(body)
                .setHeader("Content-Type", "application/json"),
        )
    }

    @Test
    fun list_parses_fields_and_sends_admin_secret() = runBlocking {
        enqueue(
            200,
            """
            [
              {"id":"f1","url":"https://a.example.org/feed.xml","category":"技术",
               "customTitle":"技术源","displayOrder":0,"allowedMediaHosts":[]},
              {"id":"f2","url":"https://b.example.org/rss","category":"",
               "customTitle":"","displayOrder":1,"allowedMediaHosts":[]}
            ]
            """.trimIndent(),
        )

        val feeds = api().list().getOrThrow()
        assertEquals(2, feeds.size)
        assertEquals("技术源", feeds[0].customTitle)
        assertEquals("技术", feeds[0].category)
        assertEquals("https://b.example.org/rss", feeds[1].url)
        assertEquals("显示名回退到主机名", "b.example.org", feeds[1].displayName)

        val recorded = server.takeRequest()
        assertEquals("/api/feeds/list/admin", recorded.path)
        assertEquals(fakeSecret, recorded.getHeader("x-admin-secret"))
        assertEquals("GET", recorded.method)
    }

    @Test
    fun add_posts_json_with_generated_id_and_does_not_send_ai_headers() = runBlocking {
        enqueue(200, """{"success":true}""")

        api().add(id = "f-abc", url = "https://a.example.org/feed.xml", category = "阅读", customTitle = "名").getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("/api/feeds/add", recorded.path)
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue("应带上 id", body.contains("\"id\":\"f-abc\""))
        assertTrue("应带上 url", body.contains("https://a.example.org/feed.xml"))
        assertTrue("应带上分组", body.contains("\"category\":\"阅读\""))
        assertTrue("首版不使用订阅标记", body.contains("\"isSub\":false"))
        assertEquals(fakeSecret, recorded.getHeader("x-admin-secret"))
        assertFalse("订阅请求不得带 AI Key", recorded.headers.values("Authorization").isNotEmpty())
    }

    @Test
    fun duplicate_conflicts_map_to_actionable_messages() = runBlocking {
        val cases = mapOf(
            "id_exists" to "已有同 id",
            "url_exists" to "已经订阅过",
            "url_change_requires_new_feed" to "作为新订阅",
            "feed_limit_reached" to "上限",
        )
        for ((code, expectedFragment) in cases) {
            enqueue(409, """{"error":"x","code":"$code"}""")
            val error = api().add("f1", "https://a.example.org/f", "", "").exceptionOrNull()
            assertTrue("$code 应映射为 Conflict", error is FeedApiError.Conflict)
            assertEquals(code, (error as FeedApiError.Conflict).code)
            assertTrue("$code 提示应可操作：${error.message}", error.message!!.contains(expectedFragment))
        }
    }

    @Test
    fun invalid_input_codes_map_to_specific_messages() = runBlocking {
        for (code in listOf("invalid_url", "invalid_id", "invalid_name", "invalid_category")) {
            enqueue(400, """{"error":"x","code":"$code"}""")
            val error = api().add("f1", "http://a/rss", "", "").exceptionOrNull()
            assertTrue("$code 应映射为 Invalid", error is FeedApiError.Invalid)
            assertEquals(code, (error as FeedApiError.Invalid).code)
        }
    }

    @Test
    fun update_sends_only_metadata_and_never_a_new_url() = runBlocking {
        enqueue(200, """{"success":true}""")

        api().updateMeta(id = "f1", url = "https://a.example.org/feed.xml", category = "阅读", customTitle = "改名").getOrThrow()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"id\":\"f1\""))
        assertTrue(body.contains("\"customTitle\":\"改名\""))
        // 请求体里没有 isSub / allowedMediaHosts，避免顺带改动其他字段
        assertFalse(body.contains("isSub"))
        assertFalse(body.contains("allowedMediaHosts"))
    }

    @Test
    fun missing_feed_and_auth_failures_are_distinct() = runBlocking {
        enqueue(404, """{"error":"not found","code":"not_found"}""")
        assertTrue(api().delete("nope").exceptionOrNull() is FeedApiError.NotFound)

        enqueue(401, """{"error":"Unauthorized","code":"unauthorized"}""")
        assertTrue(api().delete("f1").exceptionOrNull() is EndpointError.Unauthorized)

        enqueue(503, """{"error":"Server is not configured","code":"not_configured"}""")
        assertTrue(api().list().exceptionOrNull() is EndpointError.NotConfigured)

        // 403 是“后端不允许抓取这个目标”（契约 3.3），不是密钥问题：两者必须区分，
        // 否则用户会看到“密钥无效”而去换一个其实没问题的密钥。
        enqueue(403, """{"success":false,"error":"Domain not in whitelist","fallback":"use_rss_content"}""")
        val forbidden = api().fetchRawHtml("https://example.com/a").exceptionOrNull()
        assertTrue("403 应是 Forbidden，实际：$forbidden", forbidden is EndpointError.Forbidden)
        assertTrue("403 不得报成密钥失效", forbidden !is EndpointError.Unauthorized)
    }

    @Test
    fun empty_2xx_body_is_an_error_not_an_empty_payload() = runBlocking {
        // 中断或截断的连接会表现成“200 空体”。若当成合法空载荷：
        // 历史被读成“没有更多”，订阅列表被读成“源全没了”——后者会让
        // FeedStore.refresh 执行 deleteAll，把本地源镜像清空（丢数据）。
        enqueue(200, "")

        val list = api().list()
        assertTrue("订阅列表的空响应必须失败，不能当成 0 个源，实际：$list", list.isFailure)
        assertTrue("应是可识别的不可达/空响应", list.exceptionOrNull() is EndpointError.Unreachable)

        enqueue(200, "")
        val history = api().fetchHistory("f1", 100, 0)
        assertTrue("历史分页的空响应必须失败，不能当成没有更多，实际：$history", history.isFailure)
    }

    @Test
    fun empty_list_and_malformed_body_do_not_crash() = runBlocking {
        enqueue(200, "[]")
        assertEquals(0, api().list().getOrThrow().size)

        // 缺 id 的条目被跳过而不是抛错
        enqueue(200, """[{"url":"https://a.example.org/f"}]""")
        assertEquals(0, api().list().getOrThrow().size)

        // JSON 里含花括号的字符串不应打乱对象切分
        enqueue(200, """[{"id":"f1","url":"https://a.example.org/f","customTitle":"含{括号}的名","category":"","displayOrder":0}]""")
        val feeds = api().list().getOrThrow()
        assertEquals(1, feeds.size)
        assertEquals("含{括号}的名", feeds[0].customTitle)
    }
}