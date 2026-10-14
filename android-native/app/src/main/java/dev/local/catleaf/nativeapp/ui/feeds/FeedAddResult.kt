package dev.local.catleaf.nativeapp.ui.feeds

import dev.local.catleaf.nativeapp.data.network.EndpointError
import dev.local.catleaf.nativeapp.data.network.PublicFetch

/**
 * 新增订阅的结果页（原型 34，页面说明 §P03-34）：登记已成功之后的验证结论。
 * 登记失败不进结果页（保留表单输入）；实例登记超时属「提交结果待确认」，在表单内另行提示。
 */
enum class FeedAddOutcome {
    /** 已添加并验证 */
    Verified,

    /** 已保存，联网后验证：设备当前离线，抓取没有发生意义上的失败 */
    SavedOffline,

    /** 已添加，验证失败：在线但抓取/解析失败，条目保留可重试 */
    VerifyFailed,
}

data class FeedAddResult(
    val feedId: String,
    val title: String,
    val url: String,
    val outcome: FeedAddOutcome,
    /** 验证失败的原因（面向用户的短句）；成功时为 null */
    val reason: String?,
    /** 正在对同一 feedId 重新验证（不新增源） */
    val retrying: Boolean = false,
)

/** 网络类失败：离线时它们只说明「还没验证」，不是源本身有问题 */
internal fun isNetworkFailure(error: Throwable?): Boolean = when (error) {
    is EndpointError.Timeout, is EndpointError.Unreachable,
    is PublicFetch.FetchFailure.Timeout, is PublicFetch.FetchFailure.Unreachable -> true
    else -> false
}

/** 登记成功后的验证结论：离线 + 网络类失败 → 联网后验证；其余失败 → 验证失败 */
internal fun feedAddOutcomeOf(verified: Boolean, verifyError: Throwable?, online: Boolean): FeedAddOutcome = when {
    verified -> FeedAddOutcome.Verified
    !online && isNetworkFailure(verifyError) -> FeedAddOutcome.SavedOffline
    else -> FeedAddOutcome.VerifyFailed
}
