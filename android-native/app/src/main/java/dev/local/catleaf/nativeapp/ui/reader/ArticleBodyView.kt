package dev.local.catleaf.nativeapp.ui.reader

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.settings.ScrollAnchor
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.offline.OfflinePackageStore
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import kotlin.math.roundToInt

/**
 * 受控正文 WebView（契约第 7 节）。这里的每一条设置都在验收清单里：
 *
 * - 关闭 JavaScript、DOM storage、文件/content 访问、混合内容，不注册原生 JS 桥；
 * - 正文与模板只从 App 控制的 HTTPS 虚拟来源（[WebViewAssetLoader]）加载，
 *   从不 `loadUrl` 打开文章原站；
 * - **所有**子资源请求都经过 [ArticleBodyWebViewClient]：本地来源上的未知路径拒绝，
 *   其余（图片）走 [PublicFetch]（仅 HTTPS、逐跳校验、限额、无凭据），失败给占位；
 *   因此不存在“漏回默认网络加载”的路径；
 * - 外链点击交给系统浏览器，正文 WebView 自身不导航。
 */
class ReaderAssets(
    private val bodyHtml: String,
    /** 页首块（大标题/元信息/封面，本地生成、已转义）：与正文同一滚动容器，随正文滚出视口（T22 F01/F02） */
    private val headerHtml: String? = null,
    private val fontSizeSp: Int = 18,
    private val dark: Boolean = false,
    private val offlineResources: Map<String, OfflinePackageStore.Resource>? = null,
) {

    private val handler = object : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? =
            when (path) {
                INDEX_PATH -> htmlResponse(page(bodyHtml, fontSizeSp, dark, headerHtml))
                else -> null
            }
    }

    fun loader(): WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/$MOUNT/", handler)
        .addPathHandler("/offline/", WebViewAssetLoader.PathHandler { path ->
            val id = path.substringBefore('/')
            val resource = offlineResources?.get(id) ?: return@PathHandler null
            if (!id.matches(Regex("[0-9a-f]{64}"))) return@PathHandler null
            runCatching { WebResourceResponse(resource.mime, null, FileInputStream(resource.file)) }.getOrNull()
        })
        .build()

    companion object {
        /** WebViewAssetLoader 约定的本地虚拟来源 */
        const val DOMAIN = "appassets.androidplatform.net"
        private const val MOUNT = "reader"
        private const val INDEX_PATH = "index.html"

        /** 正文页固定地址：App 控制，不代表任何外部站点 */
        const val PAGE_URL = "https://$DOMAIN/$MOUNT/$INDEX_PATH"

        private fun htmlResponse(html: String): WebResourceResponse = WebResourceResponse(
            "text/html",
            "utf-8",
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )

        /**
         * 正文页模板：样式由 App 提供，正文只作为已净化片段插入，页首块只作为本地转义片段插入。
         * CSP 是第二道防线——即使有内容绕过净化，也没有脚本源可加载，图片只允许 https。
         */
        fun page(bodyHtml: String, fontSizeSp: Int = 18, dark: Boolean = false, headerHtml: String? = null): String {
            val header = headerHtml?.takeIf { it.isNotBlank() }
                ?.let { "<header class=\"catleaf-header\">$it</header>" }
                .orEmpty()
            return """
                <!DOCTYPE html>
                <html lang="zh-CN"><head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="Content-Security-Policy"
                      content="default-src 'none'; img-src https:; style-src 'unsafe-inline'">
                <title>$PAGE_TITLE</title>
                <style>${cssFor(fontSizeSp, dark)}</style>
                </head><body>$header<article class="catleaf-body">$bodyHtml</article></body></html>
            """.trimIndent()
        }

        /** 进本地模板的外部文本（标题、署名、URL）必须转义：JS 是关着的，转义是唯一防线 */
        fun escapeHtml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        /**
         * 主题由 App 决定（P07：强制深色时正文也必须深），**不跟系统 media query 走**——
         * 否则"App 深色 + 系统浅色"会出现 chrome 深、正文白的不一致（T22 真机发现）。
         * 色值取设计规范（深色 #181D1A 底 / #E5E9E3 字 / #94C9A6 链接 / #232A25 代码底）。
         * 字号只改 body 基准（16..22）；正文内标题用 em 跟随；
         * 页首文章标题是独立 token 28/36（V03），不随用户正文字号、只随系统缩放。
         */
        internal fun cssFor(fontSizeSp: Int, dark: Boolean): String {
            val light = """
                body { background: #FAF9F6; color: #252B28; }
                a { color: #356A52; } img { background: #E4E6DF; }
                blockquote { border-left-color: #356A52; color: #6C756F; }
                pre, code { background: #E8EEE7; } hr, th, td { border-color: #E4E6DF; } th { background: #E8EEE7; }
            """.trimIndent()
            val darkCss = """
                body { background: #181D1A; color: #E5E9E3; }
                a { color: #94C9A6; } img { background: #39443C; }
                blockquote { border-left-color: #94C9A6; color: #ABB5AB; }
                pre, code { background: #232A25; } hr, th, td { border-color: #39443C; } th { background: #232A25; }
                .catleaf-meta, .catleaf-hint { color: #ABB5AB; }
            """.trimIndent()
            val mediaStart = BASE_CSS.indexOf("@media (prefers-color-scheme: dark)")
            val mediaEnd = BASE_CSS.lastIndexOf('}')
            val base = BASE_CSS.removeRange(mediaStart, mediaEnd + 1).trimEnd() + "\n" +
                (if (dark) darkCss else light)
            return base.replace("font-size: 18px;", "font-size: ${fontSizeSp.coerceIn(16, 22)}px;") + "\n" +
                ".catleaf-translation { display: block; margin: .45em 0 .8em; padding-left: 12px; " +
                "border-left: 2px solid ${if (dark) "#94C9A6" else "#356A52"}; " +
                "color: ${if (dark) "#94C9A6" else "#356A52"}; font-size: ${fontSizeSp.coerceIn(16, 22)}px; line-height: 1.67; font-weight: 400; }\n" +
                ".catleaf-title-translation { font-size: 22px; line-height: 1.5; margin-bottom: 16px; }\n" +
                ".catleaf-translation-controls { margin-bottom: 12px; }\n" +
                ".catleaf-translation-status { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; font-size: 13px; color: ${if (dark) "#94C9A6" else "#356A52"}; }\n" +
                ".catleaf-retranslate { display: inline-flex; align-items: center; min-height: 48px; min-width: 48px; padding: 0 8px; text-decoration: none; }"
        }

        /**
         * 静态标题：正文 HTML 里的 title 已被净化删除，这里固定写死，
         * 既避免把外部字符串当标题，也让“脚本有没有执行”在设备上可观测
         * （脚本改标题需要 JS，而 JS 是关闭的）。
         */
        const val PAGE_TITLE = "CatLeaf"

        /** 排版与颜色按 docs/07-设计规范.md：正文 18px/1.75、暖白底、森林绿链接 */
        private val BASE_CSS = """
            :root { color-scheme: light dark; }
            html { -webkit-text-size-adjust: 100%; }
            body {
              margin: 0; padding: 8px 20px 24px;
              background: #FAF9F6; color: #252B28;
              font-size: 18px; line-height: 1.67;
              font-family: system-ui, sans-serif;
              overflow-wrap: break-word; word-break: break-word;
            }
            .catleaf-header { margin-bottom: 1.2em; }
            /* 文章主标题是独立 token（设计规范 28/36/700，V03）：不随用户正文字号变，
               但仍响应系统缩放——WebView textZoom 对 CSS px 同样生效 */
            .catleaf-title { font-size: 28px; line-height: 36px; font-weight: 700; margin: 0.1em 0 0.4em; overflow-wrap: break-word; }
            .catleaf-meta { font-size: 13px; line-height: 20px; color: #6C756F; }
            .catleaf-hint { font-size: 13px; line-height: 20px; color: #6C756F; margin-top: 8px; }
            .catleaf-cover { width: 100%; margin: 12px 0 14px; border-radius: 10px; }
            .catleaf-body > :first-child { margin-top: 0; }
            p, li, figcaption { margin: 0.9em 0; }
            h1, h2, h3, h4, h5, h6 { line-height: 1.35; margin: 1.6em 0 0.6em; font-weight: 700; }
            h1 { font-size: 1.6em; } h2 { font-size: 1.35em; } h3 { font-size: 1.15em; }
            h4, h5, h6 { font-size: 1em; }
            a { color: #356A52; text-decoration: none; }
            img { max-width: 100%; height: auto; border-radius: 10px; background: #E4E6DF; }
            figure { margin: 1.2em 0; }
            blockquote {
              margin: 1.2em 0; padding: 0.2em 0 0.2em 1em;
              border-left: 3px solid #356A52; color: #6C756F;
            }
            pre {
              background: #E8EEE7; border-radius: 10px; padding: 12px 14px;
              overflow-x: auto; font-size: 0.92em; line-height: 1.6;
            }
            code { background: #E8EEE7; border-radius: 4px; padding: 0 4px; font-size: 0.92em; }
            pre code { background: none; padding: 0; }
            hr { border: none; border-top: 1px solid #E4E6DF; margin: 1.8em 0; }
            table { display: block; overflow-x: auto; border-collapse: collapse; margin: 1.2em 0; }
            th, td { border: 1px solid #E4E6DF; padding: 6px 10px; text-align: left; }
            th { background: #E8EEE7; }
            @media (prefers-color-scheme: dark) {
              body { background: #181D1A; color: #E5E9E3; }
              a { color: #94C9A6; }
              img { background: #39443C; }
              blockquote { border-left-color: #94C9A6; color: #ABB5AB; }
              pre, code { background: #232A25; }
              hr, th, td { border-color: #39443C; }
              th { background: #232A25; }
            }
        """.trimIndent()
    }
}

