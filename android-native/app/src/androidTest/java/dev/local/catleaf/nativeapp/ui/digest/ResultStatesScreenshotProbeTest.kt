package dev.local.catleaf.nativeapp.ui.digest

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.ai.DigestSummarizer
import dev.local.catleaf.nativeapp.data.ai.Translator
import dev.local.catleaf.nativeapp.data.article.ArticleSanitizer
import dev.local.catleaf.nativeapp.data.local.AiCacheEntity
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
import java.util.TimeZone

/**
 * VR5 截图用（UIR18：04 摘要结果、06 收藏三态、21 离线阅读）：只在隔离模拟器上显式运行（默认被 ManualProbe 排除）。
 * phase=seed 在本机范围写入 vr5- 前缀的 1 个源、4 篇同日文章（正文/仅摘要/带 .invalid 图片的正文），
 * 按 DigestViewModel 同一路径（净化后纯文本、同一候选规则）算出缓存键，写入一份**合成**的完整日摘要缓存——
 * 不是 AI 生成的，不调用任何 AI；AI 地址/模型设为 .invalid 占位（缓存键需要），**不写 Key**。
 * 收藏三态：已存正文、仅书签（只有摘要）、原订阅已删除（feedId 不在源表）。
 * 原 settings 记入探针私有文件；phase=cleanup 删 vr5- 行、这份缓存，以及经真实 OPML 导入/状态恢复 UI
 * 写入的 https://example.invalid/vr5… 源与文章，并恢复原 settings。
 */
