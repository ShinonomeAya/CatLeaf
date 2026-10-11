package dev.local.catleaf.nativeapp.ui.feeds

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.FeedStore
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.local.InstanceEntity
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FeedManagerRetryFeedbackTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)
    private val credentials = CredentialStore(app)

    @Test fun failed_local_retry_keeps_its_reason_after_feed_refresh() = runBlocking {
        val previous = settings.current()
        val feedId = "retry-feedback-${UUID.randomUUID()}"
        val feed = FeedEntity(
            instanceKey = LOCAL_SCOPE_KEY,
            feedId = feedId,
            url = "https://missing-${UUID.randomUUID()}.example.invalid/feed.xml",
            title = "隔离失败源",
            category = "G7",
            status = FeedStore.STATUS_FAILED,
            lastSuccessAt = null,
        )
        try {
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            settings.setCurrentInstanceKey(null)
            db.feeds().upsert(feed)

            val viewModel = FeedManagerViewModel(app)
            viewModel.refresh()
            val loaded = withTimeout(5_000) { viewModel.state.first { it.feeds.any { row -> row.feedId == feedId } } }
            viewModel.retry(loaded.feeds.single { it.feedId == feedId })

            val result = withTimeout(20_000) {
                viewModel.state.first { it.busyFeedId == null && it.errorMessage?.contains("验证失败") == true }
            }
            assertNotNull(result.errorMessage)
            assertEquals(FeedStore.STATUS_FAILED, result.feeds.single { it.feedId == feedId }.status)
        } finally {
            db.feeds().delete(LOCAL_SCOPE_KEY, feedId)
            settings.setCurrentInstanceKey(previous.currentInstanceKey)
            settings.setActiveScopeKind(previous.activeScopeKind)
        }
    }

    /** 原型 34：新增后进结果页；重新验证只对同一条再抓取，不新增源（页面说明 §P03-34） */
    @Test fun local_add_shows_result_and_retry_does_not_add_a_duplicate() = runBlocking {
        val previous = settings.current()
        val url = "https://add-result-${UUID.randomUUID()}.example.invalid/feed.xml"
        try {
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            settings.setCurrentInstanceKey(null)
            val before = db.feeds().list(LOCAL_SCOPE_KEY).size

            val viewModel = FeedManagerViewModel(app)
            viewModel.refresh()
            viewModel.openAddForm()
            viewModel.onUrlChange(url)
            val firstResult = async(Dispatchers.Unconfined) {
                withTimeout(20_000) { viewModel.addResult.first { it != null && !it.retrying } }!!.also {
                    assertEquals(null, viewModel.form.value)
                }
            }
            viewModel.submitForm()

            val first = firstResult.await()
            // .invalid 必然抓不到：在线时是验证失败，离线时是联网后验证；都不能报成已验证
            assertTrue(first.outcome != FeedAddOutcome.Verified)
            assertNotNull(first.reason)
            assertEquals(before + 1, db.feeds().list(LOCAL_SCOPE_KEY).size)

            viewModel.retryAdded()
            withTimeout(5_000) { viewModel.addResult.first { it?.retrying == true } }
            val again = withTimeout(20_000) { viewModel.addResult.first { it != null && !it.retrying } }!!
            assertEquals(first.feedId, again.feedId)
            assertTrue(again.outcome != FeedAddOutcome.Verified)
            assertEquals(before + 1, db.feeds().list(LOCAL_SCOPE_KEY).size)
            assertEquals(FeedStore.STATUS_FAILED, db.feeds().list(LOCAL_SCOPE_KEY).single { it.feedId == first.feedId }.status)

            viewModel.closeAddResult()
            assertEquals(null, viewModel.addResult.value)
        } finally {
            db.feeds().list(LOCAL_SCOPE_KEY).filter { it.url == url }.forEach { db.feeds().delete(LOCAL_SCOPE_KEY, it.feedId) }
            settings.setCurrentInstanceKey(previous.currentInstanceKey)
            settings.setActiveScopeKind(previous.activeScopeKind)
        }
    }

    @Test fun failed_instance_retry_keeps_its_reason_when_followup_refresh_fails() = runBlocking {
        val previous = settings.current()
        val token = UUID.randomUUID().toString().replace("-", "")
        val instanceKey = "retry_feedback_$token"
        val feedId = "retry_feed_$token"
        val credentialName = "instance_secret_$instanceKey"
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""[{"id":"$feedId","url":"https://source.example.invalid/feed.xml","category":"G7","customTitle":"隔离失败源"}]"""),
            )
            server.enqueue(MockResponse().setResponseCode(503).setBody("验证失败"))
            server.enqueue(
                MockResponse().setResponseCode(503).setBody("刷新也失败")
                    .setBodyDelay(300, TimeUnit.MILLISECONDS),
            )
            db.instances().upsert(InstanceEntity(instanceKey, server.url("").toString().trimEnd('/'), instanceKey, 1L))
            credentials.put(credentialName, "FAKE-G7-RETRY-SECRET")
            db.feeds().upsert(
                FeedEntity(instanceKey, feedId, "https://source.example.invalid/feed.xml", "隔离失败源", "G7", FeedStore.STATUS_FAILED, null),
            )
            settings.setCurrentInstanceKey(instanceKey)
            settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)

            val viewModel = FeedManagerViewModel(app)
            viewModel.refresh()
            val loaded = withTimeout(5_000) { viewModel.state.first { it.feeds.any { row -> row.feedId == feedId } } }
            server.takeRequest(5, TimeUnit.SECONDS) ?: error("initial remote feed list was not requested")

            viewModel.retry(loaded.feeds.single { it.feedId == feedId })
            val verifyRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("remote retry was not requested")
            assertEquals("/api/feed?id=$feedId", verifyRequest.path)
            val retryFailure = withTimeout(5_000) {
                viewModel.state.first { it.busyFeedId == null && it.errorMessage?.contains("验证失败") == true }
            }
            val refreshRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("follow-up feed refresh was not requested")
            assertEquals("/api/feeds/list/admin", refreshRequest.path)
            val result = withTimeout(5_000) {
                viewModel.state.first { !it.loading && it.errorMessage == retryFailure.errorMessage }
            }
            assertTrue(result.errorMessage!!.contains("隔离失败源"))
            assertEquals(FeedStore.STATUS_FAILED, result.feeds.single { it.feedId == feedId }.status)
        } finally {
            settings.setCurrentInstanceKey(previous.currentInstanceKey)
            settings.setActiveScopeKind(previous.activeScopeKind)
            credentials.delete(credentialName)
            db.feeds().deleteAll(instanceKey)
            db.openHelper.writableDatabase.execSQL("DELETE FROM instances WHERE instanceKey = ?", arrayOf(instanceKey))
            server.shutdown()
        }
    }
}
