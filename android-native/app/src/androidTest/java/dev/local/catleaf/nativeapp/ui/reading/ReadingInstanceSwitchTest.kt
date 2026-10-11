package dev.local.catleaf.nativeapp.ui.reading

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.local.InstanceEntity
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.ui.feeds.FeedManagerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReadingInstanceSwitchTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)
    private val credentials = CredentialStore(app)
    private val a = "isolated_switch_a"
    private val b = "isolated_switch_b"

    @Before fun setUp() = runBlocking {
        settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
    }

    @After fun tearDown() = runBlocking {
        settings.clearScopeForTest()
        settings.setCurrentInstanceKey(null)
        credentials.delete("instance_secret_$a")
        credentials.delete("instance_secret_$b")
        db.feeds().deleteAll(a)
        db.feeds().deleteAll(b)
        db.openHelper.writableDatabase.execSQL("DELETE FROM instances WHERE instanceKey IN (?, ?)", arrayOf(a, b))
    }

    @Test fun switching_instance_replaces_cached_reading_repository() = runBlocking {
        for (key in listOf(a, b)) {
            db.instances().upsert(InstanceEntity(key, "http://127.0.0.1:1", key, 1L))
            db.feeds().upsertAll(listOf(FeedEntity(key, "feed-$key", "https://example.org/$key", key, "", "ok", null)))
            credentials.put("instance_secret_$key", "FAKE-$key")
        }
        val vm = ReadingViewModel(app)
        settings.setCurrentInstanceKey(a)
        vm.start()
        withTimeout(5_000) { vm.state.first { it.currentFeedId == "feed-$a" } }

        settings.setCurrentInstanceKey(b)
        vm.start()
        val switched = withTimeout(5_000) { vm.state.first { it.currentFeedId == "feed-$b" } }
        assertEquals(b, switched.currentFeed?.instanceKey)
    }

    @Test fun late_old_instance_response_cannot_replace_new_list() = runBlocking {
        val oldServer = MockWebServer().apply { start() }
        val newServer = MockWebServer().apply { start() }
        try {
            oldServer.enqueue(MockResponse().setBody(
                "<rss><channel><title>Old</title><item><title>Old article</title><guid>old</guid></item></channel></rss>",
            ).setBodyDelay(2, TimeUnit.SECONDS))
            newServer.enqueue(MockResponse().setResponseCode(500))
            for ((key, server) in listOf(a to oldServer, b to newServer)) {
                db.instances().upsert(InstanceEntity(key, server.url("").toString().trimEnd('/'), key, 1L))
                db.feeds().upsertAll(listOf(FeedEntity(key, "feed-$key", "https://example.org/$key", key, "", "ok", null)))
                credentials.put("instance_secret_$key", "FAKE-$key")
            }
            val vm = ReadingViewModel(app)
            settings.setCurrentInstanceKey(a)
            vm.start()
            withTimeout(5_000) { vm.state.first { it.currentFeedId == "feed-$a" } }
            oldServer.takeRequest(5, TimeUnit.SECONDS) ?: error("old refresh was not sent")

            settings.setCurrentInstanceKey(b)
            vm.start()
            withTimeout(5_000) { vm.state.first { it.currentFeedId == "feed-$b" } }
            delay(2_500)
            assertEquals(b, vm.state.value.currentFeed?.instanceKey)
            assertEquals("feed-$b", vm.state.value.currentFeedId)
        } finally {
            oldServer.shutdown()
            newServer.shutdown()
        }
    }

    @Test fun feed_manager_refresh_uses_new_instance_after_switch() = runBlocking {
        val oldServer = MockWebServer().apply { start() }
        val newServer = MockWebServer().apply { start() }
        try {
            for ((key, server) in listOf(a to oldServer, b to newServer)) {
                db.instances().upsert(InstanceEntity(key, server.url("").toString().trimEnd('/'), key, 1L))
                credentials.put("instance_secret_$key", "FAKE-$key")
                server.enqueue(MockResponse().setBody(
                    """[{"id":"feed-$key","url":"https://example.org/$key","category":"","customTitle":"$key"}]""",
                ))
            }
            val vm = FeedManagerViewModel(app)
            settings.setCurrentInstanceKey(a)
            vm.refresh()
            withTimeout(5_000) { vm.state.first { it.feeds.singleOrNull()?.instanceKey == a } }

            settings.setCurrentInstanceKey(b)
            vm.refresh()
            val switched = withTimeout(5_000) { vm.state.first { it.feeds.singleOrNull()?.instanceKey == b } }
            assertEquals("feed-$b", switched.feeds.single().feedId)
            assertNotNull(newServer.takeRequest(5, TimeUnit.SECONDS))

            credentials.put("instance_secret_$b", "FAKE-ROTATED")
            newServer.enqueue(MockResponse().setBody(
                """[{"id":"feed-$b","url":"https://example.org/$b","category":"","customTitle":"$b"}]""",
            ))
            vm.refresh()
            assertEquals("FAKE-ROTATED", newServer.takeRequest(5, TimeUnit.SECONDS)?.getHeader("x-admin-secret"))
        } finally {
            oldServer.shutdown()
            newServer.shutdown()
        }
    }
}
