package dev.local.catleaf.nativeapp.data.network

import java.net.URI
import java.security.MessageDigest

/**
 * 目的地分类与地址规范化。契约第 2 节：
 * 实例 / AI / 公开原站三类请求必须严格区分凭据，任何一类都不能串。
 *
 * 这里只做纯计算，不发请求，便于单测覆盖边界。
 */
enum class Destination { INSTANCE, AI, PUBLIC }

object EndpointPolicy {

    /** 规范化后的实例 origin，例如 `https://reader.example.com` */
    fun normalizeInstanceOrigin(raw: String): Result<String> =
        normalize(raw, allowPath = false).map { it.trimEnd('/') }

    /**
     * AI base URL 允许带路径（例如供应商要求的 `/v1`），但不能带凭据、query 或片段。
     * 返回去掉末尾 `/` 的结果，调用方只追加 `/chat/completions`，不猜是否补 `/v1`。
     */
    fun normalizeAiBaseUrl(raw: String): Result<String> =
        normalize(raw, allowPath = true).map { it.trimEnd('/') }

    private fun normalize(raw: String, allowPath: Boolean): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.failure(EndpointError.Invalid("地址不能为空"))

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return Result.failure(EndpointError.Invalid("地址格式不正确"))
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") {
            return Result.failure(EndpointError.Invalid("只接受 HTTPS 地址"))
        }
        if (uri.userInfo != null) {
            return Result.failure(EndpointError.Invalid("地址不能包含用户名或密码"))
        }
        if (uri.query != null) {
            return Result.failure(EndpointError.Invalid("地址不能包含查询参数"))
        }
        if (uri.fragment != null) {
            return Result.failure(EndpointError.Invalid("地址不能包含 # 片段"))
        }
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) {
            return Result.failure(EndpointError.Invalid("地址缺少主机名"))
        }
        if (isForbiddenHost(host)) {
            return Result.failure(EndpointError.Invalid("不接受本机或内网地址"))
        }

        val path = uri.path.orEmpty()
        if (!allowPath && path.isNotEmpty() && path != "/") {
            return Result.failure(EndpointError.Invalid("实例只接受 origin，不支持子路径部署"))
        }

        val normalizedPath = if (allowPath) path.trimEnd('/') else ""
        // 默认端口不写出来，非默认端口保留
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        return Result.success("https://$host$port$normalizedPath")
    }

    /** 实例、AI 与公开抓取都不应指向本机或内网（契约 3.3 的同类约束） */
    private fun isForbiddenHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal")
        ) {
            return true
        }
        // 字面量 IPv6 直接拒绝，避免混合表示绕过；URI 的 host 形如 [::1] 或已去括号的 ::1
        if (host.contains(':')) return true
        return isPrivateIpv4(host)
    }

    /**
     * 按八位组做数值判断，而不是拼正则：正则很容易漏段
     * （第一版写成 `^(0\.|10\.|...)$`，等于要求整串只有 "127." 才算命中，
     * 结果 https://127.0.0.1 被放行）。
     */
    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return false }
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 0 || a == 10 || a == 127 -> true   // 本网络 / 私网 / 回环
            a == 169 && b == 254 -> true            // 链路本地
            a == 172 && b in 16..31 -> true         // 私网
            a == 192 && b == 168 -> true            // 私网
            a == 100 && b in 64..127 -> true        // 运营商级 NAT
            a == 198 && b in 18..19 -> true         // 基准测试网段
            a >= 224 -> true                        // 组播与保留
            else -> false
        }
    }

    /**
     * instanceKey：规范化 origin 的稳定哈希。换密钥不换 namespace，换 origin 才是新实例。
     */
    fun instanceKey(normalizedOrigin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedOrigin.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * 判定一个目标 URL 属于哪一类目的地。未配置实例/AI 时相应目标不算命中，
     * 避免“还没配 AI 就把密钥发出去”这类情形。
     */
    fun destinationOf(target: String, instanceOrigin: String?, aiBaseUrl: String?): Destination {
        val host = runCatching {
            val uri = URI(target)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && scheme != "http") return Destination.PUBLIC
            val h = uri.host?.lowercase() ?: return Destination.PUBLIC
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            "$scheme://$h$port"
        }.getOrNull() ?: return Destination.PUBLIC

        // 已经是规范化 origin 的字符串比较，避免把 reader.example.com.evil.com 判成实例
        instanceOrigin?.let { if (host == it) return Destination.INSTANCE }
        aiBaseUrl?.let {
            val aiOrigin = originOf(it)
            if (aiOrigin != null && host == aiOrigin) return Destination.AI
        }
        return Destination.PUBLIC
    }

    /**
     * 订阅 URL 的**保守**规范化，用于 OPML 去重（契约 5.1/6）。
     *
     * 与后端 `server/security.ts` 的 `normalizeFeedUrl` 保持同一套规则：
     * 只归一 scheme/主机大小写、去掉默认端口、丢掉片段；
     * **不动** path（含尾斜杠）、**不删** query、不重写百分号编码。
     * 宁可把两个其实相同的地址当成不同，也不要把不同内容合并。
     *
     * 仅接受 HTTPS；无法解析或非 HTTPS 返回 null。
     */
    fun normalizeFeedUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val uri = try {
            URI(raw.trim())
        } catch (e: Exception) {
            return null
        }
        if (uri.scheme?.lowercase() != "https") return null
        if (uri.userInfo != null) return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isBlank()) return null
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        // rawQuery 保留原始编码与参数顺序；不给 query 排序，也不删参数
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return "https://$host$port${uri.rawPath.orEmpty()}$query"
    }

    /** 两个订阅 URL 是否指向同一目标 */
    fun isSameFeedUrl(a: String, b: String): Boolean {
        val na = normalizeFeedUrl(a)
        val nb = normalizeFeedUrl(b)
        if (na == null || nb == null) return a.trim() == b.trim()
        return na == nb
    }

    /** 取任意 https URL 的 origin；用于同源比较，不要求目标本身是裸 origin */
    fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme?.lowercase() != "https") return null
        val h = uri.host?.lowercase() ?: return null
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        "https://$h$port"
    }.getOrNull()

    /** 只允许 http/https，其余（javascript:、intent:、file: 等）一律不接受 */
    fun isSafePublicUrl(target: String): Boolean = runCatching {
        val uri = URI(target)
        val scheme = uri.scheme?.lowercase()
        (scheme == "https" || scheme == "http") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }.getOrDefault(false)

    /**
     * 公开抓取（文章原文、图片）的目标校验，与后端 `server/security.ts` 的
     * `requireFetchTarget` 同一套规则：仅 HTTPS、不含凭据、非本机/内网。
     * **每一跳都要调用它**，不能只检查第一跳。
     */
    fun requirePublicFetchTarget(raw: String?): Result<String> {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return Result.failure(EndpointError.Invalid("目标地址为空"))
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return Result.failure(EndpointError.Invalid("目标地址格式不正确"))
        }
        if (uri.scheme?.lowercase() != "https") {
            return Result.failure(EndpointError.Invalid("只接受 HTTPS 的公开抓取目标"))
        }
        if (uri.userInfo != null) {
            return Result.failure(EndpointError.Invalid("目标地址不能包含凭据"))
        }
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) return Result.failure(EndpointError.Invalid("目标地址缺少主机名"))
        if (isForbiddenHost(host)) return Result.failure(EndpointError.Invalid("不接受本机或内网目标"))
        return Result.success(uri.toASCIIString())
    }

    /**
     * 跳转目标安全校验：只允许 https，且 origin 必须与起点相同（用于带凭据请求）。
     * 比较的是**目标的 origin**，而不是把目标当裸 origin 去校验——否则同一实例下的
     * `/api/other` 这类正常跳转会被误判成“不支持子路径”而拒绝。
     */
    fun sameOriginRedirectAllowed(fromOrigin: String, redirectTarget: String): Boolean =
        originOf(redirectTarget) != null && originOf(redirectTarget) == fromOrigin
}

/** 地址或配置不可用时对外暴露的错误分类，UI 按此给不同提示 */
sealed class EndpointError(message: String) : Exception(message) {
    class Invalid(message: String) : EndpointError(message)
    class Unauthorized : EndpointError("密钥无效或已失效")

    /**
     * 后端拒绝抓取该目标。与 [Unauthorized] 分开是因为后端用 **403** 表示
     * “这个地址不在允许集合里”（契约 3.3：未登记源主机、受限地址），
     * 用 401 才表示密钥问题；混在一起会把“该站点抓不了”说成“密钥失效”。
     */
    class Forbidden(detail: String = "后端不允许抓取该地址") : EndpointError(detail)
    class NotConfigured : EndpointError("后端未配置")
    class Unreachable(detail: String) : EndpointError("无法连接：$detail")
    class Timeout : EndpointError("连接超时")
}