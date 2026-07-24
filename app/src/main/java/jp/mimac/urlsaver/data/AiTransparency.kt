package jp.mimac.urlsaver.data

import androidx.room.withTransaction
import jp.mimac.urlsaver.BuildConfig
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.UrlRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

enum class AiActionKind {
    EXPORT,
    MCP_SEARCH,
    MCP_FETCH,
    PERSONAL_LINK_SYNC,
}

enum class AiSharedTagBoundary {
    LOCAL_OR_UNTAGGED,
    CONTAINS_SHARED_TAG,
}

enum class AiDraftStatus {
    PROPOSED,
    ACCEPTED,
    REJECTED,
}

data class AiTransparencySource(
    val publicSafeId: String,
    val localEntryId: Long? = null,
    val title: String,
    val normalizedUrl: String,
    val tagNames: List<String>,
    val sharedTagBoundary: AiSharedTagBoundary,
    val aiEligible: Boolean,
    val exclusionReasons: List<String>,
)

data class AiSendPreview(
    val actionKind: AiActionKind,
    val destination: String,
    val sourceCount: Int,
    val eligibleCount: Int,
    val blockedCount: Int,
    val sources: List<AiTransparencySource>,
    val rawBodyIncluded: Boolean,
    val rawPromptIncluded: Boolean,
) {
    val canSend: Boolean = eligibleCount > 0 && !rawBodyIncluded && !rawPromptIncluded
}

data class AiSendReceipt(
    val receiptId: String,
    val actionKind: AiActionKind,
    val destination: String,
    val generatedAtIso: String,
    val sentSourceIds: List<String>,
    val blockedSourceIds: List<String>,
    val redactionProfile: String,
    val requestSizeBucket: AiSizeBucket,
    val responseSizeBucket: AiSizeBucket,
    val rawBodyIncluded: Boolean,
    val rawPromptIncluded: Boolean,
)

data class AiDraft(
    val draftId: String,
    val receiptId: String,
    val generatedAtIso: String,
    val title: String,
    val body: String,
    val citedSourceIds: List<String>,
    val status: AiDraftStatus = AiDraftStatus.PROPOSED,
)

data class AiDiffProposal(
    val proposalId: String,
    val draftId: String,
    val generatedAtIso: String,
    val operations: List<AiDiffOperation>,
    val applied: Boolean = false,
)

@Serializable
data class AiDiffOperation(
    val targetPublicSafeId: String,
    val field: String,
    val before: String?,
    val after: String?,
)

enum class AiSizeBucket {
    ZERO,
    TINY,
    SMALL,
    MEDIUM,
    LARGE,
    HUGE,
}

data class AiProviderResult(
    val title: String,
    val body: String,
    val responseBytes: Int?,
    val citedSourceIds: List<String>,
)

interface AiProvider {
    suspend fun generateDraft(preview: AiSendPreview): AiProviderResult
}

object AiTransparencyFeature {
    val isEnabled: Boolean
        get() = BuildConfig.AI_TRANSPARENCY_ENABLED
}

class MockAiProvider : AiProvider {
    override suspend fun generateDraft(preview: AiSendPreview): AiProviderResult {
        val ids = preview.sources.filter { it.aiEligible }.map { it.publicSafeId }.sorted()
        val title = if (ids.isEmpty()) "AI下書き候補なし" else "AI下書き候補 ${ids.size}件"
        val body = buildString {
            appendLine("deterministic-mock-provider")
            ids.forEach { appendLine("- $it") }
        }.trimEnd()
        return AiProviderResult(
            title = title,
            body = body,
            responseBytes = body.toByteArray(Charsets.UTF_8).size,
            citedSourceIds = ids,
        )
    }
}

data class ExternalDataPolicyResult(
    val allowed: Boolean,
    val reasons: List<String>,
)

