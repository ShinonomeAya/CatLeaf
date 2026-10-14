package dev.local.catleaf.nativeapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface InstanceDao {
    @Upsert
    suspend fun upsert(instance: InstanceEntity)

    @Query("SELECT * FROM instances WHERE instanceKey = :instanceKey")
    suspend fun get(instanceKey: String): InstanceEntity?

    @Query("SELECT * FROM instances ORDER BY lastConnectedAt DESC")
    suspend fun all(): List<InstanceEntity>
}

@Dao
interface FeedDao {
    @Upsert
    suspend fun upsert(feed: FeedEntity)

    @Upsert
    suspend fun upsertAll(feeds: List<FeedEntity>)

    @Query("SELECT * FROM feeds WHERE instanceKey = :instanceKey ORDER BY category, title")
    suspend fun list(instanceKey: String): List<FeedEntity>

    @Query("DELETE FROM feeds WHERE instanceKey = :instanceKey AND feedId = :feedId")
    suspend fun delete(instanceKey: String, feedId: String)

    /** 镜像同步用：服务端已不存在的源从本地目录移除 */
    @Query("DELETE FROM feeds WHERE instanceKey = :instanceKey AND feedId NOT IN (:keepIds)")
    suspend fun deleteMissing(instanceKey: String, keepIds: List<String>)

    @Query("DELETE FROM feeds WHERE instanceKey = :instanceKey")
    suspend fun deleteAll(instanceKey: String)

    /**
     * 只更新验证状态。同步列表时服务端不返回状态，不能顺手把状态清掉，
     * 否则界面上“失败源/可阅读”会来回闪。
     */
    @Query("UPDATE feeds SET status = :status, lastSuccessAt = :lastSuccessAt WHERE instanceKey = :instanceKey AND feedId = :feedId")
    suspend fun setStatus(instanceKey: String, feedId: String, status: String, lastSuccessAt: Long?)
}

@Dao
interface ArticleDao {

    @Upsert
    suspend fun upsertArticle(article: ArticleEntity)

    @Upsert
    suspend fun upsertContent(content: ArticleContentEntity)

    /**
     * 合并抓取结果：保留已读与收藏，只更新文章身份与正文。
     * 用 Upsert 而不是 Insert(REPLACE)，避免 REPLACE 先删后插连带
     * 触发 article_content 的 CASCADE 而丢正文（契约 4.2）。
     */
    @Transaction
    suspend fun merge(article: ArticleEntity, content: ArticleContentEntity?) {
        upsertArticle(article)
        if (content != null) upsertContent(content)
    }

    @Query("SELECT * FROM articles WHERE instanceKey = :instanceKey AND feedId = :feedId ORDER BY publishedAt DESC, localId DESC LIMIT :limit OFFSET :offset")
    suspend fun listByFeed(instanceKey: String, feedId: String, limit: Int, offset: Int): List<ArticleEntity>

    /** 日摘要先按本地日期对应UTC窗口过滤，候选不受阅读首屏200条限制。 */
    @Query("""
        SELECT a.* FROM articles a JOIN feeds f ON f.instanceKey = a.instanceKey AND f.feedId = a.feedId
        WHERE a.instanceKey = :instanceKey AND (:feedId IS NULL OR a.feedId = :feedId)
        AND julianday(a.publishedAt) >= julianday(:start) AND julianday(a.publishedAt) < julianday(:end)
        ORDER BY julianday(a.publishedAt) DESC, a.localId DESC
    """)
    suspend fun listForDigest(instanceKey: String, feedId: String?, start: String, end: String): List<ArticleEntity>

