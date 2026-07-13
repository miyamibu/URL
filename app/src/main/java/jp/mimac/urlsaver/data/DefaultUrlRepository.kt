package jp.mimac.urlsaver.data

import android.content.Intent
import android.util.Log
import androidx.room.withTransaction
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataBodyKind
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.LimitResult
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareExtractionResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DefaultUrlRepository(
    private val database: AppDatabase,
    private val dao: UrlEntryDao,
    private val tagDao: TagDao,
    private val clock: AppClock,
    private val scheduler: MetadataScheduler,
    private val usageSummaryDataSource: UsageSummaryDataSource,
) : UrlRepository {
    override fun observeActiveEntries(): Flow<List<UrlEntryEntity>> = flow {
        emitAll(dao.observeActiveEntries())
    }

    override fun observeArchiveEntries(): Flow<List<UrlEntryEntity>> = flow {
        emitAll(dao.observeArchiveEntries())
    }

    override fun observeEntry(entryId: Long): Flow<UrlEntryEntity?> = dao.observeEntry(entryId)
    override fun observeLocalTagEntryRefs(): Flow<List<LocalTagEntryRef>> =
        tagDao.observeLocalTagEntryRefs()

    override suspend fun saveFromIntent(intent: Intent): SaveResult {
        return when (val extracted = UrlRules.extractFromIntent(intent)) {
            is ShareExtractionResult.Found -> saveFromUrl(
                extracted.url,
                initialMemo = UrlRules.extractMemoWithoutUrlsFromIntent(intent),
            )
            ShareExtractionResult.InputTooLarge -> SaveResult(ShareSaveResult.INPUT_TOO_LARGE)
            ShareExtractionResult.InvalidUrl -> {
                val text = UrlRules.extractTextFallbackFromIntent(intent)
                    ?: return SaveResult(ShareSaveResult.INVALID_URL)
                saveFromTextCard(text)
            }
            ShareExtractionResult.NoUrlFound -> {
                val text = UrlRules.extractTextFallbackFromIntent(intent)
                    ?: return SaveResult(ShareSaveResult.NO_URL_FOUND)
                saveFromTextCard(text)
            }
        }
    }

    override suspend fun saveFromManualInput(input: String): SaveResult {
        return saveFromManualInput(input, initialMemo = UrlRules.extractMemoWithoutUrls(input))
    }

    override suspend fun saveFromManualInput(
        input: String,
        initialMemo: String?,
    ): SaveResult {
        return when (val extracted = UrlRules.extractForManualInput(input)) {
            is ShareExtractionResult.Found -> saveFromUrl(extracted.url, initialMemo)
            ShareExtractionResult.InputTooLarge -> SaveResult(ShareSaveResult.INPUT_TOO_LARGE)
            ShareExtractionResult.InvalidUrl -> SaveResult(ShareSaveResult.INVALID_URL)
            ShareExtractionResult.NoUrlFound -> saveFromTextCard(input)
        }
    }

    private suspend fun saveFromUrl(
        originalUrl: String,
        initialMemo: String? = null,
    ): SaveResult {
        val parsed = UrlRules.parseUrl(originalUrl) ?: return SaveResult(ShareSaveResult.INVALID_URL)
        val memoForNewEntry = normalizeInitialMemo(initialMemo)
        val now = clock.nowEpochMillis()

        return try {
            database.withTransaction {
                val existing = findExistingEntry(parsed.normalizedUrl)
                if (existing != null) {
                    if (existing.localProvenanceCount <= 0) {
                        val limitResult = usageSummaryDataSource.limitChecker.checkCanSavePersonalUrl(
                            usageSummaryDataSource.getUsageSummary(),
                        )
                        if (limitResult is LimitResult.Blocked) {
                            return@withTransaction SaveResult(ShareSaveResult.PERSONAL_URL_LIMIT_REACHED)
                        }
                        val promoted = existing.copy(
                            localProvenanceCount = 1,
                            recordState = RecordState.ACTIVE,
                            memo = mergeInitialMemo(existing.memo, memoForNewEntry),
                            archivedAt = null,
                            pendingDeletionUntil = null,
                            updatedAt = now,
                        )
                        dao.update(promoted)
                        if (shouldEnqueueMetadataAfterRestore(promoted)) {
                            if (promoted.metadataState == MetadataState.PENDING) {
                                dao.markMetadataPending(promoted.id, now)
                            }
                            enqueueMetadataOrMarkUnavailable(promoted.id, now)
                        }
                        return@withTransaction SaveResult(
                            result = ShareSaveResult.CREATED,
                            entryId = promoted.id,
                            normalizedUrl = promoted.normalizedUrl,
                        )
                    }
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
                            var restored = dao.restoreFromPending(existing, now)
                            if (restored.memo.isBlank() && memoForNewEntry.isNotBlank()) {
                                restored = restored.copy(
                                    memo = memoForNewEntry,
                                    updatedAt = now,
                                )
                            }
                            dao.update(restored)
                            if (shouldEnqueueMetadataAfterRestore(restored)) {
                                if (restored.metadataState == MetadataState.PENDING) {
                                    dao.markMetadataPending(restored.id, now)
                                }
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
                    val limitResult = usageSummaryDataSource.limitChecker.checkCanSavePersonalUrl(
                        usageSummaryDataSource.getUsageSummary(),
                    )
                    if (limitResult is LimitResult.Blocked) {
                        return@withTransaction SaveResult(ShareSaveResult.PERSONAL_URL_LIMIT_REACHED)
                    }
                    val entryId = dao.insert(
                        UrlEntryEntity(
                            originalUrl = parsed.originalUrl,
                            normalizedUrl = parsed.normalizedUrl,
                            displayUrl = parsed.displayUrl,
                            openUrl = parsed.openUrl,
                            normalizedHost = parsed.normalizedHost,
                            rawSourceHost = parsed.rawSourceHost,
                            collectionId = DEFAULT_COLLECTION_ID,
                            serviceType = parsed.serviceType,
                            contentContext = parsed.contentContext,
                            memo = memoForNewEntry,
                            metadataState = MetadataState.PENDING,
                            metadataRequestedAt = now,
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

    private suspend fun saveFromTextCard(input: String): SaveResult {
        val parsed = UrlRules.parseTextCard(input) ?: return SaveResult(ShareSaveResult.NO_URL_FOUND)
        val body = parsed.originalUrl
        val title = UrlRules.textCardTitle(body)
        val now = clock.nowEpochMillis()

        return try {
            database.withTransaction {
                val existing = findExistingEntry(parsed.normalizedUrl)
                if (existing != null) {
                    if (existing.localProvenanceCount <= 0) {
                        val limitResult = usageSummaryDataSource.limitChecker.checkCanSavePersonalUrl(
                            usageSummaryDataSource.getUsageSummary(),
                        )
                        if (limitResult is LimitResult.Blocked) {
                            return@withTransaction SaveResult(ShareSaveResult.PERSONAL_URL_LIMIT_REACHED)
                        }
                        val promoted = existing.copy(
                            localProvenanceCount = 1,
                            recordState = RecordState.ACTIVE,
                            archivedAt = null,
                            pendingDeletionUntil = null,
                            updatedAt = now,
                        )
                        dao.update(promoted)
                        return@withTransaction SaveResult(
                            result = ShareSaveResult.CREATED,
                            entryId = promoted.id,
                            normalizedUrl = promoted.normalizedUrl,
                        )
                    }
                    when (existing.recordState) {
                        RecordState.ACTIVE -> {
                            SaveResult(
                                result = ShareSaveResult.DUPLICATE_ACTIVE,
                                entryId = existing.id,
                                normalizedUrl = existing.normalizedUrl,
                            )
                        }
                        RecordState.ARCHIVED -> SaveResult(
                            result = ShareSaveResult.DUPLICATE_ARCHIVED,
                            entryId = existing.id,
                            normalizedUrl = existing.normalizedUrl,
                        )
                        RecordState.PENDING_DELETE -> {
                            val restored = dao.restoreFromPending(existing, now)
                            SaveResult(
                                result = ShareSaveResult.RESTORED_FROM_PENDING_DELETE,
                                entryId = restored.id,
                                normalizedUrl = restored.normalizedUrl,
                            )
                        }
                    }
                } else {
                    val limitResult = usageSummaryDataSource.limitChecker.checkCanSavePersonalUrl(
                        usageSummaryDataSource.getUsageSummary(),
                    )
                    if (limitResult is LimitResult.Blocked) {
                        return@withTransaction SaveResult(ShareSaveResult.PERSONAL_URL_LIMIT_REACHED)
                    }
                    val entryId = dao.insert(
                        UrlEntryEntity(
                            originalUrl = body,
                            normalizedUrl = parsed.normalizedUrl,
                            displayUrl = parsed.displayUrl,
                            openUrl = parsed.openUrl,
                            normalizedHost = parsed.normalizedHost,
                            rawSourceHost = parsed.rawSourceHost,
                            collectionId = DEFAULT_COLLECTION_ID,
                            serviceType = ServiceType.WEB,
                            contentContext = ContentContext.POST,
                            fetchedTitle = title,
                            fetchedBody = body,
                            fetchedBodyKind = MetadataBodyKind.WEB_EXCERPT,
                            bodySummary = title,
                            metadataState = MetadataState.READY,
                            metadataFetchedAt = now,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    SaveResult(
                        result = ShareSaveResult.CREATED,
                        entryId = entryId,
                        normalizedUrl = parsed.normalizedUrl,
                    )
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "saveFromTextCard failed", error)
            SaveResult(ShareSaveResult.SAVE_FAILED)
        }
    }

    private fun normalizeInitialMemo(initialMemo: String?): String {
        val memo = UrlRules.normalizeMemo(initialMemo)
        if (memo.isBlank()) return ""
        return if (UrlRules.isMemoLengthValid(memo)) memo else memo.take(2000)
    }

    private fun mergeInitialMemo(existingMemo: String, initialMemo: String): String {
        return existingMemo.ifBlank { initialMemo }
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
            MetadataState.PENDING -> entry.metadataRequestedAt == null && entry.metadataFetchedAt == null
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
        if (target.recordState != RecordState.ACTIVE && target.recordState != RecordState.ARCHIVED) return null
        val pendingUntil = now + gracePeriodMillis
        val archivedAt = when (target.recordState) {
            RecordState.ARCHIVED -> target.archivedAt ?: now
            RecordState.ACTIVE -> null
            RecordState.PENDING_DELETE -> null
        }
        dao.update(
            target.copy(
                recordState = RecordState.PENDING_DELETE,
                pendingDeletionUntil = pendingUntil,
                archivedAt = archivedAt,
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
            RecordState.PENDING_DELETE -> {
                val restoreAsArchived = target.archivedAt != null
                dao.update(
                    target.copy(
                        recordState = if (restoreAsArchived) RecordState.ARCHIVED else RecordState.ACTIVE,
                        pendingDeletionUntil = null,
                        archivedAt = if (restoreAsArchived) target.archivedAt else null,
                        updatedAt = now,
                    ),
                )
                true
            }

            RecordState.ARCHIVED -> {
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
            fetchedAuthorName = metadata.fetchedAuthorName,
            fetchedBody = metadata.fetchedBody,
            fetchedBodyKind = metadata.fetchedBodyKind,
            bodySummary = metadata.bodySummary,
            description = metadata.description,
            thumbnailUrl = metadata.thumbnailUrl,
            badgeImageUrl = metadata.badgeImageUrl,
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
        if (!canRetryMetadata(entry)) {
            return false
        }
        val now = clock.nowEpochMillis()
        dao.markMetadataPending(entryId, now)
        return enqueueMetadataOrMarkUnavailable(entryId, now)
    }

    override suspend fun refreshMetadata(entryId: Long): Boolean {
        dao.findById(entryId) ?: return false
        val now = clock.nowEpochMillis()
        dao.markMetadataPending(entryId, now)
        return enqueueMetadataOrMarkUnavailable(entryId, now)
    }

    override suspend fun backfillYouTubeAuthorNames(limit: Int): Int {
        val entryIds = dao.findYouTubeEntriesMissingAuthorName(limit)
        if (entryIds.isEmpty()) return 0

        val now = clock.nowEpochMillis()
        var enqueuedCount = 0
        for (entryId in entryIds) {
            dao.markMetadataPending(entryId, now)
            if (enqueueMetadataOrMarkUnavailable(entryId, now)) {
                enqueuedCount += 1
            }
        }
        return enqueuedCount
    }

    override suspend fun loadEntry(entryId: Long): UrlEntryEntity? = dao.findById(entryId)

    private suspend fun findExistingEntry(normalizedUrl: String): UrlEntryEntity? {
        dao.findByNormalizedUrl(normalizedUrl)?.let { return it }
        val legacyHttpTwin = UrlRules.toLegacyHttpTwin(normalizedUrl) ?: return null
        return dao.findByNormalizedUrl(legacyHttpTwin)
    }

    private fun canRetryMetadata(entry: UrlEntryEntity): Boolean {
        if (entry.metadataState in setOf(MetadataState.FAILED, MetadataState.UNAVAILABLE)) {
            return true
        }
        return entry.metadataState == MetadataState.READY &&
            (
                (entry.bodySummary.isNullOrBlank() && entry.fetchedBody.isNullOrBlank()) ||
                    (entry.serviceType == ServiceType.X && entry.badgeImageUrl.isNullOrBlank())
                )
    }

    private companion object {
        const val TAG = "DefaultUrlRepository"
    }
}
