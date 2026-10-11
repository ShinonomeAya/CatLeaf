package dev.local.catleaf.nativeapp.ui.reader

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.offline.OfflinePackageStore
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T12 验收②在**真实 WebView** 上的检查：JS 是否关闭、脚本文本是否执行、
 * 外链是否交给系统浏览器而不是留在正文 WebView、未知资源是否拒绝、
 * 图片失败是否只影响图片。
 *
 * 这里刻意把“净化器”和“WebView 配置”分开验证：
 * 净化器去掉脚本是一层（见 JVM 的 ArticleSanitizerTest），
 * 即使有脚本文本进了页面，关闭 JS 之后也不执行——那是本文件要证明的。
 */
@RunWith(AndroidJUnit4::class)
class ArticleWebViewTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val externalUrls = Collections.synchronizedList(mutableListOf<String>())
    private val blockedUrls = Collections.synchronizedList(mutableListOf<String>())

    private fun client(bodyHtml: String, publicFetch: PublicFetch = PublicFetch()) = ArticleBodyWebViewClient(
        assets = ReaderAssets(bodyHtml),
        publicFetch = publicFetch,
        onExternalUrl = { externalUrls += it },
        onBlockedNavigation = { blockedUrls += it },
        onRequest = { requests += it },
    )

    /**
     * 在真实 WebView 里加载一页并等它渲染完。
     * 返回标题用于判断脚本有没有执行：脚本改标题需要 JS，而 JS 是关的。
     */
    private fun load(bodyHtml: String): Pair<WebView, String?> {
        val finished = CountDownLatch(1)
        lateinit var webView: WebView
        val webViewClient = ArticleBodyWebViewClient(
            assets = ReaderAssets(bodyHtml),
            publicFetch = PublicFetch(),
            onExternalUrl = { externalUrls += it },
            onBlockedNavigation = { blockedUrls += it },
            onRequest = { requests += it },
            onPageLoaded = { finished.countDown() },
        )
        instrumentation.runOnMainSync {
            webView = WebView(context)
            configureArticleWebView(webView, context)
            webView.webViewClient = webViewClient
            webView.loadUrl(ReaderAssets.PAGE_URL)
        }
        assertTrue("正文页应在 15 秒内加载完成", finished.await(15, TimeUnit.SECONDS))

        // 标题由渲染器设置，可能在 onPageFinished 之后再到达
        var title: String? = null
        val deadline = System.currentTimeMillis() + 3_000
        while (title == null && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { title = webView.title }
            if (title == null) Thread.sleep(100)
        }
        return webView to title
    }

    private fun destroy(webView: WebView) {
        instrumentation.runOnMainSync {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    private fun newWebView(webViewClient: ArticleBodyWebViewClient): WebView {
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(context)
            configureArticleWebView(webView, context)
            webView.webViewClient = webViewClient
        }
        return webView
    }

    // ── ① WebView 开关 ──

    @Test
    fun webview_is_locked_down() {
        // WebView 的方法只能在主线程调用（读 settings 也算），所以先取值再断言
        var javaScriptEnabled = true
        var domStorageEnabled = true
        var allowFileAccess = true
        var allowContentAccess = true
        var allowFileAccessFromFile = true
        var allowUniversalAccessFromFile = true
        var mixedContent = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        var canOpenWindows = true

        instrumentation.runOnMainSync {
            val webView = WebView(context)
            configureArticleWebView(webView, context)
            val settings = webView.settings
            javaScriptEnabled = settings.javaScriptEnabled
            domStorageEnabled = settings.domStorageEnabled
            allowFileAccess = settings.allowFileAccess
            allowContentAccess = settings.allowContentAccess
            allowFileAccessFromFile = settings.allowFileAccessFromFileURLs
            allowUniversalAccessFromFile = settings.allowUniversalAccessFromFileURLs
            mixedContent = settings.mixedContentMode
            canOpenWindows = settings.javaScriptCanOpenWindowsAutomatically
            webView.destroy()
        }

        assertEquals("JavaScript 必须关闭", false, javaScriptEnabled)
        assertEquals("DOM storage 必须关闭", false, domStorageEnabled)
        assertEquals("文件访问必须关闭", false, allowFileAccess)
        assertEquals("content:// 访问必须关闭", false, allowContentAccess)
        assertEquals("文件来源的跨域访问必须关闭", false, allowFileAccessFromFile)
        assertEquals("任意来源的跨域访问必须关闭", false, allowUniversalAccessFromFile)
        assertEquals("混合内容必须禁止", WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContent)
        assertEquals("不允许脚本开新窗口", false, canOpenWindows)
    }

    // ── ② 脚本文本不执行、不产生外部请求 ──

    @Test
    fun script_text_in_the_page_does_not_execute() {
        val body = """
            <p>正文</p>
            <script>
              document.title = 'PWNED';
              document.write('<img src="https://tracker.example.net/pixel.png">');
            </script>
        """.trimIndent()

        val (webView, title) = load(body)

        assertEquals(
            "脚本必须没有执行（标题仍是 App 写入的静态标题）",
            ReaderAssets.PAGE_TITLE,
            title,
        )
        assertFalse(
            "脚本生成的图片请求不得出现：${requests.joinToString()}",
            requests.any { it.contains("tracker.example.net") },
        )
        destroy(webView)
    }

    // ── ③ 外链交给系统浏览器 ──

    private class FakeRequest(private val url: String, private val method: String = "GET") : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = method
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    @Test
    fun external_link_is_handed_to_the_system_browser_not_the_webview() {
        val webViewClient = client("<p>正文</p>")
        val webView = newWebView(webViewClient)

        val handled = webViewClient.shouldOverrideUrlLoading(
            webView,
            FakeRequest("https://blog.example.com/posts/other"),
        )

        assertEquals("外链必须由我们处理（WebView 不导航）", true, handled)
        assertEquals(
            "外链应交给系统浏览器",
            listOf("https://blog.example.com/posts/other"),
            externalUrls.toList(),
        )
        assertTrue("被拦下的列表应为空", blockedUrls.isEmpty())

        destroy(webView)
    }

    @Test
    fun dangerous_schemes_are_blocked_and_never_opened() {
        val webViewClient = client("<p>正文</p>")
        val webView = newWebView(webViewClient)

        for (url in listOf(
            "javascript:alert(1)",
            "intent://evil#Intent;scheme=https;end",
            "file:///etc/passwd",
            "content://com.example/secret",
            "data:text/html,<script>alert(1)</script>",
        )) {
            val handled = webViewClient.shouldOverrideUrlLoading(webView, FakeRequest(url))
            assertEquals("$url 必须被拦下（不导航）", true, handled)
        }

        assertTrue("危险 scheme 不得交给系统浏览器", externalUrls.isEmpty())
        assertEquals("五个危险地址都应记录为拦下", 5, blockedUrls.size)

        destroy(webView)
    }

    // ── ④ 未知资源与不合规目标一律拒绝 ──

    @Test
    fun unknown_local_paths_and_insecure_targets_are_denied() {
        val webViewClient = client("<p>正文</p>")
        val webView = newWebView(webViewClient)

        val denied = mapOf(
            "本地来源上的未知路径" to "https://appassets.androidplatform.net/reader/other.html",
            "明文 http 资源" to "http://cdn.example.com/a.png",
            "本机地址" to "https://127.0.0.1/a.png",
            "内网地址" to "https://10.0.0.5/a.png",
            "带凭据的地址" to "https://user:pw@cdn.example.com/a.png",
            "POST 请求" to "https://cdn.example.com/a.png",
        )
        for ((label, url) in denied) {
            val response = webViewClient.shouldInterceptRequest(
                webView,
                FakeRequest(url, if (label == "POST 请求") "POST" else "GET"),
            )
            val deniedResponse = requireNotNull(response) { "$label 必须被拒绝而不是放行" }
            assertEquals("$label 应返回拒绝响应", 403, deniedResponse.statusCode)
        }

        destroy(webView)
    }

    // ── ⑤ 图片失败给占位，不影响文字 ──

    @Test
    fun failed_image_returns_a_placeholder() {
        val webViewClient = client("<p>正文文字</p>")
        val webView = newWebView(webViewClient)

        val response = webViewClient.shouldInterceptRequest(
            webView,
            FakeRequest("https://this-host-does-not-exist.invalid/a.png"),
        )

        val placeholder = requireNotNull(response) {
            "图片失败也要给响应（占位），不能返回 null 让 WebView 直接走网络"
        }
        assertEquals("占位图用 SVG", "image/svg+xml", placeholder.mimeType)
        val body = placeholder.data.readBytes().decodeToString()
        assertTrue("占位图里应有可读说明：$body", body.contains("图片无法加载"))

        destroy(webView)
    }

    @Test
    fun offline_snapshot_serves_only_mapped_private_resource_and_never_fetches_external() {
        val file = java.io.File(context.noBackupFilesDir, "offline-image-test-${System.nanoTime()}")
        file.writeBytes(byteArrayOf(1, 2, 3))
        try {
            val id = "a".repeat(64)
            val assets = ReaderAssets("<p>离线</p>", offlineResources = mapOf(id to OfflinePackageStore.Resource(file, "image/png")))
            val webViewClient = ArticleBodyWebViewClient(assets, offlineSnapshot = true)
            val view = newWebView(webViewClient)
            try {
                val local = webViewClient.shouldInterceptRequest(view, FakeRequest("https://appassets.androidplatform.net/offline/$id"))!!
                assertEquals("image/png", local.mimeType)
                assertTrue(local.data.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
                val external = webViewClient.shouldInterceptRequest(view, FakeRequest("https://example.org/unfetched.png"))!!
                assertEquals("image/svg+xml", external.mimeType)
                val missing = webViewClient.shouldInterceptRequest(view, FakeRequest("https://appassets.androidplatform.net/offline/${"b".repeat(64)}"))!!
                assertEquals("image/svg+xml", missing.mimeType)
            } finally { destroy(view) }
        } finally { file.delete() }
    }

    @Test
    fun article_with_a_broken_image_still_renders_and_requests_no_credentials() {
        val rssHtml = """
            <p>第一段正文</p>
            <img src="https://this-host-does-not-exist.invalid/broken.png" alt="配图">
            <p>第二段正文</p>
            <img src="javascript:alert(1)" onerror="alert(2)">
        """.trimIndent()
        val sanitized = ArticleSanitizer.sanitize(rssHtml, "https://blog.example.com/posts/1").html

        val (webView, title) = load(sanitized)

        assertEquals("页面必须加载完成（图片失败不能阻塞正文）", ReaderAssets.PAGE_TITLE, title)
        assertTrue(
            "正文请求里不得出现被净化掉的 javascript: 图片",
            requests.none { it.contains("javascript:") },
        )
        assertTrue(
            "只应请求本地模板与那张图片：${requests.joinToString()}",
            requests.all {
                it.startsWith("https://appassets.androidplatform.net/") || it.contains("does-not-exist.invalid")
            },
        )
        assertTrue(
            "图片确实被请求过（证明这条路径真的走了受控通道）",
            requests.any { it.contains("does-not-exist.invalid") },
        )

        destroy(webView)
    }

    @Test
    fun resource_urls_never_carry_the_instance_credentials() {
        // 验收③后半：资源 URL 里不能出现实例密钥或实例地址。
        // 设备未配置实例时**跳过并标记**（而不是静默通过）——否则“通过”什么也没证明。
        val secret = configuredInstanceSecret()
        val origin = configuredInstanceOrigin()
        org.junit.Assume.assumeTrue(
            "设备未配置实例：跳过密钥/实例地址断言（只保留来源域名断言）",
            secret != null && origin != null,
        )

        val sanitized = ArticleSanitizer.sanitize(
            """<p>正文</p><img src="https://cdn.example.com/a.png" alt="图">""",
            "https://blog.example.com/posts/1",
        ).html

        val (webView, _) = load(sanitized)

        assertTrue(
            "资源 URL 不得包含实例密钥：${requests.joinToString()}",
            requests.none { it.contains(secret!!) },
        )
        assertTrue(
            "资源 URL 不得指向实例地址：${requests.joinToString()}",
            requests.none { it.startsWith(origin!!) },
        )
        assertTrue(
            "请求只允许出现在本地模板或 https 图片主机上：${requests.joinToString()}",
            requests.all { it.startsWith("https://") },
        )
        destroy(webView)
    }

    private fun configuredInstanceSecret(): String? = runCatching {
        runBlocking {
            val key = SettingsStore(context).current().currentInstanceKey ?: return@runBlocking null
            CredentialStore(context).get("instance_secret_$key")
        }
    }.getOrNull()

    private fun configuredInstanceOrigin(): String? = runCatching {
        runBlocking {
            val key = SettingsStore(context).current().currentInstanceKey ?: return@runBlocking null
            dev.local.catleaf.nativeapp.data.local.CatLeafDatabase.get(context).instances().get(key)?.origin
        }
    }.getOrNull()

    @Test
    fun sanitizer_output_is_what_the_webview_serves() {
        // 净化后的片段进模板，模板之外没有别的注入点
        val sanitized = ArticleSanitizer.sanitize("<p>正文</p><script>alert(1)</script>", null).html
        val page = ReaderAssets.page(sanitized)

        assertTrue("页面应包含净化后的正文", page.contains("<p>正文</p>"))
        assertFalse("页面里不得出现脚本", page.contains("<script"))
        assertTrue("应带 CSP 兜底", page.contains("Content-Security-Policy"))
        assertNull("不存在的本地路径不应由模板提供", ReaderAssets(sanitized).loader().shouldInterceptRequest(
            Uri.parse("https://appassets.androidplatform.net/reader/nope.html"),
        ))
    }
}
