package jp.mimac.urlsaver.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "ai_receipts",
    primaryKeys = ["receiptId"],
)
data class AiReceiptEntity(
    val receiptId: String,
    val actionKind: String,
    val destination: String,
    val generatedAtIso: String,
    val redactionProfile: String,
    val requestSizeBucket: String,
    val responseSizeBucket: String,
    val rawBodyIncluded: Boolean,
    val rawPromptIncluded: Boolean,
)

@Entity(
    tableName = "ai_receipt_sources",
    primaryKeys = ["receiptId", "publicSafeId"],
    indices = [
        Index(value = ["entryId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = AiReceiptEntity::class,
            parentColumns = ["receiptId"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UrlEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class AiReceiptSourceEntity(
    val receiptId: String,
    val publicSafeId: String,
    val entryId: Long?,
    val title: String,
    val normalizedUrl: String,
    val tagNamesJson: String,
    val sharedTagBoundary: String,
    val aiEligible: Boolean,
    val exclusionReasonsJson: String,
)

@Entity(
    tableName = "ai_drafts",
    primaryKeys = ["draftId"],
    indices = [
        Index(value = ["receiptId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = AiReceiptEntity::class,
            parentColumns = ["receiptId"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AiDraftEntity(
    val draftId: String,
    val receiptId: String,
    val generatedAtIso: String,
    val title: String,
    val body: String,
    val citedSourceIdsJson: String,
    val status: String,
)

@Entity(
    tableName = "ai_diff_proposals",
    primaryKeys = ["proposalId"],
    indices = [
        Index(value = ["draftId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = AiDraftEntity::class,
            parentColumns = ["draftId"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AiDiffProposalEntity(
    val proposalId: String,
    val draftId: String,
    val generatedAtIso: String,
    val operationsJson: String,
    val applied: Boolean,
)
