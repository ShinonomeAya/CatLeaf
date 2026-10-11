package dev.local.catleaf.nativeapp.data.offline

import android.app.Application
import android.graphics.BitmapFactory
import android.util.Log
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.article.SearchQuery
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.rss.FeedParser
import dev.local.catleaf.nativeapp.data.rss.FeedXmlDecoder
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.ui.reader.ArticleBodyWebViewClient
import dev.local.catleaf.nativeapp.ui.reader.ReaderAssets
import dev.local.catleaf.nativeapp.ui.reader.configureArticleWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import okio.Buffer
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Manual Q04 probes. Run only on the isolated emulator with explicit phase arguments. */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class OfflineQ04ProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val wechat = InstrumentationRegistry.getArguments().getString("feed") == "wechat"
    private val liveId = if (wechat) "isolated-q04-wechat-image" else "isolated-q04-live-image"
    private val sourceFeed = if (wechat) "https://plink.anyfeeder.com/weixin/dili360" else "https://www.theverge.com/rss/index.xml"
    private val killedId = "isolated-q04-killed-save"

    @Test fun live_feed_image_is_rendered_from_local_package_after_force_stop() = runBlocking {
        when (InstrumentationRegistry.getArguments().getString("phase")) {
            "seed" -> seedLiveImage()
            "verify" -> verifyLiveImage()
            else -> error("requires phase=seed or phase=verify")
        }
    }

    private suspend fun seedLiveImage() {
        val store = OfflinePackageStore(app, db)
        store.remove(LOCAL_SCOPE_KEY, liveId)
        db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE localId = ?", arrayOf(liveId))

        val source = PublicFetch().fetch(sourceFeed).getOrThrow()
        val xml = FeedXmlDecoder.decode(source.bytes, source.contentType).getOrThrow()
        val feed = FeedParser.parse(xml).getOrThrow()
        val photo = feed.items.firstNotNullOfOrNull { item ->
            val body = ArticleSanitizer.sanitize(item.content?.takeIf(String::isNotBlank) ?: item.description.orEmpty(), item.link).html
            val imageUrl = item.thumbnail?.takeIf { it.startsWith("https://") }
                ?: Jsoup.parseBodyFragment(body).select("img[src]").firstOrNull()?.attr("src")
                    ?.takeIf { it.startsWith("https://") }
                ?: return@firstNotNullOfOrNull null
            val fetched = PublicFetch().fetch(imageUrl, PublicFetch.MAX_IMAGE_BYTES).getOrNull() ?: return@firstNotNullOfOrNull null
            val mime = fetched.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val bitmap = BitmapFactory.decodeByteArray(fetched.bytes, 0, fetched.bytes.size)
            if (!mime.startsWith("image/") || bitmap == null) return@firstNotNullOfOrNull null
            bitmap.recycle()
            Triple(item, imageUrl, mime)
        } ?: error("featured picture feed had no reachable, decodable image")

        val item = photo.first
        val page = Jsoup.parseBodyFragment(ArticleSanitizer.sanitize(item.content?.takeIf(String::isNotBlank) ?: item.description.orEmpty(), item.link).html)
        if (!wechat) {
            page.select("img").remove()
            page.body().appendElement("img").attr("src", photo.second).attr("alt", item.title)
        }
        val body = page.body().html().ifBlank { "<p>${ReaderAssets.escapeHtml(item.title)}</p><img src=\"${photo.second}\">" }
        val articleUrl = item.link?.takeIf { it.startsWith("https://") } ?: photo.second
        db.articles().upsertArticle(ArticleEntity(
            localId = liveId, instanceKey = LOCAL_SCOPE_KEY, feedId = "isolated-q04-live-feed",
            guid = item.guid ?: articleUrl, link = articleUrl, title = item.title, publishedAt = item.publishedAt,
            feedTitle = if (wechat) "中国国家地理" else "The Verge", feedUrl = sourceFeed,
            stateUpdatedAt = 1,
        ))
        db.articles().upsertContent(ArticleContentEntity(
            localId = liveId, content = body, summary = item.description, thumbnail = photo.second,
            contentSource = "rss", contentHash = "live-q04-${photo.third}", searchText = item.title,
            searchBodyText = SearchQuery.bodyText(body), lastAccessedAt = 1,
        ))
        val saved = store.save(LOCAL_SCOPE_KEY, liveId, includeImages = true).getOrThrow()
        assertEquals(if (wechat) "partial" else "complete", saved.status)
        assertEquals((page.select("img[src]").map { it.attr("src") } + photo.second).distinct().size, saved.imageTotal)
        assertEquals(saved.imageTotal - if (wechat) 1 else 0, saved.imageSaved)
        Log.i("OfflineQ04Probe", "live image saved; mime=${photo.third}; bytes=${saved.bytes}")
    }

    private suspend fun verifyLiveImage() {
        val store = OfflinePackageStore(app, db)
        store.reconcile()
        val snapshot = store.load(LOCAL_SCOPE_KEY, liveId) ?: error("offline snapshot missing after force-stop")
        assertEquals(if (wechat) "partial" else "complete", snapshot.record.status)
        assertEquals(snapshot.record.imageSaved, snapshot.resources.size)
        snapshot.resources.values.forEach { resource ->
            val bitmap = BitmapFactory.decodeFile(resource.file.path)
            assertNotNull("real saved image must still decode", bitmap)
            bitmap!!.recycle()
        }
        val resource = snapshot.resources.values.first()

        val coverId = snapshot.record.coverId ?: error("offline cover id missing")
        val imageRequested = CountDownLatch(1)
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val pageLoaded = CountDownLatch(1)
        val assets = ReaderAssets(
            snapshot.bodyHtml,
            headerHtml = "<img class=\"catleaf-cover\" src=\"${snapshot.coverUrl}\">",
            offlineResources = snapshot.resources,
        )
        val client = ArticleBodyWebViewClient(
            assets = assets,
            offlineSnapshot = true,
            onRequest = { url ->
                requests += url
                if (url.contains("/offline/$coverId")) imageRequested.countDown()
            },
            onPageLoaded = { pageLoaded.countDown() },
        )
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(app)
            configureArticleWebView(webView, app)
            webView.webViewClient = client
            webView.loadUrl(ReaderAssets.PAGE_URL)
        }
        try {
            assertTrue("offline reader page loads", pageLoaded.await(15, TimeUnit.SECONDS))
            assertTrue("saved image is requested through the local asset route", imageRequested.await(10, TimeUnit.SECONDS))
            assertTrue(requests.all { it.startsWith("https://${ReaderAssets.DOMAIN}/") })
            Log.i("OfflineQ04Probe", "cold-start reader requested local image; imageBytes=${resource.file.length()}")
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
            store.remove(LOCAL_SCOPE_KEY, liveId)
            db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE localId = ?", arrayOf(liveId))
        }
    }

    @Test fun system_kill_during_second_image_reconciles_saved_progress() = runBlocking {
        when (InstrumentationRegistry.getArguments().getString("phase")) {
            "seed" -> blockDuringSecondImage()
            "verify" -> verifyKilledSave()
            else -> error("requires phase=seed or phase=verify")
        }
    }

    private suspend fun blockDuringSecondImage() {
        val store = OfflinePackageStore(app, db)
        store.remove(LOCAL_SCOPE_KEY, killedId)
        db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE localId = ?", arrayOf(killedId))
        val body = "<p>系统强停后正文仍在</p><img src=\"https://example.org/one.png\"><img src=\"https://example.org/two.png\">"
        db.articles().upsertArticle(ArticleEntity(
            localId = killedId, instanceKey = LOCAL_SCOPE_KEY, feedId = "isolated-q04-kill-feed", guid = killedId,
            link = "https://example.org/story", title = "隔离进行中强停", publishedAt = null,
            feedTitle = "隔离源", feedUrl = "https://example.org/feed", stateUpdatedAt = 1,
        ))
        db.articles().upsertContent(ArticleContentEntity(
            localId = killedId, content = body, summary = null, thumbnail = null,
            contentSource = "rss", contentHash = "q04-kill", searchText = "系统强停后正文仍在",
            searchBodyText = SearchQuery.bodyText(body), lastAccessedAt = 1,
        ))
        val server = MockWebServer().apply {
            enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(TestImage.png)))
            enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            start()
        }
        val client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
        val saving = CoroutineScope(Dispatchers.IO).async {
            OfflinePackageStore(app, db, PublicFetch(client = client, targetPolicy = {
                Result.success(server.url(it.substringAfterLast('/')).toString())
            })).save(LOCAL_SCOPE_KEY, killedId, includeImages = true)
        }
        try {
            withTimeout(30_000) { while ((db.offline().get(killedId, LOCAL_SCOPE_KEY)?.imageSaved ?: 0) != 1) delay(50) }
            val first = server.takeRequest(5, TimeUnit.SECONDS) ?: error("first image request did not start")
            val second = server.takeRequest(10, TimeUnit.SECONDS) ?: error("second image request did not start")
            assertTrue(first.path.orEmpty().contains("one.png"))
            assertTrue(second.path.orEmpty().contains("two.png"))
            assertTrue("save must remain in flight until the external process kill", saving.isActive)
            assertEquals("saving", db.offline().get(killedId, LOCAL_SCOPE_KEY)?.status)
            Log.i("OfflineQ04Probe", "first image persisted; second image request is blocked; force-stop now")
            CountDownLatch(1).await(10, TimeUnit.MINUTES)
            saving.await()
            error("probe expected external force-stop while the second image was blocked")
        } finally {
            if (saving.isActive) saving.cancel()
            server.shutdown()
        }
    }

    private suspend fun verifyKilledSave() {
        val store = OfflinePackageStore(app, db)
        store.reconcile()
        val snapshot = store.load(LOCAL_SCOPE_KEY, killedId) ?: error("interrupted package missing")
        assertEquals("interrupted", snapshot.record.status)
        assertEquals(1, snapshot.record.imageSaved)
        assertEquals(1, snapshot.resources.size)
        assertTrue(snapshot.bodyHtml.contains("系统强停后正文仍在"))
        assertNotNull(BitmapFactory.decodeFile(snapshot.resources.values.single().file.path))
        Log.i("OfflineQ04Probe", "system-killed package reconciled; savedImages=${snapshot.record.imageSaved}")
        store.remove(LOCAL_SCOPE_KEY, killedId)
        db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE localId = ?", arrayOf(killedId))
    }
}
