package dev.local.catleaf.nativeapp.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.importexport.StateFile
import dev.local.catleaf.nativeapp.data.importexport.StateFeed
import dev.local.catleaf.nativeapp.data.importexport.StateArticle
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.offline.OfflinePackageStore
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class StateOfflineBoundaryTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)
    private val store = OfflinePackageStore(app, db)

    @Test fun local_restore_write_failure_rolls_back_feeds_and_prior_state_updates() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val id = "restore-fault-$suffix"
        val url = "https://example.org/$suffix/rss"
        val newUrl = "https://example.org/$suffix/new"
        val trigger = "restore_fault_${suffix.replace("-", "") }"
        val file = app.cacheDir.resolve("isolated-restore-fault-$suffix.json")
        val previous = settings.current()
        try {
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            db.feeds().upsert(FeedEntity(LOCAL_SCOPE_KEY, id, url, "原订阅", "原分组", "ok", 1L))
            val old = ArticleEntity(id, LOCAL_SCOPE_KEY, id, "old-$suffix", null,
                "原文章", null, "原订阅", url, stateUpdatedAt = 1L)
            db.articles().upsertArticle(old)
            file.writeBytes(StateFile.write("local", null, 1L,
                listOf(StateFeed(url, "备份订阅", ""), StateFeed(newUrl, "新订阅", "")),
                listOf(StateArticle(url, old.guid, null, "原文章", "原订阅", true, true),
                    StateArticle(newUrl, "new-$suffix", null, "新书签", "新订阅", false, true))))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON articles " +
                "WHEN NEW.guid = 'new-$suffix' BEGIN SELECT RAISE(ABORT, 'isolated write failure'); END")
            val vm = StateBackupViewModel(app)
            vm.openFile(Uri.fromFile(file), file.name)
            withTimeout(5_000) { vm.state.first { it.phase is RestorePhase.Preview } }
            vm.startRestore()
            val failed = withTimeout(5_000) { vm.state.first { it.notice?.contains("本机恢复失败") == true } }
            assertTrue(failed.phase is RestorePhase.Idle)
            assertEquals(old, db.articles().get(id))
            assertFalse(db.feeds().list(LOCAL_SCOPE_KEY).any { it.url == newUrl })
            assertFalse(db.articles().listByInstance(LOCAL_SCOPE_KEY).any { it.guid == "new-$suffix" })
            assertEquals("原订阅", db.feeds().list(LOCAL_SCOPE_KEY).single { it.feedId == id }.title)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
            db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE localId = ?", arrayOf(id))
            db.feeds().delete(LOCAL_SCOPE_KEY, id)
            file.delete()
            settings.setCurrentInstanceKey(previous.currentInstanceKey)
            settings.setActiveScopeKind(previous.activeScopeKind)
            settings.setOnboardingDone(previous.onboardingDone)
        }
    }

    @Test fun local_restore_over_200_feeds_rejects_whole_file_without_writes() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val prefix = "https://example.org/$suffix/"
        val file = app.cacheDir.resolve("isolated-over-200-$suffix.json")
        val previous = settings.current()
        try {
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            file.writeBytes(StateFile.write("local", null, 1L,
                (1..201).map { StateFeed("$prefix$it.xml", "源$it", "") }, emptyList()))
            val vm = StateBackupViewModel(app)
            vm.openFile(Uri.fromFile(file), file.name)
            withTimeout(5_000) { vm.state.first { it.phase is RestorePhase.Preview } }
            vm.startRestore()
            withTimeout(5_000) { vm.state.first { it.notice?.contains("200") == true } }
            assertEquals(0, db.feeds().list(LOCAL_SCOPE_KEY).count { it.url.startsWith(prefix) })
        } finally {
            db.feeds().list(LOCAL_SCOPE_KEY).filter { it.url.startsWith(prefix) }.forEach {
                db.feeds().delete(LOCAL_SCOPE_KEY, it.feedId)
            }
            file.delete()
            settings.setCurrentInstanceKey(previous.currentInstanceKey)
            settings.setActiveScopeKind(previous.activeScopeKind)
            settings.setOnboardingDone(previous.onboardingDone)
        }
    }

    @Test fun oversized_state_file_is_rejected_before_preview() = runBlocking {
        val file = app.cacheDir.resolve("isolated-oversized-state.json")
        try {
            RandomAccessFile(file, "rw").use { it.setLength(StateFile.MAX_BYTES + 1) }
            val vm = StateBackupViewModel(app)
            vm.openFile(Uri.fromFile(file), file.name)
            val result = withTimeout(5_000) { vm.state.first { it.notice?.contains("10 MiB") == true } }
            assertTrue(result.phase is RestorePhase.Idle)
        } finally {
            file.delete()
        }
    }

    @Test fun v1_instance_file_to_local_maps_bookmark_to_new_feed_and_is_idempotent() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val url = "https://example.org/$suffix/rss"
        val guid = "state-$suffix"
        val file = app.cacheDir.resolve("isolated-v1-$suffix.json")
        val previous = settings.current()
        try {
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            file.writeText("""{"format":"catweb-state","version":1,"exportedAt":1,"sourceOrigin":"https://source.example.org","feeds":[{"url":"$url","title":"旧文件源","category":""}],"articles":[{"feedUrl":"$url","guid":"$guid","title":"旧文件收藏","feedTitle":"旧文件源","read":true,"starred":true}]}""")
            val vm = StateBackupViewModel(app)
            repeat(2) {
                vm.openFile(Uri.fromFile(file), file.name)
                val preview = withTimeout(5_000) { vm.state.first { it.phase is RestorePhase.Preview } }.phase as RestorePhase.Preview
                assertFalse(preview.sameInstance)
                vm.setMigrateConfirmed(true)
                vm.startRestore()
                withTimeout(5_000) { vm.state.first { it.phase is RestorePhase.Done } }
            }
            val feed = db.feeds().list(LOCAL_SCOPE_KEY).single { it.url == url }
            val article = db.articles().listByInstance(LOCAL_SCOPE_KEY).single { it.guid == guid }
            assertEquals(feed.feedId, article.feedId)
            assertTrue(article.isRead && article.isStarred)
            assertEquals(1, db.feeds().list(LOCAL_SCOPE_KEY).count { it.url == url })
            assertEquals(1, db.articles().listByInstance(LOCAL_SCOPE_KEY).count { it.guid == guid })
        } finally {
            db.articles().listByInstance(LOCAL_SCOPE_KEY).filter { it.guid == guid }.forEach {
                db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE localId = ?", arrayOf(it.localId))
            }
            db.feeds().list(LOCAL_SCOPE_KEY).filter { it.url == url }.forEach { db.feeds().delete(LOCAL_SCOPE_KEY, it.feedId) }
            file.delete()
            settings.setCurrentInstanceKey(previous.currentInstanceKey)
            settings.setActiveScopeKind(previous.activeScopeKind)
            settings.setOnboardingDone(previous.onboardingDone)
        }
    }

    @Test fun v2_file_restores_read_and_star_without_claiming_offline_package() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val feedId = "isolated-file-$suffix"
        val localId = "isolated-article-$suffix"
        val url = "https://example.org/$suffix/rss"
        val previous = settings.current()
        try {
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            db.feeds().upsert(FeedEntity(LOCAL_SCOPE_KEY, feedId, url, "隔离文件源", "", "ok", null))
            db.articles().upsertArticle(ArticleEntity(
                localId = localId, instanceKey = LOCAL_SCOPE_KEY, feedId = feedId, guid = suffix,
                link = "https://example.org/$suffix/story", title = "隔离文件文章", publishedAt = null,
                feedTitle = "隔离文件源", feedUrl = url, isRead = true, isStarred = true,
                starredAt = 1L, stateUpdatedAt = 1L,
            ))
            db.articles().upsertContent(ArticleContentEntity(
                localId = localId, content = "<p>不导出的离线正文</p>", summary = null,
                thumbnail = null, contentSource = "rss", contentHash = "fixture",
                searchText = "离线正文", searchBodyText = "不导出的离线正文", lastAccessedAt = 1L,
            ))
            assertTrue(store.save(LOCAL_SCOPE_KEY, localId, false).isSuccess)

            val vm = StateBackupViewModel(app)
            val exported = CompletableDeferred<Intent>()
            vm.exportState { exported.complete(it) }
            val uri = IntentCompat.getParcelableExtra(withTimeout(5_000) { exported.await() }, Intent.EXTRA_STREAM, Uri::class.java)!!
            val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val doc = StateFile.read(bytes).getOrThrow()
            assertTrue(doc.sourceKind == "local")
            assertFalse(bytes.decodeToString().contains("不导出的离线正文"))
            assertFalse(bytes.decodeToString().contains("offline_packages"))

            assertTrue(store.remove(LOCAL_SCOPE_KEY, localId).isSuccess)
            db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE localId = ?", arrayOf(localId))
            db.feeds().delete(LOCAL_SCOPE_KEY, feedId)
            vm.openFile(uri, "isolated-state.json")
            withTimeout(5_000) { vm.state.first { it.phase is RestorePhase.Preview } }
            vm.startRestore()
            withTimeout(5_000) { vm.state.first { it.phase is RestorePhase.Done } }
            val restored = db.articles().listByInstance(LOCAL_SCOPE_KEY).single { it.guid == suffix }
            assertTrue(restored.isRead && restored.isStarred)
            assertTrue(db.offline().get(restored.localId, LOCAL_SCOPE_KEY) == null)
            assertTrue(db.articles().getContent(restored.localId)?.content == null)
        } finally {
            store.remove(LOCAL_SCOPE_KEY, localId)
            db.articles().listByInstance(LOCAL_SCOPE_KEY).filter { it.guid == suffix }.forEach {
                db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE localId = ?", arrayOf(it.localId))
            }
            db.feeds().list(LOCAL_SCOPE_KEY).filter { it.url == url }.forEach { db.feeds().delete(LOCAL_SCOPE_KEY, it.feedId) }
            settings.setCurrentInstanceKey(previous.currentInstanceKey)
            settings.setActiveScopeKind(previous.activeScopeKind)
            settings.setOnboardingDone(previous.onboardingDone)
        }
    }
}
