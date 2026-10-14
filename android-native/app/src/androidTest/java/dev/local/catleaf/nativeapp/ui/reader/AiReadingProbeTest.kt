package dev.local.catleaf.nativeapp.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.ai.ArticleAssistant
import dev.local.catleaf.nativeapp.data.ai.BilingualArticle
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.local.*
import dev.local.catleaf.nativeapp.data.opml.OpmlImporter
import dev.local.catleaf.nativeapp.data.network.AiClient
import dev.local.catleaf.nativeapp.data.settings.*
import dev.local.catleaf.nativeapp.ui.feeds.OpmlResultSurface
import dev.local.catleaf.nativeapp.ui.digest.DigestScreen
import dev.local.catleaf.nativeapp.ui.digest.DigestViewModel
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Isolated database and cached synthetic answers; no provider key or network request. */
@ManualProbe
class AiReadingProbeTest {
    @get:Rule val rule = createComposeRule()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as Application
    private val settings = SettingsStore(app)
    private val store = ViewModelStore()
    private lateinit var previous: SettingsStore.Snapshot
    private lateinit var db: CatLeafDatabase
    private lateinit var vm: ReaderViewModel
    private lateinit var digest: DigestViewModel
    private val question = "试点持续多久？"
    private val base = "https://api.deepseek.com"
    private val model = "deepseek-flash"

    @Before fun setup() = runBlocking {
        previous = settings.current()
        settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
        settings.setAiConfig(base, model)
        settings.setReaderPosition(null)
        db = Room.inMemoryDatabaseBuilder(app, CatLeafDatabase::class.java).build()
        val body = ArticleSanitizer.sanitize((1..80).joinToString("") {
            "<p>Original paragraph $it. The city pilot covers two bus routes for six months, and the evaluation considers passenger feedback.</p>"
        }, "https://example.org/story")
        val article = BilingualArticle.fromHtml(body.html, "City bus pilot")
        db.articles().upsertArticle(ArticleEntity("reading-probe", LOCAL_SCOPE_KEY, "fixture", "fixture", "https://example.org/story",
            "City bus pilot", null, "隔离阅读样本", "https://example.org/feed", stateUpdatedAt = 1))
        db.articles().upsertContent(ArticleContentEntity("reading-probe", body.html, null, null, "rss", "fixture", lastAccessedAt = 1))
        db.feeds().upsert(FeedEntity(LOCAL_SCOPE_KEY, "fixture", "https://example.org/feed", "隔离订阅", "", "ok", null))
        for (i in 1..2) {
            db.articles().upsertArticle(ArticleEntity("digest-ui-$i", LOCAL_SCOPE_KEY, "fixture", "$i", null,
                "日摘要测试$i", "2026-10-14T12:00:00Z", "隔离订阅", "https://example.org/feed", stateUpdatedAt = 1))
            db.articles().upsertContent(ArticleContentEntity("digest-ui-$i", "<p>本地日摘要测试段落$i。</p>", null, null, "rss", "digest", lastAccessedAt = 1))
        }
        for (mode in listOf(ArticleAssistant.Mode.Overview, ArticleAssistant.Mode.Question)) {
            val request = ArticleAssistant.Request(LOCAL_SCOPE_KEY, "reading-probe", article, "Rss", mode,
                question = if (mode == ArticleAssistant.Mode.Question) question else "", baseUrl = base, model = model)
            val points = if (mode == ArticleAssistant.Mode.Overview) """[{"text":"评估将参考乘客反馈。","sourceIds":["catleaf-b40"]}]""" else "[]"
            val sources = (1..40).joinToString(",") { "\"catleaf-b$it\"" }
            val result = """{"text":"公交试点覆盖两条线路，持续六个月。","sourceIds":[$sources],"points":$points,"notInArticle":false,"background":""}"""
            db.aiCache().insert(AiCacheEntity(ArticleAssistant.cacheKey(request), LOCAL_SCOPE_KEY, ArticleAssistant.TASK,
                base, model, article.fingerprint, "zh-CN", ArticleAssistant.PROMPT_VERSION, result, true, 1))
        }
        val assistant = ArticleAssistant(db.aiCache(), AiClient { error("UI探针禁止供应商请求") })
        instrument.runOnMainSync {
            vm = ReaderViewModel(app, db, readingService = assistant); store.put("reader", vm)
            digest = DigestViewModel(app, db); store.put("digest", digest)
        }
    }

