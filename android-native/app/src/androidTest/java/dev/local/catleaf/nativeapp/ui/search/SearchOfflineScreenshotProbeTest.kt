package dev.local.catleaf.nativeapp.ui.search

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.local.OfflinePackageEntity
import dev.local.catleaf.nativeapp.data.offline.OfflinePackageStore
import dev.local.catleaf.nativeapp.data.settings.LOCAL_SCOPE_KEY
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_LOCAL
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VR4 截图用（原型 35–40 搜索 / 单篇离线）：只在隔离模拟器上显式运行（默认被 ManualProbe 排除）。
 * phase=seed 在本机范围写入 vr4- 前缀的 2 个源、7 篇文章（含正文、仅摘要、仅标题、带 .invalid 图片的正文），
 * 标题/摘要/正文中带 ASCII 关键词 CatLeaf（adb 只能输入 ASCII），vr4-a-3 仅正文命中；
 * 以及 4 条离线记录（partial/complete/text/saving，带占位 body.html 与对应已存张数的真实 PNG，id 不对应任何文章，只为 40 管理页状态；
 * saving 会在 App 冷启动对账时如实变为 interrupted）；
 * 原 settings 记入探针私有文件。不写任何密钥，所有地址都是 .invalid。
 * phase=cleanup 通过 OfflinePackageStore.remove 删除真实保存流程产生的离线文件，再删 vr4- 行并恢复原 settings。
 */
