package dev.local.catleaf.nativeapp.data

import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.article.SearchQuery
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Disposable-emulator DB-only probe. It does not claim the Release phone UI P95 gate. */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class SearchScaleProbeTest {
    @Test fun two_thousand_bodies_and_ten_thousand_mixed_identities() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
        val scope = "isolated-search-scale"
        val feed = "scale-feed"
        val times = mutableListOf<Long>()
        try {
            db.withTransaction {
                for (i in 1..10_000) {
                    val id = "scale-$i"
                    db.articles().upsertArticle(ArticleEntity(
                        localId = id, instanceKey = scope, feedId = feed, guid = id,
                        link = null, title = "规模文章 $i", publishedAt = null,
                        feedTitle = "隔离样本", feedUrl = "https://example.invalid/feed", stateUpdatedAt = 1,
                        isStarred = i % 5 == 0,
                    ))
                    if (i <= 2_000) db.articles().upsertContent(ArticleContentEntity(
                        localId = id, content = "<p>${"正文内容。".repeat(90)} 唯一词样本${"%05d".format(i)}</p>",
                        summary = null, thumbnail = null, contentSource = "rss", contentHash = id,
                        searchText = "规模摘要", searchBodyText = "${"正文内容。".repeat(90)} 唯一词样本${"%05d".format(i)}",
                        lastAccessedAt = 1,
                    ))
                }
            }
            val lines = mutableListOf("sample,term,from_query_to_snippet_ms,count")
            for (sample in 1..20) {
                val term = "唯一词样本${"%05d".format(sample * 97)}"
                val start = SystemClock.elapsedRealtimeNanos()
                val page = db.articles().searchTitles(scope, SearchQuery.likePattern(term), null, 1, 0, 0, "all", null, null, 50, 0)
                val count = db.articles().countSearch(scope, SearchQuery.likePattern(term), null, 1, 0, 0, "all", null, null)
                db.articles().searchSnippets(page.map { it.localId }, term, 1)
                val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                assertEquals(1, count)
                times += ms
                lines += "$sample,$term,$ms,$count"
            }
            val start = SystemClock.elapsedRealtimeNanos()
            val all = db.articles().countSearch(scope, SearchQuery.likePattern("规模文章"), null, 0, 0, 0, "all", null, null)
            val mixedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
            assertEquals(10_000, all)
            val pages = db.openHelper.readableDatabase.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) }
            val pageSize = db.openHelper.readableDatabase.query("PRAGMA page_size").use { it.moveToFirst(); it.getLong(0) }
            lines += "p95_db_only,,${times.sorted()[18]},"
            lines += "ten_thousand_identity_count_ms,,$mixedMs,$all"
            lines += "database_page_bytes,,${pages * pageSize},2000_bodies_10000_identities"
            File(context.getExternalFilesDir(null), "catleaf-search-scale.csv").writeText(lines.joinToString("\n") + "\n")
        } finally { db.close() }
    }
}
