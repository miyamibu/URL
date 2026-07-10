package jp.mimac.urlsaver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface AiTransparencyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceipt(receipt: AiReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceiptSources(sources: List<AiReceiptSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraft(draft: AiDraftEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiffProposal(proposal: AiDiffProposalEntity)

    @Query("SELECT * FROM ai_receipts WHERE receiptId = :receiptId")
    suspend fun findReceipt(receiptId: String): AiReceiptEntity?

    @Query("SELECT * FROM ai_receipt_sources WHERE receiptId = :receiptId ORDER BY publicSafeId ASC")
    suspend fun findSourcesForReceipt(receiptId: String): List<AiReceiptSourceEntity>

    @Query("SELECT * FROM ai_receipt_sources WHERE receiptId = :receiptId AND publicSafeId = :publicSafeId")
    suspend fun findSource(receiptId: String, publicSafeId: String): AiReceiptSourceEntity?

    @Query("SELECT * FROM ai_drafts WHERE draftId = :draftId")
    suspend fun findDraft(draftId: String): AiDraftEntity?

    @Query("SELECT * FROM ai_drafts WHERE receiptId = :receiptId ORDER BY generatedAtIso DESC")
    suspend fun findDraftsForReceipt(receiptId: String): List<AiDraftEntity>

    @Query("SELECT * FROM ai_diff_proposals WHERE proposalId = :proposalId")
    suspend fun findDiffProposal(proposalId: String): AiDiffProposalEntity?

    @Query("SELECT * FROM ai_diff_proposals WHERE draftId = :draftId ORDER BY generatedAtIso DESC")
    suspend fun findDiffProposalsForDraft(draftId: String): List<AiDiffProposalEntity>

    @Update
    suspend fun updateDraft(draft: AiDraftEntity)

    @Update
    suspend fun updateDiffProposal(proposal: AiDiffProposalEntity)

    @Query("DELETE FROM ai_diff_proposals")
    suspend fun deleteDiffProposals()

    @Query("DELETE FROM ai_drafts")
    suspend fun deleteDrafts()

    @Query("DELETE FROM ai_receipts")
    suspend fun deleteReceipts()

    @Transaction
    suspend fun deleteAllLocalAiData() {
        deleteDiffProposals()
        deleteDrafts()
        deleteReceipts()
    }
}
