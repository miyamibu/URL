package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.AiActionKind
import jp.mimac.urlsaver.data.AiDiffOperation
import jp.mimac.urlsaver.data.AiSharedTagBoundary
import jp.mimac.urlsaver.data.AiTransparencyPolicy
import jp.mimac.urlsaver.data.AiTransparencySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTransparencyPolicyTest {
    @Test
    fun previewBlocksRawBodyOrRawPromptEvenWhenSourcesAreEligible() {
        val preview = AiTransparencyPolicy.buildPreview(
            actionKind = AiActionKind.EXPORT,
            destination = "share-sheet",
            sources = listOf(localSource("safe-1")),
            rawBodyIncluded = true,
        )

        assertEquals(1, preview.eligibleCount)
        assertFalse(preview.canSend)
    }

    @Test
    fun receiptSeparatesSentAndBlockedSources() {
        val preview = AiTransparencyPolicy.buildPreview(
            actionKind = AiActionKind.MCP_SEARCH,
            destination = "chatgpt",
            sources = listOf(
                localSource("safe-1"),
                sharedSource("blocked-1"),
            ),
        )

        val receipt = AiTransparencyPolicy.buildReceipt(
            preview = preview,
            generatedAtIso = "2026-07-09T00:00:00Z",
        )

        assertTrue(preview.canSend)
        assertEquals(listOf("safe-1"), receipt.sentSourceIds)
        assertEquals(listOf("blocked-1"), receipt.blockedSourceIds)
        assertFalse(receipt.rawBodyIncluded)
        assertFalse(receipt.rawPromptIncluded)
    }

    @Test
    fun draftCanOnlyCiteSourcesThatWereActuallySent() {
        val receipt = AiTransparencyPolicy.buildReceipt(
            preview = AiTransparencyPolicy.buildPreview(
                actionKind = AiActionKind.MCP_FETCH,
                destination = "chatgpt",
                sources = listOf(localSource("safe-1"), sharedSource("blocked-1")),
            ),
            generatedAtIso = "2026-07-09T00:00:00Z",
        )

        val draft = AiTransparencyPolicy.buildDraft(
            receipt = receipt,
            generatedAtIso = "2026-07-09T00:01:00Z",
            title = "候補タイトル",
            body = "候補本文",
            citedSourceIds = listOf("blocked-1", "safe-1", "safe-1"),
        )

        assertEquals(listOf("safe-1"), draft.citedSourceIds)
    }

    @Test
    fun diffProposalIsNotAppliedByDefault() {
        val receipt = AiTransparencyPolicy.buildReceipt(
            preview = AiTransparencyPolicy.buildPreview(
                actionKind = AiActionKind.EXPORT,
                destination = "share-sheet",
                sources = listOf(localSource("safe-1")),
            ),
            generatedAtIso = "2026-07-09T00:00:00Z",
        )
        val draft = AiTransparencyPolicy.buildDraft(
            receipt = receipt,
            generatedAtIso = "2026-07-09T00:01:00Z",
            title = "候補",
            body = "本文",
            citedSourceIds = listOf("safe-1"),
        )

        val proposal = AiTransparencyPolicy.buildDiffProposal(
            draft = draft,
            generatedAtIso = "2026-07-09T00:02:00Z",
            operations = listOf(
                AiDiffOperation(
                    targetPublicSafeId = "safe-1",
                    field = "memo",
                    before = "old",
                    after = "new",
                ),
            ),
        )

        assertFalse(proposal.applied)
        assertEquals("memo", proposal.operations.single().field)
    }

    private fun localSource(id: String) = AiTransparencySource(
        publicSafeId = id,
        title = "local",
        normalizedUrl = "https://example.com/$id",
        tagNames = listOf("b", "a", "a"),
        sharedTagBoundary = AiSharedTagBoundary.LOCAL_OR_UNTAGGED,
        aiEligible = true,
        exclusionReasons = emptyList(),
    )

    private fun sharedSource(id: String) = AiTransparencySource(
        publicSafeId = id,
        title = "shared",
        normalizedUrl = "https://example.com/$id",
        tagNames = listOf("shared"),
        sharedTagBoundary = AiSharedTagBoundary.CONTAINS_SHARED_TAG,
        aiEligible = false,
        exclusionReasons = listOf("shared_tag_default_excluded"),
    )
}
