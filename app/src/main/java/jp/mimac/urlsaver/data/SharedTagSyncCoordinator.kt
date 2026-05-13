package jp.mimac.urlsaver.data

import androidx.room.withTransaction
import jp.mimac.urlsaver.domain.ApplySharedTagOpsResponse
import jp.mimac.urlsaver.domain.PullSharedTagSnapshotResponse
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.SharedTagMemberStatus
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.SharedTagSyncOperation
import jp.mimac.urlsaver.domain.SharedTagSyncStatus
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.util.AppClock
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class SharedTagSyncCoordinator(
    private val database: AppDatabase,
    private val tagDao: TagDao,
    private val syncDao: SharedTagSyncDao,
    private val urlEntryDao: UrlEntryDao,
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val remoteDataSource: SharedTagSyncRemoteDataSource,
    private val clock: AppClock,
    private val metadataScheduler: MetadataScheduler,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    suspend fun syncCurrentSession(): Boolean {
        val session = authSessionProvider.session.value ?: return true
        return syncForAuthUser(session.authUserId)
    }

    suspend fun syncForAuthUser(authUserId: String): Boolean {
        val normalizedAuthUserId = authUserId.trim()
        if (normalizedAuthUserId.isEmpty()) return true
        val session = authSessionProvider.session.value ?: return true
        if (session.authUserId != normalizedAuthUserId) {
            return true
        }
        return syncSession(session)
    }

    private suspend fun syncSession(session: SharedTagAuthSession): Boolean {
        val now = clock.nowEpochMillis()
        val syncState = ensureSyncState(session.authUserId)
        val pendingOps = syncDao.getPendingOutbox(session.authUserId)

        return runCatching {
            if (pendingOps.isNotEmpty()) {
                val operations = pendingOps.map { json.decodeFromString<SharedTagSyncOperation>(it.payloadJson) }
                val applyResponse = remoteDataSource.applyOps(session, operations)
                applyOutboxResults(pendingOps, applyResponse, now)
            }

            val snapshot = remoteDataSource.pullSnapshot(session)
            applySnapshot(session.authUserId, snapshot, now)
            syncDao.upsertSyncState(
                syncState.copy(
                    lastPulledAt = now,
                    lastSyncSucceededAt = now,
                    lastErrorMessage = null,
                ),
            )
            syncDao.deleteCompletedOutbox(session.authUserId)
            true
        }.getOrElse { error ->
            pendingOps.forEach { entity ->
                syncDao.updatePendingOutboxAttempt(
                    opId = entity.opId,
                    attemptCount = entity.attemptCount + 1,
                    lastErrorMessage = error.message,
                    updatedAt = now,
                )
            }
            syncDao.upsertSyncState(
                syncState.copy(
                    lastSyncFailedAt = now,
                    lastErrorMessage = error.message,
                ),
            )
            false
        }
    }

    suspend fun ensureClientId(authUserId: String): String {
        return ensureSyncState(authUserId).clientId
    }

    private suspend fun ensureSyncState(authUserId: String): SharedTagSyncStateEntity {
        return syncDao.findSyncState(authUserId)
            ?: SharedTagSyncStateEntity(
                authUserId = authUserId,
                clientId = UUID.randomUUID().toString(),
            ).also { syncDao.upsertSyncState(it) }
    }

    private suspend fun applyOutboxResults(
        pendingOps: List<SharedTagSyncOutboxEntity>,
        applyResponse: ApplySharedTagOpsResponse,
        now: Long,
    ) {
        val resultsByOpId = applyResponse.results.associateBy { it.opId }
        pendingOps.forEach { entity ->
            val result = resultsByOpId[entity.opId]
                ?: error("apply_shared_tag_ops result missing for opId=${entity.opId}")
            when (val status = result.status.lowercase()) {
                STATUS_APPLIED -> {
                    syncDao.updateOutboxState(
                        opId = entity.opId,
                        state = SharedTagSyncOutboxState.COMPLETED,
                        attemptCount = entity.attemptCount,
                        lastErrorMessage = null,
                        updatedAt = now,
                    )
                }

                in TERMINAL_FAILURE_STATUSES -> {
                    syncDao.updateOutboxState(
                        opId = entity.opId,
                        state = SharedTagSyncOutboxState.FAILED,
                        attemptCount = entity.attemptCount + 1,
                        lastErrorMessage = "apply_shared_tag_ops returned terminal status=$status",
                        updatedAt = now,
                    )
                }

                else -> error("apply_shared_tag_ops returned unknown status=$status for opId=${entity.opId}")
            }
        }
    }

    private suspend fun applySnapshot(
        authUserId: String,
        snapshot: PullSharedTagSnapshotResponse,
        now: Long,
    ) {
        val createdEntryIds = mutableListOf<Long>()
        database.withTransaction {
            val remoteToLocalTagId = linkedMapOf<String, Long>()
            val remoteTagIds = snapshot.tags.map { it.id }

            snapshot.tags.forEach { remoteTag ->
                val existing = tagDao.findSyncedTagByRemoteId(authUserId, remoteTag.id)
                val entity = TagEntity(
                    id = existing?.id ?: 0L,
                    name = remoteTag.name,
                    createdAt = Instant.parse(remoteTag.createdAt).toEpochMilli(),
                    scope = SharedTagScope.SYNCED,
                    authUserId = authUserId,
                    remoteTagId = remoteTag.id,
                    syncStatus = SharedTagSyncStatus.SYNCED,
                    remoteVersion = remoteTag.version,
                    deletedAt = remoteTag.deletedAt?.let { Instant.parse(it).toEpochMilli() },
                    lastSyncedAt = now,
                    syncErrorMessage = null,
                )
                val localId = if (existing == null) {
                    tagDao.insertTag(entity)
                } else {
                    tagDao.upsertTags(listOf(entity))
                    existing.id
                }
                remoteToLocalTagId[remoteTag.id] = localId
            }

            if (remoteTagIds.isEmpty()) {
                tagDao.getSyncedTagsForUser(authUserId).forEach { tag ->
                    tagDao.updateSyncedTagDeletion(
                        authUserId = authUserId,
                        remoteTagId = requireNotNull(tag.remoteTagId),
                        deletedAt = now,
                        syncStatus = SharedTagSyncStatus.SYNCED,
                    )
                }
            } else {
                tagDao.markMissingSyncedTagsDeleted(
                    authUserId = authUserId,
                    remoteTagIds = remoteTagIds,
                    deletedAt = now,
                    syncStatus = SharedTagSyncStatus.SYNCED,
                    syncErrorMessage = null,
                )
            }

            syncDao.deleteMembersForUser(authUserId)
            tagDao.deleteSyncedCrossRefsForUser(authUserId)
            urlEntryDao.resetSharedReferenceCounts()

            if (snapshot.members.isNotEmpty()) {
                val members = snapshot.members.mapNotNull { member ->
                    val localTagId = remoteToLocalTagId[member.tagId] ?: return@mapNotNull null
                    SharedTagMemberEntity(
                        tagId = localTagId,
                        authUserId = authUserId,
                        userId = member.userId,
                        role = SharedTagMemberRole.valueOf(member.role.uppercase()),
                        status = SharedTagMemberStatus.valueOf(member.status.uppercase()),
                        createdAt = Instant.parse(member.createdAt).toEpochMilli(),
                        updatedAt = Instant.parse(member.updatedAt).toEpochMilli(),
                    )
                }
                syncDao.upsertMembers(members)
            }

            if (snapshot.urls.isNotEmpty()) {
                val refs = snapshot.urls.mapNotNull { remoteUrl ->
                    val localTagId = remoteToLocalTagId[remoteUrl.tagId] ?: return@mapNotNull null
                    val entryId = ensureSharedCacheEntry(remoteUrl.rawUrl, remoteUrl.normalizedUrl, createdEntryIds)
                        ?: return@mapNotNull null
                    TagUrlCrossRef(
                        tagId = localTagId,
                        entryId = entryId,
                        createdAt = Instant.parse(remoteUrl.createdAt).toEpochMilli(),
                        scope = SharedTagScope.SYNCED,
                        authUserId = authUserId,
                        remoteUrlId = remoteUrl.id,
                        rawUrl = remoteUrl.rawUrl,
                        normalizedUrl = remoteUrl.normalizedUrl,
                        normalizationVersion = remoteUrl.normalizationVersion,
                        syncStatus = SharedTagSyncStatus.SYNCED,
                        deletedAt = remoteUrl.deletedAt?.let { Instant.parse(it).toEpochMilli() },
                        lastSyncedAt = now,
                        syncErrorMessage = null,
                    )
                }
                tagDao.upsertCrossRefs(refs)
                refs.groupingBy { it.entryId }
                    .eachCount()
                    .forEach { (entryId, count) ->
                        urlEntryDao.updateSharedReferenceCount(entryId, count)
                    }
            }
            urlEntryDao.deleteUnreferencedSharedOnlyEntries()
        }

        createdEntryIds.forEach { entryId ->
            runCatching { metadataScheduler.enqueueMetadata(entryId) }
        }
    }

    private suspend fun ensureSharedCacheEntry(
        rawUrl: String,
        normalizedUrl: String,
        createdEntryIds: MutableList<Long>,
    ): Long? {
        findExistingEntry(normalizedUrl)?.let { return it.id }
        val parsed = UrlRules.parseUrl(rawUrl) ?: UrlRules.parseUrl(normalizedUrl) ?: return null
        val now = clock.nowEpochMillis()
        val entryId = urlEntryDao.insert(
            UrlEntryEntity(
                originalUrl = rawUrl,
                normalizedUrl = parsed.normalizedUrl,
                displayUrl = parsed.displayUrl,
                openUrl = parsed.openUrl,
                normalizedHost = parsed.normalizedHost,
                rawSourceHost = parsed.rawSourceHost,
                serviceType = parsed.serviceType,
                contentContext = parsed.contentContext,
                localProvenanceCount = 0,
                sharedReferenceCount = 0,
                metadataState = jp.mimac.urlsaver.domain.MetadataState.PENDING,
                recordState = RecordState.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        createdEntryIds += entryId
        return entryId
    }

    private suspend fun findExistingEntry(normalizedUrl: String): UrlEntryEntity? {
        urlEntryDao.findByNormalizedUrl(normalizedUrl)?.let { return it }
        val legacyHttpTwin = UrlRules.toLegacyHttpTwin(normalizedUrl) ?: return null
        return urlEntryDao.findByNormalizedUrl(legacyHttpTwin)
    }

    private companion object {
        const val STATUS_APPLIED = "applied"
        val TERMINAL_FAILURE_STATUSES = setOf(
            "failed",
            "error",
            "rejected",
            "invalid",
            "forbidden",
            "unauthorized",
            "not_found",
            "conflict",
            "duplicate",
            "already_applied",
            "ignored",
            "no_op",
        )
    }
}
