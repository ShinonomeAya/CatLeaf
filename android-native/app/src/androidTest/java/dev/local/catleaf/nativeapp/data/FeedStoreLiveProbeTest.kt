package dev.local.catleaf.nativeapp.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.network.FeedApi
import dev.local.catleaf.nativeapp.data.network.FeedApiError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T08 对真实实例的增删改探针（验收①②③的端到端部分）。
 *
 * 需要真实实例与密钥，标记 [ManualProbe]，默认套件跳过：
 *
 *   adb shell am instrument -w \
 *     -e class 'dev.local.catleaf.nativeapp.data.FeedStoreLiveProbeTest#crud_against_real_instance' \
 *     -e origin <https://...> -e secret <key> \
 *     dev.local.catleaf.nativeapp.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 用公开可访问的真实 RSS，结束时删除自己创建的源，不留下测试数据。
 */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class FeedStoreLiveProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: CatLeafDatabase
    private lateinit var store: FeedStore
    private lateinit var api: FeedApi
    private val createdIds = mutableListOf<String>()

    private val instanceKey = "live-probe-instance"
    private val realFeedUrl = "https://blog.cloudflare.com/rss/"

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("origin")
        val secret = args.getString("secret")
        assertFalse("缺少 origin 参数", origin.isNullOrBlank())
        assertFalse("缺少 secret 参数", secret.isNullOrBlank())

        db = Room.inMemoryDatabaseBuilder(context, CatLeafDatabase::class.java).build()
        api = FeedApi(origin!!, secret!!)
        store = FeedStore(db, api, instanceKey)
    }

    @After
    fun tearDown() = runBlocking {
        // 清理本次创建的源，不给实例留测试数据
        createdIds.forEach { runCatching { api.delete(it) } }
        db.close()
    }

    @Test
    fun crud_against_real_instance() = runBlocking {
        // ── 列表可读 ──────────────────────────────────────────────
        val initial = api.list().getOrThrow()
        println("[probe] 初始源数=${initial.size}")

        // ── 新增：成功后本地镜像应有该源且状态为可读 ───────────────
        val addedResult = store.add(url = realFeedUrl, category = "T08探针", customTitle = "探针源").getOrThrow()
        val id = addedResult.feedId
        createdIds += id

        var mirrored = store.local()
        val added = mirrored.firstOrNull { it.feedId == id }
        assertNotNull("本地镜像应包含新增的源", added)
        assertEquals("探针源", added!!.title)
        assertEquals("T08探针", added.category)
        assertEquals("真实抓取成功应标为可读", FeedStore.STATUS_OK, added.status)
        assertNotNull("应记录最近成功时间", added.lastSuccessAt)
        println("[probe] 新增后镜像源数=${mirrored.size}")

        // ── 重复提交同一 URL：服务端拒绝，本地不新增 ───────────────
        val dup = store.add(url = realFeedUrl, category = "T08探针", customTitle = "重复源")
        assertTrue("重复 URL 应被拒绝", dup.isFailure)
        assertTrue("应是可识别的冲突", dup.exceptionOrNull() is FeedApiError.Conflict)
        assertEquals("重复提交不得新增源", mirrored.size, store.local().size)

        // ── 编辑：改名称与分组，URL 不变 ───────────────────────────
        store.updateMeta(id, category = "T08改名", customTitle = "改过的名字").getOrThrow()
        val edited = store.local().firstOrNull { it.feedId == id }!!
        assertEquals("改过的名字", edited.title)
        assertEquals("T08改名", edited.category)
        assertEquals("编辑不得改变订阅地址", realFeedUrl, edited.url)

        // ── 服务端侧核对（权威）────────────────────────────────────
        val remote = api.list().getOrThrow().firstOrNull { it.id == id }
        assertNotNull(remote)
        assertEquals("服务端名称已更新", "改过的名字", remote!!.customTitle)
        assertEquals("服务端地址未被改动", realFeedUrl, remote.url)

        // ── 删除：服务端与本地镜像同步移除 ─────────────────────────
        store.delete(id).getOrThrow()
        createdIds.remove(id)
        assertNull("本地镜像应不再包含该源", store.local().firstOrNull { it.feedId == id })
        assertNull("服务端应不再返回该源", api.list().getOrThrow().firstOrNull { it.id == id })
        println("[probe] 删除后镜像源数=${store.local().size}")

        // ── 删除不存在的源 ────────────────────────────────────────
        assertTrue("删除不存在的源应失败", store.delete("t08-probe-nonexistent").isFailure)
    }
}