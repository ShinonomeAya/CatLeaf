package dev.local.catleaf.nativeapp.data.network

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 对真实实例的一次连接（G1 的“真机实例连接”）。
 *
 * 需要真实实例与密钥，标记 [ManualProbe]，默认套件跳过。运行方式：
 *
 *   adb shell am instrument -w \
 *     -e class 'dev.local.catleaf.nativeapp.data.network.InstanceLiveProbeTest#real_instance' \
 *     -e origin <https://...> -e secret <key> \
 *     dev.local.catleaf.nativeapp.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 走生产代码 [InstanceConnector]，并断言配置真的落到 Room / Keystore / DataStore。
 */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class InstanceLiveProbeTest {

    @Test
    fun real_instance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("origin")
        val secret = args.getString("secret")

        assertFalse("缺少 origin 参数", origin.isNullOrBlank())
        assertFalse("缺少 secret 参数", secret.isNullOrBlank())

        // 用固定库名，便于随后启动 App 验证它能读到同一份配置
        val db = Room.databaseBuilder(context, CatLeafDatabase::class.java, CatLeafDatabase.NAME).build()
        val credentials = CredentialStore(context)
        val settings = SettingsStore(context)

        val result = runBlocking {
            InstanceConnector(db, credentials, settings).connect(origin!!, secret!!)
        }

        assertTrue(
            "真实实例连接应成功，实际：${result.exceptionOrNull()?.message}",
            result.isSuccess,
        )
        val connected = result.getOrThrow()

        // 凭据、实例、当前实例指针三处都必须落盘
        assertEquals(secret, credentials.get("instance_secret_${connected.instanceKey}"))
        assertEquals(
            "当前实例指针应指向本次连接",
            connected.instanceKey,
            runBlocking { settings.current().currentInstanceKey },
        )
        assertNotNull("实例应写入 Room", runBlocking { db.instances().get(connected.instanceKey) })
        assertEquals("实例 origin 应规范化保存", origin, runBlocking { db.instances().get(connected.instanceKey) }!!.origin)
        assertTrue("受保护接口应返回可读的源数量", connected.feedCount >= 0)

        db.close()
    }
}
