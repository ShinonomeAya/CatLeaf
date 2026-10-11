package dev.local.catleaf.nativeapp.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.local.InstanceEntity
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VR2 截图用（原型 29 实例态 / 31 / 17）：只在隔离模拟器上显式运行。
 * phase=seed 写入不可解析的假实例（.invalid，无密钥、不联网）并切到实例范围，原 settings 记入探针私有文件；
 * phase=cleanup 删掉这些行（含经真实「复制到本机」写入本机范围的同地址行）并恢复原 settings。
 */
@RunWith(AndroidJUnit4::class)
@ManualProbe
class UsageScopeScreenshotProbeTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)
    private val memo = app.getSharedPreferences("vr2-usage-probe", Context.MODE_PRIVATE)

    @Test fun seedOrCleanup() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        check(phase == "seed" || phase == "cleanup") { "requires explicit seed/cleanup phase" }
        if (phase == "seed") seed() else cleanup()
    }

    private suspend fun seed() {
        if (!memo.getBoolean("seeded", false)) {
            val prev = settings.current()
            memo.edit()
                .putBoolean("seeded", true)
                .putString("key", prev.currentInstanceKey)
                .putString("kind", prev.activeScopeKind)
                .commit()
        }
        db.instances().upsert(InstanceEntity(INSTANCE, ORIGIN, "隔离实例", lastConnectedAt = 1_790_000_000_000))
        FEEDS.forEachIndexed { i, title ->
            db.feeds().upsert(FeedEntity(INSTANCE, "vr2-feed-$i", "https://example.invalid/feed-$i", title, "", "ok", null))
        }
        repeat(6) { i ->
            db.articles().upsertArticle(ArticleEntity(
                localId = "vr2-usage-$i", instanceKey = INSTANCE, feedId = "vr2-feed-${i % FEEDS.size}", guid = "vr2-$i",
                link = "https://example.invalid/a/$i", title = "隔离文章 $i", publishedAt = null,
                feedTitle = FEEDS[i % FEEDS.size], feedUrl = "https://example.invalid/feed-${i % FEEDS.size}",
                isRead = i % 2 == 0, isStarred = i == 1, starredAt = if (i == 1) 1L else null, stateUpdatedAt = 1,
            ))
        }
        // 已删源的孤立收藏
        db.articles().upsertArticle(ArticleEntity(
            localId = "vr2-usage-orphan", instanceKey = INSTANCE, feedId = "vr2-gone", guid = "vr2-orphan",
            link = "https://example.invalid/a/orphan", title = "隔离孤立收藏", publishedAt = null,
            feedTitle = "已删源", feedUrl = "https://example.invalid/gone",
            isStarred = true, starredAt = 2L, stateUpdatedAt = 1,
        ))
        settings.setCurrentInstanceKey(INSTANCE)
        settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
    }

    private suspend fun cleanup() {
        val w = db.openHelper.writableDatabase
        w.execSQL("DELETE FROM articles WHERE instanceKey = ?", arrayOf(INSTANCE))
        db.feeds().deleteAll(INSTANCE)
        w.execSQL("DELETE FROM instances WHERE instanceKey = ?", arrayOf(INSTANCE))
        // 经真实「复制到本机」UI 写入本机范围的行（地址都是本探针的 https://example.invalid/feed-N / gone）
        w.execSQL("DELETE FROM articles WHERE instanceKey = ? AND (feedUrl LIKE 'https://example.invalid/feed-%' OR feedUrl = 'https://example.invalid/gone')", arrayOf(LOCAL_SCOPE_KEY))
        w.execSQL("DELETE FROM feeds WHERE instanceKey = ? AND url LIKE 'https://example.invalid/feed-%'", arrayOf(LOCAL_SCOPE_KEY))
        if (memo.getBoolean("seeded", false)) {
            settings.setCurrentInstanceKey(memo.getString("key", null))
            settings.setActiveScopeKind(memo.getString("kind", null))
            memo.edit().clear().commit()
        }
    }

    private companion object {
        const val INSTANCE = "isolated-vr2-usage"
        const val ORIGIN = "https://catleaf-demo.example.invalid"
        val FEEDS = listOf("隔离源 · 技术", "隔离源 · 设计", "隔离源 · 新闻")
    }
}
