package dev.local.catleaf.nativeapp.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 三类隔离的 HTTP 客户端。凭据的隔离靠**器具隔离**而不是条件分支：
 * 只有实例 client 会附加 `x-admin-secret`，只有 AI client 会附加 `Authorization`，
 * 公开 client 没有任何凭据拦截器。调用方按 [EndpointPolicy.destinationOf] 选 client。
 *
 * 带凭据的两个 client 都关闭自动重定向：否则 302 到别的 origin 会把密钥一起转发过去。
 * 公开抓取也关闭自动重定向，改为手动逐跳校验（见 [PublicFetch]）。
 */
object HttpClients {

    const val GENERAL_TIMEOUT_SECONDS = 15L
    const val AI_TIMEOUT_SECONDS = 60L
    const val FEED_DOWNLOAD_TIMEOUT_SECONDS = 60L

    /**
     * 指向用户实例的 client。调用方必须已经确认目标 origin 等于已配置实例 origin。
     */
    fun forInstance(secret: String): OkHttpClient =
        base(GENERAL_TIMEOUT_SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("x-admin-secret", secret)
                        .build(),
                )
            }
            .build()

    /**
     * 指向用户 AI 供应商的 client。base URL 可含路径；这里只加认证头，不改路径。
     */
    fun forAi(apiKey: String): OkHttpClient =
        base(AI_TIMEOUT_SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $apiKey")
                        .build(),
                )
            }
            .build()

    /**
     * 公开抓取（RSS 原站、文章原文、图片）：不带任何 App 凭据。
     */
    fun forPublic(): OkHttpClient = base(GENERAL_TIMEOUT_SECONDS).build()

    /** RSS may keep downloading beyond 15s; idle reads and connections retain the 15s limit. */
    fun forPublicFeed(): OkHttpClient = base(GENERAL_TIMEOUT_SECONDS)
        .callTimeout(FEED_DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    private fun base(timeoutSeconds: Long): OkHttpClient.Builder =
        OkHttpClient.Builder()
            // 带凭据与公开抓取都手动处理跳转，避免密钥跨 origin 转发、也便于逐跳校验目标
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            // 生产环境不关闭 TLS 校验、不信任自定义证书
            .retryOnConnectionFailure(true)

    /** 构造一个不带任何凭据头的请求，供各 client 复用 */
    fun request(url: String): Request = Request.Builder().url(url).get().build()
}
