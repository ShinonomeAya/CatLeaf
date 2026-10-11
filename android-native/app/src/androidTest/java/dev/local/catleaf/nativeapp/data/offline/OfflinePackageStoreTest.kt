package dev.local.catleaf.nativeapp.data.offline

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.article.SearchQuery
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CacheCleanup
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.OfflinePackageEntity
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OfflinePackageStoreTest {
    @Test fun nested_rss_keeps_translation_text_after_offline_save() = runBlocking {
        val html = "<div><section><p>正文甲<span>乙</span>丙</p><section><p>第二段</p></section></section></div>"
        seed(html)
        val before = ArticleSanitizer.sanitize(html, null).plainText
        store.save(scope, id, false).getOrThrow()
        val saved = store.load(scope, id)!!
        assertEquals(before, ArticleSanitizer.sanitize(saved.bodyHtml, null).plainText)
    }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: CatLeafDatabase
    private lateinit var store: OfflinePackageStore
    private val scope = "offline-test-${UUID.randomUUID()}"
    private val id = "offline-article-${UUID.randomUUID()}"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
        store = OfflinePackageStore(context, db)
    }

    @After fun tearDown() = runBlocking {
        store.remove(scope, id)
        db.close()
    }

    private suspend fun seed(html: String) {
        db.articles().upsertArticle(ArticleEntity(
            localId = id, instanceKey = scope, feedId = "feed", guid = id,
            link = "https://example.org/story", title = "离线测试", publishedAt = null,
            feedTitle = "测试源", feedUrl = "https://example.org/feed", stateUpdatedAt = 1,
        ))
        db.articles().upsertContent(ArticleContentEntity(
            localId = id, content = html, summary = null, thumbnail = null,
            contentSource = "rss", contentHash = "test", searchText = "离线测试",
            searchBodyText = SearchQuery.bodyText(html), lastAccessedAt = 1,
        ))
    }

    @Test fun filesystem_write_failure_keeps_old_readable_generation() = runBlocking {
        seed("<p>旧离线正文必须保留</p>")
        val old = store.save(scope, id, false).getOrThrow()
        val root = java.io.File(context.noBackupFilesDir, "offline-packages")
        val parent = root.walkTopDown().first { it.name == old.generation }.parentFile!!
        assertTrue(parent.setWritable(false, true))
        try {
            assertFalse("fault must prevent a real directory write", java.io.File(parent, "write-probe").mkdir())
            assertTrue(store.save(scope, id, false).isFailure)
            val retained = store.load(scope, id)!!
            assertEquals(old, retained.record)
            assertTrue(retained.bodyHtml.contains("旧离线正文必须保留"))
            assertEquals(listOf(old.generation), parent.list()!!.toList())
        } finally {
            assertTrue(parent.setWritable(true, true))
        }
    }

    @Test fun truncated_png_does_not_count_as_saved_image() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            seed("<p>正文</p><img src='https://example.org/broken.png'>")
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0))))
            val fetch = PublicFetch(targetPolicy = { Result.success(server.url("/broken.png").toString()) })
            val result = OfflinePackageStore(context, db, fetch).save(scope, id, true).getOrThrow()
            assertEquals("partial", result.status)
            assertEquals(0, result.imageSaved)
        } finally {
            server.shutdown()
        }
    }

    @Test fun body_over_global_budget_does_not_leave_an_over_budget_package() = runBlocking {
        seed("<p>超过剩余空间的正文</p>")
        db.offline().upsert(OfflinePackageEntity(
            localId = "other-budget-row", scopeKey = "other-scope", title = "other", feedTitle = "other",
            generation = "old", status = "text", selectedImages = false, imageTotal = 0,
            imageSaved = 0, bytes = OfflinePackageStore.MAX_TOTAL_BYTES - 1,
            bodyHash = "old", searchBodyText = "old", imageManifest = "[]", updatedAt = 1,
        ))
        assertTrue(store.save(scope, id, false).isFailure)
        assertEquals(null, db.offline().get(id, scope))
        assertTrue(db.offline().totalBytes() <= OfflinePackageStore.MAX_TOTAL_BYTES)
    }

    @Test fun large_images_hit_article_budget_report_partial_and_failed_update_keeps_old_package() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            seed("<h1>离线测试</h1><p>大图预算正文</p>" + (1..3).joinToString("") { "<img src='https://example.org/$it.png'>" })
            val png = ByteArray(8 * 1024 * 1024).also { TestImage.png.copyInto(it) }
            repeat(3) { server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))) }
            val images = OfflinePackageStore(context, db, PublicFetch(targetPolicy = { Result.success(server.url("/image").toString()) }))
            val saved = images.save(scope, id, true).getOrThrow()
            assertEquals("partial", saved.status)
            assertEquals(2, saved.imageSaved)
            assertEquals(3, saved.imageTotal)
            assertTrue(saved.bytes <= OfflinePackageStore.MAX_ARTICLE_BYTES)
            val reopened = OfflinePackageStore(context, db).load(scope, id)!!
            assertEquals(2, reopened.resources.size)
            assertFalse(reopened.bodyHtml.contains("<h1>"))
            assertTrue(reopened.resources.values.all { it.file.length() == png.size.toLong() })
            repeat(3) { server.enqueue(MockResponse().setResponseCode(404)) }
            assertTrue(images.save(scope, id, true).isFailure)
            assertEquals(saved.generation, images.load(scope, id)!!.record.generation)
            assertEquals(2, images.load(scope, id)!!.resources.size)
        } finally { server.shutdown() }
    }

    @Test fun text_package_survives_normal_cleanup_and_can_be_searched_then_removed() = runBlocking {
        seed("<p>离线正文独有词</p>")
        assertEquals("text", store.save(scope, id, false).getOrThrow().status)
        CacheCleanup(db, context).clearNormalCache()
        assertEquals(null, db.articles().getContent(id))
        val reopened = OfflinePackageStore(context, db).load(scope, id)
        assertNotNull(reopened)
        assertTrue(reopened!!.bodyHtml.contains("离线正文独有词"))
        val pattern = SearchQuery.likePattern("离线正文独有词")
        assertEquals(1, db.articles().countSearch(scope, pattern, null, 1, 0, 0, "all", null, null))
        assertEquals(0, db.articles().countSearch(scope, pattern, null, 0, 0, 0, "all", null, null))
        db.articles().setStarred(id, true, 2, 2)
        assertTrue(store.remove(scope, id).isSuccess)
        assertEquals(null, db.offline().get(id, scope))
        assertTrue(db.articles().getContent(id)?.content?.contains("离线正文独有词") == true)
        assertTrue(db.articles().get(id)!!.isStarred)
    }

    @Test fun partial_images_retry_only_missing_image_and_offline_map_contains_only_saved() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            seed("<p>图文</p><img src='https://example.org/one.png'><img src='https://example.org/two.png'>")
            val png = TestImage.png
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            val fetch = PublicFetch(targetPolicy = { Result.success(server.url(it.substringAfterLast('/')).toString()) })
            val images = OfflinePackageStore(context, db, fetch)
            val partial = images.save(scope, id, true).getOrThrow()
            assertEquals("partial", partial.status)
            assertEquals(1, partial.imageSaved)
            val view = images.load(scope, id)!!
            assertEquals(1, view.resources.size)
            assertFalse(view.bodyHtml.contains("https://example.org/one.png"))
            assertEquals("complete", images.retryMissing(scope, id).getOrThrow().status)
            assertEquals(2, images.load(scope, id)!!.resources.size)
            assertEquals(3, server.requestCount)
            repeat(3) {
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("https://example.org/", request.getHeader("Referer"))
                assertEquals(null, request.getHeader("Authorization"))
                assertEquals(null, request.getHeader("Cookie"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun interrupted_first_save_keeps_completed_images_for_retry() = runBlocking {
        seed("<p>可继续读</p><img src='https://example.org/one.png'><img src='https://example.org/two.png'>")
        val png = TestImage.png
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            val attempts = AtomicInteger()
            val interrupted = OfflinePackageStore(context, db, PublicFetch(targetPolicy = {
                if (attempts.incrementAndGet() == 2) error("模拟第二张开始时中断")
                Result.success(server.url("/first").toString())
            }))
            assertTrue(interrupted.save(scope, id, true).isFailure)
            val saved = interrupted.load(scope, id)!!
            assertEquals("interrupted", saved.record.status)
            assertEquals(1, saved.record.imageSaved)
            assertEquals(1, saved.resources.size)
            assertTrue(saved.bodyHtml.contains("可继续读"))

            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            val resumed = OfflinePackageStore(context, db, PublicFetch(targetPolicy = {
                Result.success(server.url("/second").toString())
            }))
            assertEquals("complete", resumed.retryMissing(scope, id).getOrThrow().status)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun cancel_after_first_image_keeps_progress_and_stops_next_request() = runBlocking {
        seed("<p>取消后还在</p><img src='https://example.org/one.png'><img src='https://example.org/two.png'>")
        val png = TestImage.png
        val server = MockWebServer().apply { start() }
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        try {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            val attempts = AtomicInteger()
            val saving = OfflinePackageStore(context, db, PublicFetch(targetPolicy = {
                if (attempts.incrementAndGet() == 2) {
                    secondStarted.countDown()
                    releaseSecond.await(5, TimeUnit.SECONDS)
                    Result.failure(IllegalStateException("取消后不发第二次网络请求"))
                } else Result.success(server.url("/first").toString())
            }))
            val job = async(Dispatchers.IO) { saving.save(scope, id, true) }
            assertTrue(withContext(Dispatchers.IO) { secondStarted.await(5, TimeUnit.SECONDS) })
            job.cancel()
            releaseSecond.countDown()
            runCatching { job.await() }
            val snapshot = store.load(scope, id)!!
            assertEquals("interrupted", snapshot.record.status)
            assertEquals(1, snapshot.record.imageSaved)
            assertEquals(1, snapshot.resources.size)
            assertEquals(1, server.requestCount)
        } finally {
            releaseSecond.countDown()
            server.shutdown()
        }
    }

    @Test fun failed_update_keeps_previous_readable_generation() = runBlocking {
        seed("<p>旧离线正文</p>")
        val old = store.save(scope, id, false).getOrThrow()
        seed("<p>新版正文</p><img src='https://example.org/fail.png'>")
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            val failing = OfflinePackageStore(context, db,
                PublicFetch(targetPolicy = { Result.success(server.url("/fail").toString()) }))
            assertTrue(failing.save(scope, id, true).isFailure)
            val kept = failing.load(scope, id)!!
            assertEquals(old.generation, kept.record.generation)
            assertTrue(kept.bodyHtml.contains("旧离线正文"))
            assertFalse(kept.bodyHtml.contains("新版正文"))
        } finally { server.shutdown() }
    }

    @Test fun cold_start_reconciles_unfinished_status_without_losing_body() = runBlocking {
        seed("<p>重开仍可读的文字</p>")
        val saved = store.save(scope, id, false).getOrThrow()
        db.offline().upsert(saved.copy(status = "saving"))
        OfflinePackageStore(context, db).reconcile()
        val reopened = store.load(scope, id)!!
        assertEquals("interrupted", reopened.record.status)
        assertTrue(reopened.bodyHtml.contains("重开仍可读的文字"))
    }

    @Test fun interrupted_refresh_keeps_completed_old_snapshot() = runBlocking {
        seed("<p>仍然完整的旧正文</p>")
        val old = store.save(scope, id, false).getOrThrow()
        db.offline().upsert(old.copy(pendingGeneration = UUID.randomUUID().toString(), status = "saving"))
        OfflinePackageStore(context, db).reconcile()
        val reopened = store.load(scope, id)!!
        assertEquals(old.generation, reopened.record.generation)
        assertEquals("text", reopened.record.status)
        assertTrue(reopened.bodyHtml.contains("仍然完整的旧正文"))
    }

    @Test fun image_limit_caps_requests_and_never_reports_complete() = runBlocking {
        seed("<p>五十一张图片</p>" + (1..51).joinToString("") { "<img src='https://example.org/$it.png'>" })
        val attempts = AtomicInteger()
        val blocked = OfflinePackageStore(context, db, PublicFetch(targetPolicy = {
            attempts.incrementAndGet()
            Result.failure(IllegalArgumentException("blocked fixture"))
        }))
        val result = blocked.save(scope, id, true).getOrThrow()
        assertEquals(51, result.imageTotal)
        assertEquals(0, result.imageSaved)
        assertEquals(50, attempts.get())
        assertEquals("partial", result.status)
    }
}
