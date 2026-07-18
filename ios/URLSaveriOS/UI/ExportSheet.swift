import Foundation
import SwiftUI
import UniformTypeIdentifiers

func exportTodayDateInput(now: Date = Date(), calendar: Calendar = .current) -> String {
    let formatter = DateFormatter()
    formatter.calendar = calendar
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = calendar.timeZone
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter.string(from: now)
}

func cleanupStaleChatGptTemporaryDirectories(
    in temporaryDirectory: URL = FileManager.default.temporaryDirectory,
    now: Date = Date(),
    maximumAge: TimeInterval = 7 * 24 * 60 * 60
) {
    let cutoff = now.addingTimeInterval(-maximumAge)
    let resourceKeys: Set<URLResourceKey> = [.contentModificationDateKey, .isDirectoryKey]
    guard let candidates = try? FileManager.default.contentsOfDirectory(
        at: temporaryDirectory,
        includingPropertiesForKeys: Array(resourceKeys),
        options: [.skipsHiddenFiles]
    ) else { return }
    for candidate in candidates where candidate.lastPathComponent.hasPrefix("rinbam-chatgpt-task-") {
        guard let values = try? candidate.resourceValues(forKeys: resourceKeys),
              values.isDirectory == true,
              let modifiedAt = values.contentModificationDate,
              modifiedAt < cutoff else { continue }
        try? FileManager.default.removeItem(at: candidate)
    }
}

struct ExportSheet: View {
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var model: URLSaverAppModel

    @State private var scope: URLExportScope = .all
    @State private var selectedTagIDs: Set<String> = []
    @State private var recordStateFilter: URLExportRecordStateFilter = .both
    @State private var serviceType: ServiceType = .all
    @State private var onlyWithMemo = false
    @State private var dateFromInput = ""
    @State private var dateToInput = ""
    @State private var selectedFormat: URLExportOutputFormat = .zip
    @State private var selectedDestination: ExportDestination = .shareSheet
    @State private var isStandardExporting = false
    @State private var isPreparingChatGpt = false
    @State private var errorMessage: String?
    @State private var successMessage: String?
    @State private var shareItems: [Any] = []
    @State private var isShowingShareSheet = false
    @State private var isSharingPreparedChatGptFile = false
    @State private var isShowingFileExporter = false
    @State private var fileExportDocument: ExportFileDocument?
    @State private var fileExportType: UTType = .zip
    @State private var fileExportDefaultName = "urlsaver-export"
    @State private var exportMode: ExportMode = .standard
    @State private var selectedChatGptLocalTagIDs: Set<Int64> = []
    @State private var chatGptPreview: ChatGptExportPreview?
    @State private var chatGptPreviewError: String?
    @State private var isLoadingChatGptPreview = false
    @State private var chatGptPreviewRequestID = UUID()
    @State private var chatGptGenerationID = UUID()
    @State private var chatGptPreviewTask: Task<Void, Never>?
    @State private var chatGptPreparationTask: Task<Void, Never>?
    @State private var hasConfirmedChatGptPreview = false
    @State private var preparedChatGptFileURL: URL?
    @State private var preparedChatGptEntryCount: Int?
    @State private var preparedChatGptSnapshotToken: String?
    @State private var preparedChatGptSelectedTagIDs: Set<Int64> = []
    @State private var preparedChatGptGenerationID: UUID?

    private var tagOptions: [URLExportTagOption] {
        URLExportArchiveBuilder.buildAvailableTags(
            localTags: model.localTags,
            localTagAssignments: model.localTagAssignments,
            sharedTags: model.sharedTags
        )
    }

    private var selectedItems: [SelectedExportItem] {
        var items: [SelectedExportItem] = []
        switch scope {
        case .all:
            if serviceType == .all && selectedTagIDs.isEmpty {
                items.append(SelectedExportItem(label: "すべて", kind: .scope))
            }
        case .singleTag, .multipleTags: break
        case .sharedTagsOnly: items.append(SelectedExportItem(label: "共有タグ", kind: .scope))
        }
        if serviceType != .all {
            items.append(SelectedExportItem(label: serviceType.displayName, kind: .service))
        }
        let tagItems = tagOptions
            .filter { selectedTagIDs.contains($0.id) }
            .map { SelectedExportItem(label: $0.name, kind: .tag, tagID: $0.id) }
        items.append(contentsOf: tagItems)

        var seen: Set<SelectedExportItem> = []
        return items.filter { seen.insert($0).inserted }
    }

