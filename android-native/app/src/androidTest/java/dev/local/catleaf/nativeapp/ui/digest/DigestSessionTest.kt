package dev.local.catleaf.nativeapp.ui.digest

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.ai.DigestSummarizer
import dev.local.catleaf.nativeapp.data.ai.Translator
import dev.local.catleaf.nativeapp.data.local.AiCacheEntity
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real isolated Room queries only; no credentials, network requests or user data. */
@RunWith(AndroidJUnit4::class)
class DigestSessionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val settings = SettingsStore(app)
    private val main = InstrumentationRegistry.getInstrumentation()
    private val store = ViewModelStore()
    private lateinit var previous: SettingsStore.Snapshot
    private lateinit var db: CatLeafDatabase
    private lateinit var vm: DigestViewModel
    private lateinit var dayACacheKey: String
    private val blockCache = AtomicBoolean(false)
    private val cacheStarted = CountDownLatch(1)
    private val releaseCache = CountDownLatch(1)
    private val cacheReleased = CountDownLatch(1)
    private val dayA = "2026-10-14"
    private val dayB = "2026-10-15"
    private val base = "https://ai.invalid.test"
    private val model = "digest-session-isolated"

    @Before fun setup() = runBlocking {
        previous = settings.current()
        settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
        settings.setAiConfig(base, model)
        db = Room.inMemoryDatabaseBuilder(app, CatLeafDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("SELECT * FROM ai_cache WHERE cacheKey =") && blockCache.compareAndSet(true, false)) {
                    cacheStarted.countDown()
                    check(releaseCache.await(10, TimeUnit.SECONDS))
                    cacheReleased.countDown()
                }
            }, { it.run() })
            .build()
        db.feeds().upsert(FeedEntity(LOCAL_SCOPE_KEY, "feed", "https://feeds.example.org/rss", "测试源", "", "ok", null))
        seed("day-a", dayA)
        seed("day-b", dayB)
        cacheDayA()
        main.runOnMainSync { vm = DigestViewModel(app, db); store.put("digest", vm) }
    }

    @After fun cleanup() = runBlocking {
        releaseCache.countDown()
        main.runOnMainSync { store.clear() }
        settings.setAiConfig(previous.aiBaseUrl, previous.aiModel)
        settings.setCurrentInstanceKey(previous.currentInstanceKey)
        settings.setActiveScopeKind(previous.activeScopeKind)
        db.close()
    }

    @Test fun failed_date_read_clears_previous_candidates_and_cannot_generate_under_the_new_date() {
        main.runOnMainSync { vm.open("feed", dayA) }
        waitFor { vm.state.value.result != null }
        assertEquals(listOf("day-a"), vm.state.value.items.map { it.localId })
        db.openHelper.writableDatabase.execSQL("DROP TABLE articles")
        val fault = runCatching { db.openHelper.readableDatabase.query("SELECT 1 FROM articles").use { it.moveToFirst() } }
        assertTrue("isolated table fault was not applied", fault.exceptionOrNull() is SQLiteException)
        main.runOnMainSync { vm.setDate(dayB) }
        waitFor { !vm.state.value.loading && vm.state.value.notice?.contains("无法读取") == true }
        assertEquals(dayB, vm.state.value.localDate)
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(0, vm.state.value.selectedCount)
        assertEquals(0, vm.state.value.selectedChars)
        assertNull(vm.state.value.result)
        assertFalse(vm.state.value.canGenerate)
        main.runOnMainSync { vm.generate() }
        assertFalse(vm.state.value.generating)
        assertEquals("没有勾选任何文章，不能提交", vm.state.value.notice)
    }

    @Test fun cache_read_error_keeps_loaded_candidates_and_reports_no_supplier_request() {
        db.openHelper.writableDatabase.execSQL("DROP TABLE ai_cache")
        main.runOnMainSync { vm.open("feed", dayA) }
        waitFor { !vm.state.value.loading && vm.state.value.notice?.contains("无法读取AI缓存") == true }
        assertEquals(listOf("day-a"), vm.state.value.items.map { it.localId })
        assertEquals(1, vm.state.value.selectedCount)
        assertNull(vm.state.value.result)
        assertFalse(vm.state.value.generating)
        assertEquals("无法读取AI缓存，本次未请求供应商", vm.state.value.notice)
    }

    @Test fun late_cached_result_never_overrides_another_date() {
        beginHeldCache()
        main.runOnMainSync { vm.setDate(dayB) }
        waitFor { vm.state.value.localDate == dayB && !vm.state.value.loading && vm.state.value.items.any { it.localId == "day-b" } }
        releaseAndDrain()
        assertEquals(dayB, vm.state.value.localDate)
        assertEquals(listOf("day-b"), vm.state.value.items.map { it.localId })
        assertNull(vm.state.value.result)
        assertEquals(DigestStep.Scope, vm.state.value.step)
    }

    @Test fun clearing_selection_invalidates_the_pending_full_selection_cache_and_keeps_the_saved_result() = runBlocking {
        beginHeldCache()
        main.runOnMainSync { vm.clearSelection() }
        assertEquals(0, vm.state.value.selectedCount)
        assertFalse(vm.state.value.canGenerate)
        releaseAndDrain()
        assertNull(vm.state.value.result)
        assertEquals(DigestStep.Scope, vm.state.value.step)
        assertEquals(0, vm.state.value.selectedCount)
        assertTrue(vm.state.value.items.none { it.selected })
        main.runOnMainSync { vm.toggleSelected("day-a") }
        assertEquals(1, vm.state.value.selectedCount)
        assertTrue(vm.state.value.items.single().selected)
        assertTrue(vm.state.value.canGenerate)
        val saved = db.aiCache().get(dayACacheKey)
        assertTrue(saved?.complete == true)
        assertEquals("旧日摘要", DigestSummarizer.parse(saved!!.result).title)
    }

    @Test fun late_cached_result_never_restores_the_previous_scope() = runBlocking {
        beginHeldCache()
        settings.setCurrentInstanceKey("digest-other-scope")
        settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
        waitFor { vm.state.value.notice?.contains("使用范围已切换") == true }
        releaseAndDrain()
        assertNull(vm.state.value.result)
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(0, vm.state.value.selectedCount)
        assertFalse(vm.state.value.canGenerate)
        assertTrue(vm.state.value.notice?.contains("使用范围已切换") == true)
    }

    private suspend fun seed(id: String, day: String) {
        val published = LocalDate.parse(day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toString()
        db.articles().upsertArticle(ArticleEntity(id, LOCAL_SCOPE_KEY, "feed", id, null, id, published, "测试源",
            "https://feeds.example.org/rss", stateUpdatedAt = 1))
        db.articles().upsertContent(ArticleContentEntity(id, "<p>$id 的本地正文。</p>", null, null, "rss", id, lastAccessedAt = 1))
    }

    private suspend fun cacheDayA() {
        val sources = listOf(DigestSummarizer.Source(1, "day-a", "day-a", null))
        val request = DigestSummarizer.Request(LOCAL_SCOPE_KEY, "feed", "测试源", dayA, TimeZone.getDefault().id,
            sources, listOf("day-a 的本地正文。"), base, model, "")
        dayACacheKey = DigestSummarizer.cacheKey(request)
        val digest = DigestSummarizer.Digest("旧日摘要", "旧日概览", listOf(DigestSummarizer.Point("旧日重点", listOf(1), "测试主题")))
        db.aiCache().insert(AiCacheEntity(dayACacheKey, LOCAL_SCOPE_KEY, DigestSummarizer.TASK,
            base, model, "test", Translator.TARGET_LANGUAGE, DigestSummarizer.PROMPT_VERSION,
            DigestSummarizer.serialize(digest, sources), true, 1))
    }

    private fun beginHeldCache() {
        blockCache.set(true)
        main.runOnMainSync { vm.open("feed", dayA) }
        assertTrue("cache query did not start", cacheStarted.await(5, TimeUnit.SECONDS))
    }

    private fun releaseAndDrain() {
        releaseCache.countDown()
        assertTrue(cacheReleased.await(5, TimeUnit.SECONDS))
        Thread.sleep(250)
        main.waitForIdleSync()
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = android.os.SystemClock.uptimeMillis() + 10_000
        while (!condition() && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(20)
        assertTrue("digest state did not reach expected condition", condition())
    }
}
