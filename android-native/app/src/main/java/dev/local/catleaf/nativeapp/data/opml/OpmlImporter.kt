package dev.local.catleaf.nativeapp.data.opml

import dev.local.catleaf.nativeapp.data.FeedRegistry
import dev.local.catleaf.nativeapp.data.network.EndpointError
import dev.local.catleaf.nativeapp.data.network.FeedApiError
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy

/**
 * OPML 串行导入（契约 §6；本机与实例共用，面向 [FeedRegistry]）。
 *
 * 规则：
 * - 逐条添加并验证，**不用批量并发**：失败要能定位到具体条目；
 * - 与去重权威列表及本次已添加项按**规范化 URL** 去重，重复条目不重复新增；
 * - 已存在的不改名称（“不覆写已有名称”）；
 * - 单条请求超时（实例模式）→ 标记 [Outcome.PendingConfirm]（“结果待确认”），
 *   重试前先重新读取去重权威列表，按 URL 关联已有项，否则走幂等新增；
 *   本机模式写 Room 无歧义，不产生 PendingConfirm；
 * - 取消只停止后续条目，**已完成的不回滚**。
 */
class OpmlImporter(
    private val registry: FeedRegistry,
) {

    sealed class Outcome {
        /** [verified]=false：已登记但 RSS 验证未通过（契约 8.2 登记与验证分开报），源保留、可在订阅管理重试 */
        data class Added(val feedId: String, val verified: Boolean = true) : Outcome()
        data class AlreadyExists(val reason: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
        /** 请求超时：服务端可能已写入，必须提示“结果待确认”，不能断言失败 */
        object PendingConfirm : Outcome()
        object Cancelled : Outcome()
        /** 预览中不可导入（无效/重复）或用户未勾选；[reason] 供结果页“查看跳过项”逐条说明 */
        data class SkippedInvalid(val reason: String = "已跳过") : Outcome()
    }

    data class ItemResult(
        val title: String,
        val xmlUrl: String,
        val group: String,
        val outcome: Outcome,
    )

    data class Report(val items: List<ItemResult>) {
        val added: Int get() = items.count { it.outcome is Outcome.Added }
        val exists: Int get() = items.count { it.outcome is Outcome.AlreadyExists }
        val failed: Int get() = items.count { it.outcome is Outcome.Failed }
        val pending: Int get() = items.count { it.outcome is Outcome.PendingConfirm }
        val cancelled: Int get() = items.count { it.outcome is Outcome.Cancelled }

        /** 是否存在“结果待确认”的条目：决定是否给出重试入口 */
        val needsRetry: Boolean get() = pending > 0
    }

    /**
     * @param entries 预览里被勾选的条目（顺序即导入顺序）
     * @param onProgress 每处理完一条回调一次，供界面显示“正在处理 X/Y”
     * @param isCancelled 返回 true 时停止后续条目；已完成的条目保留
     */
    suspend fun import(
        entries: List<Opml.Entry>,
        onProgress: (ItemResult, Int, Int) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Report {
        val results = mutableListOf<ItemResult>()

        // 导入前读一次去重权威列表（实例=服务端；本机=Room）：这是去重的权威来源
        val remote = registry.knownUrls().getOrElse {
            return Report(
                entries.map { e -> ItemResult(e.title, e.xmlUrl, e.group, Outcome.Failed("无法读取现有订阅：${it.message}")) },
            )
        }
        val knownUrls = remote.toMutableList()

        entries.forEachIndexed { index, entry ->
            if (isCancelled()) {
                results += ItemResult(entry.title, entry.xmlUrl, entry.group, Outcome.Cancelled)
                onProgress(results.last(), index + 1, entries.size)
                return@forEachIndexed
            }

            val normalized = EndpointPolicy.normalizeFeedUrl(entry.xmlUrl)
            val outcome = when {
                !entry.selectable -> Outcome.SkippedInvalid(entry.skipReason())

                // 与已有（含本次刚添加的）按规范化 URL 比较：重复不新增、不改名
                normalized != null && knownUrls.any { EndpointPolicy.isSameFeedUrl(it, entry.xmlUrl) } ->
                    Outcome.AlreadyExists("已在订阅中，未改动名称")

                else -> addOne(entry, normalized)
            }

            if (outcome is Outcome.Added) knownUrls += entry.xmlUrl
            results += ItemResult(entry.title, entry.xmlUrl, entry.group, outcome)
            onProgress(results.last(), index + 1, entries.size)
        }

        return Report(results)
    }

    private suspend fun addOne(entry: Opml.Entry, normalized: String?): Outcome {
        if (normalized == null) return Outcome.Failed("不是有效的 HTTPS 地址")
        val added = registry.add(
            url = entry.xmlUrl,
            category = entry.group.take(Opml.MAX_GROUP_LENGTH),
            customTitle = entry.title.take(Opml.MAX_NAME_LENGTH),
        )
        return added.fold(
            onSuccess = { Outcome.Added(it.feedId, it.verified) },
            onFailure = { error ->
                when (error) {
                    // 服务端已存在同 URL：说明此前其实写进去了（例如上次超时后重试）
                    is FeedApiError.Conflict ->
                        if (error.code == "url_exists" || error.code == "id_exists") {
                            Outcome.AlreadyExists("已在订阅中")
                        } else {
                            Outcome.Failed(error.message ?: "被拒绝")
                        }

                    is EndpointError.Timeout -> Outcome.PendingConfirm
                    else -> Outcome.Failed(error.message ?: "添加失败")
                }
            },
        )
    }

    /**
     * 对“结果待确认”的条目重试：先重新读取去重权威列表，能按 URL 关联上的记为已存在，
     * 其余再走一次幂等新增（实例模式服务端对重复 URL 返回 409，不会产生第二个源）。
     */
    suspend fun retryUnresolved(report: Report, isCancelled: () -> Boolean = { false }): Report {
        val unresolvedIndices = report.items.indices.filter { index ->
            report.items[index].outcome.let { it is Outcome.PendingConfirm || it is Outcome.Failed }
        }
        if (unresolvedIndices.isEmpty()) return report

        val remoteUrls = registry.knownUrls().getOrElse {
            return report
        }.toMutableList()

        val items = report.items.toMutableList()
        for (index in unresolvedIndices) {
            val item = items[index]
            if (isCancelled()) {
                items[index] = item.copy(outcome = Outcome.Cancelled)
                continue
            }
            val outcome = if (remoteUrls.any { EndpointPolicy.isSameFeedUrl(it, item.xmlUrl) }) {
                Outcome.AlreadyExists("核对后确认已存在")
            } else {
                addOne(
                    Opml.Entry(title = item.title, xmlUrl = item.xmlUrl, group = item.group),
                    EndpointPolicy.normalizeFeedUrl(item.xmlUrl),
                )
            }
            if (outcome is Outcome.Added) remoteUrls += item.xmlUrl
            items[index] = item.copy(outcome = outcome)
        }
        return Report(items)
    }
}

/** 预览里不可导入条目的原因（与预览行文案一致） */
fun Opml.Entry.skipReason(): String = when {
    invalidReason != null -> invalidReason
    duplicateOf != null -> duplicateOf
    groupTooLong -> "分组名超过 ${Opml.MAX_GROUP_LENGTH} 字符"
    else -> "未勾选"
}