    private var chatGptLocalTags: [LocalTagSummary] {
        model.localTags.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        ScreenContainer {
            VStack(spacing: 0) {
                exportHeader
                exportModeSelector

                if exportMode == .standard {
                    ScrollView(showsIndicators: false) {
                        VStack(alignment: .leading, spacing: 14) {
                            sectionLabel("選択中")
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(selectedItems) { item in
                                        selectedChip(item)
                                    }
                                }
                                .padding(.trailing, 16)
                            }

                            sectionLabel("クイック選択")
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 9) {
                                    presetTile(label: "すべて", icon: "archivebox", selected: scope == .all && serviceType == .all) {
                                        scope = .all
                                        serviceType = .all
                                        selectedTagIDs.removeAll()
                                    }
                                    presetTile(label: "共有タグだけ", icon: "person.2", selected: scope == .sharedTagsOnly) {
                                        scope = .sharedTagsOnly
                                        selectedTagIDs.removeAll()
                                    }
                                    presetTile(label: "今日", icon: "calendar", selected: false) {
                                        let today = exportTodayDateInput()
                                        dateFromInput = today
                                        dateToInput = today
                                    }
                                    ForEach(servicePresetOrder, id: \.self) { item in
                                        presetTile(label: item.displayName, icon: "tag", selected: serviceType == item) {
                                            scope = .all
                                            serviceType = item
                                            selectedTagIDs.removeAll()
                                        }
                                    }
                                }
                                .padding(.trailing, 16)
                            }

                            sectionLabel("タグを選択")
                            if tagOptions.isEmpty {
                                Text("選択できるタグがありません")
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundStyle(AppPalette.textSecondary)
                            } else {
                                TagFlowLayout(horizontalSpacing: 8, verticalSpacing: 8) {
                                    ForEach(tagOptions) { tag in
                                        tagCell(tag)
                                    }
                                }
                            }

                            if let errorMessage {
                                Text(errorMessage)
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(AppPalette.danger)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            if let successMessage {
                                Text(successMessage)
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(AppPalette.textSecondary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }

                            exportControlSheet
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 30)
                    }
                } else {
                    chatGptExportContent
                }
            }
        }
        .sheet(isPresented: $isShowingShareSheet, onDismiss: {
            let shouldDeleteChatGptFile = isSharingPreparedChatGptFile
            shareItems = []
            isSharingPreparedChatGptFile = false
            if shouldDeleteChatGptFile {
                invalidatePreparedChatGptFile(force: true)
            }
        }) {
            ActivityShareSheet(items: shareItems)
        }
        .fileExporter(
            isPresented: $isShowingFileExporter,
            document: fileExportDocument,
            contentType: fileExportType,
            defaultFilename: fileExportDefaultName
        ) { result in
            switch result {
            case .success(let url):
                successMessage = "\(url.lastPathComponent) を保存しました"
            case .failure(let error):
                errorMessage = (error as? LocalizedError)?.errorDescription ?? "ファイルに保存できませんでした。"
            }
            fileExportDocument = nil
        }
        .onDisappear {
            if !isShowingFileExporter && !isShowingShareSheet {
                chatGptPreviewTask?.cancel()
                chatGptPreparationTask?.cancel()
                invalidatePreparedChatGptFile(force: true)
            }
        }
    }

    private var exportModeSelector: some View {
        HStack(spacing: 8) {
            ForEach(ExportMode.allCases) { mode in
                let selected = exportMode == mode
                Button {
                    selectExportMode(mode)
                } label: {
                    HStack(spacing: 7) {
                        Image(systemName: mode.icon)
                            .font(.system(size: 15, weight: .semibold))
                        Text(mode.label)
                            .font(.system(size: 14, weight: .heavy, design: .rounded))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundStyle(selected ? AppPalette.primaryStrong : AppPalette.textSecondary)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .background(
                        selected ? AppPalette.primary.opacity(0.18) : AppPalette.surfaceSoft,
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(selected ? AppPalette.primaryStrong : AppPalette.outlineSoft, lineWidth: selected ? 1.5 : 1)
                    )
                }
                .buttonStyle(.plain)
                .disabled(isStandardExporting || isPreparingChatGpt)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }

    private var chatGptExportContent: some View {
        ScrollView(showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("確認してから共有シートへ")
                        .font(.system(size: 17, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                    Text("自作タグで選んだ保存リンクをZIPにします。質問はりんばむでは入力せず、共有先でChatGPTを選んだ後に入力してください。")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("ChatGPTへの自動送信、アカウント接続、モデル選択は行いません。")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(AppPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(14)
                .background(AppPalette.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .stroke(AppPalette.outlineSoft, lineWidth: 1)
                )

                sectionLabel("1. 自作タグを選択")
                Text("1つ以上選んでください。複数選択した場合は、いずれかのタグが付いたリンクを対象にします（OR）。")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                if chatGptLocalTags.isEmpty {
                    Text("選択できる自作タグがありません。先に自作タグを作成してください。")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(AppPalette.danger)
                } else {
                    TagFlowLayout(horizontalSpacing: 8, verticalSpacing: 8) {
                        ForEach(chatGptLocalTags) { tag in
                            chatGptTagCell(tag)
                        }
                    }
                }

                sectionLabel("2. 送る内容を確認")
                fixedChatGptContentCard(
                    title: "含まれるもの（固定）",
                    icon: "checkmark.circle",
                    items: [
                        "条件を満たす保存中のURL・タイトル・選択した自作タグ",
                        "保存時点の要約・抜粋・メモ抜粋など、既存のAI-safe情報",
                        "検出できたメールアドレス・電話番号・token・secret・Supabase値・JWT・ローカルパスは伏せ字"
                    ]
                )
                Text("自動検出ですべての機密値を保証できないため、共有前に対象URLを確認してください。")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(AppPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                fixedChatGptContentCard(
                    title: "含まれないもの（固定）",
                    icon: "nosign",
                    items: [
                        "質問文、ChatGPTのアカウント・モデル設定",
                        "PDF・画像本体、取得本文の全文、raw prompt",
                        "共有タグ・参加者情報、アーカイブ、削除待ち、共有参照があるリンク"
                    ]
                )
                if isLoadingChatGptPreview {
                    HStack(spacing: 10) {
                        ProgressView().tint(AppPalette.primaryStrong)
                        Text("対象を確認しています…")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(AppPalette.textSecondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                if selectedChatGptLocalTagIDs.isEmpty {
                    Text("自作タグを1つ以上選ぶと対象URLを表示します。")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(AppPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if let preview = chatGptPreview {
                    HStack(spacing: 8) {
                        previewCountBadge(label: "対象", count: preview.eligibleCount, highlighted: true)
                        previewCountBadge(label: "除外", count: preview.excludedCount, highlighted: false)
                    }

                    if preview.excludedCount > 0 {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("除外理由")
                                .font(.system(size: 14, weight: .heavy, design: .rounded))
                                .foregroundStyle(AppPalette.textSecondary)
                            ForEach(ChatGptExportExclusionReason.allCases, id: \.rawValue) { reason in
                                if let count = preview.exclusionReasonCounts[reason], count > 0 {
                                    Text("・\(reason.displayName)：\(count)件")
                                        .font(.system(size: 14, weight: .medium))
                                        .foregroundStyle(AppPalette.textSecondary)
                                }
                            }
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }

                    if !preview.eligibleItems.isEmpty {
                        Text("共有される保存リンク")
                            .font(.system(size: 14, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textSecondary)
                        ScrollView(.vertical, showsIndicators: true) {
                            LazyVStack(spacing: 8) {
                                ForEach(preview.eligibleItems) { item in
                                    chatGptPreviewItem(item)
                                }
                            }
                        }
                        .frame(height: min(max(CGFloat(preview.eligibleItems.count) * 320, 280), 560))
                    } else {
                        Text("現在の選択でChatGPTに送れる保存リンクは0件です。タグの選択を変えてください。")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(AppPalette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    if !preview.eligibleItems.isEmpty {
                        Toggle(isOn: $hasConfirmedChatGptPreview) {
                            Text("対象URLと表示内容を確認し、未知の秘密が含まれていないことを確認しました")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(AppPalette.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .tint(AppPalette.primaryStrong)
                        .padding(14)
                        .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                }

                if let chatGptPreviewError {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(chatGptPreviewError)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(AppPalette.danger)
                            .fixedSize(horizontal: false, vertical: true)
                        Button {
                            refreshChatGptPreview()
                        } label: {
                            Label("もう一度確認", systemImage: "arrow.clockwise")
                                .font(.system(size: 14, weight: .heavy, design: .rounded))
                                .foregroundStyle(AppPalette.textPrimary)
                                .padding(.horizontal, 14)
                                .frame(minHeight: 44)
                                .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                                        .stroke(AppPalette.outlineSoft, lineWidth: 1)
                                )
                        }
                        .buttonStyle(.plain)
                        .disabled(isLoadingChatGptPreview)
                    }
                }

                if let errorMessage {
                    Text(errorMessage)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(AppPalette.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }

                sectionLabel("3. ChatGPT用ZIPを作成")
                VStack(alignment: .leading, spacing: 10) {
                    AppActionButton(
                        tone: .primary,
                        enabled: canPrepareChatGptFile
                    ) {
                        prepareChatGptFile()
                    } label: {
                        if isPreparingChatGpt {
                            HStack(spacing: 8) {
                                ProgressView().tint(AppPalette.textPrimary)
                                Text("作成中…")
                            }
                        } else {
                            Text("ChatGPT用ファイルを作成")
                        }
                    }
                    Text("ここではZIPを作成するだけで、まだ共有されません。")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)

                    if let preparedChatGptEntryCount {
                        Label("\(preparedChatGptEntryCount)件のChatGPT用ZIPを作成しました", systemImage: "checkmark.circle.fill")
                            .font(.system(size: 14, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.primaryStrong)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(AppPalette.primary.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }

                    sectionLabel("4. ChatGPTへ共有")
                    AppActionButton(
                        tone: .secondary,
                        enabled: canSendToChatGpt
                    ) {
                        sharePreparedChatGptFile()
                    } label: {
                        Text("ChatGPTに送る")
                    }
                    Text("共有先一覧でChatGPTを選び、ChatGPT側で質問を入力して送信してください。")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(16)
                .background(AppPalette.surface, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 30, style: .continuous)
                        .stroke(AppPalette.outlineSoft, lineWidth: 1.2)
                )
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 30)
        }
    }

    private var canSendToChatGpt: Bool {
        preparedChatGptFileURL.map { FileManager.default.fileExists(atPath: $0.path) } == true &&
            preparedChatGptSnapshotToken == chatGptPreview?.snapshotToken &&
            preparedChatGptSelectedTagIDs == selectedChatGptLocalTagIDs &&
            preparedChatGptGenerationID == chatGptGenerationID &&
            hasConfirmedChatGptPreview &&
            !isPreparingChatGpt &&
            !isStandardExporting
    }

    private var canPrepareChatGptFile: Bool {
        !isPreparingChatGpt &&
            !isStandardExporting &&
            !isLoadingChatGptPreview &&
            chatGptPreviewError == nil &&
            hasConfirmedChatGptPreview &&
            chatGptPreview?.eligibleItems.isEmpty == false
    }

    private func fixedChatGptContentCard(title: String, icon: String, items: [String]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(title, systemImage: icon)
                .font(.system(size: 15, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
            ForEach(items, id: \.self) { item in
                Text("・\(item)")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AppPalette.outlineSoft, lineWidth: 1)
        )
    }

    private func previewCountBadge(label: String, count: Int, highlighted: Bool) -> some View {
        Text("\(label) \(count)件")
            .font(.system(size: 15, weight: .heavy, design: .rounded))
            .foregroundStyle(highlighted ? AppPalette.primaryStrong : AppPalette.textSecondary)
            .padding(.horizontal, 12)
            .frame(minHeight: 38)
            .background(
                highlighted ? AppPalette.primary.opacity(0.16) : AppPalette.surfaceSoft,
                in: Capsule()
            )
    }

    private func chatGptPreviewItem(_ item: ChatGptExportPreviewItem) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(item.title)
                .font(.system(size: 15, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(item.localTagNames.isEmpty ? "自作タグなし" : "自作タグ：\(item.localTagNames.joined(separator: "、"))")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(AppPalette.textSecondary)
            Text("ZIPに入る伏せ字後のJSON内容")
                .font(.system(size: 12, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textSecondary)
            Text(item.archiveEntryJSON)
                .font(.system(.caption, design: .monospaced, weight: .regular))
                .foregroundStyle(AppPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
        .padding(12)
        .background(AppPalette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(AppPalette.outlineSoft, lineWidth: 1)
        )
    }

    private var exportControlSheet: some View {
        VStack(alignment: .leading, spacing: 14) {
            sectionLabel("書き出し形式")
            HStack(spacing: 8) {
                ForEach(URLExportOutputFormat.allCases, id: \.rawValue) { item in
                    formatButton(item)
                }
            }

            sectionLabel("保存先")
            VStack(spacing: 8) {
                ForEach(ExportDestination.allCases) { item in
                    destinationRow(item)
                }
            }

            AppActionButton(tone: .primary, enabled: !isStandardExporting && !isPreparingChatGpt) {
                exportArchive()
            } label: {
                if isStandardExporting {
                    ProgressView().tint(AppPalette.textPrimary)
                } else {
                    Text("書き出す")
                }
            }
        }
        .padding(16)
        .background(AppPalette.surface, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .stroke(AppPalette.outlineSoft, lineWidth: 1.2)
        )
    }

    private var exportHeader: some View {
        HStack(alignment: .center, spacing: 8) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .heavy))
                    .foregroundStyle(AppPalette.textPrimary)
                    .frame(width: 42, height: 42)
                    .background(AppPalette.surfaceSoft, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("閉じる")

            Text("エクスポート")
                .font(.system(size: 27, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
                .layoutPriority(2)

            Spacer(minLength: 6)

            Text("ChatGPT、\nCodex、Claudeにも\n共有できるよ！")
                .font(.system(size: 11, weight: .semibold, design: .rounded))
                .foregroundStyle(AppPalette.textSecondary.opacity(0.72))
                .multilineTextAlignment(.trailing)
                .lineSpacing(1)
                .fixedSize(horizontal: false, vertical: true)
                .layoutPriority(1)
        }
        .padding(.leading, 12)
        .padding(.trailing, 16)
        .padding(.top, 8)
        .padding(.bottom, 12)
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 17, weight: .heavy, design: .rounded))
            .foregroundStyle(AppPalette.textPrimary)
    }

    private func presetTile(label: String, icon: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 7) {
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .semibold))
                Text(label)
                    .font(.system(size: 14, weight: .heavy, design: .rounded))
                    .lineLimit(1)
            }
            .foregroundStyle(selected ? AppPalette.primaryStrong : AppPalette.textPrimary)
            .padding(.horizontal, 12)
            .frame(height: 48)
            .background(selected ? AppPalette.primary.opacity(0.18) : AppPalette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(selected ? AppPalette.primaryStrong : AppPalette.outlineSoft, lineWidth: selected ? 1.5 : 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func selectedChip(_ item: SelectedExportItem) -> some View {
        HStack(spacing: 6) {
            Text(item.label)
                .font(.system(size: 14, weight: .heavy, design: .rounded))
            Button {
                removeSelectedItem(item)
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .frame(width: 18, height: 18)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(item.label)を外す")
        }
        .foregroundStyle(AppPalette.primaryStrong)
        .padding(.horizontal, 12)
        .frame(height: 34)
        .background(AppPalette.primary.opacity(0.18), in: Capsule())
        .overlay(Capsule().stroke(AppPalette.primaryStrong.opacity(0.45), lineWidth: 1))
    }

    private func removeSelectedItem(_ item: SelectedExportItem) {
        switch item.kind {
        case .scope:
            scope = .all
            selectedTagIDs.removeAll()
        case .service:
            serviceType = .all
        case .tag:
            if let tagID = item.tagID {
                selectedTagIDs.remove(tagID)
            }
            if selectedTagIDs.isEmpty {
                scope = .all
            }
        }
    }

    private func tagCell(_ tag: URLExportTagOption) -> some View {
        let selected = selectedTagIDs.contains(tag.id)
        return Button {
            if scope != .multipleTags && scope != .singleTag {
                scope = .multipleTags
            }
            toggleTag(tag.id)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: selected ? "checkmark" : "tag")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(selected ? AppPalette.primaryStrong : AppPalette.textSecondary)
                Text(tag.name)
                    .font(.system(size: 15, weight: .heavy, design: .rounded))
                    .foregroundStyle(AppPalette.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 48)
            .background(selected ? AppPalette.primary.opacity(0.14) : AppPalette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(selected ? AppPalette.primaryStrong : AppPalette.outlineSoft, lineWidth: selected ? 1.5 : 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func chatGptTagCell(_ tag: LocalTagSummary) -> some View {
        let selected = selectedChatGptLocalTagIDs.contains(tag.id)
        return Button {
            if selected {
                selectedChatGptLocalTagIDs.remove(tag.id)
            } else {
                selectedChatGptLocalTagIDs.insert(tag.id)
            }
            refreshChatGptPreview()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: selected ? "checkmark" : "tag")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(selected ? AppPalette.primaryStrong : AppPalette.textSecondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tag.name)
                        .font(.system(size: 15, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Text("\(tag.activeURLCount)件")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                }
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 52)
            .background(selected ? AppPalette.primary.opacity(0.14) : AppPalette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(selected ? AppPalette.primaryStrong : AppPalette.outlineSoft, lineWidth: selected ? 1.5 : 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(tag.name)、\(tag.activeURLCount)件")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func formatButton(_ item: URLExportOutputFormat) -> some View {
        let selected = selectedFormat == item
        return Button {
            selectedFormat = item
        } label: {
            VStack(spacing: 5) {
                Image(systemName: item.icon)
                    .font(.system(size: 18, weight: .semibold))
                Text(item.rawValue)
                    .font(.system(size: 14, weight: .heavy, design: .rounded))
            }
            .foregroundStyle(selected ? AppPalette.primaryStrong : AppPalette.textPrimary)
            .frame(maxWidth: .infinity, minHeight: 68)
            .background(selected ? AppPalette.primary.opacity(0.16) : AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(selected ? AppPalette.primaryStrong : AppPalette.outlineSoft, lineWidth: selected ? 1.5 : 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func destinationRow(_ item: ExportDestination) -> some View {
        let selected = selectedDestination == item
        return Button {
            selectedDestination = item
        } label: {
            HStack(spacing: 10) {
                Image(systemName: item.icon)
                    .font(.system(size: 17, weight: .semibold))
                Text(item.label)
                    .font(.system(size: 15, weight: .heavy, design: .rounded))
                Spacer()
                if selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 15, weight: .bold))
                }
            }
            .foregroundStyle(selected ? AppPalette.primaryStrong : AppPalette.textPrimary)
            .padding(.horizontal, 12)
            .frame(height: 50)
            .background(selected ? AppPalette.primary.opacity(0.14) : AppPalette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(selected ? AppPalette.primaryStrong : AppPalette.outlineSoft, lineWidth: selected ? 1.4 : 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func exportSection<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        AppPanel {
            Text(title)
                .font(.system(size: 17, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
            content()
        }
    }

    private func chipWrap<Data: RandomAccessCollection, Content: View>(
        _ data: Data,
        @ViewBuilder content: @escaping (Data.Element) -> Content
    ) -> some View where Data.Element: Identifiable {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 116), spacing: 8)], alignment: .leading, spacing: 8) {
            ForEach(data) { item in
                content(item)
            }
        }
    }

    private func chipWrap<Data: RandomAccessCollection, Content: View>(
        _ data: Data,
        @ViewBuilder content: @escaping (Data.Element) -> Content
    ) -> some View where Data.Element: Hashable {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 116), spacing: 8)], alignment: .leading, spacing: 8) {
            ForEach(Array(data), id: \.self) { item in
                content(item)
            }
        }
    }

    private func dateField(title: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 14, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textSecondary)
            TextField("YYYY-MM-DD", text: text)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AppPalette.textPrimary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.numbersAndPunctuation)
                .padding(.horizontal, 12)
                .padding(.vertical, 13)
                .background(AppPalette.background, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(AppPalette.outlineSoft, lineWidth: 1.2)
                )
        }
    }

    private func toggleTag(_ tagID: String) {
        if selectedTagIDs.contains(tagID) {
            selectedTagIDs.remove(tagID)
            return
        }
        if scope == .singleTag {
            selectedTagIDs = [tagID]
        } else {
            selectedTagIDs.insert(tagID)
        }
    }

    private func selectExportMode(_ mode: ExportMode) {
        guard exportMode != mode else { return }
        exportMode = mode
        errorMessage = nil
        successMessage = nil
        if mode == .chatGpt, chatGptPreview == nil, preparedChatGptFileURL == nil {
            refreshChatGptPreview()
        }
    }

    private func refreshChatGptPreview() {
        chatGptPreviewTask?.cancel()
        chatGptPreparationTask?.cancel()
        let selectedTagIDs = selectedChatGptLocalTagIDs
        let requestID = UUID()
        let generationID = UUID()
        chatGptPreviewRequestID = requestID
        chatGptGenerationID = generationID
        hasConfirmedChatGptPreview = false
        invalidatePreparedChatGptFile()
        chatGptPreview = nil
        chatGptPreviewError = nil
        errorMessage = nil
        isPreparingChatGpt = false
        guard !selectedTagIDs.isEmpty else {
            isLoadingChatGptPreview = false
            return
        }
        isLoadingChatGptPreview = true
        let task = Task { @MainActor in
            do {
                let preview = try await model.chatGptExportPreview(selectedLocalTagIDs: selectedTagIDs)
                try Task.checkCancellation()
                guard requestID == chatGptPreviewRequestID,
                      generationID == chatGptGenerationID,
                      selectedTagIDs == selectedChatGptLocalTagIDs else { return }
                chatGptPreview = preview
            } catch is CancellationError {
                return
            } catch {
                guard requestID == chatGptPreviewRequestID,
                      generationID == chatGptGenerationID,
                      selectedTagIDs == selectedChatGptLocalTagIDs else { return }
                chatGptPreviewError = (error as? LocalizedError)?.errorDescription ?? "対象を確認できませんでした。もう一度お試しください。"
            }
            guard requestID == chatGptPreviewRequestID,
                  generationID == chatGptGenerationID,
                  selectedTagIDs == selectedChatGptLocalTagIDs else { return }
            isLoadingChatGptPreview = false
            chatGptPreviewTask = nil
        }
        chatGptPreviewTask = task
    }

    private func prepareChatGptFile() {
        errorMessage = nil
        successMessage = nil
        guard hasConfirmedChatGptPreview,
              let preview = chatGptPreview,
              !preview.eligibleItems.isEmpty else {
            chatGptPreviewError = "ChatGPTに送れる保存リンクがありません。タグを選び、対象を確認してからもう一度お試しください。"
            return
        }

        chatGptPreparationTask?.cancel()
        let selectedTagIDs = selectedChatGptLocalTagIDs
        let expectedSnapshotToken = preview.snapshotToken
        let expectedEntryCount = preview.eligibleCount
        let generationID = chatGptGenerationID
        invalidatePreparedChatGptFile()
        isPreparingChatGpt = true
        let task = Task { @MainActor in
            var generatedFileURL: URL?
            do {
                let archive = try await model.prepareChatGptExportArchive(
                    selectedLocalTagIDs: selectedTagIDs,
                    expectedSnapshotToken: expectedSnapshotToken
                )
                try Task.checkCancellation()
                guard archive.entryCount == expectedEntryCount else {
                    throw URLExportError.invalidRequest("対象の保存リンクが更新されました。内容を確認して、もう一度お試しください。")
                }
                let fileURL = try await Task.detached(priority: .utility) {
                    cleanupStaleChatGptTemporaryDirectories()
                    let directoryURL = FileManager.default.temporaryDirectory
                        .appendingPathComponent("rinbam-chatgpt-task-\(generationID.uuidString)", isDirectory: true)
                    do {
                        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
                        let fileURL = directoryURL.appendingPathComponent(archive.fileName)
                        try archive.bytes.write(to: fileURL, options: [.atomic])
                        return fileURL
                    } catch {
                        try? FileManager.default.removeItem(at: directoryURL)
                        throw error
                    }
                }.value
                generatedFileURL = fileURL
                try Task.checkCancellation()
                guard selectedTagIDs == selectedChatGptLocalTagIDs,
                      generationID == chatGptGenerationID,
                      chatGptPreview?.snapshotToken == expectedSnapshotToken,
                      hasConfirmedChatGptPreview else {
                    removeChatGptTemporaryFile(at: fileURL)
                    generatedFileURL = nil
                    throw URLExportError.invalidRequest("選択または対象の内容が変わりました。内容を確認して、もう一度お試しください。")
                }
                preparedChatGptFileURL = fileURL
                preparedChatGptEntryCount = archive.entryCount
                preparedChatGptSnapshotToken = expectedSnapshotToken
                preparedChatGptSelectedTagIDs = selectedTagIDs
                preparedChatGptGenerationID = generationID
                generatedFileURL = nil
                successMessage = "\(archive.entryCount)件のZIPを作成しました"
            } catch is CancellationError {
                if let generatedFileURL {
                    removeChatGptTemporaryFile(at: generatedFileURL)
                }
            } catch {
                if let generatedFileURL {
                    removeChatGptTemporaryFile(at: generatedFileURL)
                }
                guard generationID == chatGptGenerationID else { return }
                let message = (error as? LocalizedError)?.errorDescription ?? "ChatGPT用ZIPを作成できませんでした。もう一度お試しください。"
                isPreparingChatGpt = false
                refreshChatGptPreview()
                errorMessage = message
            }
            if generationID == chatGptGenerationID {
                isPreparingChatGpt = false
                chatGptPreparationTask = nil
            }
        }
        chatGptPreparationTask = task
    }

    private func sharePreparedChatGptFile() {
        guard let preparedChatGptFileURL,
              FileManager.default.fileExists(atPath: preparedChatGptFileURL.path),
              preparedChatGptSnapshotToken == chatGptPreview?.snapshotToken,
              preparedChatGptSelectedTagIDs == selectedChatGptLocalTagIDs,
              preparedChatGptGenerationID == chatGptGenerationID,
              hasConfirmedChatGptPreview else {
            errorMessage = "先にChatGPT用ファイルを作成してください。"
            return
        }
        errorMessage = nil
        shareItems = [preparedChatGptFileURL]
        isSharingPreparedChatGptFile = true
        isShowingShareSheet = true
    }

    private func invalidatePreparedChatGptFile(force: Bool = false) {
        guard force || !isShowingShareSheet else { return }
        if let preparedChatGptFileURL {
            removeChatGptTemporaryFile(at: preparedChatGptFileURL)
        }
        preparedChatGptFileURL = nil
        preparedChatGptEntryCount = nil
        preparedChatGptSnapshotToken = nil
        preparedChatGptSelectedTagIDs = []
        preparedChatGptGenerationID = nil
        successMessage = nil
    }

    private func removeChatGptTemporaryFile(at fileURL: URL) {
        let directoryURL = fileURL.deletingLastPathComponent()
        if directoryURL.lastPathComponent.hasPrefix("rinbam-chatgpt-task-") {
            try? FileManager.default.removeItem(at: directoryURL)
        } else if FileManager.default.fileExists(atPath: fileURL.path) {
            try? FileManager.default.removeItem(at: fileURL)
        }
    }

    private func exportArchive() {
        errorMessage = nil
        successMessage = nil
        do {
            let request = URLExportRequest(
                scope: scope,
                selectedTagIDs: selectedTagIDs,
                recordStateFilter: recordStateFilter,
                serviceType: serviceType == .all ? nil : serviceType,
                onlyWithMemo: onlyWithMemo,
                dateFrom: try parseDate(dateFromInput),
                dateTo: try parseDate(dateToInput),
                outputFormat: selectedFormat
            )
            isStandardExporting = true
            Task {
                do {
                    let archive = try await model.prepareExportArchive(request: request)
                    let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent(archive.fileName)
                    try archive.bytes.write(to: fileURL, options: [.atomic])
                    switch selectedDestination {
                    case .shareSheet:
                        shareItems = [fileURL]
                        isSharingPreparedChatGptFile = false
                        isShowingShareSheet = true
                    case .file:
                        fileExportDocument = ExportFileDocument(data: archive.bytes)
                        fileExportType = archive.mimeType == "application/json" ? .json : .zip
                        fileExportDefaultName = archive.fileName
                        isShowingFileExporter = true
                    }
                    isStandardExporting = false
                } catch {
                    isStandardExporting = false
                    errorMessage = (error as? LocalizedError)?.errorDescription ?? "エクスポートできませんでした。"
                }
            }
        } catch {
            isStandardExporting = false
            errorMessage = (error as? LocalizedError)?.errorDescription ?? "エクスポートできませんでした。"
        }
    }

    private func parseDate(_ text: String) throws -> Date? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        guard let date = formatter.date(from: trimmed) else {
            throw URLExportError.invalidRequest("日付はYYYY-MM-DD形式で入力してください。")
        }
        return date
    }
}

private extension URLExportOutputFormat {
    var icon: String {
        switch self {
        case .zip: return "archivebox"
        case .json: return "doc.text"
        }
    }
}

private enum ExportMode: String, CaseIterable, Identifiable {
    case standard
    case chatGpt

    var id: String { rawValue }

    var label: String {
        switch self {
        case .standard: return "通常の書き出し"
        case .chatGpt: return "ChatGPTに聞く"
        }
    }

    var icon: String {
        switch self {
        case .standard: return "square.and.arrow.up"
        case .chatGpt: return "bubble.left.and.text.bubble.right"
        }
    }
}

private enum ExportDestination: CaseIterable, Identifiable {
    case shareSheet
    case file

    var id: String { label }

    var label: String {
        switch self {
        case .shareSheet: return "共有シート"
        case .file: return "ファイルに保存"
        }
    }

    var icon: String {
        switch self {
        case .shareSheet: return "square.and.arrow.up"
        case .file: return "folder"
        }
    }
}

private let servicePresetOrder: [ServiceType] = [.tiktok, .instagram, .youtube, .x, .web]

private struct SelectedExportItem: Identifiable, Hashable {
    let label: String
    let kind: SelectedExportItemKind
    var tagID: String?

    var id: String {
        "\(kind.rawValue):\(tagID ?? label)"
    }
}

private enum SelectedExportItemKind: String, Hashable {
    case scope
    case service
    case tag
}

private struct TagFlowLayout: Layout {
    var horizontalSpacing: CGFloat
    var verticalSpacing: CGFloat

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        let rows = rows(in: maxWidth, subviews: subviews)
        let width = rows.map(\.width).max() ?? 0
        let height = rows.reduce(CGFloat.zero) { total, row in
            total + row.height
        } + CGFloat(max(rows.count - 1, 0)) * verticalSpacing
        return CGSize(width: width, height: height)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        var y = bounds.minY
        for row in rows(in: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for item in row.items {
                subviews[item.index].place(
                    at: CGPoint(x: x, y: y),
                    proposal: ProposedViewSize(item.size)
                )
                x += item.size.width + horizontalSpacing
            }
            y += row.height + verticalSpacing
        }
    }

    private func rows(in maxWidth: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var current = Row()

        for index in subviews.indices {
            let measured = subviews[index].sizeThatFits(.unspecified)
            let size = CGSize(width: min(measured.width, maxWidth), height: measured.height)
            let nextWidth = current.items.isEmpty ? size.width : current.width + horizontalSpacing + size.width

            if !current.items.isEmpty && nextWidth > maxWidth {
                rows.append(current)
                current = Row()
            }

            current.items.append(Item(index: index, size: size))
            current.width = current.items.count == 1 ? size.width : current.width + horizontalSpacing + size.width
            current.height = max(current.height, size.height)
        }

        if !current.items.isEmpty {
            rows.append(current)
        }
        return rows
    }

    private struct Row {
        var items: [Item] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private struct Item {
        let index: Int
        let size: CGSize
    }
}

private struct ExportFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    static var writableContentTypes: [UTType] { [.data, .zip, .json] }

    private let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        self.data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
