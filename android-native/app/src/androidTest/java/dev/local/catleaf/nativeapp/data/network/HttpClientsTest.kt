package dev.local.catleaf.nativeapp.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 请求头隔离与跳转行为的实测（MockWebServer）。
 *
 * 三层分别用各自的 client 直连本地 mock，检查真实发出的请求头：
 * 凭据隔离靠“器具隔离”实现，这里验证它确实生效。
 * 注意：地址策略（HTTPS + 非内网）由 [EndpointPolicy] 在选 client 之前把关，
 * 所以本测试可以放心用 http://127.0.0.1 直连 client，而不放松生产限制。
 */
@RunWith(AndroidJUnit4::class)
class HttpClientsTest {

    private lateinit var server: MockWebServer

    /** 虚构凭据，只在本测试内使用 */
    private val fakeInstanceSecret = "FAKE-INSTANCE-SECRET-for-header-test"
    private val fakeAiKey = "FAKE-AI-KEY-for-header-test"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun instance_client_sends_admin_secret_and_nothing_else() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        HttpClients.forInstance(fakeInstanceSecret)
            .newCall(HttpClients.request(server.url("/api/feeds/list").toString()))
            .execute().close()

        val recorded = server.takeRequest()
        assertEquals(fakeInstanceSecret, recorded.getHeader("x-admin-secret"))
        assertNull("实例请求不得带 AI 的 Authorization", recorded.getHeader("Authorization"))
    }

    @Test
    fun ai_client_sends_bearer_and_never_the_instance_secret() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        HttpClients.forAi(fakeAiKey)
            .newCall(HttpClients.request(server.url("/v1/chat/completions").toString()))
            .execute().close()

        val recorded = server.takeRequest()
        assertEquals("Bearer $fakeAiKey", recorded.getHeader("Authorization"))
        assertNull("AI 请求绝不能带实例密钥", recorded.getHeader("x-admin-secret"))
    }

    @Test
    fun public_client_sends_no_credentials_at_all() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<rss/>"))

        HttpClients.forPublic()
            .newCall(HttpClients.request(server.url("/feed.xml").toString()))
            .execute().close()

        val recorded = server.takeRequest()
        assertNull("原站抓取不得带实例密钥", recorded.getHeader("x-admin-secret"))
        assertNull("原站抓取不得带 AI Key", recorded.getHeader("Authorization"))
    }

    @Test
    fun credentialed_clients_do_not_follow_redirects() {
        // 每个 client 各来一次：对手方回 302，客户端必须把 302 原样交回，不得自动跟到新地址。
        // 注意 MockWebServer.requestCount 是累计值，不随 takeRequest() 减少，
        // 所以用累计期望值判断“有没有多发一次请求”，不能写成 while(requestCount>0) 去排空。
        var expectedCumulative = 0
        for ((label, client) in listOf(
            "instance" to HttpClients.forInstance(fakeInstanceSecret),
            "ai" to HttpClients.forAi(fakeAiKey),
        )) {
            expectedCumulative++
            server.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", server.url("/moved").toString()),
            )
            val response = client
                .newCall(HttpClients.request(server.url("/start").toString()))
                .execute()

            assertEquals("$label 不应跟随跳转", 302, response.code)
            response.close()

            assertEquals(
                "$label 只应发出一次请求（跟随跳转就会多一次）",
                expectedCumulative,
                server.requestCount,
            )
            server.takeRequest()
        }
    }

    @Test
    fun public_client_also_does_not_auto_follow() {
        server.enqueue(
            MockResponse().setResponseCode(301).setHeader("Location", server.url("/elsewhere").toString()),
        )

        val response = HttpClients.forPublic()
            .newCall(HttpClients.request(server.url("/feed.xml").toString()))
            .execute()

        assertEquals("公开抓取也要手动逐跳校验，不自动跟随", 301, response.code)
        response.close()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun credentialed_clients_would_leak_across_origins_but_are_not_followed() {
        // mock 是明文 http，策略层要求带凭据的跳转目标必须是 https 同源，
        // 因此这里断言的是策略判定：http 目标与其它 origin 都不放行。
        assertEquals(
            "明文 http 目标不得作为带凭据的跳转目标",
            false,
            EndpointPolicy.sameOriginRedirectAllowed(
                "https://reader.example.com",
                "http://reader.example.com/moved",
            ),
        )
        assertEquals(
            "跨 origin 目标不得作为带凭据的跳转目标",
            false,
            EndpointPolicy.sameOriginRedirectAllowed(
                "https://reader.example.com",
                "https://evil.example.com/moved",
            ),
        )
        assertEquals(
            "同源 https 目标放行（跳转本身可继续，凭据仍只在原 origin 上）",
            true,
            EndpointPolicy.sameOriginRedirectAllowed(
                "https://reader.example.com",
                "https://reader.example.com/api/other",
            ),
        )
    }

    @Test
    fun timeouts_are_bounded_not_infinite() {
        // 生产超时必须是有界值，避免请求永久挂住
        assertEquals(15L, HttpClients.GENERAL_TIMEOUT_SECONDS)
        assertEquals(60L, HttpClients.AI_TIMEOUT_SECONDS)
    }

    @Test
    fun unroutable_host_fails_instead_of_hanging() {
        try {
            // 目标端口无人监听：必须快速失败，而不是长时间挂起
            HttpClients.forPublic()
                .newCall(HttpClients.request("http://127.0.0.1:1/feed.xml"))
                .execute()
                .close()
            fail("连不上时应抛 IOException")
        } catch (expected: java.io.IOException) {
            // 预期
        }
    }
}