@RunWith(AndroidJUnit4::class)
@ManualProbe
class ResultStatesScreenshotProbeTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)
    private val memo = app.getSharedPreferences("vr5-result-probe", Context.MODE_PRIVATE)
    private val now = System.currentTimeMillis()

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
        db.feeds().upsert(FeedEntity(
            instanceKey = LOCAL_SCOPE_KEY, feedId = FEED_ID, url = "https://example.invalid/vr5.xml",
            title = FEED_TITLE, category = "", status = "ok", lastSuccessAt = now,
        ))
        ARTICLES.forEachIndexed { i, a ->
            val localId = "vr5-a-$i"
            db.articles().upsertArticle(ArticleEntity(
                localId = localId, instanceKey = LOCAL_SCOPE_KEY, feedId = a.feedId ?: FEED_ID, guid = localId,
                link = "https://example.invalid/vr5/$i", title = a.title,
                // 同一本地日期（模拟器时区 GMT）；按 publishedAt 倒序即 0→3 的列表顺序
                publishedAt = "$DATE" + "T0${8 - i}:00:00Z",
                feedTitle = FEED_TITLE, feedUrl = "https://example.invalid/vr5.xml",
                isRead = false, isStarred = a.starredAt != null,
                starredAt = a.starredAt?.let { now - it * 60_000L }, stateUpdatedAt = 1,
            ))
            if (a.summary != null || a.bodyHtml != null) {
                db.articles().upsertContent(ArticleContentEntity(
                    localId = localId, content = a.bodyHtml, summary = a.summary?.let { "<p>$it</p>" },
                    thumbnail = null, contentSource = "rss", contentHash = "vr5-$i",
                    searchText = a.summary, searchBodyText = null, lastAccessedAt = now,
                ))
            }
        }
        settings.setAiConfig(AI_BASE, AI_MODEL)
        settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
        settings.setLastReadingPosition(FEED_ID, null, null)
        seedDigestCache()
    }

    /** 与 DigestViewModel.open/applyDate 同一路径取候选，保证缓存键一致（否则 UI 不会命中） */
    private suspend fun seedDigestCache() {
        val rows = db.articles().listByFeed(LOCAL_SCOPE_KEY, FEED_ID, 200, 0).map { e ->
            val stored = db.articles().getContent(e.localId)
            DigestScope.Row(
                localId = e.localId, title = e.title, publishedAt = e.publishedAt, link = e.link,
                body = stored?.content?.let { ArticleSanitizer.sanitize(it, e.link).plainText }?.ifBlank { null },
                summary = stored?.summary?.let { ArticleSanitizer.sanitize(it, e.link).plainText }?.ifBlank { null },
            )
        }
        val candidates = DigestScope.candidates(rows, DATE)
        check(candidates.size == 3) { "expected 3 digest candidates, got ${candidates.size}" }
        val sources = candidates.mapIndexed { i, c -> DigestSummarizer.Source(i + 1, c.localId, c.title, c.link) }
        val request = DigestSummarizer.Request(
            instanceKey = LOCAL_SCOPE_KEY, feedId = FEED_ID, feedTitle = FEED_TITLE, localDate = DATE,
            zoneId = TimeZone.getDefault().id, sources = sources, texts = candidates.map { it.text },
            baseUrl = AI_BASE, model = AI_MODEL, apiKey = "",
        )
        val digest = DigestSummarizer.Digest(
            title = "隔离合成摘要（非 AI 生成）",
            overview = "这是一份为界面验收写入的合成日摘要：三篇隔离文章都在讲离线阅读与排版，内容不是模型输出。",
            points = listOf(
                DigestSummarizer.Point("离线副本与收藏互不影响，清理普通缓存不会删除离线副本。", listOf(1)),
                DigestSummarizer.Point("正文排版在窄屏和大字号下保持可读，长标题会换行。", listOf(2, 3)),
                DigestSummarizer.Point("只有摘要的文章也能进入日摘要范围，但不会被当成已保存正文。", listOf(3)),
            ),
        )
        db.aiCache().insert(AiCacheEntity(
            cacheKey = DigestSummarizer.cacheKey(request), instanceKey = LOCAL_SCOPE_KEY, task = DigestSummarizer.TASK,
            endpoint = "https://ai.example.invalid", model = AI_MODEL,
            contentHash = DigestSummarizer.setFingerprint(request), language = Translator.TARGET_LANGUAGE,
            promptVersion = DigestSummarizer.PROMPT_VERSION, result = DigestSummarizer.serialize(digest, sources),
            complete = true, createdAt = now - 20 * 60_000L,
        ))
    }

    private suspend fun cleanup() {
        val w = db.openHelper.writableDatabase
        w.execSQL("DELETE FROM ai_cache WHERE instanceKey = ? AND cacheKey LIKE ?", arrayOf(LOCAL_SCOPE_KEY, "$LOCAL_SCOPE_KEY|$FEED_ID|%"))
        w.execSQL("DELETE FROM articles WHERE instanceKey = ? AND localId LIKE 'vr5-%'", arrayOf(LOCAL_SCOPE_KEY))
        w.execSQL("DELETE FROM feeds WHERE instanceKey = ? AND feedId LIKE 'vr5-%'", arrayOf(LOCAL_SCOPE_KEY))
        // 经真实 OPML 导入 / 状态恢复 UI 写入的行（地址都以 https://example.invalid/vr5 开头）
        w.execSQL("DELETE FROM articles WHERE instanceKey = ? AND feedUrl LIKE 'https://example.invalid/vr5%'", arrayOf(LOCAL_SCOPE_KEY))
        w.execSQL("DELETE FROM feeds WHERE instanceKey = ? AND url LIKE 'https://example.invalid/vr5%'", arrayOf(LOCAL_SCOPE_KEY))
        if (memo.getBoolean("seeded", false)) {
            settings.setCurrentInstanceKey(memo.getString("key", null))
            settings.setActiveScopeKind(memo.getString("kind", null))
            settings.setLastReadingPosition(memo.getString("feed", null), memo.getString("filter", null), memo.getString("anchor", null))
            settings.setAiConfig(memo.getString("aiBase", null), memo.getString("aiModel", null))
            memo.edit().clear().commit()
        }
    }

    /** starredAt = 几分钟前收藏（null = 未收藏）；feedId 非 null = 指向已不存在的源 */
    private class Seed(
        val title: String,
        val summary: String?,
        val bodyHtml: String?,
        val starredAt: Long? = null,
        val feedId: String? = null,
    )

    private companion object {
        const val FEED_ID = "vr5-feed-0"
        const val FEED_TITLE = "隔离源 · 结果态"
        const val DATE = "2026-09-28"
        const val AI_BASE = "https://ai.example.invalid/v1"
        const val AI_MODEL = "vr5-placeholder-model"
        val ARTICLES = listOf(
            Seed("隔离离线指南：收藏与离线副本", "隔离摘要：离线副本与收藏互不影响。",
                "<p>隔离正文：离线副本与收藏互不影响，清理普通缓存不会删除离线副本。</p>", starredAt = 5),
            Seed("隔离排版笔记：窄屏与大字号", "隔离摘要：排版检查。",
                "<p>隔离正文：正文排版在窄屏和大字号下保持可读。</p>" +
                    "<img src=\"https://example.invalid/vr5/1.png\" alt=\"隔离示意图\">" +
                    "<p>图片地址不可达，用来验证离线时的占位。</p>"),
            Seed("隔离仅摘要：只有书签的文章", "隔离摘要：只有摘要的文章也能进入日摘要范围。", null, starredAt = 30),
            Seed("隔离已删源：原订阅已删除的收藏", null, null, starredAt = 90, feedId = "vr5-gone"),
        )
    }
}
