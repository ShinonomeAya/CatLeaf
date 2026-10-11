package dev.local.catleaf.nativeapp.data.network

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 实例连接逻辑检查：状态码到错误的映射、只在成功后保存、失败不覆盖旧配置。
 *
 * 用可注入的假响应（自定义 Interceptor）驱动，不发真实网络请求：
 * 地址策略正确地禁止了 http 与内网目标，因此真实端点无法在本地构造，
 * 而这里要验证的是连接器自身的判断与保存时机。
 */
@RunWith(AndroidJUnit4::class)
class InstanceConnectorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dbName: String
    private lateinit var db: CatLeafDatabase
    private lateinit var credentials: CredentialStore
    private lateinit var settings: SettingsStore

    /** 虚构测试值 */
    private val fakeSecret = "FAKE-INSTANCE-SECRET-connector-test"
    private val fakeNewSecret = "FAKE-NEW-SECRET-connector-test"

    @Before
    fun setUp() {
        dbName = "connector-${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, CatLeafDatabase::class.java, dbName).build()
        credentials = CredentialStore(context).also { it.clearAll() }
        settings = SettingsStore(context)
        // DataStore 在同进程内跨用例共享一个文件，必须清掉，否则当前实例指针会串场
        runBlocking { settings.clear() }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
        credentials.clearAll()
    }

    private fun responder(
        code: Int,
        body: String = "[]",
        throwable: Throwable? = null,
    ): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor { chain ->
            throwable?.let { throw it }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("stub")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun connector(
        code: Int,
        body: String = "[]",
        throwable: Throwable? = null,
    ) = InstanceConnector(db, credentials, settings) { responder(code, body, throwable) }

    @Test
    fun successful_connect_saves_instance_secret_and_current_pointer() = runBlocking {
        val result = connector(200, """[{"id":"a"},{"id":"b"}]""")
            .connect("https://reader.example.com", fakeSecret)

        val connected = result.getOrNull()
        assertNotNull("200 应视为连接成功", connected)
        assertEquals(2, connected!!.feedCount)

        // 凭据按 instanceKey 保存，且能解密读回
        assertEquals(
            fakeSecret,
            credentials.get("instance_secret_${connected.instanceKey}"),
        )
        // 当前实例指针写入 DataStore
        assertEquals(connected.instanceKey, settings.current().currentInstanceKey)
        // 实例写入 Room
        assertEquals("https://reader.example.com", db.instances().get(connected.instanceKey)!!.origin)
    }

    @Test
    fun unauthorized_and_unconfigured_map_to_distinct_errors() = runBlocking {
        assertTrue(connector(401).connect("https://reader.example.com", fakeSecret).exceptionOrNull() is EndpointError.Unauthorized)
        assertTrue(connector(403).connect("https://reader.example.com", fakeSecret).exceptionOrNull() is EndpointError.Unauthorized)
        assertTrue(connector(503).connect("https://reader.example.com", fakeSecret).exceptionOrNull() is EndpointError.NotConfigured)
        assertTrue(connector(500).connect("https://reader.example.com", fakeSecret).exceptionOrNull() is EndpointError.Unreachable)
    }

    @Test
    fun timeout_maps_to_timeout_error() = runBlocking {
        val result = connector(200, throwable = SocketTimeoutException("simulated"))
            .connect("https://reader.example.com", fakeSecret)
        assertTrue("超时必须分类为 Timeout，UI 才能给出可重试提示", result.exceptionOrNull() is EndpointError.Timeout)
    }

    @Test
    fun network_failure_maps_to_unreachable() = runBlocking {
        val result = connector(200, throwable = IOException("simulated network down"))
            .connect("https://reader.example.com", fakeSecret)
        assertTrue(result.exceptionOrNull() is EndpointError.Unreachable)
    }

    @Test
    fun invalid_address_is_rejected_before_any_request() = runBlocking {
        for (bad in listOf("http://reader.example.com", "https://localhost", "https://10.0.0.1", "not a url")) {
            val result = connector(200).connect(bad, fakeSecret)
            assertTrue("应拒绝地址：$bad", result.exceptionOrNull() is EndpointError.Invalid)
            // 被拒绝的地址不得留下任何痕迹
            assertEquals("不应写入实例", 0, db.instances().all().size)
            assertEquals("不应写入凭据", 0, credentials.names().size)
            assertNull("不应切换当前实例", settings.current().currentInstanceKey)
        }
    }

    @Test
    fun failed_connect_does_not_overwrite_existing_configuration() = runBlocking {
        // 先成功连接一个实例
        val first = connector(200, """[{"id":"a"}]""").connect("https://reader.example.com", fakeSecret).getOrNull()
        assertNotNull(first)
        val key = first!!.instanceKey

        // 再用不同密钥去连另一个地址，但服务不可用
        val failed = connector(200, throwable = IOException("down"))
            .connect("https://other.example.com", fakeNewSecret)
        assertTrue(failed.isFailure)

        // 旧配置必须原样保留
        assertEquals("旧实例仍应存在", "https://reader.example.com", db.instances().get(key)!!.origin)
        assertEquals("旧密钥必须仍可解密", fakeSecret, credentials.get("instance_secret_$key"))
        assertEquals("当前实例不应被切换", key, settings.current().currentInstanceKey)
        assertNull("失败目标不应留下凭据", credentials.get("instance_secret_" + EndpointPolicy.instanceKey("https://other.example.com")))
    }

    @Test
    fun bad_secret_on_existing_instance_keeps_old_secret() = runBlocking {
        val first = connector(200, """[{"id":"a"}]""").connect("https://reader.example.com", fakeSecret).getOrNull()!!
        val key = first.instanceKey

        // 就地换密钥但校验失败（401）：不得把坏密钥写进去顶掉可用的旧密钥
        val failed = connector(401).updateSecret(key, fakeNewSecret)
        assertTrue(failed.isFailure)
        assertEquals("校验失败不得覆盖旧密钥", fakeSecret, credentials.get("instance_secret_$key"))
    }

    @Test
    fun blank_secret_is_rejected() = runBlocking {
        val result = connector(200).connect("https://reader.example.com", "   ")
        assertTrue(result.exceptionOrNull() is EndpointError.Invalid)
    }

    @Test
    fun same_origin_without_trailing_slash_reuses_same_namespace() = runBlocking {
        val a = connector(200).connect("https://reader.example.com", fakeSecret).getOrNull()!!
        val b = connector(200).connect("https://reader.example.com/", fakeSecret).getOrNull()!!
        assertEquals("同一实例不同写法必须落在同一 namespace", a.instanceKey, b.instanceKey)
    }

    @Test
    fun connector_never_sends_credentials_to_a_public_host() = runBlocking {
        // 记录实例连接实际请求到的地址，确认它只打向配置的实例 origin
        var requestedUrl: String? = null
        val capturing = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requestedUrl = chain.request().url.toString()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("stub")
                        .body("[]".toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

        InstanceConnector(db, credentials, settings) { capturing }
            .connect("https://reader.example.com", fakeSecret)

        assertEquals("https://reader.example.com/api/feeds/list", requestedUrl)
    }
}