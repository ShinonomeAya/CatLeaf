package dev.local.catleaf.nativeapp.data.article

import dev.local.catleaf.nativeapp.data.network.PublicFetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本机范围刷新走公开抓取：失败提示要说明「旧内容仍在」，不能直接露出底层异常文本（原型 21） */
class RefreshErrorTextTest {
    @Test fun public_fetch_unreachable_reads_as_offline_with_saved_content() {
        assertEquals("无法连接，正在显示已保存内容", describeRefreshError(PublicFetch.FetchFailure.Unreachable("地址无法解析")))
    }

    @Test fun public_fetch_timeout_keeps_saved_content_wording() {
        assertEquals("请求超时，正在显示已保存内容", describeRefreshError(PublicFetch.FetchFailure.Timeout()))
    }

    @Test fun public_fetch_http_error_names_status_and_keeps_saved_content() {
        val text = describeRefreshError(PublicFetch.FetchFailure.Http(404))
        assertTrue(text, text.contains("404") && text.contains("已保存内容"))
    }
}
