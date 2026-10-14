package dev.local.catleaf.nativeapp.data.network

import dev.local.catleaf.nativeapp.data.rss.FeedParser

/** 验证失败原因：本机公开抓取与实例两条路径同一口径，不直接露出底层异常类名 */
fun describeVerifyError(error: Throwable?): String = (when (error) {
    null -> "未知原因"
    is PublicFetch.FetchFailure.Unreachable -> error.message ?: "无法连接"
    is PublicFetch.FetchFailure.Timeout -> "请求超时"
    is PublicFetch.FetchFailure.Http -> "订阅地址返回 ${error.code}"
    is PublicFetch.FetchFailure.TooLarge -> "订阅内容超出大小上限"
    is PublicFetch.FetchFailure.Blocked -> error.message ?: "不接受这个地址"
    is FeedParser.FeedParseError.HtmlInsteadOfXml -> "该地址返回的是网页而非订阅内容"
    is FeedParser.FeedParseError.NotAFeed -> "该地址不是 RSS/Atom 订阅"
    is FeedParser.FeedParseError.Malformed -> "订阅内容格式错误"
    is EndpointError.Timeout -> "请求超时"
    is EndpointError.Unreachable -> error.message ?: "无法连接"
    is EndpointError.Forbidden -> "后端不允许抓取这个地址（403）"
    is EndpointError.NotConfigured -> "后端未配置密钥（503）"
    is EndpointError.Unauthorized -> "密钥无效或已失效"
    else -> "订阅验证失败"
}).replace(Regex("https?://\\S+"), "订阅地址").take(200)
