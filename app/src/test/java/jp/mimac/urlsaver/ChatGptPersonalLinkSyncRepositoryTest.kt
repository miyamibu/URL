package jp.mimac.urlsaver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.ApplyPersonalLinkOpsResponse
import jp.mimac.urlsaver.data.ChatGptPersonalLinkRemoteDataSource
import jp.mimac.urlsaver.data.ChatGptPersonalLinkSyncOperation
import jp.mimac.urlsaver.data.ChatGptPersonalLinkSyncRepository
import jp.mimac.urlsaver.data.ChatGptPersonalLinkSyncSettings
import jp.mimac.urlsaver.data.ChatGptPersonalLinkSyncSettingsStore
import jp.mimac.urlsaver.data.ChatGptSyncResult
import jp.mimac.urlsaver.data.SharedPreferencesChatGptPersonalLinkSyncSettingsStore
import jp.mimac.urlsaver.data.SharedTagAuthSession
import jp.mimac.urlsaver.data.SharedTagAuthSessionProvider
import jp.mimac.urlsaver.data.TagEntity
import jp.mimac.urlsaver.data.TagUrlCrossRef
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.SharedTagSyncStatus
import jp.mimac.urlsaver.ui.ChatGptSyncPendingAction
import jp.mimac.urlsaver.ui.chatGptSyncConfirmationBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatGptPersonalLinkSyncRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var authProvider: FakeAuthSessionProvider
    private lateinit var settingsStore: FakeSettingsStore
    private lateinit var remote: FakeRemoteDataSource
    private lateinit var repository: ChatGptPersonalLinkSyncRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authProvider = FakeAuthSessionProvider()
        settingsStore = FakeSettingsStore()
        remote = FakeRemoteDataSource()
        repository = ChatGptPersonalLinkSyncRepository(
            authSessionProvider = authProvider,
            urlEntryDao = db.urlEntryDao(),
            tagDao = db.tagDao(),
            settingsStore = settingsStore,
            remoteDataSource = remote,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun syncCurrentSnapshot_sendsOnlyLocalTagNames() = runBlocking {
        authProvider.updateSession(SharedTagAuthSession(authUserId = "user-a", accessToken = "token-a"))
        val entryId = insertEntry("https://example.com/chatgpt-local-only")
        val localTagId = db.tagDao().insertTag(TagEntity(name = "local-only", createdAt = 1L))
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = localTagId, entryId = entryId))

        repository.setEnabled(enabled = true, contentFetchEnabled = false)

        val operation = remote.operations.single()
        assertEquals(listOf("local-only"), operation.tags)
        assertFalse(operation.tags.contains("shared-hidden"))
    }

    @Test
    fun syncCurrentSnapshot_excludesEntriesAllocatedToSharedTags() = runBlocking {
        authProvider.updateSession(SharedTagAuthSession(authUserId = "user-a", accessToken = "token-a"))
        val entryId = insertEntry("https://example.com/chatgpt-shared-hidden")
        val sharedTagId = db.tagDao().insertTag(
            TagEntity(
                name = "shared-hidden",
                createdAt = 2L,
                scope = SharedTagScope.SYNCED,
                authUserId = "user-a",
                remoteTagId = "remote-tag",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        db.tagDao().upsertCrossRefs(
            listOf(
                TagUrlCrossRef(
                    tagId = sharedTagId,
                    entryId = entryId,
                    scope = SharedTagScope.SYNCED,
                    authUserId = "user-a",
                    remoteUrlId = "remote-url",
                    rawUrl = "https://example.com/chatgpt-shared-hidden",
                    normalizedUrl = "https://example.com/chatgpt-shared-hidden",
                    normalizationVersion = 1,
                    syncStatus = SharedTagSyncStatus.SYNCED,
                ),
            ),
        )

        repository.setEnabled(enabled = true, contentFetchEnabled = false)

        assertTrue(remote.operations.isEmpty())
    }

    @Test
    fun syncCurrentSnapshot_excludesArchivedAndNeverSendsFetchedBody() = runBlocking {
        authProvider.updateSession(SharedTagAuthSession(authUserId = "user-a", accessToken = "token-a"))
        insertEntry(
            normalizedUrl = "https://example.com/chatgpt-archived",
            recordState = RecordState.ARCHIVED,
            fetchedBody = "private fetched body",
        )
        insertEntry(
            normalizedUrl = "https://example.com/chatgpt-active",
            fetchedBody = "private fetched body",
        )

        repository.setEnabled(enabled = true, contentFetchEnabled = true)

        val operation = remote.operations.single()
        assertEquals("https://example.com/chatgpt-active", operation.url)
        assertEquals(null, operation.extractedText)
        assertFalse(settingsStore.snapshot("user-a").contentFetchEnabled)
    }

    @Test
    fun eligibilitySnapshot_confirmationBodyShowsTargetExcludedAndSummary() = runBlocking {
        insertEntry("https://example.com/chatgpt-confirmation-target")
        insertEntry(
            normalizedUrl = "https://example.com/chatgpt-confirmation-excluded",
            recordState = RecordState.ARCHIVED,
        )

        val body = chatGptSyncConfirmationBody(
            action = ChatGptSyncPendingAction.SYNC_NOW,
            eligibility = repository.eligibilitySnapshot(),
        )

        assertTrue(body.contains("対象 1件"))
        assertTrue(body.contains("除外 1件"))
        assertTrue(body.contains("送信対象概要: example.com"))
    }

    @Test
    fun disableConfirmationBodyShowsCountsAndNoSendWording() = runBlocking {
        insertEntry("https://example.com/chatgpt-disable-target")
        insertEntry(
            normalizedUrl = "https://example.com/chatgpt-disable-excluded",
            recordState = RecordState.ARCHIVED,
        )

        val body = chatGptSyncConfirmationBody(
            action = ChatGptSyncPendingAction.DISABLE,
            eligibility = repository.eligibilitySnapshot(),
        )

        assertTrue(body.contains("対象 1件"))
        assertTrue(body.contains("除外 1件"))
        assertTrue(body.contains("今回の送信なし"))
    }

    @Test
    fun settings_areScopedAcrossSignOutUserSwitchAndSameUserRelogin() = runBlocking {
        authProvider.updateSession(SharedTagAuthSession(authUserId = "user-a", accessToken = "token-a"))
        repository.setEnabled(enabled = true, contentFetchEnabled = false)
        val userALastSync = settingsStore.snapshot("user-a").lastSyncedAt

        authProvider.updateSession(null)
        assertFalse(repository.settings.first().enabled)

        authProvider.updateSession(SharedTagAuthSession(authUserId = "user-b", accessToken = "token-b"))
        assertFalse(repository.settings.first().enabled)
        assertEquals(null, repository.settings.first().lastSyncedAt)

        authProvider.updateSession(SharedTagAuthSession(authUserId = "user-a", accessToken = "token-a-2"))
        assertTrue(repository.settings.first().enabled)
        assertEquals(userALastSync, repository.settings.first().lastSyncedAt)
    }

    @Test
    fun legacyOwnerlessEnabledPreference_failsClosedForEverySignedInUser() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("chatgpt_personal_link_sync", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", true)
            .putLong("last_synced_at", 99L)
            .commit()
        val store = SharedPreferencesChatGptPersonalLinkSyncSettingsStore(context)

        assertEquals(ChatGptPersonalLinkSyncSettings(), store.snapshot("new-user"))
        assertEquals(ChatGptPersonalLinkSyncSettings(), store.snapshot(null))
    }

    @Test
    fun inFlightSyncCompletionAfterUserSwitch_neverWritesOldOrNewUserResult() = runBlocking {
        authProvider.updateSession(SharedTagAuthSession(authUserId = "user-a", accessToken = "token-a"))
        insertEntry("https://example.com/in-flight-user-switch")
        val operationStarted = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        remote.beforeApplyReturn = {
            operationStarted.complete(Unit)
            allowCompletion.await()
        }

        val operation = async { repository.setEnabled(enabled = true, contentFetchEnabled = false) }
        operationStarted.await()
        authProvider.updateSession(SharedTagAuthSession(authUserId = "user-b", accessToken = "token-b"))
        allowCompletion.complete(Unit)

        assertEquals(ChatGptSyncResult.AuthRequired, operation.await())
        assertEquals(null, settingsStore.snapshot("user-a").lastSyncedAt)
        assertEquals(ChatGptPersonalLinkSyncSettings(), settingsStore.snapshot("user-b"))
    }

    @Test
    fun deletionCleanup_clearsOnlyTargetAccountSettingsAndPreservesSavedUrls() = runBlocking {
        val entryId = insertEntry("https://example.com/settings-cleanup-preserves-url")
        settingsStore.setEnabled("user-a", enabled = true, contentFetchEnabled = false)
        settingsStore.setEnabled("user-b", enabled = true, contentFetchEnabled = false)

        settingsStore.clear("user-a")

        assertEquals(ChatGptPersonalLinkSyncSettings(), settingsStore.snapshot("user-a"))
        assertTrue(settingsStore.snapshot("user-b").enabled)
        assertEquals(entryId, db.urlEntryDao().findById(entryId)?.id)
    }

    private suspend fun insertEntry(
        normalizedUrl: String,
        recordState: RecordState = RecordState.ACTIVE,
        fetchedBody: String? = null,
    ): Long {
        return db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = normalizedUrl,
                normalizedUrl = normalizedUrl,
                displayUrl = normalizedUrl.removePrefix("https://"),
                openUrl = normalizedUrl,
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = ServiceType.WEB,
                contentContext = ContentContext.STANDARD,
                metadataState = MetadataState.READY,
                fetchedBody = fetchedBody,
                recordState = recordState,
                createdAt = 1_000L,
                updatedAt = 1_000L,
            ),
        )
    }

    private class FakeAuthSessionProvider : SharedTagAuthSessionProvider {
        private val state = MutableStateFlow<SharedTagAuthSession?>(null)
        override val session: StateFlow<SharedTagAuthSession?> = state

        override fun updateSession(newSession: SharedTagAuthSession?) {
            state.value = newSession
        }
    }

    private class FakeSettingsStore : ChatGptPersonalLinkSyncSettingsStore {
        private val values = mutableMapOf<String, ChatGptPersonalLinkSyncSettings>()
        private val revisionState = MutableStateFlow(0L)
        override val revision: StateFlow<Long> = revisionState

        override fun snapshot(authUserId: String?): ChatGptPersonalLinkSyncSettings =
            authUserId?.let(values::get) ?: ChatGptPersonalLinkSyncSettings()

        override fun setEnabled(authUserId: String, enabled: Boolean, contentFetchEnabled: Boolean) {
            values[authUserId] = snapshot(authUserId).copy(
                enabled = enabled,
                contentFetchEnabled = enabled && contentFetchEnabled,
                lastErrorMessage = null,
            )
            revisionState.value += 1L
        }

        override fun markSyncSuccess(authUserId: String, syncedAt: Long) {
            values[authUserId] = snapshot(authUserId).copy(lastSyncedAt = syncedAt, lastErrorMessage = null)
            revisionState.value += 1L
        }

        override fun markSyncFailure(authUserId: String, message: String) {
            values[authUserId] = snapshot(authUserId).copy(lastErrorMessage = message)
            revisionState.value += 1L
        }

        override fun clear(authUserId: String) {
            values.remove(authUserId)
            revisionState.value += 1L
        }
    }

    private class FakeRemoteDataSource : ChatGptPersonalLinkRemoteDataSource {
        val operations = mutableListOf<ChatGptPersonalLinkSyncOperation>()
        var beforeApplyReturn: suspend () -> Unit = {}

        override suspend fun setSyncEnabled(
            session: SharedTagAuthSession,
            enabled: Boolean,
            contentFetchEnabled: Boolean,
        ) = Unit

        override suspend fun applyOps(
            session: SharedTagAuthSession,
            operations: List<ChatGptPersonalLinkSyncOperation>,
        ): ApplyPersonalLinkOpsResponse {
            this.operations += operations
            beforeApplyReturn()
            return ApplyPersonalLinkOpsResponse(status = "ok", appliedCount = operations.size)
        }
    }
}
