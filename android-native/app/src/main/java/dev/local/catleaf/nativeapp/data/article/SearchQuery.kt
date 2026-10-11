package dev.local.catleaf.nativeapp.data.article

/**
 * 搜索查询的纯规则（Q01/Q02，契约 9.1）：输入不是 SQL 或 FTS 表达式。
 * - 关键词去两端空白、最多 100 字符（超限拒绝，不静默截断）；
 * - 转义 LIKE 的 `\ % _`（SQLite 无默认转义符，需 `ESCAPE '\'` 配套）；
 * - ASCII 英文不区分大小写（SQL LOWER 只折叠 ASCII，中文原样——连续子串可命中）；
 * - 空词不扫描。
 */
object SearchQuery {

    const val MAX_LENGTH = 100

    /** 规范化 + 校验：null=空词（不扫描）；失败=超限（界面明确提示） */
    fun normalize(raw: String?): Result<String?> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result.success(null)
        if (trimmed.length > MAX_LENGTH) {
            return Result.failure(IllegalArgumentException("关键词最多 $MAX_LENGTH 字符"))
        }
        return Result.success(trimmed)
    }

    /** LIKE 模式串：转义 `\ % _`，ASCII 统一小写（配合 SQL 的 LOWER，中文不受影响） */
    fun likePattern(normalized: String): String =
        normalized.lowercase()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    /** Full saved text, without markup or a silent character cutoff. */
    fun bodyText(html: String): String =
        org.jsoup.Jsoup.parseBodyFragment(html).text().replace('\u00a0', ' ').trim()
}
