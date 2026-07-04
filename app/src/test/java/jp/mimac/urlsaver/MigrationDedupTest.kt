package jp.mimac.urlsaver

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationDedupTest {

    @Test
    fun migration_dedupsAndKeepsSurvivorOriginalUrl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-dedup.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS url_entries (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    originalUrl TEXT NOT NULL,
                                    normalizedUrl TEXT NOT NULL,
                                    displayUrl TEXT NOT NULL,
                                    openUrl TEXT NOT NULL,
                                    normalizedHost TEXT NOT NULL,
                                    rawSourceHost TEXT NOT NULL,
                                    serviceType TEXT NOT NULL,
                                    contentContext TEXT NOT NULL,
                                    userTitle TEXT,
                                    fetchedTitle TEXT,
                                    memo TEXT NOT NULL,
                                    thumbnailUrl TEXT,
                                    canonicalId TEXT,
                                    metadataState TEXT NOT NULL,
                                    metadataError TEXT,
                                    metadataFetchedAt INTEGER,
                                    recordState TEXT NOT NULL,
                                    createdAt INTEGER NOT NULL,
                                    updatedAt INTEGER NOT NULL,
                                    archivedAt INTEGER,
                                    pendingDeletionUntil INTEGER
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = helper.writableDatabase
        db.execSQL(
            """
            INSERT INTO url_entries (
                originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                serviceType, contentContext, userTitle, fetchedTitle, memo, thumbnailUrl,
                canonicalId, metadataState, metadataError, metadataFetchedAt, recordState,
                createdAt, updatedAt, archivedAt, pendingDeletionUntil
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "https://active.example/raw",
                "https://example.com/a",
                "example.com/a",
                "https://example.com/a",
                "example.com",
                "example.com",
                "WEB",
                "STANDARD",
                null,
                null,
                "",
                null,
                null,
                "PENDING",
                null,
                null,
                "ACTIVE",
                100L,
                200L,
                null,
                null,
            ),
        )
        db.execSQL(
            """
            INSERT INTO url_entries (
                originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                serviceType, contentContext, userTitle, fetchedTitle, memo, thumbnailUrl,
                canonicalId, metadataState, metadataError, metadataFetchedAt, recordState,
                createdAt, updatedAt, archivedAt, pendingDeletionUntil
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "https://archived.example/raw",
                "https://example.com/a",
                "example.com/a",
                "https://example.com/a",
                "example.com",
                "example.com",
                "WEB",
                "STANDARD",
                null,
                "donor title",
                "",
                "https://example.com/thumb.jpg",
                "cid",
                "READY",
                null,
                999L,
                "ARCHIVED",
                300L,
                300L,
                301L,
                null,
            ),
        )

        AppDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT COUNT(*) FROM url_entries WHERE normalizedUrl = 'https://example.com/a'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }

        db.query("SELECT originalUrl, fetchedTitle, thumbnailUrl, canonicalId FROM url_entries WHERE normalizedUrl = 'https://example.com/a'").use {
            it.moveToFirst()
            assertEquals("https://active.example/raw", it.getString(0))
            assertEquals("donor title", it.getString(1))
            assertEquals("https://example.com/thumb.jpg", it.getString(2))
            assertEquals("cid", it.getString(3))
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_dedup_complementsFromMultipleDonors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-dedup-multi.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS url_entries (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    originalUrl TEXT NOT NULL,
                                    normalizedUrl TEXT NOT NULL,
                                    displayUrl TEXT NOT NULL,
                                    openUrl TEXT NOT NULL,
                                    normalizedHost TEXT NOT NULL,
                                    rawSourceHost TEXT NOT NULL,
                                    serviceType TEXT NOT NULL,
                                    contentContext TEXT NOT NULL,
                                    userTitle TEXT,
                                    fetchedTitle TEXT,
                                    memo TEXT NOT NULL,
                                    thumbnailUrl TEXT,
                                    canonicalId TEXT,
                                    metadataState TEXT NOT NULL,
                                    metadataError TEXT,
                                    metadataFetchedAt INTEGER,
                                    recordState TEXT NOT NULL,
                                    createdAt INTEGER NOT NULL,
                                    updatedAt INTEGER NOT NULL,
                                    archivedAt INTEGER,
                                    pendingDeletionUntil INTEGER
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = helper.writableDatabase
        val normalized = "https://example.com/multi"
        db.execSQL(
            """
            INSERT INTO url_entries (
                originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                serviceType, contentContext, userTitle, fetchedTitle, memo, thumbnailUrl,
                canonicalId, metadataState, metadataError, metadataFetchedAt, recordState,
                createdAt, updatedAt, archivedAt, pendingDeletionUntil
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "https://active.example/raw",
                normalized,
                "example.com/multi",
                normalized,
                "example.com",
                "example.com",
                "WEB",
                "STANDARD",
                null,
                null,
                "",
                null,
                null,
                "PENDING",
                null,
                null,
                "ACTIVE",
                100L,
                500L,
                null,
                null,
            ),
        )
        db.execSQL(
            """
            INSERT INTO url_entries (
                originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                serviceType, contentContext, userTitle, fetchedTitle, memo, thumbnailUrl,
                canonicalId, metadataState, metadataError, metadataFetchedAt, recordState,
                createdAt, updatedAt, archivedAt, pendingDeletionUntil
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "https://archived.example/raw",
                normalized,
                "example.com/multi",
                normalized,
                "example.com",
                "example.com",
                "WEB",
                "STANDARD",
                null,
                "title from archived",
                "",
                null,
                null,
                "READY",
                null,
                900L,
                "ARCHIVED",
                200L,
                400L,
                401L,
                null,
            ),
        )
        db.execSQL(
            """
            INSERT INTO url_entries (
                originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                serviceType, contentContext, userTitle, fetchedTitle, memo, thumbnailUrl,
                canonicalId, metadataState, metadataError, metadataFetchedAt, recordState,
                createdAt, updatedAt, archivedAt, pendingDeletionUntil
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "https://pending.example/raw",
                normalized,
                "example.com/multi",
                normalized,
                "example.com",
                "example.com",
                "WEB",
                "STANDARD",
                null,
                null,
                "",
                "https://example.com/t.jpg",
                "canon-1",
                "READY",
                null,
                950L,
                "PENDING_DELETE",
                300L,
                300L,
                null,
                999999L,
            ),
        )

        AppDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT COUNT(*) FROM url_entries WHERE normalizedUrl = '$normalized'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query(
            "SELECT originalUrl, fetchedTitle, thumbnailUrl, canonicalId FROM url_entries WHERE normalizedUrl = '$normalized'",
        ).use {
            it.moveToFirst()
            assertEquals("https://active.example/raw", it.getString(0))
            assertEquals("title from archived", it.getString(1))
            assertEquals("https://example.com/t.jpg", it.getString(2))
            assertEquals("canon-1", it.getString(3))
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_2_3_addsMetadataRequestedAtAndBackfillsPendingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-2-3.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS url_entries (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    originalUrl TEXT NOT NULL,
                                    normalizedUrl TEXT NOT NULL,
                                    displayUrl TEXT NOT NULL,
                                    openUrl TEXT NOT NULL,
                                    normalizedHost TEXT NOT NULL,
                                    rawSourceHost TEXT NOT NULL,
                                    serviceType TEXT NOT NULL,
                                    contentContext TEXT NOT NULL,
                                    userTitle TEXT,
                                    fetchedTitle TEXT,
                                    memo TEXT NOT NULL,
                                    thumbnailUrl TEXT,
                                    canonicalId TEXT,
                                    metadataState TEXT NOT NULL,
                                    metadataError TEXT,
                                    metadataFetchedAt INTEGER,
                                    recordState TEXT NOT NULL,
                                    createdAt INTEGER NOT NULL,
                                    updatedAt INTEGER NOT NULL,
                                    archivedAt INTEGER,
                                    pendingDeletionUntil INTEGER
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = helper.writableDatabase
        db.execSQL(
            """
            INSERT INTO url_entries (
                originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                serviceType, contentContext, userTitle, fetchedTitle, memo, thumbnailUrl,
                canonicalId, metadataState, metadataError, metadataFetchedAt, recordState,
                createdAt, updatedAt, archivedAt, pendingDeletionUntil
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "https://pending.example/raw",
                "https://pending.example",
                "pending.example",
                "https://pending.example",
                "pending.example",
                "pending.example",
                "WEB",
                "STANDARD",
                null,
                null,
                "",
                null,
                null,
                "PENDING",
                null,
                null,
                "ACTIVE",
                123L,
                123L,
                null,
                null,
            ),
        )
        db.execSQL(
            """
            INSERT INTO url_entries (
                originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                serviceType, contentContext, userTitle, fetchedTitle, memo, thumbnailUrl,
                canonicalId, metadataState, metadataError, metadataFetchedAt, recordState,
                createdAt, updatedAt, archivedAt, pendingDeletionUntil
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "https://ready.example/raw",
                "https://ready.example",
                "ready.example",
                "https://ready.example",
                "ready.example",
                "ready.example",
                "WEB",
                "STANDARD",
                null,
                "title",
                "",
                null,
                null,
                "READY",
                null,
                555L,
                "ACTIVE",
                456L,
                456L,
                null,
                null,
            ),
        )

        AppDatabase.MIGRATION_2_3.migrate(db)

        db.query("SELECT metadataRequestedAt FROM url_entries WHERE normalizedUrl = 'https://pending.example'").use {
            it.moveToFirst()
            assertEquals(123L, it.getLong(0))
        }
        db.query("SELECT metadataRequestedAt FROM url_entries WHERE normalizedUrl = 'https://ready.example'").use {
            it.moveToFirst()
            assertEquals(true, it.isNull(0))
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_3_4_addsFetchedBodyAndBodySummaryColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-3-4.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS url_entries (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    originalUrl TEXT NOT NULL,
                                    normalizedUrl TEXT NOT NULL,
                                    displayUrl TEXT NOT NULL,
                                    openUrl TEXT NOT NULL,
                                    normalizedHost TEXT NOT NULL,
                                    rawSourceHost TEXT NOT NULL,
                                    serviceType TEXT NOT NULL,
                                    contentContext TEXT NOT NULL,
                                    userTitle TEXT,
                                    fetchedTitle TEXT,
                                    memo TEXT NOT NULL,
                                    thumbnailUrl TEXT,
                                    canonicalId TEXT,
                                    metadataState TEXT NOT NULL,
                                    metadataError TEXT,
                                    metadataRequestedAt INTEGER,
                                    metadataFetchedAt INTEGER,
                                    recordState TEXT NOT NULL,
                                    createdAt INTEGER NOT NULL,
                                    updatedAt INTEGER NOT NULL,
                                    archivedAt INTEGER,
                                    pendingDeletionUntil INTEGER
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = helper.writableDatabase
        db.execSQL(
            """
            INSERT INTO url_entries (
                originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                serviceType, contentContext, userTitle, fetchedTitle, memo, thumbnailUrl,
                canonicalId, metadataState, metadataError, metadataRequestedAt, metadataFetchedAt,
                recordState, createdAt, updatedAt, archivedAt, pendingDeletionUntil
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "https://example.com/raw",
                "https://example.com",
                "example.com",
                "https://example.com",
                "example.com",
                "example.com",
                "WEB",
                "STANDARD",
                null,
                "title",
                "",
                null,
                null,
                "READY",
                null,
                111L,
                222L,
                "ACTIVE",
                100L,
                100L,
                null,
                null,
            ),
        )

        AppDatabase.MIGRATION_3_4.migrate(db)

        db.query("PRAGMA table_info(url_entries)").use { cursor ->
            var hasFetchedBody = false
            var hasBodySummary = false
            while (cursor.moveToNext()) {
                when (cursor.getString(1)) {
                    "fetchedBody" -> hasFetchedBody = true
                    "bodySummary" -> hasBodySummary = true
                }
            }
            assertEquals(true, hasFetchedBody)
            assertEquals(true, hasBodySummary)
        }

        db.query("SELECT fetchedBody, bodySummary FROM url_entries WHERE normalizedUrl = 'https://example.com'").use {
            it.moveToFirst()
            assertEquals(true, it.isNull(0))
            assertEquals(true, it.isNull(1))
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_6_7_addsFetchedBodyKindColumn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-6-7.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS url_entries (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    originalUrl TEXT NOT NULL,
                                    normalizedUrl TEXT NOT NULL,
                                    displayUrl TEXT NOT NULL,
                                    openUrl TEXT NOT NULL,
                                    normalizedHost TEXT NOT NULL,
                                    rawSourceHost TEXT NOT NULL,
                                    collectionId INTEGER NOT NULL,
                                    serviceType TEXT NOT NULL,
                                    contentContext TEXT NOT NULL,
                                    userTitle TEXT,
                                    fetchedTitle TEXT,
                                    fetchedBody TEXT,
                                    bodySummary TEXT,
                                    memo TEXT NOT NULL,
                                    thumbnailUrl TEXT,
                                    badgeImageUrl TEXT,
                                    canonicalId TEXT,
                                    metadataState TEXT NOT NULL,
                                    metadataError TEXT,
                                    metadataRequestedAt INTEGER,
                                    metadataFetchedAt INTEGER,
                                    recordState TEXT NOT NULL,
                                    createdAt INTEGER NOT NULL,
                                    updatedAt INTEGER NOT NULL,
                                    archivedAt INTEGER,
                                    pendingDeletionUntil INTEGER
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = helper.writableDatabase
        db.execSQL(
            """
            INSERT INTO url_entries (
                originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                collectionId, serviceType, contentContext, userTitle, fetchedTitle, fetchedBody,
                bodySummary, memo, thumbnailUrl, badgeImageUrl, canonicalId, metadataState,
                metadataError, metadataRequestedAt, metadataFetchedAt, recordState, createdAt,
                updatedAt, archivedAt, pendingDeletionUntil
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "https://example.com/raw",
                "https://example.com",
                "example.com",
                "https://example.com",
                "example.com",
                "example.com",
                1L,
                "WEB",
                "STANDARD",
                null,
                "title",
                "body",
                "summary",
                "",
                null,
                null,
                null,
                "READY",
                null,
                null,
                111L,
                "ACTIVE",
                100L,
                100L,
                null,
                null,
            ),
        )

        AppDatabase.MIGRATION_6_7.migrate(db)

        db.query("PRAGMA table_info(url_entries)").use { cursor ->
            var hasFetchedBodyKind = false
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == "fetchedBodyKind") {
                    hasFetchedBodyKind = true
                    break
                }
            }
            assertEquals(true, hasFetchedBodyKind)
        }
        db.query("SELECT fetchedBodyKind FROM url_entries WHERE normalizedUrl = 'https://example.com'").use {
            it.moveToFirst()
            assertEquals(true, it.isNull(0))
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_7_8_preservesExistingData_andCreatesTagTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-7-8.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS url_entries (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    originalUrl TEXT NOT NULL,
                                    normalizedUrl TEXT NOT NULL,
                                    displayUrl TEXT NOT NULL,
                                    openUrl TEXT NOT NULL,
                                    normalizedHost TEXT NOT NULL,
                                    rawSourceHost TEXT NOT NULL,
                                    collectionId INTEGER NOT NULL,
                                    serviceType TEXT NOT NULL,
                                    contentContext TEXT NOT NULL,
                                    userTitle TEXT,
                                    fetchedTitle TEXT,
                                    fetchedBody TEXT,
                                    fetchedBodyKind TEXT,
                                    bodySummary TEXT,
                                    memo TEXT NOT NULL,
                                    thumbnailUrl TEXT,
                                    badgeImageUrl TEXT,
                                    canonicalId TEXT,
                                    metadataState TEXT NOT NULL,
                                    metadataError TEXT,
                                    metadataRequestedAt INTEGER,
                                    metadataFetchedAt INTEGER,
                                    recordState TEXT NOT NULL,
                                    createdAt INTEGER NOT NULL,
                                    updatedAt INTEGER NOT NULL,
                                    archivedAt INTEGER,
                                    pendingDeletionUntil INTEGER
                                )
                                """.trimIndent(),
                            )
                            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_url_entries_normalizedUrl ON url_entries(normalizedUrl)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_url_entries_collectionId ON url_entries(collectionId)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_url_entries_recordState ON url_entries(recordState)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_url_entries_serviceType ON url_entries(serviceType)")
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO collections (id, name, sortOrder, createdAt, updatedAt) VALUES (1, '受信箱', 0, 100, 100)",
        )
        db.execSQL(
            """
            INSERT INTO url_entries (
                id, originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                collectionId, serviceType, contentContext, userTitle, fetchedTitle, fetchedBody,
                fetchedBodyKind, bodySummary, memo, thumbnailUrl, badgeImageUrl, canonicalId,
                metadataState, metadataError, metadataRequestedAt, metadataFetchedAt, recordState,
                createdAt, updatedAt, archivedAt, pendingDeletionUntil
            ) VALUES (
                1, 'https://example.com/raw', 'https://example.com/raw', 'example.com/raw',
                'https://example.com/raw', 'example.com', 'example.com', 1, 'WEB', 'STANDARD',
                NULL, NULL, NULL, NULL, NULL, '', NULL, NULL, NULL, 'PENDING', NULL, 10, NULL,
                'ACTIVE', 10, 10, NULL, NULL
            )
            """.trimIndent(),
        )

        AppDatabase.MIGRATION_7_8.migrate(db)

        db.query("SELECT COUNT(*) FROM collections WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM url_entries WHERE id = 1").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'tags'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'tag_url_cross_refs'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_tags_name'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_tag_url_cross_refs_entryId'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_8_9_preservesExistingData_andCreatesUserLabels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-8-9.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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
                                    PRIMARY KEY(tagId, entryId)
                                )
                                """.trimIndent(),
                            )
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_tag_url_cross_refs_entryId ON tag_url_cross_refs(entryId)")
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS url_entries (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    originalUrl TEXT NOT NULL,
                                    normalizedUrl TEXT NOT NULL,
                                    displayUrl TEXT NOT NULL,
                                    openUrl TEXT NOT NULL,
                                    normalizedHost TEXT NOT NULL,
                                    rawSourceHost TEXT NOT NULL,
                                    collectionId INTEGER NOT NULL,
                                    serviceType TEXT NOT NULL,
                                    contentContext TEXT NOT NULL,
                                    userTitle TEXT,
                                    fetchedTitle TEXT,
                                    fetchedBody TEXT,
                                    fetchedBodyKind TEXT,
                                    bodySummary TEXT,
                                    memo TEXT NOT NULL,
                                    thumbnailUrl TEXT,
                                    badgeImageUrl TEXT,
                                    canonicalId TEXT,
                                    metadataState TEXT NOT NULL,
                                    metadataError TEXT,
                                    metadataRequestedAt INTEGER,
                                    metadataFetchedAt INTEGER,
                                    recordState TEXT NOT NULL,
                                    createdAt INTEGER NOT NULL,
                                    updatedAt INTEGER NOT NULL,
                                    archivedAt INTEGER,
                                    pendingDeletionUntil INTEGER
                                )
                                """.trimIndent(),
                            )
                            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_url_entries_normalizedUrl ON url_entries(normalizedUrl)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_url_entries_collectionId ON url_entries(collectionId)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_url_entries_recordState ON url_entries(recordState)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS index_url_entries_serviceType ON url_entries(serviceType)")
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = helper.writableDatabase
        db.execSQL("INSERT INTO collections (id, name, sortOrder, createdAt, updatedAt) VALUES (1, '受信箱', 0, 100, 100)")
        db.execSQL(
            """
            INSERT INTO url_entries (
                id, originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                collectionId, serviceType, contentContext, userTitle, fetchedTitle, fetchedBody,
                fetchedBodyKind, bodySummary, memo, thumbnailUrl, badgeImageUrl, canonicalId,
                metadataState, metadataError, metadataRequestedAt, metadataFetchedAt, recordState,
                createdAt, updatedAt, archivedAt, pendingDeletionUntil
            ) VALUES (
                1, 'https://example.com/raw', 'https://example.com/raw', 'example.com/raw',
                'https://example.com/raw', 'example.com', 'example.com', 1, 'WEB', 'STANDARD',
                NULL, 'Saved title', 'Saved body', 'WEB_DESCRIPTION', 'Saved summary', '', NULL, NULL, NULL,
                'READY', NULL, 10, 20, 'ACTIVE', 10, 10, NULL, NULL
            )
            """.trimIndent(),
        )

        AppDatabase.MIGRATION_8_9.migrate(db)

        db.query("SELECT fetchedTitle, description, userLabelId FROM url_entries WHERE id = 1").use {
            it.moveToFirst()
            assertEquals("Saved title", it.getString(0))
            assertTrue(it.isNull(1))
            assertTrue(it.isNull(2))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'user_labels'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_user_labels_name'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_url_entries_userLabelId'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_12_13_backfillsTagUrlCrossRefCreatedAtFromEntry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-tag-ref-created-at.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(12) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS url_entries (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    originalUrl TEXT NOT NULL,
                                    normalizedUrl TEXT NOT NULL,
                                    displayUrl TEXT NOT NULL,
                                    openUrl TEXT NOT NULL,
                                    normalizedHost TEXT NOT NULL,
                                    rawSourceHost TEXT NOT NULL,
                                    collectionId INTEGER NOT NULL,
                                    serviceType TEXT NOT NULL,
                                    contentContext TEXT NOT NULL,
                                    userTitle TEXT,
                                    fetchedTitle TEXT,
                                    fetchedBody TEXT,
                                    fetchedBodyKind TEXT,
                                    bodySummary TEXT,
                                    memo TEXT NOT NULL,
                                    thumbnailUrl TEXT,
                                    badgeImageUrl TEXT,
                                    canonicalId TEXT,
                                    metadataState TEXT NOT NULL,
                                    metadataError TEXT,
                                    metadataRequestedAt INTEGER,
                                    metadataFetchedAt INTEGER,
                                    fetchedAuthorName TEXT,
                                    recordState TEXT NOT NULL,
                                    localProvenanceCount INTEGER NOT NULL,
                                    sharedReferenceCount INTEGER NOT NULL,
                                    createdAt INTEGER NOT NULL,
                                    updatedAt INTEGER NOT NULL,
                                    archivedAt INTEGER,
                                    pendingDeletionUntil INTEGER
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS tags (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    name TEXT NOT NULL,
                                    createdAt INTEGER NOT NULL,
                                    scope TEXT NOT NULL,
                                    authUserId TEXT,
                                    remoteTagId TEXT,
                                    syncStatus TEXT NOT NULL,
                                    remoteVersion INTEGER,
                                    deletedAt INTEGER,
                                    lastSyncedAt INTEGER,
                                    syncErrorMessage TEXT
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS tag_url_cross_refs (
                                    tagId INTEGER NOT NULL,
                                    entryId INTEGER NOT NULL,
                                    scope TEXT NOT NULL,
                                    authUserId TEXT,
                                    remoteUrlId TEXT,
                                    rawUrl TEXT,
                                    normalizedUrl TEXT,
                                    normalizationVersion INTEGER,
                                    syncStatus TEXT NOT NULL,
                                    deletedAt INTEGER,
                                    lastSyncedAt INTEGER,
                                    syncErrorMessage TEXT,
                                    PRIMARY KEY(tagId, entryId)
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val db = helper.writableDatabase
        db.execSQL(
            """
            INSERT INTO url_entries (
                id, originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                collectionId, serviceType, contentContext, memo, metadataState, recordState,
                localProvenanceCount, sharedReferenceCount, createdAt, updatedAt
            ) VALUES (
                10, 'https://example.com/old', 'https://example.com/old', 'example.com/old',
                'https://example.com/old', 'example.com', 'example.com', 1, 'WEB', 'STANDARD',
                '', 'PENDING', 'ACTIVE', 1, 0, 12345, 12345
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO tags (id, name, createdAt, scope, syncStatus) VALUES (20, 'tag', 1, 'LOCAL_ONLY', 'LOCAL_ONLY')",
        )
        db.execSQL(
            """
            INSERT INTO tag_url_cross_refs (
                tagId, entryId, scope, syncStatus
            ) VALUES (
                20, 10, 'LOCAL_ONLY', 'LOCAL_ONLY'
            )
            """.trimIndent(),
        )

        AppDatabase.MIGRATION_12_13.migrate(db)

        db.query("SELECT createdAt FROM tag_url_cross_refs WHERE tagId = 20 AND entryId = 10").use {
            it.moveToFirst()
            assertEquals(12345L, it.getLong(0))
        }
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_tag_url_cross_refs_tagId_createdAt'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_18_20_preservesEntriesAndCreatesMediaTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-18-20.db"
        context.deleteDatabase(dbName)
        val helper = createPostPlanHelper(context, dbName, includePendingDeleteOriginState = false)
        val db = helper.writableDatabase
        insertPostPlanEntry(db)

        AppDatabase.MIGRATION_18_20.migrate(db)

        db.query("SELECT COUNT(*) FROM url_entries WHERE normalizedUrl = 'https://example.com/media'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        assertTrue(hasTable(db, "video_assets"))
        assertTrue(hasTable(db, "video_downloads"))

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_19_20_dropsPostPlanColumnWithoutDeletingEntries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-19-20.db"
        context.deleteDatabase(dbName)
        val helper = createPostPlanHelper(context, dbName, includePendingDeleteOriginState = true)
        val db = helper.writableDatabase
        insertPostPlanEntry(db)

        AppDatabase.MIGRATION_19_20.migrate(db)

        db.query("SELECT COUNT(*) FROM url_entries WHERE normalizedUrl = 'https://example.com/media'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        assertTrue(!hasColumn(db, "url_entries", "pendingDeleteOriginState"))
        assertTrue(hasTable(db, "video_assets"))
        assertTrue(hasTable(db, "video_downloads"))

        helper.close()
        context.deleteDatabase(dbName)
    }

    private fun createPostPlanHelper(
        context: Context,
        dbName: String,
        includePendingDeleteOriginState: Boolean,
    ): SupportSQLiteOpenHelper {
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(if (includePendingDeleteOriginState) 19 else 18) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            val extraColumn = if (includePendingDeleteOriginState) {
                                ", pendingDeleteOriginState TEXT"
                            } else {
                                ""
                            }
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS url_entries (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    originalUrl TEXT NOT NULL,
                                    normalizedUrl TEXT NOT NULL,
                                    displayUrl TEXT NOT NULL,
                                    openUrl TEXT NOT NULL,
                                    normalizedHost TEXT NOT NULL,
                                    rawSourceHost TEXT NOT NULL,
                                    collectionId INTEGER NOT NULL,
                                    serviceType TEXT NOT NULL,
                                    contentContext TEXT NOT NULL,
                                    userTitle TEXT,
                                    fetchedTitle TEXT,
                                    fetchedAuthorName TEXT,
                                    fetchedBody TEXT,
                                    fetchedBodyKind TEXT,
                                    bodySummary TEXT,
                                    description TEXT,
                                    memo TEXT NOT NULL,
                                    thumbnailUrl TEXT,
                                    badgeImageUrl TEXT,
                                    canonicalId TEXT,
                                    userLabelId INTEGER,
                                    localProvenanceCount INTEGER NOT NULL,
                                    sharedReferenceCount INTEGER NOT NULL,
                                    metadataState TEXT NOT NULL,
                                    metadataError TEXT,
                                    metadataRequestedAt INTEGER,
                                    metadataFetchedAt INTEGER,
                                    recordState TEXT NOT NULL,
                                    createdAt INTEGER NOT NULL,
                                    updatedAt INTEGER NOT NULL,
                                    archivedAt INTEGER,
                                    pendingDeletionUntil INTEGER
                                    $extraColumn
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
    }

    private fun insertPostPlanEntry(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO url_entries (
                originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
                collectionId, serviceType, contentContext, memo, localProvenanceCount,
                sharedReferenceCount, metadataState, recordState, createdAt, updatedAt
            ) VALUES (
                'https://example.com/media', 'https://example.com/media', 'example.com/media',
                'https://example.com/media', 'example.com', 'example.com', 1, 'WEB', 'POST',
                '', 1, 0, 'READY', 'ACTIVE', 100, 200
            )
            """.trimIndent(),
        )
    }

    private fun hasTable(db: androidx.sqlite.db.SupportSQLiteDatabase, tableName: String): Boolean {
        return db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName),
        ).use { cursor -> cursor.moveToFirst() }
    }

    private fun hasColumn(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
    ): Boolean {
        return db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
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
    }
}
