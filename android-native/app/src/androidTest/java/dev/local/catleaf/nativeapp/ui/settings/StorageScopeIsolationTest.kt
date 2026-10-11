package dev.local.catleaf.nativeapp.ui.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.OfflinePackageEntity
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.ui.reader.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageScopeIsolationTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)

    @Test fun offline_manager_reloads_only_active_scope_after_switch() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val instance = "isolated-storage-$suffix"
        val localId = "local-$suffix"
        val instanceId = "instance-$suffix"
        val previous = settings.current()
        fun row(id: String, scope: String) = OfflinePackageEntity(
            localId = id, scopeKey = scope, title = id, feedTitle = "test", generation = "test",
            status = "text", selectedImages = false, imageTotal = 0, imageSaved = 0,
            bytes = 1, bodyHash = "test", searchBodyText = "test", imageManifest = "[]", updatedAt = 1,
        )
        try {
            db.offline().upsert(row(localId, LOCAL_SCOPE_KEY))
            db.offline().upsert(row(instanceId, instance))
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            val vm = StorageViewModel(app)
            vm.load()
            withTimeout(5_000) { vm.state.first { !it.loading && it.offlinePackages.any { p -> p.localId == localId } } }
            settings.setCurrentInstanceKey(instance)
            settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
            val switched = withTimeout(5_000) {
                vm.state.first { !it.loading && it.offlinePackages.any { p -> p.localId == instanceId } }
            }
            assertEquals(listOf(instanceId), switched.offlinePackages.filter { it.localId in setOf(localId, instanceId) }.map { it.localId })
        } finally {
            db.offline().delete(localId, LOCAL_SCOPE_KEY)
            db.offline().delete(instanceId, instance)
            settings.setCurrentInstanceKey(previous.currentInstanceKey)
            settings.setActiveScopeKind(previous.activeScopeKind)
        }
    }

    @Test fun reader_rejects_article_from_inactive_scope_before_marking_read() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val instance = "isolated-reader-$suffix"
        val id = "instance-article-$suffix"
        val previous = settings.current()
        try {
            db.articles().upsertArticle(ArticleEntity(
                localId = id, instanceKey = instance, feedId = "feed-$suffix", guid = suffix,
                link = "https://example.org/$suffix", title = "跨范围文章", publishedAt = null,
                feedTitle = "隔离源", feedUrl = "https://example.org/feed", stateUpdatedAt = 1,
            ))
            db.articles().upsertContent(ArticleContentEntity(
                localId = id, content = "<p>跨范围正文不能泄漏</p>", summary = null, thumbnail = null,
                contentSource = "rss", contentHash = "test", searchText = "跨范围正文",
                searchBodyText = "跨范围正文不能泄漏", lastAccessedAt = 1,
            ))
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            val vm = ReaderViewModel(app)
            vm.open(id)
            val blocked = withTimeout(5_000) { vm.state.first { it.missing } }
            assertEquals(null, blocked.bodyHtml)
            assertFalse(db.articles().get(id)!!.isRead)

            settings.setCurrentInstanceKey(instance)
            settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
            vm.open(id)
            val allowed = withTimeout(5_000) { vm.state.first { !it.loading && it.bodyHtml?.contains("跨范围正文不能泄漏") == true } }
            assertTrue(allowed.isRead)
        } finally {
            db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE localId = ?", arrayOf(id))
            settings.setCurrentInstanceKey(previous.currentInstanceKey)
            settings.setActiveScopeKind(previous.activeScopeKind)
        }
    }
}
