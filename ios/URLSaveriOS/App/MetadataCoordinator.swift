import Foundation

actor MetadataCoordinator {
    private let repository: URLRepository
    private let fetcher: MetadataFetcher
    private var inflightEntryIDs = Set<Int64>()

    init(repository: URLRepository, fetcher: MetadataFetcher = MetadataFetcher()) {
        self.repository = repository
        self.fetcher = fetcher
    }

    func enqueue(entryID: Int64) async -> Bool {
        guard !Task.isCancelled else { return false }
        guard inflightEntryIDs.insert(entryID).inserted else { return true }
        defer { inflightEntryIDs.remove(entryID) }

        let record: URLRecord?
        do {
            record = try repository.loadEntry(id: entryID)
        } catch {
            return false
        }

        guard let record,
              record.recordState != .pendingDelete else {
            return true
        }

        guard !Task.isCancelled else { return false }
        let update = await fetcher.fetch(for: record)
        guard !Task.isCancelled else { return false }
        do {
            try repository.applyMetadataUpdate(entryID: entryID, metadata: update)
            return true
        } catch {
            return false
        }
    }

    func processBacklog(limit: Int = 12) async -> Bool {
        let entries: [URLRecord]
        do {
            entries = try repository.loadEntriesNeedingMetadata(limit: limit)
        } catch {
            return false
        }

        var succeeded = true
        for entry in entries {
            guard !Task.isCancelled else { return false }
            let enqueued = await enqueue(entryID: entry.id)
            succeeded = succeeded && enqueued
        }

        return succeeded && !Task.isCancelled
    }
}
