import SwiftUI
import UIKit

struct RootView: View {
    @ObservedObject var model: URLSaverAppModel

    @State private var isShowingManualSheet = false
    @State private var selectedMainService: ServiceType = .all
    @State private var selectedArchiveService: ServiceType = .all
    @State private var selectedMainLocalTagID: Int64?
    @State private var selectedArchiveLocalTagID: Int64?
    @State private var displayMode: EntryListDisplayMode = .compact
    @State private var isShowingPrivacyInfo = false
    @State private var isShowingUnavailableNotice = false
    @State private var isShowingLocalTagCreateAlert = false
    @State private var localTagNameDraft = ""
    @State private var isShowingSharedTagCloudSheet = false
    @State private var isShowingExportSheet = false
    @State private var isShowingShareSheet = false
    @State private var shareItems: [Any] = []
    @State private var selectedSharedTagID: String?

    var body: some View {
        GeometryReader { proxy in
            NavigationStack(path: $model.navigationPath) {
                ScreenContainer {
                    Group {
                        switch model.selectedTab {
                        case .main:
                            MainScreen(
                                entries: filteredEntries(
                                    model.activeEntries,
                                    selectedService: selectedMainService,
                                    selectedLocalTagID: selectedMainLocalTagID,
                                    localTagAssignments: model.localTagAssignments
                                ),
                                totalEntries: model.activeEntries,
                                pendingInviteRecord: model.pendingInviteRecord,
                                localTags: model.localTags,
                                sharedTags: model.sharedTags,
                                selectedService: $selectedMainService,
                                selectedLocalTagID: $selectedMainLocalTagID,
                                displayMode: $displayMode,
                                onOpenArchive: { model.selectedTab = .archive },
                                onOpenDetail: model.openEntry(_:),
                                onCreateLocalTag: { isShowingLocalTagCreateAlert = true },
                                onArchive: { entryID in
                                    Task { await model.archive(entryID: entryID) }
                                },
                                onDelete: { entryID in
                                    Task { await model.markPendingDelete(entryID: entryID) }
                                },
                                onOpenManualInput: { isShowingManualSheet = true },
                                onShowPrivacyInfo: { isShowingPrivacyInfo = true },
                                onOpenShare: { isShowingExportSheet = true },
                                onOpenSharedTagCloud: { isShowingSharedTagCloudSheet = true },
                                onOpenSharedTag: { selectedSharedTagID = $0 }
                            )
                        case .archive:
                            ArchiveScreen(
                                entries: filteredEntries(
                                    model.archivedEntries,
                                    selectedService: selectedArchiveService,
                                    selectedLocalTagID: selectedArchiveLocalTagID,
                                    localTagAssignments: model.localTagAssignments
                                ),
                                totalEntries: model.archivedEntries,
                                localTags: model.localTags,
                                selectedService: $selectedArchiveService,
                                selectedLocalTagID: $selectedArchiveLocalTagID,
                                displayMode: $displayMode,
                                onBack: { model.selectedTab = .main },
                                onCreateLocalTag: { isShowingLocalTagCreateAlert = true },
                                onOpenDetail: model.openEntry(_:)
                            )
                        }
                    }
                    .safeAreaInset(edge: .bottom) {
                        if model.selectedTab == .main {
                            BottomPrimaryBar(label: "URLを追加", systemImage: "plus") {
                                isShowingManualSheet = true
                            }
                        }
                    }
                    .navigationDestination(for: Int64.self) { entryID in
                        DetailView(entryID: entryID, model: model)
                    }
                }
            }
            .frame(width: proxy.size.width, height: proxy.size.height, alignment: .top)
            .toolbar(.hidden, for: .navigationBar)
            .overlay(alignment: .bottom) {
                if let notification = model.currentNotification {
                    NotificationBanner(
                        notification: notification,
                        onAction: { model.performNotificationAction() },
                    onClose: { model.dismissCurrentNotification() }
                )
                .padding(.horizontal, 16)
                .padding(.bottom, model.selectedTab == .main ? 172 : 20)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            }
            .sheet(isPresented: $isShowingManualSheet) {
                ManualInputSheet(model: model)
                    .presentationDetents([.height(640)])
                    .presentationDragIndicator(.hidden)
                    .presentationCornerRadius(32)
            }
            .sheet(isPresented: $isShowingSharedTagCloudSheet) {
                SharedTagCloudSheet(model: model)
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
                    .presentationCornerRadius(32)
            }
            .sheet(isPresented: $isShowingExportSheet) {
                ExportSheet(model: model)
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
                    .presentationCornerRadius(32)
            }
            .sheet(isPresented: $isShowingShareSheet) {
                ActivityShareSheet(items: shareItems)
            }
            .fullScreenCover(
                isPresented: Binding(
                    get: { selectedSharedTagID != nil },
                    set: { if !$0 { selectedSharedTagID = nil } }
                )
            ) {
                if let selectedSharedTagID {
                    SharedTagDetailSheet(
                        model: model,
                        remoteTagID: selectedSharedTagID,
                        displayMode: $displayMode
                    )
                }
            }
            .alert("プライバシー情報", isPresented: $isShowingPrivacyInfo) {
                Button("閉じる", role: .cancel) {}
            } message: {
                Text("保存データはこの端末内に保持され、共有 URL の重複判定は normalizedUrl を基準に行います。")
            }
            .alert("未対応", isPresented: $isShowingUnavailableNotice) {
                Button("閉じる", role: .cancel) {}
            } message: {
                Text("このフィルター操作は iOS 版ではまだ未対応です。")
            }
            .alert("タグを作成", isPresented: $isShowingLocalTagCreateAlert) {
                TextField("タグ名", text: $localTagNameDraft)
                Button("作成") {
                    let name = localTagNameDraft
                    localTagNameDraft = ""
                    Task { _ = await model.createLocalTag(name: name) }
                }
                Button("キャンセル", role: .cancel) {
                    localTagNameDraft = ""
                }
            } message: {
                Text("このiPhone内でURLを整理するタグを作成します。")
            }
        }
        .background(AppPalette.background.ignoresSafeArea())
    }
}

private struct MainScreen: View {
    let entries: [URLRecord]
    let totalEntries: [URLRecord]
    let pendingInviteRecord: PendingInviteRecord?
    let localTags: [LocalTagSummary]
    let sharedTags: [SharedTagSummary]
    @Binding var selectedService: ServiceType
    @Binding var selectedLocalTagID: Int64?
    @Binding var displayMode: EntryListDisplayMode
    let onOpenArchive: () -> Void
    let onOpenDetail: (Int64) -> Void
    let onCreateLocalTag: () -> Void
    let onArchive: (Int64) -> Void
    let onDelete: (Int64) -> Void
    let onOpenManualInput: () -> Void
    let onShowPrivacyInfo: () -> Void
    let onOpenShare: () -> Void
    let onOpenSharedTagCloud: () -> Void
    let onOpenSharedTag: (String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(
                title: "保存したURL",
                leadingButton: nil,
                trailingButtons: [
                    ScreenHeaderButton(
                        icon: displayMode == .rich ? "list.bullet.rectangle" : "rectangle.grid.1x2",
                        accessibilityLabel: displayMode == .rich ? "画像なし表示へ切り替える" : "画像つき表示へ切り替える",
                        action: { displayMode = displayMode == .rich ? .compact : .rich }
                    ),
                    ScreenHeaderButton(
                        icon: "person.crop.circle",
                        accessibilityLabel: "プロフィール",
                        action: onOpenSharedTagCloud
                    ),
                    ScreenHeaderButton(
                        icon: "info.circle",
                        accessibilityLabel: "プライバシー情報",
                        action: onShowPrivacyInfo
                    ),
                    ScreenHeaderButton(
                        icon: "tray.and.arrow.up",
                        accessibilityLabel: "エクスポート",
                        action: onOpenShare
                    ),
                    ScreenHeaderButton(
                        icon: "archivebox",
                        accessibilityLabel: "アーカイブ",
                        action: onOpenArchive
                    ),
                ]
            )

            ServiceFilterRow(
                selectedService: $selectedService,
                selectedLocalTagID: $selectedLocalTagID,
                showsCreateChip: true,
                createAction: onCreateLocalTag,
                localTags: localTags
            )

            SharedTagSection(
                tags: sharedTags,
                onCreateTag: onOpenSharedTagCloud,
                onOpenTag: onOpenSharedTag
            )
            .padding(.bottom, 6)

            GeometryReader { proxy in
                let cardWidth = max(proxy.size.width - 32, 0)

                ScrollView(showsIndicators: false) {
                    LazyVStack(spacing: 14) {
                        if let pendingInviteRecord {
                            PendingInviteBanner(
                                pendingInviteRecord: pendingInviteRecord,
                                onOpenCloud: onOpenSharedTagCloud
                            )
                            .frame(width: cardWidth)
                        }
                        if totalEntries.isEmpty {
                            Color.clear.frame(height: 560)
                        } else if entries.isEmpty {
                            AppPanel {
                                Text("この条件に一致するURLはありません")
                                    .font(.system(size: 24, weight: .heavy, design: .rounded))
                                    .foregroundStyle(AppPalette.textSecondary)
                                    .multilineTextAlignment(.center)
                                    .frame(maxWidth: .infinity)
                                Text("フィルターを変更してください")
                                    .font(.system(size: 17, weight: .medium))
                                    .foregroundStyle(AppPalette.textSecondary)
                                    .frame(maxWidth: .infinity)
                            }
                            .frame(width: cardWidth)
                        } else {
                            ForEach(entries) { entry in
                                SwipeableEntryCard(
                                    entry: entry,
                                    displayMode: displayMode,
                                    cardWidth: cardWidth,
                                    onTap: { onOpenDetail(entry.id) },
                                    onArchive: { onArchive(entry.id) },
                                    onDelete: { onDelete(entry.id) }
                                )
                                .frame(width: cardWidth)
                            }
                        }
                    }
                    .padding(.top, 10)
                    .padding(.bottom, 24)
                    .frame(width: proxy.size.width)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

private func buildMainShareSummary(
    activeCount: Int,
    archivedCount: Int,
    sharedTagNames: [String],
    visibleEntries: [URLRecord]
) -> String {
    var lines: [String] = [
        "URL Saver",
        "",
        "保存したURL: \(activeCount)件",
        "アーカイブ: \(archivedCount)件",
        "共有タグ: \(sharedTagNames.isEmpty ? "なし" : sharedTagNames.joined(separator: ", "))",
    ]

    if !visibleEntries.isEmpty {
        lines.append("")
        lines.append("現在表示中のURL")
        for entry in visibleEntries.prefix(20) {
            lines.append("- \(entry.effectiveTitle)")
            lines.append("  \(entry.openURL)")
        }
    }

    return lines.joined(separator: "\n")
}

private struct ArchiveScreen: View {
    let entries: [URLRecord]
    let totalEntries: [URLRecord]
    let localTags: [LocalTagSummary]
    @Binding var selectedService: ServiceType
    @Binding var selectedLocalTagID: Int64?
    @Binding var displayMode: EntryListDisplayMode
    let onBack: () -> Void
    let onCreateLocalTag: () -> Void
    let onOpenDetail: (Int64) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(
                title: "アーカイブ",
                leadingButton: ScreenHeaderButton(
                    icon: "arrow.left",
                    accessibilityLabel: "戻る",
                    action: onBack
                ),
                trailingButtons: []
            )

            ServiceFilterRow(
                selectedService: $selectedService,
                selectedLocalTagID: $selectedLocalTagID,
                showsCreateChip: true,
                createAction: onCreateLocalTag,
                localTags: localTags
            )
                .padding(.bottom, 10)

            GeometryReader { proxy in
                let cardWidth = max(proxy.size.width - 32, 0)

                ScrollView(showsIndicators: false) {
                    LazyVStack(spacing: 14) {
                        if totalEntries.isEmpty {
                            ArchiveEmptyStateCard()
                                .frame(width: cardWidth)
                                .padding(.top, 220)
                        } else if entries.isEmpty {
                            AppPanel {
                                Text("この保存先のアーカイブはありません")
                                    .font(.system(size: 26, weight: .heavy, design: .rounded))
                                    .foregroundStyle(AppPalette.textSecondary)
                                    .multilineTextAlignment(.center)
                                    .frame(maxWidth: .infinity)
                                Text("フィルターを変更してください")
                                    .font(.system(size: 17, weight: .medium))
                                    .foregroundStyle(AppPalette.textSecondary)
                                    .frame(maxWidth: .infinity)
                            }
                            .frame(width: cardWidth)
                            .padding(.top, 220)
                        } else {
                            ForEach(entries) { entry in
                                Button {
                                    onOpenDetail(entry.id)
                                } label: {
                                    EntryCardView(
                                        entry: entry,
                                        timestampLabel: "アーカイブ",
                                        displayMode: displayMode,
                                        cardWidth: cardWidth
                                    )
                                    .frame(width: cardWidth)
                                }
                                .buttonStyle(.plain)
                                .frame(width: cardWidth)
                            }
                        }
                    }
                    .padding(.top, 10)
                    .padding(.bottom, 24)
                    .frame(width: proxy.size.width)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

private struct ManualInputSheet: View {
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var model: URLSaverAppModel
    @State private var input = ""
    @State private var inputError: ShareSaveResult?
    @State private var isSaving = false
    @State private var selectedLocalTagID: Int64?
    @State private var isShowingCreateTagAlert = false
    @State private var newTagName = ""

    var body: some View {
        ScreenContainer {
            VStack(alignment: .leading, spacing: 16) {
                Capsule()
                    .fill(AppPalette.outlineSoft)
                    .frame(width: 72, height: 8)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 10)

                Text("URL")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(inputError == nil ? AppPalette.textSecondary : AppPalette.danger)
                    .padding(.top, 8)

                VStack(alignment: .leading, spacing: 10) {
                    TextField("", text: $input, prompt: Text("https://example.com").foregroundStyle(AppPalette.textMuted))
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(AppPalette.textPrimary)
                        .tint(AppPalette.primaryStrong)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 20)
                        .background(AppPalette.background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .stroke(inputError == nil ? AppPalette.outlineSoft : AppPalette.danger, lineWidth: 2)
                        )

                    if let inputError {
                        Text(message(for: inputError))
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(AppPalette.danger)
                    }
                }

                Button("クリップボードを貼り付け") {
                    input = UIPasteboard.general.string ?? input
                    inputError = nil
                }
                .font(.system(size: 19, weight: .heavy))
                .foregroundStyle(AppPalette.primaryStrong)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)

                Text("タグ")
                    .font(.system(size: 18, weight: .heavy))
                    .foregroundStyle(AppPalette.textPrimary)
                    .padding(.top, 8)

                if model.localTags.isEmpty {
                    Text("タグがまだありません。必要なら作成してください")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(model.localTags) { tag in
                                FilterChipButton(
                                    label: tag.name,
                                    selected: selectedLocalTagID == tag.id
                                ) {
                                    selectedLocalTagID = selectedLocalTagID == tag.id ? nil : tag.id
                                }
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }

                Button("＋ タグを作成") {
                    isShowingCreateTagAlert = true
                }
                    .font(.system(size: 21, weight: .heavy))
                    .foregroundStyle(AppPalette.primaryStrong)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)

                Spacer(minLength: 8)

                AppActionButton(
                    tone: .primary,
                    enabled: !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isSaving
                ) {
                    Task {
                        isSaving = true
                        let localTagIDs: Set<Int64> = selectedLocalTagID.map { Set([$0]) } ?? []
                        let error = await model.manualSave(input: input, localTagIDs: localTagIDs)
                        isSaving = false
                        if let error {
                            inputError = error
                        } else {
                            dismiss()
                        }
                    }
                } label: {
                    if isSaving {
                        ProgressView().tint(AppPalette.textPrimary)
                    } else {
                        Text("保存")
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 22)
            .onChange(of: input) { _, _ in
                inputError = nil
            }
        }
        .alert("タグを作成", isPresented: $isShowingCreateTagAlert) {
            TextField("タグ名", text: $newTagName)
            Button("作成") {
                let name = newTagName
                newTagName = ""
                Task {
                    if let tag = await model.createLocalTag(name: name) {
                        selectedLocalTagID = tag.id
                    }
                }
            }
            Button("キャンセル", role: .cancel) {
                newTagName = ""
            }
        } message: {
            Text("保存時に選べる通常タグを作成します。")
        }
    }

    private func message(for result: ShareSaveResult) -> String {
        switch result {
        case .inputTooLarge:
            return "入力が長すぎます。256KB以内のテキストでURLを貼り付けてください"
        case .invalidURL:
            return "URL形式が正しくありません。https:// から始まるURLを入力してください"
        case .noURLFound:
            return "入力内にURLが見つかりませんでした。URLをそのまま貼り付けてください"
        default:
            return "保存できませんでした"
        }
    }
}

private struct NotificationBanner: View {
    let notification: AppNotification
    let onAction: () -> Void
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            Text(notification.message)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.white.opacity(0.92))
                .multilineTextAlignment(.leading)

            Spacer(minLength: 8)

            if let actionLabel = notification.actionLabel {
                Button(actionLabel, action: onAction)
                    .font(.system(size: 16, weight: .heavy))
                    .foregroundStyle(AppPalette.primary)
            }

            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.75))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(AppPalette.panelStrong, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(AppPalette.outline, lineWidth: 1.5)
        )
        .shadow(color: Color.black.opacity(0.08), radius: 14, y: 10)
    }
}
