package dev.local.catleaf.nativeapp.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.ManualProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 一次真实 AI 连通（G1 的“一次实际 AI 连通”）。
 *
 * 需要真实凭据，因此标记 [ManualProbe]，默认套件跳过。运行方式：
 *
 *   adb shell am instrument -w \
 *     -e class 'dev.local.catleaf.nativeapp.data.network.AiLiveProbeTest#real_request' \
 *     -e aiBaseUrl <base> -e aiModel <model> -e aiKey <key> \
 *     dev.local.catleaf.nativeapp.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 凭据只从 instrumentation 参数读入内存，不写文件、不入库、不打日志。
 */
@ManualProbe
@RunWith(AndroidJUnit4::class)
class AiLiveProbeTest {

    @Test
    fun real_request() {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("aiBaseUrl")
        val model = args.getString("aiModel")
        val key = args.getString("aiKey")

        assertFalse("缺少 aiBaseUrl 参数", baseUrl.isNullOrBlank())
        assertFalse("缺少 aiModel 参数", model.isNullOrBlank())
        assertFalse("缺少 aiKey 参数", key.isNullOrBlank())

        // 最小请求：只发一句，要求原样回显，便于判断协议与解析都通
        val result = runBlocking {
            AiClient().chat(baseUrl!!, model!!, key!!, "只回复两个字：收到")
        }

        val content = result.getOrNull()?.content
        assertTrue("真实请求应成功，实际：${result.exceptionOrNull()?.message}", result.isSuccess)
        assertFalse("返回内容不应为空", content.isNullOrBlank())
        assertTrue("应回显预期文本，实际：$content", content!!.contains("收到"))
    }
}