/** Shared fail-closed corpus for data that may leave the device. */
object ExternalDataPolicy {
    private val suspiciousQueryKey = Regex(
        "(?i)(token|access_token|refresh_token|code|state|nonce|signature|sig|expires|expiration|" +
            "password|passwd|secret|api[_-]?key|auth|credential|invite)",
    )
    private val labeledSecret = Regex(
        "(?i)(refresh_token|access_token|service_role|sb_secret|invite[_-]?token|password|secret|api[_-]?key|token)" +
            "\\s*[:=]\\s*[\\\"']?[A-Za-z0-9._~+/=-]{8,}",
    )
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{8,}")
    private val knownKeyPrefix = Regex("(?i)(sk-[A-Za-z0-9]{16,}|gh[pousr]_[A-Za-z0-9]{16,}|glpat-[A-Za-z0-9_-]{16,})")
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val phone = Regex("(?<!\\d)(?:\\+?\\d[\\d\\s().-]{8,}\\d)(?!\\d)")
    private val localPath = Regex("(?:/Users/|/private/var/|file://)[^\\s)]+")

    fun inspect(
        url: String?,
        title: String? = null,
        memo: String? = null,
        tags: List<String> = emptyList(),
        metadata: List<String?> = emptyList(),
    ): ExternalDataPolicyResult {
        val reasons = linkedSetOf<String>()
        val parsed = url?.let { runCatching { URI(it) }.getOrNull() }
        if (url.isNullOrBlank() || parsed == null || parsed.userInfo != null || parsed.host.isNullOrBlank()) {
            if (parsed?.userInfo != null) reasons += "url_userinfo"
            else if (!url.isNullOrBlank()) reasons += "url_parse_failed"
        } else {
            if (parsed.scheme?.lowercase() !in setOf("http", "https")) reasons += "url_scheme"
            val queryItems = parsed.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }
            queryItems.forEach { item ->
                val decodedItem = runCatching {
                    URLDecoder.decode(item, Charsets.UTF_8.name())
                }.getOrDefault(item)
                val key = decodedItem.substringBefore('=', decodedItem).lowercase()
                val value = decodedItem.substringAfter('=', "")
                if (suspiciousQueryKey.containsMatchIn(key)) reasons += "sensitive_query_key"
                if (value.length >= 40 && highEntropy(value)) reasons += "high_entropy_query_value"
            }
            val decodedUrl = runCatching {
                URLDecoder.decode(url, Charsets.UTF_8.name())
            }.getOrDefault(url)
            if (labeledSecret.containsMatchIn(decodedUrl) || jwt.containsMatchIn(decodedUrl) || knownKeyPrefix.containsMatchIn(decodedUrl)) {
                reasons += "sensitive_url_value"
            }
            if (email.containsMatchIn(decodedUrl)) reasons += "email"
            if (localPath.containsMatchIn(decodedUrl)) reasons += "local_path"
        }
        listOf(title, memo, *tags.toTypedArray(), *metadata.toTypedArray()).forEach { value ->
            if (value.isNullOrBlank()) return@forEach
            if (labeledSecret.containsMatchIn(value) || jwt.containsMatchIn(value) || knownKeyPrefix.containsMatchIn(value)) {
                reasons += "sensitive_text"
            }
            if (email.containsMatchIn(value)) reasons += "email"
            if (phone.containsMatchIn(value)) reasons += "phone"
            if (localPath.containsMatchIn(value)) reasons += "local_path"
        }
        return ExternalDataPolicyResult(reasons.isEmpty(), reasons.toList().sorted())
    }

    fun safeUrl(value: String): String {
        return if (inspect(value).allowed) value else "[excluded:sensitive_external_data]"
    }

    fun sanitizeText(value: String?): String? {
        val input = value?.takeIf { it.isNotBlank() } ?: return null
        var output = input
        output = labeledSecret.replace(output, "[redacted:token]")
        output = jwt.replace(output, "[redacted:token]")
        output = knownKeyPrefix.replace(output, "[redacted:key]")
        output = email.replace(output, "[redacted:email]")
        output = phone.replace(output, "[redacted:phone]")
        output = localPath.replace(output, "[redacted:local_path]")
        return output
    }

    private fun highEntropy(value: String): Boolean {
        val classes = listOf(
            value.any(Char::isLowerCase),
            value.any(Char::isUpperCase),
            value.any(Char::isDigit),
            value.any { it in "-_+/=" },
        ).count { it }
        return classes >= 3 && value.toSet().size >= 12
    }
}

object AiTransparencyPolicy {
    const val SAVED_SNAPSHOT_NOTICE = "保存時点の情報であり、現在の内容とは異なる可能性があります"

