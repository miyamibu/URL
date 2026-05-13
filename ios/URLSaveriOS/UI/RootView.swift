import SwiftUI
import UIKit

func shouldShowSharedTagCloudEntryPoints(
    isConfigured: Bool,
    hasSharedTags: Bool,
    hasPendingInvite: Bool
) -> Bool {
    isConfigured || hasSharedTags || hasPendingInvite
}

struct RootView: View {
    @ObservedObject var model: URLSaverAppModel

    @State private var isShowingManualSheet = false
    @State private var selectedMainService: ServiceType = .all
    @State private var selectedArchiveService: ServiceType = .all
    @State private var selectedMainLocalTagID: Int64?
    @State private var selectedArchiveLocalTagID: Int64?
    @State private var displayMode: EntryListDisplayMode = .compact
    @State private var isShowingUnavailableNotice = false
    @State private var isShowingLocalTagCreateAlert = false
    @State private var isShowingLocalTagManagementSheet = false
    @State private var localTagNameDraft = ""
    @State private var isShowingSharedTagCloudSheet = false
    @State private var isShowingSharedTagCreateSheet = false
    @State private var isShowingExportSheet = false
    @State private var isShowingShareSheet = false
    @State private var shareItems: [Any] = []
    @State private var selectedSharedTagID: String?
    @State private var localTagPendingDeletion: LocalTagSummary?
    @State private var selectedMainEntryIDs: Set<Int64> = []

