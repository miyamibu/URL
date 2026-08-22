package jp.mimac.urlsaver.data
import androidx.room.withTransaction
import jp.mimac.urlsaver.domain.MAX_SHARED_TAG_NAME_LENGTH
import jp.mimac.urlsaver.domain.SHARED_TAG_NORMALIZATION_VERSION
import jp.mimac.urlsaver.domain.SHARED_TAG_INVITE_ROLE
import jp.mimac.urlsaver.domain.SharedTagAuthResult
import jp.mimac.urlsaver.domain.SharedTagAccountDeletionResult
import jp.mimac.urlsaver.domain.SharedTagCloudState
import jp.mimac.urlsaver.domain.SharedTagGroupInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagGroupMemberRecord
import jp.mimac.urlsaver.domain.SharedTagGroupMutationResult
import jp.mimac.urlsaver.domain.SharedTagGroupRecord
import jp.mimac.urlsaver.domain.SharedTagGroupTagRecord
import jp.mimac.urlsaver.domain.SharedTagInviteAcceptanceResult
import jp.mimac.urlsaver.domain.SharedTagInviteCreationResult
import jp.mimac.urlsaver.domain.SharedTagInvitePreviewResult
import jp.mimac.urlsaver.domain.SharedTagOwnershipTransferResult
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.CreateSharedTagGroupResult
import jp.mimac.urlsaver.domain.FeatureEntitlements
import jp.mimac.urlsaver.domain.LimitResult
import jp.mimac.urlsaver.domain.MigrateSharedTagResult
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.SharedTagMemberRecord
import jp.mimac.urlsaver.domain.SharedTagRecord
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.SharedTagMemberStatus
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.SharedTagSyncOperation
import jp.mimac.urlsaver.domain.SharedTagSyncOperationType
import jp.mimac.urlsaver.domain.SharedTagSyncStatus
import jp.mimac.urlsaver.domain.TagImportResult
import jp.mimac.urlsaver.domain.TagSharePayload
import jp.mimac.urlsaver.domain.TagShareUrl
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.domain.UsageSummary
import jp.mimac.urlsaver.domain.UrlRules
import jp.mimac.urlsaver.domain.normalizeSharedTagName
import jp.mimac.urlsaver.domain.validateSharedTagName
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTagRepository(
    private val database: AppDatabase,
    private val tagDao: TagDao,
    private val syncDao: SharedTagSyncDao,
    private val urlEntryDao: UrlEntryDao,
    private val clock: AppClock,
    private val metadataScheduler: MetadataScheduler,
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val authRemoteDataSource: SharedTagAuthRemoteDataSource,
    private val syncScheduler: SharedTagSyncScheduler?,
    private val syncCoordinator: SharedTagSyncCoordinator,
    private val remoteDataSource: SharedTagSyncRemoteDataSource,
    private val remoteConfig: SharedTagSyncRemoteConfig,
    private val usageSummaryDataSource: UsageSummaryDataSource,
    private val aiLocalDataClearer: AiLocalDataClearer,
    private val accountLinkedLocalDataCleaner: AccountLinkedLocalDataCleaner = NoopAccountLinkedLocalDataCleaner,
    private val localAccountCleanupStore: LocalAccountCleanupStore = NoopLocalAccountCleanupStore,
    private val accountDeletionRequestStore: AccountDeletionRequestStore = NoopAccountDeletionRequestStore,
    private val accountOperationFence: AccountOperationFence = AccountOperationFence(localAccountCleanupStore),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : TagRepository {

    override val isSyncAvailable: Flow<Boolean> = authSessionProvider.session.map { session ->
        session != null && remoteConfig.isConfigured
    }

    override val cloudState: Flow<SharedTagCloudState> = authSessionProvider.session.map { session ->
        SharedTagCloudState(
            isConfigured = remoteConfig.isConfigured,
            isSignedIn = session != null && remoteConfig.isConfigured,
            signedInEmail = session?.userEmail,
        )
    }

    override fun observeUsageSummary(): Flow<UsageSummary> = usageSummaryDataSource.observeUsageSummary()

    override fun featureEntitlements(): FeatureEntitlements = usageSummaryDataSource.entitlements

    override fun observeAllTagsWithCount(): Flow<List<TagWithCount>> {
        return authSessionProvider.session.flatMapLatest { session ->
            tagDao.observeVisibleTagsWithCount(session?.authUserId)
        }
    }

    override fun observeTagsForEntry(entryId: Long): Flow<List<SharedTagRecord>> {
        return authSessionProvider.session.flatMapLatest { session ->
            tagDao.observeVisibleTagsForEntry(entryId, session?.authUserId)
        }
    }

    override fun observeEntriesForTag(tagId: Long): Flow<List<UrlEntryEntity>> {
        return authSessionProvider.session.flatMapLatest { session ->
            tagDao.observeEntriesForVisibleTag(tagId, session?.authUserId)
                .map { entries -> entries.map(UrlEntryListProjection::asListEntity) }
        }
    }

    override fun observeTag(tagId: Long): Flow<SharedTagRecord?> {
        return authSessionProvider.session.flatMapLatest { session ->
            tagDao.observeVisibleTag(tagId, session?.authUserId)
        }
    }

    override fun observeTagByRemoteId(remoteTagId: String): Flow<SharedTagRecord?> {
        return authSessionProvider.session.flatMapLatest { session ->
            tagDao.observeVisibleTagByRemoteId(remoteTagId, session?.authUserId)
        }
    }

    override fun observeMembersForTag(tagId: Long): Flow<List<SharedTagMemberRecord>> {
        return authSessionProvider.session.flatMapLatest { session ->
            if (session == null) {
                flowOf(emptyList())
            } else {
                tagDao.observeActiveMembersForTag(tagId, session.authUserId)
            }
        }
    }

    override fun observeGroups(): Flow<List<SharedTagGroupRecord>> {
        return authSessionProvider.session.flatMapLatest { session ->
            if (session == null) {
                flowOf(emptyList())
            } else {
                syncDao.observeGroups(session.authUserId)
            }
        }
    }

    override fun observeGroupMembers(groupId: Long): Flow<List<SharedTagGroupMemberRecord>> {
        return authSessionProvider.session.flatMapLatest { session ->
            if (session == null) {
                flowOf(emptyList())
            } else {
                syncDao.observeGroupMembers(session.authUserId, groupId)
            }
        }
    }

    override fun observeGroupTags(groupId: Long): Flow<List<SharedTagGroupTagRecord>> {
        return authSessionProvider.session.flatMapLatest { session ->
            if (session == null) {
                flowOf(emptyList())
            } else {
                syncDao.observeGroupTags(session.authUserId, groupId)
            }
        }
    }

    override suspend fun createTag(name: String): Long? {
        return when (val result = createTagWithResult(name)) {
            is CreateTagResult.Success -> result.tagId
            CreateTagResult.InvalidName,
            CreateTagResult.Duplicate,
            is CreateTagResult.LimitReached,
            CreateTagResult.Failed,
            -> null
        }
    }

    override suspend fun createTagWithResult(name: String): CreateTagResult {
        return createLocalTagWithResult(name)
    }

    override suspend fun createLocalTagWithResult(name: String): CreateTagResult {
        val normalized = normalizeSharedTagName(name)
        if (validateSharedTagName(normalized) != null) {
            return CreateTagResult.InvalidName
        }
        val usage = usageSummaryDataSource.getUsageSummary()
        val limitResult = usageSummaryDataSource.limitChecker.checkCanCreateNormalTag(usage)
        if (limitResult is LimitResult.Blocked) {
            return CreateTagResult.LimitReached(limitResult.message)
        }
        return createLocalTag(normalized)
    }

    override suspend fun createSyncedTagWithResult(name: String): CreateTagResult =
        withAccountOperation(blockedResult = { CreateTagResult.Failed }) {
            createSyncedTagWithResultWithinFence(name)
        }

    private suspend fun createSyncedTagWithResultWithinFence(name: String): CreateTagResult {
        val normalized = normalizeSharedTagName(name)
        if (validateSharedTagName(normalized) != null) {
            return CreateTagResult.InvalidName
        }
        val session = currentSyncSessionOrNull() ?: return CreateTagResult.Failed
        if (!remoteConfig.isConfigured) return CreateTagResult.Failed
        val usage = usageSummaryDataSource.getUsageSummary()
        val limitResult = usageSummaryDataSource.limitChecker.checkCanCreateSharedTag(usage)
        if (limitResult is LimitResult.Blocked) {
            return CreateTagResult.LimitReached(limitResult.message)
        }
        return createSyncedTag(normalized, session)
    }

    override suspend fun findLocalTagIdByName(name: String): Long? {
        return tagDao.findLocalTagByName(normalizeSharedTagName(name))?.id
    }

    override suspend fun renameLocalTagWithResult(tagId: Long, name: String): CreateTagResult {
        val normalized = normalizeSharedTagName(name)
        if (validateSharedTagName(normalized) != null) {
            return CreateTagResult.InvalidName
        }
        return database.withTransaction {
            val tag = tagDao.findTagById(tagId)
                ?: return@withTransaction CreateTagResult.Failed
            if (tag.scope != SharedTagScope.LOCAL_ONLY || tag.deletedAt != null) {
                return@withTransaction CreateTagResult.Failed
            }
            val existing = tagDao.findLocalTagByName(normalized)
            if (existing != null && existing.id != tagId) {
                return@withTransaction CreateTagResult.Duplicate
            }
            val updated = runCatching {
                tagDao.upsertTags(listOf(tag.copy(name = normalized)))
            }.isSuccess
            if (updated) CreateTagResult.Success(tagId) else CreateTagResult.Failed
        }
    }

    override suspend fun deleteTag(tagId: Long) =
        withAccountOperation(blockedResult = { Unit }) {
            deleteTagWithinFence(tagId)
        }

    private suspend fun deleteTagWithinFence(tagId: Long) {
        val tag = tagDao.findTagById(tagId) ?: return
        if (tag.scope == SharedTagScope.LOCAL_ONLY) {
            tagDao.deleteTag(tagId)
            return
        }

        val session = currentSyncSessionOrNull() ?: return
        val remoteTagId = tag.remoteTagId ?: return
        val now = clock.nowEpochMillis()
        val clientId = syncCoordinator.ensureClientId(session.authUserId)
        database.withTransaction {
            tagDao.upsertTags(
                listOf(
                    tag.copy(
                        deletedAt = now,
                        syncStatus = SharedTagSyncStatus.PENDING_PUSH,
                        syncErrorMessage = null,
                    ),
                ),
            )
            enqueueOutbox(
                session = session,
                operation = SharedTagSyncOperation(
                    opId = UUID.randomUUID().toString(),
                    clientId = clientId,
                    type = SharedTagSyncOperationType.DELETE_TAG,
                    submittedAt = now,
                    tagId = remoteTagId,
                ),
            )
        }
        scheduleSync(session.authUserId)
    }

    override suspend fun assignTag(tagId: Long, entryId: Long) {
        assignTagWithResult(tagId, entryId)
    }

    override suspend fun assignTagWithResult(tagId: Long, entryId: Long): AssignTagResult =
        withAccountOperation(blockedResult = { AssignTagResult.Failed }) {
            assignTagWithResultWithinFence(tagId, entryId)
        }

    private suspend fun assignTagWithResultWithinFence(tagId: Long, entryId: Long): AssignTagResult {
        val tag = tagDao.findTagById(tagId) ?: return AssignTagResult.Failed
        val now = clock.nowEpochMillis()
        if (tag.scope == SharedTagScope.LOCAL_ONLY) {
            val inserted = tagDao.insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = entryId, createdAt = now))
            return if (inserted == -1L) {
                AssignTagResult.AlreadyAssigned
            } else {
                AssignTagResult.Success
            }
        }

        val session = currentSessionForSyncedTag(tag) ?: return AssignTagResult.Failed
        val remoteTagId = tag.remoteTagId ?: return AssignTagResult.Failed
        val entry = urlEntryDao.findById(entryId) ?: return AssignTagResult.Failed
        val existingRef = tagDao.findCrossRef(tagId, entryId)
        if (existingRef != null && existingRef.deletedAt == null) return AssignTagResult.AlreadyAssigned

        val usage = usageSummaryDataSource.getUsageSummary()
        val limitResult = usageSummaryDataSource.limitChecker.checkCanAddUrlToSharedTag(usage, tagId)
        if (limitResult is LimitResult.Blocked) {
            return AssignTagResult.LimitReached(limitResult.message)
        }

        val remoteUrlId = existingRef?.remoteUrlId ?: UUID.randomUUID().toString()
        val canSyncRemote = remoteConfig.isConfigured
        val clientId = if (canSyncRemote) syncCoordinator.ensureClientId(session.authUserId) else null
        database.withTransaction {
            val upserted = TagUrlCrossRef(
                tagId = tagId,
                entryId = entryId,
                createdAt = now,
                scope = SharedTagScope.SYNCED,
                authUserId = session.authUserId,
                remoteUrlId = remoteUrlId,
                rawUrl = entry.originalUrl,
                normalizedUrl = entry.normalizedUrl,
                normalizationVersion = SHARED_TAG_NORMALIZATION_VERSION,
                syncStatus = SharedTagSyncStatus.PENDING_PUSH,
                deletedAt = null,
                lastSyncedAt = existingRef?.lastSyncedAt,
                syncErrorMessage = null,
            )
            tagDao.upsertCrossRefs(listOf(upserted))
            tagDao.upsertTags(listOf(tag.copy(syncStatus = SharedTagSyncStatus.PENDING_PUSH, syncErrorMessage = null)))
            if (canSyncRemote) {
                enqueueOutbox(
                    session = session,
                    operation = SharedTagSyncOperation(
                        opId = UUID.randomUUID().toString(),
                        clientId = requireNotNull(clientId),
                        type = SharedTagSyncOperationType.ADD_URL_TO_TAG,
                        submittedAt = now,
                        tagId = remoteTagId,
                        urlId = remoteUrlId,
                        rawUrl = entry.originalUrl,
                        normalizedUrl = entry.normalizedUrl,
                        normalizationVersion = SHARED_TAG_NORMALIZATION_VERSION,
                    ),
                )
            }
        }
        if (canSyncRemote) {
            scheduleSync(session.authUserId)
        }
        return AssignTagResult.Success
    }

    override suspend fun removeTag(tagId: Long, entryId: Long) =
        withAccountOperation(blockedResult = { Unit }) {
            removeTagWithinFence(tagId, entryId)
        }

    private suspend fun removeTagWithinFence(tagId: Long, entryId: Long) {
        val tag = tagDao.findTagById(tagId) ?: return
        if (tag.scope == SharedTagScope.LOCAL_ONLY) {
            tagDao.deleteCrossRef(tagId = tagId, entryId = entryId)
            return
        }

        val session = currentSessionForSyncedTag(tag) ?: return
        val remoteTagId = tag.remoteTagId ?: return
        val ref = tagDao.findCrossRef(tagId, entryId) ?: return
        val normalizedUrl = ref.normalizedUrl ?: urlEntryDao.findById(entryId)?.normalizedUrl ?: return
        val now = clock.nowEpochMillis()
        val canSyncRemote = remoteConfig.isConfigured
        val clientId = if (canSyncRemote) syncCoordinator.ensureClientId(session.authUserId) else null
        database.withTransaction {
            tagDao.updateCrossRefDeletion(
                tagId = tagId,
                entryId = entryId,
                deletedAt = now,
                syncStatus = SharedTagSyncStatus.PENDING_PUSH,
            )
            tagDao.upsertTags(listOf(tag.copy(syncStatus = SharedTagSyncStatus.PENDING_PUSH, syncErrorMessage = null)))
            if (canSyncRemote) {
                enqueueOutbox(
                    session = session,
                    operation = SharedTagSyncOperation(
                        opId = UUID.randomUUID().toString(),
                        clientId = requireNotNull(clientId),
                        type = SharedTagSyncOperationType.REMOVE_URL_FROM_TAG,
                        submittedAt = now,
                        tagId = remoteTagId,
                        normalizedUrl = normalizedUrl,
                    ),
                )
            }
        }
        if (canSyncRemote) {
            scheduleSync(session.authUserId)
        }
    }

    override suspend fun exportTag(tagId: Long): TagSharePayload? {
        val tag = tagDao.findTagById(tagId) ?: return null
        if (tag.deletedAt != null || tag.scope != SharedTagScope.LOCAL_ONLY) return null
        val entries = tagDao.getEntriesForTag(tagId)
            .filter { entry ->
                entry.localProvenanceCount > 0 &&
                    entry.recordState in setOf(RecordState.ACTIVE, RecordState.ARCHIVED) &&
                    entry.pendingDeletionUntil == null
            }
        return TagSharePayload(
            urlsaverVersion = 1,
            tag = tag.name,
            exportedAt = clock.nowEpochMillis(),
            urls = entries.map { entry ->
                TagShareUrl(
                    url = entry.normalizedUrl,
                )
            },
        )
    }

    override suspend fun importTag(payload: TagSharePayload): TagImportResult {
        val normalizedTagName = normalizeSharedTagName(payload.tag)
        if (normalizedTagName.isBlank() || normalizedTagName.length > MAX_SHARED_TAG_NAME_LENGTH) {
            return TagImportResult(
                tagId = -1L,
                tagName = normalizedTagName.ifBlank { payload.tag },
                created = 0,
                merged = 0,
                duplicateSkipped = 0,
                failed = payload.urls.size,
            )
        }

        val existingTagId = tagDao.findLocalTagByName(normalizedTagName)?.id
        val usage = usageSummaryDataSource.getUsageSummary()
        if (existingTagId == null) {
            val tagLimit = usageSummaryDataSource.limitChecker.checkCanCreateNormalTag(usage)
            if (tagLimit is LimitResult.Blocked) {
                return TagImportResult(
                    tagId = -1L,
                    tagName = normalizedTagName,
                    created = 0,
                    merged = 0,
                    duplicateSkipped = 0,
                    failed = 0,
                    cancelled = true,
                    message = tagLimit.message,
                )
            }
        }

        val prospectiveNewEntries = countProspectiveImportedEntries(payload)
        val personalUrlLimit = usageSummaryDataSource.entitlements.limits.personalUrlLimit
        if (usage.personalUrlCount + prospectiveNewEntries > personalUrlLimit) {
            return TagImportResult(
                tagId = existingTagId ?: -1L,
                tagName = normalizedTagName,
                created = 0,
                merged = 0,
                duplicateSkipped = 0,
                failed = 0,
                cancelled = true,
                message = "現在のプランの保存上限に達しました。不要なURLを整理してから追加してください。",
            )
        }

        val createdEntryIds = mutableListOf<Long>()
        val result = database.withTransaction {
            val now = clock.nowEpochMillis()
            val tagId = findOrCreateLocalTagId(normalizedTagName, now)
            var created = 0
            var merged = 0
            var duplicateSkipped = 0
            var failed = 0

            payload.urls.forEach { item ->
                val parsed = UrlRules.parseUrl(item.url)
                if (parsed == null) {
                    failed += 1
                    return@forEach
                }

                val existing = findExistingEntry(parsed.normalizedUrl)
                if (existing != null) {
                    val inserted = tagDao.insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = existing.id, createdAt = now))
                    if (inserted == -1L) {
                        duplicateSkipped += 1
                    } else {
                        merged += 1
                    }
                    return@forEach
                }

                val entryId = urlEntryDao.insert(
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
                        userTitle = UrlRules.normalizeUserTitle(item.title),
                        memo = normalizeImportedMemo(item.memo),
                        metadataRequestedAt = now,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                createdEntryIds += entryId
                created += 1
                tagDao.insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = entryId, createdAt = now))
            }

            TagImportResult(
                tagId = tagId,
                tagName = normalizedTagName,
                created = created,
                merged = merged,
                duplicateSkipped = duplicateSkipped,
                failed = failed,
            )
        }

        createdEntryIds.forEach { entryId ->
            runCatching { metadataScheduler.enqueueMetadata(entryId) }
        }

        return result
    }

    override suspend fun migrateLocalTagToCloud(tagId: Long): MigrateSharedTagResult =
        withAccountOperation(blockedResult = { MigrateSharedTagResult.NotEligible }) {
            migrateLocalTagToCloudWithinFence(tagId)
        }

    private suspend fun migrateLocalTagToCloudWithinFence(tagId: Long): MigrateSharedTagResult {
        val session = currentSyncSessionOrNull() ?: return MigrateSharedTagResult.NotEligible
        val tag = tagDao.findTagById(tagId) ?: return MigrateSharedTagResult.NotEligible
        if (tag.scope != SharedTagScope.LOCAL_ONLY) return MigrateSharedTagResult.NotEligible

        val existingSyncedWithName = tagDao.findActiveSyncedTagByName(session.authUserId, tag.name)
        if (existingSyncedWithName != null) return MigrateSharedTagResult.NotEligible

        val usage = usageSummaryDataSource.getUsageSummary()
        val sharedTagLimit = usageSummaryDataSource.limitChecker.checkCanCreateSharedTag(usage)
        if (sharedTagLimit is LimitResult.Blocked) {
            return MigrateSharedTagResult.LimitReached(sharedTagLimit.message)
        }

        val now = clock.nowEpochMillis()
        val remoteTagId = UUID.randomUUID().toString()
        val clientId = syncCoordinator.ensureClientId(session.authUserId)
        val entries = tagDao.getEntriesForTag(tagId)

        database.withTransaction {
            tagDao.upsertTags(
                listOf(
                    tag.copy(
                        scope = SharedTagScope.SYNCED,
                        authUserId = session.authUserId,
                        remoteTagId = remoteTagId,
                        syncStatus = SharedTagSyncStatus.PENDING_PUSH,
                        deletedAt = null,
                        lastSyncedAt = null,
                        syncErrorMessage = null,
                    ),
                ),
            )

            enqueueOutbox(
                session = session,
                operation = SharedTagSyncOperation(
                    opId = UUID.randomUUID().toString(),
                    clientId = clientId,
                    type = SharedTagSyncOperationType.CREATE_TAG,
                    submittedAt = now,
                    tagId = remoteTagId,
                    name = tag.name,
                ),
            )

            entries.forEach { entry ->
                val remoteUrlId = UUID.randomUUID().toString()
                tagDao.upsertCrossRefs(
                    listOf(
                        TagUrlCrossRef(
                            tagId = tagId,
                            entryId = entry.id,
                            createdAt = now,
                            scope = SharedTagScope.SYNCED,
                            authUserId = session.authUserId,
                            remoteUrlId = remoteUrlId,
                            rawUrl = entry.originalUrl,
                            normalizedUrl = entry.normalizedUrl,
                            normalizationVersion = SHARED_TAG_NORMALIZATION_VERSION,
                            syncStatus = SharedTagSyncStatus.PENDING_PUSH,
                            deletedAt = null,
                            lastSyncedAt = null,
                            syncErrorMessage = null,
                        ),
                    ),
                )
                enqueueOutbox(
                    session = session,
                    operation = SharedTagSyncOperation(
                        opId = UUID.randomUUID().toString(),
                        clientId = clientId,
                        type = SharedTagSyncOperationType.ADD_URL_TO_TAG,
                        submittedAt = now,
                        tagId = remoteTagId,
                        urlId = remoteUrlId,
                        rawUrl = entry.originalUrl,
                        normalizedUrl = entry.normalizedUrl,
                        normalizationVersion = SHARED_TAG_NORMALIZATION_VERSION,
                    ),
                )
            }
        }

        scheduleSync(session.authUserId)
        return MigrateSharedTagResult.Success
    }

    override suspend fun triggerSync(): Boolean = withAccountOperation(blockedResult = { false }) {
        val session = currentSyncSessionOrNull() ?: return@withAccountOperation false
        syncNowOrSchedule(session.authUserId)
    }

    override suspend fun triggerSyncIfStale(minIntervalMillis: Long): Boolean =
        withAccountOperation(blockedResult = { false }) {
            val session = currentSyncSessionOrNull() ?: return@withAccountOperation false
            val now = clock.nowEpochMillis()
            val lastPulledAt = syncDao.findSyncState(session.authUserId)?.lastPulledAt
            if (lastPulledAt != null && now - lastPulledAt < minIntervalMillis) {
                return@withAccountOperation false
            }
            syncNowOrSchedule(session.authUserId)
        }

    override suspend fun signIn(email: String, password: String): SharedTagAuthResult =
        accountOperationFence.withExclusiveOperation {
            if (localAccountCleanupStore.pending.value != null) {
                SharedTagAuthResult.Failure(ACCOUNT_CLEANUP_IN_PROGRESS_MESSAGE)
            } else {
                signInWithinFence(email, password)
            }
        }

    private suspend fun signInWithinFence(email: String, password: String): SharedTagAuthResult {
        if (!remoteConfig.isConfigured) {
            return SharedTagAuthResult.Failure("クラウド共有はまだ設定されていません")
        }
        return runCatching {
            when (val result = authRemoteDataSource.signIn(email.trim(), password)) {
                is SharedTagAuthRemoteResult.NeedsEmailConfirmation -> SharedTagAuthResult.NeedsEmailConfirmation
                is SharedTagAuthRemoteResult.SignedIn -> {
                    authSessionProvider.updateSession(result.session)
                    scheduleSync(result.session.authUserId)
                    SharedTagAuthResult.Success(result.session.userEmail)
                }
            }
        }.getOrElse { error ->
            SharedTagAuthResult.Failure(error.message ?: "サインインできませんでした")
        }
    }

    override fun googleOAuthUrl(): String? {
        if (!remoteConfig.isConfigured) return null
        return runCatching {
            authRemoteDataSource.oauthUrl(
                provider = "google",
                redirectTo = "urlsaver://auth/callback",
            )
        }.getOrNull()
    }

    override suspend fun handleOAuthCallback(callbackUrl: String): SharedTagAuthResult =
        accountOperationFence.withExclusiveOperation {
            if (localAccountCleanupStore.pending.value != null) {
                SharedTagAuthResult.Failure(ACCOUNT_CLEANUP_IN_PROGRESS_MESSAGE)
            } else {
                handleOAuthCallbackWithinFence(callbackUrl)
            }
        }

    private suspend fun handleOAuthCallbackWithinFence(callbackUrl: String): SharedTagAuthResult {
        if (!remoteConfig.isConfigured) {
            return SharedTagAuthResult.Failure("クラウド共有はまだ設定されていません")
        }
        return runCatching {
            when (val result = authRemoteDataSource.signInWithOAuthCallback(callbackUrl)) {
                SharedTagAuthRemoteResult.NeedsEmailConfirmation -> SharedTagAuthResult.NeedsEmailConfirmation
                is SharedTagAuthRemoteResult.SignedIn -> {
                    authSessionProvider.updateSession(result.session)
                    scheduleSync(result.session.authUserId)
                    SharedTagAuthResult.Success(result.session.userEmail)
                }
            }
        }.getOrElse { error ->
            SharedTagAuthResult.Failure(error.message ?: "Googleでサインインできませんでした")
        }
    }

    override suspend fun signUp(email: String, password: String): SharedTagAuthResult =
        accountOperationFence.withExclusiveOperation {
            if (localAccountCleanupStore.pending.value != null) {
                SharedTagAuthResult.Failure(ACCOUNT_CLEANUP_IN_PROGRESS_MESSAGE)
            } else {
                signUpWithinFence(email, password)
            }
        }

    private suspend fun signUpWithinFence(email: String, password: String): SharedTagAuthResult {
        if (!remoteConfig.isConfigured) {
            return SharedTagAuthResult.Failure("クラウド共有はまだ設定されていません")
        }
        return runCatching {
            when (val result = authRemoteDataSource.signUp(email.trim(), password)) {
                is SharedTagAuthRemoteResult.NeedsEmailConfirmation -> SharedTagAuthResult.NeedsEmailConfirmation
                is SharedTagAuthRemoteResult.SignedIn -> {
                    authSessionProvider.updateSession(result.session)
                    scheduleSync(result.session.authUserId)
                    SharedTagAuthResult.Success(result.session.userEmail)
                }
            }
        }.getOrElse { error ->
            SharedTagAuthResult.Failure(error.message ?: "アカウントを作成できませんでした")
        }
    }

    override suspend fun resendEmailConfirmation(email: String): SharedTagAuthResult {
        if (!remoteConfig.isConfigured) {
            return SharedTagAuthResult.Failure("クラウド共有はまだ設定されていません")
        }
        return runCatching {
            authRemoteDataSource.resendEmailConfirmation(email.trim())
            SharedTagAuthResult.NeedsEmailConfirmation
        }.getOrElse { error ->
            SharedTagAuthResult.Failure(error.message ?: "確認メールを再送できませんでした")
        }
    }

    override suspend fun sendPasswordRecovery(email: String): SharedTagAuthResult {
        if (!remoteConfig.isConfigured) {
            return SharedTagAuthResult.Failure("クラウド共有はまだ設定されていません")
        }
        return runCatching {
            authRemoteDataSource.sendPasswordRecovery(email.trim())
            SharedTagAuthResult.NeedsEmailConfirmation
        }.getOrElse { error ->
            SharedTagAuthResult.Failure(error.message ?: "パスワード再設定メールを送信できませんでした")
        }
    }

    override suspend fun signOut() {
        accountOperationFence.withExclusiveOperation {
            authSessionProvider.updateSession(null)
        }
    }

    override suspend fun deleteAccount(): SharedTagAccountDeletionResult =
        accountOperationFence.withExclusiveOperation { deleteAccountLocked() }

    private suspend fun deleteAccountLocked(): SharedTagAccountDeletionResult {
        localAccountCleanupStore.pending.value?.let { pending ->
            val retryResult = clearLocalAccountData(pending)
            if (retryResult == SharedTagAccountDeletionResult.Success) {
                accountDeletionRequestStore.clear()
            }
            return retryResult
        }
        val session = currentSyncSessionOrNull()
        val pendingRequest = accountDeletionRequestStore.pending
        if (pendingRequest != null) {
            return convergePendingAccountDeletion(pendingRequest, session)
        }
        if (session == null) {
            return SharedTagAccountDeletionResult.AuthRequired
        }
        val grant = runCatching {
            remoteDataSource.createAccountDeletionRequest(session)
        }.getOrElse { failure ->
            if (failure is UnsupportedOperationException) {
                return legacyDeleteAccountLocked(session)
            }
            return SharedTagAccountDeletionResult.Failure(
                deletionFailureMessage(failure),
            )
        }
        // Persist the durable recovery record BEFORE the remote call. If the
        // process dies at any later point — including right after the server
        // committed but before any local marker exists — the next launch
        // converges via the recorded request instead of repeating or stalling.
        accountDeletionRequestStore.save(
            AccountDeletionRequestRecord(
                authUserId = session.authUserId,
                requestId = grant.requestId,
                token = grant.token,
            )
        )
        val remoteError = runCatching {
            remoteDataSource.deleteAccountWithRequest(session, grant.requestId)
        }.exceptionOrNull()
        if (remoteError != null) {
            // The record is intentionally kept: a lost response after a server
            // commit is resolved by convergence on the next attempt.
            val message = remoteError.message.orEmpty()
            return if (message.contains("owner_transfer_required", ignoreCase = true)) {
                SharedTagAccountDeletionResult.OwnerTransferRequired
            } else {
                SharedTagAccountDeletionResult.Failure(
                    message.ifBlank { "アカウントを削除できませんでした" },
                )
            }
        }
        return finishRemoteAccountDeletion(session.authUserId)
    }

    private suspend fun convergePendingAccountDeletion(
        record: AccountDeletionRequestRecord,
        session: SharedTagAuthSession?,
    ): SharedTagAccountDeletionResult {
        val usableSession = session?.takeIf { it.authUserId == record.authUserId }
        if (usableSession != null) {
            // Replaying the same request id is safe: a committed deletion keeps
            // returning the identical successful result.
            val remoteError = runCatching {
                remoteDataSource.deleteAccountWithRequest(usableSession, record.requestId)
            }.exceptionOrNull()
            if (remoteError == null) {
                return finishRemoteAccountDeletion(record.authUserId)
            }
            val message = remoteError.message.orEmpty()
            if (message.contains("owner_transfer_required", ignoreCase = true)) {
                return SharedTagAccountDeletionResult.OwnerTransferRequired
            }
            // Fall through to the status query for the definitive outcome; the
            // server may have committed before the failure.
        }
        val statusResult = runCatching {
            remoteDataSource.accountDeletionStatus(record.requestId, record.token)
        }
        val statusFailure = statusResult.exceptionOrNull()
        if (statusFailure != null) {
            return SharedTagAccountDeletionResult.Failure(
                deletionFailureMessage(statusFailure).let { message ->
                    if (message == "アカウントを削除できませんでした") "アカウント削除の状態を確認できませんでした" else message
                },
            )
        }
        val status = statusResult.getOrThrow()
        if (status.isCompleted) {
            return finishRemoteAccountDeletion(record.authUserId)
        }
        // pending / not_found / unknown: the server never confirmed deletion, so
        // repeating it blindly here would not be safe without a live session.
        return SharedTagAccountDeletionResult.Failure("アカウント削除が完了していません。サインイン後にもう一度お試しください")
    }

    private suspend fun legacyDeleteAccountLocked(
        session: SharedTagAuthSession,
    ): SharedTagAccountDeletionResult {
        val remoteError = runCatching {
            remoteDataSource.deleteAccount(session)
        }.exceptionOrNull()
        if (remoteError != null) {
            val message = remoteError.message.orEmpty()
            return if (message.contains("owner_transfer_required", ignoreCase = true)) {
                SharedTagAccountDeletionResult.OwnerTransferRequired
            } else {
                SharedTagAccountDeletionResult.Failure(
                    message.ifBlank { "アカウントを削除できませんでした" },
                )
            }
        }
        return finishRemoteAccountDeletion(session.authUserId)
    }

    private suspend fun finishRemoteAccountDeletion(
        authUserId: String,
    ): SharedTagAccountDeletionResult {
        accountOperationFence.markRemoteAccountDeleted(authUserId)
        val marker = LocalAccountCleanupMarker(
            aiDataPending = true,
            sessionPending = true,
            syncWorkCancellationPending = true,
            sharedDataCleanupPending = true,
            pendingInviteCleanupPending = false,
            entitlementCleanupPending = true,
            personalLinkSettingsCleanupPending = true,
            authUserId = authUserId,
        )
        localAccountCleanupStore.save(marker)
        val result = clearLocalAccountData(marker)
        if (result == SharedTagAccountDeletionResult.Success) {
            accountDeletionRequestStore.clear()
        }
        return result
    }

    private fun deletionFailureMessage(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: "アカウントを削除できませんでした"

    override suspend fun retryLocalAccountCleanup(): SharedTagAccountDeletionResult =
        accountOperationFence.withExclusiveOperation {
            val pending = localAccountCleanupStore.pending.value
                ?: return@withExclusiveOperation SharedTagAccountDeletionResult.Success
            pending.authUserId?.let(accountOperationFence::markRemoteAccountDeleted)
            val result = clearLocalAccountData(pending)
            if (result == SharedTagAccountDeletionResult.Success) {
                accountDeletionRequestStore.clear()
            }
            result
        }

    private suspend fun clearLocalAccountData(
        initialPending: LocalAccountCleanupMarker,
    ): SharedTagAccountDeletionResult {
        var pending = initialPending

        if (pending.sessionPending && authSessionProvider.session.value == null) {
            pending = pending.copy(sessionPending = false)
            localAccountCleanupStore.save(pending)
        } else if (pending.sessionPending && runCatching {
                authSessionProvider.updateSession(null)
            }.isSuccess
        ) {
            pending = pending.copy(sessionPending = false)
            localAccountCleanupStore.save(pending)
        }

        if (!pending.sessionPending && pending.syncWorkCancellationPending && runCatching {
                accountLinkedLocalDataCleaner.cancelSharedTagSyncWork(requireNotNull(pending.authUserId))
            }.isSuccess
        ) {
            pending = pending.copy(syncWorkCancellationPending = false)
            localAccountCleanupStore.save(pending)
        }

        if (pending.aiDataPending && runCatching {
                aiLocalDataClearer.clearLocalAiData()
            }.isSuccess
        ) {
            pending = pending.copy(aiDataPending = false)
            localAccountCleanupStore.save(pending)
        }

        val cleanupAuthUserId = pending.authUserId?.takeIf { it.isNotBlank() }
        val canClearAccountData = !pending.sessionPending &&
            !pending.syncWorkCancellationPending &&
            cleanupAuthUserId != null
        if (canClearAccountData && pending.sharedDataCleanupPending && runCatching {
                accountLinkedLocalDataCleaner.clearSharedTagData(requireNotNull(cleanupAuthUserId))
            }.isSuccess
        ) {
            pending = pending.copy(sharedDataCleanupPending = false)
            localAccountCleanupStore.save(pending)
        }
        if (pending.pendingInviteCleanupPending) {
            // Legacy markers cannot prove ownership of the global pending invite record.
            // Resolve the obsolete stage without deleting that user intent.
            pending = pending.copy(pendingInviteCleanupPending = false)
            localAccountCleanupStore.save(pending)
        }
        if (canClearAccountData && pending.entitlementCleanupPending && runCatching {
                accountLinkedLocalDataCleaner.clearEntitlementCache(requireNotNull(cleanupAuthUserId))
            }.isSuccess
        ) {
            pending = pending.copy(entitlementCleanupPending = false)
            localAccountCleanupStore.save(pending)
        }
        if (canClearAccountData && pending.personalLinkSettingsCleanupPending && runCatching {
                accountLinkedLocalDataCleaner.clearChatGptPersonalLinkSettings(requireNotNull(cleanupAuthUserId))
            }.isSuccess
        ) {
            pending = pending.copy(personalLinkSettingsCleanupPending = false)
            localAccountCleanupStore.save(pending)
        }

        return if (!pending.requiresCleanup) {
            localAccountCleanupStore.clear()
            SharedTagAccountDeletionResult.Success
        } else {
            SharedTagAccountDeletionResult.LocalCleanupRequired(
                aiDataPending = pending.aiDataPending,
                sessionPending = pending.sessionPending,
                syncWorkCancellationPending = pending.syncWorkCancellationPending,
                sharedDataCleanupPending = pending.sharedDataCleanupPending,
                pendingInviteCleanupPending = pending.pendingInviteCleanupPending,
                entitlementCleanupPending = pending.entitlementCleanupPending,
                personalLinkSettingsCleanupPending = pending.personalLinkSettingsCleanupPending,
            )
        }
    }

    override suspend fun createInviteLink(tagId: Long): SharedTagInviteCreationResult =
        withAccountOperation(blockedResult = { SharedTagInviteCreationResult.AuthRequired }) {
            createInviteLinkWithinFence(tagId)
        }

    private suspend fun createInviteLinkWithinFence(tagId: Long): SharedTagInviteCreationResult {
        val session = currentSyncSessionOrNull() ?: return SharedTagInviteCreationResult.AuthRequired
        val tag = tagDao.findTagById(tagId) ?: return SharedTagInviteCreationResult.NotSharedTag
        if (tag.scope != SharedTagScope.SYNCED || tag.remoteTagId.isNullOrBlank()) {
            return SharedTagInviteCreationResult.NotSharedTag
        }
        if (tag.syncStatus != SharedTagSyncStatus.SYNCED) {
            return SharedTagInviteCreationResult.SyncPending
        }
        val member = tagDao.findCurrentUserMember(tagId, session.authUserId)
        if (member?.role != SharedTagMemberRole.OWNER) {
            return SharedTagInviteCreationResult.OwnerOnly
        }
        return runCatching {
            val invite = remoteDataSource.createInvite(
                session = session,
                remoteTagId = requireNotNull(tag.remoteTagId),
                role = SHARED_TAG_INVITE_ROLE,
            )
            SharedTagInviteCreationResult.Success(
                inviteToken = invite.inviteToken,
                inviteUrl = buildInviteUrl(invite.inviteToken),
                expiresAt = invite.expiresAt,
            )
        }.getOrElse { error ->
            SharedTagInviteCreationResult.Failure(error.message ?: "招待リンクを作成できませんでした")
        }
    }

    override suspend fun createGroup(name: String): Boolean {
        return createGroupWithResult(name) == CreateSharedTagGroupResult.Success
    }

    override suspend fun createGroupWithResult(name: String): CreateSharedTagGroupResult =
        withAccountOperation(blockedResult = { CreateSharedTagGroupResult.AuthRequired }) {
            createGroupWithResultWithinFence(name)
        }

    private suspend fun createGroupWithResultWithinFence(name: String): CreateSharedTagGroupResult {
        val normalized = normalizeSharedTagName(name)
        if (validateSharedTagName(normalized) != null) return CreateSharedTagGroupResult.InvalidName
        val session = currentSyncSessionOrNull() ?: return CreateSharedTagGroupResult.AuthRequired
        val usage = usageSummaryDataSource.getUsageSummary()
        val limit = usageSummaryDataSource.limitChecker.checkCanCreateSharedTagGroup(usage)
        if (limit is LimitResult.Blocked) {
            return CreateSharedTagGroupResult.LimitReached(limit.message)
        }
        return runCatching {
            remoteDataSource.createGroup(session, normalized)
            syncNowOrSchedule(session.authUserId)
            CreateSharedTagGroupResult.Success
        }.getOrElse { error ->
            CreateSharedTagGroupResult.Failed(error.message)
        }
    }

    override suspend fun addTagToGroup(groupId: Long, tagId: Long): Boolean =
        withAccountOperation(blockedResult = { false }) {
            addTagToGroupWithinFence(groupId, tagId)
        }

    private suspend fun addTagToGroupWithinFence(groupId: Long, tagId: Long): Boolean {
        val session = currentSyncSessionOrNull() ?: return false
        val tag = tagDao.findTagById(tagId) ?: return false
        val remoteTagId = tag.remoteTagId ?: return false
        val remoteGroupId = syncDao.findLocalGroupById(session.authUserId, groupId)?.remoteGroupId ?: return false
        return runCatching {
            remoteDataSource.addTagToGroup(session, remoteGroupId, remoteTagId)
            syncNowOrSchedule(session.authUserId)
            true
        }.getOrDefault(false)
    }

    override suspend fun removeTagFromGroup(groupId: Long, tagId: Long): Boolean =
        withAccountOperation(blockedResult = { false }) {
            removeTagFromGroupWithinFence(groupId, tagId)
        }

    private suspend fun removeTagFromGroupWithinFence(groupId: Long, tagId: Long): Boolean {
        val session = currentSyncSessionOrNull() ?: return false
        val tag = tagDao.findTagById(tagId) ?: return false
        val remoteTagId = tag.remoteTagId ?: return false
        val remoteGroupId = syncDao.findLocalGroupById(session.authUserId, groupId)?.remoteGroupId ?: return false
        return runCatching {
            remoteDataSource.removeTagFromGroup(session, remoteGroupId, remoteTagId)
            syncNowOrSchedule(session.authUserId)
            true
        }.getOrDefault(false)
    }

    override suspend fun createGroupInviteLink(
        groupId: Long,
        role: String,
    ): SharedTagGroupInviteCreationResult =
        withAccountOperation(blockedResult = { SharedTagGroupInviteCreationResult.AuthRequired }) {
            createGroupInviteLinkWithinFence(groupId, role)
        }

    private suspend fun createGroupInviteLinkWithinFence(
        groupId: Long,
        role: String,
    ): SharedTagGroupInviteCreationResult {
        val session = currentSyncSessionOrNull() ?: return SharedTagGroupInviteCreationResult.AuthRequired
        val remoteGroupId = syncDao.findLocalGroupById(session.authUserId, groupId)?.remoteGroupId
            ?: return SharedTagGroupInviteCreationResult.Failure("グループが見つかりませんでした")
        val inviteRole = role.lowercase().takeIf { it == "editor" || it == "viewer" } ?: "editor"
        return runCatching {
            val invite = remoteDataSource.createGroupInvite(session, remoteGroupId, inviteRole)
            SharedTagGroupInviteCreationResult.Success(
                inviteToken = invite.inviteToken,
                inviteUrl = buildInviteUrl(invite.inviteToken),
                expiresAt = invite.expiresAt,
            )
        }.getOrElse { error ->
            val message = error.message.orEmpty()
            if (message.contains("forbidden", ignoreCase = true)) {
                SharedTagGroupInviteCreationResult.OwnerOnly
            } else {
                SharedTagGroupInviteCreationResult.Failure(message.ifBlank { "グループ招待リンクを作成できませんでした" })
            }
        }
    }

    override suspend fun renameGroup(groupId: Long, name: String): SharedTagGroupMutationResult {
        val normalized = normalizeSharedTagName(name)
        if (validateSharedTagName(normalized) != null) return SharedTagGroupMutationResult.InvalidTarget
        return mutateGroup(groupId) { session, remoteGroupId ->
            remoteDataSource.renameGroup(session, remoteGroupId, normalized)
        }
    }

    override suspend fun deleteGroup(groupId: Long): SharedTagGroupMutationResult {
        return mutateGroup(groupId) { session, remoteGroupId ->
            remoteDataSource.deleteGroup(session, remoteGroupId)
        }
    }

    override suspend fun changeGroupMemberRole(
        groupId: Long,
        userId: String,
        role: SharedTagMemberRole,
    ): SharedTagGroupMutationResult {
        val targetUserId = userId.trim()
        if (targetUserId.isBlank()) return SharedTagGroupMutationResult.InvalidTarget
        return mutateGroup(groupId) { session, remoteGroupId ->
            remoteDataSource.changeGroupMemberRole(
                session = session,
                remoteGroupId = remoteGroupId,
                userId = targetUserId,
                role = role.name.lowercase(),
            )
        }
    }

    override suspend fun transferGroupOwnership(
        groupId: Long,
        newOwnerUserId: String,
    ): SharedTagGroupMutationResult {
        val targetUserId = newOwnerUserId.trim()
        if (targetUserId.isBlank()) return SharedTagGroupMutationResult.InvalidTarget
        return mutateGroup(groupId) { session, remoteGroupId ->
            remoteDataSource.transferGroupOwnership(session, remoteGroupId, targetUserId)
        }
    }

    override suspend fun removeGroupMember(groupId: Long, userId: String): SharedTagGroupMutationResult {
        val targetUserId = userId.trim()
        if (targetUserId.isBlank()) return SharedTagGroupMutationResult.InvalidTarget
        return mutateGroup(groupId) { session, remoteGroupId ->
            remoteDataSource.removeGroupMember(session, remoteGroupId, targetUserId)
        }
    }

    override suspend fun syncSharedProfileDisplayName(displayName: String): Boolean =
        withAccountOperation(blockedResult = { false }) {
            syncSharedProfileDisplayNameWithinFence(displayName)
        }

    private suspend fun syncSharedProfileDisplayNameWithinFence(displayName: String): Boolean {
        val session = currentSyncSessionOrNull() ?: return false
        return runCatching {
            remoteDataSource.upsertSharedProfile(session, displayName.trim().take(40))
            syncNowOrSchedule(session.authUserId)
            true
        }.getOrDefault(false)
    }

    private suspend fun mutateGroup(
        groupId: Long,
        block: suspend (SharedTagAuthSession, String) -> Any?,
    ): SharedTagGroupMutationResult = withAccountOperation(
        blockedResult = { SharedTagGroupMutationResult.AuthRequired },
    ) {
        mutateGroupWithinFence(groupId, block)
    }

    private suspend fun mutateGroupWithinFence(
        groupId: Long,
        block: suspend (SharedTagAuthSession, String) -> Any?,
    ): SharedTagGroupMutationResult {
        val session = currentSyncSessionOrNull() ?: return SharedTagGroupMutationResult.AuthRequired
        val remoteGroupId = syncDao.findLocalGroupById(session.authUserId, groupId)?.remoteGroupId
            ?: return SharedTagGroupMutationResult.InvalidTarget
        return runCatching {
            block(session, remoteGroupId)
            syncNowOrSchedule(session.authUserId)
            SharedTagGroupMutationResult.Success
        }.getOrElse { error ->
            val message = error.message.orEmpty()
            when {
                message.contains("auth_required", ignoreCase = true) ->
                    SharedTagGroupMutationResult.AuthRequired
                message.contains("forbidden", ignoreCase = true) ->
                    SharedTagGroupMutationResult.OwnerOnly
                message.contains("invalid", ignoreCase = true) ||
                    message.contains("not_found", ignoreCase = true) ||
                    message.contains("transfer_required", ignoreCase = true) ||
                    message.contains("owns_group_tag", ignoreCase = true) ->
                    SharedTagGroupMutationResult.InvalidTarget
                else -> SharedTagGroupMutationResult.Failure(message.ifBlank { "グループを更新できませんでした" })
            }
        }
    }

    override suspend fun previewInvite(inviteToken: String): SharedTagInvitePreviewResult {
        val token = inviteToken.trim()
        if (token.isBlank()) return SharedTagInvitePreviewResult.InvalidInvite
        return runCatching {
            val preview = remoteDataSource.previewInvite(token)
            SharedTagInvitePreviewResult.Success(
                displayName = preview.groupName ?: preview.tagName,
                inviteType = if (preview.inviteType.equals("group", ignoreCase = true)) {
                    jp.mimac.urlsaver.domain.SharedInviteType.GROUP
                } else {
                    jp.mimac.urlsaver.domain.SharedInviteType.TAG
                },
            )
        }.getOrElse { error ->
            val message = error.message.orEmpty()
            if (message.contains("invalid_invite", ignoreCase = true) ||
                message.contains("invalid_or_expired_invite", ignoreCase = true)
            ) {
                SharedTagInvitePreviewResult.InvalidInvite
            } else {
                SharedTagInvitePreviewResult.Failure(message.ifBlank { "招待リンクを確認できませんでした" })
            }
        }
    }

    override suspend fun acceptInvite(inviteToken: String): SharedTagInviteAcceptanceResult =
        withAccountOperation(blockedResult = { SharedTagInviteAcceptanceResult.AuthRequired }) {
            acceptInviteWithinFence(inviteToken)
        }

    private suspend fun acceptInviteWithinFence(inviteToken: String): SharedTagInviteAcceptanceResult {
        val session = currentSyncSessionOrNull() ?: return SharedTagInviteAcceptanceResult.AuthRequired
        val token = inviteToken.trim()
        if (token.isBlank()) return SharedTagInviteAcceptanceResult.InvalidInvite
        return runCatching {
            val accepted = remoteDataSource.acceptInvite(session, token)
            syncNowOrSchedule(session.authUserId)
            SharedTagInviteAcceptanceResult.Success(
                remoteId = accepted.groupId ?: accepted.tagId.orEmpty(),
                displayName = accepted.groupName ?: accepted.tagName.orEmpty(),
                inviteType = if (accepted.inviteType.equals("group", ignoreCase = true)) {
                    jp.mimac.urlsaver.domain.SharedInviteType.GROUP
                } else {
                    jp.mimac.urlsaver.domain.SharedInviteType.TAG
                },
            )
        }.getOrElse { error ->
            val message = error.message.orEmpty()
            if (message.contains("invalid_invite", ignoreCase = true) ||
                message.contains("invalid_or_expired_invite", ignoreCase = true)
            ) {
                SharedTagInviteAcceptanceResult.InvalidInvite
            } else {
                SharedTagInviteAcceptanceResult.Failure(message.ifBlank { "招待に参加できませんでした" })
            }
        }
    }

    override suspend fun transferOwnership(
        tagId: Long,
        newOwnerUserId: String,
    ): SharedTagOwnershipTransferResult =
        withAccountOperation(blockedResult = { SharedTagOwnershipTransferResult.AuthRequired }) {
            transferOwnershipWithinFence(tagId, newOwnerUserId)
        }

    private suspend fun transferOwnershipWithinFence(
        tagId: Long,
        newOwnerUserId: String,
    ): SharedTagOwnershipTransferResult {
        val session = currentSyncSessionOrNull() ?: return SharedTagOwnershipTransferResult.AuthRequired
        val targetUserId = newOwnerUserId.trim()
        if (targetUserId.isBlank() || targetUserId == session.authUserId) {
            return SharedTagOwnershipTransferResult.InvalidTarget
        }
        val tag = tagDao.findTagById(tagId) ?: return SharedTagOwnershipTransferResult.InvalidTarget
        val remoteTagId = tag.remoteTagId ?: return SharedTagOwnershipTransferResult.InvalidTarget
        if (tag.scope != SharedTagScope.SYNCED) return SharedTagOwnershipTransferResult.InvalidTarget

        val currentMember = tagDao.findCurrentUserMember(tagId, session.authUserId)
        if (currentMember?.role != SharedTagMemberRole.OWNER) {
            return SharedTagOwnershipTransferResult.OwnerOnly
        }

        val targetMember = tagDao.findMember(tagId, session.authUserId, targetUserId)
            ?: return SharedTagOwnershipTransferResult.InvalidTarget
        if (targetMember.status != SharedTagMemberStatus.ACTIVE ||
            targetMember.role == SharedTagMemberRole.OWNER
        ) {
            return SharedTagOwnershipTransferResult.InvalidTarget
        }

        return runCatching {
            remoteDataSource.transferOwnership(
                session = session,
                remoteTagId = remoteTagId,
                newOwnerUserId = targetUserId,
            )
            syncNowOrSchedule(session.authUserId)
            SharedTagOwnershipTransferResult.Success
        }.getOrElse { error ->
            val message = error.message.orEmpty()
            when {
                message.contains("auth_required", ignoreCase = true) ->
                    SharedTagOwnershipTransferResult.AuthRequired
                message.contains("forbidden", ignoreCase = true) ->
                    SharedTagOwnershipTransferResult.OwnerOnly
                message.contains("invalid_new_owner", ignoreCase = true) ||
                    message.contains("target_member_not_found", ignoreCase = true) ||
                    message.contains("target_member_not_active", ignoreCase = true) ||
                    message.contains("target_already_owner", ignoreCase = true) ->
                    SharedTagOwnershipTransferResult.InvalidTarget
                else -> SharedTagOwnershipTransferResult.Failure(
                    message.ifBlank { "オーナー権限を移譲できませんでした" },
                )
            }
        }
    }

    override suspend fun removeMember(tagId: Long, userId: String): Boolean =
        withAccountOperation(blockedResult = { false }) {
            removeMemberWithinFence(tagId, userId)
        }

    private suspend fun removeMemberWithinFence(tagId: Long, userId: String): Boolean {
        val session = currentSyncSessionOrNull() ?: return false
        if (userId == session.authUserId) return false
        val tag = tagDao.findTagById(tagId) ?: return false
        val remoteTagId = tag.remoteTagId ?: return false
        if (tag.scope != SharedTagScope.SYNCED) return false

        val currentMember = tagDao.findCurrentUserMember(tagId, session.authUserId)
        if (currentMember?.role != SharedTagMemberRole.OWNER) return false

        val targetMember = tagDao.findMember(tagId, session.authUserId, userId) ?: return false
        if (targetMember.role == SharedTagMemberRole.OWNER) return false
        if (targetMember.status != SharedTagMemberStatus.ACTIVE) return false

        val now = clock.nowEpochMillis()
        val clientId = syncCoordinator.ensureClientId(session.authUserId)
        database.withTransaction {
            syncDao.upsertMembers(
                listOf(
                    targetMember.copy(
                        status = SharedTagMemberStatus.REMOVED,
                        updatedAt = now,
                    ),
                ),
            )
            tagDao.upsertTags(
                listOf(
                    tag.copy(
                        syncStatus = SharedTagSyncStatus.PENDING_PUSH,
                        syncErrorMessage = null,
                    ),
                ),
            )
            enqueueOutbox(
                session = session,
                operation = SharedTagSyncOperation(
                    opId = UUID.randomUUID().toString(),
                    clientId = clientId,
                    type = SharedTagSyncOperationType.REMOVE_MEMBER,
                    submittedAt = now,
                    tagId = remoteTagId,
                    userId = userId,
                ),
            )
        }
        scheduleSync(session.authUserId)
        return true
    }

    override suspend fun leaveSharedTag(tagId: Long): Boolean =
        withAccountOperation(blockedResult = { false }) {
            leaveSharedTagWithinFence(tagId)
        }

    private suspend fun leaveSharedTagWithinFence(tagId: Long): Boolean {
        val session = currentSyncSessionOrNull() ?: return false
        val tag = tagDao.findTagById(tagId) ?: return false
        val remoteTagId = tag.remoteTagId ?: return false
        if (tag.scope != SharedTagScope.SYNCED) return false

        val currentMember = tagDao.findCurrentUserMember(tagId, session.authUserId) ?: return false
        if (currentMember.role == SharedTagMemberRole.OWNER) return false
        if (currentMember.status != SharedTagMemberStatus.ACTIVE) return false

        val now = clock.nowEpochMillis()
        val clientId = syncCoordinator.ensureClientId(session.authUserId)
        database.withTransaction {
            syncDao.upsertMembers(
                listOf(
                    currentMember.copy(
                        status = SharedTagMemberStatus.REMOVED,
                        updatedAt = now,
                    ),
                ),
            )
            tagDao.upsertTags(
                listOf(
                    tag.copy(
                        deletedAt = now,
                        syncStatus = SharedTagSyncStatus.PENDING_PUSH,
                        syncErrorMessage = null,
                    ),
                ),
            )
            enqueueOutbox(
                session = session,
                operation = SharedTagSyncOperation(
                    opId = UUID.randomUUID().toString(),
                    clientId = clientId,
                    type = SharedTagSyncOperationType.REMOVE_MEMBER,
                    submittedAt = now,
                    tagId = remoteTagId,
                    userId = session.authUserId,
                ),
            )
        }
        scheduleSync(session.authUserId)
        return true
    }

    private suspend fun createLocalTag(name: String): CreateTagResult {
        return database.withTransaction {
            if (tagDao.findLocalTagByName(name) != null) {
                CreateTagResult.Duplicate
            } else {
                val insertedId = runCatching {
                    tagDao.insertTag(
                        TagEntity(
                            name = name,
                            createdAt = clock.nowEpochMillis(),
                        ),
                    )
                }.getOrNull()
                if (insertedId != null) {
                    CreateTagResult.Success(insertedId)
                } else {
                    CreateTagResult.Failed
                }
            }
        }
    }

    private suspend fun createSyncedTag(
        name: String,
        session: SharedTagAuthSession,
    ): CreateTagResult {
        val now = clock.nowEpochMillis()
        val remoteTagId = UUID.randomUUID().toString()
        val clientId = syncCoordinator.ensureClientId(session.authUserId)
        var createdTagId: Long? = null
        val result = database.withTransaction {
            if (
                tagDao.findLocalTagByName(name) != null ||
                tagDao.findActiveSyncedTagByName(session.authUserId, name) != null
            ) {
                CreateTagResult.Duplicate
            } else {
                val insertedId = runCatching {
                    tagDao.insertTag(
                        TagEntity(
                            name = name,
                            createdAt = now,
                            scope = SharedTagScope.SYNCED,
                            authUserId = session.authUserId,
                            remoteTagId = remoteTagId,
                            syncStatus = SharedTagSyncStatus.PENDING_PUSH,
                        ),
                    )
                }.getOrNull()
                if (insertedId == null) {
                    CreateTagResult.Failed
                } else {
                    createdTagId = insertedId
                    syncDao.upsertMembers(
                        listOf(
                            SharedTagMemberEntity(
                                tagId = insertedId,
                                authUserId = session.authUserId,
                                userId = session.authUserId,
                                role = SharedTagMemberRole.OWNER,
                                status = SharedTagMemberStatus.ACTIVE,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        ),
                    )
                    enqueueOutbox(
                        session = session,
                        operation = SharedTagSyncOperation(
                            opId = UUID.randomUUID().toString(),
                            clientId = clientId,
                            type = SharedTagSyncOperationType.CREATE_TAG,
                            submittedAt = now,
                            tagId = remoteTagId,
                            name = name,
                        ),
                    )
                    CreateTagResult.Success(insertedId)
                }
            }
        }
        if (createdTagId != null) {
            scheduleSync(session.authUserId)
        }
        return result
    }

    private suspend fun findOrCreateLocalTagId(name: String, now: Long): Long {
        tagDao.findLocalTagByName(name)?.let { return it.id }
        val insertedId = runCatching {
            tagDao.insertTag(
                TagEntity(
                    name = name,
                    createdAt = now,
                ),
            )
        }.getOrNull()
        if (insertedId != null) return insertedId
        return requireNotNull(tagDao.findLocalTagByName(name)?.id)
    }

    private suspend fun enqueueOutbox(
        session: SharedTagAuthSession,
        operation: SharedTagSyncOperation,
    ) {
        val now = clock.nowEpochMillis()
        syncDao.upsertOutbox(
            SharedTagSyncOutboxEntity(
                opId = operation.opId,
                clientId = operation.clientId,
                authUserId = session.authUserId,
                operationType = operation.type,
                payloadJson = json.encodeToString(operation),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun scheduleSync(authUserId: String) {
        syncScheduler?.enqueue(authUserId)
    }

    private suspend fun syncNowOrSchedule(authUserId: String): Boolean {
        val success = syncCoordinator.syncForAuthUserWithinAccountOperation(authUserId)
        if (!success) {
            scheduleSync(authUserId)
        }
        return success
    }

    private suspend fun <T> withAccountOperation(
        blockedResult: () -> T,
        operation: suspend () -> T,
    ): T = accountOperationFence.withAccountOperation(
        authUserId = { authSessionProvider.session.value?.authUserId },
        blockedResult = blockedResult,
        operation = operation,
    )

    private fun currentSyncSessionOrNull(): SharedTagAuthSession? {
        val session = authSessionProvider.session.value ?: return null
        return session.takeIf { remoteConfig.isConfigured }
    }

    private fun currentSessionForSyncedTag(tag: TagEntity): SharedTagAuthSession? {
        val session = authSessionProvider.session.value ?: return null
        return session.takeIf { current ->
            tag.authUserId == null || tag.authUserId == current.authUserId
        }
    }

    private suspend fun findExistingEntry(normalizedUrl: String): UrlEntryEntity? {
        urlEntryDao.findByNormalizedUrl(normalizedUrl)?.let { return it }
        val legacyHttpTwin = UrlRules.toLegacyHttpTwin(normalizedUrl) ?: return null
        return urlEntryDao.findByNormalizedUrl(legacyHttpTwin)
    }

    private fun normalizeImportedMemo(memo: String?): String {
        if (memo == null) return ""
        if (!UrlRules.isMemoLengthValid(memo)) return ""
        return UrlRules.normalizeMemo(memo)
    }

    private fun buildInviteUrl(inviteToken: String): String {
        val baseUrl = remoteConfig.normalizedInviteLinkBaseUrl
        if (baseUrl.isNotBlank()) {
            return "$baseUrl/invite/$inviteToken"
        }
        return "urlsaver://invite/$inviteToken"
    }

    private companion object {
        const val ACCOUNT_CLEANUP_IN_PROGRESS_MESSAGE =
            "アカウント削除後の端末内データ消去を完了してから、もう一度お試しください"
    }

    private suspend fun countProspectiveImportedEntries(payload: TagSharePayload): Int {
        var newEntryCount = 0
        val seenNormalizedUrls = linkedSetOf<String>()
        payload.urls.forEach { item ->
            val parsed = UrlRules.parseUrl(item.url) ?: return@forEach
            val normalizedUrl = parsed.normalizedUrl
            if (!seenNormalizedUrls.add(normalizedUrl)) {
                return@forEach
            }
            val existing = findExistingEntry(normalizedUrl)
            if (existing == null) {
                newEntryCount += 1
            }
        }
        return newEntryCount
    }
}
