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
        SharedTagGroupEntity::class,
        SharedTagGroupMemberEntity::class,
        SharedTagGroupTagEntity::class,
        SharedTagSyncOutboxEntity::class,
        SharedTagSyncStateEntity::class,
        VideoAssetEntity::class,
        VideoDownloadEntity::class,
        AiReceiptEntity::class,
        AiReceiptSourceEntity::class,
        AiDraftEntity::class,
        AiDiffProposalEntity::class,
    ],
    version = 22,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun urlEntryDao(): UrlEntryDao
    abstract fun collectionDao(): CollectionDao
    abstract fun tagDao(): TagDao
    abstract fun sharedTagSyncDao(): SharedTagSyncDao
    abstract fun videoAssetDao(): VideoAssetDao
    abstract fun videoDownloadDao(): VideoDownloadDao
    abstract fun aiTransparencyDao(): AiTransparencyDao

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
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_20,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
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

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tag_url_cross_refs ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE tag_url_cross_refs
                    SET createdAt = COALESCE(
                        (
                            SELECT createdAt
                            FROM url_entries
                            WHERE url_entries.id = tag_url_cross_refs.entryId
                        ),
                        0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tag_url_cross_refs_tagId_createdAt ON tag_url_cross_refs(tagId, createdAt)",
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSharedTagGroupTables(db)
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSharedTagGroupTables(db)
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "shared_tag_members", "displayName", "TEXT")
                addColumnIfMissing(db, "shared_tag_group_members", "displayName", "TEXT")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMediaTables(db)
            }
        }

        val MIGRATION_18_20 = object : Migration(18, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMediaTables(db)
                dropPostPlanColumnsIfPresent(db)
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMediaTables(db)
                dropPostPlanColumnsIfPresent(db)
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureMediaTables(db)
                addColumnIfMissing(db, "video_assets", "sortIndex", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureAiTransparencyTables(db)
            }
        }

        private fun dropPostPlanColumnsIfPresent(db: SupportSQLiteDatabase) {
            dropColumnIfPresent(db, "url_entries", "pendingDeleteOriginState")
        }

        private fun ensureMediaTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS video_assets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    entryId INTEGER NOT NULL,
                    provider TEXT NOT NULL,
                    providerAssetId TEXT NOT NULL,
                    sourceUrl TEXT NOT NULL,
                    canonicalPostUrl TEXT,
                    authorName TEXT,
                    title TEXT,
                    bodyText TEXT,
                    thumbnailUrl TEXT,
                    durationMs INTEGER,
                    mediaType TEXT NOT NULL,
                    hasVideo TEXT NOT NULL,
                    resolveStatus TEXT NOT NULL,
                    downloadUrl TEXT,
                    requestHeadersJson TEXT,
                    mimeType TEXT,
                    qualityLabel TEXT,
                    width INTEGER,
                    height INTEGER,
                    bitrate INTEGER,
                    sortIndex INTEGER NOT NULL DEFAULT 0,
                    isPreferred INTEGER NOT NULL,
                    checkedAt INTEGER NOT NULL,
                    expiresAt INTEGER,
                    errorReason TEXT,
                    FOREIGN KEY(entryId) REFERENCES url_entries(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS video_downloads (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    entryId INTEGER NOT NULL,
                    videoAssetId INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    progress INTEGER NOT NULL,
                    bytesDownloaded INTEGER,
                    totalBytes INTEGER,
                    localUri TEXT,
                    fileName TEXT,
                    startedAt INTEGER,
                    savedAt INTEGER,
                    errorMessage TEXT,
                    FOREIGN KEY(entryId) REFERENCES url_entries(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(videoAssetId) REFERENCES video_assets(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_video_assets_entryId ON video_assets(entryId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_video_assets_provider ON video_assets(provider)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_video_assets_resolveStatus ON video_assets(resolveStatus)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_video_assets_entryId_provider_providerAssetId ON video_assets(entryId, provider, providerAssetId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_video_downloads_entryId ON video_downloads(entryId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_video_downloads_videoAssetId ON video_downloads(videoAssetId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_video_downloads_status ON video_downloads(status)")
        }

        private fun ensureAiTransparencyTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_receipts (
                    receiptId TEXT NOT NULL,
                    actionKind TEXT NOT NULL,
                    destination TEXT NOT NULL,
                    generatedAtIso TEXT NOT NULL,
                    redactionProfile TEXT NOT NULL,
                    requestSizeBucket TEXT NOT NULL,
                    responseSizeBucket TEXT NOT NULL,
                    rawBodyIncluded INTEGER NOT NULL,
                    rawPromptIncluded INTEGER NOT NULL,
                    PRIMARY KEY(receiptId)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_receipt_sources (
                    receiptId TEXT NOT NULL,
                    publicSafeId TEXT NOT NULL,
                    entryId INTEGER,
                    title TEXT NOT NULL,
                    normalizedUrl TEXT NOT NULL,
                    tagNamesJson TEXT NOT NULL,
                    sharedTagBoundary TEXT NOT NULL,
                    aiEligible INTEGER NOT NULL,
                    exclusionReasonsJson TEXT NOT NULL,
                    PRIMARY KEY(receiptId, publicSafeId),
                    FOREIGN KEY(receiptId) REFERENCES ai_receipts(receiptId) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(entryId) REFERENCES url_entries(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_receipt_sources_entryId ON ai_receipt_sources(entryId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_drafts (
                    draftId TEXT NOT NULL,
                    receiptId TEXT NOT NULL,
                    generatedAtIso TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    citedSourceIdsJson TEXT NOT NULL,
                    status TEXT NOT NULL,
                    PRIMARY KEY(draftId),
                    FOREIGN KEY(receiptId) REFERENCES ai_receipts(receiptId) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_drafts_receiptId ON ai_drafts(receiptId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ai_diff_proposals (
                    proposalId TEXT NOT NULL,
                    draftId TEXT NOT NULL,
                    generatedAtIso TEXT NOT NULL,
                    operationsJson TEXT NOT NULL,
                    applied INTEGER NOT NULL,
                    PRIMARY KEY(proposalId),
                    FOREIGN KEY(draftId) REFERENCES ai_drafts(draftId) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_diff_proposals_draftId ON ai_diff_proposals(draftId)")
        }

        private fun ensureSharedTagGroupTables(db: SupportSQLiteDatabase) {
            dropTableIfColumnless(db, "shared_tag_groups")
            dropTableIfColumnless(db, "shared_tag_group_members")
            dropTableIfColumnless(db, "shared_tag_group_tags")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS shared_tag_groups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    authUserId TEXT NOT NULL,
                    remoteGroupId TEXT NOT NULL,
                    name TEXT NOT NULL,
                    currentUserRole TEXT,
                    deletedAt INTEGER,
                    lastSyncedAt INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_tag_groups_authUserId ON shared_tag_groups(authUserId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_shared_tag_groups_authUserId_remoteGroupId ON shared_tag_groups(authUserId, remoteGroupId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS shared_tag_group_members (
                    groupId INTEGER NOT NULL,
                    authUserId TEXT NOT NULL,
                    userId TEXT NOT NULL,
                    displayName TEXT,
                    role TEXT NOT NULL,
                    status TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(groupId, userId),
                    FOREIGN KEY(groupId) REFERENCES shared_tag_groups(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_tag_group_members_authUserId ON shared_tag_group_members(authUserId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_tag_group_members_userId ON shared_tag_group_members(userId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS shared_tag_group_tags (
                    groupId INTEGER NOT NULL,
                    tagId INTEGER NOT NULL,
                    authUserId TEXT NOT NULL,
                    remoteGroupId TEXT NOT NULL,
                    remoteTagId TEXT NOT NULL,
                    addedBy TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    PRIMARY KEY(groupId, tagId),
                    FOREIGN KEY(groupId) REFERENCES shared_tag_groups(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_tag_group_tags_authUserId ON shared_tag_group_tags(authUserId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_shared_tag_group_tags_tagId ON shared_tag_group_tags(tagId)")
        }

        private fun dropTableIfColumnless(db: SupportSQLiteDatabase, tableName: String) {
            val hasTable = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(tableName),
            ).use { cursor -> cursor.moveToFirst() }
            if (!hasTable) return

            val columnCount = db.query("PRAGMA table_info(`$tableName`)").use { cursor -> cursor.count }
            if (columnCount == 0) {
                db.execSQL("DROP TABLE `$tableName`")
            }
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            columnDefinition: String,
        ) {
            val hasColumn = db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        found = true
                        break
                    }
                }
                found
            }
            if (!hasColumn) {
                db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefinition")
            }
        }

        private fun dropColumnIfPresent(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
        ) {
            val hasColumn = db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        found = true
                        break
                    }
                }
                found
            }
            if (hasColumn) {
                db.execSQL("ALTER TABLE `$tableName` DROP COLUMN `$columnName`")
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
