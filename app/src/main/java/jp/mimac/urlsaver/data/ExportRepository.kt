package jp.mimac.urlsaver.data

import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.SharedTagRecord
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportScope {
    ALL,
    SINGLE_TAG,
    MULTIPLE_TAGS,
    SHARED_TAGS_ONLY,
}

enum class ExportRecordStateFilter {
    ACTIVE,
    ARCHIVED,
    BOTH,
}

enum class ExportOutputFormat(
    val fileExtension: String,
    val mimeType: String,
) {
    ZIP(
        fileExtension = "zip",
        mimeType = "application/zip",
    ),
    JSON(
        fileExtension = "json",
        mimeType = "application/json",
    ),
}

data class ExportRequest(
    val scope: ExportScope,
    val selectedTagIds: Set<Long> = emptySet(),
    val recordStateFilter: ExportRecordStateFilter = ExportRecordStateFilter.BOTH,
    val serviceType: ServiceType? = null,
    val onlyWithMemo: Boolean = false,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val outputFormat: ExportOutputFormat = ExportOutputFormat.ZIP,
)

data class ExportTagOption(
    val id: Long,
    val name: String,
    val scope: SharedTagScope,
    val urlCount: Int,
)

data class PreparedExportArchive(
    val fileName: String,
    val bytes: ByteArray,
    val entryCount: Int,
    val mimeType: String,
)

