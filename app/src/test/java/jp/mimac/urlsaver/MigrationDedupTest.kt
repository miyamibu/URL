package jp.mimac.urlsaver

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import org.junit.Assert.assertEquals
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
}
