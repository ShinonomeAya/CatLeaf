package dev.local.catleaf.nativeapp.ui.settings

import dev.local.catleaf.nativeapp.data.network.AiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 契约 5.1：未配置、输入不合法时**本地拦截**，不发请求也不写成功缓存。
 * 这条规则决定「测试连接」会不会在没填全的情况下把请求发出去，所以要钉住。
 */
class AiConfigRulesTest {

    @Test
    fun complete_config_passes() {
        assertNull(
            "三项齐全时应放行",
            aiConfigBlockReason("https://api.example.com/v1", "model-x", "sk-test"),
        )
    }

    @Test
    fun missing_fields_are_blocked_locally_with_readable_reason() {
        assertEquals("缺服务地址", "请先填写服务地址", aiConfigBlockReason("", "model-x", "sk-test"))
        assertEquals("缺模型", "请先填写模型 ID", aiConfigBlockReason("https://api.example.com/v1", "  ", "sk-test"))
        assertEquals("缺 Key", "请先填写 API Key", aiConfigBlockReason("https://api.example.com/v1", "model-x", ""))
    }

    @Test
    fun illegal_base_url_is_blocked_not_sent() {
        for (bad in listOf(
            "http://api.example.com",             // 非 HTTPS
            "https://user:pw@api.example.com",    // 带凭据
            "https://127.0.0.1/v1",               // 本机
            "not a url",
            "https://api.example.com/?a=1",       // 带查询参数
        )) {
            val reason = aiConfigBlockReason(bad, "model-x", "sk-test")
            assertNotNull("$bad 应在本地被拦下", reason)
            assertFalse("理由要能看懂：$reason", reason!!.isBlank())
        }
    }

    @Test
    fun base_url_with_path_is_allowed_and_v1_is_not_duplicated() {
        // 供应商文档常写 .../v1；客户端只追加 /chat/completions，不猜是否补 /v1
        assertNull(
            "带 /v1 的地址应放行",
            aiConfigBlockReason("https://api.deepseek.com/v1", "deepseek-chat", "sk-test"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiClient.endpointFor("https://api.deepseek.com/v1"),
        )
    }
}