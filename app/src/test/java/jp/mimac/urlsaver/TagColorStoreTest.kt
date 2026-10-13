package jp.mimac.urlsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.DataStoreTagColorStore
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.SharedTagRecord
import jp.mimac.urlsaver.domain.TagColorChoice
import jp.mimac.urlsaver.domain.TagColorKey
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.domain.choiceFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TagColorStoreTest {
    @Test
    fun tagColorKeys_separateLocalIdsAndSharedAccounts() {
        val localOne = TagColorKey.forTag(TagWithCount(id = 1, name = "same", urlCount = 0)).single()
        val renamedLocalOne = TagColorKey.forTag(TagWithCount(id = 1, name = "renamed", urlCount = 0)).single()
        val localTwo = TagColorKey.forTag(TagWithCount(id = 2, name = "same", urlCount = 0)).single()
        val sharedAccountOne = TagColorKey.forTag(
            TagWithCount(
                id = 10,
                name = "shared",
                urlCount = 0,
                scope = SharedTagScope.SYNCED,
                authUserId = "account-one",
                remoteTagId = "remote-1",
            ),
        ).first()
        val sharedAccountTwo = TagColorKey.forTag(
            TagWithCount(
                id = 11,
                name = "shared",
                urlCount = 0,
                scope = SharedTagScope.SYNCED,
                authUserId = "account-two",
                remoteTagId = "remote-1",
            ),
        ).first()
        val sameSharedTagDifferentRole = TagColorKey.forTag(
            TagWithCount(
                id = 10,
                name = "renamed shared tag",
                urlCount = 0,
                scope = SharedTagScope.SYNCED,
                authUserId = "account-one",
                remoteTagId = "remote-1",
                currentUserRole = jp.mimac.urlsaver.domain.SharedTagMemberRole.VIEWER,
            ),
        ).first()

        assertEquals(localOne, renamedLocalOne)
        assertNotEquals(localOne, localTwo)
        assertNotEquals(sharedAccountOne, sharedAccountTwo)
        assertEquals(sharedAccountOne, sameSharedTagDifferentRole)
        assertNotEquals(localOne, sharedAccountOne)
        assertEquals(8, TagColorChoice.CUSTOM.size)
    }

    @Test
    fun sharedColorKey_keepsLocalIdFallbackWhenRemoteIdArrivesLater() {
        val beforeSync = TagColorKey.forTag(
            SharedTagRecord(
                id = 45,
                name = "shared",
                scope = SharedTagScope.SYNCED,
                authUserId = "account-one",
            ),
        )
        val afterSync = TagColorKey.forTag(
            SharedTagRecord(
                id = 45,
                name = "shared",
                scope = SharedTagScope.SYNCED,
                authUserId = "account-one",
                remoteTagId = "remote-45",
            ),
        )

        assertEquals(beforeSync.first(), afterSync.last())
        assertNotEquals(afterSync.first(), afterSync.last())
    }

    @Test
    fun resettingSyncedTagColorClearsPreSyncFallbackAndKeepsOtherTags() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DataStoreTagColorStore(context)
        val id = UUID.randomUUID().mostSignificantBits
        val beforeSync = SharedTagRecord(
            id = id,
            name = "shared",
            scope = SharedTagScope.SYNCED,
            authUserId = "account-one",
        )
        val fallbackKeys = TagColorKey.forTag(beforeSync)
        val syncedKeys = TagColorKey.forTag(beforeSync.copy(remoteTagId = "remote-$id"))
        val otherKey = TagColorKey.shared("account-two", "remote-$id")

        store.setChoice(fallbackKeys, TagColorChoice.SAKURA)
        store.setChoice(otherKey, TagColorChoice.MINT)
        assertEquals(TagColorChoice.SAKURA, store.observeChoices().first().choiceFor(syncedKeys))

        store.setChoice(syncedKeys, TagColorChoice.DEFAULT)
        val resetChoices = DataStoreTagColorStore(context).observeChoices().first()
        assertEquals(TagColorChoice.DEFAULT, resetChoices.choiceFor(syncedKeys))
        assertTrue(syncedKeys.none { it.storageValue in resetChoices })
        assertEquals(TagColorChoice.MINT, resetChoices.choiceFor(listOf(otherKey)))

        store.setChoice(fallbackKeys, TagColorChoice.SAKURA)
        store.setChoice(syncedKeys, TagColorChoice.SKY)
        val updatedChoices = store.observeChoices().first()
        assertEquals(TagColorChoice.SKY, updatedChoices.choiceFor(syncedKeys))
        assertTrue(fallbackKeys.none { it.storageValue in updatedChoices })
        store.setChoice(syncedKeys, TagColorChoice.DEFAULT)
        store.setChoice(otherKey, TagColorChoice.DEFAULT)
    }

    @Test
    fun choicesPersistAcrossStoreInstancesAndDefaultRemovesTheOverride() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = TagColorKey.local(UUID.randomUUID().mostSignificantBits)
        val firstStore = DataStoreTagColorStore(context)

        firstStore.setChoice(key, TagColorChoice.SAKURA)
        assertEquals(TagColorChoice.SAKURA, firstStore.observeChoices().first().choiceFor(listOf(key)))

        val reopenedStore = DataStoreTagColorStore(context)
        assertEquals(TagColorChoice.SAKURA, reopenedStore.observeChoices().first().choiceFor(listOf(key)))

        reopenedStore.setChoice(key, TagColorChoice.DEFAULT)
        assertEquals(TagColorChoice.DEFAULT, firstStore.observeChoices().first().choiceFor(listOf(key)))
        assertTrue(key.storageValue !in firstStore.observeChoices().first())
    }
}
