package dev.local.catleaf.nativeapp.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.catleaf.nativeapp.data.local.ArticleContentEntity
import dev.local.catleaf.nativeapp.data.local.ArticleDao
import dev.local.catleaf.nativeapp.data.local.ArticleEntity
import dev.local.catleaf.nativeapp.data.local.CatLeafDatabase
import dev.local.catleaf.nativeapp.data.local.FeedEntity
import dev.local.catleaf.nativeapp.data.local.InstanceDao
import dev.local.catleaf.nativeapp.data.local.InstanceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T04 的 Android 侧检查：事务回滚、迁移与迁移失败、重开数据库后的持久化，
 * 以及“清正文不丢身份与收藏”。只使用虚构数据，不接触真实 Key。
 */
@RunWith(AndroidJUnit4::class)
class LocalDatabaseTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dbName: String
    private lateinit var db: CatLeafDatabase

    @Before
    fun setUp() {
        dbName = "test-${System.nanoTime()}.db"
        db = openV1()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(dbName)
    }

    private fun openV1(): CatLeafDatabase =
        Room.databaseBuilder(context, CatLeafDatabase::class.java, dbName).build()

    private fun file(): File = context.getDatabasePath(dbName)

    private fun article(localId: String, read: Boolean = false, starred: Boolean = false) = ArticleEntity(
        localId = localId,
        instanceKey = "inst-a",
        feedId = "feed-1",
        guid = "guid-$localId",
        link = "https://example.invalid/a/$localId",
        title = "标题 $localId",
        publishedAt = "2026-09-22T00:00:00Z",
        feedTitle = "示例源",
        feedUrl = "https://example.invalid/feed.xml",
        isRead = read,
        isStarred = starred,
        starredAt = if (starred) 1_700_000_000_000 else null,
        stateUpdatedAt = 1_700_000_000_000,
    )

    private fun content(localId: String, text: String) = ArticleContentEntity(
        localId = localId,
        content = text,
        summary = null,
        thumbnail = null,
        contentSource = "rss",
        contentHash = "hash-$localId",
        lastAccessedAt = 1_700_000_000_000,
    )

    private fun seed() = runBlocking {
        db.instances().upsert(InstanceEntity("inst-a", "https://reader.example.test", "示例", 1L))
        db.feeds().upsert(
            FeedEntity("inst-a", "feed-1", "https://example.invalid/feed.xml", "示例源", "未分组", "ok", 1L),
        )
        db.articles().merge(article("a1", read = true, starred = true), content("a1", "正文"))
        db.articles().merge(article("a2"), content("a2", "正文2"))
    }

    /** 迁移/重开后必须仍然成立的事实；对 v1 与 v2 数据库都适用 */
    private fun assertSeedIntact(articles: ArticleDao, instances: InstanceDao, label: String) = runBlocking {
        val a1 = articles.get("a1")
        assertNotNull("$label: 文章 a1 应仍在", a1)
        assertTrue("$label: 收藏必须保留", a1!!.isStarred)
        assertTrue("$label: 已读必须保留", a1.isRead)
        assertEquals("$label: 来源身份必须保留", "https://example.invalid/feed.xml", a1.feedUrl)
        assertEquals("$label: 正文必须保留", "正文", articles.getContent("a1")!!.content)
        assertEquals("$label: 实例必须保留", 1, instances.all().size)
        assertEquals("$label: 收藏计数", 1, articles.starredCount())
    }

    @Test
    fun article_state_survives_body_cleanup() = runBlocking {
        seed()
        db.articles().deleteContent(listOf("a1"))

        assertNull("正文应被清理", db.articles().getContent("a1"))
        val kept = db.articles().get("a1")!!
        assertTrue("清理正文不得影响已读", kept.isRead)
        assertTrue("清理正文不得影响收藏", kept.isStarred)
        assertEquals(1, db.articles().starredCount())
    }

    @Test
    fun failed_transaction_rolls_back_and_keeps_previous_state() = runBlocking {
        seed()

        try {
            db.runInTransaction {
                runBlocking {
                    db.articles().upsertArticle(article("a1").copy(title = "不该保留的标题"))
                    db.articles().upsertContent(content("a1", "不该保留的正文"))
                }
                throw IllegalStateException("模拟写盘失败")
            }
            fail("事务内异常必须向外传播")
        } catch (expected: IllegalStateException) {
            // 预期
        }

        val after = db.articles().get("a1")!!
        assertEquals("回滚后标题不得改变", "标题 a1", after.title)
        assertEquals("回滚后正文不得改变", "正文", db.articles().getContent("a1")!!.content)
        assertTrue("回滚不能丢收藏", after.isStarred)
    }

    @Test
    fun manual_unread_is_not_overwritten() = runBlocking {
        seed()
        db.articles().setRead("a1", isRead = false, now = 2_000_000_000_000)
        val after = db.articles().get("a1")!!
        assertEquals(false, after.isRead)
        assertEquals("状态时间应随手动改动更新", 2_000_000_000_000, after.stateUpdatedAt)
    }

    @Test
    fun data_survives_database_reopen() = runBlocking {
        seed()
        db.close()

        db = openV1()
        assertSeedIntact(db.articles(), db.instances(), "同进程重开")
        assertTrue("数据库文件应真实存在于应用私有目录", file().exists())
        assertEquals("catleaf.db 之外不应有第二个数据库名", CatLeafDatabase.NAME, "catleaf.db")
    }

    /**
     * Q01 起正式库为 v2：迁移测试改为手工创建 v1 库 → Room 打开跑正式 MIGRATION_1_2。
     * 比 T04 的假 v2 更有价值——验证真实迁移（含 searchText 回填）。
     */
    @Test
    fun successful_migration_keeps_rows() = runBlocking {
        val legacy = createLegacyV1(dbName)
        legacy.execSQL(
            "INSERT INTO articles (localId, instanceKey, feedId, guid, link, title, publishedAt, feedTitle, feedUrl, isRead, isStarred, starredAt, stateUpdatedAt) " +
                "VALUES ('a1', 'inst-a', 'feed-1', 'g1', 'https://example.invalid/a/a1', '标题 a1', '2026-09-22T00:00:00Z', '示例源', 'https://example.invalid/feed.xml', 1, 1, 1700000000000, 1)",
        )
        legacy.execSQL(
            "INSERT INTO article_content (localId, content, summary, thumbnail, contentSource, contentHash, lastAccessedAt) " +
                "VALUES ('a1', '<p>老版本的正文</p>', NULL, NULL, 'rss', 'h1', 1)",
        )
        legacy.execSQL("INSERT INTO instances (instanceKey, origin, displayName, lastConnectedAt) VALUES ('inst-a', 'https://example.invalid', 'x', 1)")
        legacy.close()

        val migrated = Room.databaseBuilder(context, CatLeafDatabase::class.java, dbName)
            .addMigrations(CatLeafDatabase.MIGRATION_1_2, CatLeafDatabase.MIGRATION_2_3, CatLeafDatabase.MIGRATION_3_4)
            .build()
        try {
            org.junit.Assert.assertEquals("身份与状态行保留", 1, migrated.articles().listByInstance("inst-a").size)
            val content = migrated.articles().getContent("a1")!!
            org.junit.Assert.assertEquals("正文保留", "<p>老版本的正文</p>", content.content)
            assertTrue("v2 回填 searchText（剥标签）", content.searchText?.contains("老版本的正文") == true)
            assertTrue("v3 回填已保存正文", content.searchBodyText?.contains("老版本的正文") == true)
            assertTrue("收藏/已读保留", migrated.articles().listByInstance("inst-a").single().isStarred)
        } finally {
            migrated.close()
        }
        db = openV1()
    }

    /** The Q01 APK shipped v2 without searchBodyText; upgrading it must keep user state. */
    @Test
    fun shipped_v2_to_v4_preserves_bookmark_and_backfills_full_body() = runBlocking {
        val old = createLegacyV1(dbName)
        old.execSQL("ALTER TABLE article_content ADD COLUMN searchText TEXT")
        old.execSQL("INSERT INTO articles (localId, instanceKey, feedId, guid, link, title, publishedAt, feedTitle, feedUrl, isRead, isStarred, starredAt, stateUpdatedAt) VALUES ('a1', 'inst-a', 'f1', 'g1', NULL, '旧标题', NULL, '源', 'https://example.invalid/rss', 1, 1, 7, 7)")
        old.execSQL("INSERT INTO article_content (localId, content, summary, thumbnail, contentSource, contentHash, searchText, lastAccessedAt) VALUES ('a1', '<p>旧正文 &amp; 尾部中文</p>', '短摘要', NULL, 'rss', 'h1', '旧正文', 1)")
        old.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        old.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'f43e11a48da44f4d3ed8d4a15da162e8')")
        old.version = 2
        old.close()
        val upgraded = Room.databaseBuilder(context, CatLeafDatabase::class.java, dbName)
            .addMigrations(CatLeafDatabase.MIGRATION_2_3, CatLeafDatabase.MIGRATION_3_4).build()
        try {
            val row = upgraded.articles().get("a1")!!
            assertTrue(row.isRead && row.isStarred)
            val body = upgraded.articles().getContent("a1")!!
            assertEquals("旧正文 & 尾部中文", body.searchBodyText)
            assertTrue(body.searchText?.contains("短摘要") == true)
        } finally {
            upgraded.close()
        }
        db = openV1()
    }

    /**
     * 坏库不得被静默重建（契约 4.3）：模拟「迁移半途失败留下的库」——
     * 表还是 v1 结构但 user_version 已标 2。Room 打开时 identity hash 校验失败，
     * 打开失败，文件与数据原样保留，不做破坏性重建。
     */
    @Test
    fun failing_migration_does_not_destroy_data() = runBlocking {
        val legacy = createLegacyV1(dbName)
        legacy.execSQL(
            "INSERT INTO articles (localId, instanceKey, feedId, guid, link, title, publishedAt, feedTitle, feedUrl, isRead, isStarred, starredAt, stateUpdatedAt) " +
                "VALUES ('a1', 'inst-a', 'feed-1', 'g1', NULL, '标题 a1', NULL, '示例源', 'https://example.invalid/feed.xml', 0, 1, 1700000000000, 1)",
        )
        legacy.version = 2
        legacy.close()
        val sizeBefore = file().length()

        try {
            Room.databaseBuilder(context, CatLeafDatabase::class.java, dbName).build()
                .openHelper.writableDatabase
            fail("坏库必须让打开失败，不能静默继续")
        } catch (expected: Exception) {
            // 预期：Room cannot verify the data integrity
        }

        // 不删库地重新打开（user_version=2 与 helper 一致 → 不触发 onCreate），坏库数据仍在
        val raw = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        ).writableDatabase
        legacy_data_preserved(raw)
        raw.close()
        assertEquals("失败不得重建或清空数据库文件", sizeBefore, file().length())
        db = openV1()
    }

    private fun legacy_data_preserved(db: SupportSQLiteDatabase) {
        db.query("SELECT COUNT(*) FROM articles").use { c ->
            c.moveToFirst()
            org.junit.Assert.assertEquals("数据原样保留", 1, c.getInt(0))
        }
    }

    /** 手工创建 v1 结构的库（建表语句逐字取自 schemas/1.json），user_version=1 */
    private fun createLegacyV1(name: String): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        V1_CREATE_SQL.forEach(db::execSQL)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        return helper.writableDatabase
    }

    private companion object {
        /** v1 建表语句（逐字取自 schemas/1.json，迁移测试的起点） */
        val V1_CREATE_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `instances` (`instanceKey` TEXT NOT NULL, `origin` TEXT NOT NULL, `displayName` TEXT NOT NULL, `lastConnectedAt` INTEGER NOT NULL, PRIMARY KEY(`instanceKey`))",
            "CREATE TABLE IF NOT EXISTS `feeds` (`instanceKey` TEXT NOT NULL, `feedId` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL, `status` TEXT NOT NULL, `lastSuccessAt` INTEGER, PRIMARY KEY(`instanceKey`, `feedId`))",
            "CREATE TABLE IF NOT EXISTS `articles` (`localId` TEXT NOT NULL, `instanceKey` TEXT NOT NULL, `feedId` TEXT NOT NULL, `guid` TEXT, `link` TEXT, `title` TEXT NOT NULL, `publishedAt` TEXT, `feedTitle` TEXT NOT NULL, `feedUrl` TEXT NOT NULL, `isRead` INTEGER NOT NULL, `isStarred` INTEGER NOT NULL, `starredAt` INTEGER, `stateUpdatedAt` INTEGER NOT NULL, PRIMARY KEY(`localId`))",
            "CREATE TABLE IF NOT EXISTS `article_content` (`localId` TEXT NOT NULL, `content` TEXT, `summary` TEXT, `thumbnail` TEXT, `contentSource` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `lastAccessedAt` INTEGER NOT NULL, PRIMARY KEY(`localId`), FOREIGN KEY(`localId`) REFERENCES `articles`(`localId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `ai_cache` (`cacheKey` TEXT NOT NULL, `instanceKey` TEXT NOT NULL, `task` TEXT NOT NULL, `endpoint` TEXT NOT NULL, `model` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `language` TEXT NOT NULL, `promptVersion` TEXT NOT NULL, `result` TEXT NOT NULL, `complete` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))",
            "CREATE INDEX IF NOT EXISTS `index_feeds_instanceKey` ON `feeds` (`instanceKey`)",
            "CREATE INDEX IF NOT EXISTS `index_articles_instanceKey_feedId` ON `articles` (`instanceKey`, `feedId`)",
            "CREATE INDEX IF NOT EXISTS `index_articles_instanceKey_guid` ON `articles` (`instanceKey`, `guid`)",
            "CREATE INDEX IF NOT EXISTS `index_articles_instanceKey_link` ON `articles` (`instanceKey`, `link`)",
            "CREATE INDEX IF NOT EXISTS `index_articles_isStarred_starredAt` ON `articles` (`isStarred`, `starredAt`)",
            "CREATE INDEX IF NOT EXISTS `index_article_content_lastAccessedAt` ON `article_content` (`lastAccessedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_ai_cache_instanceKey_task` ON `ai_cache` (`instanceKey`, `task`)",
        )
    }
}
