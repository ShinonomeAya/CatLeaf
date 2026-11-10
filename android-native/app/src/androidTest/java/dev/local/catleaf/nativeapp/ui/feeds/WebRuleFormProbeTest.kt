package dev.local.catleaf.nativeapp.ui.feeds

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.article.WebsiteRules
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebRuleFormProbeTest {
    private lateinit var model: FeedManagerViewModel
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as Application
    private val rules = WebsiteRules(items = "article", title = "h2", link = "a")
    private val html = "<article><h2>Fixture story</h2><a href=/story>Read</a></article>"
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(40)
        assertTrue(condition())
    }
    private fun response(request: okhttp3.Request) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").body(html.toResponseBody()).build()

    @Test fun previewThenSaveUsesProductionFormRegistryAndMergeWithoutFetchingEveryArticle() = runBlocking {
        val settings = SettingsStore(app)
        val previous = settings.current()
        settings.setActiveScopeKind("local")
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val fetch = PublicFetch(OkHttpClient.Builder().addInterceptor { chain -> requests += chain.request().url.toString(); response(chain.request()) }.build())
        try {
            instrument.runOnMainSync { model = FeedManagerViewModel(app, fetch); model.refresh() }
            waitFor { model.state.value.localOnly }
            val url = "https://example.com/web-rule-fixture-${System.nanoTime()}"
            instrument.runOnMainSync {
                model.openAddForm(); model.onUrlChange(url); model.onSourceTypeChange("html"); model.onRulesChange(rules)
                assertFalse(model.form.value!!.canSubmit)
                model.previewRules()
            }
            waitFor { model.form.value?.canSubmit == true }
            instrument.runOnMainSync { model.submitForm() }
            waitFor { model.addResult.value != null }
            val id = model.addResult.value!!.feedId
            val db = CatLeafDatabase.get(app)
            val row = db.feeds().list(dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY).single { it.feedId == id }
            assertEquals("html", row.sourceType)
            assertEquals(rules, WebsiteRules.fromJson(row.ruleJson!!))
            val article = db.articles().listByInstance(row.instanceKey).single { it.feedId == id }
            assertEquals("Fixture story", article.title)
            assertTrue(requests.isNotEmpty())
            assertTrue("Preview and verification only request the configured list page", requests.all { it == url })
        } finally {
            if (::model.isInitialized) instrument.runOnMainSync { model.viewModelScope.cancel() }
            settings.setActiveScopeKind(previous.activeScopeKind)
        }
    }

    @Test fun closingAnInFlightPreviewCannotFillOrUnlockANewForm() = runBlocking {
        val settings = SettingsStore(app)
        val previous = settings.current()
        settings.setActiveScopeKind("local")
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val fetch = PublicFetch(OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            try { gate.await(10, TimeUnit.SECONDS); response(chain.request()) } finally { stopped.countDown() }
        }.build())
        try {
            instrument.runOnMainSync { model = FeedManagerViewModel(app, fetch); model.refresh() }
            waitFor { model.state.value.localOnly }
            instrument.runOnMainSync {
                model.openAddForm(); model.onUrlChange("https://example.com/old"); model.onSourceTypeChange("html"); model.onRulesChange(rules); model.previewRules()
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            instrument.runOnMainSync { model.closeForm(); model.openAddForm(); model.onUrlChange("https://example.com/new") }
            gate.countDown()
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            instrument.waitForIdleSync()
            assertEquals("https://example.com/new", model.form.value!!.url)
            assertNull(model.form.value!!.previewMessage)
            assertNull(model.form.value!!.previewedKey)
        } finally {
            gate.countDown()
            if (::model.isInitialized) instrument.runOnMainSync { model.viewModelScope.cancel() }
            settings.setActiveScopeKind(previous.activeScopeKind)
        }
    }
}
