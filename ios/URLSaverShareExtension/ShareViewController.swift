import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    private enum Layout {
        static let pickerBottomOffset: CGFloat = -19
        static let resultBottomOffset: CGFloat = -152
        static let pickerTopInset: CGFloat = 19
        static let pickerHorizontalInset: CGFloat = 8
        static let compactResultHeight: CGFloat = 420
        static let minimumPickerHeight: CGFloat = 360
        static let maximumPickerHeight: CGFloat = 700
        static let maximumTagAreaHeight: CGFloat = 230
    }

    private let statusLabel = UILabel()
    private let activityIndicator = UIActivityIndicatorView(style: .large)
    private let doneButton = UIButton(type: .system)
    private let panelView = UIView()
    private let contentStack = UIStackView()
    private let pickerContainerView = UIView()
    private let pickerTitleLabel = UILabel()
    private let pickerMessageLabel = UILabel()
    private let tagAreaView = UIView()
    private let tagScrollView = UIScrollView()
    private let tagFlowView = TagFlowView()
    private let createTagField = UITextField()
    private let createTagButton = UIButton(type: .system)
    private let saveButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)
    private let pickerActionsStack = UIStackView()
    private let resultSpacer = UIView()
    private var pickerBottomConstraint: NSLayoutConstraint?
    private var resultBottomConstraint: NSLayoutConstraint?
    private var panelHeightConstraint: NSLayoutConstraint?
    private var tagAreaHeightConstraint: NSLayoutConstraint?
    private var tagFlowHeightConstraint: NSLayoutConstraint?
    private var resultDirectConstraints: [NSLayoutConstraint] = []
    private var repository: URLRepository?
    private var localTags: [LocalTagSummary] = []
    private var pendingShare: PendingExtensionShare?
    private var selectedLocalTagIDs = Set<Int64>()

    override func viewDidLoad() {
        super.viewDidLoad()
        preferredContentSize = CGSize(width: 0, height: Layout.minimumPickerHeight)
        view.backgroundColor = .systemBackground
        view.isOpaque = true
        configureUI()

        Task {
            await processShare()
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        preferredContentSize = CGSize(width: view.bounds.width, height: panelHeightConstraint?.constant ?? Layout.minimumPickerHeight)
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
        doneButton.titleLabel?.font = .preferredFont(forTextStyle: .title2)
        doneButton.titleLabel?.adjustsFontForContentSizeCategory = true
        var doneConfiguration = UIButton.Configuration.filled()
        doneConfiguration.title = "完了"
        doneConfiguration.contentInsets = NSDirectionalEdgeInsets(top: 16, leading: 48, bottom: 16, trailing: 48)
        doneConfiguration.cornerStyle = .capsule
        doneButton.configuration = doneConfiguration
        doneButton.addTarget(self, action: #selector(finishExtension), for: .touchUpInside)
        doneButton.isHidden = true

        resultSpacer.translatesAutoresizingMaskIntoConstraints = false
        resultSpacer.setContentHuggingPriority(.defaultLow, for: .vertical)
        resultSpacer.setContentCompressionResistancePriority(.defaultLow, for: .vertical)

        tagAreaView.translatesAutoresizingMaskIntoConstraints = false
        tagAreaView.setContentHuggingPriority(.required, for: .vertical)
        tagAreaView.setContentCompressionResistancePriority(.required, for: .vertical)

        tagScrollView.translatesAutoresizingMaskIntoConstraints = false
        tagScrollView.alwaysBounceVertical = true
        tagScrollView.showsVerticalScrollIndicator = false
        tagScrollView.addSubview(tagFlowView)

        tagFlowView.translatesAutoresizingMaskIntoConstraints = false
        tagFlowHeightConstraint = tagFlowView.heightAnchor.constraint(equalToConstant: 0)
        tagAreaView.addSubview(tagScrollView)
        NSLayoutConstraint.activate([
            tagScrollView.leadingAnchor.constraint(equalTo: tagAreaView.leadingAnchor),
            tagScrollView.trailingAnchor.constraint(equalTo: tagAreaView.trailingAnchor),
            tagScrollView.topAnchor.constraint(equalTo: tagAreaView.topAnchor),
            tagScrollView.bottomAnchor.constraint(equalTo: tagAreaView.bottomAnchor),
            tagFlowView.leadingAnchor.constraint(equalTo: tagScrollView.contentLayoutGuide.leadingAnchor),
            tagFlowView.trailingAnchor.constraint(equalTo: tagScrollView.contentLayoutGuide.trailingAnchor),
            tagFlowView.topAnchor.constraint(equalTo: tagScrollView.contentLayoutGuide.topAnchor),
            tagFlowView.bottomAnchor.constraint(equalTo: tagScrollView.contentLayoutGuide.bottomAnchor),
            tagFlowView.widthAnchor.constraint(equalTo: tagScrollView.frameLayoutGuide.widthAnchor),
            tagFlowHeightConstraint!,
        ])

        contentStack.translatesAutoresizingMaskIntoConstraints = false
        contentStack.axis = .vertical
        contentStack.spacing = 16
        contentStack.alignment = .center
        contentStack.addArrangedSubview(activityIndicator)
        contentStack.addArrangedSubview(statusLabel)
        contentStack.addArrangedSubview(doneButton)

        pickerContainerView.translatesAutoresizingMaskIntoConstraints = false
        pickerContainerView.isHidden = true

        pickerTitleLabel.translatesAutoresizingMaskIntoConstraints = false
        pickerTitleLabel.text = "保存先タグ"
        pickerTitleLabel.font = .preferredFont(forTextStyle: .largeTitle)
        pickerTitleLabel.adjustsFontForContentSizeCategory = true
        pickerTitleLabel.textAlignment = .left

        pickerMessageLabel.translatesAutoresizingMaskIntoConstraints = false
        pickerMessageLabel.numberOfLines = 0
        pickerMessageLabel.font = .preferredFont(forTextStyle: .headline)
        pickerMessageLabel.adjustsFontForContentSizeCategory = true
        pickerMessageLabel.textColor = .secondaryLabel
        pickerMessageLabel.isHidden = true

        createTagField.translatesAutoresizingMaskIntoConstraints = false
        createTagButton.translatesAutoresizingMaskIntoConstraints = false
        saveButton.translatesAutoresizingMaskIntoConstraints = false
        cancelButton.translatesAutoresizingMaskIntoConstraints = false
        pickerActionsStack.translatesAutoresizingMaskIntoConstraints = false
        pickerActionsStack.axis = .horizontal
        pickerActionsStack.spacing = 16
        pickerActionsStack.distribution = .fillEqually
        pickerActionsStack.addArrangedSubview(cancelButton)
        pickerActionsStack.addArrangedSubview(saveButton)

        panelView.translatesAutoresizingMaskIntoConstraints = false
        panelView.backgroundColor = .systemBackground
        panelView.layer.cornerRadius = 28
        panelView.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        panelView.clipsToBounds = true

        view.addSubview(panelView)
        panelView.addSubview(contentStack)
        view.addSubview(pickerContainerView)
        pickerContainerView.addSubview(pickerTitleLabel)
        pickerContainerView.addSubview(pickerMessageLabel)
        pickerContainerView.addSubview(tagAreaView)
        pickerContainerView.addSubview(createTagField)
        pickerContainerView.addSubview(createTagButton)
        pickerContainerView.addSubview(pickerActionsStack)
        pickerBottomConstraint = contentStack.bottomAnchor.constraint(
            equalTo: panelView.safeAreaLayoutGuide.bottomAnchor,
            constant: Layout.pickerBottomOffset
        )
        resultBottomConstraint = contentStack.bottomAnchor.constraint(
            equalTo: panelView.safeAreaLayoutGuide.bottomAnchor,
            constant: Layout.resultBottomOffset
        )
        panelHeightConstraint = panelView.heightAnchor.constraint(equalToConstant: Layout.minimumPickerHeight)
        resultBottomConstraint?.isActive = false
        NSLayoutConstraint.activate([
            panelView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            panelView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            panelView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            panelHeightConstraint!,
            contentStack.leadingAnchor.constraint(equalTo: panelView.layoutMarginsGuide.leadingAnchor),
            contentStack.trailingAnchor.constraint(equalTo: panelView.layoutMarginsGuide.trailingAnchor),
            contentStack.topAnchor.constraint(equalTo: panelView.safeAreaLayoutGuide.topAnchor, constant: 18),
            pickerBottomConstraint!,
            pickerContainerView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: Layout.pickerHorizontalInset),
            pickerContainerView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -Layout.pickerHorizontalInset),
            pickerContainerView.topAnchor.constraint(equalTo: view.topAnchor, constant: Layout.pickerTopInset),
            pickerContainerView.bottomAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.bottomAnchor, constant: Layout.pickerBottomOffset),
            pickerTitleLabel.leadingAnchor.constraint(equalTo: pickerContainerView.leadingAnchor),
            pickerTitleLabel.trailingAnchor.constraint(equalTo: pickerContainerView.trailingAnchor),
            pickerTitleLabel.topAnchor.constraint(equalTo: pickerContainerView.topAnchor),
            pickerMessageLabel.leadingAnchor.constraint(equalTo: pickerContainerView.leadingAnchor),
            pickerMessageLabel.trailingAnchor.constraint(equalTo: pickerContainerView.trailingAnchor),
            pickerMessageLabel.topAnchor.constraint(equalTo: pickerTitleLabel.bottomAnchor, constant: 8),
            pickerActionsStack.leadingAnchor.constraint(equalTo: pickerContainerView.leadingAnchor),
            pickerActionsStack.trailingAnchor.constraint(equalTo: pickerContainerView.trailingAnchor),
            pickerActionsStack.topAnchor.constraint(equalTo: createTagButton.bottomAnchor, constant: 22),
            pickerActionsStack.bottomAnchor.constraint(equalTo: pickerContainerView.bottomAnchor),
            pickerActionsStack.heightAnchor.constraint(equalToConstant: 58),
            createTagButton.leadingAnchor.constraint(equalTo: pickerContainerView.leadingAnchor),
            createTagButton.trailingAnchor.constraint(equalTo: pickerContainerView.trailingAnchor),
            createTagButton.topAnchor.constraint(equalTo: createTagField.bottomAnchor, constant: 18),
            createTagButton.heightAnchor.constraint(equalToConstant: 54),
            createTagField.leadingAnchor.constraint(equalTo: pickerContainerView.leadingAnchor),
            createTagField.trailingAnchor.constraint(equalTo: pickerContainerView.trailingAnchor),
            createTagField.topAnchor.constraint(equalTo: tagAreaView.bottomAnchor, constant: 14),
            createTagField.heightAnchor.constraint(equalToConstant: 58),
            tagAreaView.leadingAnchor.constraint(equalTo: pickerContainerView.leadingAnchor),
            tagAreaView.trailingAnchor.constraint(equalTo: pickerContainerView.trailingAnchor),
            tagAreaView.topAnchor.constraint(equalTo: pickerMessageLabel.bottomAnchor, constant: 18),
        ])
    }

    @objc
    private func finishExtension() {
        extensionContext?.completeRequest(returningItems: nil)
    }

    @MainActor
    private func updateStatus(_ text: String, finished: Bool) {
        if finished {
            pickerContainerView.isHidden = true
            contentStack.isHidden = true
            contentStack.arrangedSubviews.forEach { view in
                contentStack.removeArrangedSubview(view)
                view.removeFromSuperview()
            }
            pickerBottomConstraint?.isActive = false
            resultBottomConstraint?.isActive = true
            statusLabel.font = .preferredFont(forTextStyle: .title2)
            statusLabel.adjustsFontForContentSizeCategory = true
            statusLabel.textColor = .label
            statusLabel.isHidden = false
            statusLabel.removeFromSuperview()
            doneButton.removeFromSuperview()
            panelView.addSubview(statusLabel)
            panelView.addSubview(doneButton)
            NSLayoutConstraint.deactivate(resultDirectConstraints)
            resultDirectConstraints = [
                doneButton.centerXAnchor.constraint(equalTo: panelView.centerXAnchor),
                doneButton.widthAnchor.constraint(greaterThanOrEqualTo: panelView.widthAnchor, multiplier: 0.72),
                doneButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 58),
                doneButton.bottomAnchor.constraint(equalTo: panelView.safeAreaLayoutGuide.bottomAnchor, constant: Layout.resultBottomOffset),
                statusLabel.centerXAnchor.constraint(equalTo: panelView.centerXAnchor),
                statusLabel.leadingAnchor.constraint(greaterThanOrEqualTo: panelView.leadingAnchor, constant: 24),
                statusLabel.trailingAnchor.constraint(lessThanOrEqualTo: panelView.trailingAnchor, constant: -24),
                statusLabel.topAnchor.constraint(greaterThanOrEqualTo: panelView.safeAreaLayoutGuide.topAnchor, constant: 24),
                statusLabel.bottomAnchor.constraint(equalTo: doneButton.topAnchor, constant: -96),
            ]
            NSLayoutConstraint.activate(resultDirectConstraints)
            updatePreferredContentHeight(Layout.compactResultHeight)
        } else if statusLabel.superview == nil {
            contentStack.arrangedSubviews.forEach { view in
                contentStack.removeArrangedSubview(view)
                view.removeFromSuperview()
            }
            contentStack.addArrangedSubview(statusLabel)
        }
        statusLabel.text = text
        statusLabel.textAlignment = .center
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

        guard let repository else {
            await MainActor.run { updateStatus("保存先の初期化に失敗しました", finished: true) }
            return
        }
        self.repository = repository

        if let tagPayload = payload.tagSharePayload {
            do {
                let result = try repository.importLocalTagPayload(tagPayload)
                await MainActor.run {
                    updateStatus(
                        "タグ「\(result.tagName)」を読み込みました\n新規\(result.created)件 / 追加\(result.merged)件",
                        finished: true
                    )
                }
            } catch {
                await MainActor.run { updateStatus("タグデータを読み込めませんでした", finished: true) }
            }
            return
        }

        let extractedBatch = URLRules.extractAllFromCandidateGroups(payload.candidateGroups)
        let allURLs = extractedBatch.urls
        let sharedMemo = URLRules.extractMemoWithoutURLs(from: payload.candidateGroups)

        if allURLs.count > 1 {
            let degradation: ShareDegradationNotice? = extractedBatch.truncatedToMaxURLs ? .truncatedToMaxURLs : nil
            await presentTagPicker(
                repository: repository,
                share: PendingExtensionShare(
                    urls: allURLs,
                    isBatch: true,
                    memo: sharedMemo,
                    degradationNotice: degradation
                )
            )
            return
        } else {
            let degradation = URLRules.countValidURLs(in: payload.candidateGroups) > 1 ? ShareDegradationNotice.truncatedToFirstURL : nil
            switch URLRules.extractFromCandidateGroups(payload.candidateGroups) {
            case .found(let url):
                await presentTagPicker(
                    repository: repository,
                    share: PendingExtensionShare(
                        urls: [url],
                        isBatch: false,
                        memo: sharedMemo,
                        degradationNotice: degradation
                    )
                )
                return
            case .inputTooLarge:
                await MainActor.run { updateStatus("共有内容が長すぎるため処理できませんでした", finished: true) }
                return
            case .invalidURL:
                if let text = URLRules.extractTextFallback(from: payload.candidateGroups) {
                    await presentTagPicker(
                        repository: repository,
                        share: PendingExtensionShare(
                            urls: [text],
                            isBatch: false,
                            degradationNotice: nil
                        )
                    )
                } else {
                    await MainActor.run { updateStatus("有効なURLではありませんでした", finished: true) }
                }
                return
            case .noURLFound:
                if let text = URLRules.extractTextFallback(from: payload.candidateGroups) {
                    await presentTagPicker(
                        repository: repository,
                        share: PendingExtensionShare(
                            urls: [text],
                            isBatch: false,
                            degradationNotice: nil
                        )
                    )
                } else {
                    await MainActor.run { updateStatus("保存できる内容が見つかりませんでした", finished: true) }
                }
                return
            }
        }
    }

    private func presentTagPicker(repository: URLRepository, share: PendingExtensionShare) async {
        let tags = (try? repository.loadLocalTags()) ?? []
        await MainActor.run {
            localTags = tags
            pendingShare = share
            selectedLocalTagIDs = []
            showTagPicker()
        }
    }

    @MainActor
    private func showTagPicker() {
        activityIndicator.stopAnimating()
        doneButton.isHidden = true
        contentStack.isHidden = true
        pickerContainerView.isHidden = false
        pickerBottomConstraint?.isActive = true
        resultBottomConstraint?.isActive = false

        statusLabel.textAlignment = .left
        statusLabel.font = .preferredFont(forTextStyle: .headline)
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.textColor = .secondaryLabel
        statusLabel.text = nil
        statusLabel.isHidden = true
        pickerMessageLabel.text = nil
        pickerMessageLabel.isHidden = true

        rebuildTagButtons()

        createTagField.borderStyle = .roundedRect
        createTagField.placeholder = "新しいタグ名"
        createTagField.font = .preferredFont(forTextStyle: .title3)
        createTagField.adjustsFontForContentSizeCategory = true
        createTagField.autocorrectionType = .no
        createTagField.returnKeyType = .done
        createTagField.setContentHuggingPriority(.required, for: .vertical)
        createTagField.setContentCompressionResistancePriority(.required, for: .vertical)

        createTagButton.setTitle("＋", for: .normal)
        createTagButton.titleLabel?.font = .preferredFont(forTextStyle: .title2)
        createTagButton.titleLabel?.adjustsFontForContentSizeCategory = true
        createTagButton.setContentHuggingPriority(.required, for: .vertical)
        createTagButton.setContentCompressionResistancePriority(.required, for: .vertical)
        createTagButton.removeTarget(nil, action: nil, for: .allEvents)
        createTagButton.addTarget(self, action: #selector(createLocalTagFromInput), for: .touchUpInside)

        saveButton.setTitle("保存", for: .normal)
        saveButton.titleLabel?.font = .preferredFont(forTextStyle: .title2)
        saveButton.titleLabel?.adjustsFontForContentSizeCategory = true
        saveButton.removeTarget(nil, action: nil, for: .allEvents)
        saveButton.addTarget(self, action: #selector(savePendingShare), for: .touchUpInside)
        saveButton.isEnabled = true

        cancelButton.setTitle("キャンセル", for: .normal)
        cancelButton.titleLabel?.font = .preferredFont(forTextStyle: .title2)
        cancelButton.titleLabel?.adjustsFontForContentSizeCategory = true
        cancelButton.removeTarget(nil, action: nil, for: .allEvents)
        cancelButton.addTarget(self, action: #selector(finishExtension), for: .touchUpInside)

        tagAreaHeightConstraint?.isActive = false
        tagAreaHeightConstraint = tagAreaView.heightAnchor.constraint(equalToConstant: preferredTagAreaHeight())
        tagAreaHeightConstraint?.isActive = true

        if localTags.isEmpty {
            pickerMessageLabel.text = "タグがまだありません。必要なら作成できます。"
            pickerMessageLabel.isHidden = false
            createTagField.becomeFirstResponder()
        }
        updatePickerLayoutHeight()
    }

    @MainActor
    private func rebuildTagButtons() {
        tagFlowView.configure(
            tags: localTags,
            selectedTagIDs: selectedLocalTagIDs,
            onToggle: { [weak self] tagID in
                self?.toggleLocalTag(tagID)
            }
        )
        tagFlowHeightConstraint?.constant = preferredTagContentHeight()
        tagAreaHeightConstraint?.constant = preferredTagAreaHeight()
        updatePickerLayoutHeight()
    }

    private func preferredTagAreaHeight() -> CGFloat {
        guard !localTags.isEmpty else { return 0 }
        return min(preferredTagContentHeight(), Layout.maximumTagAreaHeight)
    }

    private func preferredTagContentHeight() -> CGFloat {
        guard !localTags.isEmpty else { return 0 }
        let marginsWidth = view.layoutMargins.left + view.layoutMargins.right
        let availableWidth = max(240, view.bounds.width - marginsWidth)
        return max(56, tagFlowView.preferredHeight(for: availableWidth))
    }

    private func updatePickerLayoutHeight() {
        updatePreferredContentHeight(preferredPickerHeight())
    }

    private func preferredPickerHeight() -> CGFloat {
        let contentWidth = max(240, view.bounds.width - Layout.pickerHorizontalInset * 2)
        let titleHeight = fittingHeight(for: pickerTitleLabel, width: contentWidth)
        let messageHeight = pickerMessageLabel.isHidden ? 0 : fittingHeight(for: pickerMessageLabel, width: contentWidth)
        let messageGap: CGFloat = pickerMessageLabel.isHidden ? 0 : 8
        let tagGap: CGFloat = localTags.isEmpty ? 0 : 18
        let contentHeight = Layout.pickerTopInset +
            titleHeight +
            messageGap +
            messageHeight +
            tagGap +
            preferredTagAreaHeight() +
            14 +
            58 +
            18 +
            54 +
            22 +
            58 +
            abs(Layout.pickerBottomOffset)
        return min(max(contentHeight, Layout.minimumPickerHeight), Layout.maximumPickerHeight)
    }

    private func fittingHeight(for label: UILabel, width: CGFloat) -> CGFloat {
        label.systemLayoutSizeFitting(
            CGSize(width: width, height: UIView.layoutFittingCompressedSize.height),
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel
        ).height
    }

    private func updatePreferredContentHeight(_ height: CGFloat) {
        panelHeightConstraint?.constant = height
        preferredContentSize = CGSize(width: view.bounds.width, height: height)
    }

    private func toggleLocalTag(_ tagID: Int64) {
        if selectedLocalTagIDs.contains(tagID) {
            selectedLocalTagIDs.remove(tagID)
        } else {
            selectedLocalTagIDs.insert(tagID)
        }
        saveButton.isEnabled = true
        rebuildTagButtons()
    }

    @objc
    private func createLocalTagFromInput() {
        guard let repository else { return }
        let name = createTagField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !name.isEmpty else {
            pickerMessageLabel.text = "タグ名を入力してください"
            pickerMessageLabel.isHidden = false
            return
        }
        guard let tag = try? repository.createLocalTag(name: name) else {
            pickerMessageLabel.text = "タグを作成できませんでした"
            pickerMessageLabel.isHidden = false
            return
        }
        localTags = (try? repository.loadLocalTags()) ?? [tag]
        selectedLocalTagIDs.insert(tag.id)
        createTagField.text = ""
        pickerMessageLabel.text = nil
        pickerMessageLabel.isHidden = true
        saveButton.isEnabled = true
        rebuildTagButtons()
    }

    @objc
    private func savePendingShare() {
        guard let repository, let pendingShare else { return }
        let localTagIDs = Array(selectedLocalTagIDs)
        saveButton.isEnabled = false
        cancelButton.isEnabled = false
        createTagButton.isEnabled = false
        statusLabel.text = "保存しています…"
        Task {
            if pendingShare.urls.count > 1 || pendingShare.isBatch {
                var created = 0
                var duplicate = 0
                var restored = 0
                var failed = 0
                for url in pendingShare.urls {
                    let result = (try? repository.saveFromResolvedURL(
                        url,
                        localTagIDs: localTagIDs,
                        initialMemo: pendingShare.memo
                    ))
                        ?? SaveResult(result: .saveFailed)
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
                    total: pendingShare.urls.count,
                    created: created,
                    duplicate: duplicate,
                    restored: restored,
                    failed: failed
                )
                let report = ShareHandoffReport(
                    result: .batchProcessed,
                    entryID: nil,
                    normalizedURL: nil,
                    degradationNotice: pendingShare.degradationNotice,
                    batchSummary: summary,
                    createdAt: Date()
                )
                try? await ShareHandoffStore().write(report)
                let statusText = "\(summary.total)件を処理しました（新規\(summary.created) / 既存\(summary.duplicate) / 復元\(summary.restored) / 失敗\(summary.failed)）"
                await MainActor.run { updateStatus(statusText, finished: true) }
            } else {
                let result = (try? repository.saveFromResolvedURL(
                    pendingShare.urls[0],
                    localTagIDs: localTagIDs,
                    initialMemo: pendingShare.memo
                ))
                    ?? SaveResult(result: .saveFailed)
                let report = ShareHandoffReport(
                    result: result.result,
                    entryID: result.entryID,
                    normalizedURL: result.normalizedURL,
                    degradationNotice: pendingShare.degradationNotice,
                    batchSummary: nil,
                    createdAt: Date()
                )
                try? await ShareHandoffStore().write(report)
                await MainActor.run {
                    updateStatus(shareStatusText(result: result.result, degradation: pendingShare.degradationNotice), finished: true)
                }
            }
        }
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
            await MainActor.run { updateStatus("保存できる内容が見つかりませんでした", finished: true) }
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
            main = "保存できる内容が見つかりませんでした"
        case .batchProcessed:
            main = "処理しました"
        }

        guard degradation == .truncatedToFirstURL else {
            return main
        }
        return main + "\n共有内容に複数URLが含まれていたため、1件目のみ保存しました"
    }
}

private final class TagFlowView: UIView {
    private let horizontalSpacing: CGFloat = 8
    private let verticalSpacing: CGFloat = 8
    private let maxChipWidth: CGFloat = 210
    private var chipButtons: [UIButton] = []
    private var tagIDsByButton = [UIButton: Int64]()
    private var onToggle: ((Int64) -> Void)?

    func configure(
        tags: [LocalTagSummary],
        selectedTagIDs: Set<Int64>,
        onToggle: @escaping (Int64) -> Void
    ) {
        chipButtons.forEach { $0.removeFromSuperview() }
        chipButtons = []
        tagIDsByButton = [:]
        self.onToggle = onToggle

        for tag in tags {
            let button = UIButton(type: .system)
            let selected = selectedTagIDs.contains(tag.id)
            var configuration = UIButton.Configuration.plain()
            configuration.title = tag.name
            configuration.image = UIImage(systemName: selected ? "checkmark.circle.fill" : "circle")
            configuration.imagePlacement = .leading
            configuration.imagePadding = 8
            configuration.baseForegroundColor = .label
            configuration.background.backgroundColor = selected
                ? UIColor.systemBlue.withAlphaComponent(0.16)
                : UIColor.secondarySystemBackground
            configuration.contentInsets = NSDirectionalEdgeInsets(top: 14, leading: 14, bottom: 14, trailing: 16)
            button.configuration = configuration
            button.contentHorizontalAlignment = .leading
            button.titleLabel?.font = .preferredFont(forTextStyle: .title3)
            button.titleLabel?.adjustsFontForContentSizeCategory = true
            button.titleLabel?.lineBreakMode = .byTruncatingTail
            button.layer.cornerRadius = 16
            button.layer.borderWidth = 1
            button.layer.borderColor = selected ? UIColor.systemBlue.cgColor : UIColor.separator.cgColor
            button.clipsToBounds = true
            button.addTarget(self, action: #selector(toggleTag(_:)), for: .touchUpInside)
            addSubview(button)
            chipButtons.append(button)
            tagIDsByButton[button] = tag.id
        }

        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: measuredHeight(for: bounds.width))
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let rows = makeRows(maxWidth: bounds.width)
        var y: CGFloat = 0

        for row in rows {
            var x: CGFloat = 0
            for item in row.items {
                item.button.frame = CGRect(x: x, y: y, width: item.size.width, height: item.size.height)
                x += item.size.width + horizontalSpacing
            }
            y += row.height + verticalSpacing
        }
    }

    override func systemLayoutSizeFitting(_ targetSize: CGSize) -> CGSize {
        CGSize(width: targetSize.width, height: measuredHeight(for: targetSize.width))
    }

    func preferredHeight(for width: CGFloat) -> CGFloat {
        measuredHeight(for: width)
    }

    @objc
    private func toggleTag(_ sender: UIButton) {
        guard let tagID = tagIDsByButton[sender] else { return }
        onToggle?(tagID)
    }

    private func measuredHeight(for width: CGFloat) -> CGFloat {
        let rows = makeRows(maxWidth: width > 0 ? width : UIScreen.main.bounds.width - 40)
        return rows.reduce(CGFloat(0)) { partial, row in partial + row.height } +
            CGFloat(max(0, rows.count - 1)) * verticalSpacing
    }

    private func makeRows(maxWidth: CGFloat) -> [FlowRow] {
        guard maxWidth > 0 else { return [] }
        var rows: [FlowRow] = []
        var currentItems: [FlowItem] = []
        var currentWidth: CGFloat = 0
        var currentHeight: CGFloat = 0

        for button in chipButtons {
            let fittingSize = button.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize)
            let itemWidth = min(maxChipWidth, fittingSize.width)
            let itemHeight = fittingSize.height
            let nextWidth = currentItems.isEmpty ? itemWidth : currentWidth + horizontalSpacing + itemWidth

            if !currentItems.isEmpty && nextWidth > maxWidth {
                rows.append(FlowRow(items: currentItems, height: currentHeight))
                currentItems = []
                currentWidth = 0
                currentHeight = 0
            }

            currentItems.append(FlowItem(button: button, size: CGSize(width: itemWidth, height: itemHeight)))
            currentWidth = currentItems.count == 1 ? itemWidth : currentWidth + horizontalSpacing + itemWidth
            currentHeight = max(currentHeight, itemHeight)
        }

        if !currentItems.isEmpty {
            rows.append(FlowRow(items: currentItems, height: currentHeight))
        }
        return rows
    }

    private struct FlowItem {
        let button: UIButton
        let size: CGSize
    }

    private struct FlowRow {
        let items: [FlowItem]
        let height: CGFloat
    }
}

private struct ShareExtensionPayload {
    let candidateGroups: ShareCandidateGroups
    let isExplicitMultiShare: Bool

    var tagSharePayload: TagSharePayload? {
        for group in candidateGroups.orderedGroups {
            for candidate in group {
                let trimmed = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
                guard trimmed.hasPrefix("{"), let data = trimmed.data(using: .utf8) else { continue }
                if let payload = try? JSONDecoder().decode(TagSharePayload.self, from: data),
                   payload.urlsaverVersion == 1 {
                    return payload
                }
            }
        }
        return nil
    }
}

private struct PendingExtensionShare {
    let urls: [String]
    let isBatch: Bool
    let memo: String?
    let degradationNotice: ShareDegradationNotice?

    init(
        urls: [String],
        isBatch: Bool,
        memo: String? = nil,
        degradationNotice: ShareDegradationNotice?
    ) {
        self.urls = urls
        self.isBatch = isBatch
        self.memo = memo
        self.degradationNotice = degradationNotice
    }
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
