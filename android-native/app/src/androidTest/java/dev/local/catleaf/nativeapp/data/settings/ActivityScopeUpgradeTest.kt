package dev.local.catleaf.nativeapp.data.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.local.InstanceEntity
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.ui.reading.ReadingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

/**
 * L01 验收（契约 8.1）：旧版有数据覆盖升级保留原范围与引导已处理；全新安装默认本机；
 * 密钥暂不可用仍读缓存；切范围后迟到的旧范围回包不污染新界面、不写入新范围。
 */
@RunWith(AndroidJUnit4::class)
class ActivityScopeUpgradeTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)
    private val credentials = CredentialStore(app)
    private val oldKey = "isolated_scope_upgrade_old"

    @After fun tearDown() = runBlocking {
        settings.clearScopeForTest()
        settings.setCurrentInstanceKey(null)
        credentials.delete("instance_secret_$oldKey")
        db.feeds().deleteAll(oldKey)
        db.feeds().deleteAll(LOCAL_SCOPE_KEY)
        db.openHelper.writableDatabase.execSQL("DELETE FROM instances WHERE instanceKey = ?", arrayOf(oldKey))
    }

    @Test fun fresh_install_resolves_local_scope_and_leaves_onboarding_pending() = runBlocking {
        settings.clearScopeForTest()
        settings.setCurrentInstanceKey(null)
        val scope = ActivityScopeResolver(settings, db).resolve()

        assertEquals(ScopeKind.LOCAL, scope.kind)
        assertEquals(LOCAL_SCOPE_KEY, scope.key)
        // 迁移结果落盘，之后判定稳定
        assertEquals(SCOPE_KIND_LOCAL, settings.current().activeScopeKind)
        assertFalse(settings.current().onboardingDone)
        // 再次解析结果一致（不漂移）
        assertEquals(ScopeKind.LOCAL, ActivityScopeResolver(settings, db).resolve().kind)
    }

    @Test fun legacy_upgrade_keeps_instance_scope_and_marks_onboarding_done() = runBlocking {
        // 模拟旧版本安装：有实例指针 + 实例行 + 订阅镜像，DataStore 无范围字段
        settings.clearScopeForTest()
        settings.setCurrentInstanceKey(oldKey)
        db.instances().upsert(InstanceEntity(oldKey, "https://old.example.org", oldKey, 1L))
        db.feeds().upsertAll(listOf(FeedEntity(oldKey, "feed-old", "https://old.example.org/feed", "旧源", "", "ok", null)))

        val scope = ActivityScopeResolver(settings, db).resolve()

        assertEquals(ScopeKind.INSTANCE, scope.kind)
        assertEquals(oldKey, scope.key)
        assertTrue(scope.instanceReady)
        // 老用户：引导已处理，不重复新引导
        assertTrue(settings.current().onboardingDone)
        // 旧数据原样保留：键不重算、行不迁移
        assertEquals("feed-old", db.feeds().list(oldKey).single().feedId)
        assertNull(db.feeds().list(LOCAL_SCOPE_KEY).firstOrNull())
    }

    @Test fun instance_credential_unavailable_still_reads_cached_content() = runBlocking {
        settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
        settings.setCurrentInstanceKey(oldKey)
        db.instances().upsert(InstanceEntity(oldKey, "https://old.example.org", oldKey, 1L))
        db.feeds().upsertAll(listOf(FeedEntity(oldKey, "feed-old", "https://old.example.org/feed", "旧源", "", "ok", null)))
        // 不写凭据：Keystore 密文缺失（修复场景）

        val vm = ReadingViewModel(app)
        vm.start()
        val state = withTimeout(5_000) { vm.state.first { it.currentFeedId != null || it.notConnected } }

        // 仍读缓存（契约 8.1：不可因密钥暂不可读创建空范围覆盖）
        assertFalse(state.notConnected)
        assertEquals("feed-old", state.currentFeedId)
        assertTrue(state.feeds.isNotEmpty())
    }

    @Test fun late_response_after_switching_to_local_stays_out_of_new_scope() = runBlocking {
        val previous = settings.current()
        val slowServer = MockWebServer().apply { start() }
        try {
            settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
            settings.setCurrentInstanceKey(oldKey)
            db.instances().upsert(InstanceEntity(oldKey, slowServer.url("").toString().trimEnd('/'), oldKey, 1L))
            db.feeds().upsertAll(listOf(FeedEntity(oldKey, "feed-old", "https://old.example.org/feed", "旧源", "", "ok", null)))
            credentials.put("instance_secret_$oldKey", "FAKE")
            slowServer.enqueue(
                MockResponse().setBody(
                    "<rss><channel><title>Old</title><item><title>Old article</title><guid>old</guid></item></channel></rss>",
                ).setBodyDelay(2, TimeUnit.SECONDS),
            )

            // 只测迟到归属，固定单源选择，避免继承其他用例的“全部订阅”。
            settings.setLastReadingPosition("feed-old", null, null, oldKey, allFeeds = false)
            val vm = ReadingViewModel(app)
            vm.start()
            withTimeout(5_000) { vm.state.first { it.currentFeedId == "feed-old" } }
            slowServer.takeRequest(5, TimeUnit.SECONDS) ?: error("old refresh was not sent")

            // 切到本机范围（L05 的持久化开关；本机范围里有自己的源）
            settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
            db.feeds().upsertAll(listOf(FeedEntity(LOCAL_SCOPE_KEY, "feed-local", "https://local.example.org/feed", "本机源", "", "ok", null)))
            settings.setLastReadingPosition("feed-local", null, null, LOCAL_SCOPE_KEY, allFeeds = false)
            vm.start()
            val switched = withTimeout(5_000) { vm.state.first { it.currentFeedId == "feed-local" } }
            assertEquals(LOCAL_SCOPE_KEY, switched.currentFeed?.instanceKey)

            // 旧范围回包晚到：不更新新界面，也不写入本机范围
            delay(2_500)
            assertEquals("feed-local", vm.state.value.currentFeedId)
            assertEquals(LOCAL_SCOPE_KEY, vm.state.value.currentFeed?.instanceKey)
            assertTrue(db.articles().listByFeed(LOCAL_SCOPE_KEY, "feed-old", 10, 0).isEmpty())
        } finally {
            slowServer.shutdown()
            settings.setLastReadingPosition(previous.lastFeedId, previous.lastFilter, previous.lastScrollAnchor,
                previous.lastReadingScopeKey, previous.lastReadingAllFeeds, previous.lastReadingLoadedCount)
        }
    }
}
