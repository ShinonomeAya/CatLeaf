package dev.local.catleaf.nativeapp.data.network

import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.UnknownHostException

/**
 * 公开抓取的受控通道（契约 3.3）：文章全文 HTML 与正文图片都从这里出去，
 * **不带任何 App 凭据**（用 [HttpClients.forPublic] 建的 client），并在每一跳上重复校验：
 * 仅 HTTPS、不含凭据、非本机/内网、最多 3 次跳转、响应字节有上限。
 *
 * 为什么不用 OkHttp 的自动跳转：自动跳转会直接把请求发到 302 指向的地址，
 * 跳转链上的目标就没有地方能拦下来；契约要求“每一跳均校验”，因此这里手动循环。
 * 与后端 `server/http.ts` 的 `secureFetch` 是同一套规则（后端负责服务端抓取）。
 */
class PublicFetch(
    private val client: OkHttpClient = HttpClients.forPublic(),
    private val maxRedirects: Int = MAX_REDIRECTS,
    /** 测试注入点；生产始终用 [EndpointPolicy.requirePublicFetchTarget] */
    private val targetPolicy: (String) -> Result<String> = EndpointPolicy::requirePublicFetchTarget,
) {

    data class Fetched(
        val bytes: ByteArray,
        val contentType: String?,
    )

    sealed class FetchFailure(message: String) : Exception(message) {
        class Blocked(message: String) : FetchFailure(message)
        class TooLarge : FetchFailure("响应超出大小上限")
        class Http(val code: Int) : FetchFailure("服务返回 $code")
        class Timeout : FetchFailure("请求超时")
        class Unreachable(detail: String) : FetchFailure("无法连接：$detail")
    }

    /**
     * 抓取一个公开资源。返回原始字节，字符集交给调用方（HTML 需要嗅探 meta）。
     * 传输过程中也记账，不只看 `content-length`：截断或未声明长度的响应同样受上限约束。
     */
    fun fetch(url: String, maxBytes: Int = MAX_BYTES, articleUrl: String? = null): Result<Fetched> {
        // Only the public article origin is sent; never forward its path, query, or credentials.
        val referer = EndpointPolicy.requirePublicFetchTarget(articleUrl).getOrNull()?.let {
            val source = URI(it)
            URI("https", null, source.host, source.port, "/", null, null).toASCIIString()
        }
        var current = url
        for (hop in 0..maxRedirects) {
            val target = targetPolicy(current).getOrElse { return Result.failure(it) }

            val response = try {
                val request = HttpClients.request(target).newBuilder().apply {
                    referer?.let { header("Referer", it) }
                }.build()
                client.newCall(request).execute()
            } catch (e: InterruptedIOException) {
                return Result.failure(FetchFailure.Timeout())
            } catch (e: UnknownHostException) {
                return Result.failure(FetchFailure.Unreachable("地址无法解析"))
            } catch (e: IOException) {
                return Result.failure(FetchFailure.Unreachable("网络不可用"))
            }

            response.use { r ->
                if (r.code in 300..399) {
                    val location = r.header("Location")?.trim()
                        ?: return Result.failure(FetchFailure.Http(r.code))
                    if (hop == maxRedirects) {
                        return Result.failure(FetchFailure.Blocked("跳转次数超过上限（$maxRedirects）"))
                    }
                    // 相对 Location 也按当前目标解析；下一跳会重新过一遍策略
                    current = runCatching { URI(target).resolve(location).toString() }
                        .getOrElse { return Result.failure(FetchFailure.Blocked("跳转目标无效")) }
                    return@use
                }
                if (r.code !in 200..299) return Result.failure(FetchFailure.Http(r.code))

                val body = r.body ?: return Result.failure(FetchFailure.Http(r.code))
                if (body.contentLength() > maxBytes) return Result.failure(FetchFailure.TooLarge())
                val bytes = try {
                    readAtMost(body.byteStream(), maxBytes)
                } catch (e: InterruptedIOException) {
                    return Result.failure(FetchFailure.Timeout())
                } catch (e: IOException) {
                    return Result.failure(FetchFailure.Unreachable("读取响应失败"))
                } ?: return Result.failure(FetchFailure.TooLarge())

                return Result.success(Fetched(bytes, r.header("Content-Type")))
            }
        }
        return Result.failure(FetchFailure.Blocked("跳转次数超过上限（$maxRedirects）"))
    }

    /** 读满 maxBytes 还没结束就返回 null（超限），避免把超大响应读进内存 */
    private fun readAtMost(stream: java.io.InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > maxBytes) return null
            out.write(buffer, 0, read)
        }
    }

    companion object {
        /** 契约 3.3：提取最多 5 MiB，最多 3 次受控跳转 */
        const val MAX_BYTES = 5 * 1024 * 1024
        const val MAX_REDIRECTS = 3

        /** 单张正文图片的上限：图片不承诺缓存，超过就占位 */
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    }
}