    fun buildPreview(
        actionKind: AiActionKind,
        destination: String,
        sources: List<AiTransparencySource>,
        rawBodyIncluded: Boolean = false,
        rawPromptIncluded: Boolean = false,
    ): AiSendPreview {
        val normalizedSources = sources.map { source ->
            source.copy(
                tagNames = source.tagNames.distinct().sorted(),
                exclusionReasons = source.exclusionReasons.distinct().sorted(),
            )
        }
        val eligibleCount = normalizedSources.count { it.aiEligible }
        return AiSendPreview(
            actionKind = actionKind,
            destination = destination,
            sourceCount = normalizedSources.size,
            eligibleCount = eligibleCount,
            blockedCount = normalizedSources.size - eligibleCount,
            sources = normalizedSources,
            rawBodyIncluded = rawBodyIncluded,
            rawPromptIncluded = rawPromptIncluded,
        )
    }

    fun buildReceipt(
        preview: AiSendPreview,
        generatedAtIso: String,
        redactionProfile: String = "ai-safe-v1",
        requestBytes: Int? = null,
        responseBytes: Int? = null,
    ): AiSendReceipt {
        val sentSourceIds = preview.sources
            .filter { it.aiEligible }
            .map { it.publicSafeId }
            .sorted()
        val blockedSourceIds = preview.sources
            .filterNot { it.aiEligible }
            .map { it.publicSafeId }
            .sorted()
        return AiSendReceipt(
            receiptId = stableId("receipt", preview.actionKind.name, preview.destination, generatedAtIso, sentSourceIds),
            actionKind = preview.actionKind,
            destination = preview.destination,
            generatedAtIso = generatedAtIso,
            sentSourceIds = sentSourceIds,
            blockedSourceIds = blockedSourceIds,
            redactionProfile = redactionProfile,
            requestSizeBucket = sizeBucket(requestBytes),
            responseSizeBucket = sizeBucket(responseBytes),
            rawBodyIncluded = preview.rawBodyIncluded,
            rawPromptIncluded = preview.rawPromptIncluded,
        )
    }

    fun buildDraft(
        receipt: AiSendReceipt,
        generatedAtIso: String,
        title: String,
        body: String,
        citedSourceIds: List<String>,
    ): AiDraft {
        val allowedCitations = citedSourceIds
            .filter { it in receipt.sentSourceIds }
            .distinct()
            .sorted()
        return AiDraft(
            draftId = stableId("draft", receipt.receiptId, generatedAtIso, title, allowedCitations),
            receiptId = receipt.receiptId,
            generatedAtIso = generatedAtIso,
            title = title,
            body = body,
            citedSourceIds = allowedCitations,
        )
    }

    fun buildDiffProposal(
        draft: AiDraft,
        generatedAtIso: String,
        operations: List<AiDiffOperation>,
    ): AiDiffProposal {
        val normalizedOperations = operations.sortedWith(
            compareBy<AiDiffOperation> { it.targetPublicSafeId }.thenBy { it.field },
        )
        return AiDiffProposal(
            proposalId = stableId("diff", draft.draftId, generatedAtIso, normalizedOperations),
            draftId = draft.draftId,
            generatedAtIso = generatedAtIso,
            operations = normalizedOperations,
            applied = false,
        )
    }

    fun sourceForEntry(
        entry: UrlEntryEntity,
        publicSafeId: String,
        tagNames: List<String> = emptyList(),
        containsSharedTag: Boolean = entry.sharedReferenceCount > 0,
    ): AiTransparencySource {
        val reasons = buildList {
            if (containsSharedTag) add("shared_tag_default_excluded")
            if (entry.localProvenanceCount <= 0) add("no_local_provenance")
            if (entry.recordState == RecordState.ARCHIVED) add("archived_default_excluded")
            if (entry.recordState == RecordState.PENDING_DELETE) add("pending_delete_excluded")
            addAll(
                ExternalDataPolicy.inspect(
                    url = entry.normalizedUrl,
                    title = entry.userTitle ?: entry.fetchedTitle,
                    memo = entry.memo,
                    tags = tagNames,
                ).reasons.map { "external_data_$it" },
            )
        }.distinct().sorted()
        return AiTransparencySource(
            publicSafeId = publicSafeId,
            localEntryId = entry.id.takeIf { it > 0 },
            title = ExternalDataPolicy.sanitizeText(entry.userTitle ?: entry.fetchedTitle ?: entry.normalizedHost)
                ?: entry.normalizedHost,
            normalizedUrl = ExternalDataPolicy.safeUrl(entry.normalizedUrl),
            tagNames = tagNames,
            sharedTagBoundary = if (containsSharedTag) {
                AiSharedTagBoundary.CONTAINS_SHARED_TAG
            } else {
                AiSharedTagBoundary.LOCAL_OR_UNTAGGED
            },
            aiEligible = reasons.isEmpty(),
            exclusionReasons = reasons,
        )
    }

