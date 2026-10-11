package dev.local.catleaf.nativeapp.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据存储：实例密钥与 AI Key。
 *
 * 设计要点（契约 2、4.1）：
 * - Keystore 里只保存 **AES-GCM 加密密钥**；API Key 是**被加密的数据**，不是 Keystore 密钥。
 * - 密文与随机 IV 存在应用私有、**不参与备份**的目录（[Context.getNoBackupFilesDir]），
 *   避免设备恢复出无法解密的旧密文。
 * - 每个凭据一个文件：`<name>.bin`，内容是 `[IV 长度 1 字节][IV][密文]`。
 *   一个文件损坏不影响其他凭据，也不需要 JSON 解析。
 * - 没有明文回退：解密失败就抛出，由调用方提示重新配置，其他数据保持不变。
 *
 * ponytail: 不做每凭据独立密钥与密钥轮换；单个应用级密钥足够首版个人使用，
 * 需要按实例隔离密钥时再按 instanceKey 派生 alias。
 */
class CredentialStore(private val context: Context) {

    private val dir: File
        get() = File(context.noBackupFilesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        require(iv.size <= 255) { "IV too long: ${iv.size}" }
        val payload = ByteArray(1 + iv.size + ciphertext.size)
        payload[0] = iv.size.toByte()
        iv.copyInto(payload, 1)
        ciphertext.copyInto(payload, 1 + iv.size)
        fileFor(name).writeBytes(payload)
    }

    /** 不存在返回 null；存在但解密失败抛 [CredentialUnavailableException] */
    fun get(name: String): String? {
        val file = fileFor(name)
        if (!file.exists()) return null
        val payload = file.readBytes()
        if (payload.size < 2) throw CredentialUnavailableException("credential payload truncated: $name")
        val ivLength = payload[0].toInt() and 0xFF
        if (ivLength == 0 || payload.size <= 1 + ivLength) {
            throw CredentialUnavailableException("credential payload malformed: $name")
        }
        val iv = payload.copyOfRange(1, 1 + ivLength)
        val ciphertext = payload.copyOfRange(1 + ivLength, payload.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // 不返回明文回退；其他凭据与数据库不受影响
            throw CredentialUnavailableException("cannot decrypt credential: $name", e)
        }
    }

    fun delete(name: String) {
        fileFor(name).delete()
    }

    fun exists(name: String): Boolean = fileFor(name).exists()

    /** 供验证与清理使用：只看文件名，不读取内容 */
    fun names(): List<String> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) }?.map { it.name.removeSuffix(SUFFIX) }?.sorted()
            ?: emptyList()

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** 存储位置本身不是秘密，但也只在需要时暴露给诊断 */
    fun directoryPath(): String = dir.absolutePath

    private fun fileFor(name: String): File {
        require(NAME_PATTERN.matches(name)) { "invalid credential name: $name" }
        return File(dir, "$name$SUFFIX")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 首版不要求生物识别：后台刷新也需要读取实例密钥
                .setUserAuthenticationRequired(false)
                // GCM 下这是默认值，显式写出以表明“每次加密都用新 IV”是刻意要求
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val INSTANCE_SECRET = "instance_secret"
        const val AI_KEY = "ai_key"

        private const val DIR_NAME = "credentials"
        private const val SUFFIX = ".bin"
        private const val KEY_ALIAS = "catleaf.credentials.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private val NAME_PATTERN = Regex("[a-z0-9_]{1,64}")
    }
}

/** 凭据存在但无法解密：调用方应提示重新输入，不得回退明文，也不得删库 */
class CredentialUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)