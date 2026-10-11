package dev.local.catleaf.nativeapp.data.article

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 发布日期规范化：把 RSS/Atom 的各种写法统一成 ISO-8601 UTC 字符串。
 *
 * 契约 5.3 要求“日期按设备本地时区计算 00:00 至次日 00:00；缺失/无效发布时间的文章进入‘日期未知’”，
 * 所以无法解析时必须返回 null，**不能**用当前时间顶替（那会把旧文章塞进今天）。
 */
object DateNormalizer {

    /** 常见 RSS/Atom 日期格式；按出现频率排序，命中即止 */
    private val PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",       // RFC 822（RSS 主流）
        "EEE, dd MMM yyyy HH:mm Z",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",          // ISO-8601（Atom 主流）
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    fun toIsoUtc(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 先试 ISO-8601 直接解析（覆盖带偏移与 Z 的绝大多数）
        parseIso(text)?.let { return it }

        for (pattern in PATTERNS) {
            val parsed = try {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    // 不带时区信息的模式按 UTC 解释，避免用设备时区猜测
                    if (!pattern.contains("Z") && !pattern.contains("XXX")) {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                }.parse(text)
            } catch (e: Exception) {
                null
            }
            if (parsed != null) return formatUtc(parsed.time)
        }
        return null
    }

    private fun parseIso(text: String): String? = try {
        val instant = java.time.Instant.parse(text)
        formatUtc(instant.toEpochMilli())
    } catch (e: Exception) {
        try {
            val offset = java.time.OffsetDateTime.parse(text)
            formatUtc(offset.toInstant().toEpochMilli())
        } catch (e2: Exception) {
            null
        }
    }

    private fun formatUtc(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(java.util.Date(millis))
    }

    /** 把 ISO UTC 字符串转成设备本地时区的“日期”字符串（yyyy-MM-dd），供按日筛选与摘要使用 */
    fun localDateOf(isoUtc: String?, zone: TimeZone = TimeZone.getDefault()): String? {
        if (isoUtc.isNullOrBlank()) return null
        return try {
            val millis = java.time.Instant.parse(isoUtc).toEpochMilli()
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
            fmt.format(java.util.Date(millis))
        } catch (e: Exception) {
            null
        }
    }
}