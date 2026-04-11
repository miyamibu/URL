package jp.mimac.urlsaver.data

import android.content.Intent
import android.util.Log
import androidx.room.withTransaction
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ShareExtractionResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.flow.Flow

class DefaultUrlRepository(
    private val database: AppDatabase,
    private val dao: UrlEntryDao,
    private val clock: AppClock,
    private val scheduler: MetadataScheduler,
) : UrlRepository {

    override fun observeActiveEntries(): Flow<List<UrlEntryEntity>> = dao.observeActiveEntries()

    override fun observeArchiveEntries(): Flow<List<UrlEntryEntity>> = dao.observeArchiveEntries()

    override fun observeEntry(entryId: Long): Flow<UrlEntryEntity?> = dao.observeEntry(entryId)

    override suspend fun saveFromIntent(intent: Intent): SaveResult {
        return when (val extracted = UrlRules.extractFromIntent(intent)) {
            is ShareExtractionResult.Found -> saveFromUrl(extracted.url)
            ShareExtractionResult.InvalidUrl -> SaveResult(ShareSaveResult.INVALID_URL)
            ShareExtractionResult.NoUrlFound -> SaveResult(ShareSaveResult.NO_URL_FOUND)
        }
    }

    override suspend fun saveFromManualInput(input: String): SaveResult {
        return when (val extracted = UrlRules.extractForManualInput(input)) {
            is ShareExtractionResult.Found -> saveFromUrl(extracted.url)
            ShareExtractionResult.InvalidUrl -> SaveResult(ShareSaveResult.INVALID_URL)
            ShareExtractionResult.NoUrlFound -> SaveResult(ShareSaveResult.NO_URL_FOUND)
        }
    }

    private suspend fun saveFromUrl(originalUrl: String): SaveResult {
        val parsed = UrlRules.parseUrl(originalUrl) ?: return SaveResult(ShareSaveResult.INVALID_URL)
        val now = clock.nowEpochMillis()

        return try {
            database.withTransaction {
                val existing = dao.findByNormalizedUrl(parsed.normalizedUrl)
                if (existing != null) {
                    when (existing.recordState) {
                        RecordState.ACTIVE -> {
                            SaveResult(
                                result = ShareSaveResult.DUPLICATE_ACTIVE,
                                entryId = existing.id,
                                normalizedUrl = existing.normalizedUrl,
                            )
                        }

                        RecordState.ARCHIVED -> {
                            SaveResult(
                                result = ShareSaveResult.DUPLICATE_ARCHIVED,
                                entryId = existing.id,
                                normalizedUrl = existing.normalizedUrl,
                            )
                        }

                        RecordState.PENDING_DELETE -> {
                            val restored = dao.restoreFromPending(existing, now)
                            if (shouldEnqueueMetadataAfterRestore(restored)) {
                                enqueueMetadataOrMarkUnavailable(restored.id, now)
                            }
                            SaveResult(
                                result = ShareSaveResult.RESTORED_FROM_PENDING_DELETE,
                                entryId = restored.id,
                                normalizedUrl = restored.normalizedUrl,
                            )
                        }
                    }
                } else {
                    val entryId = dao.insert(
                        UrlEntryEntity(
                            originalUrl = parsed.originalUrl,
                            normalizedUrl = parsed.normalizedUrl,
                            displayUrl = parsed.displayUrl,
                            openUrl = parsed.openUrl,
                            normalizedHost = parsed.normalizedHost,
                            rawSourceHost = parsed.rawSourceHost,
                            serviceType = parsed.serviceType,
                            contentContext = parsed.contentContext,
                            metadataState = MetadataState.PENDING,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    enqueueMetadataOrMarkUnavailable(entryId, now)
                    SaveResult(
                        result = ShareSaveResult.CREATED,
                        entryId = entryId,
                        normalizedUrl = parsed.normalizedUrl,
                    )
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "saveFromUrl failed", error)
            SaveResult(ShareSaveResult.SAVE_FAILED)
        }
    }

    private suspend fun enqueueMetadataOrMarkUnavailable(entryId: Long, now: Long): Boolean {
        return runCatching {
            scheduler.enqueueMetadata(entryId)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Metadata scheduling failed for entryId=$entryId", error)
            markMetadataUnavailable(entryId, now)
            false
        }
    }

    private suspend fun markMetadataUnavailable(entryId: Long, now: Long) {
        val target = dao.findById(entryId) ?: return
        dao.update(
            target.copy(
                metadataState = MetadataState.UNAVAILABLE,
                metadataError = MetadataError.SCHEDULER_UNAVAILABLE,
                metadataFetchedAt = now,
            ),
        )
    }

    private fun shouldEnqueueMetadataAfterRestore(entry: UrlEntryEntity): Boolean {
        return when (entry.metadataState) {
            MetadataState.READY -> false
            MetadataState.UNAVAILABLE -> false
            MetadataState.FAILED -> true
            MetadataState.PENDING -> entry.metadataFetchedAt == null
        }
    }

    override suspend fun archive(entryId: Long): Boolean {
        val now = clock.nowEpochMillis()
        val target = dao.findById(entryId) ?: return false
        if (target.recordState != RecordState.ACTIVE) return false
        dao.update(
            target.copy(
                recordState = RecordState.ARCHIVED,
                archivedAt = now,
                pendingDeletionUntil = null,
                updatedAt = now,
            ),
        )
        return true
    }

    override suspend fun unarchive(entryId: Long): Boolean {
        val now = clock.nowEpochMillis()
        val target = dao.findById(entryId) ?: return false
        if (target.recordState != RecordState.ARCHIVED) return false
        dao.update(
            target.copy(
                recordState = RecordState.ACTIVE,
                archivedAt = null,
                pendingDeletionUntil = null,
                updatedAt = now,
            ),
        )
        return true
    }

    override suspend fun markPendingDelete(entryId: Long, gracePeriodMillis: Long): Long? {
        val now = clock.nowEpochMillis()
        val target = dao.findById(entryId) ?: return null
        if (target.recordState != RecordState.ACTIVE) return null
        val pendingUntil = now + gracePeriodMillis
        dao.update(
            target.copy(
                recordState = RecordState.PENDING_DELETE,
                pendingDeletionUntil = pendingUntil,
                archivedAt = null,
                updatedAt = now,
            ),
        )
        return pendingUntil
    }

    override suspend fun finalizePendingDelete(entryId: Long) {
        val now = clock.nowEpochMillis()
        val target = dao.findById(entryId) ?: return
        if (target.recordState != RecordState.PENDING_DELETE) return
        val due = target.pendingDeletionUntil ?: return
        if (due <= now) {
            dao.deleteById(entryId)
        }
    }

    override suspend fun cleanupExpiredPendingDeletes() {
        dao.cleanupExpiredPending(clock.nowEpochMillis())
    }

    override suspend fun restore(entryId: Long): Boolean {
        val now = clock.nowEpochMillis()
        val target = dao.findById(entryId) ?: return false
        return when (target.recordState) {
            RecordState.PENDING_DELETE,
            RecordState.ARCHIVED,
            -> {
                dao.update(
                    target.copy(
                        recordState = RecordState.ACTIVE,
                        pendingDeletionUntil = null,
                        archivedAt = null,
                        updatedAt = now,
                    ),
                )
                true
            }

            RecordState.ACTIVE -> false
        }
    }

    override suspend fun saveUserTitle(entryId: Long, rawTitle: String): SaveTitleResult {
        val entry = dao.findById(entryId) ?: return SaveTitleResult(success = false)
        if (!UrlRules.isTitleLengthValid(rawTitle)) {
            return SaveTitleResult(success = false, tooLong = true)
        }
        val newTitle = UrlRules.normalizeUserTitle(rawTitle)
        val oldTitle = entry.userTitle
        dao.update(
            entry.copy(
                userTitle = newTitle,
                updatedAt = clock.nowEpochMillis(),
            ),
        )
        return SaveTitleResult(
            success = true,
            oldTitle = oldTitle,
            newTitle = newTitle,
        )
    }

    override suspend fun saveMemo(entryId: Long, rawMemo: String): SaveMemoResult {
        val entry = dao.findById(entryId) ?: return SaveMemoResult(success = false)
        if (!UrlRules.isMemoLengthValid(rawMemo)) {
            return SaveMemoResult(success = false, tooLong = true)
        }
        val memo = UrlRules.normalizeMemo(rawMemo)
        dao.update(
            entry.copy(
                memo = memo,
                updatedAt = clock.nowEpochMillis(),
            ),
        )
        return SaveMemoResult(success = true)
    }

    override suspend fun restoreUserTitle(entryId: Long, oldTitle: String?): Boolean {
        val entry = dao.findById(entryId) ?: return false
        dao.update(
            entry.copy(
                userTitle = oldTitle,
                updatedAt = clock.nowEpochMillis(),
            ),
        )
        return true
    }

    override suspend fun applyCanonicalId(entryId: Long, canonicalId: String?) {
        dao.updateCanonicalId(entryId, canonicalId)
    }

    override suspend fun applyMetadataUpdate(entryId: Long, metadata: MetadataUpdate) {
        dao.updateMetadata(
            entryId = entryId,
            fetchedTitle = metadata.fetchedTitle,
            thumbnailUrl = metadata.thumbnailUrl,
            metadataState = metadata.metadataState,
            metadataFetchedAt = metadata.metadataFetchedAt,
            metadataError = metadata.metadataError,
            canonicalId = metadata.canonicalId,
            normalizedHost = metadata.normalizedHost,
            rawSourceHost = metadata.rawSourceHost,
        )
    }

    override suspend fun retryMetadata(entryId: Long): Boolean {
        val entry = dao.findById(entryId) ?: return false
        if (entry.metadataState !in setOf(MetadataState.FAILED, MetadataState.UNAVAILABLE)) {
            return false
        }
        dao.markMetadataPending(entryId)
        return enqueueMetadataOrMarkUnavailable(entryId, clock.nowEpochMillis())
    }

    override suspend fun loadEntry(entryId: Long): UrlEntryEntity? = dao.findById(entryId)

    private companion object {
        const val TAG = "DefaultUrlRepository"
    }
}
