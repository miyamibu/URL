import Foundation
import SQLite3

final class URLRepository: @unchecked Sendable {
    let database: SQLiteDatabase

    init(databaseURL: URL = SharedContainer.databaseURL()) throws {
        database = try SQLiteDatabase(databaseURL: databaseURL)
        try migrateIfNeeded()
    }

    func observeActiveSnapshot() throws -> [URLRecord] {
        try fetchEntries(
            whereClause: "local_provenance_count > 0 AND record_state = 'ACTIVE' ORDER BY created_at DESC"
        )
    }

    func observeArchiveSnapshot() throws -> [URLRecord] {
        try fetchEntries(
            whereClause: "local_provenance_count > 0 AND record_state = 'ARCHIVED' ORDER BY archived_at DESC, created_at DESC"
        )
    }

    func loadExportSnapshot() throws -> [URLRecord] {
        try fetchEntries(
            whereClause: """
            (local_provenance_count > 0 OR shared_reference_count > 0)
            AND record_state IN ('ACTIVE', 'ARCHIVED')
            ORDER BY
                CASE record_state
                    WHEN 'ACTIVE' THEN 0
                    WHEN 'ARCHIVED' THEN 1
                    ELSE 2
                END,
                COALESCE(archived_at, created_at) DESC,
                created_at DESC
            """
        )
    }

    func loadLocalTags() throws -> [LocalTagSummary] {
        try database.fetchMany(
            sql: """
            SELECT
                local_tags.id,
                local_tags.name,
                COUNT(CASE
                    WHEN url_entries.local_provenance_count > 0
                        AND url_entries.record_state = 'ACTIVE'
                    THEN 1
                END) AS active_url_count,
                local_tags.created_at,
                local_tags.updated_at
            FROM local_tags
            LEFT JOIN local_tag_entries
                ON local_tag_entries.tag_id = local_tags.id
            LEFT JOIN url_entries
                ON url_entries.id = local_tag_entries.entry_id
            GROUP BY local_tags.id
            ORDER BY local_tags.created_at DESC, local_tags.id DESC;
            """
        ) { statement in
            LocalTagSummary(
                id: sqlite3_column_int64(statement, 0),
                name: textColumn(statement, index: 1) ?? "",
                activeURLCount: Int(sqlite3_column_int(statement, 2)),
                createdAt: dateColumn(statement, index: 3) ?? Date(timeIntervalSince1970: 0),
                updatedAt: dateColumn(statement, index: 4) ?? Date(timeIntervalSince1970: 0)
            )
        }
    }

    func loadLocalTagsForEntry(entryID: Int64) throws -> [LocalTagSummary] {
        try database.fetchMany(
            sql: """
            SELECT
                local_tags.id,
                local_tags.name,
                COUNT(CASE
                    WHEN counted_entries.local_provenance_count > 0
                        AND counted_entries.record_state = 'ACTIVE'
                    THEN 1
                END) AS active_url_count,
                local_tags.created_at,
                local_tags.updated_at
            FROM local_tags
            INNER JOIN local_tag_entries selected_entries
                ON selected_entries.tag_id = local_tags.id
                AND selected_entries.entry_id = ?
            LEFT JOIN local_tag_entries counted_links
                ON counted_links.tag_id = local_tags.id
            LEFT JOIN url_entries counted_entries
                ON counted_entries.id = counted_links.entry_id
            GROUP BY local_tags.id
            ORDER BY local_tags.created_at DESC, local_tags.id DESC;
            """,
            binds: [sql(entryID)]
        ) { statement in
            LocalTagSummary(
                id: sqlite3_column_int64(statement, 0),
                name: textColumn(statement, index: 1) ?? "",
                activeURLCount: Int(sqlite3_column_int(statement, 2)),
                createdAt: dateColumn(statement, index: 3) ?? Date(timeIntervalSince1970: 0),
                updatedAt: dateColumn(statement, index: 4) ?? Date(timeIntervalSince1970: 0)
            )
        }
    }

    func loadCollections() throws -> [CollectionSummary] {
        try database.fetchMany(
            sql: """
            SELECT
                collections.id,
                collections.name,
                collections.sort_order,
                COUNT(CASE
                    WHEN url_entries.local_provenance_count > 0
                        AND url_entries.record_state = 'ACTIVE'
                    THEN 1
                END) AS active_url_count,
                collections.created_at,
                collections.updated_at
            FROM collections
            LEFT JOIN url_entries
                ON url_entries.collection_id = collections.id
            GROUP BY collections.id
            ORDER BY collections.sort_order ASC, collections.id ASC;
            """
        ) { statement in
            CollectionSummary(
                id: sqlite3_column_int64(statement, 0),
                name: textColumn(statement, index: 1) ?? "",
                sortOrder: Int(sqlite3_column_int(statement, 2)),
                activeURLCount: Int(sqlite3_column_int(statement, 3)),
                createdAt: dateColumn(statement, index: 4) ?? Date(timeIntervalSince1970: 0),
                updatedAt: dateColumn(statement, index: 5) ?? Date(timeIntervalSince1970: 0)
            )
        }
    }

