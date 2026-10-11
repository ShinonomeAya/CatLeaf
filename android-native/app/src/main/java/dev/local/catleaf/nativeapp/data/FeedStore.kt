package dev.local.catleaf.nativeapp.data

import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.network.FeedApi

/**
 * 订阅登记的共用边界（契约 8.2）：实例与本机各有一个实现，OPML 导入与订阅管理面向它编程。
 * - 实例（[FeedStore]）：服务端是权威，本地 `feeds` 表只是只读镜像，增删改先服务端后镜像；
 * - 本机（[LocalFeedStore]）：Room 是权威，先写本机、成功后再公开抓取验证。
 */
interface FeedRegistry {
    /**
     * 新增的**两个真实结果**（契约 8.2，L06）：登记（服务端/Room 写入）与验证（RSS 可读性）
     * 分开报——登记成功但验证失败时源保留、原因可重试，不再统一说「已添加并验证」。
     */
    data class AddResult(
        val feedId: String,
        /** 验证是否成功（RSS 可读/抓取解析通过） */
        val verified: Boolean,
        /** 验证失败原因（verified=false 时非空） */
        val verifyError: Throwable? = null,
    )

    /** 本地镜像行（预览/导出/管理列表显示用） */
    suspend fun local(): List<FeedEntity>

    /** 去重权威的 URL 列表：实例=服务端完整列表；本机=Room 现有行 */
    suspend fun knownUrls(): Result<List<String>>

    suspend fun add(url: String, category: String, customTitle: String): Result<AddResult>
    suspend fun updateMeta(feedId: String, category: String, customTitle: String): Result<Unit>
    suspend fun delete(feedId: String): Result<Unit>
    suspend fun verify(feedId: String): Result<Unit>
    suspend fun refresh(): Result<List<FeedEntity>>
}

/**
 * 订阅目录：服务端是权威，本地 `feeds` 表只是**只读镜像**（契约 4.1，离线不编辑服务端源）。
 *
 * 增删改一律先调服务端、成功后再刷新镜像；服务端拒绝时本地保持原样，
 * 界面据返回的稳定 code 给出可操作提示（T08 已把这些 code 在生产验证过）。
 */
class FeedStore(
    private val db: CatLeafDatabase,
    private val api: FeedApi,
    private val instanceKey: String,
) : FeedRegistry {

    /** 去重权威：服务端完整源列表（本地镜像可能滞后） */
    override suspend fun knownUrls(): Result<List<String>> =
        api.list().map { feeds -> feeds.map { it.url } }

    /** 全量刷新镜像：服务端已删除的源在本地一并移除 */
    override suspend fun refresh(): Result<List<FeedEntity>> {
        val remote = api.list()
        remote.fold(
            onSuccess = { feeds ->
                // 本地现状读一次建索引：原先每个源各查一次全表，200 个源会多出几百次查询
                val existing = db.feeds().list(instanceKey).associateBy { it.feedId }

                if (feeds.isEmpty()) {
                    db.feeds().deleteAll(instanceKey)
                } else {
                    db.feeds().deleteMissing(instanceKey, feeds.map { it.id })
                }
                db.feeds().upsertAll(
                    feeds.map { f ->
                        FeedEntity(
                            instanceKey = instanceKey,
                            feedId = f.id,
                            url = f.url,
                            title = f.displayName,
                            category = f.category,
                            // 同步列表不带状态：保留已有状态，新源先记 pending 等验证
                            status = existing[f.id]?.status ?: STATUS_PENDING,
                            lastSuccessAt = existing[f.id]?.lastSuccessAt,
                        )
                    },
                )
            },
            onFailure = { return Result.failure(it) },
        )
        return Result.success(local())
    }

    override suspend fun local(): List<FeedEntity> {
        val rows = db.feeds().list(instanceKey)
        // 分组按名称、组内按 displayOrder 对应的入库顺序：稳定即可
        return rows.sortedWith(compareBy({ it.category }, { it.title }))
    }

    /**
     * 新增源：客户端生成 id（契约 3.2），成功后立即抓取验证。
     * 验证失败**不删除源**，只标记为 failed 并把验证结果分开返回（L06）；
     * refresh 失败不回滚登记——服务端已写入，镜像下次刷新会追上。
     */
    override suspend fun add(url: String, category: String, customTitle: String): Result<FeedRegistry.AddResult> {
        val id = "f${java.util.UUID.randomUUID().toString().replace("-", "").take(20)}"
        api.add(id = id, url = url, category = category, customTitle = customTitle).onFailure {
            return Result.failure(it)
        }
        // 顺序很重要：先把新源同步进本地镜像，再验证。
        // 反过来时 setStatus 会更新 0 行（行还不存在），随后 refresh 又补成 pending，
        // 校验结果就丢了——探针实测到过这个 bug。
        runCatching { refresh() }
        val verify = verify(id)
        return Result.success(FeedRegistry.AddResult(id, verify.isSuccess, verify.exceptionOrNull()))
    }

    override suspend fun updateMeta(feedId: String, category: String, customTitle: String): Result<Unit> {
        val current = db.feeds().list(instanceKey).firstOrNull { it.feedId == feedId }
            ?: return Result.failure(dev.local.catleaf.nativeapp.data.network.FeedApiError.NotFound)
        api.updateMeta(id = feedId, url = current.url, category = category, customTitle = customTitle)
            .onFailure { return Result.failure(it) }
        refresh()
        return Result.success(Unit)
    }

    /** 删除：服务端先删，本地镜像在刷新时移除；**本地收藏不受影响**（收藏在 articles 表） */
    override suspend fun delete(feedId: String): Result<Unit> {
        api.delete(feedId).onFailure { return Result.failure(it) }
        refresh()
        return Result.success(Unit)
    }

    /** 验证/重试单个源：只更新状态，不动其他源 */
    override suspend fun verify(feedId: String): Result<Unit> {
        val outcome = api.verify(feedId)
        db.feeds().setStatus(
            instanceKey = instanceKey,
            feedId = feedId,
            status = if (outcome.isSuccess) STATUS_OK else STATUS_FAILED,
            lastSuccessAt = if (outcome.isSuccess) {
                System.currentTimeMillis()
            } else {
                db.feeds().list(instanceKey).firstOrNull { it.feedId == feedId }?.lastSuccessAt
            },
        )
        return outcome
    }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_OK = "ok"
        const val STATUS_FAILED = "failed"
    }
}