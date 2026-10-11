package dev.local.catleaf.nativeapp.ui.feeds

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.FeedStore
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.network.FeedApi
import dev.local.catleaf.nativeapp.data.opml.OpmlImporter
import dev.local.catleaf.nativeapp.ui.theme.CatLeafTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OpmlCancellationTest {
    @get:Rule val composeRule = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val db = Room.inMemoryDatabaseBuilder(app, CatLeafDatabase::class.java).build()
    private val server = MockWebServer()
    private val file = app.cacheDir.resolve("isolated-opml-cancel.opml")

    @After fun tearDown() {
        file.delete()
        server.shutdown()
        db.close()
    }

    @Test fun closing_import_stops_later_add_requests() = runBlocking {
        val adds = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/feeds/list/admin") == true -> MockResponse().setBody("[]")
                request.path?.startsWith("/api/feeds/add") == true -> {
                    adds.incrementAndGet()
                    MockResponse().setResponseCode(500).setBody("boom").setBodyDelay(1, TimeUnit.SECONDS)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val api = FeedApi(server.url("").toString().trimEnd('/'), "FAKE-OPML-SECRET")
        val store = FeedStore(db, api, "isolated_opml_cancel")
        val vm = OpmlViewModel(app)
        vm.attach(store, OpmlImporter(store))
        file.writeText("""<opml version="2.0"><head><title>test</title></head><body>
            <outline type="rss" text="A" xmlUrl="https://a.example.org/rss"/>
            <outline type="rss" text="B" xmlUrl="https://b.example.org/rss"/>
            </body></opml>""")
        vm.loadDocument(Uri.fromFile(file))
        withTimeout(5_000) { vm.state.first { it.document?.entries?.size == 2 } }
        vm.startImport()
        withTimeout(5_000) { while (adds.get() == 0) delay(20) }
        vm.dismiss()
        delay(1_500)
        assertEquals(1, adds.get())
        assertNull(vm.state.value.report)
    }

    @Test fun failed_opml_import_can_be_retried_without_overwriting_existing_remote_feed() = runBlocking {
        val existingUrl = "https://existing.example.org/rss"
        val retryUrl = "https://retry.example.org/rss"
        val retryAttempts = AtomicInteger()
        val existingAdds = AtomicInteger()
        data class RemoteFeed(val id: String, val url: String, val category: String, val title: String)
        val remoteFeeds = mutableListOf(RemoteFeed("existing-id", existingUrl, "原分组", "服务器原名"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/feeds/list/admin") == true -> {
                    val body = remoteFeeds.joinToString(",", "[", "]") {
                        """{"id":"${it.id}","url":"${it.url}","category":"${it.category}","customTitle":"${it.title}","displayOrder":0}"""
                    }
                    MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
                request.path?.startsWith("/api/feeds/add") == true -> {
                    val body = request.body.readUtf8()
                    val url = Regex("\"url\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                    if (url == existingUrl) existingAdds.incrementAndGet()
                    if (url == retryUrl && retryAttempts.incrementAndGet() == 1) {
                        MockResponse().setResponseCode(503).setBody("暂时不可用")
                    } else {
                        val id = Regex("\"id\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                        val title = Regex("\"customTitle\":\"([^\"]*)\"").find(body)?.groupValues?.get(1).orEmpty()
                        val category = Regex("\"category\":\"([^\"]*)\"").find(body)?.groupValues?.get(1).orEmpty()
                        remoteFeeds += RemoteFeed(id, url, category, title)
                        MockResponse().setBody("""{"success":true}""")
                    }
                }
                request.path?.startsWith("/api/feed?") == true -> MockResponse().setBody("<rss/>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val store = FeedStore(db, FeedApi(server.url("").toString().trimEnd('/'), "FAKE-OPML-RETRY"), "isolated_opml_retry")
        val vm = OpmlViewModel(app)
        vm.attach(store, OpmlImporter(store))
        composeRule.setContent {
            CatLeafTheme {
                val state by vm.state.collectAsState()
                state.report?.let { report ->
                    OpmlResultSurface(
                        report = report,
                        importing = state.importing,
                        localOnly = false,
                        onRetry = vm::retryUnresolved,
                        onDismiss = vm::dismiss,
                    )
                }
            }
        }
        file.writeText("""<opml version="2.0"><head><title>test</title></head><body>
            <outline type="rss" text="文件里的旧名称" xmlUrl="$existingUrl" />
            <outline type="rss" text="暂时失败的源" xmlUrl="$retryUrl" />
            </body></opml>""")
        vm.loadDocument(Uri.fromFile(file))
        withTimeout(5_000) { vm.state.first { it.document?.entries?.size == 2 } }

        vm.startImport()
        val first = withTimeout(10_000) { vm.state.first { !it.importing && it.report != null } }.report!!
        assertEquals(1, first.exists)
        assertEquals(1, first.failed)
        assertEquals(0, first.added)
        composeRule.onNodeWithText("部分导入完成").assertIsDisplayed()

        composeRule.onNodeWithText("重试失败项（1）").performClick()
        val retried = withTimeout(10_000) {
            vm.state.first { !it.importing && it.report?.added == 1 }
        }.report!!
        assertEquals(1, retried.exists)
        assertEquals(1, retried.added)
        assertEquals(0, retried.failed)
        assertEquals("已有订阅不得被 OPML 改名", 0, existingAdds.get())
        assertEquals("明确失败的条目应重新请求", 2, retryAttempts.get())
        assertEquals("服务器原名称应保留", "服务器原名", remoteFeeds.single { it.url == existingUrl }.title)
        assertTrue(remoteFeeds.any { it.url == retryUrl })
        // 结果行 = 登记结果 · 验证结果（契约 8.2），按前缀匹配
        composeRule.onNodeWithText("已添加", substring = true).assertIsDisplayed()
        Unit
    }

    @Test fun timed_out_opml_import_can_be_confirmed_from_remote_list_in_result_ui() = runBlocking {
        val retryUrl = "https://uncertain.example.org/rss"
        val attempts = AtomicInteger()
        data class RemoteFeed(val id: String, val url: String)
        val remoteFeeds = mutableListOf<RemoteFeed>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/feeds/list/admin") == true -> {
                    val body = remoteFeeds.joinToString(",", "[", "]") {
                        """{"id":"${it.id}","url":"${it.url}","category":"","customTitle":"","displayOrder":0}"""
                    }
                    MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
                request.path?.startsWith("/api/feeds/add") == true -> {
                    attempts.incrementAndGet()
                    val body = request.body.readUtf8()
                    val id = Regex("\"id\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                    val url = Regex("\"url\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                    remoteFeeds += RemoteFeed(id, url)
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val api = FeedApi(
            server.url("").toString().trimEnd('/'),
            "FAKE-OPML-TIMEOUT",
            clientFactory = { secret ->
                dev.local.catleaf.nativeapp.data.network.HttpClients.forInstance(secret)
                    .newBuilder().callTimeout(500, TimeUnit.MILLISECONDS).build()
            },
        )
        val store = FeedStore(db, api, "isolated_opml_timeout")
        val vm = OpmlViewModel(app)
        vm.attach(store, OpmlImporter(store))
        composeRule.setContent {
            CatLeafTheme {
                val state by vm.state.collectAsState()
                state.report?.let { report ->
                    OpmlResultSurface(
                        report = report,
                        importing = state.importing,
                        localOnly = false,
                        onRetry = vm::retryUnresolved,
                        onDismiss = vm::dismiss,
                    )
                }
            }
        }
        file.writeText("""<opml version="2.0"><head><title>timeout</title></head><body>
            <outline type="rss" text="可能已写入的源" xmlUrl="$retryUrl" />
            </body></opml>""")
        vm.loadDocument(Uri.fromFile(file))
        withTimeout(5_000) { vm.state.first { it.document?.entries?.size == 1 } }

        vm.startImport()
        val first = withTimeout(10_000) { vm.state.first { !it.importing && it.report != null } }.report!!
        assertEquals(1, first.pending)
        assertEquals(0, first.failed)
        assertEquals(1, attempts.get())
        composeRule.onNodeWithText("请求超时，结果待确认").assertIsDisplayed()

        composeRule.onNodeWithText("重试失败项（1）").performClick()
        val retried = withTimeout(10_000) {
            vm.state.first { !it.importing && it.report?.exists == 1 && it.report.pending == 0 }
        }.report!!
        assertEquals(0, retried.failed)
        assertEquals("核对后应关联既有远端源，不要重复新增", 1, attempts.get())
        assertEquals(1, remoteFeeds.size)
        composeRule.onNodeWithText("核对后确认已存在").assertIsDisplayed()
        Unit
    }
}
