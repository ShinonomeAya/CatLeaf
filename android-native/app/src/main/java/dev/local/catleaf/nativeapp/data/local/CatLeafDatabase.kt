package dev.local.catleaf.nativeapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        InstanceEntity::class,
        FeedEntity::class,
        ArticleEntity::class,
        ArticleContentEntity::class,
        OfflinePackageEntity::class,
        AiCacheEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class CatLeafDatabase : RoomDatabase() {

    abstract fun instances(): InstanceDao
    abstract fun feeds(): FeedDao
    abstract fun articles(): ArticleDao
    abstract fun aiCache(): AiCacheDao
    abstract fun offline(): OfflinePackageDao

    companion object {
        const val NAME = "catleaf.db"

        /**
         * v1→v2（Q01 搜索索引，契约 9.1）：article_content 加 searchText（纯文本摘要）。
         * 存量行回填在迁移里一次性完成（读行→剥标签→更新；2000 行量级一次性成本），
         * 回填失败不抛出（列可空——那些行只是暂不参与摘要搜索，仍可按标题搜）。
         */
        internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE article_content ADD COLUMN searchText TEXT")
                backfillSearchText(db)
            }

            private fun backfillSearchText(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                runCatching {
                    data class Row(val localId: String, val content: String?, val summary: String?)
                    val rows = mutableListOf<Row>()
                    db.query("SELECT localId, content, summary FROM article_content").use { cursor ->
                        while (cursor.moveToNext()) {
                            rows += Row(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                        }
                    }
                    val summaryStmt = db.compileStatement("UPDATE article_content SET searchText = ? WHERE localId = ?")
                    for (row in rows) {
                        summaryStmt.bindString(1, runCatching {
                            dev.local.catleaf.nativeapp.data.article.ArticleFilter.summaryPreview(
                                row.summary ?: row.content, limit = 500,
                            )
                        }.getOrNull() ?: continue)
                        summaryStmt.bindString(2, row.localId)
                        summaryStmt.executeUpdateDelete()
                    }
                }
            }
        }

        /** Q02 keeps the shipped v2 shape intact and adds the saved-body search column in v3. */
        internal val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE article_content ADD COLUMN searchBodyText TEXT")
                val bodyStatement = db.compileStatement("UPDATE article_content SET searchBodyText = ? WHERE localId = ?")
                val summaryStatement = db.compileStatement("UPDATE article_content SET searchText = ? WHERE localId = ?")
                db.query("SELECT localId, content, summary FROM article_content").use { cursor ->
                    while (cursor.moveToNext()) {
                        val localId = cursor.getString(0)
                        cursor.getString(2)?.let { summary ->
                            val text = dev.local.catleaf.nativeapp.data.article.ArticleFilter.summaryPreview(summary, 500)
                            if (text != null) {
                                summaryStatement.bindString(1, text)
                                summaryStatement.bindString(2, localId)
                                summaryStatement.executeUpdateDelete()
                            }
                        }
                        cursor.getString(1)?.let { body ->
                            bodyStatement.bindString(1, dev.local.catleaf.nativeapp.data.article.SearchQuery.bodyText(body))
                            bodyStatement.bindString(2, localId)
                            bodyStatement.executeUpdateDelete()
                        }
                    }
                }
            }
        }

        internal val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `offline_packages` (`localId` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `title` TEXT NOT NULL, `feedTitle` TEXT NOT NULL, `generation` TEXT NOT NULL, `pendingGeneration` TEXT, `status` TEXT NOT NULL, `selectedImages` INTEGER NOT NULL, `imageTotal` INTEGER NOT NULL, `imageSaved` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, `bodyHash` TEXT NOT NULL, `searchBodyText` TEXT NOT NULL, `imageManifest` TEXT NOT NULL, `coverId` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`localId`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_packages_scopeKey` ON `offline_packages` (`scopeKey`)")
            }
        }

        @Volatile
        private var instance: CatLeafDatabase? = null

        fun get(context: Context): CatLeafDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): CatLeafDatabase =
            Room.databaseBuilder(context, CatLeafDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // 不提供 fallbackToDestructiveMigration：迁移缺失时宁可失败进入可恢复错误页，
                // 也不能静默删库（契约 4.3）。
                .build()

        /**
         * 「重置此设备数据」（P07）专用：先关连接再删文件，并丢弃单例让下次 `get()` 重开。
         * 这与"Room 失败不自动删库"不冲突——那是禁止用删库掩盖错误，这里是用户显式、
         * 二次确认后的动作，且只删本机（云端订阅不在此路径上）。
         */
        fun resetForDeviceReset(context: Context) {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
                context.applicationContext.deleteDatabase(NAME)
            }
        }
    }
}
