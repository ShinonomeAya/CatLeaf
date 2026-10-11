package dev.local.catleaf.nativeapp.data.article

import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.network.FeedApi
import dev.local.catleaf.nativeapp.data.network.HttpClients
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全文提取的完整链路（契约 3.3 的失败顺序）：本机直连 → 自有后端取原始 HTML →
 * Readability 提取 → 同一净化入口。用 MockWebServer 分辨两个来源分别收到了什么请求。
 *
 * 注入放行的地址策略只是为了能用 mock 的 http 地址；地址策略本身在
 * EndpointPolicyTest / PublicFetchTest 里单独测（含仅 HTTPS、非内网、无凭据）。
 */
class FullTextExtractionTest {

    private val secret = "FAKE-INSTANCE-SECRET-extract-test"

    /**
     * 真实文章页面的形状：导航、广告位、多段正文、图片、页脚。
     * 正文段落放够密度——Readability 这类算法靠密度打分决定“哪块是正文”，
     * 三行假页面测不出真实行为（探针实测：段落不足时会连导航一起留下）。
     */
    private val paragraphs = (1..12).joinToString("") { i ->
        "<p>第 $i 段正文内容：这里放足够长的中文文字，用来模拟真实文章页的正文密度。</p>"
    }

    private val articlePage = """
        <!DOCTYPE html><html><head><title>示例文章</title>
        <meta charset="utf-8">
        <script>window.ads = 1;</script>
        <style>.ad { display: block }</style>
        </head><body>
        <nav><a href="/">首页</a><a href="/about">关于</a></nav>
        <div class="ad"><a href="https://ads.example.net/x">赞助商广告</a></div>
        <article>
          <h1>让阅读变得轻松</h1>
          <p class="byline">作者：某编辑</p>
          $paragraphs
          <img src="/images/cover.png" alt="封面">
          <a href="javascript:alert(1)">坏链接</a>
        </article>
        <aside><ul><li><a href="/hot">热门文章</a></li></ul></aside>
        <footer><p>版权所有 © 示例站点</p></footer>
        <script>tracker();</script>
        </body></html>
    """.trimIndent()

    private fun server(dispatcher: Dispatcher): MockWebServer = MockWebServer().apply {
        start()
        this.dispatcher = dispatcher
    }

    private fun extractorFor(server: MockWebServer): FullTextExtractor {
        val base = server.url("/").toString().trimEnd('/')
        val fetch = PublicFetch(
            client = HttpClients.forPublic(),
            targetPolicy = { Result.success(it) },
        )
        return FullTextExtractor(api = FeedApi(base, secret), publicFetch = fetch)
    }

