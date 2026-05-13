package jp.mimac.urlsaver.ui

import jp.mimac.urlsaver.data.CollectionEntity
import jp.mimac.urlsaver.domain.SharedTagRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailTagUiStateTest {
    @Test
    fun sharedTagCloudEntryPoints_showWhenCloudConfigured() {
        assertTrue(
            shouldShowSharedTagCloudEntryPoints(
                isConfigured = true,
                hasSharedTags = false,
            ),
        )
    }

    @Test
    fun sharedTagCloudEntryPoints_keepCachedSharedTagsVisibleWhenConfigMissing() {
        assertTrue(
            shouldShowSharedTagCloudEntryPoints(
                isConfigured = false,
                hasSharedTags = true,
            ),
        )
    }

    @Test
    fun sharedTagCloudEntryPoints_keepPendingInviteVisibleWhenConfigMissing() {
        assertTrue(
            shouldShowSharedTagCloudEntryPoints(
                isConfigured = false,
                hasSharedTags = false,
                hasPendingInvite = true,
            ),
        )
    }

    @Test
    fun sharedTagCloudEntryPoints_hideEmptyUnconfiguredState() {
        assertFalse(
            shouldShowSharedTagCloudEntryPoints(
                isConfigured = false,
                hasSharedTags = false,
            ),
        )
    }

    @Test
    fun detailAssignedTagIds_includesLocallyAssignedTagsBeforeRoomFlowCatchesUp() {
        val assignedTags = listOf(
            SharedTagRecord(id = 10L, name = "追加済み"),
        )

        val ids = detailAssignedTagIds(
            assignedTags = assignedTags,
            locallyAssignedTagIds = setOf(20L),
        )

        assertEquals(setOf(10L, 20L), ids)
    }

    @Test
    fun detailAvailableCollectionTags_excludesCollectionsWhoseNameIsAlreadyShownAsTag() {
        val available = detailAvailableCollectionTags(
            customCollections = listOf(
                CollectionEntity(id = 2L, name = "かさ", sortOrder = 2, createdAt = 1L, updatedAt = 1L),
                CollectionEntity(id = 3L, name = "未追加", sortOrder = 3, createdAt = 2L, updatedAt = 2L),
            ),
            currentCollectionId = null,
            assignedLocalTagNameSet = setOf("かさ"),
        )

        assertEquals(listOf("未追加"), available.map { it.name })
    }
}