    func createCollection(name rawName: String) throws -> CollectionSummary? {
        guard let normalized = normalizeCollectionName(rawName) else { return nil }
        if let existing = try loadCollectionByNormalizedName(normalized.key) {
            return existing
        }
        let now = Date()
        let nextSortOrder = (try database.fetchInt("SELECT COALESCE(MAX(sort_order), 0) FROM collections;") ?? 0) + 1
        let id = try insert(
            sql: """
            INSERT INTO collections (name, normalized_name, sort_order, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?);
            """,
            binds: [
                sql(normalized.name),
                sql(normalized.key),
                sql(nextSortOrder),
                sql(now.timeIntervalSince1970),
                sql(now.timeIntervalSince1970),
            ]
        )
        return CollectionSummary(
            id: id,
            name: normalized.name,
            sortOrder: nextSortOrder,
            activeURLCount: 0,
            createdAt: now,
            updatedAt: now
        )
    }

    func assignCollection(entryID: Int64, collectionID: Int64) throws -> Bool {
        guard try loadEntry(id: entryID) != nil,
              try loadCollection(id: collectionID) != nil else {
            return false
        }
        try execute(
            """
            UPDATE url_entries
            SET collection_id = ?,
                updated_at = ?
            WHERE id = ?;
            """,
            binds: [sql(collectionID), sql(Date().timeIntervalSince1970), sql(entryID)]
        )
        return true
    }

    func reorderCollections(collectionIDs: [Int64]) throws -> Bool {
        let customCollections = try loadCollections().filter { $0.id != defaultCollectionID }
        let customIDs = Set(customCollections.map(\.id))
        guard Set(collectionIDs) == customIDs else { return false }
        let now = Date().timeIntervalSince1970
        try database.transaction {
            for (index, collectionID) in collectionIDs.enumerated() {
                try execute(
                    "UPDATE collections SET sort_order = ?, updated_at = ? WHERE id = ?;",
                    binds: [sql(index + 1), sql(now), sql(collectionID)]
                )
            }
        }
        return true
    }

    func deleteCollection(id collectionID: Int64) throws -> Bool {
        guard let collection = try loadCollection(id: collectionID),
              collection.id != defaultCollectionID,
              collection.name != defaultCollectionName else {
            return false
        }
        try database.transaction {
            try execute(
                "UPDATE url_entries SET collection_id = ?, updated_at = ? WHERE collection_id = ?;",
                binds: [sql(defaultCollectionID), sql(Date().timeIntervalSince1970), sql(collectionID)]
            )
            if let normalized = normalizeLocalTagName(collection.name),
               let localTag = try loadLocalTagByNormalizedName(normalized.key) {
                try execute("DELETE FROM local_tag_entries WHERE tag_id = ?;", binds: [sql(localTag.id)])
                try execute("DELETE FROM local_tags WHERE id = ?;", binds: [sql(localTag.id)])
            }
            try execute("DELETE FROM collections WHERE id = ?;", binds: [sql(collectionID)])
        }
        return true
    }

    func loadLocalTagAssignments() throws -> [Int64: Set<Int64>] {
        let pairs = try database.fetchMany(
            sql: "SELECT entry_id, tag_id FROM local_tag_entries;"
        ) { statement in
            (
                entryID: sqlite3_column_int64(statement, 0),
                tagID: sqlite3_column_int64(statement, 1)
            )
        }
        var assignments: [Int64: Set<Int64>] = [:]
        for pair in pairs {
            assignments[pair.entryID, default: []].insert(pair.tagID)
        }
        return assignments
    }

    func createLocalTag(name rawName: String) throws -> LocalTagSummary? {
        guard let normalized = normalizeLocalTagName(rawName) else { return nil }
        let now = Date()
        do {
            let id = try insert(
                sql: """
                INSERT INTO local_tags (name, normalized_name, created_at, updated_at)
                VALUES (?, ?, ?, ?);
                """,
                binds: [
                    sql(normalized.name),
                    sql(normalized.key),
                    sql(now.timeIntervalSince1970),
                    sql(now.timeIntervalSince1970),
                ]
            )
            return LocalTagSummary(
                id: id,
                name: normalized.name,
                activeURLCount: 0,
                createdAt: now,
                updatedAt: now
            )
        } catch {
            return try loadLocalTagByNormalizedName(normalized.key)
        }
    }

    func assignLocalTag(entryID: Int64, tagID: Int64) throws -> Bool {
        guard try loadEntry(id: entryID) != nil,
              try loadLocalTag(id: tagID) != nil else {
            return false
        }
        let now = Date().timeIntervalSince1970
        try execute(
            """
            INSERT OR IGNORE INTO local_tag_entries (tag_id, entry_id, created_at)
            VALUES (?, ?, ?);
            """,
            binds: [sql(tagID), sql(entryID), sql(now)]
        )
        return true
    }

    func removeLocalTag(entryID: Int64, tagID: Int64) throws -> Bool {
        try execute(
            "DELETE FROM local_tag_entries WHERE tag_id = ? AND entry_id = ?;",
            binds: [sql(tagID), sql(entryID)]
        )
        return true
    }

    func deleteLocalTag(id tagID: Int64) throws -> Bool {
        guard try loadLocalTag(id: tagID) != nil else {
            return false
        }
        try database.transaction {
            try execute(
                "DELETE FROM local_tag_entries WHERE tag_id = ?;",
                binds: [sql(tagID)]
            )
            try execute(
                "DELETE FROM local_tags WHERE id = ?;",
                binds: [sql(tagID)]
            )
        }
        return true
    }

