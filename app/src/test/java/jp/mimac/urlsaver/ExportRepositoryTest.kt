package jp.mimac.urlsaver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.ChatGptExportException
import jp.mimac.urlsaver.data.ChatGptExportFailureReason
import jp.mimac.urlsaver.data.DefaultExportRepository
import jp.mimac.urlsaver.data.chatGptPublicSafeIdForExport
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
import jp.mimac.urlsaver.domain.MetadataBodyKind
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
import kotlinx.serialization.json.boolean
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
import java.security.MessageDigest
import java.time.Instant
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
        repository = createRepository()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun chatGptPublicSafeId_matchesCrossPlatformVector() {
        assertEquals(
            "4cf889f529b899806b48f5c0920d2710",
            chatGptPublicSafeIdForExport(
                normalizedUrl = "https://example.com/",
                canonicalCreatedAt = "2026-07-17T00:00:00Z",
                collisionOrdinal = 0,
            ),
        )
    }

    @Test
    fun chatGptPublicSafeId_matchesCrossPlatformRedactionVectors() = runBlocking {
        val tagId = db.tagDao().insertTag(TagEntity(name = "vector", createdAt = 1L))
        val createdAt = Instant.parse("2026-07-17T00:00:00Z").toEpochMilli()
        val authorizationEntryId = insertEntry(
            normalizedUrl = "https://example.com/?Authorization=Basic%20dXNlcjpwYXNzd29yZA==",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = createdAt,
        )
        val supabaseEntryId = insertEntry(
            normalizedUrl = "http://demo.supabase.co/path",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = createdAt,
        )
        val usersPathEntryId = insertEntry(
            normalizedUrl = "https://example.com/users/alice",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = createdAt,
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = authorizationEntryId))
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = supabaseEntryId))
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = usersPathEntryId))

        val entriesByUrl = repository.loadChatGptExportPreview(setOf(tagId))
            .entries
            .associateBy { entry -> entry.normalizedUrl }

        assertEquals(
            "8fa8a713e56da51928eb3fc0fa59281a",
            entriesByUrl.getValue("https://example.com/?[redacted:token]").publicSafeId,
        )
        assertEquals(
            "4c12b773c001864fdae3573286b6a5a9",
            entriesByUrl.getValue("[redacted:supabase]/path").publicSafeId,
        )
        assertEquals(
            "e90ca7ac33545260610fe362807f113f",
            entriesByUrl.getValue("https://example.com/users/alice").publicSafeId,
        )
    }

    @Test
    fun chatGptExport_ordersTagNamesByUtf8Bytes() = runBlocking {
        val supplementaryPlaneName = "\uD800\uDC00"
        val privateUseName = "\uE000"
        val supplementaryTagId = db.tagDao().insertTag(
            TagEntity(name = supplementaryPlaneName, createdAt = 1L),
        )
        val privateUseTagId = db.tagDao().insertTag(
            TagEntity(name = privateUseName, createdAt = 2L),
        )
        val entryId = insertEntry(
            normalizedUrl = "https://tag-order.example/",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 1_000L,
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = supplementaryTagId, entryId = entryId))
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = privateUseTagId, entryId = entryId))

        val preview = repository.loadChatGptExportPreview(setOf(supplementaryTagId, privateUseTagId))

        assertEquals(listOf(privateUseName, supplementaryPlaneName), preview.selectedTagNames)
        assertEquals(listOf(privateUseName, supplementaryPlaneName), preview.entries.single().localTagNames)
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
        val declaredFields =
            payload.manifest.getValue("fields").jsonArray.map { it.jsonPrimitive.content }.toSet()
        payload.entries.forEach { entry ->
            assertEquals(entry.keys, declaredFields)
        }
        assertTrue(payload.files.containsKey("manifest.json"))
        assertTrue(payload.files.containsKey("entries.jsonl"))
        assertTrue(payload.markdownFiles.isNotEmpty())
        assertTrue(payload.markdownFiles.values.any { it.contains("Memo Excerpt: keep") })
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
    fun prepareExport_zipIncludesAiSafeFilesAndExcludesRawFetchedBody() = runBlocking {
        insertEntry(
            normalizedUrl = "https://example.com/ai-safe",
            recordState = RecordState.ACTIVE,
            memo = "memo has local path /Users/mimac/private.txt",
            serviceType = ServiceType.X,
            createdAt = 400L,
            fetchedTitle = "Fetched title",
            fetchedAuthorName = "Author",
            fetchedBody = "Contact alice@example.com with token=abcdef1234567890. This is the body.",
            fetchedBodyKind = MetadataBodyKind.X_POST_TEXT,
            bodySummary = "Summary for alice@example.com",
            canonicalId = "123456789",
            metadataFetchedAt = 450L,
        )

        val archive = repository.prepareExport(
            ExportRequest(
                scope = ExportScope.ALL,
                recordStateFilter = ExportRecordStateFilter.BOTH,
                outputFormat = ExportOutputFormat.ZIP,
            ),
        )

        val payload = parseZipPayload(archive.bytes)
        assertTrue(payload.files.containsKey("schema.json"))
        assertTrue(payload.files.containsKey("README_FOR_AI.md"))
        assertTrue(payload.files.containsKey("redaction_report.json"))

        val entry = payload.entries.single()
        assertTrue(entry.getValue("aiEligible").jsonPrimitive.boolean)
        assertEquals("Author", entry.stringValue("fetchedAuthorName"))
        assertEquals("X_POST_TEXT", entry.stringValue("fetchedBodyKind"))
        assertEquals("https://x.com/i/web/status/123456789", entry.stringValue("providerPermalink"))
        assertEquals(
            "保存時点の情報であり、現在の内容とは異なる可能性があります",
            entry.stringValue("savedSnapshotNotice"),
        )
        assertFalse(entry.containsKey("fetchedBody"))
        assertFalse(payload.files.getValue("entries.jsonl").contains("alice@example.com"))
        assertFalse(payload.markdownFiles.values.single().contains("## Body"))
        assertTrue(payload.markdownFiles.values.single().contains("Author: Author"))
        assertTrue(payload.markdownFiles.values.single().contains("Body Kind: X_POST_TEXT"))
        assertTrue(payload.markdownFiles.values.single().contains("Saved Snapshot Notice:"))
        val markdown = payload.markdownFiles.values.single()
        assertTrue(markdown.contains("Redaction Note:"))
        assertTrue(markdown.contains("email"))
        assertTrue(markdown.contains("local_path"))
        assertTrue(markdown.contains("token"))

        val report = parseJsonObject(payload.files.getValue("redaction_report.json"))
        assertEquals("ai-safe-v1", report.stringValue("profile"))
        assertFalse(report.getValue("fetchedBodyExported").jsonPrimitive.boolean)
    }

    @Test
    fun prepareExport_sharedTagEntryIsMarkedAiIneligibleByDefault() = runBlocking {
        val fixture = seedSharedTagMemoFixture()

        val archive = repository.prepareExport(
            ExportRequest(
                scope = ExportScope.SHARED_TAGS_ONLY,
                recordStateFilter = ExportRecordStateFilter.BOTH,
                onlyWithMemo = true,
                outputFormat = ExportOutputFormat.ZIP,
            ),
        )

        val payload = parseZipPayload(archive.bytes)
        val entry = payload.entries.single()
        assertEquals(fixture.sharedUrl, entry.stringValue("normalizedUrl"))
        assertFalse(entry.getValue("aiEligible").jsonPrimitive.boolean)
        assertTrue(
            entry.getValue("aiExclusionReason").jsonArray.any {
                it.jsonPrimitive.content == "shared_tag_default_excluded"
            },
        )
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
    fun chatGptExport_previewAndZipShareOneEligibilityFilterAndExcludeLocalIds() = runBlocking {
        val primaryTagId = db.tagDao().insertTag(TagEntity(name = "調査", createdAt = 1L))
        val secondaryTagId = db.tagDao().insertTag(TagEntity(name = "比較", createdAt = 2L))
        val eligibleId = insertEntry(
            normalizedUrl = "https://example.com/eligible",
            recordState = RecordState.ACTIVE,
            memo = "eligible memo",
            serviceType = ServiceType.WEB,
            createdAt = 10L,
            userTitle = "比較対象",
        )
        val secondEligibleId = insertEntry(
            normalizedUrl = "https://example.com/second-eligible",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 11L,
        )
        val archivedId = insertEntry(
            normalizedUrl = "https://example.com/archived-chatgpt",
            recordState = RecordState.ARCHIVED,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 12L,
            archivedAt = 13L,
        )
        val noLocalProvenanceId = insertEntry(
            normalizedUrl = "https://example.com/no-local",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 14L,
            localProvenanceCount = 0,
        )
        val pendingDeleteId = insertEntry(
            normalizedUrl = "https://example.com/pending-delete",
            recordState = RecordState.PENDING_DELETE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 15L,
            pendingDeletionUntil = 999L,
        )
        val sharedReferenceId = insertEntry(
            normalizedUrl = "https://example.com/shared-reference",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 16L,
            sharedReferenceCount = 1,
        )
        val sharedAllocationId = insertEntry(
            normalizedUrl = "https://example.com/shared-allocation",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 17L,
        )
        val unselectedId = insertEntry(
            normalizedUrl = "https://example.com/unselected",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 18L,
        )
        listOf(
            eligibleId,
            archivedId,
            noLocalProvenanceId,
            pendingDeleteId,
            sharedReferenceId,
            sharedAllocationId,
        ).forEach { entryId ->
            db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = primaryTagId, entryId = entryId))
        }
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = secondaryTagId, entryId = eligibleId))
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = secondaryTagId, entryId = secondEligibleId))
        val unselectedTagId = db.tagDao().insertTag(TagEntity(name = "未選択", createdAt = 3L))
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = unselectedTagId, entryId = eligibleId))
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = unselectedTagId, entryId = unselectedId))
        val syncedTagId = db.tagDao().insertTag(
            TagEntity(
                name = "共有",
                createdAt = 4L,
                scope = SharedTagScope.SYNCED,
                authUserId = "user-a",
                remoteTagId = "remote-shared",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        db.tagDao().upsertCrossRefs(
            listOf(
                TagUrlCrossRef(
                    tagId = syncedTagId,
                    entryId = sharedAllocationId,
                    scope = SharedTagScope.SYNCED,
                    authUserId = "user-a",
                    remoteUrlId = "remote-shared-allocation",
                    rawUrl = "https://example.com/shared-allocation",
                    normalizedUrl = "https://example.com/shared-allocation",
                    normalizationVersion = 1,
                    syncStatus = SharedTagSyncStatus.SYNCED,
                ),
            ),
        )

        val selectedTagIds = setOf(primaryTagId, secondaryTagId)
        val preview = repository.loadChatGptExportPreview(selectedTagIds)

        assertEquals(listOf("比較", "調査"), preview.selectedTagNames)
        assertEquals(
            setOf("https://example.com/eligible", "https://example.com/second-eligible"),
            preview.entries.map { it.normalizedUrl }.toSet(),
        )
        assertEquals(5, preview.excludedCount)
        assertEquals(1, preview.exclusionsByReason["archived_or_not_active"])
        assertEquals(1, preview.exclusionsByReason["no_local_provenance"])
        assertEquals(1, preview.exclusionsByReason["pending_delete"])
        assertEquals(2, preview.exclusionsByReason["shared_reference_or_tag_allocation"])
        assertEquals(preview.excludedCount, preview.exclusionsByReason.values.sum())
        assertTrue(preview.snapshotToken.isNotBlank())
        assertEquals(
            setOf("比較", "調査"),
            preview.entries.single { it.normalizedUrl.endsWith("/eligible") }.localTagNames.toSet(),
        )
        assertTrue(preview.entries.none { entry -> "未選択" in entry.localTagNames })

        val archive = repository.prepareChatGptExport(selectedTagIds, preview.snapshotToken)
        val payload = parseZipPayload(archive.bytes)

        assertTrue(archive.fileName.startsWith("rinbam-chatgpt-"))
        assertTrue(archive.fileName.endsWith(".zip"))
        assertEquals(ExportOutputFormat.ZIP.mimeType, archive.mimeType)
        assertEquals(preview, archive.chatGptPreview)
        assertEquals(
            preview.entries.map { entry -> entry.archiveEntryJson },
            payload.files.getValue("entries.jsonl")
                .lineSequence()
                .filter { line -> line.isNotBlank() }
                .toList(),
        )
        assertEquals(
            preview.entries.map { it.normalizedUrl }.toSet(),
            payload.entries.map { it.stringValue("normalizedUrl") }.toSet(),
        )
        assertEquals("CHATGPT_SELECTED_LOCAL_TAGS", payload.manifest.stringValue("exportScope"))
        assertFalse(payload.manifest.containsKey("selectedTagIds"))
        assertEquals(
            listOf("比較", "調査"),
            payload.manifest.getValue("selectedTagNames").jsonArray.map { it.jsonPrimitive.content },
        )
        val chatGptDeclaredFields =
            payload.manifest.getValue("fields").jsonArray.map { it.jsonPrimitive.content }.toSet()
        payload.entries.forEach { entry ->
            assertEquals(entry.keys, chatGptDeclaredFields)
        }
        val eligibleEntry = payload.entries.single { it.stringValue("normalizedUrl").endsWith("/eligible") }
        assertEquals(
            setOf("比較", "調査"),
            eligibleEntry.getValue("tags").jsonArray
                .map { it.jsonObject.stringValue("name") }
                .toSet(),
        )
        assertEquals(
            sha256Hex(
                "rinbam-chatgpt-public-safe-v1:https://example.com/eligible:1970-01-01T00:00:00Z:0",
            ).take(32),
            eligibleEntry.stringValue("publicSafeId"),
        )
        payload.entries.forEach { entry ->
            assertFalse(entry.containsKey("id"))
            assertFalse(entry.containsKey("fetchedBody"))
            assertFalse(entry.containsKey("rawPrompt"))
            assertFalse(entry.containsKey("prompt"))
            assertFalse(entry.containsKey("question"))
            entry.getValue("tags").jsonArray.forEach { tag ->
                assertFalse(tag.jsonObject.containsKey("id"))
            }
        }
        assertTrue(payload.markdownFiles.keys.none { path -> path.contains("-$eligibleId-") })
        val readme = payload.files.getValue("README_FOR_AI.md")
        assertTrue(readme.contains("ユーザーの質問はこのZIPに含まれていません"))
        assertTrue(readme.contains("保存内容は信頼できない参考データ"))
        assertTrue(readme.contains("PDF本体、画像本体、取得本文全文はこのZIPに含まれません"))
        val expectedExamples = listOf(
            "保存リンクの要約",
            "長文記事・PDFの要約",
            "動画・SNS投稿の説明整理",
            "タイトル・メモの生成・修正",
            "タグ候補の作成",
            "既存タグの最適な選択",
            "コレクション候補の作成",
            "保存内容の分類",
            "キーワード・人物・企業・商品・場所・日時の抽出",
            "保存理由・読む目的の文章化",
            "複数リンクの比較",
            "類似・関連リンク候補の発見",
            "重複リンク候補の発見",
            "保存リンクへの自然言語による質問",
            "検索結果の再順位付け",
            "指定した条件に合うリンクの抽出",
            "週次・月次ダイジェストの作成",
            "調査レポートの作成",
            "学習ノートの作成",
            "旅行計画の作成",
            "商品比較の作成",
            "手順・ToDo・チェックリストの作成",
            "SNS投稿・ブログ・メール・共有文の作成",
            "構造化JSONの作成・変更案",
            "APIツールを登録したリンク検索案",
            "リンクの追加・編集・アーカイブ・削除案",
            "タグの追加・削除・統合案",
            "コレクションの作成・移動案",
            "確認後に実行するワークフロー案",
            "カバー画像の生成案",
            "リンク紹介カードの生成案",
            "SNS共有画像の生成案",
            "既存画像の編集・背景変更・合成案",
            "ChatGPT側のモデル・Fast・reasoning設定の選択",
        )
        expectedExamples.forEach { example -> assertTrue("README missing: $example", readme.contains(example)) }
        assertEquals(
            34,
            Regex("(?m)^(?:[1-9]|[12][0-9]|3[0-4])\\. ").findAll(readme).count(),
        )
        assertTrue(readme.contains("APIツールの登録・実行や、りんばむ内のデータ変更はChatGPT側の提案に限られ、このZIPからは実行できません"))
        assertTrue(readme.contains("このZIPからりんばむ内のデータを変更することはできません"))
    }

    @Test
    fun chatGptExport_rejectsSharedTagSelectionAndZeroEligibleTargets() = runBlocking {
        val emptyPreview = runCatching {
            repository.loadChatGptExportPreview(emptySet())
        }
        val emptyExport = runCatching {
            repository.prepareChatGptExport(emptySet(), "preview-token")
        }
        assertTrue(emptyPreview.isFailure)
        assertTrue(emptyExport.isFailure)
        assertTrue(emptyPreview.exceptionOrNull()?.message.orEmpty().contains("1つ以上"))

        authProvider.updateSession(
            SharedTagAuthSession(authUserId = "user-a", accessToken = "token"),
        )
        val sharedTagId = db.tagDao().insertTag(
            TagEntity(
                name = "共有",
                createdAt = 1L,
                scope = SharedTagScope.SYNCED,
                authUserId = "user-a",
                remoteTagId = "remote-shared-only",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        val sharedSelection = runCatching {
            repository.loadChatGptExportPreview(setOf(sharedTagId))
        }
        assertTrue(sharedSelection.isFailure)
        assertTrue(sharedSelection.exceptionOrNull()?.message.orEmpty().contains("自作タグだけ"))

        val localTagId = db.tagDao().insertTag(TagEntity(name = "対象なし", createdAt = 2L))
        val archivedId = insertEntry(
            normalizedUrl = "https://example.com/no-eligible-target",
            recordState = RecordState.ARCHIVED,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 20L,
            archivedAt = 21L,
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = localTagId, entryId = archivedId))

        val preview = repository.loadChatGptExportPreview(setOf(localTagId))
        assertTrue(preview.entries.isEmpty())
        assertEquals(1, preview.excludedCount)
        val zeroTargetExport = runCatching {
            repository.prepareChatGptExport(setOf(localTagId), preview.snapshotToken)
        }
        assertTrue(zeroTargetExport.isFailure)
        assertTrue(zeroTargetExport.exceptionOrNull()?.message.orEmpty().contains("URLがありません"))
    }

    @Test
    fun chatGptExport_redactsEveryOutwardUserStringAndHashesSanitizedUrl() = runBlocking {
        val secret = "RINBAMSECRET1234567890"
        val boundarySecret = "BOUNDARYSECRET_987654321"
        val unknownSecret = "MY_PRIVATE_CODE_ABC"
        val supabaseHost = "rinbamprojectsecret.supabase.co"
        val jwt = "eyJabcdefghijklmnopqrst.abcdefghijklmnopqrstuv.abcdefghijkl"
        val email = "rinbam-private@example.com"
        val phone = "+81 90 1234 5678"
        val fullWidthPhone = "+８１ ９０ １２３４ ５６７８"
        val localPath = "/Users/rinbam/private-secret.txt"
        val authorizationValue = "AUTHORIZATIONVALUE123456"
        val cookieValue = "COOKIEVALUE123456789"
        val basicAuthorizationValue = "dXNlcjpwYXNzd29yZA=="
        val sessionCookieValue = "SUPERSECRET123456"
        val equalsAuthorizationValue = "RVFVQUxTOnNlY3JldA=="
        val equalsCookieValue = "EQUALSCOOKIESECRET456"
        val jsonAuthorizationValue = "SlNPTjpzZWNyZXQ="
        val jsonCookieValue = "JSONCOOKIESECRET789"
        val foldedAuthorizationValue = "Rk9MREVEOmNyZWRlbnRpYWw="
        val foldedCookieValue = "FOLDEDCOOKIESECRET012"
        val encodedTokenValue = "ENCODEDURLTOKEN123456"
        val providerTokenValue = "sk-proj-provider-token-1234567890"
        db.openHelper.writableDatabase.execSQL(
            "UPDATE collections SET name = ? WHERE id = 1",
            arrayOf("collection secret=$secret"),
        )
        val selectedTagId = db.tagDao().insertTag(
            TagEntity(name = "tag secret=$secret", createdAt = 1L),
        )
        val normalizedUrl = "https://example.com/?access_token=$secret"
        val entryId = insertEntry(
            normalizedUrl = normalizedUrl,
            originalUrl = "https://example.com/original?refresh_token=$secret",
            displayUrl = "example.com/display?cookie=$secret",
            openUrl = "https://example.com/open?api_key=$secret",
            normalizedHost = supabaseHost,
            rawSourceHost = "http://$supabaseHost",
            recordState = RecordState.ACTIVE,
            memo = """
                memo password=$secret path=$localPath unknown=$unknownSecret
                Authorization: Basic $basicAuthorizationValue
                Cookie: theme=darkmode; session=$sessionCookieValue
                Authorization=Basic $equalsAuthorizationValue
                Cookie=theme=darkmode; session=$equalsCookieValue
                {"Authorization":"Basic $jsonAuthorizationValue"}
                {"Cookie":"theme=darkmode; session=$jsonCookieValue"}
                Authorization:
                  Basic $foldedAuthorizationValue
                Cookie: theme=darkmode;
                  session=$foldedCookieValue
                access_token%3D$encodedTokenValue provider=$providerTokenValue
            """.trimIndent(),
            serviceType = ServiceType.X,
            createdAt = 50L,
            userTitle = "title secret=$secret",
            fetchedTitle = "fetched token=$secret email=$email fullwidth=$fullWidthPhone",
            fetchedAuthorName = "author api_key=$secret phone=$phone",
            fetchedBody = "${"x".repeat(980)} password=$boundarySecret tail",
            bodySummary = "summary Authorization=$authorizationValue",
            description = "description Cookie=$cookieValue\nclient_secret=$secret jwt=$jwt",
            canonicalId = "token=$secret",
            thumbnailUrl = "https://example.com/thumb?access_token=$secret",
            badgeImageUrl = "https://example.com/badge?secret=$secret",
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = selectedTagId, entryId = entryId))

        val preview = repository.loadChatGptExportPreview(setOf(selectedTagId))
        val sanitizedUrl = preview.entries.single().normalizedUrl
        assertTrue(sanitizedUrl.contains("[redacted:token]"))
        assertFalse(sanitizedUrl.contains(secret, ignoreCase = true))
        assertEquals(
            sha256Hex(
                "rinbam-chatgpt-public-safe-v1:$sanitizedUrl:1970-01-01T00:00:00Z:0",
            ).take(32),
            preview.entries.single().publicSafeId,
        )
        assertFalse(
            preview.entries.single().publicSafeId ==
                sha256Hex(
                    "rinbam-chatgpt-public-safe-v1:$normalizedUrl:1970-01-01T00:00:00Z:0",
                ).take(32),
        )
        assertFalse(preview.selectedTagNames.joinToString().contains(secret))
        assertFalse(preview.entries.single().archiveEntryJson.contains(authorizationValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(cookieValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(fullWidthPhone))
        assertFalse(preview.entries.single().archiveEntryJson.contains(basicAuthorizationValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(sessionCookieValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(equalsAuthorizationValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(equalsCookieValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(jsonAuthorizationValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(jsonCookieValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(foldedAuthorizationValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(foldedCookieValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(encodedTokenValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(providerTokenValue))
        assertFalse(preview.entries.single().archiveEntryJson.contains(supabaseHost))
        assertTrue(preview.entries.single().archiveEntryJson.contains("[redacted:secret]"))
        assertFalse(preview.entries.single().archiveEntryJson.contains(boundarySecret))
        assertTrue(preview.entries.single().archiveEntryJson.contains("[redacted:supabase]"))
        assertTrue(preview.entries.single().archiveEntryJson.contains(unknownSecret))

        val archive = repository.prepareChatGptExport(setOf(selectedTagId), preview.snapshotToken)
        val files = unzip(archive.bytes)
        files.forEach { (path, content) ->
            assertFalse("ZIP path must not contain raw secret", path.contains(secret, ignoreCase = true))
            assertFalse("$path must not contain raw secret", content.contains(secret, ignoreCase = true))
            assertFalse("$path must not contain Supabase host", content.contains(supabaseHost, ignoreCase = true))
            assertFalse("$path must not contain JWT", content.contains(jwt))
            assertFalse("$path must not contain email", content.contains(email, ignoreCase = true))
            assertFalse("$path must not contain phone", content.contains(phone))
            assertFalse("$path must not contain full-width phone", content.contains(fullWidthPhone))
            assertFalse("$path must not contain local path", content.contains(localPath))
            assertFalse("$path must not contain Authorization value", content.contains(authorizationValue))
            assertFalse("$path must not contain Cookie value", content.contains(cookieValue))
            assertFalse("$path must not contain Basic Authorization value", content.contains(basicAuthorizationValue))
            assertFalse("$path must not contain later Cookie value", content.contains(sessionCookieValue))
            assertFalse("$path must not contain equals Authorization value", content.contains(equalsAuthorizationValue))
            assertFalse("$path must not contain equals Cookie value", content.contains(equalsCookieValue))
            assertFalse("$path must not contain JSON Authorization value", content.contains(jsonAuthorizationValue))
            assertFalse("$path must not contain JSON Cookie value", content.contains(jsonCookieValue))
            assertFalse("$path must not contain folded Authorization value", content.contains(foldedAuthorizationValue))
            assertFalse("$path must not contain folded Cookie value", content.contains(foldedCookieValue))
            assertFalse("$path must not contain URL-encoded token value", content.contains(encodedTokenValue))
            assertFalse("$path must not contain provider token value", content.contains(providerTokenValue))
        }
        assertTrue(files.getValue("entries.jsonl").contains("[redacted:token]"))
        assertTrue(files.getValue("entries.jsonl").contains("[redacted:secret]"))
        assertTrue(files.getValue("entries.jsonl").contains("[redacted:supabase]"))
        assertTrue(files.getValue("entries.jsonl").contains("[redacted:jwt]"))
        assertTrue(files.getValue("entries.jsonl").contains("[redacted:email]"))
        assertTrue(files.getValue("entries.jsonl").contains("[redacted:phone]"))
        assertTrue(files.getValue("entries.jsonl").contains("[redacted:local_path]"))
        assertTrue(files.getValue("entries.jsonl").contains("[redacted:secret]"))
        assertFalse(files.getValue("entries.jsonl").contains(boundarySecret))
        assertTrue(files.getValue("entries.jsonl").contains(unknownSecret))
    }

    @Test
    fun chatGptExport_distinguishesUrlsWhoseSecretValuesRedactToSameText() = runBlocking {
        val firstSecret = "FIRSTCOLLISIONSECRET"
        val secondSecret = "SECONDCOLLISIONSECRET"
        val tagId = db.tagDao().insertTag(TagEntity(name = "collision", createdAt = 1L))
        val firstId = insertEntry(
            normalizedUrl = "https://example.com/private?access_token=$firstSecret",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 80L,
            userTitle = "first",
        )
        val secondId = insertEntry(
            normalizedUrl = "https://example.com/private?access_token=$secondSecret",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 80L,
            userTitle = "second",
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = firstId))
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = secondId))

        val preview = repository.loadChatGptExportPreview(setOf(tagId))
        assertEquals(1, preview.entries.map { it.normalizedUrl }.toSet().size)
        assertEquals(2, preview.entries.map { it.publicSafeId }.toSet().size)
        val rawUrlsByTitle = mapOf(
            "first" to "https://example.com/private?access_token=$firstSecret",
            "second" to "https://example.com/private?access_token=$secondSecret",
        )
        val ordinalByRawUrl = rawUrlsByTitle.values
            .sortedBy(::sha256Hex)
            .withIndex()
            .associate { (ordinal, rawUrl) -> rawUrl to ordinal }
        preview.entries.forEach { entry ->
            val rawUrl = rawUrlsByTitle.getValue(entry.effectiveTitle)
            val expectedOrdinal = ordinalByRawUrl.getValue(rawUrl)
            assertEquals(
                sha256Hex(
                    "rinbam-chatgpt-public-safe-v1:${entry.normalizedUrl}:1970-01-01T00:00:00Z:$expectedOrdinal",
                ).take(32),
                entry.publicSafeId,
            )
        }

        val archive = repository.prepareChatGptExport(setOf(tagId), preview.snapshotToken)
        val files = unzip(archive.bytes)
        val markdownPaths = files.keys.filter { path -> path.startsWith("entries/") }
        preview.entries.forEach { entry ->
            assertTrue(markdownPaths.any { path -> path.contains(entry.publicSafeId.take(12)) })
        }
        files.forEach { (_, content) ->
            assertFalse(content.contains(firstSecret))
            assertFalse(content.contains(secondSecret))
        }
    }

    @Test
    fun chatGptExport_rejectsContentAndTargetChangesAfterPreview() = runBlocking {
        val tagId = db.tagDao().insertTag(TagEntity(name = "TOCTOU", createdAt = 1L))
        val entryId = insertEntry(
            normalizedUrl = "https://example.com/toctou",
            recordState = RecordState.ACTIVE,
            memo = "before",
            serviceType = ServiceType.WEB,
            createdAt = 60L,
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = entryId))

        val contentPreview = repository.loadChatGptExportPreview(setOf(tagId))
        val original = requireNotNull(db.urlEntryDao().findById(entryId))
        db.urlEntryDao().update(original.copy(memo = "after", updatedAt = 61L))
        val contentFailure = runCatching {
            repository.prepareChatGptExport(setOf(tagId), contentPreview.snapshotToken)
        }.exceptionOrNull()
        assertEquals(
            ChatGptExportFailureReason.SNAPSHOT_CHANGED,
            (contentFailure as ChatGptExportException).reason,
        )

        val targetPreview = repository.loadChatGptExportPreview(setOf(tagId))
        val updated = requireNotNull(db.urlEntryDao().findById(entryId))
        db.urlEntryDao().update(
            updated.copy(recordState = RecordState.ARCHIVED, archivedAt = 62L, updatedAt = 62L),
        )
        val targetFailure = runCatching {
            repository.prepareChatGptExport(setOf(tagId), targetPreview.snapshotToken)
        }.exceptionOrNull()
        assertEquals(
            ChatGptExportFailureReason.SNAPSHOT_CHANGED,
            (targetFailure as ChatGptExportException).reason,
        )
    }

    @Test
    fun chatGptExport_failsClosedWhenPreExportSyncFails() = runBlocking {
        val tagId = db.tagDao().insertTag(TagEntity(name = "sync", createdAt = 1L))
        val entryId = insertEntry(
            normalizedUrl = "https://example.com/sync-failure",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 70L,
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = entryId))
        val preview = repository.loadChatGptExportPreview(setOf(tagId))
        val failure = runCatching {
            createRepository(syncBeforeExport = { false }).prepareChatGptExport(
                setOf(tagId),
                preview.snapshotToken,
            )
        }.exceptionOrNull()

        assertEquals(
            ChatGptExportFailureReason.SYNC_FAILED,
            (failure as ChatGptExportException).reason,
        )
        assertFalse(failure.message.orEmpty().contains("false", ignoreCase = true))
    }

    @Test
    fun normalExport_keepsExistingLocalEntryAndTagIds() = runBlocking {
        val tagId = db.tagDao().insertTag(TagEntity(name = "通常", createdAt = 1L))
        val entryId = insertEntry(
            normalizedUrl = "https://example.com/normal-id-compatibility",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 30L,
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = entryId))

        val archive = repository.prepareExport(
            ExportRequest(
                scope = ExportScope.SINGLE_TAG,
                selectedTagIds = setOf(tagId),
                outputFormat = ExportOutputFormat.ZIP,
            ),
        )
        val payload = parseZipPayload(archive.bytes)

        assertTrue(payload.manifest.containsKey("selectedTagIds"))
        val entry = payload.entries.single()
        assertEquals(entryId.toString(), entry.stringValue("id"))
        assertEquals(tagId.toString(), entry.getValue("tags").jsonArray.single().jsonObject.stringValue("id"))
    }

    @Test
    fun observeAvailableTags_includesAssignedLocalTags() = runBlocking {
        val tagId = db.tagDao().insertTag(TagEntity(name = "あとで読む", createdAt = 1L))
        val entryId = insertEntry(
            normalizedUrl = "https://example.com/assigned-local",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 10L,
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = tagId, entryId = entryId))

        val names = withTimeout(2_000L) {
            repository.observeAvailableTags()
                .map { tags -> tags.map { it.name } }
                .first()
        }

        assertEquals(listOf("あとで読む"), names)
    }

    @Test
    fun loadAvailableTags_excludesZeroCountTagsAndIncludesLocalAndCurrentUserSharedTags() = runBlocking {
        authProvider.updateSession(
            SharedTagAuthSession(
                authUserId = "user-a",
                accessToken = "token",
            ),
        )
        val localTagId = db.tagDao().insertTag(TagEntity(name = "local", createdAt = 1L))
        db.tagDao().insertTag(TagEntity(name = "unused", createdAt = 2L))
        val sharedTagId = db.tagDao().insertTag(
            TagEntity(
                name = "shared mine",
                createdAt = 3L,
                scope = SharedTagScope.SYNCED,
                authUserId = "user-a",
                remoteTagId = "remote-a",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        db.tagDao().insertTag(
            TagEntity(
                name = "shared other",
                createdAt = 4L,
                scope = SharedTagScope.SYNCED,
                authUserId = "user-b",
                remoteTagId = "remote-b",
                syncStatus = SharedTagSyncStatus.SYNCED,
            ),
        )
        val localEntryId = insertEntry(
            normalizedUrl = "https://example.com/local-tagged",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 20L,
        )
        val sharedEntryId = insertEntry(
            normalizedUrl = "https://example.com/shared-tagged",
            recordState = RecordState.ACTIVE,
            memo = "",
            serviceType = ServiceType.WEB,
            createdAt = 21L,
            localProvenanceCount = 0,
        )
        db.tagDao().insertCrossRef(TagUrlCrossRef(tagId = localTagId, entryId = localEntryId))
        db.tagDao().upsertCrossRefs(
            listOf(
                TagUrlCrossRef(
                    tagId = sharedTagId,
                    entryId = sharedEntryId,
                    scope = SharedTagScope.SYNCED,
                    authUserId = "user-a",
                    remoteUrlId = "remote-url-a",
                    rawUrl = "https://example.com/shared-tagged",
                    normalizedUrl = "https://example.com/shared-tagged",
                    normalizationVersion = 1,
                    syncStatus = SharedTagSyncStatus.SYNCED,
                ),
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
        originalUrl: String = normalizedUrl,
        displayUrl: String = normalizedUrl.removePrefix("https://"),
        openUrl: String = normalizedUrl,
        normalizedHost: String = "example.com",
        rawSourceHost: String = "example.com",
        recordState: RecordState,
        memo: String,
        serviceType: ServiceType,
        createdAt: Long,
        archivedAt: Long? = null,
        localProvenanceCount: Int = 1,
        fetchedTitle: String? = null,
        fetchedAuthorName: String? = null,
        fetchedBody: String? = null,
        fetchedBodyKind: MetadataBodyKind? = null,
        bodySummary: String? = null,
        description: String? = null,
        thumbnailUrl: String? = null,
        badgeImageUrl: String? = null,
        canonicalId: String? = null,
        metadataFetchedAt: Long? = null,
        userTitle: String? = null,
        sharedReferenceCount: Int = 0,
        pendingDeletionUntil: Long? = null,
    ): Long {
        return db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = originalUrl,
                normalizedUrl = normalizedUrl,
                displayUrl = displayUrl,
                openUrl = openUrl,
                normalizedHost = normalizedHost,
                rawSourceHost = rawSourceHost,
                serviceType = serviceType,
                contentContext = ContentContext.STANDARD,
                userTitle = userTitle,
                fetchedTitle = fetchedTitle,
                fetchedAuthorName = fetchedAuthorName,
                fetchedBody = fetchedBody,
                fetchedBodyKind = fetchedBodyKind,
                bodySummary = bodySummary,
                description = description,
                memo = memo,
                thumbnailUrl = thumbnailUrl,
                badgeImageUrl = badgeImageUrl,
                canonicalId = canonicalId,
                metadataState = MetadataState.PENDING,
                metadataFetchedAt = metadataFetchedAt,
                recordState = recordState,
                localProvenanceCount = localProvenanceCount,
                sharedReferenceCount = sharedReferenceCount,
                createdAt = createdAt,
                updatedAt = createdAt,
                archivedAt = archivedAt,
                pendingDeletionUntil = pendingDeletionUntil,
            ),
        )
    }

    private fun createRepository(
        syncBeforeExport: suspend () -> Boolean = { true },
    ): DefaultExportRepository {
        return DefaultExportRepository(
            urlEntryDao = db.urlEntryDao(),
            tagDao = db.tagDao(),
            collectionDao = db.collectionDao(),
            authSessionProvider = authProvider,
            syncBeforeExport = syncBeforeExport,
            clock = clock,
            appVersion = "1.0-test",
        )
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
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
