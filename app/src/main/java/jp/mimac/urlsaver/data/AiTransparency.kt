package jp.mimac.urlsaver.data

import jp.mimac.urlsaver.BuildConfig
import jp.mimac.urlsaver.domain.RecordState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
            if (entry.recordState == RecordState.ARCHIVED) add("archived_default_excluded")
            if (entry.recordState == RecordState.PENDING_DELETE) add("pending_delete_excluded")
        }.distinct().sorted()
        return AiTransparencySource(
            publicSafeId = publicSafeId,
            localEntryId = entry.id.takeIf { it > 0 },
            title = entry.userTitle ?: entry.fetchedTitle ?: entry.normalizedHost,
            normalizedUrl = entry.normalizedUrl,
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

class AiTransparencyRepository(
    private val aiTransparencyDao: AiTransparencyDao,
    private val urlEntryDao: UrlEntryDao,
    private val provider: AiProvider = MockAiProvider(),
    private val nowIso: () -> String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
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
        val proposalEntity = aiTransparencyDao.findDiffProposal(proposalId) ?: return false
        val proposal = proposalEntity.toModel()
        if (!confirm || proposal.applied) return false
        val draft = aiTransparencyDao.findDraft(proposal.draftId) ?: return false
        var changed = false
        for (operation in proposal.operations) {
            if (operation.field !in setOf("userTitle", "memo")) continue
            val source = aiTransparencyDao.findSource(draft.receiptId, operation.targetPublicSafeId) ?: continue
            val entryId = source.entryId ?: continue
            val entry = urlEntryDao.findById(entryId) ?: continue
            val updated = when (operation.field) {
                "userTitle" -> entry.copy(userTitle = operation.after?.takeIf { it.isNotBlank() }, updatedAt = nowMillis())
                "memo" -> entry.copy(memo = operation.after.orEmpty(), updatedAt = nowMillis())
                else -> entry
            }
            urlEntryDao.update(updated)
            changed = true
        }
        if (changed) {
            aiTransparencyDao.updateDiffProposal(proposalEntity.copy(applied = true))
            aiTransparencyDao.updateDraft(draft.copy(status = AiDraftStatus.ACCEPTED.name))
        }
        return changed
    }

    suspend fun clearLocalAiData() {
        aiTransparencyDao.deleteAllLocalAiData()
    }

    companion object {
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
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
        normalizedUrl = normalizedUrl,
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
