package jp.mimac.urlsaver.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UrlEntryEntity::class,
        CollectionEntity::class,
        TagEntity::class,
        TagUrlCrossRef::class,
        UserLabelEntity::class,
        SharedTagMemberEntity::class,
        SharedTagSyncOutboxEntity::class,
        SharedTagSyncStateEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun urlEntryDao(): UrlEntryDao
    abstract fun collectionDao(): CollectionDao
    abstract fun tagDao(): TagDao
    abstract fun userLabelDao(): UserLabelDao
    abstract fun sharedTagSyncDao(): SharedTagSyncDao

    companion object {
        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, "url_saver.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                )
                .addCallback(
                    object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            val now = System.currentTimeMillis()
                            db.execSQL(
                                "INSERT OR IGNORE INTO collections (id, name, sortOrder, createdAt, updatedAt) VALUES (1, ?, 0, ?, ?)",
                                arrayOf(DEFAULT_COLLECTION_NAME, now, now),
                            )
                        }
                    },
                )
                .build()
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val rows = mutableListOf<Row>()
                db.query(
                    """
                    SELECT id, normalizedUrl, recordState, metadataState, updatedAt, createdAt,
                           fetchedTitle, thumbnailUrl, canonicalId, rawSourceHost, normalizedHost, metadataFetchedAt
                    FROM url_entries
                    ORDER BY normalizedUrl ASC,
                             CASE recordState WHEN 'ACTIVE' THEN 3 WHEN 'ARCHIVED' THEN 2 WHEN 'PENDING_DELETE' THEN 1 ELSE 0 END DESC,
                             CASE metadataState WHEN 'READY' THEN 1 ELSE 0 END DESC,
                             updatedAt DESC,
                             createdAt DESC,
                             id DESC
                    """.trimIndent(),
                ).use { c ->
                    while (c.moveToNext()) {
                        rows += Row(
                            id = c.getLong(0),
                            normalizedUrl = c.getString(1),
                            recordState = c.getString(2),
                            metadataState = c.getString(3),
                            updatedAt = c.getLong(4),
                            createdAt = c.getLong(5),
                            fetchedTitle = c.getStringOrNull(6),
                            thumbnailUrl = c.getStringOrNull(7),
                            canonicalId = c.getStringOrNull(8),
                            rawSourceHost = c.getStringOrNull(9),
                            normalizedHost = c.getStringOrNull(10),
                            metadataFetchedAt = c.getLongOrNull(11),
                        )
                    }
                }

                val grouped = rows.groupBy { it.normalizedUrl }
                grouped.values.forEach { group ->
                    if (group.size <= 1) return@forEach
                    val survivor = group.first()

                    group.drop(1).forEach { donor ->
                        db.execSQL(
                            """
                            UPDATE url_entries
                            SET fetchedTitle = COALESCE(fetchedTitle, ?),
                                thumbnailUrl = COALESCE(thumbnailUrl, ?),
                                canonicalId = COALESCE(canonicalId, ?),
                                rawSourceHost = COALESCE(rawSourceHost, ?),
                                normalizedHost = COALESCE(normalizedHost, ?),
                                metadataFetchedAt = COALESCE(metadataFetchedAt, ?)
                            WHERE id = ?
                            """.trimIndent(),
                            arrayOf(
                                donor.fetchedTitle,
                                donor.thumbnailUrl,
                                donor.canonicalId,
                                donor.rawSourceHost,
                                donor.normalizedHost,
                                donor.metadataFetchedAt,
                                survivor.id,
                            ),
                        )
                    }

                    group.drop(1).forEach { duplicate ->
                        db.execSQL("DELETE FROM url_entries WHERE id = ?", arrayOf(duplicate.id))
                    }
                }

                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_url_entries_normalizedUrl ON url_entries(normalizedUrl)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE url_entries ADD COLUMN metadataRequestedAt INTEGER")
                db.execSQL(
                    """
                    UPDATE url_entries
                    SET metadataRequestedAt =
                        CASE
                            WHEN metadataState = 'PENDING' THEN createdAt
                            ELSE NULL
                        END
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE url_entries ADD COLUMN fetchedBody TEXT")
                db.execSQL("ALTER TABLE url_entries ADD COLUMN bodySummary TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE url_entries ADD COLUMN badgeImageUrl TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS collections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_collections_name ON collections(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collections_sortOrder ON collections(sortOrder)")
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT OR IGNORE INTO collections (id, name, sortOrder, createdAt, updatedAt) VALUES (1, ?, 0, ?, ?)",
                    arrayOf(DEFAULT_COLLECTION_NAME, now, now),
                )
                db.execSQL("ALTER TABLE url_entries ADD COLUMN collectionId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_url_entries_collectionId ON url_entries(collectionId)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE url_entries ADD COLUMN fetchedBodyKind TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tags (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tags_name ON tags(name)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tag_url_cross_refs (
                        tagId INTEGER NOT NULL,
                        entryId INTEGER NOT NULL,
                        PRIMARY KEY(tagId, entryId),
                        FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(entryId) REFERENCES url_entries(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_url_cross_refs_entryId ON tag_url_cross_refs(entryId)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE url_entries ADD COLUMN description TEXT")
                db.execSQL("ALTER TABLE url_entries ADD COLUMN userLabelId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_url_entries_userLabelId ON url_entries(userLabelId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_labels (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_user_labels_name ON user_labels(name)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_tags_name")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tags_name ON tags(name)")
                db.execSQL("ALTER TABLE tags ADD COLUMN scope TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                db.execSQL("ALTER TABLE tags ADD COLUMN authUserId TEXT")
                db.execSQL("ALTER TABLE tags ADD COLUMN remoteTagId TEXT")
                db.execSQL("ALTER TABLE tags ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                db.execSQL("ALTER TABLE tags ADD COLUMN remoteVersion INTEGER")
                db.execSQL("ALTER TABLE tags ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE tags ADD COLUMN lastSyncedAt INTEGER")
                db.execSQL("ALTER TABLE tags ADD COLUMN syncErrorMessage TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tags_scope ON tags(scope)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tags_authUserId ON tags(authUserId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tags_authUserId_remoteTagId ON tags(authUserId, remoteTagId)")

                db.execSQL("ALTER TABLE tag_url_cross_refs ADD COLUMN scope TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                db.execSQL("ALTER TABLE tag_url_cross_refs ADD COLUMN authUserId TEXT")
                db.execSQL("ALTER TABLE tag_url_cross_refs ADD COLUMN remoteUrlId TEXT")
                db.execSQL("ALTER TABLE tag_url_cross_refs ADD COLUMN rawUrl TEXT")
                db.execSQL("ALTER TABLE tag_url_cross_refs ADD COLUMN normalizedUrl TEXT")
                db.execSQL("ALTER TABLE tag_url_cross_refs ADD COLUMN normalizationVersion INTEGER")
                db.execSQL("ALTER TABLE tag_url_cross_refs ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                db.execSQL("ALTER TABLE tag_url_cross_refs ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE tag_url_cross_refs ADD COLUMN lastSyncedAt INTEGER")
                db.execSQL("ALTER TABLE tag_url_cross_refs ADD COLUMN syncErrorMessage TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_url_cross_refs_authUserId ON tag_url_cross_refs(authUserId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tag_url_cross_refs_authUserId_remoteUrlId ON tag_url_cross_refs(authUserId, remoteUrlId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS shared_tag_members (
                        tagId INTEGER NOT NULL,
                        authUserId TEXT NOT NULL,
                        userId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(tagId, userId),
                        FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_tag_members_authUserId ON shared_tag_members(authUserId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_tag_members_userId ON shared_tag_members(userId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS shared_tag_sync_outbox (
                        opId TEXT NOT NULL PRIMARY KEY,
                        clientId TEXT NOT NULL,
                        authUserId TEXT NOT NULL,
                        operationType TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        state TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        lastErrorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_tag_sync_outbox_authUserId_createdAt ON shared_tag_sync_outbox(authUserId, createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_tag_sync_outbox_authUserId_state ON shared_tag_sync_outbox(authUserId, state)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS shared_tag_sync_state (
                        authUserId TEXT NOT NULL PRIMARY KEY,
                        clientId TEXT NOT NULL,
                        lastPulledAt INTEGER,
                        lastSyncSucceededAt INTEGER,
                        lastSyncFailedAt INTEGER,
                        lastErrorMessage TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE url_entries ADD COLUMN localProvenanceCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE url_entries ADD COLUMN sharedReferenceCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_url_entries_localProvenanceCount_recordState ON url_entries(localProvenanceCount, recordState)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_url_entries_sharedReferenceCount ON url_entries(sharedReferenceCount)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE url_entries ADD COLUMN fetchedAuthorName TEXT")
            }
        }
    }
}

private data class Row(
    val id: Long,
    val normalizedUrl: String,
    val recordState: String,
    val metadataState: String,
    val updatedAt: Long,
    val createdAt: Long,
    val fetchedTitle: String?,
    val thumbnailUrl: String?,
    val canonicalId: String?,
    val rawSourceHost: String?,
    val normalizedHost: String?,
    val metadataFetchedAt: Long?,
)

private fun android.database.Cursor.getStringOrNull(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}

private fun android.database.Cursor.getLongOrNull(index: Int): Long? {
    return if (isNull(index)) null else getLong(index)
}