    func exportLocalTag(tagID: Int64) throws -> TagSharePayload? {
        guard let tag = try loadLocalTag(id: tagID) else { return nil }
        let entries = try database.fetchMany(
            sql: """
            SELECT url_entries.normalized_url, url_entries.user_title, url_entries.fetched_title, url_entries.memo
            FROM url_entries
            INNER JOIN local_tag_entries ON local_tag_entries.entry_id = url_entries.id
            WHERE local_tag_entries.tag_id = ?
              AND url_entries.local_provenance_count > 0
              AND url_entries.record_state IN ('ACTIVE', 'ARCHIVED')
            ORDER BY url_entries.created_at DESC;
            """,
            binds: [sql(tagID)]
        ) { statement in
            TagShareURL(
                url: textColumn(statement, index: 0) ?? "",
                title: textColumn(statement, index: 1) ?? textColumn(statement, index: 2),
                memo: (textColumn(statement, index: 3) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? nil
                    : textColumn(statement, index: 3)
            )
        }
        return TagSharePayload(
            urlsaverVersion: 1,
            tag: tag.name,
            exportedAt: Int64(Date().timeIntervalSince1970 * 1000),
            urls: entries.filter { !$0.url.isEmpty }
        )
    }

    func importLocalTagPayload(_ payload: TagSharePayload) throws -> TagImportResult {
        guard payload.urlsaverVersion == 1,
              let normalized = normalizeLocalTagName(payload.tag) else {
            return TagImportResult(
                tagID: -1,
                tagName: payload.tag,
                created: 0,
                merged: 0,
                duplicateSkipped: 0,
                failed: payload.urls.count
            )
        }
        let tag = try createLocalTag(name: normalized.name) ?? loadLocalTagByNormalizedName(normalized.key)
        guard let tag else {
            return TagImportResult(
                tagID: -1,
                tagName: normalized.name,
                created: 0,
                merged: 0,
                duplicateSkipped: 0,
                failed: payload.urls.count
            )
        }

        var created = 0
        var merged = 0
        var duplicateSkipped = 0
        var failed = 0
        for item in payload.urls {
            let result = try saveFromResolvedURL(item.url)
            guard let entryID = result.entryID else {
                failed += 1
                continue
            }
            let wasAlreadyAssigned = try hasLocalTagAssignment(entryID: entryID, tagID: tag.id)
            _ = try assignLocalTag(entryID: entryID, tagID: tag.id)
            switch result.result {
            case .created, .restoredFromPendingDelete:
                created += 1
                if let title = item.title, !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    _ = try saveUserTitle(entryID: entryID, rawTitle: title)
                }
                if let memo = item.memo, !memo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    _ = try saveMemo(entryID: entryID, rawMemo: memo)
                }
            case .duplicateActive, .duplicateArchived:
                if wasAlreadyAssigned {
                    duplicateSkipped += 1
                } else {
                    merged += 1
                }
            case .batchProcessed, .inputTooLarge, .invalidURL, .noURLFound, .saveFailed:
                failed += 1
            }
        }
        return TagImportResult(
            tagID: tag.id,
            tagName: tag.name,
            created: created,
            merged: merged,
            duplicateSkipped: duplicateSkipped,
            failed: failed
        )
    }

    func loadEntry(id: Int64) throws -> URLRecord? {
        let statementSQL = "SELECT * FROM url_entries WHERE id = ? LIMIT 1;"
        return try fetchOne(sql: statementSQL, binds: [sql(id)])
    }

    func loadPendingDeleteEntries() throws -> [URLRecord] {
        try fetchEntries(
            whereClause: "record_state = 'PENDING_DELETE' AND pending_deletion_until IS NOT NULL ORDER BY pending_deletion_until ASC"
        )
    }

    func loadEntriesNeedingMetadata(limit: Int = 20) throws -> [URLRecord] {
        try fetchEntries(
            whereClause: """
            record_state != 'PENDING_DELETE'
            AND (
                metadata_state = 'PENDING'
                OR metadata_state = 'FAILED'
                OR (
                    service_type = 'youtube'
                    AND (
                        fetched_author_name IS NULL
                        OR TRIM(fetched_author_name) = ''
                        OR badge_image_url IS NULL
                        OR (
                            badge_image_url NOT LIKE '%yt3.ggpht.com%'
                            AND badge_image_url NOT LIKE '%yt3.googleusercontent.com%'
                        )
                    )
                )
            )
            ORDER BY created_at DESC
            LIMIT \(limit)
            """
        )
    }

    func saveFromManualInput(_ input: String, localTagIDs: [Int64] = []) throws -> SaveResult {
        switch URLRules.extractForManualInput(input) {
        case .found(let url):
            let result = try saveFromURL(url)
            try assignLocalTagsAfterSave(result: result, localTagIDs: localTagIDs)
            return result
        case .inputTooLarge:
            return SaveResult(result: .inputTooLarge)
        case .invalidURL:
            return SaveResult(result: .invalidURL)
        case .noURLFound:
            return SaveResult(result: .noURLFound)
        }
    }

    func saveFromResolvedURL(_ url: String, localTagIDs: [Int64] = []) throws -> SaveResult {
        let result = try saveFromURL(url)
        try assignLocalTagsAfterSave(result: result, localTagIDs: localTagIDs)
        return result
    }

    func archive(entryID: Int64) throws -> Bool {
        guard let entry = try loadEntry(id: entryID), entry.recordState == .active else {
            return false
        }
        let now = Date()
        try execute(
            """
            UPDATE url_entries
            SET record_state = 'ARCHIVED',
                archived_at = ?,
                pending_deletion_until = NULL,
                updated_at = ?
            WHERE id = ?;
            """,
            binds: [sql(now.timeIntervalSince1970), sql(now.timeIntervalSince1970), sql(entryID)]
        )
        return true
    }

    func unarchive(entryID: Int64) throws -> Bool {
        guard let entry = try loadEntry(id: entryID), entry.recordState == .archived else {
            return false
        }
        let now = Date()
        try execute(
            """
            UPDATE url_entries
            SET record_state = 'ACTIVE',
                archived_at = NULL,
                pending_deletion_until = NULL,
                updated_at = ?
            WHERE id = ?;
            """,
            binds: [sql(now.timeIntervalSince1970), sql(entryID)]
        )
        return true
    }

    func markPendingDelete(entryID: Int64, gracePeriod: TimeInterval = 5) throws -> Date? {
        guard let entry = try loadEntry(id: entryID), entry.recordState == .active else {
            return nil
        }
        let now = Date()
        let pendingUntil = now.addingTimeInterval(gracePeriod)
        try execute(
            """
            UPDATE url_entries
            SET record_state = 'PENDING_DELETE',
                pending_deletion_until = ?,
                archived_at = NULL,
                updated_at = ?
            WHERE id = ?;
            """,
            binds: [sql(pendingUntil.timeIntervalSince1970), sql(now.timeIntervalSince1970), sql(entryID)]
        )
        return pendingUntil
    }

    func finalizePendingDelete(entryID: Int64, now: Date = Date()) throws {
        guard let entry = try loadEntry(id: entryID),
              entry.recordState == .pendingDelete,
              let due = entry.pendingDeletionUntil,
              due <= now else {
            return
        }
        if entry.sharedReferenceCount > 0 {
            try execute(
                """
                UPDATE url_entries
                SET local_provenance_count = 0,
                    record_state = 'ACTIVE',
                    pending_deletion_until = NULL,
                    archived_at = NULL,
                    updated_at = ?
                WHERE id = ?;
                """,
                binds: [sql(now.timeIntervalSince1970), sql(entryID)]
            )
            return
        }
        try execute("DELETE FROM url_entries WHERE id = ?;", binds: [sql(entryID)])
    }

    func cleanupExpiredPendingDeletes(now: Date = Date()) throws {
        let expiredEntries = try fetchEntries(
            whereClause: """
            record_state = 'PENDING_DELETE'
            AND pending_deletion_until IS NOT NULL
            AND pending_deletion_until <= \(now.timeIntervalSince1970)
            ORDER BY pending_deletion_until ASC
            """
        )
        for entry in expiredEntries {
            try finalizePendingDelete(entryID: entry.id, now: now)
        }
    }

    func restore(entryID: Int64) throws -> Bool {
        guard let entry = try loadEntry(id: entryID),
              entry.recordState == .pendingDelete || entry.recordState == .archived else {
            return false
        }
        let now = Date()
        try execute(
            """
            UPDATE url_entries
            SET record_state = 'ACTIVE',
                pending_deletion_until = NULL,
                archived_at = NULL,
                updated_at = ?
            WHERE id = ?;
            """,
            binds: [sql(now.timeIntervalSince1970), sql(entryID)]
        )
        return true
    }

    func saveUserTitle(entryID: Int64, rawTitle: String) throws -> (success: Bool, oldTitle: String?, tooLong: Bool) {
        guard let entry = try loadEntry(id: entryID) else {
            return (false, nil, false)
        }
        guard URLRules.isTitleLengthValid(rawTitle) else {
            return (false, nil, true)
        }
        let newTitle = URLRules.normalizeUserTitle(rawTitle)
        try execute(
            """
            UPDATE url_entries
            SET user_title = ?,
                updated_at = ?
            WHERE id = ?;
            """,
            binds: [sql(newTitle), sql(Date().timeIntervalSince1970), sql(entryID)]
        )
        return (true, entry.userTitle, false)
    }

    func restoreUserTitle(entryID: Int64, oldTitle: String?) throws -> Bool {
        guard try loadEntry(id: entryID) != nil else { return false }
        try execute(
            """
            UPDATE url_entries
            SET user_title = ?,
                updated_at = ?
            WHERE id = ?;
            """,
            binds: [sql(oldTitle), sql(Date().timeIntervalSince1970), sql(entryID)]
        )
        return true
    }

    func saveMemo(entryID: Int64, rawMemo: String) throws -> (success: Bool, tooLong: Bool) {
        guard try loadEntry(id: entryID) != nil else { return (false, false) }
        guard URLRules.isMemoLengthValid(rawMemo) else {
            return (false, true)
        }
        let memo = URLRules.normalizeMemo(rawMemo)
        try execute(
            """
            UPDATE url_entries
            SET memo = ?,
                updated_at = ?
            WHERE id = ?;
            """,
            binds: [sql(memo), sql(Date().timeIntervalSince1970), sql(entryID)]
        )
        return (true, false)
    }

    func retryMetadata(entryID: Int64) throws -> Bool {
        guard let entry = try loadEntry(id: entryID), entry.canRetryMetadata else {
            return false
        }
        try markMetadataPending(entryID: entryID, requestedAt: Date())
        return true
    }

    func refreshMetadata(entryID: Int64) throws -> Bool {
        guard try loadEntry(id: entryID) != nil else {
            return false
        }
        try markMetadataPending(entryID: entryID, requestedAt: Date())
        return true
    }

    func markMetadataPending(entryID: Int64, requestedAt: Date) throws {
        try execute(
            """
            UPDATE url_entries
            SET metadata_state = 'PENDING',
                metadata_error = NULL,
                metadata_requested_at = ?
            WHERE id = ?;
            """,
            binds: [sql(requestedAt.timeIntervalSince1970), sql(entryID)]
        )
    }

    func applyMetadataUpdate(entryID: Int64, metadata: MetadataUpdate) throws {
        try execute(
            """
            UPDATE url_entries
            SET fetched_title = ?,
                fetched_author_name = ?,
                fetched_body = ?,
                fetched_body_kind = ?,
                body_summary = ?,
                description = ?,
                thumbnail_url = ?,
                badge_image_url = COALESCE(?, badge_image_url),
                metadata_state = ?,
                metadata_fetched_at = ?,
                metadata_error = ?,
                canonical_id = ?,
                normalized_host = COALESCE(?, normalized_host),
                raw_source_host = COALESCE(?, raw_source_host)
            WHERE id = ?;
            """,
            binds: [
                sql(metadata.fetchedTitle),
                sql(metadata.fetchedAuthorName),
                sql(metadata.fetchedBody),
                sql(metadata.fetchedBodyKind?.rawValue),
                sql(metadata.bodySummary),
                sql(metadata.description),
                sql(metadata.thumbnailURL),
                sql(metadata.badgeImageURL),
                sql(metadata.metadataState.rawValue),
                sql(metadata.metadataFetchedAt?.timeIntervalSince1970),
                sql(metadata.metadataError?.rawValue),
                sql(metadata.canonicalID),
                sql(metadata.normalizedHost),
                sql(metadata.rawSourceHost),
                sql(entryID),
            ]
        )
    }

    private func saveFromURL(_ originalURL: String) throws -> SaveResult {
        guard let parsed = URLRules.parseURL(originalURL) else {
            return SaveResult(result: .invalidURL)
        }

        if let existing = try findExisting(normalizedURL: parsed.normalizedURL) {
            if existing.localProvenanceCount == 0 {
                let now = Date()
                try execute(
                    """
                    UPDATE url_entries
                    SET local_provenance_count = 1,
                        record_state = 'ACTIVE',
                        pending_deletion_until = NULL,
                        archived_at = NULL,
                        updated_at = ?
                    WHERE id = ?;
                    """,
                    binds: [sql(now.timeIntervalSince1970), sql(existing.id)]
                )
                return SaveResult(
                    result: .created,
                    entryID: existing.id,
                    normalizedURL: existing.normalizedURL,
                    shouldScheduleMetadata: existing.needsMetadataRetryAfterRestore
                )
            }

            switch existing.recordState {
            case .active:
                return SaveResult(result: .duplicateActive, entryID: existing.id, normalizedURL: existing.normalizedURL)
            case .archived:
                return SaveResult(result: .duplicateArchived, entryID: existing.id, normalizedURL: existing.normalizedURL)
            case .pendingDelete:
                let now = Date()
                try execute(
                    """
                    UPDATE url_entries
                    SET record_state = 'ACTIVE',
                        pending_deletion_until = NULL,
                        archived_at = NULL,
                        updated_at = ?
                    WHERE id = ?;
                    """,
                    binds: [sql(now.timeIntervalSince1970), sql(existing.id)]
                )
                return SaveResult(
                    result: .restoredFromPendingDelete,
                    entryID: existing.id,
                    normalizedURL: existing.normalizedURL,
                    shouldScheduleMetadata: existing.needsMetadataRetryAfterRestore
                )
            }
        }

        let now = Date()
        let insertSQL = """
        INSERT INTO url_entries (
            original_url,
            normalized_url,
            display_url,
            open_url,
            normalized_host,
            raw_source_host,
            service_type,
            content_context,
            metadata_state,
            metadata_requested_at,
            record_state,
            local_provenance_count,
            shared_reference_count,
            created_at,
            updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, 'ACTIVE', 1, 0, ?, ?);
        """
        let rowID = try insert(
            sql: insertSQL,
            binds: [
                sql(parsed.originalURL),
                sql(parsed.normalizedURL),
                sql(parsed.displayURL),
                sql(parsed.openURL),
                sql(parsed.normalizedHost),
                sql(parsed.rawSourceHost),
                sql(parsed.serviceType.rawValue),
                sql(parsed.contentContext.rawValue),
                sql(now.timeIntervalSince1970),
                sql(now.timeIntervalSince1970),
                sql(now.timeIntervalSince1970),
            ]
        )
        return SaveResult(
            result: .created,
            entryID: rowID,
            normalizedURL: parsed.normalizedURL,
            shouldScheduleMetadata: true
        )
    }

    private func findExisting(normalizedURL: String) throws -> URLRecord? {
        try fetchOne(
            sql: "SELECT * FROM url_entries WHERE normalized_url = ? LIMIT 1;",
            binds: [sql(normalizedURL)]
        )
    }

    private func loadLocalTag(id: Int64) throws -> LocalTagSummary? {
        try database.fetchOne(
            sql: """
            SELECT id, name, 0 AS active_url_count, created_at, updated_at
            FROM local_tags
            WHERE id = ?
            LIMIT 1;
            """,
            binds: [sql(id)]
        ) { statement in
            LocalTagSummary(
                id: sqlite3_column_int64(statement, 0),
                name: textColumn(statement, index: 1) ?? "",
                activeURLCount: Int(sqlite3_column_int(statement, 2)),
                createdAt: dateColumn(statement, index: 3) ?? Date(timeIntervalSince1970: 0),
                updatedAt: dateColumn(statement, index: 4) ?? Date(timeIntervalSince1970: 0)
            )
        }
    }

    private func loadLocalTagByNormalizedName(_ normalizedName: String) throws -> LocalTagSummary? {
        try database.fetchOne(
            sql: """
            SELECT id, name, 0 AS active_url_count, created_at, updated_at
            FROM local_tags
            WHERE normalized_name = ?
            LIMIT 1;
            """,
            binds: [sql(normalizedName)]
        ) { statement in
            LocalTagSummary(
                id: sqlite3_column_int64(statement, 0),
                name: textColumn(statement, index: 1) ?? "",
                activeURLCount: Int(sqlite3_column_int(statement, 2)),
                createdAt: dateColumn(statement, index: 3) ?? Date(timeIntervalSince1970: 0),
                updatedAt: dateColumn(statement, index: 4) ?? Date(timeIntervalSince1970: 0)
            )
        }
    }

    private func assignLocalTagsAfterSave(result: SaveResult, localTagIDs: [Int64]) throws {
        guard let entryID = result.entryID, !localTagIDs.isEmpty else { return }
        switch result.result {
        case .created, .duplicateActive, .duplicateArchived, .restoredFromPendingDelete:
            for tagID in Set(localTagIDs) {
                _ = try assignLocalTag(entryID: entryID, tagID: tagID)
            }
        case .batchProcessed, .inputTooLarge, .invalidURL, .noURLFound, .saveFailed:
            return
        }
    }

    private func hasLocalTagAssignment(entryID: Int64, tagID: Int64) throws -> Bool {
        let count = try database.fetchOne(
            sql: "SELECT COUNT(*) FROM local_tag_entries WHERE entry_id = ? AND tag_id = ?;",
            binds: [sql(entryID), sql(tagID)]
        ) { statement in
            Int(sqlite3_column_int(statement, 0))
        } ?? 0
        return count > 0
    }

    private func normalizeLocalTagName(_ rawName: String) -> (name: String, key: String)? {
        let trimmed = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 40 else { return nil }
        let name = trimmed
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        guard !name.isEmpty else { return nil }
        return (name, name.lowercased())
    }

    private func normalizeCollectionName(_ rawName: String) -> (name: String, key: String)? {
        let trimmed = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 40 else { return nil }
        let name = trimmed
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        guard !name.isEmpty else { return nil }
        return (name, name.lowercased())
    }

    private func loadCollection(id collectionID: Int64) throws -> CollectionSummary? {
        try database.fetchOne(
            sql: """
            SELECT id, name, sort_order, 0 AS active_url_count, created_at, updated_at
            FROM collections
            WHERE id = ?
            LIMIT 1;
            """,
            binds: [sql(collectionID)]
        ) { statement in
            CollectionSummary(
                id: sqlite3_column_int64(statement, 0),
                name: textColumn(statement, index: 1) ?? "",
                sortOrder: Int(sqlite3_column_int(statement, 2)),
                activeURLCount: Int(sqlite3_column_int(statement, 3)),
                createdAt: dateColumn(statement, index: 4) ?? Date(timeIntervalSince1970: 0),
                updatedAt: dateColumn(statement, index: 5) ?? Date(timeIntervalSince1970: 0)
            )
        }
    }

    private func loadCollectionByNormalizedName(_ normalizedName: String) throws -> CollectionSummary? {
        try database.fetchOne(
            sql: """
            SELECT id, name, sort_order, 0 AS active_url_count, created_at, updated_at
            FROM collections
            WHERE normalized_name = ?
            LIMIT 1;
            """,
            binds: [sql(normalizedName)]
        ) { statement in
            CollectionSummary(
                id: sqlite3_column_int64(statement, 0),
                name: textColumn(statement, index: 1) ?? "",
                sortOrder: Int(sqlite3_column_int(statement, 2)),
                activeURLCount: Int(sqlite3_column_int(statement, 3)),
                createdAt: dateColumn(statement, index: 4) ?? Date(timeIntervalSince1970: 0),
                updatedAt: dateColumn(statement, index: 5) ?? Date(timeIntervalSince1970: 0)
            )
        }
    }

    private func migrateIfNeeded() throws {
        let createSQL = """
        CREATE TABLE IF NOT EXISTS url_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            original_url TEXT NOT NULL,
            normalized_url TEXT NOT NULL UNIQUE,
            display_url TEXT NOT NULL,
            open_url TEXT NOT NULL,
            normalized_host TEXT NOT NULL,
            raw_source_host TEXT NOT NULL,
            service_type TEXT NOT NULL,
            content_context TEXT NOT NULL,
            user_title TEXT,
            fetched_title TEXT,
            fetched_body TEXT,
            fetched_body_kind TEXT,
            body_summary TEXT,
            description TEXT,
            memo TEXT NOT NULL DEFAULT '',
            thumbnail_url TEXT,
            canonical_id TEXT,
            metadata_state TEXT NOT NULL,
            metadata_error TEXT,
            metadata_requested_at REAL,
            metadata_fetched_at REAL,
            record_state TEXT NOT NULL,
            local_provenance_count INTEGER NOT NULL DEFAULT 1,
            shared_reference_count INTEGER NOT NULL DEFAULT 0,
            created_at REAL NOT NULL,
            updated_at REAL NOT NULL,
            archived_at REAL,
            pending_deletion_until REAL,
            badge_image_url TEXT,
            fetched_author_name TEXT,
            collection_id INTEGER NOT NULL DEFAULT 1
        );
        CREATE INDEX IF NOT EXISTS idx_url_entries_state ON url_entries(record_state);
        CREATE INDEX IF NOT EXISTS idx_url_entries_metadata ON url_entries(metadata_state);
        CREATE INDEX IF NOT EXISTS idx_url_entries_local_visibility ON url_entries(local_provenance_count, record_state);
        CREATE TABLE IF NOT EXISTS schema_flags (
            key TEXT PRIMARY KEY,
            applied_at REAL NOT NULL
        );
        CREATE TABLE IF NOT EXISTS local_tags (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            normalized_name TEXT NOT NULL UNIQUE,
            created_at REAL NOT NULL,
            updated_at REAL NOT NULL
        );
        CREATE TABLE IF NOT EXISTS local_tag_entries (
            tag_id INTEGER NOT NULL,
            entry_id INTEGER NOT NULL,
            created_at REAL NOT NULL,
            PRIMARY KEY (tag_id, entry_id),
            FOREIGN KEY (tag_id) REFERENCES local_tags(id) ON DELETE CASCADE,
            FOREIGN KEY (entry_id) REFERENCES url_entries(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_local_tag_entries_entry ON local_tag_entries(entry_id);
        CREATE TABLE IF NOT EXISTS collections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            normalized_name TEXT NOT NULL UNIQUE,
            sort_order INTEGER NOT NULL,
            created_at REAL NOT NULL,
            updated_at REAL NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_collections_sort_order ON collections(sort_order);
        """
        try executeBatch(createSQL)
        try ensureDefaultCollection()
        try database.addColumnIfMissing(
            table: "url_entries",
            column: "local_provenance_count",
            definition: "local_provenance_count INTEGER NOT NULL DEFAULT 1"
        )
        try database.addColumnIfMissing(
            table: "url_entries",
            column: "shared_reference_count",
            definition: "shared_reference_count INTEGER NOT NULL DEFAULT 0"
        )
        try database.addColumnIfMissing(
            table: "url_entries",
            column: "badge_image_url",
            definition: "badge_image_url TEXT"
        )
        try database.addColumnIfMissing(
            table: "url_entries",
            column: "fetched_author_name",
            definition: "fetched_author_name TEXT"
        )
        try database.addColumnIfMissing(
            table: "url_entries",
            column: "collection_id",
            definition: "collection_id INTEGER NOT NULL DEFAULT 1"
        )
        try executeBatch("CREATE INDEX IF NOT EXISTS idx_url_entries_local_visibility ON url_entries(local_provenance_count, record_state);")
        try executeBatch("CREATE INDEX IF NOT EXISTS idx_url_entries_collection ON url_entries(collection_id);")
        try scheduleSocialBadgeBackfillIfNeeded()
    }

    private func ensureDefaultCollection() throws {
        let now = Date().timeIntervalSince1970
        try execute(
            """
            INSERT OR IGNORE INTO collections (id, name, normalized_name, sort_order, created_at, updated_at)
            VALUES (?, ?, ?, 0, ?, ?);
            """,
            binds: [
                sql(defaultCollectionID),
                sql(defaultCollectionName),
                sql(defaultCollectionName.lowercased()),
                sql(now),
                sql(now),
            ]
        )
    }

    private func scheduleSocialBadgeBackfillIfNeeded() throws {
        let flagKey = "social_badge_backfill_v7"
        let existingFlag = try database.fetchString(
            "SELECT key FROM schema_flags WHERE key = ? LIMIT 1;",
            binds: [sql(flagKey)]
        )
        guard existingFlag == nil else { return }

        let now = Date().timeIntervalSince1970
        try execute(
            """
            UPDATE url_entries
            SET metadata_state = 'PENDING',
                metadata_error = NULL,
                metadata_requested_at = ?
            WHERE record_state != 'PENDING_DELETE'
                AND (
                    (
                        service_type IN ('youtube', 'tiktok', 'x', 'instagram')
                        AND (
                            badge_image_url IS NULL
                            OR badge_image_url LIKE '%/favicon.ico%'
                            OR badge_image_url LIKE '%.ico'
                            OR badge_image_url LIKE '%.svg'
                            OR badge_image_url LIKE '%google.com/s2/favicons%'
                            OR (
                                service_type = 'youtube'
                                AND badge_image_url NOT LIKE '%yt3.ggpht.com%'
                                AND badge_image_url NOT LIKE '%yt3.googleusercontent.com%'
                            )
                        )
                    )
                    OR (
                        service_type = 'tiktok'
                        AND (
                            fetched_body IS NULL
                            OR thumbnail_url IS NULL
                            OR badge_image_url IS NULL
                            OR badge_image_url LIKE '%google.com/s2/favicons%'
                        )
                    )
                    OR (
                        service_type = 'web'
                        AND (
                            badge_image_url IS NULL
                            OR badge_image_url LIKE '%/favicon.ico%'
                            OR badge_image_url LIKE '%.ico'
                            OR badge_image_url LIKE '%.svg'
                        )
                    )
                )
                AND metadata_state IN ('READY', 'UNAVAILABLE');
            """,
            binds: [sql(now)]
        )
        try execute(
            "INSERT OR IGNORE INTO schema_flags (key, applied_at) VALUES (?, ?);",
            binds: [sql(flagKey), sql(now)]
        )
    }

    private func fetchEntries(whereClause: String) throws -> [URLRecord] {
        try fetchMany(sql: "SELECT * FROM url_entries WHERE \(whereClause);", binds: [])
    }

    private func fetchMany(sql: String, binds: [SQLiteValue]) throws -> [URLRecord] {
        try database.fetchMany(sql: sql, binds: binds) { statement in
            try decodeRow(statement)
        }
    }

    private func fetchOne(sql: String, binds: [SQLiteValue]) throws -> URLRecord? {
        try database.fetchOne(sql: sql, binds: binds) { statement in
            try decodeRow(statement)
        }
    }

    private func insert(sql: String, binds: [SQLiteValue]) throws -> Int64 {
        try database.insert(sql, binds: binds)
    }

    private func execute(_ sql: String, binds: [SQLiteValue]) throws {
        try database.execute(sql, binds: binds)
    }

    private func executeBatch(_ sql: String) throws {
        try database.executeBatch(sql)
    }

    private func decodeRow(_ statement: OpaquePointer?) throws -> URLRecord {
        URLRecord(
            id: sqlite3_column_int64(statement, index(of: "id")),
            originalURL: textColumn(statement, name: "original_url") ?? "",
            normalizedURL: textColumn(statement, name: "normalized_url") ?? "",
            displayURL: textColumn(statement, name: "display_url") ?? "",
            openURL: textColumn(statement, name: "open_url") ?? "",
            normalizedHost: textColumn(statement, name: "normalized_host") ?? "",
            rawSourceHost: textColumn(statement, name: "raw_source_host") ?? "",
            collectionID: sqlite3_column_int64(statement, index(of: "collection_id")),
            serviceType: ServiceType(rawValue: textColumn(statement, name: "service_type") ?? "") ?? .web,
            contentContext: ContentContext(rawValue: textColumn(statement, name: "content_context") ?? "") ?? .standard,
            userTitle: textColumn(statement, name: "user_title"),
            fetchedTitle: textColumn(statement, name: "fetched_title"),
            fetchedAuthorName: textColumn(statement, name: "fetched_author_name"),
            fetchedBody: textColumn(statement, name: "fetched_body"),
            fetchedBodyKind: textColumn(statement, name: "fetched_body_kind").flatMap(MetadataBodyKind.init(rawValue:)),
            bodySummary: textColumn(statement, name: "body_summary"),
            description: textColumn(statement, name: "description"),
            memo: textColumn(statement, name: "memo") ?? "",
            thumbnailURL: textColumn(statement, name: "thumbnail_url"),
            badgeImageURL: textColumn(statement, name: "badge_image_url"),
            canonicalID: textColumn(statement, name: "canonical_id"),
            metadataState: MetadataState(rawValue: textColumn(statement, name: "metadata_state") ?? "") ?? .pending,
            metadataError: textColumn(statement, name: "metadata_error").flatMap(MetadataError.init(rawValue:)),
            metadataRequestedAt: dateColumn(statement, name: "metadata_requested_at"),
            metadataFetchedAt: dateColumn(statement, name: "metadata_fetched_at"),
            recordState: RecordState(rawValue: textColumn(statement, name: "record_state") ?? "") ?? .active,
            localProvenanceCount: Int(sqlite3_column_int(statement, index(of: "local_provenance_count"))),
            sharedReferenceCount: Int(sqlite3_column_int(statement, index(of: "shared_reference_count"))),
            createdAt: dateColumn(statement, name: "created_at") ?? Date(timeIntervalSince1970: 0),
            updatedAt: dateColumn(statement, name: "updated_at") ?? Date(timeIntervalSince1970: 0),
            archivedAt: dateColumn(statement, name: "archived_at"),
            pendingDeletionUntil: dateColumn(statement, name: "pending_deletion_until")
        )
    }

    private func index(of columnName: String) -> Int32 {
        switch columnName {
        case "id": return 0
        case "original_url": return 1
        case "normalized_url": return 2
        case "display_url": return 3
        case "open_url": return 4
        case "normalized_host": return 5
        case "raw_source_host": return 6
        case "service_type": return 7
        case "content_context": return 8
        case "user_title": return 9
        case "fetched_title": return 10
        case "fetched_body": return 11
        case "fetched_body_kind": return 12
        case "body_summary": return 13
        case "description": return 14
        case "memo": return 15
        case "thumbnail_url": return 16
        case "canonical_id": return 17
        case "metadata_state": return 18
        case "metadata_error": return 19
        case "metadata_requested_at": return 20
        case "metadata_fetched_at": return 21
        case "record_state": return 22
        case "local_provenance_count": return 23
        case "shared_reference_count": return 24
        case "created_at": return 25
        case "updated_at": return 26
        case "archived_at": return 27
        case "pending_deletion_until": return 28
        case "badge_image_url": return 29
        case "fetched_author_name": return 30
        case "collection_id": return 31
        default: return 0
        }
    }

    private func textColumn(_ statement: OpaquePointer?, name: String) -> String? {
        let index = index(of: name)
        return textColumn(statement, index: index)
    }

    private func textColumn(_ statement: OpaquePointer?, index: Int32) -> String? {
        guard sqlite3_column_type(statement, index) != SQLITE_NULL,
              let value = sqlite3_column_text(statement, index) else {
            return nil
        }
        return String(cString: value)
    }

    private func dateColumn(_ statement: OpaquePointer?, name: String) -> Date? {
        let index = index(of: name)
        return dateColumn(statement, index: index)
    }

    private func dateColumn(_ statement: OpaquePointer?, index: Int32) -> Date? {
        guard sqlite3_column_type(statement, index) != SQLITE_NULL else { return nil }
        return Date(timeIntervalSince1970: sqlite3_column_double(statement, index))
    }

    private let defaultCollectionID: Int64 = 1
    private let defaultCollectionName = "受信箱"
}
