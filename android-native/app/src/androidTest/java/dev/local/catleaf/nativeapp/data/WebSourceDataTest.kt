package dev.local.catleaf.nativeapp.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.article.WebsiteRules
import dev.local.catleaf.nativeapp.data.article.ArticleRepository
import dev.local.catleaf.nativeapp.data.importexport.StateFeed
import dev.local.catleaf.nativeapp.data.importexport.StateFile
import dev.local.catleaf.nativeapp.data.importexport.StateRestore
import dev.local.catleaf.nativeapp.data.local.CacheCleanup
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebSourceDataTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val rule = WebsiteRules(items = "article", title = "h2", link = "a", summary = "p")

    @Test fun shippedRoom4MigratesWithoutLosingIdentityTextFavoritesOrOfflineMetadata() = runBlocking {
        val name = "web-rule-migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrument.context.assets.open("dev.local.catleaf.nativeapp.data.local.CatLeafDatabase/4.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                if (table in listOf("feeds", "articles", "article_content", "ai_cache", "offline_packages")) {
                    val values = ContentValues()
                    val fields = entity.getJSONArray("fields")
                    for (i in 0 until fields.length()) {
                        val field = fields.getJSONObject(i)
                        val column = field.getString("columnName")
                        if (field.getString("affinity") == "INTEGER") values.put(column, 1L)
                        else values.put(column, when (column) {
                            "instanceKey", "scopeKey" -> LOCAL_SCOPE_KEY
                            "url", "feedUrl" -> "https://example.com/feed"
                            "link" -> "https://example.com/story"
                            "content" -> "<p>Retained text</p>"
                            "imageManifest" -> "[]"
                            else -> "kept"
                        })
                    }
                    old.insertOrThrow(table, null, values)
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.version = 4
        }
        val db = Room.databaseBuilder(context, CatLeafDatabase::class.java, name).addMigrations(CatLeafDatabase.MIGRATION_4_5).build()
        try {
            val feed = db.feeds().list(LOCAL_SCOPE_KEY).single()
            assertEquals("rss", feed.sourceType)
            assertNull(feed.ruleJson)
            assertEquals("kept", feed.feedId)
            val article = db.articles().get("kept")!!
            assertTrue(article.isRead)
            assertTrue(article.isStarred)
            assertEquals("<p>Retained text</p>", db.articles().getContent("kept")!!.content)
            for (table in listOf("ai_cache", "offline_packages")) db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun webpageRefreshDeduplicatesAndFailureKeepsReadAndFavoriteState() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
        var html = "<article><h2>First</h2><a href=/story>Read</a><p>Preview</p></article>"
        try {
            val store = LocalFeedStore(db, LOCAL_SCOPE_KEY, CacheCleanup(db, context)) { Result.success(html) }
            val added = store.addConfigured("https://example.com/news", "", "News", "html", rule.toJson()).getOrThrow()
            assertTrue(added.verified)
            val first = db.articles().listByInstance(LOCAL_SCOPE_KEY).single()
            db.articles().setRead(first.localId, true, 1)
            db.articles().setStarred(first.localId, true, 1, 1)
            val body = "<p>Previously extracted full text.</p>"
            db.articles().upsertContent(db.articles().getContent(first.localId)!!.copy(content = body, contentSource = "extracted", contentHash = "retained"))
            html = html.replace("Preview", "New preview") + "<article><h2>Again</h2><a href=/story#other>Read</a></article>"
            store.verify(added.feedId).getOrThrow()
            assertEquals(1, db.articles().listByInstance(LOCAL_SCOPE_KEY).size)
            val refreshedContent = db.articles().getContent(first.localId)!!
            assertEquals(body, refreshedContent.content)
            assertEquals("retained", refreshedContent.contentHash)
            assertTrue(refreshedContent.summary!!.contains("New preview"))
            html = "<script>render()</script>"
            assertTrue(store.verify(added.feedId).isFailure)
            val retained = db.articles().get(first.localId)!!
            assertTrue(retained.isRead && retained.isStarred)
            assertEquals(body, db.articles().getContent(first.localId)?.content)
        } finally { db.close() }
    }

    @Test fun stateRestoreWritesWebRulesForNewFeedsAndKeepsExistingConfiguration() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
        try {
            val backup = StateFile.write("local", null, 1, listOf(StateFeed("https://example.com/news", "News", "", "html", rule.toJson())), emptyList())
            val doc = StateFile.read(backup).getOrThrow()
            db.feeds().upsertAll(StateRestore.localFeedsToImport(doc, emptyList(), LOCAL_SCOPE_KEY))
            val row = db.feeds().list(LOCAL_SCOPE_KEY).single()
            assertEquals("html", row.sourceType)
            assertEquals(rule, WebsiteRules.fromJson(row.ruleJson!!))
            assertTrue(StateRestore.localFeedsToImport(doc, listOf(row.copy(ruleJson = rule.copy(items = ".new").toJson())), LOCAL_SCOPE_KEY).isEmpty())
        } finally { db.close() }
    }

    @Test fun changedRulesInvalidateRefreshFreshnessAndSuccessfulStatus() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
        val html = "<article class=first><h2>One</h2><a href=/one>Read</a></article><article class=second><h2>Two</h2><a href=/two>Read</a></article><article class=second><h2>Invalid</h2></article>"
        try {
            val store = LocalFeedStore(db, LOCAL_SCOPE_KEY, CacheCleanup(db, context)) { Result.success(html) }
            val firstRule = rule.copy(items = ".first")
            val added = store.addConfigured("https://example.com/news", "", "News", "html", firstRule.toJson()).getOrThrow()
            val first = store.local().single()
            var clock = 1000L
            val repo = ArticleRepository(db, null, LOCAL_SCOPE_KEY, now = { clock++ }, cleanup = CacheCleanup(db, context), localFeedXml = { Result.success(html) })
            assertTrue(repo.refreshIfStale(first) is ArticleRepository.RefreshOutcome.Refreshed)
            assertTrue(repo.refreshIfStale(first) is ArticleRepository.RefreshOutcome.SkippedFresh)
            store.updateConfigured(added.feedId, "", "News", "html", rule.copy(items = ".second").toJson()).getOrThrow()
            val changed = store.local().single()
            assertEquals(FeedStore.STATUS_PENDING, changed.status)
            assertNull(changed.lastSuccessAt)
            val refreshed = repo.refreshIfStale(changed) as ArticleRepository.RefreshOutcome.Refreshed
            assertEquals(1, refreshed.inserted)
            assertEquals(1, refreshed.unidentified)
            assertEquals(2, db.articles().listByInstance(LOCAL_SCOPE_KEY).size)
        } finally { db.close() }
    }

    @Test fun oldResponseCannotMarkEditedRulesSuccessfulOrInsertItsArticles() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val feed = dev.local.catleaf.nativeapp.data.local.FeedEntity(LOCAL_SCOPE_KEY, "fixture", "https://example.com/news", "News", "", "pending", null, "html", rule.toJson())
            db.feeds().upsert(feed)
            val repo = ArticleRepository(db, null, LOCAL_SCOPE_KEY, cleanup = CacheCleanup(db, context), localFeedXml = {
                started.complete(Unit); release.await()
                Result.success("<article><h2>Old result</h2><a href=/old>Read</a></article>")
            })
            val request = async { repo.refreshNow(feed) }
            started.await()
            db.feeds().upsert(feed.copy(ruleJson = rule.copy(items = ".new").toJson()))
            release.complete(Unit)
            assertTrue(request.await() is ArticleRepository.RefreshOutcome.Failed)
            assertTrue(db.articles().listByInstance(LOCAL_SCOPE_KEY).isEmpty())
            assertEquals("pending", db.feeds().list(LOCAL_SCOPE_KEY).single().status)
        } finally { release.complete(Unit); db.close() }
    }
}
