package dev.local.catleaf.nativeapp.ui.search

import android.view.KeyEvent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.MainActivity
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.offline.OfflinePackageStore
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable emulator only: proves search result opens the reader and Back restores the query. */
@RunWith(AndroidJUnit4::class)
class SearchFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db by lazy { CatLeafDatabase.get(context) }
    private val settings by lazy { SettingsStore(context) }
    private var previous: SettingsStore.Snapshot? = null
    private val id = "isolated-search-flow"
    private val feedId = "isolated-search-feed"

    @Before fun seed() { runBlocking {
        previous = settings.current()
        settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
        settings.setOnboardingDone(true)
        settings.setLastReadingPosition(feedId, null, null)
        db.feeds().upsert(FeedEntity(LOCAL_SCOPE_KEY, feedId, "https://example.invalid/rss", "隔离搜索源", "", "ok", null))
        db.articles().upsertArticle(ArticleEntity(
            localId = id, instanceKey = LOCAL_SCOPE_KEY, feedId = feedId, guid = id,
            link = "https://example.invalid/article", title = "隔离搜索标题",
            publishedAt = "2026-09-26T10:00:00Z", feedTitle = "隔离搜索源",
            feedUrl = "https://example.invalid/rss", stateUpdatedAt = 1L,
        ))
        db.articles().upsertContent(ArticleContentEntity(
            localId = id, content = "<p>正文独特尾词</p>", summary = "普通摘要", thumbnail = null,
            contentSource = "rss", contentHash = "isolated-hash", searchText = "普通摘要",
            searchBodyText = "正文独特尾词", lastAccessedAt = 1L,
        ))
        rule.activityRule.scenario.recreate()
    } }

    @After fun cleanup() { runBlocking {
        rule.activityRule.scenario.close()
        OfflinePackageStore(context, db).remove(LOCAL_SCOPE_KEY, id)
        db.openHelper.writableDatabase.execSQL("DELETE FROM articles WHERE localId = ?", arrayOf(id))
        db.feeds().delete(LOCAL_SCOPE_KEY, feedId)
        previous?.let {
            settings.setActiveScopeKind(it.activeScopeKind)
            settings.setOnboardingDone(it.onboardingDone)
            settings.setCurrentInstanceKey(it.currentInstanceKey)
            settings.setLastReadingPosition(it.lastFeedId, it.lastFilter, it.lastScrollAnchor)
        }
    } }

    @Test fun body_result_opens_reader_then_returns_to_query_and_filters() {
        rule.onNodeWithContentDescription("搜索标题与摘要").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("正文独特尾词")
        rule.onNodeWithText("搜索正文").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("隔离搜索标题").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("隔离搜索标题").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("搜索正文").fetchSemanticsNodes().isEmpty()
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("获取全文").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("搜索正文").assertExists()
        rule.onNode(hasSetTextAction()).assertTextContains("正文独特尾词")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboard_can_reach_reader_favorite_from_more_menu() {
        rule.onNodeWithContentDescription("搜索标题与摘要").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("搜索标题")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("隔离搜索标题").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("隔离搜索标题").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("搜索标题与摘要").fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("获取全文").fetchSemanticsNodes().isNotEmpty() }

        val more = rule.onNodeWithContentDescription("更多")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var moreFocused = false
        repeat(12) {
            if (!moreFocused) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                moreFocused = more.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
            }
        }
        org.junit.Assert.assertTrue("Tab traversal cannot reach Reader actions", moreFocused)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        rule.onNodeWithText("收藏").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        rule.onNodeWithText("已收藏").assertExists()
    }

    @Test fun system_back_from_search_returns_to_reading() {
        rule.onNodeWithContentDescription("搜索标题与摘要").performClick()
        rule.onNode(hasSetTextAction()).assertExists()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        // 回到阅读页头部，且 Activity 没有被结束
        rule.onNodeWithContentDescription("搜索标题与摘要").assertExists()
        org.junit.Assert.assertTrue(rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
    }

    @Test fun newer_query_drops_visible_old_result() {
        rule.onNodeWithContentDescription("搜索标题与摘要").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("隔离搜索标题")
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("隔离搜索标题").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasSetTextAction()).performTextReplacement("不存在的词")
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("没有找到匹配文章", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("隔离搜索标题").fetchSemanticsNodes().isEmpty())
    }

    @Test fun result_can_save_text_offline_from_reader_more_menu() {
        rule.onNodeWithContentDescription("搜索标题与摘要").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("隔离搜索")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("隔离搜索标题").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("隔离搜索标题").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("获取全文").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("保存离线").performClick()
        // 360dp 下该行可能在首屏边缘：先滚到可见，避免点击落空仍按「含图」保存
        rule.onNodeWithText("包含正文图片").performScrollTo().performClick()
        rule.onNodeWithText("保存到本机").performScrollTo()
        rule.onNodeWithText("保存到本机").performClick()
        rule.waitUntil(10_000) {
            runBlocking { db.offline().get(id, LOCAL_SCOPE_KEY)?.status == "text" }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("仅正文已保存", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("管理离线内容").performScrollTo()
        rule.onNodeWithText("管理离线内容").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("隔离搜索标题").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("隔离搜索标题").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("获取全文").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("离线内容").assertExists()
    }
}
