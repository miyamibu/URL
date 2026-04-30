package jp.mimac.urlsaver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.DefaultExportRepository
import jp.mimac.urlsaver.data.ExportOutputFormat
import jp.mimac.urlsaver.data.ExportRecordStateFilter
import jp.mimac.urlsaver.data.ExportRequest
import jp.mimac.urlsaver.data.ExportScope
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
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class ExportRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultExportRepository
    private val authProvider = FakeAuthSessionProvider()
    private val clock = FakeClock(1_714_000_000_000L)
    private val jsonParser = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultExportRepository(
            urlEntryDao = db.urlEntryDao(),
            tagDao = db.tagDao(),
            collectionDao = db.collectionDao(),
            authSessionProvider = authProvider,
            clock = clock,
            appVersion = "1.0-test",
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun prepareExport_zipSharedTagsOnly_filtersByMemo_andContainsZipArtifacts() = runBlocking {
        val fixture = seedSharedTagMemoFixture()

        val archive = repository.prepareExport(
            ExportRequest(
                scope = ExportScope.SHARED_TAGS_ONLY,
                recordStateFilter = ExportRecordStateFilter.BOTH,
                onlyWithMemo = true,
                outputFormat = ExportOutputFormat.ZIP,
            ),
        )

        assertEquals(1, archive.entryCount)
        assertTrue(archive.fileName.endsWith(".zip"))
        assertEquals("application/zip", archive.mimeType)

        val payload = parseZipPayload(archive.bytes)
        val normalizedUrls = payload.entries.map { it.stringValue("normalizedUrl") }
        assertEquals(listOf(fixture.sharedUrl), normalizedUrls)
        assertFalse(normalizedUrls.contains(fixture.localUrl))

        assertEquals("SHARED_TAGS_ONLY", payload.manifest.stringValue("exportScope"))
        assertTrue(payload.files.containsKey("manifest.json"))
        assertTrue(payload.files.containsKey("entries.jsonl"))
        assertTrue(payload.markdownFiles.isNotEmpty())
        assertTrue(payload.markdownFiles.values.any { it.contains("Memo: keep") })
    }

    @Test
    fun prepareExport_jsonSharedTagsOnly_filtersByMemo_andContainsManifestAndEntries() = runBlocking {
        val fixture = seedSharedTagMemoFixture()

        val archive = repository.prepareExport(
            ExportRequest(
                scope = ExportScope.SHARED_TAGS_ONLY,
                recordStateFilter = ExportRecordStateFilter.BOTH,
                onlyWithMemo = true,
                outputFormat = ExportOutputFormat.JSON,
            ),
        )

        assertEquals(1, archive.entryCount)
        assertTrue(archive.fileName.endsWith(".json"))
        assertEquals("application/json", archive.mimeType)

        val payload = parseJsonPayload(archive.bytes)
        val normalizedUrls = payload.entries.map { it.stringValue("normalizedUrl") }
        assertEquals(listOf(fixture.sharedUrl), normalizedUrls)
        assertFalse(normalizedUrls.contains(fixture.localUrl))

        assertEquals("SHARED_TAGS_ONLY", payload.manifest.stringValue("exportScope"))
        assertEquals(1, payload.entries.size)
    }

    @Test
    fun prepareExport_allScopeIncludesSharedOnlyEntries_zip() = runBlocking {
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "user-a",
                accessToken = "token",
            ),
        )
        val sharedOnlyUrl = "https://example.com/shared-only"
        val sharedOnlyEntryId = insertEntry(
            normalizedUrl = sharedOnlyUrl,
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 250L,
            localProvenanceCount = 0,
        )
        val syncedTagId = db.tagDao().insertTag(
            TagEntity(
                name = "shared export",
                createdAt = 50L,
                scope = SharedTagScope.SYNCED,
                authUserId = "user-a",
                remoteTagId = "remote-export",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        db.tagDao().upsertCrossRefs(
            listOf(
                TagUrlCrossRef(
                    tagId = syncedTagId,
                    entryId = sharedOnlyEntryId,
                    scope = SharedTagScope.SYNCED,
                    authUserId = "user-a",
                    remoteUrlId = "remote-url",
                    rawUrl = sharedOnlyUrl,
                    normalizedUrl = sharedOnlyUrl,
                    normalizationVersion = 1,
                    syncStatus = SharedTagSyncStatus.SYNCED,
                ),
            ),
        )

        val archive = repository.prepareExport(
            ExportRequest(
                scope = ExportScope.ALL,
                recordStateFilter = ExportRecordStateFilter.BOTH,
                outputFormat = ExportOutputFormat.ZIP,
            ),
        )

        val payload = parseZipPayload(archive.bytes)
        assertEquals(1, archive.entryCount)
        assertTrue(payload.files.getValue("entries.jsonl").contains(sharedOnlyUrl))
        assertTrue(payload.files.getValue("entries.jsonl").contains("shared export"))
    }

    @Test
    fun prepareExport_filteringParity_zipAndJson_forSingleTagArchivedScope() = runBlocking {
        val fixture = seedSingleTagArchivedFixture()
        val zipRequest = ExportRequest(
            scope = ExportScope.SINGLE_TAG,
            selectedTagIds = setOf(fixture.tagId),
            recordStateFilter = ExportRecordStateFilter.ARCHIVED,
            serviceType = ServiceType.INSTAGRAM,
            outputFormat = ExportOutputFormat.ZIP,
        )

        val jsonRequest = zipRequest.copy(outputFormat = ExportOutputFormat.JSON)

        val zipArchive = repository.prepareExport(zipRequest)
        val zipPayload = parseZipPayload(zipArchive.bytes)

        val jsonArchive = repository.prepareExport(jsonRequest)
        val jsonPayload = parseJsonPayload(jsonArchive.bytes)

        val zipUrls = zipPayload.entries.map { it.stringValue("normalizedUrl") }.toSet()
        val jsonUrls = jsonPayload.entries.map { it.stringValue("normalizedUrl") }.toSet()

        assertEquals(1, zipArchive.entryCount)
        assertEquals(1, jsonArchive.entryCount)
        assertEquals(setOf(fixture.archivedUrl), zipUrls)
        assertEquals(setOf(fixture.archivedUrl), jsonUrls)
        assertEquals(zipUrls, jsonUrls)
        assertFalse(zipUrls.contains(fixture.activeUrl))
        assertFalse(jsonUrls.contains(fixture.activeUrl))

        assertTrue(zipPayload.files.getValue("manifest.json").contains("\"selectedTagIds\""))
        assertEquals("SINGLE_TAG", jsonPayload.manifest.stringValue("exportScope"))
    }

    @Test
    fun outputFormatEnum_containsOnlyZipAndJson() {
        assertEquals(
            listOf(ExportOutputFormat.ZIP, ExportOutputFormat.JSON),
            ExportOutputFormat.entries,
        )
    }

    @Test
    fun observeAvailableTags_includesLocalTags() = runBlocking {
        db.tagDao().insertTag(TagEntity(name = "あとで読む", createdAt = 1L))
        val names = withTimeout(2_000L) {
            repository.observeAvailableTags()
                .map { tags -> tags.map { it.name } }
                .first()
        }

        assertEquals(listOf("あとで読む"), names)
    }

    @Test
    fun loadAvailableTags_includesLocalAndCurrentUserSharedTags() = runBlocking {
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "user-a",
                accessToken = "token",
            ),
        )
        db.tagDao().insertTag(TagEntity(name = "local", createdAt = 1L))
        db.tagDao().insertTag(
            TagEntity(
                name = "shared mine",
                createdAt = 2L,
                scope = SharedTagScope.SYNCED,
                authUserId = "user-a",
                remoteTagId = "remote-a",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        db.tagDao().insertTag(
            TagEntity(
                name = "shared other",
                createdAt = 3L,
                scope = SharedTagScope.SYNCED,
                authUserId = "user-b",
                remoteTagId = "remote-b",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )

        val tags = repository.loadAvailableTags()

        assertEquals(listOf("local", "shared mine"), tags.map { it.name })
        assertEquals(listOf(SharedTagScope.LOCAL_ONLY, SharedTagScope.SYNCED), tags.map { it.scope })
    }

    private suspend fun seedSharedTagMemoFixture(): SharedTagMemoFixture {
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "user-a",
                accessToken = "token",
            ),
        )

        val localUrl = "https://example.com/local"
        val sharedUrl = "https://example.com/shared"
        insertEntry(
            normalizedUrl = localUrl,
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 100L,
        )
        val sharedEntryId = insertEntry(
            normalizedUrl = sharedUrl,
            recordState = RecordState.ACTIVE,
            memo = "keep",
            serviceType = ServiceType.X,
            createdAt = 200L,
        )

        val syncedTagId = db.tagDao().insertTag(
            TagEntity(
                name = "cloud",
                createdAt = 50L,
                scope = SharedTagScope.SYNCED,
                authUserId = "user-a",
                remoteTagId = "remote-tag",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        db.tagDao().upsertCrossRefs(
            listOf(
                TagUrlCrossRef(
                    tagId = syncedTagId,
                    entryId = sharedEntryId,
                    scope = SharedTagScope.SYNCED,
                    authUserId = "user-a",
                    remoteUrlId = "remote-url",
                    rawUrl = sharedUrl,
                    normalizedUrl = sharedUrl,
                    normalizationVersion = 1,
                    syncStatus = SharedTagSyncStatus.SYNCED,
                ),
            ),
        )

        return SharedTagMemoFixture(
            localUrl = localUrl,
            sharedUrl = sharedUrl,
        )
    }

    private suspend fun seedSingleTagArchivedFixture(): SingleTagArchivedFixture {
        val tagId = db.tagDao().insertTag(TagEntity(name = "alpha", createdAt = 1L))
        val archivedUrl = "https://example.com/archived"
        val activeUrl = "https://example.com/active"

        val archivedEntryId = insertEntry(
            normalizedUrl = archivedUrl,
            recordState = RecordState.ARCHIVED,
            memo = "",
            serviceType = ServiceType.INSTAGRAM,
            createdAt = 300L,
            archivedAt = 350L,
        )
        val activeEntryId = insertEntry(
            normalizedUrl = activeUrl,
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.INSTAGRAM,
            createdAt = 301L,
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = archivedEntryId))
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = activeEntryId))

        return SingleTagArchivedFixture(
            tagId = tagId,
            archivedUrl = archivedUrl,
            activeUrl = activeUrl,
        )
    }

    private suspend fun insertEntry(
        normalizedUrl: String,
        recordState: RecordState,
        memo: String,
        serviceType: ServiceType,
        createdAt: Long,
        archivedAt: Long? = null,
        localProvenanceCount: Int = 1,
    ): Long {
        return db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = normalizedUrl,
                normalizedUrl = normalizedUrl,
                displayUrl = normalizedUrl.removePrefix("https://"),
                openUrl = normalizedUrl,
                normalizedHost = "example.com",
                rawSourceHost = "example.com",
                serviceType = serviceType,
                contentContext = ContentContext.STANDARD,
                memo = memo,
                metadataState = MetadataState.PENDING,
                recordState = recordState,
                localProvenanceCount = localProvenanceCount,
                createdAt = createdAt,
                updatedAt = createdAt,
                archivedAt = archivedAt,
            ),
        )
    }

    private fun parseZipPayload(bytes: ByteArray): ZipPayload {
        val files = unzip(bytes)
        val manifest = parseJsonObject(files.getValue("manifest.json"))
        val entries = files.getValue("entries.jsonl")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line -> parseJsonObject(line) }
            .toList()
        val markdownFiles = files.filterKeys { it.startsWith("entries/") && it.endsWith(".md") }

        return ZipPayload(
            files = files,
            manifest = manifest,
            entries = entries,
            markdownFiles = markdownFiles,
        )
    }

    private fun parseJsonPayload(bytes: ByteArray): JsonPayload {
        val root = parseJsonObject(bytes.toString(Charsets.UTF_8))
        val manifest = root.getValue("manifest").jsonObject
        val entries = root.getValue("entries").jsonArray

        return JsonPayload(
            manifest = manifest,
            entries = entries.map { it.jsonObject },
        )
    }

    private fun parseJsonObject(text: String): JsonObject {
        return jsonParser.parseToJsonElement(text).jsonObject
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val files = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return files
    }

    private fun JsonObject.stringValue(key: String): String {
        return getValue(key).jsonPrimitive.content
    }

    private data class SharedTagMemoFixture(
        val localUrl: String,
        val sharedUrl: String,
    )

    private data class SingleTagArchivedFixture(
        val tagId: Long,
        val archivedUrl: String,
        val activeUrl: String,
    )

    private data class ZipPayload(
        val files: Map<String, String>,
        val manifest: JsonObject,
        val entries: List<JsonObject>,
        val markdownFiles: Map<String, String>,
    )

    private data class JsonPayload(
        val manifest: JsonObject,
        val entries: List<JsonObject>,
    )

    private class FakeClock(
        private val now: Long,
    ) : AppClock {
        override fun nowEpochMillis(): Long = now
    }

    private class FakeAuthSessionProvider : SharedTagAuthSessionProvider {
        private val state = MutableStateFlow<SharedTagAuthSession?>(null)
        override val session: StateFlow<SharedTagAuthSession?> = state

        override fun updateSession(newSession: SharedTagAuthSession?) {
            state.value = newSession
        }
    }
}
