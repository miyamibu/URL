import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    private let statusLabel = UILabel()
    private let activityIndicator = UIActivityIndicatorView(style: .large)
    private let doneButton = UIButton(type: .system)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        configureUI()

        Task {
            await processShare()
        }
    }

    private func configureUI() {
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center
        statusLabel.text = "URLを保存しています…"

        activityIndicator.translatesAutoresizingMaskIntoConstraints = false
        activityIndicator.startAnimating()

        doneButton.translatesAutoresizingMaskIntoConstraints = false
        doneButton.setTitle("完了", for: .normal)
        doneButton.addTarget(self, action: #selector(finishExtension), for: .touchUpInside)
        doneButton.isHidden = true

        let stack = UIStackView(arrangedSubviews: [activityIndicator, statusLabel, doneButton])
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .vertical
        stack.spacing = 16
        stack.alignment = .center

        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    @objc
    private func finishExtension() {
        extensionContext?.completeRequest(returningItems: nil)
    }

    @MainActor
    private func updateStatus(_ text: String, finished: Bool) {
        statusLabel.text = text
        if finished {
            activityIndicator.stopAnimating()
            doneButton.isHidden = false
        }
    }

    private func processShare() async {
        guard let extensionItems = extensionContext?.inputItems as? [NSExtensionItem] else {
            await MainActor.run { updateStatus("共有内容を読み取れませんでした", finished: true) }
            return
        }

        let payload = await ShareExtensionPayloadExtractor.extract(from: extensionItems)
        if !SharedContainer.hasAppGroupAccess() {
            await processShareViaHostAppFallback(payload: payload)
            return
        }
        let repository = try? URLRepository()
        let handoffStore = ShareHandoffStore()

        guard let repository else {
            await MainActor.run { updateStatus("保存先の初期化に失敗しました", finished: true) }
            return
        }

        let extractedBatch = URLRules.extractAllFromCandidateGroups(payload.candidateGroups)
        let allURLs = extractedBatch.urls
        let report: ShareHandoffReport
        let statusText: String

        if payload.isExplicitMultiShare && allURLs.count > 1 {
            var created = 0
            var duplicate = 0
            var restored = 0
            var failed = 0

            for url in allURLs {
                let result = (try? repository.saveFromResolvedURL(url)) ?? SaveResult(result: .saveFailed)
                switch result.result {
                case .created:
                    created += 1
                case .duplicateActive, .duplicateArchived:
                    duplicate += 1
                case .restoredFromPendingDelete:
                    restored += 1
                default:
                    failed += 1
                }
            }

            let summary = BatchSaveSummary(
                total: allURLs.count,
                created: created,
                duplicate: duplicate,
                restored: restored,
                failed: failed
            )
            let degradation: ShareDegradationNotice? = extractedBatch.truncatedToMaxURLs ? .truncatedToMaxURLs : nil
            report = ShareHandoffReport(
                result: .batchProcessed,
                entryID: nil,
                normalizedURL: nil,
                degradationNotice: degradation,
                batchSummary: summary,
                createdAt: Date()
            )
            if degradation == .truncatedToMaxURLs {
                statusText = "\(summary.total)件を処理しました（新規\(summary.created) / 既存\(summary.duplicate) / 復元\(summary.restored) / 失敗\(summary.failed)）\n多数のURLが含まれていたため、先頭\(URLRules.maxBatchSaveURLsPerIntake)件のみ処理しました"
            } else {
                statusText = "\(summary.total)件を処理しました（新規\(summary.created) / 既存\(summary.duplicate) / 復元\(summary.restored) / 失敗\(summary.failed)）"
            }
        } else {
            let degradation = URLRules.countValidURLs(in: payload.candidateGroups) > 1 ? ShareDegradationNotice.truncatedToFirstURL : nil
            let result: SaveResult
            switch URLRules.extractFromCandidateGroups(payload.candidateGroups) {
            case .found(let url):
                result = (try? repository.saveFromResolvedURL(url)) ?? SaveResult(result: .saveFailed)
            case .inputTooLarge:
                result = SaveResult(result: .inputTooLarge)
            case .invalidURL:
                result = SaveResult(result: .invalidURL)
            case .noURLFound:
                result = SaveResult(result: .noURLFound)
            }

            report = ShareHandoffReport(
                result: result.result,
                entryID: result.entryID,
                normalizedURL: result.normalizedURL,
                degradationNotice: degradation,
                batchSummary: nil,
                createdAt: Date()
            )
            statusText = shareStatusText(result: result.result, degradation: degradation)
        }

        try? await handoffStore.write(report)
        await MainActor.run { updateStatus(statusText, finished: true) }
    }

    private func processShareViaHostAppFallback(payload: ShareExtensionPayload) async {
        let degradation = URLRules.countValidURLs(in: payload.candidateGroups) > 1 ? ShareDegradationNotice.truncatedToFirstURL : nil
        let routeURL: URL?

        switch URLRules.extractFromCandidateGroups(payload.candidateGroups) {
        case .found(let url):
            routeURL = makeHostAppSaveURL(url: url, degradation: degradation)
        case .inputTooLarge:
            routeURL = nil
            await MainActor.run { updateStatus("共有内容が長すぎるため処理できませんでした", finished: true) }
            return
        case .invalidURL:
            routeURL = nil
            await MainActor.run { updateStatus("有効なURLではありませんでした", finished: true) }
            return
        case .noURLFound:
            routeURL = nil
            await MainActor.run { updateStatus("URLが見つかりませんでした", finished: true) }
            return
        }

        guard let routeURL else {
            await MainActor.run { updateStatus("保存できませんでした", finished: true) }
            return
        }

        let opened = await openHostApp(routeURL)
        let statusText = if opened {
            degradation == .truncatedToFirstURL
                ? "アプリで保存を続けます\n共有内容に複数URLが含まれていたため、1件目のみ保存します"
                : "アプリで保存を続けます"
        } else {
            "アプリへ引き継げなかったため保存できませんでした"
        }
        await MainActor.run { updateStatus(statusText, finished: true) }
        if opened {
            finishExtension()
        }
    }

    private func makeHostAppSaveURL(url: String, degradation: ShareDegradationNotice?) -> URL? {
        var components = URLComponents()
        components.scheme = "urlsaver"
        components.host = "save"
        var queryItems = [URLQueryItem(name: "url", value: url)]
        if let degradation {
            queryItems.append(URLQueryItem(name: "degradation", value: degradation.rawValue))
        }
        components.queryItems = queryItems
        return components.url
    }

    private func openHostApp(_ url: URL) async -> Bool {
        await withCheckedContinuation { continuation in
            extensionContext?.open(url) { success in
                continuation.resume(returning: success)
            } ?? continuation.resume(returning: false)
        }
    }

    private func shareStatusText(result: ShareSaveResult, degradation: ShareDegradationNotice?) -> String {
        let main: String
        switch result {
        case .created:
            main = "保存しました"
        case .duplicateActive:
            main = "このURLはすでに保存済みです"
        case .duplicateArchived:
            main = "このURLはアーカイブ済みです"
        case .restoredFromPendingDelete:
            main = "削除を取り消して復元しました"
        case .saveFailed:
            main = "保存できませんでした"
        case .inputTooLarge:
            main = "共有内容が長すぎるため処理できませんでした"
        case .invalidURL:
            main = "有効なURLではありませんでした"
        case .noURLFound:
            main = "URLが見つかりませんでした"
        case .batchProcessed:
            main = "処理しました"
        }

        guard degradation == .truncatedToFirstURL else {
            return main
        }
        return main + "\n共有内容に複数URLが含まれていたため、1件目のみ保存しました"
    }
}

private struct ShareExtensionPayload {
    let candidateGroups: ShareCandidateGroups
    let isExplicitMultiShare: Bool
}

private enum ShareExtensionPayloadExtractor {
    static func extract(from items: [NSExtensionItem]) async -> ShareExtensionPayload {
        var groups = ShareCandidateGroups()
        var payloadCount = 0

        for item in items {
            if let attributedText = item.attributedContentText?.string, !attributedText.isEmpty {
                groups.extraCandidates.append(attributedText)
                payloadCount += 1
            }

            let attachments = item.attachments ?? []
            for provider in attachments {
                if let text = await loadText(from: provider) {
                    groups.providerTextCandidates.append(text)
                    payloadCount += 1
                    continue
                }
                if let urlString = await loadURLString(from: provider) {
                    groups.streamCandidates.append(urlString)
                    payloadCount += 1
                }
            }
        }

        return ShareExtensionPayload(
            candidateGroups: groups,
            isExplicitMultiShare: payloadCount > 1
        )
    }

    private static func loadText(from provider: NSItemProvider) async -> String? {
        let textTypes = [UTType.plainText, .utf8PlainText, .text, .html]
        for type in textTypes where provider.hasItemConformingToTypeIdentifier(type.identifier) {
            if let value = try? await provider.loadItemValue(forTypeIdentifier: type.identifier) {
                if case .string(let string) = value, !string.isEmpty {
                    return string
                }
                if case .data(let data) = value, let string = String(data: data, encoding: .utf8), !string.isEmpty {
                    return string
                }
            }
        }
        return nil
    }

    private static func loadURLString(from provider: NSItemProvider) async -> String? {
        let urlTypes = [UTType.url, .fileURL]
        for type in urlTypes where provider.hasItemConformingToTypeIdentifier(type.identifier) {
            if let value = try? await provider.loadItemValue(forTypeIdentifier: type.identifier) {
                if case .url(let url) = value {
                    return url.absoluteString
                }
                if case .string(let string) = value {
                    return string
                }
            }
        }
        return nil
    }
}

private extension NSItemProvider {
    func loadItemValue(forTypeIdentifier identifier: String) async throws -> LoadedItem? {
        try await withCheckedThrowingContinuation { continuation in
            loadItem(forTypeIdentifier: identifier, options: nil) { item, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    if let url = item as? URL {
                        continuation.resume(returning: .url(url))
                    } else if let data = item as? Data {
                        continuation.resume(returning: .data(data))
                    } else if let string = item as? String {
                        continuation.resume(returning: .string(string))
                    } else if let attributed = item as? NSAttributedString {
                        continuation.resume(returning: .string(attributed.string))
                    } else {
                        continuation.resume(returning: nil)
                    }
                }
            }
        }
    }
}

private enum LoadedItem: Sendable {
    case string(String)
    case data(Data)
    case url(URL)
}
