package dev.local.catleaf.nativeapp.ui.reading

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.article.ArticleFilter
import dev.local.catleaf.nativeapp.data.local.*
import dev.local.catleaf.nativeapp.data.settings.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AllSubscriptionsTest {
    private fun awaitState(timeoutMs: Long, condition: () -> Boolean) = runBlocking {
        withTimeout(timeoutMs) { while (!condition()) delay(20) }
    }

    @Test fun aggregate_pages_filters_membership_and_scope_restore() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = CatLeafDatabase.get(app)
        val settings = SettingsStore(app)
        val old = runBlocking { settings.current() }
        val scope = "all-${UUID.randomUUID()}"
        val other = "$scope-other"
        try {
            runBlocking {
            db.instances().upsert(InstanceEntity(scope, "https://all.invalid", "隔离全部", 1))
            db.instances().upsert(InstanceEntity(other, "https://other.invalid", "隔离另范围", 1))
            for (f in listOf("a", "b")) db.feeds().upsert(FeedEntity(scope, f, "https://all.invalid/$f", "隔离源$f", "", "ok", 1))
            repeat(450) { i -> db.articles().upsertArticle(ArticleEntity(
                localId = "$scope-${i.toString().padStart(4, '0')}", instanceKey = scope, feedId = if (i % 2 == 0) "a" else "b",
                guid = "$i", link = null, title = "聚合文章$i", publishedAt = if (i == 449) "2000-01-01T00:00:00Z" else if (i == 448) null else "2026-10-08T00:00:00Z",
                feedTitle = "隔离源${if (i % 2 == 0) "a" else "b"}", feedUrl = "https://all.invalid", isRead = i != 449, stateUpdatedAt = 1)) }
            db.articles().upsertArticle(ArticleEntity("$scope-orphan", scope, "deleted", null, null, "已删源收藏", null, "已删源", "https://all.invalid", isStarred = true, stateUpdatedAt = 1))
            settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
            settings.setCurrentInstanceKey(scope)
            settings.setLastReadingPosition("a", null, null, scope)
            }
            val vm = ReadingViewModel(app)
            vm.start()
            awaitState(10_000) { vm.state.value.let { it.feedsLoaded && it.loaded.size == 200 && !it.refreshing } }
            vm.selectAllFeeds()
            awaitState(10_000) { vm.state.value.let { it.allFeeds && it.loaded.size == 200 && !it.refreshing } }
            assertEquals(setOf("a", "b"), vm.state.value.loaded.map { it.entity.feedId }.toSet())
            for (count in listOf(400, 450)) {
                vm.loadMoreHistory(); vm.loadMoreHistory()
                awaitState(5_000) { vm.state.value.let { !it.loadingMore && it.loaded.size == count } }
                assertEquals(count, vm.state.value.loaded.map { it.entity.localId }.distinct().size)
            }
            assertFalse(vm.state.value.hasMoreHistory)
            val deepAnchor = vm.state.value.loaded.last().entity.localId
            vm.rememberScrollAnchor(deepAnchor, 12)
            awaitState(5_000) { runBlocking { settings.current().lastReadingLoadedCount == 450 } }
            val reopened = ReadingViewModel(app)
            reopened.start()
            awaitState(5_000) { reopened.state.value.let { it.allFeeds && !it.refreshing && it.loaded.size == 450 } }
            assertTrue(reopened.state.value.loaded.any { it.entity.localId == deepAnchor })
            assertEquals(deepAnchor, runBlocking { reopened.savedScrollAnchor() }!!.localId)
            assertNotNull(vm.state.value.offlineNotice) // Missing fake instance credentials preserve all saved rows.
            assertTrue(runBlocking { db.articles().get("$scope-orphan")!!.isStarred })
            vm.toggleUnreadOnly()
            awaitState(5_000) { vm.state.value.let { !it.loadingMore && it.loaded.size == 1 } }
            assertEquals("聚合文章449", vm.state.value.visible.single().entity.title)
            vm.setScope(ArticleFilter.DateScope.Unknown)
            awaitState(5_000) { vm.state.value.let { !it.loadingMore && it.loaded.isEmpty() } }
            vm.clearFilters()
            awaitState(5_000) { vm.state.value.let { !it.loadingMore && it.loaded.size == 200 } }
            vm.rememberScrollAnchor(vm.state.value.loaded.first().entity.localId, 12)
            awaitState(5_000) { runBlocking { settings.current().let { it.lastReadingAllFeeds && it.lastScrollAnchor != null } } }
            vm.start()
            awaitState(5_000) { vm.state.value.let { it.allFeeds && it.feedsLoaded && !it.refreshing } }
            assertNotNull(runBlocking { vm.savedScrollAnchor() })
            vm.selectFeed("b")
            awaitState(5_000) { vm.state.value.let { !it.allFeeds && it.currentFeedId == "b" && !it.refreshing } }
            assertTrue(vm.state.value.loaded.all { it.entity.feedId == "b" })
            vm.selectAllFeeds()
            awaitState(5_000) { vm.state.value.let { it.allFeeds && !it.refreshing } }
            runBlocking { settings.setCurrentInstanceKey(other) }
            vm.start()
            awaitState(5_000) { vm.state.value.let { it.feedsLoaded && it.noFeeds && !it.allFeeds } }
            assertNull(runBlocking { vm.savedScrollAnchor() })
        } finally {
            runBlocking {
            for (key in listOf(scope, other)) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE instanceKey = ?", arrayOf(key))
                db.feeds().deleteAll(key)
                db.openHelper.writableDatabase.execSQL("DELETE FROM instances WHERE instanceKey = ?", arrayOf(key))
            }
            settings.setCurrentInstanceKey(old.currentInstanceKey)
            settings.setActiveScopeKind(old.activeScopeKind)
            settings.setLastReadingPosition(old.lastFeedId, old.lastFilter, old.lastScrollAnchor, old.lastReadingScopeKey, old.lastReadingAllFeeds, old.lastReadingLoadedCount)
            }
        }
    }

}
