package dev.local.catleaf.nativeapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.local.InstanceEntity
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Manual only: seeds synthetic, credential-free content in a disposable emulator for Release timing. */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class PerformanceSeedTest {
    @Test fun seed_200_cached_articles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = CatLeafDatabase.get(context)
        val key = "perf_isolated"
        val feedId = "perf-feed"
        val now = System.currentTimeMillis()
        db.instances().upsert(InstanceEntity(key, "https://example.org", "Isolated performance", now))
        db.feeds().upsert(FeedEntity(key, feedId, "https://example.org/feed", "Isolated performance", "", "ok", now))
        SettingsStore(context).setActiveScopeKind(SCOPE_KIND_INSTANCE)
        SettingsStore(context).setCurrentInstanceKey(key)
        for (i in 1..200) {
            val id = "perf-$i"
            db.articles().upsertArticle(ArticleEntity(
                localId = id, instanceKey = key, feedId = feedId, guid = id,
                link = "https://example.org/articles/$i", title = "Cached article $i",
                publishedAt = "2026-09-24T12:00:00Z", feedTitle = "Isolated performance",
                feedUrl = "https://example.org/feed", stateUpdatedAt = now,
            ))
            db.articles().upsertContent(ArticleContentEntity(
                localId = id, content = "<p>Cached readable body $i. ${"Local text. ".repeat(120)}</p>",
                summary = null, thumbnail = null, contentSource = "rss",
                contentHash = "perf-$i", lastAccessedAt = now,
            ))
        }
        assertEquals(200, db.articles().countByFeed(key, feedId))
    }

    @Test fun seed_scale_limit_in_isolated_emulator() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = CatLeafDatabase.get(context)
        val key = "scale_isolated"
        val now = System.currentTimeMillis()
        db.instances().upsert(InstanceEntity(key, "https://example.org", "Isolated scale", now))
        db.feeds().upsertAll((1..200).map { feed ->
            FeedEntity(key, "scale-feed-$feed", "https://example.org/feed/$feed", "Scale source $feed", "Group ${feed % 10}", "ok", now)
        })
        SettingsStore(context).setActiveScopeKind(SCOPE_KIND_INSTANCE)
        for (feed in 1..200) for (article in 1..10) {
            val id = "scale-$feed-$article"
            db.articles().upsertArticle(ArticleEntity(
                localId = id, instanceKey = key, feedId = "scale-feed-$feed", guid = id,
                link = "https://example.org/articles/$id",
                title = "Very long scale article $id ${"Long title ".repeat(20)}",
                publishedAt = "2026-09-24T12:00:00Z", feedTitle = "Scale source $feed",
                feedUrl = "https://example.org/feed/$feed", stateUpdatedAt = now,
            ))
        }
        SettingsStore(context).setCurrentInstanceKey(key)
        assertEquals(200, db.feeds().list(key).size)
        assertEquals(2_000, db.articles().listByInstance(key).size)
    }
}