    @After fun cleanup() = runBlocking {
        instrument.runOnMainSync { store.clear() }
        settings.setAiConfig(previous.aiBaseUrl, previous.aiModel)
        settings.setActiveScopeKind(previous.activeScopeKind)
        settings.setCurrentInstanceKey(previous.currentInstanceKey)
        settings.setReaderPosition(previous.readerPosition)
        CurrentBody.localId = null
        db.close()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        Thread.sleep(450)
        File(app.getExternalFilesDir(null), "reading-$name.png").outputStream().use {
            instrument.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun webView(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { webView(view.getChildAt(it)) } else null

    @Test fun native_surfaces_keyboard_and_source_jump_without_ai_request() {
        val scene = mutableIntStateOf(0)
        val report = OpmlImporter.Report(listOf(
            OpmlImporter.ItemResult("科技观察", "https://example.org/feed", "科技", OpmlImporter.Outcome.Added("a", false, "源站返回HTTP503，可稍后重试")),
            OpmlImporter.ItemResult("下一批订阅", "https://example.org/next", "", OpmlImporter.Outcome.Deferred),
        ))
        var retries = 0
        var next = 0
        val dark = InstrumentationRegistry.getArguments().getString("dark") == "true"
        rule.setContent { CatLeafTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize()) {
            when (scene.intValue) {
                1 -> ReaderScreen("reading-probe", vm, {}, {}, {})
                2 -> DigestScreen(DigestViewModel.ALL_FEEDS, "2026-10-14", digest, {}, {})
                else -> OpmlResultSurface(report, false, true, { retries++ }, {}, { next++ })
            }
        } } }
        capture("opml")
        rule.onAllNodesWithText("重试失败项", substring = true).onLast().performClick()
        assertEquals(1, retries)
        rule.onNodeWithText("继续下一批（1）").performClick()
        assertEquals(1, next)
        rule.runOnIdle { scene.intValue = 1 }
        rule.waitUntil(10000) { vm.state.value.hasText && !vm.state.value.loading }
        rule.onNodeWithContentDescription("AI解读").performClick()
        rule.waitUntil(10000) { vm.state.value.aiReading.cached }
        assertEquals(40, vm.state.value.aiReading.answer?.sourceIds?.size)
        rule.onAllNodesWithContentDescription("查看原文段落40").assertCountEquals(0)
        rule.onNodeWithText("概览").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭AI解读").assertIsDisplayed()
        capture("overview")
        rule.onNodeWithText("问本文").performClick()
        capture("empty")
        val panelTop = rule.onNodeWithText("AI解读").fetchSemanticsNode().boundsInRoot.top
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp(durationMillis = 100) }
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown(durationMillis = 100) }
        rule.waitForIdle()
        assertEquals(panelTop, rule.onNodeWithText("AI解读").fetchSemanticsNode().boundsInRoot.top, 2f)
        rule.onNode(hasSetTextAction()).performScrollTo().performClick().performTextInput(question)
        capture("keyboard")
        instrument.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
        instrument.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
        rule.onNodeWithText("提问", substring = false).performClick()
        rule.waitUntil(10000) { vm.state.value.aiReading.cached }
        capture("question")
        rule.onNodeWithText("解释段落").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("原文段落1"))
        capture("paragraphs")
        rule.onNodeWithText("原文段落1").assertIsDisplayed().performClick()
        rule.waitUntil(5000) { vm.state.value.aiReading.paragraphId == "catleaf-b1" }
        assertEquals("catleaf-b1", vm.state.value.aiReading.paragraphId)
        capture("explain")
        rule.onNodeWithText("概览").performClick()
        rule.waitUntil(10000) { vm.state.value.aiReading.cached }
        rule.onNodeWithText("查看依据（40处）").performScrollTo().performClick()
        rule.onNodeWithText("收起依据（40处）").assertIsDisplayed()
        rule.onNodeWithText("收起依据（40处）").performClick()
        rule.onAllNodesWithContentDescription("查看原文段落40").assertCountEquals(0)
        rule.onNodeWithText("查看依据（40处）").performClick()
        capture("citations")
        rule.onNodeWithContentDescription("查看原文段落40").performScrollTo().performClick()
        rule.waitUntil(10000) { !vm.state.value.aiReading.open && vm.state.value.paragraphJump == null }
        var jumped = false
        rule.waitUntil(10000) {
            instrument.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
                val web = activity?.window?.decorView?.let(::webView)
                jumped = web != null && web.scrollY > 0 && web.url.orEmpty().endsWith("#catleaf-b40")
            }
            jumped
        }
        capture("source-jump")
        instrument.runOnMainSync { vm.openAiReading() }
        rule.onNodeWithContentDescription("关闭AI解读").performClick()
        assertFalse(vm.state.value.aiReading.open)
        rule.runOnIdle { scene.intValue = 2 }
        rule.waitUntil(10000) { !digest.state.value.loading && digest.state.value.selectedCount == 2 }
        capture("digest-scope")
        rule.onNodeWithText("清空勾选").performScrollTo().performClick()
        assertEquals(0, digest.state.value.selectedCount)
        rule.onNodeWithText("日摘要测试1").performClick()
        assertEquals(1, digest.state.value.selectedCount)
    }
}
