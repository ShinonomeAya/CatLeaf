package dev.local.catleaf.nativeapp.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T12 验收③的另一半：公开抓取通道（文章原文、正文图片）的边界。
 * 这些规则与后端 `secureFetch` 一致，任何一条失效都意味着
 * “App 会替外部 HTML 去访问本机/内网”或“凭据会跟着资源请求出网”。
 *
 * 用 MockWebServer 直连：地址策略单独测（见 EndpointPolicyTest），
 * 这里关注抓取机制本身（逐跳、上限、字节记账、不自动跟随），
 * 因此注入一个放行的地址策略，让 mock 的 http 地址可用。
 */
class PublicFetchTest {

    @Test fun timeout_while_reading_the_body_is_reported_as_timeout() {
        withServer { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("body")
                .setBodyDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS))
            val client = HttpClients.forPublic().newBuilder()
                .readTimeout(100, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            val result = PublicFetch(client = client, targetPolicy = { Result.success(it) })
                .fetch(server.url("/feed").toString())
            assertTrue(result.exceptionOrNull() is PublicFetch.FetchFailure.Timeout)
        }
    }

    @Test fun image_origin_is_sanitized_and_preserved_across_controlled_redirects() {
        withServer { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", "/image"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("image"))
            assertTrue(permissive().fetch(server.url("/start").toString(), PublicFetch.MAX_IMAGE_BYTES,
                "https://sspai.com/post/115166?token=PRIVATE#section").isSuccess)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("https://sspai.com/", request.getHeader("Referer"))
                assertEquals(null, request.getHeader("Authorization"))
                assertEquals(null, request.getHeader("Cookie"))
            }
            for (source in listOf("http://sspai.com/a", "https://user:secret@sspai.com/a", "https://127.0.0.1/a", "https://localhost/a", "javascript:alert(1)", null)) {
                server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("image"))
                assertTrue(permissive().fetch(server.url("/image").toString(), PublicFetch.MAX_IMAGE_BYTES, source).isSuccess)
                assertEquals(null, server.takeRequest().getHeader("Referer"))
            }
        }
    }

    @Test
    fun image_limit_accepts_large_images_and_rejects_one_extra_byte() {
        withServer { server ->
            for (size in listOf(3 * 1024 * 1024, 8 * 1024 * 1024, 10 * 1024 * 1024)) {
                server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(ByteArray(size))))
                assertEquals(size, permissive().fetch(server.url("/image").toString(), PublicFetch.MAX_IMAGE_BYTES).getOrThrow().bytes.size)
            }
            val oversized = okio.Buffer().write(ByteArray(10 * 1024 * 1024 + 1))
            for (chunked in listOf(false, true)) {
                val response = okhttp3.mockwebserver.MockResponse()
                server.enqueue(if (chunked) response.setChunkedBody(oversized.clone(), 64 * 1024) else response.setBody(oversized.clone()))
                assertTrue(permissive().fetch(server.url("/oversized").toString(), PublicFetch.MAX_IMAGE_BYTES).exceptionOrNull() is PublicFetch.FetchFailure.TooLarge)
            }
        }
    }

    private fun permissive(): PublicFetch =
        PublicFetch(
            client = HttpClients.forPublic(),
            targetPolicy = { Result.success(it) },
        )

    private inline fun withServer(block: (okhttp3.mockwebserver.MockWebServer) -> Unit) {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun follows_redirects_manually_up_to_three_hops() {
        withServer { server ->
            // 3 次跳转后落在正文上：应当成功
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", "/h1"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", "/h2"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", "/h3"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("<html>正文</html>"))

            val result = permissive().fetch(server.url("/start").toString())

            assertEquals("3 跳后应拿到正文", "<html>正文</html>", result.getOrNull()?.bytes?.decodeToString())
            assertEquals("应恰好发出 4 次请求（1 次起始 + 3 跳）", 4, server.requestCount)
        }
    }

    @Test
    fun rejects_the_fourth_redirect() {
        withServer { server ->
            repeat(5) { index ->
                server.enqueue(
                    okhttp3.mockwebserver.MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", "/hop$index"),
                )
            }

            val error = permissive().fetch(server.url("/start").toString()).exceptionOrNull()

            assertTrue(
                "超过 3 跳必须失败，实际：${error?.message}",
                error is PublicFetch.FetchFailure.Blocked && error.message!!.contains("3"),
            )
            assertEquals("上限 3 跳时总共只应发出 4 次请求", 4, server.requestCount)
        }
    }

    @Test
    fun resolves_relative_location_headers() {
        withServer { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", "sub/page.html"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("ok"))

            val result = permissive().fetch(server.url("/dir/start").toString())

            assertEquals("相对 Location 应基于当前地址解析", "ok", result.getOrNull()?.bytes?.decodeToString())
            server.takeRequest()
            assertEquals(
                "第二跳应落在 /dir/sub/page.html",
                "/dir/sub/page.html",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun rejects_a_body_larger_than_the_cap_during_transfer() {
        withServer { server ->
            // 不声明 content-length，逼迫实现按实际传输字节记账
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setChunkedBody("x".repeat(700_000), 64_000),
            )

            val error = permissive().fetch(server.url("/big").toString(), maxBytes = 500_000).exceptionOrNull()

            assertEquals(
                "超出上限必须在读取过程中失败",
                true,
                error is PublicFetch.FetchFailure.TooLarge,
            )
        }
    }

    @Test
    fun rejects_a_declared_oversize_body_without_reading_it() {
        withServer { server ->
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setBody("x".repeat(600_000)),
            )

            val error = permissive().fetch(server.url("/big").toString(), maxBytes = 500_000).exceptionOrNull()

            assertEquals("声明超限也要失败", true, error is PublicFetch.FetchFailure.TooLarge)
        }
    }

    @Test
    fun production_policy_blocks_plain_http_and_localhost() {
        withServer { server ->
            // 不注入策略：走生产的 requirePublicFetchTarget（仅 HTTPS、非内网）
            val fetch = PublicFetch(client = HttpClients.forPublic())
            val url = server.url("/article").toString()

            val error = fetch.fetch(url).exceptionOrNull()

            assertTrue(
                "http 目标必须被拒（实际：${error?.message}）",
                error is EndpointError.Invalid && error.message!!.contains("HTTPS"),
            )
            assertEquals("被拒的目标不得发出任何请求", 0, server.requestCount)
        }
    }

    @Test
    fun public_requests_carry_no_app_credentials() {
        withServer { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("ok"))

            permissive().fetch(server.url("/img.png").toString()).getOrThrow()

            val recorded = server.takeRequest()
            assertEquals("原站抓取不得带实例密钥", null, recorded.getHeader("x-admin-secret"))
            assertEquals("原站抓取不得带 AI Key", null, recorded.getHeader("Authorization"))
            assertEquals("原站抓取不得带 Cookie", null, recorded.getHeader("Cookie"))
        }
    }

    @Test
    fun non_success_status_is_reported_not_treated_as_content() {
        withServer { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404).setBody("not found"))

            val error = permissive().fetch(server.url("/missing").toString()).exceptionOrNull()

            assertEquals("404 必须算失败", true, error is PublicFetch.FetchFailure.Http && error.code == 404)
        }
    }

    @Test
    fun blocked_redirect_target_is_rejected_on_the_hop() {
        withServer { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", "/internal"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("secret"))

            // 策略只放行起始地址：第二跳会被拒（模拟“起始地址合法、跳转目标违规”）
            val start = server.url("/start").toString()
            val fetch = PublicFetch(
                client = HttpClients.forPublic(),
                targetPolicy = { target -> if (target == start) Result.success(target) else Result.failure(EndpointError.Invalid("拦下")) },
            )

            val error = fetch.fetch(start).exceptionOrNull()

            assertEquals("被拦下的跳转目标不得继续抓取", "拦下", error?.message)
            assertEquals("跳转目标被拦时只应有一次请求", 1, server.requestCount)
            assertFalse("不得把跳转目标的响应内容当成结果", error is PublicFetch.FetchFailure.Http)
        }
    }
}
