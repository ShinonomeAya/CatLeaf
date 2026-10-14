package dev.local.catleaf.nativeapp.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T04 的凭据检查：Keystore 加解密往返、密文里没有明文、删除后不再可读，
 * 以及“另一个凭据损坏不影响其他凭据”。
 *
 * 全部使用虚构值：这些字符串是刻意编造的，不代表任何可用凭据。
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {

    @Test fun ai_provider_binding_and_opaque_rollback_preserve_the_previous_key() {
        store.putAiKey("https://api.deepseek.com", fakeAiKey)
        val oldKey = store.ciphertext(CredentialStore.AI_KEY)
        val oldOrigin = store.ciphertext(CredentialStore.AI_KEY_ORIGIN)
        assertEquals(fakeAiKey, store.getAiKey("https://api.deepseek.com/v1"))
        assertNull(store.getAiKey("https://another.example.org"))
        assertEquals(listOf(CredentialStore.AI_KEY), store.names())
        store.putAiKey("https://another.example.org", "NOT-A-REAL-NEW-KEY")
        assertNull(store.getAiKey("https://api.deepseek.com"))
        store.restoreCiphertext(CredentialStore.AI_KEY, oldKey)
        store.restoreCiphertext(CredentialStore.AI_KEY_ORIGIN, oldOrigin)
        assertEquals(fakeAiKey, store.getAiKey("https://api.deepseek.com"))
        val atomic = android.util.AtomicFile(File(store.directoryPath(), "${CredentialStore.AI_KEY}.bin"))
        val output = atomic.startWrite()
        output.write(byteArrayOf(1, 2, 3))
        atomic.failWrite(output)
        assertEquals(fakeAiKey, store.getAiKey("https://api.deepseek.com"))
    }

    @Test fun legacy_key_without_binding_remains_readable() {
        store.put(CredentialStore.AI_KEY, fakeAiKey)
        assertEquals(fakeAiKey, store.getAiKey("https://api.deepseek.com"))
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: CredentialStore

    /** 虚构测试值，不是任何真实服务的密钥 */
    private val fakeInstanceSecret = "FAKE-TEST-INSTANCE-SECRET-not-a-real-key"
    private val fakeAiKey = "sk-FAKE-TEST-AI-KEY-0000000000000000"

    @Before
    fun setUp() {
        store = CredentialStore(context)
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.clearAll()
    }

    @Test
    fun round_trip_returns_exact_value() {
        store.put(CredentialStore.INSTANCE_SECRET, fakeInstanceSecret)
        store.put(CredentialStore.AI_KEY, fakeAiKey)

        assertEquals(fakeInstanceSecret, store.get(CredentialStore.INSTANCE_SECRET))
        assertEquals(fakeAiKey, store.get(CredentialStore.AI_KEY))
        assertTrue(store.exists(CredentialStore.INSTANCE_SECRET))
        assertEquals(listOf(CredentialStore.AI_KEY, CredentialStore.INSTANCE_SECRET), store.names())
    }

    @Test
    fun missing_credential_returns_null_without_throwing() {
        assertNull(store.get(CredentialStore.INSTANCE_SECRET))
        assertFalse(store.exists(CredentialStore.AI_KEY))
    }

    @Test
    fun ciphertext_file_contains_no_plaintext() {
        store.put(CredentialStore.AI_KEY, fakeAiKey)

        val file = File(store.directoryPath(), "${CredentialStore.AI_KEY}.bin")
        assertTrue("密文文件应写入 no-backup 私有目录", file.exists())

        val raw = file.readBytes()
        val asText = String(raw, Charsets.ISO_8859_1)
        assertFalse("密文里不得出现明文 Key", asText.contains(fakeAiKey))
        assertFalse("密文里不得出现明文的片段", asText.contains("FAKE-TEST-AI-KEY"))
        assertTrue("载荷应包含 IV 与密文", raw.size > fakeAiKey.length / 2)

        // 目录确实位于不参与备份的位置
        assertTrue(
            "凭据目录应位于 noBackupFilesDir 下",
            store.directoryPath().startsWith(context.noBackupFilesDir.absolutePath),
        )
    }

    @Test
    fun re_encrypting_uses_a_fresh_iv() {
        store.put(CredentialStore.AI_KEY, fakeAiKey)
        val first = File(store.directoryPath(), "${CredentialStore.AI_KEY}.bin").readBytes()
        store.put(CredentialStore.AI_KEY, fakeAiKey)
        val second = File(store.directoryPath(), "${CredentialStore.AI_KEY}.bin").readBytes()

        assertFalse("同样的明文重写必须使用不同 IV", first.contentEquals(second))
        assertEquals("重写后仍要能解回原值", fakeAiKey, store.get(CredentialStore.AI_KEY))
    }

    @Test
    fun corrupt_credential_does_not_break_others() {
        store.put(CredentialStore.INSTANCE_SECRET, fakeInstanceSecret)
        store.put(CredentialStore.AI_KEY, fakeAiKey)

        // 只破坏 AI Key 的密文
        val broken = File(store.directoryPath(), "${CredentialStore.AI_KEY}.bin")
        val bytes = broken.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        broken.writeBytes(bytes)

        try {
            store.get(CredentialStore.AI_KEY)
            org.junit.Assert.fail("损坏的密文必须抛出，不能回退明文")
        } catch (expected: CredentialUnavailableException) {
            // 预期
        }

        assertEquals("其他凭据不受影响", fakeInstanceSecret, store.get(CredentialStore.INSTANCE_SECRET))
    }

    @Test
    fun delete_removes_only_that_credential() {
        store.put(CredentialStore.INSTANCE_SECRET, fakeInstanceSecret)
        store.put(CredentialStore.AI_KEY, fakeAiKey)

        store.delete(CredentialStore.AI_KEY)

        assertNull(store.get(CredentialStore.AI_KEY))
        assertEquals(fakeInstanceSecret, store.get(CredentialStore.INSTANCE_SECRET))
    }

    @Test
    fun rejects_unexpected_names() {
        try {
            store.put("../escape", "x")
            org.junit.Assert.fail("非法凭据名必须被拒绝")
        } catch (expected: IllegalArgumentException) {
            // 预期
        }
    }
}