    @Test
    fun extracts_article_body_and_sanitizes_it() {
        val server = server(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(articlePage.replace("<p>第三段正文内容，提到收藏与离线阅读。</p>",
                    "<p>第三段正文内容，提到收藏与离线阅读。</p><a href=\"javascript:alert(1)\">坏链接</a>"))
        })
        try {
            val outcome = runBlocking {
                extractorFor(server).extract(server.url("/posts/1").toString(), "标题回退")
            }

            val extracted = outcome as FullTextExtractor.Outcome.Extracted
            assertFalse("本机直连成功时不应走后端兜底", extracted.fromBackend)
            assertTrue("应提取到正文第一段", extracted.html.contains("第 1 段正文内容"))
            assertTrue("应提取到正文最后一段", extracted.html.contains("第 12 段正文内容"))
            assertFalse("导航不应进入正文", extracted.html.contains("首页"))
            assertFalse("广告不应进入正文", extracted.html.contains("ads.example.net"))
            assertFalse("页脚不应进入正文", extracted.html.contains("版权所有"))
            assertFalse("脚本不得进入正文", extracted.html.contains("<script", ignoreCase = true))
            assertFalse("危险协议不得进入正文", extracted.html.contains("javascript:"))
            assertFalse("页面 meta 不应作为正文字段留下", extracted.html.contains("<meta", ignoreCase = true))
            assertTrue(
                "相对图片地址应按文章地址解析，且保留端口：${extracted.html}",
                extracted.html.contains(server.url("/images/cover.png").toString()),
            )


            val recorded = server.takeRequest()
            assertEquals("本机抓取不应带实例密钥", null, recorded.getHeader("x-admin-secret"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun falls_back_to_backend_when_direct_fetch_fails() {
        val server = server(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/api/article/extract") -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(articlePage)

                else -> MockResponse().setResponseCode(500)
            }
        })
        try {
            val articleUrl = server.url("/posts/2").toString()
            val outcome = runBlocking { extractorFor(server).extract(articleUrl, "标题回退") }

            val extracted = outcome as FullTextExtractor.Outcome.Extracted
            assertTrue("直连失败后应标记为后端兜底", extracted.fromBackend)
            assertTrue("后端兜底同样要提取到正文", extracted.html.contains("第 1 段正文内容"))

            val direct = server.takeRequest()
            assertEquals("直连的就是文章地址", "/posts/2", direct.path)
            assertFalse("直连失败不应阻止兜底", direct.path!!.startsWith("/api/"))

            val fallback = server.takeRequest()
            assertTrue(
                "兜底必须走 mode=raw（客户端自己解析）：${fallback.path}",
                fallback.path.orEmpty().contains("mode=raw"),
            )
            assertTrue(
                "兜底请求应带上实例密钥（它打向自有实例）",
                fallback.path.orEmpty().contains("url="),
            )
            assertEquals("兜底请求走实例 client", secret, fallback.getHeader("x-admin-secret"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun reports_failure_when_no_article_body_can_be_found() {
        val server = server(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><body></body></html>")
        })
        try {
            val outcome = runBlocking {
                extractorFor(server).extract(server.url("/empty").toString(), null)
            }

            val failed = outcome as FullTextExtractor.Outcome.Failed
            assertTrue("识别不出正文时必须报失败：${failed.reason}", failed.reason.contains("正文"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun short_extraction_is_rejected_when_it_adds_nothing() {
        val server = server(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                // 只有导航的页面：Readability 会把“首页”当正文返回
                .setBody("<html><body><nav><a href=\"/\">首页</a><a href=\"/about\">关于</a></nav></body></html>")
        })
        try {
            val outcome = runBlocking {
                extractorFor(server).extract(server.url("/navonly").toString(), null, currentTextLength = 500)
            }

            val failed = outcome as FullTextExtractor.Outcome.Failed
            assertTrue("过短的提取结果必须报失败：${failed.reason}", failed.reason.contains("过短"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun short_extraction_is_accepted_when_there_was_no_content_before() {
        val server = server(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><body><p>很短的正文，但比什么都没有好。</p></body></html>")
        })
        try {
            val outcome = runBlocking {
                extractorFor(server).extract(server.url("/tiny").toString(), null, currentTextLength = 0)
            }

            val extracted = outcome as FullTextExtractor.Outcome.Extracted
            assertTrue("原本没有正文时，短正文也应接受", extracted.html.contains("很短的正文"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun reports_failure_when_both_direct_and_backend_fail() {
        val server = server(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(502)
        })
        try {
            val outcome = runBlocking {
                extractorFor(server).extract(server.url("/broken").toString(), null)
            }

            val failed = outcome as FullTextExtractor.Outcome.Failed
            assertTrue("原因里要同时说明两次尝试：${failed.reason}", failed.reason.contains("后端"))
            assertEquals("两个来源各发一次请求", 2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun non_https_article_url_never_leaves_the_device_directly() {
        val server = server(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(articlePage)
        })
        try {
            // 生产策略（不注入）：明文 http 的文章地址不允许本机直连
            val production = FullTextExtractor(
                api = FeedApi(server.url("/").toString().trimEnd('/'), secret),
                publicFetch = PublicFetch(client = HttpClients.forPublic()),
            )
            val articleUrl = server.url("/posts/3").toString()
            assertTrue("测试地址本身是明文 http", articleUrl.startsWith("http://"))

            val outcome = runBlocking { production.extract(articleUrl, null) }

            val extracted = outcome as FullTextExtractor.Outcome.Extracted
            assertTrue("只能是后端兜底拿到的内容", extracted.fromBackend)
            val first = server.takeRequest()
            assertFalse("明文地址不得由本机直接抓取", first.path.orEmpty().startsWith("/posts/3"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun production_policy_is_the_default_for_public_fetch() {
        // 默认参数必须是生产策略，而不是放行策略（否则上面的注入会悄悄变成默认行为）
        val failed = PublicFetch().fetch("https://127.0.0.1/article")
        assertTrue("默认策略必须拒绝本机地址", failed.isFailure)
        assertTrue(
            "拒绝原因应来自地址策略",
            failed.exceptionOrNull() is dev.local.catleaf.nativeapp.data.network.EndpointError.Invalid,
        )
        assertEquals(
            "策略函数本身仍可用（防止上面的断言因拼写错误而恒真）",
            false,
            EndpointPolicy.requirePublicFetchTarget("https://127.0.0.1/article").isSuccess,
        )
    }
}