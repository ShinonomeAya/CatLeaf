package dev.local.catleaf.nativeapp.data.network

/**
 * 历史尽力写入（L07/契约 8.3）：实例范围的刷新把解析出的条目 upsert 到实例历史，
 * 失败不影响本地阅读；本机范围绝不调用。幂等语义由服务端 upsert 保证（T07 生产验证）。
 */

/** upsert 请求条目（服务端 HistoryItem 的原生侧子集，契约 3.2） */
data class HistoryUpsert(
    val title: String?,
    val guid: String?,
    val link: String?,
    val pubDate: String?,
    val description: String?,
    val content: String?,
    val thumbnail: String?,
)

/**
 * 手写 JSON（转义规则与 StateFile 相同的最小子集：引号/反斜杠/控制字符）。
 * ponytail: 单一调用点（ArticleRepository 刷新后），不引入序列化库。
 */
internal fun buildUpsertBody(feedId: String, items: List<HistoryUpsert>): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"feedId\":").append(jsonString(feedId)).append(',')
    sb.append("\"items\":[")
    items.forEachIndexed { i, item ->
        if (i > 0) sb.append(',')
        sb.append('{')
        sb.append("\"title\":").append(jsonString(item.title)).append(',')
        sb.append("\"guid\":").append(jsonString(item.guid)).append(',')
        sb.append("\"link\":").append(jsonString(item.link)).append(',')
        sb.append("\"pubDate\":").append(jsonString(item.pubDate)).append(',')
        sb.append("\"description\":").append(jsonString(item.description)).append(',')
        sb.append("\"content\":").append(jsonString(item.content)).append(',')
        sb.append("\"thumbnail\":").append(jsonString(item.thumbnail))
        sb.append('}')
    }
    sb.append("]}")
    return sb.toString()
}

private fun jsonString(value: String?): String {
    if (value == null) return "null"
    val sb = StringBuilder(value.length + 8)
    sb.append('"')
    for (c in value) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
