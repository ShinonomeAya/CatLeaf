package dev.local.catleaf.nativeapp.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地址规范化与目的地分类的纯逻辑检查（JVM，无需设备）。
 * 这些判定决定“密钥会不会被发错地方”，所以边界要覆盖到。
 */
class EndpointPolicyTest {

    // ── 实例 origin 规范化 ──────────────────────────────────────────

    @Test
    fun accepts_plain_https_origin_and_normalizes_it() {
        assertEquals(
            "https://reader.example.com",
            EndpointPolicy.normalizeInstanceOrigin("https://reader.example.com").getOrNull(),
        )
        assertEquals(
            "尾部斜杠应被归一",
            "https://reader.example.com",
            EndpointPolicy.normalizeInstanceOrigin("https://reader.example.com/").getOrNull(),
        )
        assertEquals(
            "主机名大小写应归一",
            "https://reader.example.com",
            EndpointPolicy.normalizeInstanceOrigin("HTTPS://Reader.Example.COM").getOrNull(),
        )
        assertEquals(
            "默认端口不应写出来",
            "https://reader.example.com",
            EndpointPolicy.normalizeInstanceOrigin("https://reader.example.com:443").getOrNull(),
        )
        assertEquals(
            "非默认端口应保留",
            "https://reader.example.com:8443",
            EndpointPolicy.normalizeInstanceOrigin("https://reader.example.com:8443").getOrNull(),
        )
    }

    @Test
    fun rejects_insecure_or_dangerous_instance_addresses() {
        for (bad in listOf(
            "http://reader.example.com",                       // 非 HTTPS
            "https://user:pass@reader.example.com",            // 带凭据
            "https://reader.example.com?token=abc",            // 带 query
            "https://reader.example.com#frag",                 // 带片段
            "https://reader.example.com/subpath",              // 子路径部署不支持
            "https://reader.example.com/subpath/",
            "https://localhost",                               // 本机
            "https://127.0.0.1",                               // 回环
            "https://10.1.2.3",                                // 私网
            "https://192.168.1.10",                            // 私网
            "https://172.16.5.4",                              // 私网
            "https://169.254.1.1",                             // 链路本地
            "https://[::1]",                                   // IPv6 回环
            "",
            "   ",
            "reader.example.com",                              // 缺 scheme
        )) {
            assertTrue(
                "应拒绝：$bad",
                EndpointPolicy.normalizeInstanceOrigin(bad).isFailure,
            )
        }
    }

    // ── AI base URL ────────────────────────────────────────────────

    @Test
    fun ai_base_url_allows_path_but_not_credentials_or_query() {
        assertEquals(
            "https://api.example.com/v1",
            EndpointPolicy.normalizeAiBaseUrl("https://api.example.com/v1/").getOrNull(),
        )
        assertEquals(
            "https://api.example.com",
            EndpointPolicy.normalizeAiBaseUrl("https://api.example.com").getOrNull(),
        )
        assertTrue(EndpointPolicy.normalizeAiBaseUrl("http://api.example.com").isFailure)
        assertTrue(EndpointPolicy.normalizeAiBaseUrl("https://k:secret@api.example.com").isFailure)
        assertTrue(EndpointPolicy.normalizeAiBaseUrl("https://api.example.com?key=1").isFailure)
        assertTrue(EndpointPolicy.normalizeAiBaseUrl("https://localhost:11434").isFailure)
    }

    // ── instanceKey ───────────────────────────────────────────────

    @Test
    fun instance_key_is_stable_and_origin_scoped() {
        val a = EndpointPolicy.instanceKey("https://reader.example.com")
        val again = EndpointPolicy.instanceKey("https://reader.example.com")
        val other = EndpointPolicy.instanceKey("https://other.example.com")
        val otherPort = EndpointPolicy.instanceKey("https://reader.example.com:8443")

        assertEquals("同一 origin 必须得到同一个 key", a, again)
        assertNotEquals("不同 origin 必须是不同 namespace", a, other)
        assertNotEquals("不同端口是不同实例", a, otherPort)
        assertEquals("长度固定，便于入库", 32, a.length)
    }

    // ── 目的地分类 ─────────────────────────────────────────────────

