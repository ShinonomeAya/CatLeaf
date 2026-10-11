package dev.local.catleaf.nativeapp.ui.feeds

import dev.local.catleaf.nativeapp.data.network.EndpointError
import dev.local.catleaf.nativeapp.data.network.PublicFetch
import dev.local.catleaf.nativeapp.data.rss.FeedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原型 34 / 页面说明：登记成功后分「已添加并验证 / 已保存，联网后验证 / 已添加，验证失败」 */
class FeedAddResultTest {
    @Test fun verified_wins_regardless_of_network() {
        assertEquals(FeedAddOutcome.Verified, feedAddOutcomeOf(verified = true, verifyError = null, online = false))
    }

    @Test fun offline_network_failure_is_saved_for_later_not_failed() {
        assertEquals(
            FeedAddOutcome.SavedOffline,
            feedAddOutcomeOf(false, PublicFetch.FetchFailure.Unreachable("网络不可用"), online = false),
        )
        assertEquals(FeedAddOutcome.SavedOffline, feedAddOutcomeOf(false, EndpointError.Timeout(), online = false))
    }

    @Test fun online_failure_is_verify_failed_even_if_network_class() {
        // 在线却连不上（如 .invalid 解析失败）是源的问题，不能说成「联网后验证」
        assertEquals(
            FeedAddOutcome.VerifyFailed,
            feedAddOutcomeOf(false, PublicFetch.FetchFailure.Unreachable("地址无法解析"), online = true),
        )
    }

    @Test fun offline_parse_failure_is_still_verify_failed() {
        assertEquals(
            FeedAddOutcome.VerifyFailed,
            feedAddOutcomeOf(false, FeedParser.FeedParseError.NotAFeed(), online = false),
        )
    }

    @Test fun reasons_are_readable_and_do_not_leak_class_names() {
        assertEquals("无法连接：地址无法解析", describeVerifyError(PublicFetch.FetchFailure.Unreachable("地址无法解析")))
        assertEquals("订阅地址返回 404", describeVerifyError(PublicFetch.FetchFailure.Http(404)))
        assertEquals("该地址返回的是网页而非订阅内容", describeVerifyError(FeedParser.FeedParseError.HtmlInsteadOfXml()))
        assertEquals("订阅内容格式错误", describeVerifyError(FeedParser.FeedParseError.Malformed("x")))
        assertEquals("未知原因", describeVerifyError(null))
    }

    @Test fun network_failure_classification() {
        assertTrue(isNetworkFailure(PublicFetch.FetchFailure.Timeout()))
        assertTrue(isNetworkFailure(EndpointError.Unreachable("x")))
        assertFalse(isNetworkFailure(PublicFetch.FetchFailure.Http(500)))
        assertFalse(isNetworkFailure(null))
    }
}
