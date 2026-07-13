package jp.mimac.urlsaver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.AiLocalDataClearer
import jp.mimac.urlsaver.data.DefaultTagRepository
import jp.mimac.urlsaver.data.DefaultUrlRepository
import jp.mimac.urlsaver.data.DefaultUsageSummaryDataSource
import jp.mimac.urlsaver.data.MetadataScheduler
import jp.mimac.urlsaver.data.SharedTagAuthSession
import jp.mimac.urlsaver.data.SharedTagAuthSessionProvider
import jp.mimac.urlsaver.data.SharedTagSyncCoordinator
import jp.mimac.urlsaver.data.SharedTagSyncRemoteConfig
import jp.mimac.urlsaver.data.SupabaseSharedTagAuthRemoteDataSource
import jp.mimac.urlsaver.data.SupabaseSharedTagSyncRemoteDataSource
import jp.mimac.urlsaver.domain.AssignTagResult
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.SharedTagAuthResult
import jp.mimac.urlsaver.domain.SharedTagInviteCreationResult
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedTagCloudLiveDeviceTest {
    private lateinit var db: AppDatabase
    private lateinit var authProvider: InMemoryAuthSessionProvider

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authProvider = InMemoryAuthSessionProvider()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun androidOwnerCreatesTagUrlAndInviteAgainstLiveCloud() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e runLiveSharedTagCloud true to create live shared-tag cloud data",
            args.getString("runLiveSharedTagCloud") == "true",
        )
        val config = SharedTagSyncRemoteConfig(
            enabled = BuildConfig.SHARED_TAG_CLOUD_ENABLED,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
        )
        assumeTrue("Live shared-tag cloud config is required", config.isConfigured)

        val authRemote = SupabaseSharedTagAuthRemoteDataSource(config)
        val syncRemote = SupabaseSharedTagSyncRemoteDataSource(
            config = config,
            authSessionProvider = authProvider,
            authRemoteDataSource = authRemote,
        )
        val metadataScheduler = NoopMetadataScheduler()
        val coordinator = SharedTagSyncCoordinator(
            database = db,
            tagDao = db.tagDao(),
            syncDao = db.sharedTagSyncDao(),
            urlEntryDao = db.urlEntryDao(),
            authSessionProvider = authProvider,
            remoteDataSource = syncRemote,
            clock = SystemMillisClock,
            metadataScheduler = metadataScheduler,
        )
        val usage = DefaultUsageSummaryDataSource(
            urlEntryDao = db.urlEntryDao(),
            tagDao = db.tagDao(),
            authSessionProvider = authProvider,
        )
        val urlRepository = DefaultUrlRepository(
            database = db,
            dao = db.urlEntryDao(),
            tagDao = db.tagDao(),
            clock = SystemMillisClock,
            scheduler = metadataScheduler,
            usageSummaryDataSource = usage,
        )
        val tagRepository = DefaultTagRepository(
            database = db,
            tagDao = db.tagDao(),
            syncDao = db.sharedTagSyncDao(),
            urlEntryDao = db.urlEntryDao(),
            clock = SystemMillisClock,
            metadataScheduler = metadataScheduler,
            authSessionProvider = authProvider,
            authRemoteDataSource = authRemote,
            syncScheduler = null,
            syncCoordinator = coordinator,
            remoteDataSource = syncRemote,
            remoteConfig = config,
            usageSummaryDataSource = usage,
            aiLocalDataClearer = AiLocalDataClearer { },
        )

        val testId = UUID.randomUUID().toString().lowercase()
        val email = "android-owner-${testId.take(8)}@example.com"
        val password = "pass12345"
        val tagName = "iPhone Join ${testId.take(6)}"
        val rawUrl = "https://example.com/iphone-join-$testId"

        val signUp = tagRepository.signUp(email, password)
        assertTrue("Android owner sign-up failed: $signUp", signUp is SharedTagAuthResult.Success)
        assertTrue("Initial Android owner sync failed", coordinator.syncCurrentSession())

        val saveResult: SaveResult = urlRepository.saveFromManualInput(rawUrl)
        val entryId = requireNotNull(saveResult.entryId) { "Android URL save did not return entry id: $saveResult" }

        val createTag = tagRepository.createSyncedTagWithResult(tagName)
        assertTrue("Android tag creation failed: $createTag", createTag is CreateTagResult.Success)
        val tagId = (createTag as CreateTagResult.Success).tagId
        assertTrue("Android tag creation sync failed", coordinator.syncCurrentSession())

        val assign = tagRepository.assignTagWithResult(tagId, entryId)
        assertEquals(AssignTagResult.Success, assign)
        assertTrue("Android shared URL sync failed", coordinator.syncCurrentSession())

        val visibleTags = tagRepository.observeAllTagsWithCount().first()
        val syncedTag = visibleTags.firstOrNull { it.name == tagName }
        assertNotNull("Synced Android-created tag was not visible locally", syncedTag)
        assertEquals(1, requireNotNull(syncedTag).urlCount)
        assertFalse("Outbox still has pending operations", db.sharedTagSyncDao().getPendingOutbox(requireNotNull(authProvider.session.value).authUserId).isNotEmpty())

        val invite = tagRepository.createInviteLink(tagId)
        assertTrue("Android invite creation failed: $invite", invite is SharedTagInviteCreationResult.Success)
        val success = invite as SharedTagInviteCreationResult.Success

        println("URLSAVER_IOS_ACCEPT_TAG_NAME=$tagName")
        println("URLSAVER_IOS_ACCEPT_INVITE_TOKEN=${success.inviteToken}")
        println("URLSAVER_IOS_ACCEPT_INVITE_URL=${success.inviteUrl}")
        println("URLSAVER_IOS_ACCEPT_EXPECTED_URL=$rawUrl")
    }
}

private object SystemMillisClock : AppClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

private class InMemoryAuthSessionProvider : SharedTagAuthSessionProvider {
    private val state = MutableStateFlow<SharedTagAuthSession?>(null)
    override val session: StateFlow<SharedTagAuthSession?> = state

    override fun updateSession(newSession: SharedTagAuthSession?) {
        state.value = newSession
    }
}

private class NoopMetadataScheduler : MetadataScheduler {
    override fun enqueueMetadata(entryId: Long) = Unit
}
