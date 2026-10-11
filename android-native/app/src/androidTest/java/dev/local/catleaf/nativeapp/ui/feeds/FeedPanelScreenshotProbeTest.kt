package dev.local.catleaf.nativeapp.ui.feeds

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VR3 截图用（原型 08 源面板 / 09 订阅管理 / 13 日摘要未配置 AI）：只在隔离模拟器上显式运行。
 * phase=seed 在本机范围写入 36 个 vr3- 前缀的分组源（.invalid 地址，含失败源、长名、上次成功时间），
 * 当前源设为第 33 个，并临时清空 AI 地址/模型（不碰 Keystore 密文）；原 settings 记入探针私有文件。
 * 用本机范围是因为实例范围需要真实凭据才显示订阅管理列表；不写任何密钥。
 * phase=cleanup 只删 vr3- 前缀的行并恢复原 settings。
 */
@RunWith(AndroidJUnit4::class)
@ManualProbe
class FeedPanelScreenshotProbeTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)
    private val memo = app.getSharedPreferences("vr3-feed-probe", Context.MODE_PRIVATE)

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
                .putString("feed", prev.lastFeedId)
                .putString("filter", prev.lastFilter)
                .putString("anchor", prev.lastScrollAnchor)
                .putString("aiBase", prev.aiBaseUrl)
                .putString("aiModel", prev.aiModel)
                .commit()
        }
        val feeds = feeds()
        db.feeds().upsertAll(feeds)
        // 当前源与两个邻近源各给几篇文章，阅读页与日摘要有真实内容
        listOf(CURRENT, CURRENT - 1, 0).forEach { f ->
            repeat(4) { i ->
                db.articles().upsertArticle(ArticleEntity(
                    localId = "vr3-a-$f-$i", instanceKey = LOCAL_SCOPE_KEY, feedId = feeds[f].feedId, guid = "vr3-$f-$i",
                    link = "https://example.invalid/a/$f/$i", title = "隔离文章 ${f + 1}-${i + 1}",
                    publishedAt = "2026-09-2${8 - i % 2}T0${i + 1}:00:00Z",
                    feedTitle = feeds[f].title, feedUrl = feeds[f].url, stateUpdatedAt = 1,
                ))
                // 只给短摘要（无正文）：日摘要候选需要有效输入（契约 5.3），CASCADE 随文章清理
                db.articles().upsertContent(ArticleContentEntity(
                    localId = "vr3-a-$f-$i", content = null,
                    summary = "<p>隔离摘要 ${f + 1}-${i + 1}：用于截图的本地示例段落，不来自任何真实订阅。</p>",
                    thumbnail = null, contentSource = "rss", contentHash = "vr3-$f-$i", lastAccessedAt = now,
                ))
            }
        }
        settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
        settings.setLastReadingPosition(feeds[CURRENT].feedId, null, null)
        settings.setAiConfig(null, null)
    }

    private suspend fun cleanup() {
        val w = db.openHelper.writableDatabase
        w.execSQL("DELETE FROM articles WHERE instanceKey = ? AND feedId LIKE 'vr3-feed-%'", arrayOf(LOCAL_SCOPE_KEY))
        w.execSQL("DELETE FROM feeds WHERE instanceKey = ? AND feedId LIKE 'vr3-feed-%'", arrayOf(LOCAL_SCOPE_KEY))
        if (memo.getBoolean("seeded", false)) {
            settings.setCurrentInstanceKey(memo.getString("key", null))
            settings.setActiveScopeKind(memo.getString("kind", null))
            settings.setLastReadingPosition(memo.getString("feed", null), memo.getString("filter", null), memo.getString("anchor", null))
            settings.setAiConfig(memo.getString("aiBase", null), memo.getString("aiModel", null))
            memo.edit().clear().commit()
        }
    }

    private val now = System.currentTimeMillis()

    private fun feeds(): List<FeedEntity> = (0 until COUNT).map { i ->
        // 标题带两位序号，组内按标题排序可预期；当前源在面板中的位置由面板自己计算
        val category = CATEGORIES[i * CATEGORIES.size / COUNT]
        val title = when (i) {
            LONG -> "%02d 隔离源 · 一个名字特别长、在窄屏和大字号下需要换行才能完整显示的订阅".format(i + 1)
            else -> "%02d 隔离源 · %s".format(i + 1, category)
        }
        FeedEntity(
            instanceKey = LOCAL_SCOPE_KEY,
            feedId = "vr3-feed-%02d".format(i),
            url = "https://example.invalid/feed-$i.xml",
            title = title,
            category = category,
            status = if (i == FAILED) "failed" else "ok",
            lastSuccessAt = when {
                i == CURRENT -> now // 打开阅读页不触发 15 分钟前台刷新
                i == FAILED || i % 5 == 4 -> null
                else -> now - (COUNT - i) * 3_600_000L
            },
        )
    }

    private companion object {
        const val COUNT = 36
        const val CURRENT = 32
        const val FAILED = 2
        const val LONG = 5
        val CATEGORIES = listOf("技术", "设计", "新闻", "生活")
    }
}
