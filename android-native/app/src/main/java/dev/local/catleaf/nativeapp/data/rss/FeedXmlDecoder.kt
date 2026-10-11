package dev.local.catleaf.nativeapp.data.rss

import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 订阅 XML 字节 → 文本（契约 8.2）：按 BOM → 响应 Content-Type charset → XML 声明 encoding
 * 的顺序判定，都不存在才回退 UTF-8。**不能把所有 XML 强解 UTF-8**——中文站常见的
 * GBK/GB2312 声明必须能解。非法字节按错误上报，不用替换字符蒙混。
 */
object FeedXmlDecoder {

    sealed class DecodeError(message: String) : Exception(message) {
        class UnsupportedCharset(name: String) : DecodeError("不支持的编码：$name")
        class InvalidBytes(charset: String) : DecodeError("内容不是有效的 $charset 文本")
    }

    fun decode(bytes: ByteArray, contentType: String? = null): Result<String> {
        // 1. BOM 优先级最高
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                return decodeWith(bytes, Charsets.UTF_8, skip = 3)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                return decodeWith(bytes, Charsets.UTF_16LE, skip = 2)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                return decodeWith(bytes, Charsets.UTF_16BE, skip = 2)
        }

        // 2. 响应头 Content-Type 的 charset（如 text/xml; charset=gbk）
        contentType?.substringAfter("charset=", "")?.takeIf { it.isNotBlank() }?.let { raw ->
            val name = raw.trim().trimEnd(';').trim().trim('"', '\'')
            if (name.isNotEmpty()) return charsetByName(name).mapCatching { decodeWith(bytes, it, skip = 0).getOrThrow() }
        }

        // 3. XML 声明的 encoding（只看前几百字节，避免整篇解码）
        declaredEncoding(bytes)?.let { name ->
            return charsetByName(name).mapCatching { decodeWith(bytes, it, skip = 0).getOrThrow() }
        }

        // 4. 回退 UTF-8
        return decodeWith(bytes, Charsets.UTF_8, skip = 0)
    }

    private fun charsetByName(name: String): Result<java.nio.charset.Charset> = try {
        Result.success(java.nio.charset.Charset.forName(name))
    } catch (e: Exception) {
        Result.failure(DecodeError.UnsupportedCharset(name))
    }

    /** 只检查 ASCII 前缀里的 encoding="…"，值可能是单/双引号 */
    private fun declaredEncoding(bytes: ByteArray): String? {
        val head = bytes.decodeToString(0, minOf(bytes.size, 512))
        val declaration = head.substringBefore("?>")
        if (!declaration.trimStart().startsWith("<?xml")) return null
        val match = Regex("encoding\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(declaration) ?: return null
        return match.groupValues[1].trim()
    }

    private fun decodeWith(bytes: ByteArray, charset: java.nio.charset.Charset, skip: Int): Result<String> {
        val body = if (skip > 0) bytes.copyOfRange(skip, bytes.size) else bytes
        val text = try {
            charset.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(body))
                .toString()
        } catch (e: Exception) {
            return Result.failure(DecodeError.InvalidBytes(charset.name()))
        }
        return Result.success(text)
    }
}

/**
 * 本机范围的订阅抓取通道（契约 8.2）：复用 [PublicFetch] 的公开规则
 * （仅 HTTPS、逐跳校验、限额、无凭据），再按 [FeedXmlDecoder] 处理编码。
 * PublicFetch 是阻塞 IO，[fetchXml] 已切到 IO 线程。
 */
class LocalFeedFetcher(private val fetcher: PublicFetch = PublicFetch(HttpClients.forPublicFeed())) {

    suspend fun fetchXml(url: String): Result<String> = withContext(Dispatchers.IO) {
        fetcher.fetch(url).fold(
            onSuccess = { FeedXmlDecoder.decode(it.bytes, it.contentType) },
            onFailure = { Result.failure(it) },
        )
    }
}