interface ExportRepository {
    suspend fun loadAvailableTags(): List<ExportTagOption>
    fun observeAvailableTags(): Flow<List<ExportTagOption>>
    suspend fun prepareExport(request: ExportRequest): PreparedExportArchive
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultExportRepository(
    private val urlEntryDao: UrlEntryDao,
    private val tagDao: TagDao,
    private val collectionDao: CollectionDao,
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val syncBeforeExport: suspend () -> Boolean = { true },
    private val clock: AppClock,
    private val appVersion: String,
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    },
    private val jsonLine: Json = Json {
        encodeDefaults = true
    },
) : ExportRepository {

    override suspend fun loadAvailableTags(): List<ExportTagOption> {
        val authUserId = authSessionProvider.session.value?.authUserId
        return tagDao.getVisibleTagsWithCount(authUserId).toExportTagOptions()
    }

    override fun observeAvailableTags(): Flow<List<ExportTagOption>> {
        return authSessionProvider.session.flatMapLatest { session ->
            tagDao.observeVisibleTagsWithCount(session?.authUserId)
        }.map { tags ->
            tags.toExportTagOptions()
        }
    }

    override suspend fun prepareExport(request: ExportRequest): PreparedExportArchive {
        validate(request)
        syncBeforeExport()

        val authUserId = authSessionProvider.session.value?.authUserId
        val collectionsById = collectionDao.loadCollections().associateBy { it.id }
        val availableTags = tagDao.getVisibleTagsWithCount(authUserId)
        val availableTagNamesById = availableTags.associate { it.id to it.name }

        val selectedTagNames = request.selectedTagIds.mapNotNull(availableTagNamesById::get)
        val entries = urlEntryDao.loadAllEntries()
            .map { entry ->
                val tags = tagDao.getVisibleTagsForEntry(entry.id, authUserId)
                ExportSourceEntry(
                    entry = entry,
                    tags = tags,
                    collectionName = collectionsById[entry.collectionId]?.name,
                )
            }
            .filter { source -> source.matches(request) }

        val createdAt = clock.nowEpochMillis()
        val exportedAtIso = Instant.ofEpochMilli(createdAt).toString()
        val entryDocuments = entries.map { source ->
            source.toDocument()
        }
        val redactionReport = ExportRedactionReport.from(
            generatedAt = exportedAtIso,
            documents = entryDocuments,
        )

        val manifest = ExportManifest(
            generatedAt = exportedAtIso,
            appVersion = appVersion,
            entryCount = entryDocuments.size,
            exportScope = request.scope.name,
            selectedTagIds = request.selectedTagIds.toList().sorted(),
            selectedTagNames = selectedTagNames.sorted(),
            recordStateFilter = request.recordStateFilter.name,
            serviceFilter = request.serviceType?.name,
            onlyWithMemo = request.onlyWithMemo,
            dateFrom = request.dateFrom?.toString(),
            dateTo = request.dateTo?.toString(),
            fields = EXPORT_FIELDS,
        )

        val bytes = when (request.outputFormat) {
            ExportOutputFormat.ZIP -> buildZipExportBytes(
                manifest = manifest,
                entryDocuments = entryDocuments,
                redactionReport = redactionReport,
            )
            ExportOutputFormat.JSON -> buildJsonExportBytes(
                manifest = manifest,
                entryDocuments = entryDocuments,
                redactionReport = redactionReport,
            )
        }

        return PreparedExportArchive(
            fileName = buildExportFileName(
                createdAt = createdAt,
                outputFormat = request.outputFormat,
            ),
            bytes = bytes,
            entryCount = entryDocuments.size,
            mimeType = request.outputFormat.mimeType,
        )
    }

    private fun validate(request: ExportRequest) {
        when (request.scope) {
            ExportScope.ALL, ExportScope.SHARED_TAGS_ONLY -> Unit
            ExportScope.SINGLE_TAG -> require(request.selectedTagIds.size == 1) {
                "単一タグエクスポートでは1つのタグを選択してください。"
            }
            ExportScope.MULTIPLE_TAGS -> require(request.selectedTagIds.isNotEmpty()) {
                "複数タグエクスポートでは1つ以上のタグを選択してください。"
            }
        }
        require(
            request.dateFrom == null || request.dateTo == null || !request.dateFrom.isAfter(request.dateTo),
        ) {
            "開始日は終了日以前にしてください。"
        }
    }

    private fun buildExportFileName(
        createdAt: Long,
        outputFormat: ExportOutputFormat,
    ): String {
        val timestamp = FILE_TIMESTAMP_FORMAT.format(
            Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()),
        )
        return "urlsaver-export-$timestamp.${outputFormat.fileExtension}"
    }

    private fun buildZipExportBytes(
        manifest: ExportManifest,
        entryDocuments: List<ExportEntryDocument>,
        redactionReport: ExportRedactionReport,
    ): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                addZipEntry(
                    zip = zip,
                    path = "manifest.json",
                    content = json.encodeToString(manifest),
                )
                addZipEntry(zip, "schema.json", AI_SAFE_SCHEMA_JSON)
                addZipEntry(zip, "README_FOR_AI.md", buildReadmeForAi(manifest, redactionReport))
                addZipEntry(zip, "redaction_report.json", json.encodeToString(redactionReport))

                val jsonl = entryDocuments.joinToString(separator = "\n") { document ->
                    jsonLine.encodeToString(document)
                }
                addZipEntry(zip, "entries.jsonl", jsonl)

                entryDocuments.forEachIndexed { index, document ->
                    val stableName = buildEntryFileName(index = index + 1, document = document)
                    addZipEntry(
                        zip = zip,
                        path = "entries/$stableName.md",
                        content = document.toMarkdown(),
                    )
                }
            }
            output.toByteArray()
        }
    }

    private fun buildJsonExportBytes(
        manifest: ExportManifest,
        entryDocuments: List<ExportEntryDocument>,
        redactionReport: ExportRedactionReport,
    ): ByteArray {
        val payload = ExportJsonPayload(
            manifest = manifest,
            entries = entryDocuments,
            readmeForAi = buildReadmeForAi(manifest, redactionReport),
            redactionReport = redactionReport,
        )
        return json.encodeToString(payload).toByteArray(Charsets.UTF_8)
    }

    private fun buildEntryFileName(index: Int, document: ExportEntryDocument): String {
        val titleSeed = document.effectiveTitle.ifBlank { document.normalizedHost.ifBlank { "entry" } }
        val slug = titleSeed
            .lowercase()
            .replace(SLUG_SANITIZE_REGEX, "-")
            .trim('-')
            .take(48)
            .ifBlank { "entry" }
        return "${index.toString().padStart(4, '0')}-${document.id}-$slug"
    }

    private fun addZipEntry(
        zip: ZipOutputStream,
        path: String,
        content: String,
    ) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private data class ExportSourceEntry(
        val entry: UrlEntryEntity,
        val tags: List<SharedTagRecord>,
        val collectionName: String?,
    ) {
        fun matches(request: ExportRequest): Boolean {
            val matchesScope = when (request.scope) {
                ExportScope.ALL -> entry.localProvenanceCount > 0 || tags.any { it.scope == SharedTagScope.SYNCED }
                ExportScope.SHARED_TAGS_ONLY -> tags.any { it.scope == SharedTagScope.SYNCED }
                ExportScope.SINGLE_TAG -> tags.any { it.id in request.selectedTagIds } &&
                    (entry.localProvenanceCount > 0 || tags.any { it.scope == SharedTagScope.SYNCED })
                ExportScope.MULTIPLE_TAGS -> tags.any { it.id in request.selectedTagIds } &&
                    (entry.localProvenanceCount > 0 || tags.any { it.scope == SharedTagScope.SYNCED })
            }
            if (!matchesScope) return false

            val matchesState = when (request.recordStateFilter) {
                ExportRecordStateFilter.ACTIVE -> entry.recordState == jp.mimac.urlsaver.domain.RecordState.ACTIVE
                ExportRecordStateFilter.ARCHIVED -> entry.recordState == jp.mimac.urlsaver.domain.RecordState.ARCHIVED
                ExportRecordStateFilter.BOTH -> {
                    entry.recordState == jp.mimac.urlsaver.domain.RecordState.ACTIVE ||
                        entry.recordState == jp.mimac.urlsaver.domain.RecordState.ARCHIVED
                }
            }
            if (!matchesState) return false

            if (request.serviceType != null && entry.serviceType != request.serviceType) return false
            if (request.onlyWithMemo && entry.memo.isBlank()) return false

            val createdDate = Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
            if (request.dateFrom != null && createdDate.isBefore(request.dateFrom)) return false
            if (request.dateTo != null && createdDate.isAfter(request.dateTo)) return false

            return true
        }

        fun toDocument(): ExportEntryDocument {
            val tagSummaries = tags.map {
                ExportTagSummary(
                    id = it.id,
                    name = it.name,
                    scope = it.scope.name,
                )
            }
            val hasSharedTag = tags.any { it.scope == SharedTagScope.SYNCED }
            val aiExclusionReasons = buildList {
                if (hasSharedTag) add("shared_tag_default_excluded")
                if (entry.recordState == jp.mimac.urlsaver.domain.RecordState.ARCHIVED) add("archived_default_excluded")
                if (entry.recordState == jp.mimac.urlsaver.domain.RecordState.PENDING_DELETE ||
                    entry.pendingDeletionUntil != null
                ) {
                    add("pending_delete_excluded")
                }
            }
            val bodySummary = redact(entry.bodySummary)
            val bodyExcerpt = redact(entry.fetchedBody?.let { clipText(it, BODY_EXCERPT_MAX_CHARS) })
            val description = redact(entry.description)
            val memoExcerpt = redact(entry.memo.takeIf { it.isNotBlank() }?.let { clipText(it, MEMO_EXCERPT_MAX_CHARS) })
            val redactionApplied = (
                bodySummary.redactions +
                    bodyExcerpt.redactions +
                    description.redactions +
                    memoExcerpt.redactions
                ).toSortedSet().toList()
            return ExportEntryDocument(
                id = entry.id,
                publicSafeId = publicSafeId(entry.id, entry.normalizedUrl),
                originalUrl = entry.originalUrl,
                normalizedUrl = entry.normalizedUrl,
                displayUrl = entry.displayUrl,
                openUrl = entry.openUrl,
                providerPermalink = providerPermalink(entry),
                providerCanonicalId = entry.canonicalId,
                serviceType = entry.serviceType.name,
                contentContext = entry.contentContext.name,
                recordState = entry.recordState.name,
                createdAt = Instant.ofEpochMilli(entry.createdAt).toString(),
                updatedAt = Instant.ofEpochMilli(entry.updatedAt).toString(),
                archivedAt = entry.archivedAt?.let { Instant.ofEpochMilli(it).toString() },
                userTitle = entry.userTitle,
                fetchedTitle = entry.fetchedTitle,
                fetchedAuthorName = entry.fetchedAuthorName,
                fetchedBodyKind = entry.fetchedBodyKind?.name,
                bodySummary = bodySummary.value,
                bodyExcerpt = bodyExcerpt.value,
                description = description.value,
                memoExcerpt = memoExcerpt.value,
                thumbnailUrl = entry.thumbnailUrl,
                badgeImageUrl = entry.badgeImageUrl,
                canonicalId = entry.canonicalId,
                normalizedHost = entry.normalizedHost,
                rawSourceHost = entry.rawSourceHost,
                metadataState = entry.metadataState.name,
                metadataError = entry.metadataError?.name,
                metadataFetchedAt = entry.metadataFetchedAt?.let { Instant.ofEpochMilli(it).toString() },
                metadataSource = metadataSource(entry),
                savedSnapshotNotice = savedSnapshotNotice(entry),
                collection = collectionName,
                tags = tagSummaries,
                effectiveTitle = preferredTitle(entry),
                sharedTagBoundary = if (hasSharedTag) "contains_shared_tag" else "local_or_untagged",
                aiEligible = aiExclusionReasons.isEmpty(),
                aiExclusionReason = aiExclusionReasons,
                redactionApplied = redactionApplied,
            )
        }

        private fun preferredTitle(entry: UrlEntryEntity): String {
            return when {
                !entry.userTitle.isNullOrBlank() -> entry.userTitle
                !entry.fetchedTitle.isNullOrBlank() -> entry.fetchedTitle
                else -> entry.normalizedHost.ifBlank { "保存したリンク" }
            }.orEmpty()
        }

        private fun providerPermalink(entry: UrlEntryEntity): String {
            return when (entry.serviceType) {
                ServiceType.YOUTUBE -> entry.canonicalId?.takeIf { it.isNotBlank() }
                    ?.let { "https://www.youtube.com/watch?v=$it" }
                    ?: entry.openUrl
                ServiceType.X -> entry.canonicalId?.takeIf { it.isNotBlank() }
                    ?.let { "https://x.com/i/web/status/$it" }
                    ?: entry.openUrl
                else -> entry.openUrl
            }
        }

        private fun metadataSource(entry: UrlEntryEntity): String {
            return when {
                entry.metadataFetchedAt != null -> "metadata_fetcher"
                !entry.fetchedTitle.isNullOrBlank() || !entry.bodySummary.isNullOrBlank() -> "metadata_cache"
                else -> "user_saved_url"
            }
        }

        private fun savedSnapshotNotice(entry: UrlEntryEntity): String? {
            val hasSavedMetadata = entry.metadataFetchedAt != null ||
                !entry.fetchedTitle.isNullOrBlank() ||
                !entry.fetchedAuthorName.isNullOrBlank() ||
                !entry.bodySummary.isNullOrBlank() ||
                !entry.description.isNullOrBlank() ||
                !entry.thumbnailUrl.isNullOrBlank()
            return if (hasSavedMetadata) AiTransparencyPolicy.SAVED_SNAPSHOT_NOTICE else null
        }
    }

    @Serializable
    private data class ExportManifest(
        val generatedAt: String,
        val appVersion: String,
        val entryCount: Int,
        val exportScope: String,
        val selectedTagIds: List<Long>,
        val selectedTagNames: List<String>,
        val recordStateFilter: String,
        val serviceFilter: String? = null,
        val onlyWithMemo: Boolean,
        val dateFrom: String? = null,
        val dateTo: String? = null,
        val fields: List<String>,
    )

    @Serializable
    private data class ExportTagSummary(
        val id: Long,
        val name: String,
        val scope: String,
    )

    @Serializable
    private data class ExportEntryDocument(
        val id: Long,
        val publicSafeId: String,
        val originalUrl: String,
        val normalizedUrl: String,
        val displayUrl: String,
        val openUrl: String,
        val providerPermalink: String,
        val providerCanonicalId: String? = null,
        val serviceType: String,
        val contentContext: String,
        val recordState: String,
        val createdAt: String,
        val updatedAt: String,
        val archivedAt: String? = null,
        val userTitle: String? = null,
        val fetchedTitle: String? = null,
        val fetchedAuthorName: String? = null,
        val fetchedBodyKind: String? = null,
        val bodySummary: String? = null,
        val bodyExcerpt: String? = null,
        val description: String? = null,
        val memoExcerpt: String? = null,
        val thumbnailUrl: String? = null,
        val badgeImageUrl: String? = null,
        val canonicalId: String? = null,
        val normalizedHost: String,
        val rawSourceHost: String,
        val metadataState: String,
        val metadataError: String? = null,
        val metadataFetchedAt: String? = null,
        val metadataSource: String,
        val savedSnapshotNotice: String? = null,
        val collection: String? = null,
        val tags: List<ExportTagSummary>,
        val effectiveTitle: String,
        val sharedTagBoundary: String,
        val aiEligible: Boolean,
        val aiExclusionReason: List<String>,
        val redactionApplied: List<String>,
    ) {
        fun toMarkdown(): String {
            val lines = mutableListOf<String>()
            lines += "# $effectiveTitle"
            lines += ""
            lines += "- Public Safe ID: $publicSafeId"
            lines += "- URL: $normalizedUrl"
            lines += "- Original URL: $originalUrl"
            lines += "- Display URL: $displayUrl"
            lines += "- Open URL: $openUrl"
            lines += "- Provider Permalink: $providerPermalink"
            providerCanonicalId?.let { lines += "- Provider Canonical ID: $it" }
            lines += "- Service: $serviceType"
            lines += "- Context: $contentContext"
            lines += "- State: $recordState"
            lines += "- Created At: $createdAt"
            lines += "- Updated At: $updatedAt"
            archivedAt?.let { lines += "- Archived At: $it" }
            collection?.let { lines += "- Collection: $it" }
            if (tags.isEmpty()) {
                lines += "- Tags: none"
            } else {
                lines += "- Tags: ${tags.joinToString { "${it.name} (${it.scope})" }}"
            }
            userTitle?.let { lines += "- User Title: $it" }
            fetchedTitle?.let { lines += "- Fetched Title: $it" }
            fetchedAuthorName?.let { lines += "- Author: $it" }
            fetchedBodyKind?.let { lines += "- Body Kind: $it" }
            bodySummary?.let { lines += "- Summary: $it" }
            bodyExcerpt?.let { lines += "- Body Excerpt: $it" }
            description?.let { lines += "- Description: $it" }
            memoExcerpt?.let { lines += "- Memo Excerpt: $it" }
            thumbnailUrl?.let { lines += "- Thumbnail URL: $it" }
            badgeImageUrl?.let { lines += "- Badge Image URL: $it" }
            canonicalId?.let { lines += "- Canonical ID: $it" }
            lines += "- Metadata State: $metadataState"
            metadataError?.let { lines += "- Metadata Error: $it" }
            metadataFetchedAt?.let { lines += "- Metadata Fetched At: $it" }
            lines += "- Metadata Source: $metadataSource"
            savedSnapshotNotice?.let { lines += "- Saved Snapshot Notice: $it" }
            lines += "- Normalized Host: $normalizedHost"
            lines += "- Raw Source Host: $rawSourceHost"
            lines += "- Shared Tag Boundary: $sharedTagBoundary"
            lines += "- AI Eligible: $aiEligible"
            if (aiExclusionReason.isNotEmpty()) {
                lines += "- AI Exclusion Reason: ${aiExclusionReason.joinToString()}"
            }
            if (redactionApplied.isNotEmpty()) {
                lines += "- Redaction Note: ${redactionApplied.joinToString()}"
            }
            return lines.joinToString(separator = "\n")
        }
    }

    @Serializable
    private data class ExportJsonPayload(
        val manifest: ExportManifest,
        val entries: List<ExportEntryDocument>,
        val readmeForAi: String,
        val redactionReport: ExportRedactionReport,
    )

    @Serializable
    private data class ExportRedactionReport(
        val generatedAt: String,
        val profile: String,
        val fetchedBodyExported: Boolean,
        val bodyExcerptMaxChars: Int,
        val memoExcerptMaxChars: Int,
        val entryCount: Int,
        val redactedEntryCount: Int,
        val redactionTypes: Map<String, Int>,
    ) {
        companion object {
            fun from(generatedAt: String, documents: List<ExportEntryDocument>): ExportRedactionReport {
                val redactionCounts = documents
                    .flatMap { it.redactionApplied }
                    .groupingBy { it }
                    .eachCount()
                    .toSortedMap()
                return ExportRedactionReport(
                    generatedAt = generatedAt,
                    profile = "ai-safe-v1",
                    fetchedBodyExported = false,
                    bodyExcerptMaxChars = BODY_EXCERPT_MAX_CHARS,
                    memoExcerptMaxChars = MEMO_EXCERPT_MAX_CHARS,
                    entryCount = documents.size,
                    redactedEntryCount = documents.count { it.redactionApplied.isNotEmpty() },
                    redactionTypes = redactionCounts,
                )
            }
        }
    }

    private companion object {
        val EXPORT_FIELDS = listOf(
            "id",
            "publicSafeId",
            "originalUrl",
            "normalizedUrl",
            "displayUrl",
            "openUrl",
            "providerPermalink",
            "providerCanonicalId",
            "serviceType",
            "contentContext",
            "recordState",
            "createdAt",
            "updatedAt",
            "archivedAt",
            "userTitle",
            "fetchedTitle",
            "fetchedAuthorName",
            "fetchedBodyKind",
            "bodySummary",
            "bodyExcerpt",
            "description",
            "memoExcerpt",
            "thumbnailUrl",
            "badgeImageUrl",
            "canonicalId",
            "normalizedHost",
            "rawSourceHost",
            "metadataState",
            "metadataError",
            "metadataFetchedAt",
            "metadataSource",
            "savedSnapshotNotice",
            "collection",
            "tags",
            "tagScopes",
            "sharedTagBoundary",
            "aiEligible",
            "aiExclusionReason",
            "redactionApplied",
        )
        const val BODY_EXCERPT_MAX_CHARS = 1_000
        const val MEMO_EXCERPT_MAX_CHARS = 1_000
        val FILE_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val SLUG_SANITIZE_REGEX = Regex("[^a-z0-9]+")
        val EMAIL_REGEX = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        val PHONE_REGEX = Regex("""(?<!\d)(?:\+?\d[\d\s().-]{8,}\d)(?!\d)""")
        val TOKEN_REGEX = Regex("""(?i)\b(?:refresh_token|access_token|service_role|sb_secret|invite[_-]?token|token)\s*[:=]\s*["']?[A-Za-z0-9._~+/=-]{8,}""")
        val SUPABASE_REGEX = Regex("""https://[a-z0-9-]+\.supabase\.co|eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}""")
        val LOCAL_PATH_REGEX = Regex("""(?:/Users/|/private/var/|file://)[^\s)]+""")
        const val AI_SAFE_SCHEMA_JSON = """
{
  "schemaVersion": "ai-safe-v1",
  "fetchedBodyDefault": "excluded",
  "requiredFiles": ["manifest.json", "entries.jsonl", "schema.json", "README_FOR_AI.md", "redaction_report.json"],
  "entryRequiredFields": ["publicSafeId", "originalUrl", "normalizedUrl", "openUrl", "effectiveTitle", "recordState", "aiEligible", "sharedTagBoundary", "redactionApplied"],
  "privacyDefaults": {
    "sharedTags": "excluded_from_ai_by_default",
    "pendingDelete": "excluded_from_ai",
    "archived": "excluded_from_ai_by_default",
    "body": "summary_or_excerpt_only"
  }
}
"""

        fun publicSafeId(id: Long, normalizedUrl: String): String {
            val input = "android-ai-export-v1:$id:$normalizedUrl"
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }

        fun clipText(value: String, maxChars: Int): String {
            val trimmed = value.trim()
            return if (trimmed.length <= maxChars) trimmed else trimmed.take(maxChars - 1) + "…"
        }

        fun redact(value: String?): RedactedText {
            val input = value?.trim()?.takeIf { it.isNotBlank() } ?: return RedactedText(null, emptySet())
            var output = input
            val redactions = mutableSetOf<String>()
            fun apply(regex: Regex, label: String) {
                if (regex.containsMatchIn(output)) {
                    output = regex.replace(output, "[redacted:$label]")
                    redactions += label
                }
            }
            apply(EMAIL_REGEX, "email")
            apply(PHONE_REGEX, "phone")
            apply(TOKEN_REGEX, "token")
            apply(SUPABASE_REGEX, "supabase")
            apply(LOCAL_PATH_REGEX, "local_path")
            return RedactedText(output, redactions)
        }

        fun buildReadmeForAi(manifest: ExportManifest, report: ExportRedactionReport): String {
            return """
            # りんばむ AI-safe Export

            Generated at: ${manifest.generatedAt}
            App version: ${manifest.appVersion}
            Entry count: ${manifest.entryCount}

            This archive is intended for AI-assisted review of user-selected saved links.
            It is not a restore backup. Full fetched bodies are excluded by default.

            ## Files
            - `manifest.json`: export metadata and selected filters.
            - `entries.jsonl`: one AI-safe JSON document per saved link.
            - `entries/*.md`: readable Markdown summaries.
            - `schema.json`: compact schema contract.
            - `redaction_report.json`: redaction profile and counts.

            ## Privacy defaults
            - Shared-tag entries are marked `aiEligible=false` by default.
            - Pending-delete entries are marked `aiEligible=false`.
            - Archived entries are marked `aiEligible=false` unless a future explicit flow opts them in.
            - `bodyExcerpt` and `memoExcerpt` are capped at ${report.bodyExcerptMaxChars} and ${report.memoExcerptMaxChars} characters.
            - `savedSnapshotNotice` means title/author/summary/excerpt/thumbnail/metadata are saved-time data and may differ from the current live URL.
            - Raw fetched body, raw prompt text, tokens, local paths, and secrets are not intended to appear in this archive.
            """.trimIndent()
        }
    }
}

private data class RedactedText(
    val value: String?,
    val redactions: Set<String>,
)

private fun List<TagWithCount>.toExportTagOptions(): List<ExportTagOption> {
    return filter { it.urlCount > 0 }
        .map {
            ExportTagOption(
                id = it.id,
                name = it.name,
                scope = it.scope,
                urlCount = it.urlCount,
            )
        }
}
