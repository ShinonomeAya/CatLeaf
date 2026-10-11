package dev.local.catleaf.nativeapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 跨进程持久化探针（验收①：杀进程/重启后文章状态与密钥仍可读回）。
 *
 * 必须分两次运行，让两次之间进程真的死掉：
 *
 *   adb shell am instrument -w -e class \
 *     'dev.local.catleaf.nativeapp.data.PersistenceProbeTest#step1_write' \
 *     dev.local.catleaf.nativeapp.test/androidx.test.runner.AndroidJUnitRunner
 *   adb shell am instrument -w -e class \
 *     'dev.local.catleaf.nativeapp.data.PersistenceProbeTest#step2_readBackInNewProcess' \
 *     dev.local.catleaf.nativeapp.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 只写入虚构值；用固定的数据库名，便于第二次运行读回。
 */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class PersistenceProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val db: CatLeafDatabase by lazy {
        androidx.room.Room.databaseBuilder(context, CatLeafDatabase::class.java, DB_NAME).build()
    }

    @Test
    fun step1_write() = runBlocking {
        db.instances().upsert(
            dev.local.catleaf.nativeapp.data.local.InstanceEntity(
                "inst-probe", "https://reader.example.test", "探针实例", System.currentTimeMillis(),
            ),
        )
        db.articles().merge(
            ArticleEntity(
                localId = "probe-1",
                instanceKey = "inst-probe",
                feedId = "feed-probe",
                guid = "guid-probe",
                link = "https://example.invalid/probe",
                title = "跨进程探针",
                publishedAt = "2026-09-22T00:00:00Z",
                feedTitle = "探针源",
                feedUrl = "https://example.invalid/feed.xml",
                isRead = true,
                isStarred = true,
                starredAt = 1_700_000_000_000,
                stateUpdatedAt = 1_700_000_000_000,
            ),
            ArticleContentEntity(
                localId = "probe-1",
                content = "探针正文",
                summary = null,
                thumbnail = null,
                contentSource = "rss",
                contentHash = "probe-hash",
                lastAccessedAt = 1_700_000_000_000,
            ),
        )
        db.aiCache().insert(
            dev.local.catleaf.nativeapp.data.local.AiCacheEntity(
                cacheKey = "probe-cache-key",
                instanceKey = "inst-probe",
                task = "translation",
                endpoint = "https://ai.example.test/v1",
                model = "probe-model",
                contentHash = "probe-hash",
                language = "zh-Hans",
                promptVersion = "v1",
                result = "探针译文",
                complete = true,
                createdAt = 1_700_000_000_000,
            ),
        )
        CredentialStore(context).put(CredentialStore.INSTANCE_SECRET, FAKE_SECRET)
        assertTrue("写入阶段应能立即读回", db.articles().get("probe-1")!!.isStarred)
    }

    @Test
    fun step2_readBackInNewProcess() = runBlocking {
        val article = db.articles().get("probe-1")
        assertNotNull("新进程必须能读回文章状态", article)
        assertTrue("已读应保留", article!!.isRead)
        assertTrue("收藏应保留", article.isStarred)
        assertEquals("来源身份应保留", "https://example.invalid/feed.xml", article.feedUrl)
        assertEquals("正文应保留", "探针正文", db.articles().getContent("probe-1")!!.content)
        assertEquals("实例应保留", "https://reader.example.test", db.instances().get("inst-probe")!!.origin)
        assertEquals("AI 缓存应保留", "探针译文", db.aiCache().get("probe-cache-key")!!.result)

        // 新进程重新从 Keystore 取密钥并解密，证明重启后凭据仍可用
        assertEquals("新进程必须能解密凭据", FAKE_SECRET, CredentialStore(context).get(CredentialStore.INSTANCE_SECRET))
    }

    private companion object {
        const val DB_NAME = "catleaf.db"

        /** 虚构测试值 */
        const val FAKE_SECRET = "FAKE-PROBE-SECRET-not-a-real-credential"
    }
}