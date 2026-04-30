import Foundation

protocol SharedTagSyncDriving: Sendable {
    func sync(authUserID: String) async -> Bool
}

actor SharedTagSyncExecutor {
    private struct SyncState {
        var isRunning = false
        var needsRerun = false
    }

    private let driver: SharedTagSyncDriving
    private var states: [String: SyncState] = [:]

    init(driver: SharedTagSyncDriving) {
        self.driver = driver
    }

    func enqueue(authUserID: String) {
        let normalized = authUserID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return }

        if var state = states[normalized], state.isRunning {
            state.needsRerun = true
            states[normalized] = state
            return
        }

        states[normalized] = SyncState(isRunning: true, needsRerun: false)
        Task {
            await self.runLoop(for: normalized)
        }
    }

    private func runLoop(for authUserID: String) async {
        while true {
            _ = await driver.sync(authUserID: authUserID)
            let shouldRerun = await finishIteration(for: authUserID)
            if !shouldRerun {
                return
            }
        }
    }

    private func finishIteration(for authUserID: String) -> Bool {
        guard var state = states[authUserID] else { return false }
        if state.needsRerun {
            state.needsRerun = false
            states[authUserID] = state
            return true
        }
        states.removeValue(forKey: authUserID)
        return false
    }
}

struct NoOpSharedTagSyncDriver: SharedTagSyncDriving {
    func sync(authUserID: String) async -> Bool {
        _ = authUserID
        return true
    }
}

struct SharedTagCloudSyncDriver: SharedTagSyncDriving {
    private let service: SharedTagCloudService

    init(service: SharedTagCloudService) {
        self.service = service
    }

    func sync(authUserID: String) async -> Bool {
        guard service.currentSession()?.authUserID == authUserID else {
            return true
        }
        return await service.syncCurrentSession()
    }
}