    fun publicSafeIdForEntry(entry: UrlEntryEntity): String {
        return stableId("entry", entry.normalizedUrl)
    }

    fun sizeBucket(bytes: Int?): AiSizeBucket {
        val value = bytes ?: return AiSizeBucket.ZERO
        return when {
            value <= 0 -> AiSizeBucket.ZERO
            value <= 1_024 -> AiSizeBucket.TINY
            value <= 16_384 -> AiSizeBucket.SMALL
            value <= 131_072 -> AiSizeBucket.MEDIUM
            value <= 1_048_576 -> AiSizeBucket.LARGE
            else -> AiSizeBucket.HUGE
        }
    }

    private fun stableId(prefix: String, vararg parts: Any?): String {
        val input = parts.joinToString(separator = "\u001f") { it.toString() }
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return "$prefix-" + digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}

fun interface AiLocalDataClearer {
    suspend fun clearLocalAiData()
}

class AiTransparencyRepository(
    private val database: AppDatabase,
    private val aiTransparencyDao: AiTransparencyDao,
    private val urlEntryDao: UrlEntryDao,
    private val tagDao: TagDao,
    private val provider: AiProvider = MockAiProvider(),
    private val nowIso: () -> String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AiLocalDataClearer {
    suspend fun buildPreview(): AiSendPreview {
        val sources = urlEntryDao.loadAllEntries().map { entry ->
            val localTags = tagDao.getLocalOnlyTagsForEntry(entry.id).map { it.name }
            val hasSharedTagAllocation = tagDao.countActiveSyncedRefsForEntry(entry.id) > 0
            AiTransparencyPolicy.sourceForEntry(
                entry = entry,
                publicSafeId = AiTransparencyPolicy.publicSafeIdForEntry(entry),
                tagNames = localTags,
                containsSharedTag = entry.sharedReferenceCount > 0 || hasSharedTagAllocation,
            )
        }
        return AiTransparencyPolicy.buildPreview(
            actionKind = AiActionKind.PERSONAL_LINK_SYNC,
            destination = "端末内モック",
            sources = sources,
        )
    }

    suspend fun saveReceipt(
        preview: AiSendPreview,
        requestBytes: Int? = null,
        responseBytes: Int? = null,
        redactionProfile: String = "ai-safe-v1",
    ): AiSendReceipt {
        require(!preview.rawBodyIncluded) { "raw fetchedBody must not be sent or recorded in AI receipts" }
        require(!preview.rawPromptIncluded) { "raw prompt must not be sent or recorded in AI receipts" }
        val receipt = AiTransparencyPolicy.buildReceipt(
            preview = preview,
            generatedAtIso = nowIso(),
            redactionProfile = redactionProfile,
            requestBytes = requestBytes,
            responseBytes = responseBytes,
        )
        aiTransparencyDao.upsertReceipt(receipt.toEntity())
        aiTransparencyDao.upsertReceiptSources(preview.sources.map { it.toSourceEntity(receipt.receiptId) })
        return receipt
    }

    suspend fun generateDraftWithFallback(preview: AiSendPreview, receipt: AiSendReceipt): AiDraft {
        val result = provider.generateDraft(preview)
        return saveDraft(
            receipt = receipt,
            title = result.title,
            body = result.body,
            citedSourceIds = result.citedSourceIds,
        )
    }

    suspend fun saveDraft(
        receipt: AiSendReceipt,
        title: String,
        body: String,
        citedSourceIds: List<String>,
    ): AiDraft {
        val draft = AiTransparencyPolicy.buildDraft(
            receipt = receipt,
            generatedAtIso = nowIso(),
            title = title,
            body = body,
            citedSourceIds = citedSourceIds,
        )
        aiTransparencyDao.upsertDraft(draft.toEntity())
        return draft
    }

    suspend fun saveDiffProposal(draft: AiDraft, operations: List<AiDiffOperation>): AiDiffProposal {
        val proposal = AiTransparencyPolicy.buildDiffProposal(
            draft = draft,
            generatedAtIso = nowIso(),
            operations = operations,
        )
        aiTransparencyDao.upsertDiffProposal(proposal.toEntity())
        return proposal
    }

    suspend fun createLocalMockMemoDiff(
        preview: AiSendPreview,
        draft: AiDraft,
    ): AiDiffProposal {
        val source = preview.sources.firstOrNull { it.aiEligible && it.localEntryId != null }
        val entry = source?.localEntryId?.let { urlEntryDao.findById(it) }
        val operations = if (source != null && entry != null) {
            listOf(
                AiDiffOperation(
                    targetPublicSafeId = source.publicSafeId,
                    field = "memo",
                    before = entry.memo,
                    after = draft.body.take(2_000),
                ),
            )
        } else {
            emptyList()
        }
        return saveDiffProposal(draft, operations)
    }

    suspend fun loadReceipt(receiptId: String): AiSendReceipt? {
        val entity = aiTransparencyDao.findReceipt(receiptId) ?: return null
        val sources = aiTransparencyDao.findSourcesForReceipt(receiptId)
        return entity.toModel(
            sentSourceIds = sources.filter { it.aiEligible }.map { it.publicSafeId }.sorted(),
            blockedSourceIds = sources.filterNot { it.aiEligible }.map { it.publicSafeId }.sorted(),
        )
    }

    suspend fun loadDraft(draftId: String): AiDraft? = aiTransparencyDao.findDraft(draftId)?.toModel()

    suspend fun loadDiffProposal(proposalId: String): AiDiffProposal? =
        aiTransparencyDao.findDiffProposal(proposalId)?.toModel()

    suspend fun applyDiffProposal(proposalId: String, confirm: Boolean): Boolean {
        if (!confirm) return false
        return database.withTransaction {
            val proposalEntity = aiTransparencyDao.findDiffProposal(proposalId) ?: return@withTransaction false
            val proposal = proposalEntity.toModel()
            if (proposal.applied || proposal.operations.isEmpty()) return@withTransaction false
            if (proposal.operations.any { it.field !in ALLOWED_DIFF_FIELDS }) return@withTransaction false
            if (proposal.operations.distinctBy { it.targetPublicSafeId to it.field }.size != proposal.operations.size) {
                return@withTransaction false
            }
            val draft = aiTransparencyDao.findDraft(proposal.draftId) ?: return@withTransaction false
            val updates = proposal.operations.map { operation ->
                val source = aiTransparencyDao.findSource(draft.receiptId, operation.targetPublicSafeId)
                    ?: return@withTransaction false
                val entryId = source.entryId ?: return@withTransaction false
                val entry = urlEntryDao.findById(entryId) ?: return@withTransaction false
                if (
                    entry.recordState != RecordState.ACTIVE ||
                    entry.localProvenanceCount <= 0 ||
                    entry.sharedReferenceCount != 0 ||
                    entry.pendingDeletionUntil != null ||
                    tagDao.countActiveSyncedRefsForEntry(entry.id) > 0
                ) {
                    return@withTransaction false
                }
                val currentValue = when (operation.field) {
                    "userTitle" -> entry.userTitle
                    "memo" -> entry.memo
                    else -> return@withTransaction false
                }
                if (currentValue != operation.before) return@withTransaction false
                val normalizedAfter = when (operation.field) {
                    "userTitle" -> {
                        if (!UrlRules.isTitleLengthValid(operation.after.orEmpty())) return@withTransaction false
                        UrlRules.normalizeUserTitle(operation.after)
                    }
                    "memo" -> {
                        if (!UrlRules.isMemoLengthValid(operation.after.orEmpty())) return@withTransaction false
                        UrlRules.normalizeMemo(operation.after)
                    }
                    else -> return@withTransaction false
                }
                entry to (operation.field to normalizedAfter)
            }
            val updatedAt = nowMillis()
            val updatedEntries = linkedMapOf<Long, UrlEntryEntity>()
            updates.forEach { (entry, change) ->
                val current = updatedEntries[entry.id] ?: entry
                val updated = when (change.first) {
                    "userTitle" -> current.copy(userTitle = change.second, updatedAt = updatedAt)
                    "memo" -> current.copy(memo = change.second.orEmpty(), updatedAt = updatedAt)
                    else -> return@withTransaction false
                }
                updatedEntries[entry.id] = updated
            }
            updatedEntries.values.forEach { updated ->
                urlEntryDao.update(updated)
            }
            aiTransparencyDao.updateDiffProposal(proposalEntity.copy(applied = true))
            aiTransparencyDao.updateDraft(draft.copy(status = AiDraftStatus.ACCEPTED.name))
            true
        }
    }

    override suspend fun clearLocalAiData() {
        aiTransparencyDao.deleteAllLocalAiData()
    }

    companion object {
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        private val ALLOWED_DIFF_FIELDS = setOf("userTitle", "memo")
    }

    private fun AiSendReceipt.toEntity() = AiReceiptEntity(
        receiptId = receiptId,
        actionKind = actionKind.name,
        destination = destination,
        generatedAtIso = generatedAtIso,
        redactionProfile = redactionProfile,
        requestSizeBucket = requestSizeBucket.name,
        responseSizeBucket = responseSizeBucket.name,
        rawBodyIncluded = rawBodyIncluded,
        rawPromptIncluded = rawPromptIncluded,
    )

    private fun AiReceiptEntity.toModel(
        sentSourceIds: List<String>,
        blockedSourceIds: List<String>,
    ) = AiSendReceipt(
        receiptId = receiptId,
        actionKind = AiActionKind.valueOf(actionKind),
        destination = destination,
        generatedAtIso = generatedAtIso,
        sentSourceIds = sentSourceIds,
        blockedSourceIds = blockedSourceIds,
        redactionProfile = redactionProfile,
        requestSizeBucket = AiSizeBucket.valueOf(requestSizeBucket),
        responseSizeBucket = AiSizeBucket.valueOf(responseSizeBucket),
        rawBodyIncluded = rawBodyIncluded,
        rawPromptIncluded = rawPromptIncluded,
    )

    private fun AiTransparencySource.toSourceEntity(receiptId: String) = AiReceiptSourceEntity(
        receiptId = receiptId,
        publicSafeId = publicSafeId,
        entryId = localEntryId,
        title = title,
        normalizedUrl = "",
        tagNamesJson = json.encodeToString(tagNames.distinct().sorted()),
        sharedTagBoundary = sharedTagBoundary.name,
        aiEligible = aiEligible,
        exclusionReasonsJson = json.encodeToString(exclusionReasons.distinct().sorted()),
    )

    private fun AiDraft.toEntity() = AiDraftEntity(
        draftId = draftId,
        receiptId = receiptId,
        generatedAtIso = generatedAtIso,
        title = title,
        body = body,
        citedSourceIdsJson = json.encodeToString(citedSourceIds.distinct().sorted()),
        status = status.name,
    )

    private fun AiDraftEntity.toModel() = AiDraft(
        draftId = draftId,
        receiptId = receiptId,
        generatedAtIso = generatedAtIso,
        title = title,
        body = body,
        citedSourceIds = json.decodeFromString(citedSourceIdsJson),
        status = AiDraftStatus.valueOf(status),
    )

    private fun AiDiffProposal.toEntity() = AiDiffProposalEntity(
        proposalId = proposalId,
        draftId = draftId,
        generatedAtIso = generatedAtIso,
        operationsJson = json.encodeToString(operations),
        applied = applied,
    )

    private fun AiDiffProposalEntity.toModel() = AiDiffProposal(
        proposalId = proposalId,
        draftId = draftId,
        generatedAtIso = generatedAtIso,
        operations = json.decodeFromString(operationsJson),
        applied = applied,
    )

}
