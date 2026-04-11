package jp.mimac.urlsaver.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [UrlEntryEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun urlEntryDao(): UrlEntryDao

    companion object {
        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, "url_saver.db")
                .addMigrations(MIGRATION_1_2)
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
