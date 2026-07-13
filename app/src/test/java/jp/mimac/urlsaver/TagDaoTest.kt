package jp.mimac.urlsaver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.TagEntity
import jp.mimac.urlsaver.data.TagUrlCrossRef
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TagDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeAllTagsWithCount_returnsCountsIncludingZero() = runBlocking {
        val tagDao = db.tagDao()
        val firstTagId = tagDao.insertTag(TagEntity(name = "alpha", createdAt = 1L))
        val secondTagId = tagDao.insertTag(TagEntity(name = "beta", createdAt = 2L))
        val entryId = insertEntry("https://example.com/alpha")

        tagDao.insertCrossRef(TagUrlCrossRef(tagId = firstTagId, entryId = entryId))

        val tags = tagDao.observeAllTagsWithCount().first()
        assertEquals(listOf("alpha", "beta"), tags.map { it.name })
        assertEquals(1, tags.first { it.id == firstTagId }.urlCount)
        assertEquals(0, tags.first { it.id == secondTagId }.urlCount)
    }

    @Test
    fun insertCrossRef_isIdempotent() = runBlocking {
        val tagId = db.tagDao().insertTag(TagEntity(name = "stable", createdAt = 1L))
        val entryId = insertEntry("https://example.com/stable")

        val first = db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = entryId))
        val second = db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = entryId))

        assertTrue(first > 0L)
        assertEquals(-1L, second)
    }

    @Test
    fun deleteTag_cascadesCrossRefs() = runBlocking {
        val tagDao = db.tagDao()
        val tagId = tagDao.insertTag(TagEntity(name = "cascade", createdAt = 1L))
        val entryId = insertEntry("https://example.com/cascade")
        tagDao.insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = entryId))

        tagDao.deleteTag(tagId)

        val tagsForEntry = tagDao.observeTagsForEntry(entryId).first()
        assertTrue(tagsForEntry.isEmpty())
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM tag_url_cross_refs").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun observeTagsForEntry_andObserveEntriesForTag_returnExpectedRows() = runBlocking {
        val tagDao = db.tagDao()
        val alphaId = tagDao.insertTag(TagEntity(name = "alpha", createdAt = 1L))
        val betaId = tagDao.insertTag(TagEntity(name = "beta", createdAt = 2L))
        val olderEntryId = insertEntry("https://example.com/older", createdAt = 10L)
        val newerEntryId = insertEntry("https://example.com/newer", createdAt = 20L)

        tagDao.insertCrossRef(TagUrlCrossRef(tagId = betaId, entryId = newerEntryId))
        tagDao.insertCrossRef(TagUrlCrossRef(tagId = alphaId, entryId = newerEntryId, createdAt = 100L))
        tagDao.insertCrossRef(TagUrlCrossRef(tagId = alphaId, entryId = olderEntryId, createdAt = 200L))

        val tagsForEntry = tagDao.observeTagsForEntry(newerEntryId).first()
        assertEquals(listOf("alpha", "beta"), tagsForEntry.map { it.name })

        val entriesForTag = tagDao.observeEntriesForTag(alphaId).first()
        assertEquals(listOf(olderEntryId, newerEntryId), entriesForTag.map { it.id })
    }

    private suspend fun insertEntry(url: String, createdAt: Long = 1L): Long {
        return db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = url,
                normalizedUrl = url,
                displayUrl = url.removePrefix("https://"),
                openUrl = url,
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = ServiceType.WEB,
                contentContext = ContentContext.STANDARD,
                metadataState = MetadataState.PENDING,
                recordState = RecordState.ACTIVE,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
    }
}
