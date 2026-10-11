package dev.local.catleaf.nativeapp.ui.settings

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.importexport.StateFeed
import dev.local.catleaf.nativeapp.data.importexport.StateFile
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.InstanceEntity
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateRestoreRetryTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)
    private val credentials = CredentialStore(app)
    private val key = "isolated_restore_retry"
    private val otherKey = "isolated_restore_other"
    private val file = app.cacheDir.resolve("isolated-restore-retry.json")
    private val server = MockWebServer()
    private lateinit var previous: SettingsStore.Snapshot

    @Before fun setUp() = runBlocking {
        previous = settings.current()
        settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
    }

    @After fun tearDown() = runBlocking {
        settings.setCurrentInstanceKey(previous.currentInstanceKey)
        settings.setActiveScopeKind(previous.activeScopeKind)
        credentials.delete("instance_secret_$key")
        credentials.delete("instance_secret_$otherKey")
        db.feeds().deleteAll(key)
        db.feeds().deleteAll(otherKey)
        db.openHelper.writableDatabase.execSQL("DELETE FROM instances WHERE instanceKey IN (?, ?)", arrayOf(key, otherKey))
        file.delete()
        server.shutdown()
    }

    @Test fun result_retry_restarts_failed_remote_restore() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/feeds/list/admin") == true -> MockResponse().setBody("[]")
                request.path?.startsWith("/api/feeds/add") == true -> MockResponse().setResponseCode(500)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        db.instances().upsert(InstanceEntity(key, server.url("").toString().trimEnd('/'), key, 1L))
        credentials.put("instance_secret_$key", "FAKE-RESTORE-SECRET")
        settings.setCurrentInstanceKey(key)
        file.writeBytes(StateFile.write(
            "instance", "https://source.example.org", 1L,
            listOf(StateFeed("https://feed.example.org/rss", "Test feed", "")), emptyList(),
        ))
        val vm = StateBackupViewModel(app)
        vm.openFile(Uri.fromFile(file), file.name)
        withTimeout(5_000) { vm.state.first { it.phase is RestorePhase.Preview } }
        vm.setMigrateConfirmed(true)
        vm.startRestore()
        val done = withTimeout(5_000) { vm.state.first { it.phase is RestorePhase.Done } }.phase as RestorePhase.Done
        assertTrue(done.remote?.failed == 1)
        val before = server.requestCount

        vm.startRestore() // 结果页“重试”使用的同一个回调
        withTimeout(5_000) { while (server.requestCount == before) delay(20) }
    }

    @Test fun old_preview_cannot_restore_into_new_instance() = runBlocking {
        val otherServer = MockWebServer().apply {
            enqueue(MockResponse().setBody("[]"))
            start()
        }
        try {
            server.start()
            db.instances().upsert(InstanceEntity(key, server.url("").toString().trimEnd('/'), key, 1L))
            db.instances().upsert(InstanceEntity(otherKey, otherServer.url("").toString().trimEnd('/'), otherKey, 1L))
            credentials.put("instance_secret_$key", "FAKE-OLD")
            credentials.put("instance_secret_$otherKey", "FAKE-NEW")
            settings.setCurrentInstanceKey(key)
            file.writeBytes(StateFile.write("instance", "https://source.example.org", 1L, emptyList(), emptyList()))
            val vm = StateBackupViewModel(app)
            vm.openFile(Uri.fromFile(file), file.name)
            withTimeout(5_000) { vm.state.first { it.phase is RestorePhase.Preview } }
            vm.setMigrateConfirmed(true)

            settings.setCurrentInstanceKey(otherKey)
            vm.startRestore()
            withTimeout(5_000) { vm.state.first { it.notice == "使用范围已切换，请重新选择文件" } }
            assertEquals(0, otherServer.requestCount)
        } finally {
            otherServer.shutdown()
        }
    }

    @Test fun instance_preview_cannot_restore_into_local_after_scope_switch() = runBlocking {
        val url = "https://feed.example.org/isolated-scope-switch-rss"
        try {
            server.start()
            db.instances().upsert(InstanceEntity(key, server.url("").toString().trimEnd('/'), key, 1L))
            credentials.put("instance_secret_$key", "FAKE-SCOPE-SECRET")
            settings.setCurrentInstanceKey(key)
            file.writeBytes(StateFile.write(
                "instance", "https://source.example.org", 1L,
                listOf(StateFeed(url, "Scope feed", "")), emptyList(),
            ))
            val vm = StateBackupViewModel(app)
            vm.openFile(Uri.fromFile(file), file.name)
            withTimeout(5_000) { vm.state.first { it.phase is RestorePhase.Preview } }
            vm.setMigrateConfirmed(true)

            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            vm.startRestore()
            withTimeout(5_000) { vm.state.first { it.notice == "使用范围已切换，请重新选择文件" } }
            assertTrue(db.feeds().list(LOCAL_SCOPE_KEY).none { it.url == url })
        } finally {
            db.feeds().list(LOCAL_SCOPE_KEY).filter { it.url == url }.forEach {
                db.feeds().delete(LOCAL_SCOPE_KEY, it.feedId)
            }
        }
    }
}
