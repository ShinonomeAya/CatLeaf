package dev.local.catleaf.nativeapp.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 实例配置。instanceKey 是规范化 origin 的稳定哈希：换密钥不换 namespace，
 * 换 origin 才是新实例（契约 4.1）。
 */
@Entity(tableName = "instances")
data class InstanceEntity(
    @PrimaryKey val instanceKey: String,
    val origin: String,
    val displayName: String,
    val lastConnectedAt: Long,
)

/**
 * 订阅源的本地只读镜像。离线不编辑服务端源（契约 4.1）。
 */
@Entity(
    tableName = "feeds",
    primaryKeys = ["instanceKey", "feedId"],
    indices = [Index("instanceKey")],
)
data class FeedEntity(
    val instanceKey: String,
    val feedId: String,
    val url: String,
    val title: String,
    val category: String,
    /** pending / ok / failed：新增后验证状态，失败源仍可见可删 */
    val status: String,
    val lastSuccessAt: Long?,
)

/**
 * 文章身份 + 阅读状态。这一张表**不参与普通缓存淘汰**：
 * 清掉正文后仍要保留文章身份、已读与收藏（契约 4.3）。
 * 正文另存 [ArticleContentEntity]，可被独立清理。
 */
@Entity(
    tableName = "articles",
    primaryKeys = ["localId"],
    indices = [
        Index(value = ["instanceKey", "feedId"]),
        Index(value = ["instanceKey", "guid"]),
        Index(value = ["instanceKey", "link"]),
        Index(value = ["isStarred", "starredAt"]),
    ],
)
data class ArticleEntity(
    val localId: String,
    val instanceKey: String,
    val feedId: String,
    /** GUID 为不透明字符串：只去两侧空白，不改大小写（契约 4.2） */
    val guid: String?,
    val link: String?,
    val title: String,
    /** 统一 ISO-8601 UTC；未知日期为 null */
    val publishedAt: String?,
    /** 源名与源 URL 快照：删源后仍能导出收藏的来源身份 */
    val feedTitle: String,
    val feedUrl: String,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    /** 收藏时间，用于收藏页按时间倒序 */
    val starredAt: Long? = null,
    val stateUpdatedAt: Long,
)

/**
 * 正文/摘要快照，属于普通缓存：受篇数与容量预算约束，按最近访问淘汰。
 * 与身份表一对一，删除本行不影响已读与收藏。
 */
@Entity(
    tableName = "article_content",
    primaryKeys = ["localId"],
    foreignKeys = [
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["localId"],
            childColumns = ["localId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("lastAccessedAt")],
)
data class ArticleContentEntity(
    val localId: String,
    /** 已取得的正文（RSS 或提取结果），未取得时为 null */
    val content: String?,
    val summary: String?,
    val thumbnail: String?,
    /** rss / extracted：正文来源，界面要区分显示 */
    val contentSource: String,
    /** 内容指纹，用于判断是否需要更新与生成缓存键 */
    val contentHash: String,
    /**
     * 搜索用纯文本摘要（Q01，契约 9.1）：content/summary 剥标签后的前 500 字，
     * 与正文同生命周期（正文清理时本行 CASCADE，搜索索引同步消失）。
     */
    val searchText: String? = null,
    /**
     * 已保存正文的纯文本全文（Q02，契约 9.1）：content 剥标签；null=没有正文（仅摘要/书签）。
     * 同样随正文 CASCADE；键入时不做任何净化，查询只对列做 LIKE。
     */
    val searchBodyText: String? = null,
    val lastAccessedAt: Long,
)

/** User-requested, device-only snapshot. Files live under noBackupFilesDir; this row is never exported. */
@Entity(tableName = "offline_packages", indices = [Index("scopeKey")])
data class OfflinePackageEntity(
    @PrimaryKey val localId: String,
    val scopeKey: String,
    val title: String,
    val feedTitle: String,
    val generation: String,
    val pendingGeneration: String? = null,
    /** saving / text / complete / partial / interrupted */
    val status: String,
    val selectedImages: Boolean,
    val imageTotal: Int,
    val imageSaved: Int,
    val bytes: Long,
    val bodyHash: String,
    val searchBodyText: String,
    /** JSON array of {url,id,mime}; no file paths or credentials. */
    val imageManifest: String,
    val coverId: String? = null,
    val updatedAt: Long,
)

/**
 * P06 收藏列表行：文章身份 + 「是否已保存正文」。
 * 不是新表，只是查询投影——正文状态本来就分两张表存（契约 4.1）。
 */
data class StarredRow(
    @Embedded val article: ArticleEntity,
    /** content 非空即视为「文本已保存」；只有摘要时为 false（界面显示「仅书签」） */
    val hasText: Boolean,
)

/**
 * P02 阅读列表行（T22 F09）：文章身份 + 短摘要原料 + 封面地址。
 * 只是查询投影：摘要原料取正文或摘要的前 600 个字符（够列表两行预览，不拉整篇），
 * 正文被缓存清理过的行自然为 null——无图不留空框。
 */
data class ArticleListRow(
    @Embedded val article: ArticleEntity,
    val previewRaw: String?,
    val thumbnail: String?,
)

/**
 * AI 结果缓存。cacheKey 由任务、内容指纹、模型、语言、promptVersion 组成（契约 5.2/5.3）。
 * 不含任何 Key。
 */
@Entity(
    tableName = "ai_cache",
    primaryKeys = ["cacheKey"],
    indices = [Index(value = ["instanceKey", "task"])],
)
data class AiCacheEntity(
    val cacheKey: String,
    val instanceKey: String,
    /** translation / digest */
    val task: String,
    val endpoint: String,
    val model: String,
    val contentHash: String,
    val language: String,
    val promptVersion: String,
    val result: String,
    /** 结果不完整（例如来源编号无效）时为 false，不写成功缓存 */
    val complete: Boolean,
    val createdAt: Long,
)
