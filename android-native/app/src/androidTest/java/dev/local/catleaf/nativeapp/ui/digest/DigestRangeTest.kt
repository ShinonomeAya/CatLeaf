package dev.local.catleaf.nativeapp.ui.digest

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DigestRangeTest {
    @Test fun date_window_compares_instants_with_fractional_seconds_and_offsets() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, CatLeafDatabase::class.java).build()
        try {
            db.feeds().upsert(FeedEntity("scope", "feed", "https://feeds.example.org/rss", "测试", "", "ok", null))
            val dates = mapOf("start" to "2026-10-14T00:00:00.001Z", "offset" to "2026-10-13T23:30:00-02:00",
                "end" to "2026-10-15T00:00:00.001Z", "before" to "2026-10-14T01:00:00+02:00")
            for ((id, date) in dates) db.articles().upsertArticle(ArticleEntity(id, "scope", "feed", id, null, id, date,
                "测试", "https://feeds.example.org/rss", stateUpdatedAt = 1))
            assertEquals(listOf("offset", "start"), db.articles().listForDigest("scope", null,
                "2026-10-14T00:00:00Z", "2026-10-15T00:00:00Z").map { it.localId })
        } finally { db.close() }
    }

    @Test fun daily_candidates_include_multiple_feeds_and_rows_beyond_200_but_never_another_scope_or_unsubscribed_feed() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, CatLeafDatabase::class.java).build()
        try {
            for (feed in listOf("a", "b")) db.feeds().upsert(FeedEntity("scope", feed, "https://feeds.example.org/$feed", feed, "", "ok", null))
            db.feeds().upsert(FeedEntity("other", "a", "https://feeds.example.org/a", "a", "", "ok", null))
            for (i in 1..201) db.articles().upsertArticle(ArticleEntity("article-$i", "scope", "a", "$i", null, "$i", "2026-10-14T12:00:00Z", "a", "https://feeds.example.org/a", stateUpdatedAt = 1))
            for ((id, scope, feed) in listOf(Triple("second-feed", "scope", "b"), Triple("unsubscribed", "scope", "deleted"), Triple("other-scope", "other", "a"))) {
                db.articles().upsertArticle(ArticleEntity(id, scope, feed, id, null, id, "2026-10-14T12:00:00Z", feed, "https://feeds.example.org/$feed", stateUpdatedAt = 1))
            }
            assertEquals(202, db.articles().listForDigest("scope", null, "2026-10-14T00:00:00Z", "2026-10-15T00:00:00Z").size)
            assertEquals(201, db.articles().listForDigest("scope", "a", "2026-10-14T00:00:00Z", "2026-10-15T00:00:00Z").size)
            assertEquals(0, db.articles().listForDigest("scope", null, "2026-10-15T00:00:00Z", "2026-10-16T00:00:00Z").size)
        } finally { db.close() }
    }
}
