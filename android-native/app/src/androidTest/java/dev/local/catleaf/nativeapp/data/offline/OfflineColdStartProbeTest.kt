package dev.local.catleaf.nativeapp.data.offline

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.article.SearchQuery
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run phase=seed, force-stop the isolated emulator app, then phase=verify without reinstalling it. */
@RunWith(AndroidJUnit4::class)
@ManualProbe
class OfflineColdStartProbeTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val id = "isolated-offline-cold-start"
    private val png = TestImage.png

    @Test fun verifyAcrossForceStop() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        check(phase == "seed" || phase == "verify") { "requires explicit seed/verify phases" }
        if (phase == "seed") {
            val html = "<p>强停后可读的离线正文</p><img src='https://example.org/cold.png'>"
            db.articles().upsertArticle(ArticleEntity(
                localId = id, instanceKey = LOCAL_SCOPE_KEY, feedId = "isolated-cold-feed", guid = id,
                link = "https://example.org/cold-story", title = "隔离冷启动文章", publishedAt = null,
                feedTitle = "隔离源", feedUrl = "https://example.org/cold-feed", stateUpdatedAt = 1,
            ))
            db.articles().upsertContent(ArticleContentEntity(
                localId = id, content = html, summary = null, thumbnail = null,
                contentSource = "rss", contentHash = "cold", searchText = "强停后可读",
                searchBodyText = SearchQuery.bodyText(html), lastAccessedAt = 1,
            ))
            val server = MockWebServer().apply { start() }
            try {
                server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(png)))
                val fetch = PublicFetch(targetPolicy = { Result.success(server.url("/cold.png").toString()) })
                val saved = OfflinePackageStore(app, db, fetch).save(LOCAL_SCOPE_KEY, id, true).getOrThrow()
                assertEquals("complete", saved.status)
                assertEquals(1, saved.imageSaved)
            } finally {
                server.shutdown()
            }
        } else {
            val store = OfflinePackageStore(app, db)
            try {
                store.reconcile()
                val snapshot = store.load(LOCAL_SCOPE_KEY, id)!!
                assertEquals("complete", snapshot.record.status)
                assertTrue(snapshot.bodyHtml.contains("强停后可读的离线正文"))
                assertEquals(1, snapshot.resources.size)
                assertTrue(snapshot.resources.values.single().file.readBytes().contentEquals(png))
                assertTrue(android.graphics.BitmapFactory.decodeFile(snapshot.resources.values.single().file.path) != null)
            } finally {
                store.remove(LOCAL_SCOPE_KEY, id)
                db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE localId = ?", arrayOf(id))
            }
        }
    }
}
