package dev.local.catleaf.nativeapp.ui.reading

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.local.InstanceEntity
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class ReadingPaginationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun saved_201st_article_is_reachable() = verify(201)
    @Test fun saved_450_articles_page_without_duplicates_and_survive_reentry() = verify(450)

    @Test fun all_saved_pages_are_reachable_while_sources_refresh() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = CatLeafDatabase.get(app)
        val settings = SettingsStore(app)
        val old = settings.current()
        val scope = "all_paging_${UUID.randomUUID().toString().replace("-", "") }"
        val credentials = CredentialStore(app)
        val release = CountDownLatch(1)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path.orEmpty().startsWith("/api/feed?")) {
                        release.await(20, TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(500)
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        try {
            db.instances().upsert(InstanceEntity(scope, server.url("").toString().trimEnd('/'), "隔离聚合分页", 1))
            credentials.put("instance_secret_$scope", "FAKE-all-paging")
            for (feedId in listOf("a", "b")) db.feeds().upsert(FeedEntity(scope, feedId, "https://paging.invalid/$feedId", "分页源$feedId", "", "ok", 1))
            repeat(450) { i -> db.articles().upsertArticle(ArticleEntity(
                localId = "$scope-${i.toString().padStart(4, '0')}", instanceKey = scope, feedId = if (i % 2 == 0) "a" else "b",
                guid = "g$i", link = null, title = "聚合分页 $i", publishedAt = null,
                feedTitle = "分页源${if (i % 2 == 0) "a" else "b"}", feedUrl = "https://paging.invalid", stateUpdatedAt = 1)) }
            settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
            settings.setCurrentInstanceKey(scope)
            settings.setLastReadingPosition(null, null, null, scope, allFeeds = true)
            val vm = ReadingViewModel(app)
            rule.setContent { CatLeafTheme { ReadingScreen(vm, {}, { _, _ -> }, {}) } }
            withTimeout(5_000) { vm.state.first { it.allFeeds && it.loaded.size == 200 && it.refreshing } }
            rule.onNodeWithText("显示 200 / 450 篇").assertExists()
            for (count in listOf(400, 450)) {
                assertTrue("Refresh must still be in flight", vm.state.value.refreshing)
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("加载更多历史"))
                rule.onNodeWithText("加载更多历史").assertIsEnabled().performClick()
                vm.loadMoreHistory() // Same-frame repeat must not skip a saved page.
                withTimeout(5_000) { vm.state.first { !it.loadingMore && it.loaded.size == count } }
                assertEquals(count, vm.state.value.loaded.map { it.entity.localId }.distinct().size)
            }
            release.countDown()
            withTimeout(5_000) { vm.state.first { !it.refreshing } }
            assertEquals(450, vm.state.value.loaded.size)
            assertFalse(vm.state.value.hasMoreHistory)
            assertNotNull(vm.state.value.offlineNotice)
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("聚合分页 0"))
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("显示 450 / 450 篇"))
            Unit
        } finally {
            release.countDown()
            server.shutdown()
            db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE instanceKey = ?", arrayOf(scope))
            db.feeds().deleteAll(scope)
            db.openHelper.writableDatabase.execSQL("DELETE FROM instances WHERE instanceKey = ?", arrayOf(scope))
            credentials.delete("instance_secret_$scope")
            settings.setCurrentInstanceKey(old.currentInstanceKey)
            settings.setActiveScopeKind(old.activeScopeKind)
            settings.setLastReadingPosition(old.lastFeedId, old.lastFilter, old.lastScrollAnchor, old.lastReadingScopeKey, old.lastReadingAllFeeds, old.lastReadingLoadedCount)
        }
    }

    private fun verify(count: Int) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = CatLeafDatabase.get(app)
        val settings = SettingsStore(app)
        val old = settings.current()
        val feedId = "paging-${UUID.randomUUID()}"
        try {
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            settings.setLastReadingPosition(feedId, null, null)
            db.feeds().upsert(FeedEntity(LOCAL_SCOPE_KEY, feedId, "https://paging.invalid/feed", "隔离分页", "", "ok", null))
            repeat(count) { i ->
                db.articles().upsertArticle(ArticleEntity(
                    localId = "$feedId-${i.toString().padStart(4, '0')}", instanceKey = LOCAL_SCOPE_KEY,
                    feedId = feedId, guid = "g$i", link = "https://example.org/$i", title = "分页 $i",
                    publishedAt = null, feedTitle = "隔离分页", feedUrl = "https://paging.invalid/feed", stateUpdatedAt = 1,
                ))
            }
            val vm = ReadingViewModel(app)
            rule.setContent { CatLeafTheme { ReadingScreen(vm, {}, { _, _ -> }, {}) } }
            withTimeout(25_000) { vm.state.first { it.loaded.size == 200 && !it.refreshing && it.offlineNotice != null } }
            assertTrue(vm.state.value.hasMoreHistory)
            vm.setScope(dev.local.catleaf.nativeapp.data.article.ArticleFilter.DateScope.Unknown)
            while (vm.state.value.hasMoreHistory) {
                val before = vm.state.value.loaded.size
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("加载更多历史"))
                if (before == 200) screenshot(app, "$count-more")
                rule.onNodeWithText("加载更多历史").performClick()
                vm.loadMoreHistory() // same-frame double click must not request another page
                withTimeout(5_000) { vm.state.first { !it.loadingMore && it.loaded.size > before } }
                assertEquals(minOf(before + 200, count), vm.state.value.loaded.size)
                assertEquals(vm.state.value.loaded.size, vm.state.value.loaded.map { it.entity.localId }.distinct().size)
            }
            assertEquals(count, vm.state.value.visible.size)
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("分页 0"))
            screenshot(app, "$count-last-article")
            vm.start()
            withTimeout(5_000) { vm.state.first { it.feedsLoaded && !it.refreshing && it.loaded.size == count } }
            assertEquals(count, vm.state.value.loaded.size)
            assertFalse(vm.state.value.hasMoreHistory)
            assertEquals(dev.local.catleaf.nativeapp.data.article.ArticleFilter.DateScope.Unknown, vm.state.value.criteria.scope)
        } finally {
            db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE instanceKey = ? AND feedId = ?", arrayOf(LOCAL_SCOPE_KEY, feedId))
            db.feeds().delete(LOCAL_SCOPE_KEY, feedId)
            settings.setCurrentInstanceKey(old.currentInstanceKey)
            settings.setActiveScopeKind(old.activeScopeKind)
            settings.setLastReadingPosition(old.lastFeedId, old.lastFilter, old.lastScrollAnchor)
        }
    }

    private fun screenshot(app: Application, name: String) {
        java.io.File(app.getExternalFilesDir(null), "paging-$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
