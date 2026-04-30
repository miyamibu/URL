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
            )
            ExportOutputFormat.JSON -> buildJsonExportBytes(
                manifest = manifest,
                entryDocuments = entryDocuments,
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
    ): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                addZipEntry(
                    zip = zip,
                    path = "manifest.json",
                    content = json.encodeToString(manifest),
                )

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
    ): ByteArray {
        val payload = ExportJsonPayload(
            manifest = manifest,
            entries = entryDocuments,
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
            return ExportEntryDocument(
                id = entry.id,
                originalUrl = entry.originalUrl,
                normalizedUrl = entry.normalizedUrl,
                displayUrl = entry.displayUrl,
                openUrl = entry.openUrl,
                serviceType = entry.serviceType.name,
                contentContext = entry.contentContext.name,
                recordState = entry.recordState.name,
                createdAt = Instant.ofEpochMilli(entry.createdAt).toString(),
                updatedAt = Instant.ofEpochMilli(entry.updatedAt).toString(),
                archivedAt = entry.archivedAt?.let { Instant.ofEpochMilli(it).toString() },
                userTitle = entry.userTitle,
                fetchedTitle = entry.fetchedTitle,
                fetchedBody = entry.fetchedBody,
                bodySummary = entry.bodySummary,
                description = entry.description,
                memo = entry.memo,
                thumbnailUrl = entry.thumbnailUrl,
                badgeImageUrl = entry.badgeImageUrl,
                canonicalId = entry.canonicalId,
                normalizedHost = entry.normalizedHost,
                rawSourceHost = entry.rawSourceHost,
                metadataState = entry.metadataState.name,
                metadataError = entry.metadataError?.name,
                collection = collectionName,
                tags = tagSummaries,
                effectiveTitle = preferredTitle(entry),
            )
        }

        private fun preferredTitle(entry: UrlEntryEntity): String {
            return when {
                !entry.userTitle.isNullOrBlank() -> entry.userTitle
                !entry.fetchedTitle.isNullOrBlank() -> entry.fetchedTitle
                else -> entry.normalizedHost.ifBlank { "保存したリンク" }
            }.orEmpty()
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
        val originalUrl: String,
        val normalizedUrl: String,
        val displayUrl: String,
        val openUrl: String,
        val serviceType: String,
        val contentContext: String,
        val recordState: String,
        val createdAt: String,
        val updatedAt: String,
        val archivedAt: String? = null,
        val userTitle: String? = null,
        val fetchedTitle: String? = null,
        val fetchedBody: String? = null,
        val bodySummary: String? = null,
        val description: String? = null,
        val memo: String,
        val thumbnailUrl: String? = null,
        val badgeImageUrl: String? = null,
        val canonicalId: String? = null,
        val normalizedHost: String,
        val rawSourceHost: String,
        val metadataState: String,
        val metadataError: String? = null,
        val collection: String? = null,
        val tags: List<ExportTagSummary>,
        val effectiveTitle: String,
    ) {
        fun toMarkdown(): String {
            val lines = mutableListOf<String>()
            lines += "# $effectiveTitle"
            lines += ""
            lines += "- URL: $normalizedUrl"
            lines += "- Original URL: $originalUrl"
            lines += "- Display URL: $displayUrl"
            lines += "- Open URL: $openUrl"
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
            bodySummary?.let { lines += "- Summary: $it" }
            description?.let { lines += "- Description: $it" }
            if (memo.isNotBlank()) {
                lines += "- Memo: $memo"
            }
            thumbnailUrl?.let { lines += "- Thumbnail URL: $it" }
            badgeImageUrl?.let { lines += "- Badge Image URL: $it" }
            canonicalId?.let { lines += "- Canonical ID: $it" }
            lines += "- Metadata State: $metadataState"
            metadataError?.let { lines += "- Metadata Error: $it" }
            lines += "- Normalized Host: $normalizedHost"
            lines += "- Raw Source Host: $rawSourceHost"
            if (!fetchedBody.isNullOrBlank()) {
                lines += ""
                lines += "## Body"
                lines += ""
                lines += fetchedBody
            }
            return lines.joinToString(separator = "\n")
        }
    }

    @Serializable
    private data class ExportJsonPayload(
        val manifest: ExportManifest,
        val entries: List<ExportEntryDocument>,
    )

    private companion object {
        val EXPORT_FIELDS = listOf(
            "id",
            "originalUrl",
            "normalizedUrl",
            "displayUrl",
            "openUrl",
            "serviceType",
            "contentContext",
            "recordState",
            "createdAt",
            "updatedAt",
            "archivedAt",
            "userTitle",
            "fetchedTitle",
            "fetchedBody",
            "bodySummary",
            "description",
            "memo",
            "thumbnailUrl",
            "badgeImageUrl",
            "canonicalId",
            "normalizedHost",
            "rawSourceHost",
            "metadataState",
            "metadataError",
            "collection",
            "tags",
            "tagScopes",
        )
        val FILE_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val SLUG_SANITIZE_REGEX = Regex("[^a-z0-9]+")
    }
}

private fun List<TagWithCount>.toExportTagOptions(): List<ExportTagOption> {
    return map {
        ExportTagOption(
            id = it.id,
            name = it.name,
            scope = it.scope,
            urlCount = it.urlCount,
        )
    }
}
