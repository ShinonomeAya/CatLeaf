package dev.local.catleaf.nativeapp.ui.reader

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.ai.Translator
import dev.local.catleaf.nativeapp.data.ai.BilingualArticle
import dev.local.catleaf.nativeapp.data.ai.ArticleAssistant
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.article.FullTextExtractor
import dev.local.catleaf.nativeapp.data.local.*
import dev.local.catleaf.nativeapp.data.network.AiClient
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** In-memory Room and intercepted local responses only; no external AI requests. */
@RunWith(AndroidJUnit4::class)
class FullTextSessionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val settings = SettingsStore(app)
    private val main = InstrumentationRegistry.getInstrumentation()
    private lateinit var db: CatLeafDatabase
    private lateinit var vm: ReaderViewModel
    private val store = ViewModelStore()
    private lateinit var previous: SettingsStore.Snapshot
    private var previousCiphertext: ByteArray? = null
    private var previousOrigin: ByteArray? = null
    private val title = "本地测试标题"
    private val oldHtml = "<p>旧正文</p>"
    private val fullHtml = "<html><head><title>$title</title><meta property='og:image' content='https://example.org/cover.png'></head><body><article><h1>$title</h1><p>${"完整正文用于同会话测试。".repeat(45)}</p></article></body></html>"
    private var extractGate: CountDownLatch? = null
    private var translationGate: CountDownLatch? = null
    private val extractStarted = CountDownLatch(1)
    private var translationStarted = CountDownLatch(1)
    private val translationFinished = CountDownLatch(1)
    private var prompt = ""
    private var invalidTranslation = false
    private val base = "https://ai.example.org/v1"
    private val model = "rf-local-fake"
    private var readingGate: CountDownLatch? = null
    private val readingStarted = CountDownLatch(1)
    private val readingFinished = CountDownLatch(1)
    private var readingCalls = 0
    private var readingPrompt = ""
    private var invalidReading = false

    private fun client(body: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body(chain.request()).toResponseBody("text/html".toMediaType())).build()
    }.build()

    @Before fun setup() = runBlocking {
        previous = settings.current()
        previousCiphertext = CredentialStore(app).ciphertext(CredentialStore.AI_KEY)
        previousOrigin = CredentialStore(app).ciphertext(CredentialStore.AI_KEY_ORIGIN)
        settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
        settings.setAiConfig(base, model)
        CredentialStore(app).putAiKey(base, "NOT-A-REAL-KEY-rf-local")
        db = Room.inMemoryDatabaseBuilder(app, CatLeafDatabase::class.java).build()
        seed("rf-session")
        val fetch = PublicFetch(client = client {
            extractStarted.countDown()
            check(extractGate?.await(15, TimeUnit.SECONDS) != false)
            fullHtml
        })
        val translator = Translator(db.aiCache(), AiClient { client {
            val buffer = okio.Buffer()
            it.body!!.writeTo(buffer)
            prompt = buffer.readUtf8()
            translationStarted.countDown()
            check(translationGate?.await(15, TimeUnit.SECONDS) != false)
            translationFinished.countDown()
            val decodedPrompt = org.json.JSONObject(prompt).getJSONArray("messages").getJSONObject(0).getString("content")
            val blocks = org.json.JSONArray(decodedPrompt.substringAfter("-----BEGIN-----").substringBefore("-----END-----").trim())
            val segments = org.json.JSONArray()
            for (index in 0 until blocks.length()) {
                segments.put(org.json.JSONObject().put("id", blocks.getJSONObject(index).getString("id")).put("text", "本地假译文"))
            }
            val result = if (invalidTranslation) "broken response" else org.json.JSONObject().put("segments", segments).toString()
            org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject()
                .put("message", org.json.JSONObject().put("content", result)).put("finish_reason", "stop"))).toString()
        } })
        val reading = ArticleAssistant(db.aiCache(), AiClient { client { request ->
            readingCalls++
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            readingPrompt = org.json.JSONObject(buffer.readUtf8()).getJSONArray("messages").getJSONObject(0).getString("content")
            readingStarted.countDown()
            check(readingGate?.await(15, TimeUnit.SECONDS) != false)
            readingFinished.countDown()
            val result = if (invalidReading) "broken" else """{"text":"本地概览","sourceIds":["catleaf-b1"],"points":[{"text":"本地重点","sourceIds":["catleaf-b1"]}],"notInArticle":false,"background":""}"""
            org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject()
                .put("message", org.json.JSONObject().put("content", result)).put("finish_reason", "stop"))).toString()
        } })
        main.runOnMainSync { vm = ReaderViewModel(app, db, FullTextExtractor(null, fetch), translator, reading); store.put("reader", vm) }
    }

    @After fun cleanup() = runBlocking {
        extractGate?.countDown(); translationGate?.countDown(); readingGate?.countDown()
        main.runOnMainSync { store.clear() }
        settings.setAiConfig(previous.aiBaseUrl, previous.aiModel)
        settings.setActiveScopeKind(previous.activeScopeKind)
        settings.setCurrentInstanceKey(previous.currentInstanceKey)
        CredentialStore(app).restoreCiphertext(CredentialStore.AI_KEY, previousCiphertext)
        CredentialStore(app).restoreCiphertext(CredentialStore.AI_KEY_ORIGIN, previousOrigin)
        CurrentBody.localId = null
        db.close()
    }

    private suspend fun seed(id: String) {
        db.articles().upsertArticle(ArticleEntity(id, LOCAL_SCOPE_KEY, "feed", id, "https://example.org/story", title, null, "测试源", "https://example.org/feed", stateUpdatedAt = 1))
        db.articles().upsertContent(ArticleContentEntity(id, oldHtml, "可靠摘要", null, "rss", "old", lastAccessedAt = 1))
    }
    private suspend fun cache(text: String, result: String) {
        val hash = Translator.hash(text)
        db.aiCache().insert(AiCacheEntity(Translator.cacheKey(LOCAL_SCOPE_KEY, "rf-session", hash, base, model), LOCAL_SCOPE_KEY,
            Translator.TASK, base, model, hash, Translator.TARGET_LANGUAGE, "v1", result, true, 1))
    }
    private fun waitFor(condition: () -> Boolean) {
        val until = android.os.SystemClock.uptimeMillis() + 15000
        while (!condition() && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(30)
        assertTrue("reader state did not reach expected condition", condition())
    }
    private fun open(id: String = "rf-session") { main.runOnMainSync { vm.open(id) }; waitFor { !vm.state.value.loading } }
    private fun fetch() { main.runOnMainSync { vm.fetchFullText() }; waitFor { !vm.state.value.extracting } }

    private fun openReading() {
        main.runOnMainSync { vm.openAiReading() }
        waitFor { vm.state.value.aiReading.model == model }
    }

    @Test fun article_overview_uses_cache_keeps_old_result_on_failure_and_validates_source_jumps() {
        open(); openReading()
        main.runOnMainSync { vm.generateAiReading() }
        waitFor { !vm.state.value.aiReading.busy && vm.state.value.aiReading.answer != null }
        val answer = vm.state.value.aiReading.answer
        main.runOnMainSync { vm.jumpToAiSource("https://evil.example/") }
        assertNull(vm.state.value.paragraphJump)
        main.runOnMainSync { vm.jumpToAiSource("catleaf-b1") }
        assertEquals("catleaf-b1", vm.state.value.paragraphJump!!.id)
        assertFalse(vm.state.value.aiReading.open)
        openReading()
        waitFor { vm.state.value.aiReading.cached }
        assertEquals(1, readingCalls)
        invalidReading = true
        main.runOnMainSync { vm.generateAiReading(force = true) }
        waitFor { !vm.state.value.aiReading.busy && vm.state.value.aiReading.notice != null }
        assertEquals(answer, vm.state.value.aiReading.answer)
        assertEquals(2, readingCalls)
    }

    @Test fun selected_text_opens_without_request_and_cannot_reuse_another_article_body_or_scope() = runBlocking {
        open()
        val hash = vm.state.value.bodyHash
        main.runOnMainSync { vm.openAiReadingForSelection("旧正文", "another-article", hash) }
        assertFalse(vm.state.value.aiReading.open)
        main.runOnMainSync { vm.openAiReadingForSelection("旧正文", "rf-session", "old-render") }
        assertFalse(vm.state.value.aiReading.open)
        main.runOnMainSync { vm.openAiReadingForSelection("旧正文", "rf-session", hash) }
        waitFor { vm.state.value.aiReading.baseUrl == base }
        assertEquals(ArticleAssistant.Mode.Explain, vm.state.value.aiReading.mode)
        assertEquals("catleaf-b1", vm.state.value.aiReading.paragraphId)
        assertEquals("旧正文", vm.state.value.aiReading.selection)
        assertEquals(0, readingCalls)
        main.runOnMainSync { vm.selectAiReadingMode(ArticleAssistant.Mode.Question) }
        waitFor { vm.state.value.aiReading.baseUrl == base }
        main.runOnMainSync { vm.setAiQuestion("这是什么意思？"); vm.generateAiReading() }
        waitFor { !vm.state.value.aiReading.busy && vm.state.value.aiReading.answer != null }
        assertTrue(readingPrompt.contains("\"selectedText\":\"旧正文\""))
        assertEquals(1, readingCalls)
        main.runOnMainSync { vm.closeAiReading() }
        fetch()
        main.runOnMainSync { vm.openAiReadingForSelection("旧正文", "rf-session", hash) }
        assertFalse(vm.state.value.aiReading.open)
        settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
        main.runOnMainSync { vm.openAiReadingForSelection("完整正文", "rf-session", vm.state.value.bodyHash) }
        assertFalse(vm.state.value.aiReading.open)
    }

    @Test fun fulltext_replacement_discards_late_overview_and_uses_the_new_body_in_the_same_session() {
        readingGate = CountDownLatch(1)
        open(); openReading()
        main.runOnMainSync { vm.generateAiReading() }
        assertTrue(readingStarted.await(5, TimeUnit.SECONDS))
        fetch()
        readingGate!!.countDown()
        assertTrue(readingFinished.await(5, TimeUnit.SECONDS))
        assertFalse(vm.state.value.aiReading.open)
        assertNull(vm.state.value.aiReading.answer)
        openReading()
        main.runOnMainSync { vm.generateAiReading() }
        waitFor { !vm.state.value.aiReading.busy && vm.state.value.aiReading.answer != null }
        assertTrue(readingPrompt.contains("完整正文用于同会话测试"))
        assertFalse(readingPrompt.contains("旧正文"))
    }

    @Test fun changing_article_or_scope_never_applies_the_previous_reading_result() = runBlocking {
        readingGate = CountDownLatch(1)
        open(); openReading()
        main.runOnMainSync { vm.generateAiReading() }
        assertTrue(readingStarted.await(5, TimeUnit.SECONDS))
        seed("rf-other-article")
        open("rf-other-article")
        readingGate!!.countDown()
        assertTrue(readingFinished.await(5, TimeUnit.SECONDS))
        assertNull(vm.state.value.aiReading.answer)
        openReading()
        settings.setCurrentInstanceKey("rf-other-scope")
        settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
        waitFor { !vm.state.value.aiReading.open }
        assertNull(vm.state.value.aiReading.answer)
    }

    @Test fun summary_to_fulltext_enables_offline_save_in_same_session() = runBlocking {
        val old = db.articles().getContent("rf-session")!!
        db.articles().upsertContent(old.copy(content = null, contentSource = "summary"))
        open()
        assertTrue(vm.state.value.bookmarkOnly)
        fetch()
        assertFalse(vm.state.value.bookmarkOnly)
        assertEquals(ContentSource.Extracted, vm.state.value.source)
        assertTrue(vm.state.value.bodyPreview.contains("完整正文"))
    }

    @Test fun same_session_rematches_cache_and_translation_uses_new_text() = runBlocking {
        cache("旧正文", "旧缓存译文")
        val extracted = FullTextExtractor(null, PublicFetch(client = client { fullHtml })).extract("https://example.org/story", title) as FullTextExtractor.Outcome.Extracted
        val text = ArticleSanitizer.sanitize(extracted.html, "https://example.org/story").plainText
        cache(text, "新缓存译文")
        open(); assertEquals("旧缓存译文", vm.state.value.translation)
        main.runOnMainSync { vm.showTranslation() }
        fetch()
        val state = vm.state.value
        assertEquals(text.length, state.translatableChars)
        assertEquals(text.take(120), state.bodyPreview)
        assertEquals("新缓存译文", state.translation)
        assertEquals("https://example.org/cover.png", state.coverUrl)
        assertEquals(state.bodyHtml, db.articles().getContent("rf-session")!!.content)
        main.runOnMainSync { vm.translate(force = true) }
        waitFor { !vm.state.value.translating }
        assertTrue(prompt.contains("完整正文")); assertFalse(prompt.contains("旧正文"))
        open(); assertEquals(state.coverUrl, vm.state.value.coverUrl)
    }

    @Test fun late_translation_cannot_replace_new_body_and_mismatched_cache_is_cleared() = runBlocking {
        cache("旧正文", "旧缓存译文")
        open(); main.runOnMainSync { vm.showTranslation() }
        translationGate = CountDownLatch(1)
        main.runOnMainSync { vm.translate(force = true) }
        assertTrue(translationStarted.await(5, TimeUnit.SECONDS))
        fetch(); assertNull(vm.state.value.translation); assertFalse(vm.state.value.showTranslation)
        translationGate!!.countDown(); assertTrue(translationFinished.await(5, TimeUnit.SECONDS))
        Thread.sleep(300); main.waitForIdleSync()
        assertNull(vm.state.value.translation); assertEquals(ContentSource.Extracted, vm.state.value.source)
    }

    @Test fun disk_failure_preserves_all_current_body_and_translation_state() = runBlocking {
        cache("旧正文", "旧缓存译文"); open()
        val before = vm.state.value
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER rf_fail BEFORE INSERT ON article_content BEGIN SELECT RAISE(ABORT, 'rf simulated disk failure'); END")
        fetch()
        val after = vm.state.value
        assertEquals(before.bodyHtml, after.bodyHtml); assertEquals(before.bodyHash, after.bodyHash)
        assertEquals(before.translatableChars, after.translatableChars); assertEquals(before.translation, after.translation)
        assertEquals(oldHtml, db.articles().getContent("rf-session")!!.content)
        assertNotNull(after.notice)
    }

    @Test fun bilingual_cache_and_failed_retranslation_keep_original_and_verified_mapping() = runBlocking {
        open()
        main.runOnMainSync { vm.translate() }
        waitFor { !vm.state.value.translating }
        val before = vm.state.value
        assertEquals("本地假译文\n\n本地假译文", before.translation)
        assertNotNull(before.translationSegments)
        assertTrue(before.showTranslation)
        assertEquals(oldHtml, before.bodyHtml)
        val article = BilingualArticle.fromHtml(before.bodyHtml!!, before.title)
        assertEquals(article.blocks.map { it.id }.toSet(), before.translationSegments!!.keys)
        invalidTranslation = true
        main.runOnMainSync { vm.translate(force = true) }
        waitFor { !vm.state.value.translating }
        assertEquals(before.translationSegments, vm.state.value.translationSegments)
        assertEquals(before.translation, vm.state.value.translation)
        assertTrue(vm.state.value.showTranslation)
        assertNotNull(vm.state.value.notice)
        open()
        assertEquals(before.translationSegments, vm.state.value.translationSegments)
        assertTrue(vm.state.value.translationCached)
        main.runOnMainSync { vm.showOriginal(); vm.showTranslation() }
        assertEquals(oldHtml, db.articles().getContent("rf-session")!!.content)
    }

    @Test fun late_bilingual_translation_is_ignored_after_article_or_scope_change() = runBlocking {
        for (switchScope in listOf(false, true)) {
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            db.aiCache().clearInstance(LOCAL_SCOPE_KEY)
            open()
            translationGate = CountDownLatch(1)
            translationStarted = CountDownLatch(1)
            main.runOnMainSync { vm.translate(force = true) }
            assertTrue(translationStarted.await(5, TimeUnit.SECONDS))
            if (switchScope) { settings.setCurrentInstanceKey("rf-other-scope"); settings.setActiveScopeKind(SCOPE_KIND_INSTANCE) }
            else { seed("rf-other"); open("rf-other") }
            translationGate!!.countDown(); Thread.sleep(500); main.waitForIdleSync()
            assertNull(vm.state.value.translationSegments)
            if (!switchScope) assertNull(vm.state.value.translation)
        }
    }

    @Test fun late_extraction_is_ignored_after_article_or_scope_changes() = runBlocking {
        for (switchScope in listOf(false, true)) {
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            open(); extractGate = CountDownLatch(1)
            main.runOnMainSync { vm.fetchFullText() }
            waitFor { vm.state.value.extracting }; Thread.sleep(200)
            if (switchScope) { settings.setCurrentInstanceKey("rf-other-scope"); settings.setActiveScopeKind(SCOPE_KIND_INSTANCE) }
            else { seed("rf-other"); open("rf-other") }
            extractGate!!.countDown(); Thread.sleep(500); main.waitForIdleSync()
            assertEquals(oldHtml, db.articles().getContent("rf-session")!!.content)
            if (!switchScope) assertEquals(ContentSource.Rss, vm.state.value.source)
        }
    }
}
