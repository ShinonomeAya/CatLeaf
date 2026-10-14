package dev.local.catleaf.nativeapp.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.ai.BilingualArticle
import dev.local.catleaf.nativeapp.data.ai.Translator
import dev.local.catleaf.nativeapp.data.network.AiClient
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.local.*
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.*
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.json.JSONArray

/** Isolated Room and versioned cache only; no requests to AI or public image servers. */
class BilingualReaderProbeTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val settings = SettingsStore(app)
    private val store = ViewModelStore()
    private lateinit var db: CatLeafDatabase
    private lateinit var vm: ReaderViewModel
    private lateinit var previous: SettingsStore.Snapshot
    private var previousCiphertext: ByteArray? = null
    private var previousOrigin: ByteArray? = null
    private var previousAccessibilityFlags = 0
    private val english = "Birds arrive in the garden every morning. The original paragraph stays here, with its link and formatting."
    private val chinese = "每天早晨，鸟儿都会来到花园。译文位于对应的原文下面，原有链接与排版继续保留。"
    private val originalTitle = "My brief romance with an AI bird feeder"
    private val translatedTitle = "我与 AI 喂鸟器的短暂情缘"
    private var aiCalls = 0
    private val body = "<p>$english <a href='https://example.org/story'>Read more</a></p>" +
        "<figure><img src='https://appassets.androidplatform.net/fixture.jpg' alt='本地图片占位' width='280' height='100'><figcaption>Original image caption</figcaption></figure>" +
        "<h2>A second section</h2><p>The second paragraph keeps its original position.</p><ul><li>First item</li><li>Second item</li></ul>" +
        "<pre><code>val greeting = \"Hello\"</code></pre>" + (1..12).joinToString("") { "<p>Additional original paragraph $it for scrolling and return checks.</p>" }
    private val base = "https://ai.invalid.test/v1"

    @Before fun setup() = runBlocking {
        previousAccessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        previous = settings.current()
        previousCiphertext = CredentialStore(app).ciphertext(CredentialStore.AI_KEY)
        previousOrigin = CredentialStore(app).ciphertext(CredentialStore.AI_KEY_ORIGIN)
        settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
        settings.setAiConfig(base, "bilingual-isolated")
        settings.setBodyFontSize(18)
        settings.setReaderPosition(null)
        CredentialStore(app).putAiKey(base, "NOT-A-REAL-KEY-bilingual-probe")
        db = Room.inMemoryDatabaseBuilder(app, CatLeafDatabase::class.java).build()
        val sanitized = ArticleSanitizer.sanitize(body, "https://example.org/story")
        db.articles().upsertArticle(ArticleEntity("bilingual-probe", LOCAL_SCOPE_KEY, "fixture", "fixture", "https://example.org/story",
            originalTitle, null, "隔离样本", "https://example.org/feed", stateUpdatedAt = 1))
        db.articles().upsertContent(ArticleContentEntity("bilingual-probe", sanitized.html, null, null, "rss", "fixture", lastAccessedAt = 1))
        val article = BilingualArticle.fromHtml(sanitized.html, originalTitle)
        val translations = article.blocks.associate { it.id to when (it.id) {
            BilingualArticle.TITLE_ID -> translatedTitle
            "catleaf-b1" -> chinese
            else -> "对应中文：${it.text}"
        } }
        val hash = Translator.hash(sanitized.plainText)
        db.aiCache().insert(AiCacheEntity(Translator.cacheKey(LOCAL_SCOPE_KEY, "bilingual-probe", hash, base, "bilingual-isolated", article.fingerprint),
            LOCAL_SCOPE_KEY, Translator.TASK, base, "bilingual-isolated", hash, Translator.TARGET_LANGUAGE,
            Translator.PROMPT_VERSION, article.serializeTranslations(translations), true, 1))
        val translator = Translator(db.aiCache(), AiClient { OkHttpClient.Builder().addInterceptor { chain ->
            aiCalls++
            val result = article.serializeTranslations(translations)
            val chat = JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", result)).put("finish_reason", "stop")))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(chat.toString().toResponseBody("application/json".toMediaType())).build()
        }.build() })
        instrumentation.runOnMainSync { vm = ReaderViewModel(app, db, translationService = translator); store.put("reader", vm) }
    }

    @After fun cleanup() = runBlocking {
        instrumentation.runOnMainSync { store.clear() }
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = previousAccessibilityFlags }
        settings.setAiConfig(previous.aiBaseUrl, previous.aiModel)
        settings.setActiveScopeKind(previous.activeScopeKind)
        settings.setCurrentInstanceKey(previous.currentInstanceKey)
        settings.setBodyFontSize(previous.bodyFontSizeSp)
        settings.setReaderPosition(previous.readerPosition)
        CredentialStore(app).restoreCiphertext(CredentialStore.AI_KEY, previousCiphertext)
        CredentialStore(app).restoreCiphertext(CredentialStore.AI_KEY_ORIGIN, previousOrigin)
        CurrentBody.localId = null
        db.close()
    }

    private fun visibleText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        return node.text?.toString().orEmpty() + (0 until node.childCount).joinToString("\n") { visibleText(node.getChild(it)) }
    }

    private fun windowText(): String = instrumentation.uiAutomation.windows.joinToString("\n") { visibleText(it.root) }

    private fun visibleNode(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        // Chromium can report an offscreen control as a visible sliver of up to two CSS pixels.
        if (node.text?.toString() == text && node.isVisibleToUser && bounds.height() > app.resources.displayMetrics.density * 2) return node
        return (0 until node.childCount).firstNotNullOfOrNull { visibleNode(node.getChild(it), text) }
    }

    private fun visibleNode(text: String): AccessibilityNodeInfo? = instrumentation.uiAutomation.windows
        .firstNotNullOfOrNull { visibleNode(it.root, text) }

    private fun capture(name: String) {
        Thread.sleep(400)
        File(app.getExternalFilesDir(null), "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun exercise(capture: Boolean) {
        val dark = InstrumentationRegistry.getArguments().getString("theme") == "dark"
        rule.setContent { CatLeafTheme(darkTheme = dark) {
            Surface(Modifier.fillMaxSize()) { ReaderScreen("bilingual-probe", vm, {}, {}, {}) }
        } }
        rule.waitUntil(10000) { vm.state.value.translationSegments != null && !vm.state.value.loading }
        val before = vm.state.value
        rule.onNodeWithText("双语对照").performClick()
        rule.waitUntil(10000) { windowText().contains(chinese) }
        val text = windowText()
        assertTrue(text.contains(english))
        assertTrue(text.indexOf(english) < text.indexOf(chinese))
        assertTrue(text.contains(originalTitle) && text.contains(translatedTitle))
        assertTrue(text.indexOf(originalTitle) < text.indexOf(translatedTitle))
        assertNotNull(visibleNode("重新翻译"))
        assertEquals(before.bodyHtml, vm.state.value.bodyHtml)
        assertEquals(before.bodyHash, vm.state.value.bodyHash)
        if (capture) capture("bilingual-top")
        rule.onRoot().performTouchInput {
            swipe(Offset(center.x, height * .8f), Offset(center.x, height * .25f), 800)
        }
        Thread.sleep(1200)
        if (capture) capture("bilingual-body")
        assertNull("Status action must leave the viewport with the article", visibleNode("重新翻译"))
        rule.onNodeWithText("原文").assertExists()
        rule.onNodeWithText("双语对照").assertExists()
        rule.onNodeWithText("原文").performClick()
        rule.waitUntil(10000) { !windowText().contains(chinese) }
        assertFalse(vm.state.value.showTranslation)
        if (capture) capture("bilingual-original")
        rule.onNodeWithText("双语对照").performClick()
        assertTrue(vm.state.value.showTranslation)
        assertTrue(vm.state.value.translationCached)
        assertEquals(before.translatableChars, vm.state.value.translatableChars)
        assertEquals(0, aiCalls)
    }

    @Test fun scrolling_retranslate_control_dispatches_one_explicit_local_request() {
        rule.setContent { CatLeafTheme { Surface(Modifier.fillMaxSize()) { ReaderScreen("bilingual-probe", vm, {}, {}, {}) } } }
        rule.waitUntil(10000) { vm.state.value.translationSegments != null && !vm.state.value.loading }
        rule.onNodeWithText("双语对照").performClick()
        rule.waitUntil(10000) { visibleNode("重新翻译") != null }
        assertEquals(0, aiCalls)
        assertTrue(visibleNode("重新翻译")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        rule.waitUntil(10000) { aiCalls == 1 && !vm.state.value.translating && vm.state.value.showTranslation }
        assertEquals(translatedTitle, vm.state.value.translationSegments!![BilingualArticle.TITLE_ID])
        assertFalse(vm.state.value.translationCached)
    }

    @Test fun cached_bilingual_is_same_reader_and_original_remains_unchanged() = exercise(false)
    @Test @ManualProbe fun capture_bilingual_matrix_without_ai_request() = exercise(true)
}
