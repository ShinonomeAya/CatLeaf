package dev.local.catleaf.nativeapp.data.article

import java.net.URI
import java.security.MessageDigest

/**
 * 文章身份（契约 4.2）。纯计算，无 Android 依赖，便于单测覆盖边界。
 *
 * 规则：
 * - 同一实例、同一 feedId 内**先按非空 GUID 匹配，再按安全绝对原文 URL 匹配**；
 * - 匹配上已有记录时**保持原 localId**，不因这次用哪个键命中而改；
 * - 新记录的 localId 取两者中可用者做**带类型前缀的稳定哈希**，不用标题或数组下标；
 * - GUID 是不透明字符串：只去两侧空白、**不改大小写**；
 * - URL 用标准解析消除默认端口、规范主机，**保留 path/query**（不删追踪参数，避免合并不同内容）；
 * - 两者都缺 → 返回 null，由调用方计入“缺少标识未保存”。
 */
object ArticleIdentity {

    /** 标识命中信息：命中的是哪种键，以及（可选）冲突的另一条记录 */
    sealed class Resolution {
        data class Matched(val byGuid: Boolean) : Resolution()
        data class New(val byGuid: Boolean) : Resolution()

        /**
         * GUID 命中一条记录、link 命中**另一条**记录：契约要求记录冲突、保留原状态、
         * 不自动合并删除，界面显示部分条目异常。
         */
        data class Conflict(val guidLocalId: String, val linkLocalId: String) : Resolution()

        object Unidentified : Resolution()
    }

    /** GUID 的规范化：只去两侧空白，不改大小写（契约明确） */
    fun normalizeGuid(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 原文链接的安全绝对 URL：只接受 http/https、无凭据、有主机名。
     * 返回规范化字符串（小写 scheme/主机、去默认端口、丢片段），path 与 query 原样保留。
     */
    fun normalizeLink(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val uri = try {
            URI(raw.trim())
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.userInfo != null) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val defaultPort = (scheme == "https" && (uri.port == -1 || uri.port == 443)) ||
            (scheme == "http" && (uri.port == -1 || uri.port == 80))
        val port = if (defaultPort) "" else ":${uri.port}"
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return "$scheme://$host$port${uri.rawPath.orEmpty()}$query"
    }

    /** 新记录的 localId：带类型前缀的稳定哈希。两者都缺返回 null */
    fun localIdFor(instanceKey: String, feedId: String, guid: String?, link: String?): String? {
        val g = normalizeGuid(guid)
        if (g != null) return "g:" + hash("$instanceKey|$feedId|$g")
        val l = normalizeLink(link)
        if (l != null) return "l:" + hash("$instanceKey|$feedId|$l")
        return null
    }

    /**
     * 正文内容指纹：用于判断是否需要更新、以及 AI 缓存的键。
     * 空内容返回空串（不产生“有指纹但无内容”的歧义）。
     */
    fun contentFingerprint(content: String?): String =
        if (content.isNullOrEmpty()) "" else hash(content)

    /** 发布时间的规范化：有效日期统一成 ISO-8601 UTC，无法解析返回 null（进“日期未知”） */
    fun normalizePublishedAt(raw: String?): String? = DateNormalizer.toIsoUtc(raw)

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        // 截断到 32 个十六进制字符：足够避免碰撞，且便于入库与日志
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}