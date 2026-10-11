package dev.local.catleaf.nativeapp.data.network

import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.InstanceEntity
import dev.local.catleaf.nativeapp.data.security.CredentialStore
import dev.local.catleaf.nativeapp.data.settings.SCOPE_KIND_INSTANCE
import dev.local.catleaf.nativeapp.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 实例连接：校验地址 → 用受保护接口探测 → 只在校验成功后保存。
 *
 * 验收①的关键在于**失败不覆盖旧配置**：探测失败时提前返回，不写 Room、不写 Keystore、
 * 不写 DataStore，原有实例照旧可用。
 */
class InstanceConnector(
    private val db: CatLeafDatabase,
    private val credentials: CredentialStore,
    private val settings: SettingsStore,
    /** 仅供测试注入，生产走 [HttpClients.forInstance] */
    private val clientFactory: (String) -> OkHttpClient = HttpClients::forInstance,
) {

    data class Connected(val instanceKey: String, val origin: String, val feedCount: Int)

    suspend fun connect(originRaw: String, secret: String): Result<Connected> = withContext(Dispatchers.IO) {
        if (secret.isBlank()) {
            return@withContext Result.failure(EndpointError.Invalid("密钥不能为空"))
        }

        val origin = EndpointPolicy.normalizeInstanceOrigin(originRaw).getOrElse {
            return@withContext Result.failure(it)
        }
        val instanceKey = EndpointPolicy.instanceKey(origin)

        // 探测：受保护接口必须拒绝无密钥请求，因此返回 200 才说明地址与密钥都对
        val request = Request.Builder()
            .url("$origin/api/feeds/list")
            .get()
            .header("Accept", "application/json")
            .build()

        val feedCount = try {
            clientFactory(secret).newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> response.body?.string()?.let { countFeeds(it) } ?: 0
                    401, 403 -> return@withContext Result.failure(EndpointError.Unauthorized())
                    503 -> return@withContext Result.failure(EndpointError.NotConfigured())
                    else -> return@withContext Result.failure(
                        EndpointError.Unreachable("服务返回 ${response.code}"),
                    )
                }
            }
        } catch (e: InterruptedIOException) {
            return@withContext Result.failure(EndpointError.Timeout())
        } catch (e: UnknownHostException) {
            return@withContext Result.failure(EndpointError.Unreachable("地址无法解析"))
        } catch (e: SSLException) {
            return@withContext Result.failure(EndpointError.Unreachable("TLS 校验失败"))
        } catch (e: IOException) {
            return@withContext Result.failure(EndpointError.Unreachable("网络不可用"))
        }

        // 到这里才写：先写凭据与实例，再切当前实例
        runCatching {
            credentials.put(secretNameFor(instanceKey), secret)
            db.instances().upsert(
                InstanceEntity(
                    instanceKey = instanceKey,
                    origin = origin,
                    displayName = origin.removePrefix("https://"),
                    lastConnectedAt = System.currentTimeMillis(),
                ),
            )
            settings.setCurrentInstanceKey(instanceKey)
            // 连接成功即把活动范围切到该实例并标记引导已处理（契约 8.1：选择已保存实例不需联网）
            settings.setActiveScopeKind(SCOPE_KIND_INSTANCE)
            settings.setOnboardingDone(true)
        }.onFailure { return@withContext Result.failure(EndpointError.Unreachable("本机保存失败：${it.message}")) }

        Result.success(Connected(instanceKey, origin, feedCount))
    }

    /** 换密钥保留数据：只改凭据，不动数据库 */
    suspend fun updateSecret(instanceKey: String, secret: String): Result<Unit> = withContext(Dispatchers.IO) {
        val instance = db.instances().get(instanceKey)
            ?: return@withContext Result.failure(EndpointError.Invalid("实例不存在"))
        connect(instance.origin, secret).map { }
    }

    suspend fun current(instanceKey: String?) = withContext(Dispatchers.IO) {
        if (instanceKey == null) return@withContext null
        val secret = runCatching { credentials.get(secretNameFor(instanceKey)) }.getOrNull()
        val instance = db.instances().get(instanceKey)
        if (instance == null || secret == null) null else instance to secret
    }

    private fun secretNameFor(instanceKey: String) = "instance_secret_$instanceKey"

    /** 只统计顶层数组元素个数，不解析字段：兼容后端现有返回体 */
    private fun countFeeds(json: String): Int {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return 0
        return trimmed.count { it == '{' }
    }
}