    /**
     * 阅读列表带摘要与封面（T22 F09）：LEFT JOIN 正文表，只取前 600 字符做预览原料，
     * 不逐行拉全文；正文被缓存清理过的行 previewRaw/thumbnail 自然为 null。
     */
    @Query(
        """
        SELECT a.*, substr(COALESCE(c.content, c.summary), 1, 600) AS previewRaw, c.thumbnail AS thumbnail
        FROM articles a LEFT JOIN article_content c ON a.localId = c.localId
        WHERE a.instanceKey = :instanceKey AND a.feedId = :feedId
        ORDER BY a.publishedAt DESC, a.localId DESC LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listByFeedWithPreview(instanceKey: String, feedId: String, limit: Int, offset: Int): List<ArticleListRow>

    @Query("""
        SELECT a.*, substr(COALESCE(c.content, c.summary), 1, 600) AS previewRaw, c.thumbnail AS thumbnail
        FROM articles a JOIN feeds f ON f.instanceKey = a.instanceKey AND f.feedId = a.feedId
        LEFT JOIN article_content c ON a.localId = c.localId
        WHERE a.instanceKey = :instanceKey AND (:unreadOnly = 0 OR a.isRead = 0)
        AND (:dateMode = 'all' OR (:dateMode = 'on' AND a.publishedAt >= :dayStart AND a.publishedAt < :dayEnd)
            OR (:dateMode = 'unknown' AND a.publishedAt IS NULL))
        ORDER BY a.publishedAt DESC, a.localId DESC LIMIT :limit
    """)
    suspend fun listAllSubscribedWithPreview(instanceKey: String, unreadOnly: Boolean, dateMode: String, dayStart: String?, dayEnd: String?, limit: Int): List<ArticleListRow>

    @Query("""
        SELECT COUNT(*) FROM articles a JOIN feeds f ON f.instanceKey = a.instanceKey AND f.feedId = a.feedId
        WHERE a.instanceKey = :instanceKey AND (:unreadOnly = 0 OR a.isRead = 0)
        AND (:dateMode = 'all' OR (:dateMode = 'on' AND a.publishedAt >= :dayStart AND a.publishedAt < :dayEnd)
            OR (:dateMode = 'unknown' AND a.publishedAt IS NULL))
    """)
    suspend fun countAllSubscribed(instanceKey: String, unreadOnly: Boolean, dateMode: String, dayStart: String?, dayEnd: String?): Int

    @Query("SELECT * FROM articles WHERE localId = :localId")
    suspend fun get(localId: String): ArticleEntity?

    /** 状态导出与恢复匹配用：一个实例的全部身份行（状态文件只含这些行需要的字段，无正文） */
    @Query("SELECT * FROM articles WHERE instanceKey = :instanceKey ORDER BY localId")
    suspend fun listByInstance(instanceKey: String): List<ArticleEntity>

    // ── 搜索（Q01/Q02，契约 9.1）：参数化、LIKE 转义（ESCAPE '\\'）、数据库分页 50/页 ──

    // WHERE 子句在两个查询里重复（Room 不允许 DAO 内拼接 SQL）：标题命中 或 摘要/正文
    // 纯文本（search_text）命中；LOWER 折叠 ASCII；ESCAPE '\\' 配合 SearchQuery.likePattern。
    // 注意：Kotlin 字符串里 "\\\\" 编译为 SQL 的 "\\"（转义符字面量）。

    // 筛选参数（契约 9.1 组合）：feedId 可空、未读/收藏 0/1、日期 all/on/unknown
    // （today 归并为 on：日界由调用方按本地时区算好传入）；unknown=发布时间缺失单独一档。

    @Query(
        "SELECT a.* FROM articles a LEFT JOIN article_content c ON a.localId = c.localId " +
            "LEFT JOIN offline_packages o ON o.localId = a.localId AND o.scopeKey = a.instanceKey AND o.generation != '' " +
            "WHERE a.instanceKey = :instanceKey " +
            "AND (LOWER(a.title) LIKE '%' || :pattern || '%' ESCAPE '\\' " +
            "OR LOWER(COALESCE(c.searchText, '')) LIKE '%' || :pattern || '%' ESCAPE '\\' " +
            "OR (:bodyMode = 1 AND LOWER(COALESCE(o.searchBodyText, c.searchBodyText, '')) LIKE '%' || :pattern || '%' ESCAPE '\\')) " +
            "AND (:feedId IS NULL OR a.feedId = :feedId) " +
            "AND (:unreadOnly = 0 OR a.isRead = 0) " +
            "AND (:starredOnly = 0 OR a.isStarred = 1) " +
            "AND (:dateMode = 'all' " +
            " OR (:dateMode = 'on' AND a.publishedAt >= :dayStart AND a.publishedAt < :dayEnd) " +
            " OR (:dateMode = 'unknown' AND a.publishedAt IS NULL)) " +
            "ORDER BY a.publishedAt DESC, a.localId DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun searchTitles(
        instanceKey: String,
        pattern: String,
        feedId: String?,
        bodyMode: Int,
        unreadOnly: Int,
        starredOnly: Int,
        dateMode: String,
        dayStart: String?,
        dayEnd: String?,
        limit: Int,
        offset: Int,
    ): List<ArticleEntity>

    @Query(
        "SELECT COUNT(*) FROM articles a LEFT JOIN article_content c ON a.localId = c.localId " +
            "LEFT JOIN offline_packages o ON o.localId = a.localId AND o.scopeKey = a.instanceKey AND o.generation != '' " +
            "WHERE a.instanceKey = :instanceKey " +
            "AND (LOWER(a.title) LIKE '%' || :pattern || '%' ESCAPE '\\' " +
            "OR LOWER(COALESCE(c.searchText, '')) LIKE '%' || :pattern || '%' ESCAPE '\\' " +
            "OR (:bodyMode = 1 AND LOWER(COALESCE(o.searchBodyText, c.searchBodyText, '')) LIKE '%' || :pattern || '%' ESCAPE '\\')) " +
            "AND (:feedId IS NULL OR a.feedId = :feedId) " +
            "AND (:unreadOnly = 0 OR a.isRead = 0) " +
            "AND (:starredOnly = 0 OR a.isStarred = 1) " +
            "AND (:dateMode = 'all' " +
            " OR (:dateMode = 'on' AND a.publishedAt >= :dayStart AND a.publishedAt < :dayEnd) " +
            " OR (:dateMode = 'unknown' AND a.publishedAt IS NULL))",
    )
    suspend fun countSearch(
        instanceKey: String,
        pattern: String,
        feedId: String?,
        bodyMode: Int,
        unreadOnly: Int,
        starredOnly: Int,
        dateMode: String,
        dayStart: String?,
        dayEnd: String?,
    ): Int

    /** One small, plain-text excerpt for each visible result; no HTML is returned to the UI. */
    @Query(
        "SELECT a.localId AS localId, " +
            "CASE WHEN INSTR(LOWER(a.title), :term) > 0 THEN a.title " +
            "WHEN INSTR(LOWER(COALESCE(c.searchText, '')), :term) > 0 THEN " +
            "SUBSTR(c.searchText, MAX(1, INSTR(LOWER(c.searchText), :term) - 40), 140) " +
            "WHEN :bodyMode = 1 AND INSTR(LOWER(COALESCE(o.searchBodyText, c.searchBodyText, '')), :term) > 0 THEN " +
            "SUBSTR(COALESCE(o.searchBodyText, c.searchBodyText), MAX(1, INSTR(LOWER(COALESCE(o.searchBodyText, c.searchBodyText)), :term) - 40), 140) " +
            "ELSE '' END AS snippet, " +
            "CASE WHEN INSTR(LOWER(a.title), :term) > 0 THEN 'title' " +
            "WHEN INSTR(LOWER(COALESCE(c.searchText, '')), :term) > 0 THEN 'summary' " +
            "ELSE 'body' END AS location, " +
            "CASE WHEN INSTR(LOWER(a.title), :term) > 0 THEN INSTR(LOWER(a.title), :term) " +
            "WHEN INSTR(LOWER(COALESCE(c.searchText, '')), :term) > 0 THEN INSTR(LOWER(c.searchText), :term) " +
            "ELSE INSTR(LOWER(COALESCE(o.searchBodyText, c.searchBodyText, '')), :term) END AS position " +
            "FROM articles a LEFT JOIN article_content c ON a.localId = c.localId " +
            "LEFT JOIN offline_packages o ON o.localId = a.localId AND o.scopeKey = a.instanceKey AND o.generation != '' " +
            "WHERE a.localId IN (:localIds)",
    )
    suspend fun searchSnippets(localIds: List<String>, term: String, bodyMode: Int): List<SearchSnippet>

    @Query("SELECT * FROM article_content WHERE localId = :localId")
    suspend fun getContent(localId: String): ArticleContentEntity?

    /** 本地已获取的篇数：用于“已获取 N 篇”和判断是否还能“加载更多” */
    @Query("SELECT COUNT(*) FROM articles WHERE instanceKey = :instanceKey AND feedId = :feedId")
    suspend fun countByFeed(instanceKey: String, feedId: String): Int

    @Query("SELECT * FROM articles WHERE instanceKey = :instanceKey AND feedId = :feedId AND guid = :guid LIMIT 1")
    suspend fun findByGuid(instanceKey: String, feedId: String, guid: String): ArticleEntity?

    @Query("SELECT * FROM articles WHERE instanceKey = :instanceKey AND feedId = :feedId AND link = :link LIMIT 1")
    suspend fun findByLink(instanceKey: String, feedId: String, link: String): ArticleEntity?

    @Query("SELECT * FROM articles WHERE isStarred = 1 ORDER BY starredAt DESC")
    suspend fun listStarred(): List<ArticleEntity>

    /**
     * P06 收藏列表：一次查出「是否已保存正文」，避免每行再查一次。
     * 只有 content 非空才算「文本已保存」——只有摘要的行按「仅书签」显示，
     * 免得把订阅摘要说成可离线读的正文（契约 4.3）。
     */
    @Query(
        """
        SELECT a.*, ((c.content IS NOT NULL AND TRIM(c.content) != '') OR o.generation IS NOT NULL) AS hasText
        FROM articles a LEFT JOIN article_content c ON a.localId = c.localId
        LEFT JOIN offline_packages o ON o.localId = a.localId AND o.scopeKey = a.instanceKey AND o.generation != ''
        WHERE a.isStarred = 1
        ORDER BY a.starredAt DESC
        """,
    )
    suspend fun listStarredWithText(): List<StarredRow>

    @Query("SELECT COUNT(*) FROM articles WHERE isStarred = 1")
    suspend fun starredCount(): Int

    /** 手动把文章设回未读：只改这一个字段，不会被同帧渲染结果覆盖（契约 P04） */
    @Query("UPDATE articles SET isRead = :isRead, stateUpdatedAt = :now WHERE localId = :localId")
    suspend fun setRead(localId: String, isRead: Boolean, now: Long): Int

    @Query("UPDATE articles SET isStarred = :starred, starredAt = :starredAt, stateUpdatedAt = :now WHERE localId = :localId")
    suspend fun setStarred(localId: String, starred: Boolean, starredAt: Long?, now: Long): Int

    /** 普通缓存清理只删正文，不删身份与状态 */
    @Query("DELETE FROM article_content WHERE localId IN (:localIds)")
    suspend fun deleteContent(localIds: List<String>)

    /**
     * 预算统计：每行正文快照的**字节数**（CAST 成 BLOB 取 UTF-8 实际字节，
     * LENGTH(TEXT) 只数字符）、收藏标记与最近访问时间。LEFT JOIN——身份行没了
     * （理论上 FK 会级联掉正文）也只当未收藏处理，不为统计崩掉。
     */
    @Query(
        """
        SELECT c.localId AS localId,
               IFNULL(LENGTH(CAST(c.content AS BLOB)), 0) +
                 IFNULL(LENGTH(CAST(c.summary AS BLOB)), 0) +
                 IFNULL(LENGTH(CAST(c.thumbnail AS BLOB)), 0) +
                 IFNULL(LENGTH(CAST(c.searchText AS BLOB)), 0) +
                 IFNULL(LENGTH(CAST(c.searchBodyText AS BLOB)), 0) AS bytes,
               IFNULL(a.isStarred, 0) AS starred,
               c.lastAccessedAt AS lastAccessedAt
        FROM article_content c LEFT JOIN articles a ON a.localId = c.localId
        """,
    )
    suspend fun contentStats(): List<ContentStat>

    /** 打开正文即“访问”：淘汰按最近访问，读过的不该比没读的先删（契约 4.3） */
    @Query("UPDATE article_content SET lastAccessedAt = :now WHERE localId = :localId")
    suspend fun touchContent(localId: String, now: Long)

    @Query("SELECT localId FROM articles WHERE isStarred = 1")
    suspend fun starredLocalIds(): List<String>
}

data class SearchSnippet(val localId: String, val snippet: String, val location: String, val position: Int)

/** [ArticleDao.contentStats] 的投影，不是表 */
data class ContentStat(
    val localId: String,
    val bytes: Long,
    val starred: Boolean,
    val lastAccessedAt: Long,
)

@Dao
interface OfflinePackageDao {
    @Upsert
    suspend fun upsert(pkg: OfflinePackageEntity)

    @Query("SELECT * FROM offline_packages WHERE localId = :localId AND scopeKey = :scopeKey")
    suspend fun get(localId: String, scopeKey: String): OfflinePackageEntity?

    @Query("SELECT * FROM offline_packages WHERE scopeKey = :scopeKey ORDER BY updatedAt DESC")
    suspend fun list(scopeKey: String): List<OfflinePackageEntity>

    @Query("SELECT * FROM offline_packages")
    suspend fun all(): List<OfflinePackageEntity>

    @Query("SELECT IFNULL(SUM(bytes), 0) FROM offline_packages")
    suspend fun totalBytes(): Long

    @Query("DELETE FROM offline_packages WHERE localId = :localId AND scopeKey = :scopeKey")
    suspend fun delete(localId: String, scopeKey: String): Int
}

@Dao
interface AiCacheDao {
    /**
     * 写入缓存结果。用 Upsert 而不是 IGNORE：手动「重新翻译」成功后新结果应生效
     * （键相同就是同一份输入）。
     * **注意**：不完整的结果（complete=false）不要 upsert 到已有成功结果上——
     * 契约 5.1 要求“保留已有成功结果”，翻译器也只在完整时才写。
     */
    @Upsert
    suspend fun insert(result: AiCacheEntity)

    @Query("SELECT * FROM ai_cache WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): AiCacheEntity?

    /**
     * 日摘要「有新增内容」提示用：查同一 实例|源|日期|时区 前缀的最近一份完整结果。
     * 前缀由键的开头字段拼成（见 DigestSummarizer.cacheKey），只作提示、不参与正确性。
     */
    @Query("SELECT * FROM ai_cache WHERE cacheKey LIKE :prefix || '%' AND complete = 1 AND task = 'digest' ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestCompleteByPrefix(prefix: String): AiCacheEntity?

    @Query("DELETE FROM ai_cache WHERE instanceKey = :instanceKey")
    suspend fun clearInstance(instanceKey: String)

    /** 预算统计：AI 结果的 UTF-8 字节与任务/键（收藏译文的保护判定在 CacheCleanup） */
    @Query("SELECT cacheKey AS cacheKey, task AS task, LENGTH(CAST(result AS BLOB)) AS bytes, createdAt AS createdAt FROM ai_cache")
    suspend fun resultStats(): List<AiResultStat>

    @Query("DELETE FROM ai_cache WHERE cacheKey IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)
}

/** [AiCacheDao.resultStats] 的投影，不是表 */
data class AiResultStat(val cacheKey: String, val task: String, val bytes: Long, val createdAt: Long)