    var body: some View {
        GeometryReader { proxy in
            let mainVisibleEntries = filteredEntries(
                model.activeEntries,
                selectedService: selectedMainService,
                selectedLocalTagID: selectedMainLocalTagID,
                localTagAssignments: model.localTagAssignments
            )
            let showsSharedTagCloud = shouldShowSharedTagCloudEntryPoints(
                isConfigured: model.sharedTagCloudState.isConfigured,
                hasSharedTags: !model.sharedTags.isEmpty,
                hasPendingInvite: model.pendingInviteRecord != nil
            )
            NavigationStack(path: $model.navigationPath) {
                ScreenContainer {
                    Group {
                        switch model.selectedTab {
                        case .main:
                            MainScreen(
                                entries: mainVisibleEntries,
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
                                onManageLocalTags: { isShowingLocalTagManagementSheet = true },
                                onArchive: { entryID in
                                    Task { await model.archive(entryID: entryID) }
                                },
                                onDelete: { entryID in
                                    Task { await model.markPendingDelete(entryID: entryID) }
                                },
                                selectedEntryIDs: selectedMainEntryIDs,
                                onStartSelection: { entryID in
                                    selectedMainEntryIDs = [entryID]
                                },
                                onToggleSelection: { entryID in
                                    toggleMainSelection(entryID)
                                },
                                onSelectAll: {
                                    selectedMainEntryIDs = Set(mainVisibleEntries.map(\.id))
                                },
                                onCancelSelection: {
                                    selectedMainEntryIDs = []
                                },
                                onArchiveSelection: {
                                    let ids = selectedMainEntryIDs
                                    selectedMainEntryIDs = []
                                    Task { await model.archive(entryIDs: ids) }
                                },
                                onDeleteSelection: {
                                    let ids = selectedMainEntryIDs
                                    selectedMainEntryIDs = []
                                    Task { await model.markPendingDelete(entryIDs: ids) }
                                },
                                onOpenManualInput: { isShowingManualSheet = true },
                                onOpenShare: { isShowingExportSheet = true },
                                onOpenSharedTagCloud: { isShowingSharedTagCloudSheet = true },
                                onCreateSharedTag: { isShowingSharedTagCreateSheet = true },
                                onOpenSharedTag: { selectedSharedTagID = $0 },
                                showsSharedTagCloud: showsSharedTagCloud
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
                                onManageLocalTags: { isShowingLocalTagManagementSheet = true },
                                onOpenDetail: model.openEntry(_:)
                            )
                        }
                    }
                    .safeAreaInset(edge: .bottom) {
                        if model.selectedTab == .main && selectedMainEntryIDs.isEmpty {
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
            .onChange(of: model.selectedTab) { _, tab in
                if tab != .main {
                    selectedMainEntryIDs = []
                }
            }
            .onChange(of: model.activeEntries) { _, entries in
                selectedMainEntryIDs = selectedMainEntryIDs.intersection(Set(entries.map(\.id)))
            }
            .onChange(of: selectedMainService) { _, _ in
                selectedMainEntryIDs = []
            }
            .onChange(of: selectedMainLocalTagID) { _, _ in
                selectedMainEntryIDs = []
            }
            .overlay(alignment: .bottom) {
                if let notification = model.currentNotification {
                    NotificationBanner(
                        notification: notification,
                        onAction: { model.performNotificationAction() },
                    onClose: { model.dismissCurrentNotification() }
                )
                .padding(.horizontal, 16)
                .padding(.bottom, model.selectedTab == .main ? LaunchAdVisibility.mainNotificationBottomPadding : 20)
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
            .sheet(isPresented: $isShowingLocalTagManagementSheet) {
                LocalTagManagementSheet(
                    localTags: model.localTags,
                    onDelete: { tag in
                        isShowingLocalTagManagementSheet = false
                        localTagPendingDeletion = tag
                    },
                    onClose: { isShowingLocalTagManagementSheet = false }
                )
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(32)
            }
            .sheet(isPresented: $isShowingSharedTagCreateSheet) {
                SharedTagCreateSheet(
                    model: model,
                    onOpenProfile: {
                        isShowingSharedTagCreateSheet = false
                        isShowingSharedTagCloudSheet = true
                    }
                )
                .presentationDetents([.height(420), .large])
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
            .fullScreenCover(
                isPresented: Binding(
                    get: { model.inviteConfirmationToken != nil },
                    set: { if !$0 { model.clearInviteConfirmation() } }
                )
            ) {
                if let token = model.inviteConfirmationToken {
                    SharedTagInviteConfirmationView(
                        inviteToken: token,
                        model: model,
                        onClose: { model.clearInviteConfirmation() }
                    )
                }
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
            .alert("タグを削除", isPresented: isShowingLocalTagDeleteAlert) {
                Button("キャンセル", role: .cancel) {
                    localTagPendingDeletion = nil
                }
                Button("削除する", role: .destructive) {
                    guard let tag = localTagPendingDeletion else { return }
                    localTagPendingDeletion = nil
                    Task {
                        if await model.deleteLocalTag(tagID: tag.id) {
                            if selectedMainLocalTagID == tag.id {
                                selectedMainLocalTagID = nil
                            }
                            if selectedArchiveLocalTagID == tag.id {
                                selectedArchiveLocalTagID = nil
                            }
                        }
                    }
                }
            } message: {
                Text(localTagDeleteMessage(localTagPendingDeletion))
            }
        }
        .background(AppPalette.background.ignoresSafeArea())
    }

    private var isShowingLocalTagDeleteAlert: Binding<Bool> {
        Binding(
            get: { localTagPendingDeletion != nil },
            set: { isPresented in
                if !isPresented {
                    localTagPendingDeletion = nil
                }
            }
        )
    }

    private func localTagDeleteMessage(_ tag: LocalTagSummary?) -> String {
        guard let tag else {
            return "このタグを削除します。URL自体は削除されません。"
        }
        return "「\(tag.name)」を削除します。URL自体は削除されません。"
    }

    private func toggleMainSelection(_ entryID: Int64) {
        if selectedMainEntryIDs.contains(entryID) {
            selectedMainEntryIDs.remove(entryID)
        } else {
            selectedMainEntryIDs.insert(entryID)
        }
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
    let onManageLocalTags: () -> Void
    let onArchive: (Int64) -> Void
    let onDelete: (Int64) -> Void
    let selectedEntryIDs: Set<Int64>
    let onStartSelection: (Int64) -> Void
    let onToggleSelection: (Int64) -> Void
    let onSelectAll: () -> Void
    let onCancelSelection: () -> Void
    let onArchiveSelection: () -> Void
    let onDeleteSelection: () -> Void
    let onOpenManualInput: () -> Void
    let onOpenShare: () -> Void
    let onOpenSharedTagCloud: () -> Void
    let onCreateSharedTag: () -> Void
    let onOpenSharedTag: (String) -> Void
    let showsSharedTagCloud: Bool

    var body: some View {
        VStack(spacing: 0) {
            let trailingButtons = mainTrailingButtons
            ScreenHeader(
                title: "保存したURL",
                leadingButton: nil,
                trailingButtons: trailingButtons
            )

            ServiceFilterRow(
                selectedService: $selectedService,
                selectedLocalTagID: $selectedLocalTagID,
                showsCreateChip: true,
                createAction: onCreateLocalTag,
                localTags: localTags
            )

            if showsSharedTagCloud {
                SharedTagSection(
                    tags: sharedTags,
                    onCreateTag: onCreateSharedTag,
                    onOpenTag: onOpenSharedTag
                )
                .padding(.bottom, 6)
            }

            if !selectedEntryIDs.isEmpty {
                EntrySelectionBar(
                    selectedCount: selectedEntryIDs.count,
                    allSelected: !entries.isEmpty && selectedEntryIDs.count == entries.count,
                    onSelectAll: onSelectAll,
                    onArchive: onArchiveSelection,
                    onDelete: onDeleteSelection,
                    onCancel: onCancelSelection
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
                .transition(.move(edge: .top).combined(with: .opacity))
            }

            GeometryReader { proxy in
                let cardWidth = max(proxy.size.width - 32, 0)
                let selectionMode = !selectedEntryIDs.isEmpty

                ScrollView(showsIndicators: false) {
                    LazyVStack(spacing: 14) {
                        if showsSharedTagCloud, let pendingInviteRecord {
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
                                    .font(.system(size: 21, weight: .heavy, design: .rounded))
                                    .foregroundStyle(AppPalette.textSecondary)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.82)
                                    .multilineTextAlignment(.center)
                                    .frame(maxWidth: .infinity)
                            }
                            .frame(width: cardWidth)
                        } else {
                            ForEach(entries) { entry in
                                if selectionMode {
                                    Button {
                                        onToggleSelection(entry.id)
                                    } label: {
                                        EntryCardView(
                                            entry: entry,
                                            timestampLabel: "保存",
                                            displayMode: displayMode,
                                            cardWidth: cardWidth,
                                            selected: selectedEntryIDs.contains(entry.id)
                                        )
                                        .frame(width: cardWidth)
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel("\(preferredDisplayTitle(for: entry))")
                                    .accessibilityValue(selectedEntryIDs.contains(entry.id) ? "選択中" : "未選択")
                                } else {
                                    SwipeableEntryCard(
                                        entry: entry,
                                        displayMode: displayMode,
                                        cardWidth: cardWidth,
                                        onTap: { onOpenDetail(entry.id) },
                                        onArchive: { onArchive(entry.id) },
                                        onDelete: { onDelete(entry.id) },
                                        onLongPress: { onStartSelection(entry.id) }
                                    )
                                    .frame(width: cardWidth)
                                }
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
        .animation(.interactiveSpring(response: 0.28, dampingFraction: 0.9), value: selectedEntryIDs)
    }

    private var mainTrailingButtons: [ScreenHeaderButton] {
        var buttons: [ScreenHeaderButton] = [
            ScreenHeaderButton(
                icon: displayMode == .rich ? "list.bullet.rectangle" : "rectangle.grid.1x2",
                accessibilityLabel: displayMode == .rich ? "画像なし表示へ切り替える" : "画像つき表示へ切り替える",
                action: { displayMode = displayMode == .rich ? .compact : .rich }
            ),
        ]
        buttons.append(
            ScreenHeaderButton(
                icon: "person.crop.circle",
                accessibilityLabel: "プロフィール",
                action: onOpenSharedTagCloud
            )
        )
        buttons.append(contentsOf: [
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
            ScreenHeaderButton(
                icon: "tag",
                accessibilityLabel: "タグ管理",
                action: onManageLocalTags
            ),
        ])
        return buttons
    }
}

private struct EntrySelectionBar: View {
    let selectedCount: Int
    let allSelected: Bool
    let onSelectAll: () -> Void
    let onArchive: () -> Void
    let onDelete: () -> Void
    let onCancel: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text("\(selectedCount)件選択")
                .font(.system(size: 15, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textSecondary)
                .lineLimit(1)
                .layoutPriority(1)

            Spacer(minLength: 0)

            selectAllButton
            selectionIconButton("アーカイブ", systemImage: "archivebox", action: onArchive)
            selectionIconButton("削除", systemImage: "trash", role: .destructive, action: onDelete)
            selectionIconButton("キャンセル", systemImage: "xmark", action: onCancel)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AppPalette.outlineSoft, lineWidth: 1.2)
        )
        .accessibilityElement(children: .contain)
    }

    private var selectAllButton: some View {
        Button(action: onSelectAll) {
            Label("すべて選択", systemImage: "checklist")
                .font(.system(size: 14, weight: .bold))
                .lineLimit(1)
                .padding(.horizontal, 10)
                .frame(height: 36)
                .background(AppPalette.panelStrong, in: Capsule())
                .foregroundStyle(Color.white.opacity(0.95))
        }
        .buttonStyle(.plain)
        .disabled(allSelected)
        .opacity(allSelected ? 0.45 : 1)
    }

    private func selectionIconButton(
        _ title: String,
        systemImage: String,
        role: ButtonRole? = nil,
        action: @escaping () -> Void
    ) -> some View {
        Button(role: role, action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .heavy))
                .frame(width: 38, height: 36)
                .background(AppPalette.panelStrong, in: Capsule())
                .foregroundStyle(role == .destructive ? AppPalette.warning : Color.white.opacity(0.95))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
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

private struct SharedTagInviteConfirmationView: View {
    let inviteToken: String
    @ObservedObject var model: URLSaverAppModel
    let onClose: () -> Void

    @State private var previewState: InvitePreviewState = .loading
    @State private var message: String?
    @State private var isWorking = false
    @State private var didJoin = false

    var body: some View {
        ScreenContainer {
            VStack(spacing: 0) {
                ScreenHeader(
                    title: "",
                    leadingButton: ScreenHeaderButton(
                        icon: "chevron.left",
                        accessibilityLabel: "閉じる",
                        action: onClose
                    ),
                    trailingButtons: []
                )

                VStack(spacing: 20) {
                    Spacer(minLength: 40)

                    switch previewState {
                    case .loading:
                        ProgressView()
                            .tint(AppPalette.textSecondary)
                        Text("招待リンクを確認しています")
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                            .foregroundStyle(AppPalette.textSecondary)
                    case .invalid:
                        inviteTitle("招待リンクを確認できませんでした")
                        Text("招待リンクが無効か期限切れです。")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                            .multilineTextAlignment(.center)
                    case .failure(let text):
                        inviteTitle("招待リンクを確認できませんでした")
                        Text(text)
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                            .multilineTextAlignment(.center)
                    case .ready(let tagName):
                        inviteTitle("共有タグに参加しますか？")
                        Text("「\(tagName)」")
                            .font(.system(size: 30, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textPrimary)
                            .multilineTextAlignment(.center)
                            .lineLimit(3)
                            .minimumScaleFactor(0.75)

                        Text("参加すると、この共有タグのURL一覧が同期されます。")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)

                        VStack(spacing: 12) {
                            AppActionButton(
                                tone: .primary,
                                enabled: !isWorking && !didJoin
                            ) {
                                joinInvite()
                            } label: {
                                if isWorking {
                                    ProgressView().tint(AppPalette.textPrimary)
                                } else {
                                    Text(didJoin ? "参加しました" : "参加する")
                                }
                            }

                            Button("参加しない", action: onClose)
                                .font(.system(size: 17, weight: .heavy))
                                .foregroundStyle(AppPalette.primaryStrong)
                                .disabled(isWorking || didJoin)
                        }
                        .padding(.top, 8)
                    }

                    if let message {
                        Text(message)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Spacer(minLength: 40)
                }
                .frame(maxWidth: 420)
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: inviteToken) {
            await loadPreview()
        }
    }

    private func inviteTitle(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 25, weight: .heavy, design: .rounded))
            .foregroundStyle(AppPalette.textPrimary)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func loadPreview() async {
        previewState = .loading
        message = nil
        switch await model.previewInvite(inviteToken: inviteToken) {
        case .success(let tagName):
            previewState = .ready(tagName: tagName)
        case .invalidInvite:
            previewState = .invalid
        case .failure(let text):
            previewState = .failure(text)
        }
    }

    private func joinInvite() {
        guard !isWorking else { return }
        isWorking = true
        message = nil
        Task {
            switch await model.acceptInvite(inviteToken: inviteToken) {
            case .accepted:
                didJoin = true
                message = "共有タグに参加しました。"
                try? await Task.sleep(nanoseconds: 700_000_000)
                onClose()
            case .authRequired:
                message = "参加するにはプロフィール画面でサインインしてください。"
            case .invalidInvite:
                previewState = .invalid
            case .failure(let text):
                message = text
            }
            isWorking = false
        }
    }

    private enum InvitePreviewState: Equatable {
        case loading
        case ready(tagName: String)
        case invalid
        case failure(String)
    }
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
    let onManageLocalTags: () -> Void
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
                trailingButtons: [
                    ScreenHeaderButton(
                        icon: "tag",
                        accessibilityLabel: "タグ管理",
                        action: onManageLocalTags
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

private struct SharedTagCreateSheet: View {
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var model: URLSaverAppModel
    let onOpenProfile: () -> Void

    @State private var tagName = ""
    @State private var isWorking = false

    var body: some View {
        ScreenContainer {
            VStack(alignment: .leading, spacing: 16) {
                Capsule()
                    .fill(AppPalette.outlineSoft)
                    .frame(width: 72, height: 8)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 10)

                HStack {
                    Text("共有タグを作成")
                        .font(.system(size: 26, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                    Spacer()
                    Button("閉じる") { dismiss() }
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(AppPalette.primaryStrong)
                }

                if model.sharedTagCloudState.isConfigured && model.sharedTagCloudState.isSignedIn {
                    createForm
                } else {
                    authRequiredPanel
                }

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .task {
            await model.refreshSharedTagCloudState()
        }
    }

    private var createForm: some View {
        AppPanel {
            Text("新しい共有タグ")
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            TextField(
                "",
                text: $tagName,
                prompt: Text("共有タグ名").foregroundStyle(AppPalette.textMuted)
            )
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.system(size: 18, weight: .medium))
            .foregroundStyle(AppPalette.textPrimary)
            .tint(AppPalette.primaryStrong)
            .padding(.horizontal, 18)
            .padding(.vertical, 18)
            .background(AppPalette.background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(AppPalette.outlineSoft, lineWidth: 1.5)
            )

            AppActionButton(
                tone: .primary,
                enabled: !tagName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isWorking
            ) {
                guard !isWorking else { return }
                isWorking = true
                Task {
                    if await model.createSharedTag(name: tagName) {
                        dismiss()
                    }
                    isWorking = false
                }
            } label: {
                if isWorking {
                    ProgressView().tint(AppPalette.textPrimary)
                } else {
                    Text("作成")
                }
            }
        }
    }

    private var authRequiredPanel: some View {
        AppPanel {
            Text("共有タグを作るにはサインインが必要です")
                .font(.system(size: 20, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            Text("共有タグはクラウド同期を使うため、先にプロフィール画面でサインインしてください。")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)

            AppActionButton(tone: .primary) {
                onOpenProfile()
            } label: {
                Text("サインインへ")
            }
        }
    }

}

private struct LocalTagManagementSheet: View {
    let localTags: [LocalTagSummary]
    let onDelete: (LocalTagSummary) -> Void
    let onClose: () -> Void

    var body: some View {
        ZStack {
            AppPalette.background.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("タグ管理")
                        .font(.system(size: 26, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                    Spacer()
                    Button("閉じる", action: onClose)
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(AppPalette.primaryStrong)
                }

                if localTags.isEmpty {
                    AppPanel {
                        Text("削除できるタグはまだありません")
                            .font(.system(size: 17, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                } else {
                    ScrollView(showsIndicators: false) {
                        LocalTagManagementFlowLayout(horizontalSpacing: 8, verticalSpacing: 8) {
                            ForEach(localTags) { tag in
                                LocalTagManagementPill(tag: tag, onDelete: onDelete)
                            }
                        }
                        .padding(.bottom, 12)
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.top, 22)
            .padding(.bottom, 24)
        }
    }
}

private struct LocalTagManagementFlowLayout: Layout {
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

private struct LocalTagManagementPill: View {
    let tag: LocalTagSummary
    let onDelete: (LocalTagSummary) -> Void

    var body: some View {
        HStack(spacing: 6) {
            Text(tag.name)
                .font(.system(size: 21, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(1)
                .frame(maxWidth: 104, alignment: .leading)

            Button(role: .destructive) {
                onDelete(tag)
            } label: {
                Label("削除", systemImage: "trash")
                    .font(.system(size: 18, weight: .heavy))
                    .lineLimit(1)
            }
            .buttonStyle(.plain)
            .foregroundStyle(AppPalette.danger)
        }
        .padding(.leading, 16)
        .padding(.trailing, 12)
        .padding(.vertical, 13)
        .background(AppPalette.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(AppPalette.outline, lineWidth: 1.5)
        )
        .fixedSize(horizontal: true, vertical: false)
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
