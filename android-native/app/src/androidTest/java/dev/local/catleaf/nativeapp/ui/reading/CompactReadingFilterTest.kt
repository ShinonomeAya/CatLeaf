package dev.local.catleaf.nativeapp.ui.reading

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.article.ArticleFilter
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.util.UUID

class CompactReadingFilterTest {
    @get:Rule val rule = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)
    private val feed = "compact-${UUID.randomUUID()}"
    private lateinit var old: SettingsStore.Snapshot
    private lateinit var vm: ReadingViewModel

    @Before fun seed() = runBlocking {
        old = settings.current()
        settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
        settings.setLastReadingPosition(feed, null, null)
        db.feeds().upsert(FeedEntity(LOCAL_SCOPE_KEY, feed, "https://compact.invalid/feed", "一个名称很长的隔离订阅源用于检查箭头", "", "ok", System.currentTimeMillis()))
        repeat(3) { i ->
            db.articles().upsertArticle(ArticleEntity(
                localId = "$feed-$i", instanceKey = LOCAL_SCOPE_KEY, feedId = feed, guid = "$i",
                link = null, title = listOf("当前未读", "当前已读", "未知未读")[i],
                publishedAt = if (i == 2) null else Instant.now().toString(),
                feedTitle = "隔离订阅", feedUrl = "https://compact.invalid/feed", isRead = i == 1, stateUpdatedAt = 1,
            ))
        }
        vm = ReadingViewModel(app)
        val dark = InstrumentationRegistry.getArguments().getString("theme") == "dark"
        rule.setContent {
            CatLeafTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    ReadingScreen(vm, {}, { _, _ -> }, {})
                }
            }
        }
        rule.waitUntil(10_000) { vm.state.value.feedsLoaded && vm.state.value.loaded.size == 3 && !vm.state.value.refreshing }
    }

    @After fun cleanup() = runBlocking {
        db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE instanceKey = ? AND feedId = ?", arrayOf(LOCAL_SCOPE_KEY, feed))
        db.feeds().delete(LOCAL_SCOPE_KEY, feed)
        settings.setCurrentInstanceKey(old.currentInstanceKey)
        settings.setActiveScopeKind(old.activeScopeKind)
        settings.setLastReadingPosition(old.lastFeedId, old.lastFilter, old.lastScrollAnchor)
    }

    @Test fun date_and_unread_combine_and_cancel_preserves_selection() {
        rule.onNodeWithText("全部日期").performClick()
        rule.onNodeWithText("未知日期").performClick()
        rule.onNodeWithText("仅未读").performClick()
        rule.waitForIdle()
        assertEquals(1, vm.state.value.visible.size)
        rule.onNodeWithText("已显示的 1 篇中有 1 篇未读（只统计已加载内容）").assertExists()
        rule.onNodeWithText("未知日期").performClick()
        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(ArticleFilter.DateScope.Unknown, vm.state.value.criteria.scope)
        assertTrue(vm.state.value.criteria.unreadOnly)
        rule.onNodeWithText("未知日期").performClick()
        rule.onNodeWithText("今天").performClick()
        rule.waitForIdle()
        assertTrue(vm.state.value.criteria.unreadOnly)
        assertEquals(1, vm.state.value.visible.size)
        rule.onNodeWithText("今天").performClick()
        rule.onNodeWithText("指定日期").performClick()
        rule.onNodeWithText("取消").performClick()
        assertEquals(ArticleFilter.DateScope.Today, vm.state.value.criteria.scope)
        assertTrue(vm.state.value.criteria.unreadOnly)
        rule.onNodeWithText("清除筛选").performClick()
        rule.waitForIdle()
        assertEquals(ArticleFilter.Criteria(), vm.state.value.criteria)
        assertEquals(3, vm.state.value.visible.size)
        vm.start()
        rule.waitUntil(5_000) { !vm.state.value.refreshing }
        assertEquals(ArticleFilter.Criteria(), vm.state.value.criteria)
    }

    @Test @ManualProbe fun capture_compact_filter_and_date_sheet() {
        rule.onNodeWithText("仅未读").performClick()
        rule.waitForIdle()
        screenshot("compact-reading")
        rule.onNodeWithText("全部日期").performClick()
        rule.waitForIdle()
        screenshot("compact-date-sheet")
        rule.onNodeWithText("今天").performClick()
        rule.onNodeWithText("今天").performClick()
        rule.waitForIdle()
        screenshot("compact-date-sheet-today")
    }

    private fun screenshot(name: String) {
        Thread.sleep(400) // Wait for the window buffer after Compose becomes idle.
        java.io.File(app.getExternalFilesDir(null), "$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