/**
 * 正文 WebView 的请求与导航策略。
 * 内部不持有凭据、不发任何带凭据的请求——图片走公开通道（[HttpClients.forPublic] 建的 client）。
 */
class ArticleBodyWebViewClient(
    assets: ReaderAssets,
    private val publicFetch: PublicFetch = PublicFetch(),
    /** 外链交给系统浏览器；正文 WebView 自己不导航 */
    private val onExternalUrl: (String) -> Unit = {},
    /** 被拦下的导航（javascript:、intent: 等） */
    private val onBlockedNavigation: (String) -> Unit = {},
    /** 所有经过拦截的请求地址，供设备验收与测试核对 */
    private val onRequest: (String) -> Unit = {},
    /** 页面加载完成（设备验收与测试用来确认“正文已渲染”） */
    private val onPageLoaded: (String) -> Unit = {},
    private val offlineSnapshot: Boolean = false,
    private val articleUrl: String? = null,
) : WebViewClient() {

    private val assetLoader = assets.loader()

    override fun onPageFinished(view: WebView, url: String) {
        onPageLoaded(url)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        onRequest(url)

        // 1. 本地模板与正文
        assetLoader.shouldInterceptRequest(request.url)?.let { return it }
        // 2. 本地来源上的未知路径：拒绝（不允许有任何“顺手加载”的空间）
        if (url.startsWith(ORIGIN)) return if (offlineSnapshot) placeholder() else denied()
        // 冻结的离线正文绝不回源取图片，包括旧包中残留的外部地址。
        if (offlineSnapshot) return placeholder()
        // 3. 只有 GET 且通过公开抓取策略（仅 HTTPS、无凭据、非内网）才去取
        if (!request.method.equals("GET", ignoreCase = true)) return denied()
        if (EndpointPolicy.requirePublicFetchTarget(url).isFailure) return denied()
        // 4. 受控图片通道：失败给占位，文字不受影响
        return fetchResource(url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (EndpointPolicy.isSafePublicUrl(url)) {
            onExternalUrl(url)
        } else {
            onBlockedNavigation(url)
        }
        // 无论哪种，都不在正文 WebView 里导航：外站只走系统浏览器
        return true
    }

    private fun fetchResource(url: String): WebResourceResponse =
        publicFetch.fetch(url, PublicFetch.MAX_IMAGE_BYTES, articleUrl).fold(
            onSuccess = { fetched ->
                WebResourceResponse(mimeOf(fetched.contentType), null, ByteArrayInputStream(fetched.bytes))
            },
            onFailure = { placeholder() },
        )

    private fun mimeOf(contentType: String?): String =
        contentType?.substringBefore(';')?.trim()?.takeIf { it.contains('/') } ?: "application/octet-stream"

    /** 图片失败/离线时的占位：说明文字在图里，alt 仍在正文里 */
    private fun placeholder(): WebResourceResponse = WebResourceResponse(
        "image/svg+xml",
        "utf-8",
        ByteArrayInputStream(PLACEHOLDER_SVG.toByteArray(Charsets.UTF_8)),
    )

    private fun denied(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        private val ORIGIN = "https://${ReaderAssets.DOMAIN}"

        /** 纯静态占位图：不引用任何外部资源，颜色与设计规范一致 */
        private val PLACEHOLDER_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" width="100%" height="104" viewBox="0 0 320 104">
              <rect width="320" height="104" rx="10" fill="#E4E6DF"/>
              <text x="160" y="58" text-anchor="middle" font-family="sans-serif" font-size="14" fill="#6C756F">
                图片无法加载
              </text>
            </svg>
        """.trimIndent()
    }
}

/**
 * 正文 WebView 的开关设置。单独成函数，便于用真实 WebView 断言
 * （测试直接读 webView.settings，而不是读一个“配置对象”）。
 */
fun configureArticleWebView(webView: WebView, context: Context = webView.context) {
    val settings = webView.settings
    settings.javaScriptEnabled = false
    settings.domStorageEnabled = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.setGeolocationEnabled(false)
    // 系统字体缩放继续生效；正文尺寸只由 CSS 决定，避免双重缩放
    settings.textZoom = (context.resources.configuration.fontScale * 100f).roundToInt()
    // 不调用 addJavascriptInterface：正文里没有任何原生桥
    @Suppress("DEPRECATION")
    settings.savePassword = false
    @Suppress("DEPRECATION")
    settings.saveFormData = false
    webView.isVerticalScrollBarEnabled = true
    webView.isHorizontalScrollBarEnabled = false
    webView.setBackgroundColor(Color.TRANSPARENT)
}

/**
 * Compose 外层：正文页整块交给 WebView 滚动（不嵌套 Compose 滚动），
 * 标题/元信息/操作仍在 Compose 里，二级页整屏。
 */
@Composable
fun ArticleBodyView(
    bodyHtml: String,
    onExternalUrl: (String) -> Unit,
    /** 页首块（大标题/元信息/封面）：与正文一起滚动（T22 F01）；由本地生成并已转义 */
    headerHtml: String? = null,
    /** 上次的阅读位置；布局就绪后按内容高度换算（内容换过则按比例回退，见 ScrollAnchor） */
    scrollAnchor: ScrollAnchor.ReaderAnchor? = null,

    /** 离开正文时回报滚动位置与内容高度，供下次恢复（契约 §7） */
    onScrollPosition: ((scrollY: Int, contentHeight: Int) -> Unit)? = null,
    /** 正文字号 16/18/20/22（P07）；改动即时重载 */
    fontSizeSp: Int = 18,
    /** 主题跟 App 生效的调色板走（P07），不跟系统 media query */
    darkTheme: Boolean = false,
    offlineSnapshot: OfflinePackageStore.Snapshot? = null,
    articleUrl: String? = null,
    modifier: Modifier = Modifier,
    /** Layout identity separates original and bilingual scroll heights without changing source data. */
    displayKey: String = "original",
) {
    val context = LocalContext.current
    val publicFetch = remember { PublicFetch() }
    val assets = remember(bodyHtml, headerHtml, fontSizeSp, darkTheme, offlineSnapshot) {
        ReaderAssets(bodyHtml, headerHtml, fontSizeSp, darkTheme, offlineSnapshot?.resources)
    }
    // Distinct local document URLs prevent cached HTML and stale page-finished callbacks during mode changes.
    val pageUrl = remember(assets) { "${ReaderAssets.PAGE_URL}?render=${java.util.UUID.randomUUID()}" }
    // 恢复值只在首次加载时用一次：正文换过或用户已滚动后不要被重放
    val pendingRestore = remember { mutableStateOf(scrollAnchor) }
    val scrollReporter = rememberUpdatedState(onScrollPosition)
    val holder = remember { WebViewHolder() }
    LaunchedEffect(scrollAnchor) {
        pendingRestore.value = scrollAnchor
        val view = holder.view
        if (scrollAnchor != null && view != null && holder.loaded) {
            holder.restoreTick?.let(view::removeCallbacks)
            holder.restoreTick = applyScrollRestore(view, scrollAnchor)
            pendingRestore.value = null
        }
    }
    val client = remember(assets, articleUrl, displayKey) {
        ArticleBodyWebViewClient(
            assets = assets,
            publicFetch = publicFetch,
            offlineSnapshot = offlineSnapshot != null,
            articleUrl = articleUrl,
            onExternalUrl = onExternalUrl,
            onPageLoaded = { url ->
                // onPageFinished precedes drawing; wait for Chromium's visual state before reading the new height.
                if (url == pageUrl) {
                    val view = holder.view
                    view?.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (holder.view !== view || holder.pageUrl != pageUrl) return
                            holder.loaded = true
                            val anchor = pendingRestore.value
                            if (anchor != null) {
                                holder.restoreTick?.let(view::removeCallbacks)
                                holder.restoreTick = applyScrollRestore(view, anchor)
                            }
                            pendingRestore.value = null
                        }
                    })
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            holder.view?.let { view ->
                holder.pendingReport?.let(view::removeCallbacks)
                holder.scrollListener?.let(view.viewTreeObserver::removeOnScrollChangedListener)
                holder.restoreTick?.let(view::removeCallbacks)
                // 离开详情前回报读到的位置（WebView 销毁后就取不到了）
                // 位置用设备像素（scrollY），高度用 CSS 像素（contentHeight）：比例是单位无关的，两者都只用各自的原始量
                if (holder.loaded) scrollReporter.value?.invoke(view.scrollY, view.contentHeight)
                view.stopLoading()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            holder.view = null
        }
    }

    // V01（独立验收）：位置只在 onDispose 回报的话，进程被杀就不保证执行——
    // a13→a15 实测重开同文回到开头。滚动中节流，停下后补末沿，销毁时再报最终位置。
    val lastScrollReport = remember { longArrayOf(0L) }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).also { view ->
                configureArticleWebView(view, ctx)
                view.webViewClient = client
                view.loadUrl(pageUrl)
                val pending = Runnable {
                    lastScrollReport[0] = android.os.SystemClock.uptimeMillis()
                    if (holder.loaded) scrollReporter.value?.invoke(view.scrollY, view.contentHeight)
                }
                val listener = ViewTreeObserver.OnScrollChangedListener {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastScrollReport[0] >= SCROLL_REPORT_INTERVAL_MS) {
                        lastScrollReport[0] = now
                        if (holder.loaded) scrollReporter.value?.invoke(view.scrollY, view.contentHeight)
                    }
                    view.removeCallbacks(pending)
                    view.postDelayed(pending, SCROLL_REPORT_INTERVAL_MS)
                }
                view.viewTreeObserver.addOnScrollChangedListener(listener)
                holder.scrollListener = listener
                holder.pendingReport = pending
                holder.view = view
                holder.displayKey = displayKey
                holder.pageUrl = pageUrl
            }
        },
        update = { view ->
            // 正文变了（例如获取全文成功）：换掉资源提供者并重新加载，不重建 WebView
            if (view.webViewClient !== client) {
                if (holder.loaded && view.contentHeight > 0) {
                    val current = ScrollAnchor.ReaderAnchor("reader", holder.displayKey, view.scrollY, view.contentHeight)
                    holder.positions[holder.displayKey] = current
                    // shortcut: first bilingual insertion uses proportional fallback; exact positions resume on later toggles.
                    pendingRestore.value = holder.positions[displayKey] ?: current
                }
                holder.loaded = false
                holder.displayKey = displayKey
                holder.pageUrl = pageUrl
                holder.restoreTick?.let(view::removeCallbacks)
                view.webViewClient = client
                view.loadUrl(pageUrl)
            }
        },
    )
}

/**
 * 布局就绪再恢复阅读位置（契约 §7）。
 *
 * onPageFinished 时图片还没到（正文图片是受控通道异步取的），图片出现会把内容顶下去，
 * 只滚一次就会偏。所以按固定节拍重试，直到内容高度稳定；一旦发现用户自己滚了就不再插手。
 *
 * 位置按比例换算（见 ScrollAnchor），图片每补进来一批就重新滚一次：**整个过程持续 [RESTORE_WINDOW_MS] 毫秒**，
 * 不能因为某一瞬间高度没变就提前收工——图片是滚到位置后才被请求的，下一批往往就在这几百毫秒后到。
 * 只有发现用户自己滚了才立刻停手（用户意图优先）。
 *
 * ponytail: 固定节拍的启发式窗口（约 4 秒）。实测：视口以上的图片在窗口内到齐就精确复原；
 * 更晚到的图片仍会把读到的位置顶偏（契约 §7 允许位置回退，不承诺逐像素一致）。
 * 若将来正文更大更慢，改成由图片请求完成事件驱动（受控通道里能知道还剩几张在取）。
 */
private fun applyScrollRestore(view: WebView, anchor: ScrollAnchor.ReaderAnchor): Runnable {
    var lastHeight = -1
    var applied = -1
    var attempts = 0
    val tick = object : Runnable {
        override fun run() {
            attempts++
            if (applied >= 0 && kotlin.math.abs(view.scrollY - applied) > USER_SCROLL_TOLERANCE) return
            val range = view.contentHeight
            val target = ScrollAnchor.readerScrollTarget(anchor, anchor.localId, range)
            // 首次一定要滚；之后只在文档长度变了（图片补进来了）才再滚一次
            if (target > 0 && (attempts == 1 || range != lastHeight)) {
                view.scrollTo(0, target)
                applied = target
            }
            lastHeight = range
            if (attempts < RESTORE_WINDOW_MS / RESTORE_INTERVAL_MS) {
                view.postDelayed(this, RESTORE_INTERVAL_MS)
            }
        }
    }
    view.post(tick)
    return tick
}

private const val RESTORE_WINDOW_MS = 10_000L
private const val RESTORE_INTERVAL_MS = 200L

/** 阅读位置首沿节流与末沿回报窗口，避免短拖动停止后丢失最终位置 */
private const val SCROLL_REPORT_INTERVAL_MS = 1_000L

/** 与上次恢复位置的偏差超过这个值就认为用户自己在滚，停止重试 */
private const val USER_SCROLL_TOLERANCE = 24

/** AndroidView 的 factory 只跑一次，这里把实例留给 onDispose 释放 */
private class WebViewHolder {
    var view: WebView? = null
    var loaded: Boolean = false
    var displayKey: String = "original"
    var pageUrl: String = ""
    val positions = mutableMapOf<String, ScrollAnchor.ReaderAnchor>()
    var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    var pendingReport: Runnable? = null
    var restoreTick: Runnable? = null
}
