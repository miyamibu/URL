package jp.mimac.urlsaver

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AiActionKind
import jp.mimac.urlsaver.data.AiDiffOperation
import jp.mimac.urlsaver.data.AiSharedTagBoundary
import jp.mimac.urlsaver.data.AiSizeBucket
import jp.mimac.urlsaver.data.AiTransparencyFeature
import jp.mimac.urlsaver.data.AiTransparencyPolicy
import jp.mimac.urlsaver.data.AiTransparencyRepository
import jp.mimac.urlsaver.data.AiTransparencySource
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.MockAiProvider
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiTransparencyRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: AiTransparencyRepository
    private var tick = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AiTransparencyRepository(
            aiTransparencyDao = db.aiTransparencyDao(),
            urlEntryDao = db.urlEntryDao(),
            provider = MockAiProvider(),
            nowIso = { "2026-07-10T00:00:0${tick++}Z" },
            nowMillis = { 10_000L + tick },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun previewExcludesSharedArchivedAndPendingDeleteSources() = runBlocking {
        val activeId = insertEntry("https://example.com/active", RecordState.ACTIVE)
        val sharedId = insertEntry("https://example.com/shared", RecordState.ACTIVE, sharedReferenceCount = 1)
        val archivedId = insertEntry("https://example.com/archived", RecordState.ARCHIVED)
        val pendingId = insertEntry("https://example.com/pending", RecordState.PENDING_DELETE)

        val preview = AiTransparencyPolicy.buildPreview(
            actionKind = AiActionKind.MCP_FETCH,
            destination = "internal-preview",
            sources = listOf(
                source(activeId, "safe-active"),
                source(sharedId, "shared", containsSharedTag = true),
                source(archivedId, "archived"),
                source(pendingId, "pending"),
            ),
        )

        assertTrue(preview.canSend)
        assertEquals(1, preview.eligibleCount)
        assertEquals(3, preview.blockedCount)
        assertEquals(listOf("archived_default_excluded"), preview.sources.single { it.publicSafeId == "archived" }.exclusionReasons)
        assertEquals(listOf("pending_delete_excluded"), preview.sources.single { it.publicSafeId == "pending" }.exclusionReasons)
        assertEquals(listOf("shared_tag_default_excluded"), preview.sources.single { it.publicSafeId == "shared" }.exclusionReasons)
    }

    @Test
    fun receiptPersistsMetadataOnlyWithSizeBuckets() = runBlocking {
        val entryId = insertEntry("https://example.com/safe", RecordState.ACTIVE)
        val preview = previewOf(source(entryId, "safe-1"))

        val receipt = repository.saveReceipt(preview, requestBytes = 1_337, responseBytes = 2_000_000)
        val loaded = repository.loadReceipt(receipt.receiptId)!!

        assertEquals(listOf("safe-1"), loaded.sentSourceIds)
        assertEquals(AiSizeBucket.SMALL, loaded.requestSizeBucket)
        assertEquals(AiSizeBucket.HUGE, loaded.responseSizeBucket)
        assertFalse(loaded.rawPromptIncluded)
        assertFalse(loaded.rawBodyIncluded)
        assertEquals("ai-safe-v1", db.aiTransparencyDao().findReceipt(receipt.receiptId)?.redactionProfile)
    }

    @Test
    fun draftPersistsSeparatelyAndMockProviderIsDeterministic() = runBlocking {
        val entryId = insertEntry("https://example.com/safe", RecordState.ACTIVE)
        val preview = previewOf(source(entryId, "safe-1"))
        val receipt = repository.saveReceipt(preview)

        val draft = repository.generateDraftWithFallback(preview, receipt)
        val loaded = repository.loadDraft(draft.draftId)!!

        assertEquals(receipt.receiptId, loaded.receiptId)
        assertEquals(listOf("safe-1"), loaded.citedSourceIds)
        assertTrue(loaded.body.contains("deterministic-mock-provider"))
    }

    @Test
    fun diffDoesNotApplyWithoutConfirmationAndAppliesAllowedFieldsWithConfirmation() = runBlocking {
        val entryId = insertEntry("https://example.com/apply", RecordState.ACTIVE)
        val preview = previewOf(source(entryId, "safe-1"))
        val receipt = repository.saveReceipt(preview)
        val draft = repository.saveDraft(receipt, "候補", "本文", listOf("safe-1"))
        val proposal = repository.saveDiffProposal(
            draft,
            listOf(
                AiDiffOperation("safe-1", "userTitle", before = null, after = "新しいタイトル"),
                AiDiffOperation("safe-1", "memo", before = "", after = "新しいメモ"),
                AiDiffOperation("safe-1", "normalizedUrl", before = "old", after = "https://evil.example"),
            ),
        )

        assertFalse(repository.applyDiffProposal(proposal.proposalId, confirm = false))
        assertNull(db.urlEntryDao().findById(entryId)!!.userTitle)

        assertTrue(repository.applyDiffProposal(proposal.proposalId, confirm = true))
        val updated = db.urlEntryDao().findById(entryId)!!
        assertEquals("新しいタイトル", updated.userTitle)
        assertEquals("新しいメモ", updated.memo)
        assertEquals("https://example.com/apply", updated.normalizedUrl)
        assertTrue(repository.loadDiffProposal(proposal.proposalId)!!.applied)
    }

    @Test
    fun clearLocalAiDataRemovesReceiptsDraftsAndDiffs() = runBlocking {
        val entryId = insertEntry("https://example.com/delete", RecordState.ACTIVE)
        val receipt = repository.saveReceipt(previewOf(source(entryId, "safe-1")))
        val draft = repository.saveDraft(receipt, "候補", "本文", listOf("safe-1"))
        val proposal = repository.saveDiffProposal(draft, listOf(AiDiffOperation("safe-1", "memo", "", "x")))

        repository.clearLocalAiData()

        assertNull(repository.loadReceipt(receipt.receiptId))
        assertNull(repository.loadDraft(draft.draftId))
        assertNull(repository.loadDiffProposal(proposal.proposalId))
    }

    @Test
    fun featureFlagDefaultsOffForNormalUi() {
        assertFalse(AiTransparencyFeature.isEnabled)
    }

    @Test
    fun migration21To22CreatesAiTransparencyTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "ai-transparency-migration.db"
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(21) {
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
                                    localProvenanceCount INTEGER NOT NULL DEFAULT 1,
                                    sharedReferenceCount INTEGER NOT NULL DEFAULT 0,
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
                .build()
        )
        val writable = helper.writableDatabase

        AppDatabase.MIGRATION_21_22.migrate(writable)

        writable.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'ai_receipts'").use {
            assertTrue(it.moveToFirst())
            assertEquals("ai_receipts", it.getString(0))
        }
        writable.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'ai_drafts'").use {
            assertTrue(it.moveToFirst())
            assertEquals("ai_drafts", it.getString(0))
        }
        helper.close()
        context.deleteDatabase(dbName)
    }

    private fun previewOf(vararg sources: AiTransparencySource) = AiTransparencyPolicy.buildPreview(
        actionKind = AiActionKind.EXPORT,
        destination = "internal-preview",
        sources = sources.toList(),
    )

    private suspend fun source(
        entryId: Long,
        publicSafeId: String,
        containsSharedTag: Boolean = false,
    ): AiTransparencySource {
        return AiTransparencyPolicy.sourceForEntry(
            entry = db.urlEntryDao().findById(entryId)!!,
            publicSafeId = publicSafeId,
            tagNames = listOf("tag"),
            containsSharedTag = containsSharedTag,
        )
    }

    private suspend fun insertEntry(
        normalizedUrl: String,
        recordState: RecordState,
        sharedReferenceCount: Int = 0,
    ): Long {
        return db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = normalizedUrl,
                normalizedUrl = normalizedUrl,
                displayUrl = normalizedUrl.removePrefix("https://"),
                openUrl = normalizedUrl,
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = ServiceType.WEB,
                contentContext = ContentContext.STANDARD,
                metadataState = MetadataState.READY,
                recordState = recordState,
                sharedReferenceCount = sharedReferenceCount,
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
    }
}