    @Test
    fun destination_classification_isolates_credentials() {
        val instance = "https://reader.example.com"
        val ai = "https://api.example.com/v1"

        assertEquals(
            Destination.INSTANCE,
            EndpointPolicy.destinationOf("https://reader.example.com/api/feeds/list", instance, ai),
        )
        assertEquals(
            Destination.AI,
            EndpointPolicy.destinationOf("https://api.example.com/v1/chat/completions", instance, ai),
        )
        assertEquals(
            "RSS 原站属于公开抓取",
            Destination.PUBLIC,
            EndpointPolicy.destinationOf("https://blog.example.org/feed.xml", instance, ai),
        )
        assertEquals(
            "相似域名不得被当成实例",
            Destination.PUBLIC,
            EndpointPolicy.destinationOf("https://reader.example.com.evil.test/api/feeds/list", instance, ai),
        )
        assertEquals(
            "不同端口不得被当成实例",
            Destination.PUBLIC,
            EndpointPolicy.destinationOf("https://reader.example.com:8443/api/feeds/list", instance, ai),
        )
        assertEquals(
            "未配置 AI 时不得把 AI 供应商当命中",
            Destination.PUBLIC,
            EndpointPolicy.destinationOf("https://api.example.com/v1/models", instance, null),
        )
        assertEquals(
            "未配置实例时不得把实例当命中",
            Destination.PUBLIC,
            EndpointPolicy.destinationOf("https://reader.example.com/api/feeds/list", null, ai),
        )
    }

    @Test
    fun public_url_allows_only_http_and_https() {
        assertTrue(EndpointPolicy.isSafePublicUrl("https://blog.example.org/a"))
        assertTrue(EndpointPolicy.isSafePublicUrl("http://blog.example.org/a"))
        for (bad in listOf(
            "javascript:alert(1)",
            "intent://evil#Intent;scheme=http;end",
            "file:///etc/passwd",
            "data:text/html;base64,PHNjcmlwdD4=",
            "https://user:pw@blog.example.org/a",
        )) {
            assertFalse("应拒绝：$bad", EndpointPolicy.isSafePublicUrl(bad))
        }
    }

    @Test
    fun redirect_must_stay_on_the_same_origin_for_credentialed_requests() {
        val origin = "https://reader.example.com"
        assertTrue(EndpointPolicy.sameOriginRedirectAllowed(origin, "https://reader.example.com/api/other"))
        assertFalse(
            "跨 origin 跳转不得携带密钥",
            EndpointPolicy.sameOriginRedirectAllowed(origin, "https://evil.example.com/api/other"),
        )
        assertFalse(
            "降级到 http 不得携带密钥",
            EndpointPolicy.sameOriginRedirectAllowed(origin, "http://reader.example.com/api/other"),
        )
    }

    // ── 公开抓取目标（T12：文章原文与正文图片） ──────────────────────

    @Test
    fun public_fetch_target_requires_https_and_a_real_host() {
        assertEquals(
            "https 目标放行",
            "https://blog.example.org/posts/1?x=2",
            EndpointPolicy.requirePublicFetchTarget("https://blog.example.org/posts/1?x=2").getOrNull(),
        )
        for (bad in listOf(
            "http://blog.example.org/posts/1",
            "//blog.example.org/posts/1",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "https://",
            "",
        )) {
            assertTrue("应拒绝公开抓取目标：$bad", EndpointPolicy.requirePublicFetchTarget(bad).isFailure)
        }
        assertTrue("null 目标应拒绝", EndpointPolicy.requirePublicFetchTarget(null).isFailure)
    }

    @Test
    fun public_fetch_target_rejects_credentials_and_internal_hosts() {
        for (bad in listOf(
            "https://user:pw@blog.example.org/posts/1",
            "https://localhost/posts/1",
            "https://127.0.0.1/posts/1",
            "https://10.1.2.3/posts/1",
            "https://192.168.1.10/posts/1",
            "https://172.20.0.9/posts/1",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/posts/1",
            "https://service.internal/posts/1",
            "https://printer.local/posts/1",
        )) {
            assertTrue("应拒绝（本机/内网/凭据）：$bad", EndpointPolicy.requirePublicFetchTarget(bad).isFailure)
        }
    }
}