@RunWith(AndroidJUnit4::class)
@ManualProbe
class SearchOfflineScreenshotProbeTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = CatLeafDatabase.get(app)
    private val settings = SettingsStore(app)
    private val memo = app.getSharedPreferences("vr4-search-probe", Context.MODE_PRIVATE)
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
                .commit()
        }
        FEEDS.forEachIndexed { i, (id, title) ->
            db.feeds().upsert(FeedEntity(
                instanceKey = LOCAL_SCOPE_KEY, feedId = id, url = "https://example.invalid/vr4-$i.xml",
                title = title, category = "", status = "ok", lastSuccessAt = now,
            ))
        }
        ARTICLES.forEachIndexed { i, a ->
            val localId = "vr4-a-$i"
            db.articles().upsertArticle(ArticleEntity(
                localId = localId, instanceKey = LOCAL_SCOPE_KEY, feedId = FEEDS[a.feed].first, guid = localId,
                link = "https://example.invalid/vr4/$i", title = a.title,
                publishedAt = "2026-09-${20 + i}T0${i + 1}:00:00Z",
                feedTitle = FEEDS[a.feed].second, feedUrl = "https://example.invalid/vr4-${a.feed}.xml",
                isRead = i % 3 == 2, stateUpdatedAt = 1,
            ))
            if (a.summary != null || a.body != null) {
                db.articles().upsertContent(ArticleContentEntity(
                    localId = localId, content = a.bodyHtml ?: a.body?.let { "<p>$it</p>" },
                    summary = a.summary?.let { "<p>$it</p>" }, thumbnail = null,
                    contentSource = "rss", contentHash = "vr4-$i", searchText = a.summary,
                    searchBodyText = a.body, lastAccessedAt = now,
                ))
            }
        }
        OFFLINE.forEachIndexed { i, o ->
            // 冷启动对账会删掉没有 body.html 的记录，并按实际图片文件核对已存张数：
            // 按 OfflinePackageStore 的目录规则写占位正文与已存张数对应的真实 PNG
            val folder = java.io.File(java.io.File(java.io.File(app.noBackupFilesDir, "offline-packages"),
                sha("$LOCAL_SCOPE_KEY\nvr4-off-$i")), "vr4-fake").apply { mkdirs() }
            folder.resolve("body.html").writeText("<p>隔离离线占位正文</p>")
            val manifest = org.json.JSONArray()
            repeat(o.saved) { k ->
                val id = sha("vr4-img-$i-$k")
                folder.resolve(id).writeBytes(PNG)
                manifest.put(org.json.JSONObject().put("id", id).put("mime", "image/png"))
            }
            db.offline().upsert(OfflinePackageEntity(
                localId = "vr4-off-$i", scopeKey = LOCAL_SCOPE_KEY, title = o.title, feedTitle = FEEDS[i % 2].second,
                generation = "vr4-fake", pendingGeneration = null, status = o.status,
                selectedImages = o.total > 0, imageTotal = o.total, imageSaved = o.saved,
                bytes = o.bytes, bodyHash = "vr4-off-$i", searchBodyText = "", imageManifest = manifest.toString(),
                coverId = null, updatedAt = now - i * 60_000L,
            ))
        }
        settings.setActiveScopeKind(SCOPE_KIND_LOCAL)
        settings.setLastReadingPosition(FEEDS[0].first, null, null)
    }

    private suspend fun cleanup() {
        val store = OfflinePackageStore(app, db)
        ARTICLES.indices.forEach { store.remove(LOCAL_SCOPE_KEY, "vr4-a-$it") }
        OFFLINE.indices.forEach { store.remove(LOCAL_SCOPE_KEY, "vr4-off-$it") }
        val w = db.openHelper.writableDatabase
        w.execSQL("DELETE FROM offline_packages WHERE scopeKey = ? AND localId LIKE 'vr4-%'", arrayOf(LOCAL_SCOPE_KEY))
        w.execSQL("DELETE FROM articles WHERE instanceKey = ? AND feedId LIKE 'vr4-feed-%'", arrayOf(LOCAL_SCOPE_KEY))
        w.execSQL("DELETE FROM feeds WHERE instanceKey = ? AND feedId LIKE 'vr4-feed-%'", arrayOf(LOCAL_SCOPE_KEY))
        if (memo.getBoolean("seeded", false)) {
            settings.setCurrentInstanceKey(memo.getString("key", null))
            settings.setActiveScopeKind(memo.getString("kind", null))
            settings.setLastReadingPosition(memo.getString("feed", null), memo.getString("filter", null), memo.getString("anchor", null))
            memo.edit().clear().commit()
        }
    }

    private fun sha(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class Seed(val feed: Int, val title: String, val summary: String?, val body: String?, val bodyHtml: String? = null)
    private class Offline(val title: String, val status: String, val saved: Int, val total: Int, val bytes: Long)

    private companion object {
        /** 2×2 真实 PNG（BitmapFactory 可解码），供离线包图片核对 */
        val PNG: ByteArray by lazy {
            val out = java.io.ByteArrayOutputStream()
            android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val FEEDS = listOf("vr4-feed-0" to "隔离源 · 技术", "vr4-feed-1" to "隔离源 · 设计")
        private const val IMG_BODY = "隔离离线正文：这一段用于保存离线截图，正文中有三张示例图片，地址全部不可达，用来验证图片未齐的真实结果。"
        val ARTICLES = listOf(
            Seed(0, "隔离离线阅读指南：CatLeaf 如何保存单篇文章", "隔离摘要：离线阅读只保存当前正文。", "隔离正文：离线副本与收藏互不影响，清理普通缓存也不会删除。"),
            Seed(1, "隔离设计笔记：阅读器排版与离线", "隔离摘要：CatLeaf 排版规则说明。", "隔离正文：正文排版在离线时保持一致。"),
            Seed(0, "隔离周报：本周订阅更新", "隔离摘要：本周离线功能上线。", null),
            Seed(1, "隔离专题：窄屏与大字号", "隔离摘要：360dp 与 1.3 倍字号检查。", "隔离正文：CatLeaf 在这里也提到了离线保存的限制，单篇最多二十兆。"),
            Seed(0, "隔离图文：CatLeaf 带三张图片的文章", "隔离摘要：用于保存结果页。", IMG_BODY,
                "<p>$IMG_BODY</p><img src=\"https://example.invalid/vr4/1.png\"><img src=\"https://example.invalid/vr4/2.png\"><img src=\"https://example.invalid/vr4/3.png\">"),
            Seed(1, "隔离仅标题：只有书签的文章", null, null),
            Seed(0, "隔离长标题：CatLeaf 一个名字特别长、在窄屏和大字号下需要换行才能完整显示的离线文章标题", "隔离摘要：长标题换行检查。", "隔离正文：长标题文章正文。"),
        )
        val OFFLINE = listOf(
            Offline("隔离离线：图片未齐的文章", "partial", 3, 5, 3_400_000),
            Offline("隔离离线：所选内容已保存", "complete", 6, 6, 8_900_000),
            Offline("隔离离线：仅正文", "text", 0, 0, 42_000),
            Offline("隔离离线：保存中的文章", "saving", 2, 4, 1_200_000),
        )
    }
}
