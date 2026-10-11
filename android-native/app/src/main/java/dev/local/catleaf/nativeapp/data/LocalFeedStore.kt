package dev.local.catleaf.nativeapp.data

import dev.local.catleaf.nativeapp.data.article.ArticleRepository
import dev.local.catleaf.nativeapp.data.local.CacheCleanup
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.network.EndpointPolicy
import dev.local.catleaf.nativeapp.data.network.FeedApiError
import dev.local.catleaf.nativeapp.data.rss.LocalFeedFetcher

/**
 * 本机范围的订阅目录（契约 8.2，[FeedRegistry] 的本机实现）：
 * **Room 是权威**——新增先写本机、成功后再公开抓取验证；验证失败保留源与原因，可重试；
 * 编辑/删除离线可完成，写盘失败保持旧状态。没有实例 API、不发后端请求、没有假 URL/假密钥。
 */
class LocalFeedStore(
    private val db: CatLeafDatabase,
    private val scopeKey: String,
    private val cleanup: CacheCleanup,
    /** 源 XML 抓取（默认公开通道）；仪器测试注入假通道 */
    private val fetchXml: suspend (FeedEntity) -> Result<String> = DEFAULT_FETCH_XML,
) : FeedRegistry {

    override suspend fun local(): List<FeedEntity> =
        db.feeds().list(scopeKey).sortedWith(compareBy({ it.category }, { it.title }))

    override suspend fun knownUrls(): Result<List<String>> = Result.success(local().map { it.url })

    /**
     * 新增：先写 Room（登记成功），再抓取验证。登记与验证结果分开返回（契约 8.2）。
     */
    override suspend fun add(url: String, category: String, customTitle: String): Result<FeedRegistry.AddResult> {
        val normalized = validateNewUrl(url).getOrElse { return Result.failure(it) }
        val id = "f${java.util.UUID.randomUUID().toString().replace("-", "").take(20)}"
        val row = FeedEntity(
            instanceKey = scopeKey,
            feedId = id,
            url = normalized,
            // 名称自定义优先；来源名称取得前以 URL 主机兜底（契约 8.2）
            title = customTitle.trim().ifBlank {
                runCatching { java.net.URI(normalized).host }.getOrNull() ?: normalized
            },
            category = category.trim(),
            status = FeedStore.STATUS_PENDING,
            lastSuccessAt = null,
        )
        try {
            db.feeds().upsertAll(listOf(row))
        } catch (e: Exception) {
            // 写盘失败保持旧状态：不假称已登记（契约 8.2）
            return Result.failure(FeedApiError.Conflict("local_write_failed", "本机保存失败：${e.message ?: "未知原因"}"))
        }
        val verify = verify(id)
        return Result.success(FeedRegistry.AddResult(id, verify.isSuccess, verify.exceptionOrNull()))
    }

    override suspend fun updateMeta(feedId: String, category: String, customTitle: String): Result<Unit> {
        val current = db.feeds().list(scopeKey).firstOrNull { it.feedId == feedId }
            ?: return Result.failure(FeedApiError.NotFound)
        val updated = current.copy(
            category = category.trim(),
            title = customTitle.trim().ifBlank { current.title },
        )
        return try {
            db.feeds().upsertAll(listOf(updated))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(FeedApiError.Conflict("local_write_failed", "本机保存失败：${e.message ?: "未知原因"}"))
        }
    }

    /** 删除只移除本机源行；收藏与已读在 articles 表，不受影响（契约 4.3 同源语义） */
    override suspend fun delete(feedId: String): Result<Unit> {
        val current = db.feeds().list(scopeKey).firstOrNull { it.feedId == feedId }
            ?: return Result.failure(FeedApiError.NotFound)
        return try {
            db.feeds().delete(scopeKey, current.feedId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(FeedApiError.Conflict("local_write_failed", "本机删除失败：${e.message ?: "未知原因"}"))
        }
    }

    /**
     * 验证/重试：公开抓取 → 解析 → 合并入库 → 更新真实状态。
     * 复用 [ArticleRepository] 的刷新路径（预算腾挪、合并保留状态、失败不清缓存）。
     */
    override suspend fun verify(feedId: String): Result<Unit> {
        val row = db.feeds().list(scopeKey).firstOrNull { it.feedId == feedId }
            ?: return Result.failure(FeedApiError.NotFound)
        val repo = ArticleRepository(
            db = db,
            api = null,
            instanceKey = scopeKey,
            cleanup = cleanup,
            localFeedXml = fetchXml,
        )
        return when (val outcome = repo.refreshNow(row)) {
            is ArticleRepository.RefreshOutcome.Refreshed -> Result.success(Unit)
            is ArticleRepository.RefreshOutcome.SkippedFresh -> Result.success(Unit)
            else -> {
                // 失败要如实写状态（pending 会误导“正在验证”）；保留原因给界面
                val error = (outcome as ArticleRepository.RefreshOutcome.Failed).error
                db.feeds().setStatus(scopeKey, feedId, FeedStore.STATUS_FAILED, row.lastSuccessAt)
                Result.failure(error)
            }
        }
    }

    /** 本机没有远端可同步：镜像即权威，refresh 是空操作（OPML/表单写完直接重读本机） */
    override suspend fun refresh(): Result<List<FeedEntity>> = Result.success(local())

    /** 仅 scope+规范化 URL 重复判已有；同站不同路径/有意义查询保持独立（契约 8.2） */
    private suspend fun validateNewUrl(raw: String): Result<String> {
        val trimmed = raw.trim()
        val normalized = EndpointPolicy.normalizeFeedUrl(trimmed)
            ?: return Result.failure(FeedApiError.Invalid("invalid_url", "只接受有效的 HTTPS RSS/Atom 地址"))
        EndpointPolicy.requirePublicFetchTarget(normalized)
            .getOrElse { return Result.failure(FeedApiError.Invalid("invalid_url", it.message ?: "地址不可用")) }
        val existing = db.feeds().list(scopeKey)
        if (existing.size >= MAX_FEEDS) {
            return Result.failure(FeedApiError.Conflict("feed_limit_reached", "本机订阅已达 $MAX_FEEDS 个上限，请先删除一些"))
        }
        if (existing.any { EndpointPolicy.isSameFeedUrl(it.url, normalized) }) {
            return Result.failure(FeedApiError.Conflict("url_exists", "这个地址已经订阅过了"))
        }
        return Result.success(normalized)
    }

    companion object {
        /** 每个使用范围的订阅上限（PRD：200 源） */
        const val MAX_FEEDS = 200

        /** 生产抓取通道（共享一个 PublicFetch client）；测试注入假函数 */
        private val sharedFetcher = LocalFeedFetcher()

        val DEFAULT_FETCH_XML: suspend (FeedEntity) -> Result<String> =
            { row: FeedEntity -> sharedFetcher.fetchXml(row.url) }
    }
}
