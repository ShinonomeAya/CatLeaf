package dev.local.catleaf.nativeapp.data.local

import android.content.Context
import androidx.room.withTransaction
import dev.local.catleaf.nativeapp.data.article.CacheBudget
import java.io.File

/**
 * 正在读的文章：淘汰要"避开当前正文"（契约 4.3），而预算是在抓取/保存路径上跑的，
 * 和阅读页不在同一个 ViewModel——用这个进程内的单点传递，不引入事件总线。
 * ponytail: 单读者全局值；出现多面板同读时改成显式传参。
 */
object CurrentBody {
    @Volatile
    var localId: String? = null
}

/** 存储页统计（P07 存储分组）：单位都是 UTF-8 字节；`databaseBytes` 是整个库文件的估算 */
data class StorageStats(
    val normalArticles: Int,
    val normalBytes: Long,
    val starredTextBytes: Long,
    val normalAiBytes: Long,
    val protectedAiBytes: Long,
    val databaseBytes: Long,
) {
    /** 「普通缓存」显示值 = 普通正文 + 普通 AI 结果（收藏文本单列，不计入） */
    val normalTotalBytes: Long get() = normalBytes + normalAiBytes
}

/**
 * 缓存预算执行层（契约 4.3）：统计 → [CacheBudget.plan] → 事务内删除。
 *
 * 边界（都写进注释是因为它们就是验收点）：
 * - 只删 `article_content` 的普通行与普通 AI 结果；**文章身份、已读/收藏、订阅、密钥永不进删除集**；
 * - 收藏正文与收藏文章的译文独立保护；日摘要属普通缓存；
 * - 删除放一个 `withTransaction`：中途失败整个回滚，旧内容保留（"事务失败"用例的根据）；
 * - **不自动删库、无后台清理服务**——只在会增长普通缓存的写入路径前调用。
 */
class CacheCleanup(private val db: CatLeafDatabase, context: Context) {

    // 取这个数据库实例**实际**的文件名，而不是常量 NAME：测试用随机库名时才不会误读生产文件。
    // 惰性：内存库（openHelper.databaseName 为 null）没有文件可估，只有 stats() 才需要它。
    private val contextRef = context
    private val databaseFile: File? by lazy {
        runCatching { contextRef.getDatabasePath(db.openHelper.databaseName) }.getOrNull()
    }

    /** 收藏文章的译文受保护：翻译缓存键第 2 段就是 localId（`Translator.cacheKey` 的拼接顺序） */
    private fun isProtectedTranslation(stat: AiResultStat, starredLocalIds: Set<String>): Boolean {
        if (stat.task != "translation") return false // 日摘要等一律属普通缓存（契约 4.3）
        val localId = stat.cacheKey.split('|').getOrNull(1) ?: return false
        return localId in starredLocalIds
    }

    suspend fun stats(): StorageStats {
        val contents = db.articles().contentStats()
        val starredIds = db.articles().starredLocalIds().toSet()
        val ai = db.aiCache().resultStats()
        val normal = contents.filterNot { it.starred }
        return StorageStats(
            normalArticles = normal.size,
            normalBytes = normal.sumOf { it.bytes },
            starredTextBytes = contents.filter { it.starred }.sumOf { it.bytes },
            normalAiBytes = ai.filterNot { isProtectedTranslation(it, starredIds) }.sumOf { it.bytes },
            protectedAiBytes = ai.filter { isProtectedTranslation(it, starredIds) }.sumOf { it.bytes },
            databaseBytes = dbFileBytes(),
        )
    }

    /**
     * 到上限就按最近访问淘汰。keep = 调用方正在写/正在读的 localId，外加 [CurrentBody]。
     * 抛异常（Room 故障）时**什么都没删**（事务回滚），由调用方保留旧内容并提示——
     * 这正是契约要的"为新写入腾空间失败时保留旧内容并提示"。
     *
     * @param maxArticles/@param maxBytes 仅测试注入小预算用；生产路径走默认（契约数值）。
     */
    /** 复制预检（契约 8.4）：普通缓存剩余字节预算（按设备各范围合计） */
    suspend fun remainingBudgetBytes(): Long {
        val st = stats()
        return CacheBudget.MAX_BYTES - st.normalBytes.coerceAtLeast(0L)
    }

    suspend fun enforceBudget(
        keepLocalIds: Set<String> = emptySet(),
        maxArticles: Int = CacheBudget.MAX_ARTICLES,
        maxBytes: Long = CacheBudget.MAX_BYTES,
    ): CacheBudget.Plan {
        val starredIds = db.articles().starredLocalIds().toSet()
        val plan = CacheBudget.plan(
            contents = db.articles().contentStats().map { CacheBudget.ContentRow(it.localId, it.bytes, it.starred, it.lastAccessedAt) },
            ai = db.aiCache().resultStats().map { CacheBudget.AiRow(it.cacheKey, it.bytes, isProtectedTranslation(it, starredIds), it.createdAt) },
            keepLocalIds = keepLocalIds + setOfNotNull(CurrentBody.localId),
            maxArticles = maxArticles,
            maxBytes = maxBytes,
        )
        applyPlan(plan)
        return plan
    }

    /**
     * 手动「清普通缓存」（P07）：删**全部**未收藏正文与普通 AI 结果。
     * 保留：收藏及其译文、已读/收藏状态、订阅、密钥、当前正文——确认框按这个清单向用户列保留项，
     * 与「重置此设备数据」（删全部本机实例/密钥/缓存，T20 接入）范围不同。
     */
    suspend fun clearNormalCache(): Int {
        val starredIds = db.articles().starredLocalIds().toSet()
        val keep = setOfNotNull(CurrentBody.localId)
        val contents = db.articles().contentStats().filter { !it.starred && it.localId !in keep }
        val ai = db.aiCache().resultStats().filter { !isProtectedTranslation(it, starredIds) }
        db.withTransaction {
            db.articles().deleteContent(contents.map { it.localId })
            db.aiCache().deleteKeys(ai.map { it.cacheKey })
        }
        return contents.size + ai.size
    }

    private suspend fun applyPlan(plan: CacheBudget.Plan) {
        if (plan.isEmpty) return
        db.withTransaction {
            db.articles().deleteContent(plan.contentLocalIds)
            db.aiCache().deleteKeys(plan.aiCacheKeys)
        }
    }

    /** 库文件 + WAL/SHM：给"空间估算"用的近似值（P07 说的是估算，不是磁盘管理器）。内存库为 0 */
    private fun dbFileBytes(): Long {
        val file = databaseFile ?: return 0L
        val dir = file.parentFile ?: return 0L
        return dir.listFiles { f -> f.name == file.name || f.name.startsWith("${file.name}-") }?.sumOf { it.length() } ?: 0L
    }
}
