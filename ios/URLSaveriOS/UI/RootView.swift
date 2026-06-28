import SwiftUI
import UIKit

private extension Color {
    init(hex: Int) {
        self.init(UIColor(hex: hex))
    }
}

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
    @AppStorage("entryListDisplayMode") private var displayModeRaw = EntryListDisplayMode.compact.rawValue
    @State private var isShowingLocalTagCreateAlert = false
    @State private var isShowingLocalTagManagementSheet = false
    @State private var localTagNameDraft = ""
    @State private var isShowingUsageGuide = false
    @State private var isShowingSearchBar = false
    @State private var searchQuery = ""
    @State private var isShowingSharedTagCloudSheet = false
    @State private var isShowingSharedTagCreateSheet = false
    @State private var isShowingSharedTagGroupCreateSheet = false
    @State private var isShowingExportSheet = false
    @State private var isShowingShareSheet = false
    @State private var shareItems: [Any] = []
    @State private var selectedSharedTagID: String?
    @State private var localTagPendingDeletion: LocalTagSummary?
    @State private var selectedMainEntryIDs: Set<Int64> = []

    private var displayMode: EntryListDisplayMode {
        EntryListDisplayMode(rawValue: displayModeRaw) ?? .compact
    }

    private var displayModeBinding: Binding<EntryListDisplayMode> {
        Binding(
            get: { EntryListDisplayMode(rawValue: displayModeRaw) ?? .compact },
            set: { displayModeRaw = $0.rawValue }
        )
    }

    var body: some View {
        GeometryReader { proxy in
            let mainVisibleEntries = filteredEntries(
                model.activeEntries,
                selectedService: selectedMainService,
                selectedLocalTagID: selectedMainLocalTagID,
                localTagAssignments: model.localTagAssignments
            )
            let mainDisplayedEntries = searchFilteredEntries(
                mainVisibleEntries,
                query: searchQuery,
                localTags: model.localTags,
                localTagAssignments: model.localTagAssignments
            )
            let showsSharedTagCloud = shouldShowSharedTagCloudEntryPoints(
                isConfigured: model.sharedTagCloudState.isConfigured,
                hasSharedTags: !model.sharedTags.isEmpty || !model.sharedTagGroups.isEmpty,
                hasPendingInvite: model.pendingInviteRecord != nil
            )
            NavigationStack(path: $model.navigationPath) {
                ScreenContainer {
                    Group {
                        switch model.selectedTab {
                        case .main:
                            MainScreen(
                                entries: mainDisplayedEntries,
                                totalEntries: model.activeEntries,
                                pendingInviteRecord: model.pendingInviteRecord,
                                localTags: model.localTags,
                                sharedTags: model.sharedTags,
                                selectedService: $selectedMainService,
                                selectedLocalTagID: $selectedMainLocalTagID,
                                displayMode: displayModeBinding,
                                isShowingUsageGuide: $isShowingUsageGuide,
                                isShowingSearchBar: $isShowingSearchBar,
                                searchQuery: $searchQuery,
                                onOpenArchive: { model.selectedTab = .archive },
                                onOpenGroups: { model.selectedTab = .groups },
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
                                    selectedMainEntryIDs = Set(mainDisplayedEntries.map(\.id))
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
                                onOpenUsageGuide: {
                                    selectedMainEntryIDs = []
                                    searchQuery = ""
                                    isShowingSearchBar = false
                                    isShowingUsageGuide = true
                                },
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
                                displayMode: displayModeBinding,
                                onBack: { model.selectedTab = .main },
                                onCreateLocalTag: { isShowingLocalTagCreateAlert = true },
                                onManageLocalTags: { isShowingLocalTagManagementSheet = true },
                                onOpenDetail: model.openEntry(_:)
                            )
                        case .groups:
                            SharedTagGroupScreen(
                                model: model,
                                groups: model.sharedTagGroups,
                                sharedTags: model.sharedTags,
                                onBack: { model.selectedTab = .main },
                                onCreateGroup: { isShowingSharedTagGroupCreateSheet = true },
                                onOpenSharedTag: { selectedSharedTagID = $0 },
                                onShareInvite: { inviteURL in
                                    shareItems = [inviteURL]
                                    isShowingShareSheet = true
                                }
                            )
                        }
                    }
                    .overlay(alignment: .bottom) {
                        if model.selectedTab == .main && selectedMainEntryIDs.isEmpty && !isShowingUsageGuide {
                            BottomHomeActionBar(
                                onOpenGroups: { model.selectedTab = .groups },
                                onOpenExport: { isShowingExportSheet = true },
                                onAddURL: { isShowingManualSheet = true },
                                onOpenTags: { isShowingLocalTagManagementSheet = true },
                                onOpenArchive: { model.selectedTab = .archive },
                                bottomSafeAreaInset: proxy.safeAreaInsets.bottom
                            )
                            .offset(y: proxy.safeAreaInsets.bottom)
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
                    searchQuery = ""
                    isShowingSearchBar = false
                    isShowingUsageGuide = false
                }
            }
            .onChange(of: model.activeEntries) { _, entries in
                selectedMainEntryIDs = selectedMainEntryIDs.intersection(Set(entries.map(\.id)))
            }
            .onChange(of: model.pendingPromoCode) { _, code in
                if code?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false {
                    isShowingSharedTagCloudSheet = true
                }
            }
            .onChange(of: model.incomingLocalTagID) { _, tagID in
                guard let tagID else { return }
                model.selectedTab = .main
                selectedMainService = .all
                selectedMainLocalTagID = tagID
                model.consumeIncomingLocalTagID()
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
                    localLinkText: { model.localTagShareText(for: $0) },
                    payloadText: { model.localTagPayloadText(tagID: $0.id) },
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
            .sheet(isPresented: $isShowingSharedTagGroupCreateSheet) {
                SharedTagGroupCreateSheet(
                    model: model,
                    onOpenProfile: {
                        isShowingSharedTagGroupCreateSheet = false
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
                        displayMode: displayModeBinding
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
    @Binding var isShowingUsageGuide: Bool
    @Binding var isShowingSearchBar: Bool
    @Binding var searchQuery: String
    let onOpenArchive: () -> Void
    let onOpenGroups: () -> Void
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
    let onOpenUsageGuide: () -> Void
    let onOpenSharedTagCloud: () -> Void
    let onCreateSharedTag: () -> Void
    let onOpenSharedTag: (String) -> Void
    let showsSharedTagCloud: Bool

    var body: some View {
        VStack(spacing: 0) {
            let trailingButtons = mainTrailingButtons
            ScreenHeader(
                title: "りんばむ",
                leadingButton: nil,
                trailingButtons: trailingButtons
            )

            if isShowingUsageGuide {
                UsageGuideView(onBack: {
                    isShowingUsageGuide = false
                })
            } else {
            if isShowingSearchBar {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(AppPalette.textMuted)
                    TextField("検索", text: $searchQuery)
                        .textInputAutocapitalization(.never)
                        .disableAutocorrection(true)
                    if !searchQuery.isEmpty {
                        Button {
                            searchQuery = ""
                            isShowingSearchBar = false
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(AppPalette.textMuted)
                        }
                        .accessibilityLabel("クリア")
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .padding(.horizontal, 14)
                .padding(.bottom, 8)
            }

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
        buttons.append(
            ScreenHeaderButton(
                icon: "checkmark.square",
                accessibilityLabel: "選択",
                action: onSelectAll
            )
        )
        buttons.append(
            ScreenHeaderButton(
                icon: "book.fill",
                accessibilityLabel: "使い方",
                action: onOpenUsageGuide
            )
        )
        buttons.append(
            ScreenHeaderButton(
                icon: "magnifyingglass",
                accessibilityLabel: "検索",
                action: {
                    if isShowingUsageGuide {
                        isShowingUsageGuide = false
                        isShowingSearchBar = true
                    } else if isShowingSearchBar {
                        searchQuery = ""
                        isShowingSearchBar = false
                    } else {
                        isShowingSearchBar = true
                    }
                }
            )
        )
        return buttons
    }
}

private struct BottomHomeActionBar: View {
    let onOpenGroups: () -> Void
    let onOpenExport: () -> Void
    let onAddURL: () -> Void
    let onOpenTags: () -> Void
    let onOpenArchive: () -> Void
    let bottomSafeAreaInset: CGFloat

    var body: some View {
        ZStack(alignment: .top) {
            AppPalette.surface
                .frame(height: 76 + bottomSafeAreaInset)
                .frame(maxHeight: .infinity, alignment: .bottom)

            HStack(alignment: .bottom, spacing: 8) {
                bottomItem("グループ", systemImage: "person.3", action: onOpenGroups)
                bottomItem("エクスポート", systemImage: "tray.and.arrow.up", action: onOpenExport)
                Color.clear
                    .frame(width: 76, height: 64)
                bottomItem("タグ", systemImage: "tag", action: onOpenTags)
                bottomItem("アーカイブ", systemImage: "archivebox", action: onOpenArchive)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8 + bottomSafeAreaInset)
            .frame(maxHeight: .infinity, alignment: .bottom)

            Button(action: onAddURL) {
                Image(systemName: "plus")
                    .font(.system(size: 34, weight: .heavy, design: .rounded))
                    .foregroundStyle(Color.black)
                    .frame(width: 76, height: 76)
                    .background(AppPalette.primary, in: Circle())
            }
            .padding(.top, 2)
            .accessibilityLabel("URLを追加")
        }
        .frame(height: 104 + bottomSafeAreaInset)
        .frame(maxWidth: .infinity)
        .ignoresSafeArea(.container, edges: .bottom)
    }

    private func bottomItem(_ label: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: systemImage)
                    .font(.system(size: 25, weight: .semibold, design: .rounded))
                Text(label)
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            }
            .foregroundStyle(AppPalette.textSecondary)
            .frame(maxWidth: .infinity, minHeight: 64)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label == "タグ" ? "タグ管理" : label)
    }
}

private struct UsageGuideView: View {
    let onBack: () -> Void

    var body: some View {
        ScrollView(showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: 0) {
                Button(action: onBack) {
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.left")
                            .font(.system(size: 17, weight: .bold))
                        Text("戻る")
                            .font(.system(size: 17, weight: .bold))
                    }
                    .foregroundStyle(AppPalette.primaryStrong)
                }
                .buttonStyle(.plain)
                .padding(.top, 12)
                .padding(.bottom, 20)

                Text("使い方")
                    .font(.system(size: 34, weight: .heavy, design: .rounded))
                    .foregroundStyle(AppPalette.textPrimary)
                    .padding(.bottom, 8)

                Text("りんばむの基本から便利な使い方、AIとの連携までまとめました。\n最初だけ読んでも、あとで見返しても大丈夫です。")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 22)

                UsageGuideSectionHeader("まず覚える")
                UsageGuideRow(marker: "1", markerColor: Color(hex: 0x16A34A), icon: "square.and.arrow.up", iconColor: Color(hex: 0x128A2E), iconBackground: Color(hex: 0xEAF7ED), title: "Safariや他アプリから保存", body: "Safariや他のアプリの共有から、りんばむを選ぶだけで保存できます。", layout: .stacked) {
                    ShareToRinbamPreview()
                }
                UsageGuideRow(marker: "2", markerColor: Color(hex: 0x16A34A), icon: "tag.fill", iconColor: Color(hex: 0x128A2E), iconBackground: Color(hex: 0xEAF7ED), title: "タグで整理", body: "自作タグを付けて、テーマごとに見つけやすく整理できます。") {
                    GuideTagChipsPreview()
                }
                UsageGuideRow(marker: "3", markerColor: AppPalette.primaryStrong, icon: "magnifyingglass", iconColor: AppPalette.primaryStrong, iconBackground: AppPalette.primaryStrong.opacity(0.12), title: "検索で見つける", body: "キーワードやタグで検索して、見たいURLをすぐに見つけられます。") {
                    GuideSearchPreview()
                }

                UsageGuideSectionHeader("便利な操作")
                UsageGuideRow(marker: "4", markerColor: Color(hex: 0xF97316), icon: "pencil", iconColor: Color(hex: 0xF97316), iconBackground: Color(hex: 0xFFF2DF), title: "自作タグ名を変更", body: "自作タグをダブルタップすると、名前を変更できます。", layout: .stacked) {
                    GuideRenameTagPreview()
                }
                UsageGuideRow(marker: "5", markerColor: Color(hex: 0xF97316), icon: "rectangle.and.hand.point.up.left", iconColor: Color(hex: 0xF97316), iconBackground: Color(hex: 0xFFF2DF), title: "カードをスライド", body: "カードを横にスライドすると、アーカイブや削除ができます。", layout: .stacked) {
                    GuideSwipePreview()
                }

                UsageGuideSectionHeader("共有とAI")
                UsageGuideRow(marker: "6", markerColor: Color(hex: 0x7C3AED), icon: "person.2.fill", iconColor: Color(hex: 0x7C3AED), iconBackground: Color(hex: 0xF3E8FF), title: "共有タグを使う", body: "家族やチームとタグを共有して、いっしょに整理できます。", layout: .stacked) {
                    GuideSharedTagsPreview()
                }
                UsageGuideRow(marker: "7", markerColor: AppPalette.primaryStrong, icon: "tray.and.arrow.up", iconColor: AppPalette.primaryStrong, iconBackground: AppPalette.primaryStrong.opacity(0.12), title: "エクスポートでAIに渡す", body: "エクスポートしたデータをClaudeやChatGPTに渡して活用できます。", layout: .stacked) {
                    GuideAIExportPreview()
                }

                UsageGuideNote()
                    .padding(.top, 12)
                    .padding(.bottom, 24)
            }
            .padding(.horizontal, 16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

private struct UsageGuideSectionHeader: View {
    let title: String

    init(_ title: String) {
        self.title = title
    }

    var body: some View {
        HStack(spacing: 14) {
            Text(title)
                .font(.system(size: 22, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
            Rectangle()
                .fill(AppPalette.outlineSoft.opacity(0.7))
                .frame(height: 1)
        }
        .padding(.top, 2)
        .padding(.bottom, 10)
    }
}

private enum UsageGuideRowLayout {
    case inline
    case stacked
}

private struct UsageGuideRow<Preview: View>: View {
    let marker: String
    let markerColor: Color
    let icon: String
    let iconColor: Color
    let iconBackground: Color
    let title: String
    let bodyText: String
    let layout: UsageGuideRowLayout
    let preview: Preview

    init(marker: String, markerColor: Color, icon: String, iconColor: Color, iconBackground: Color, title: String, body: String, layout: UsageGuideRowLayout = .inline, @ViewBuilder preview: () -> Preview) {
        self.marker = marker
        self.markerColor = markerColor
        self.icon = icon
        self.iconColor = iconColor
        self.iconBackground = iconBackground
        self.title = title
        self.bodyText = body
        self.layout = layout
        self.preview = preview()
    }

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 12) {
                if layout == .stacked {
                    HStack(alignment: .center, spacing: 10) {
                        rowLabel
                        rowIcon
                        rowText
                    }
                    preview
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .padding(.leading, 40)
                } else {
                    HStack(alignment: .center, spacing: 10) {
                        rowLabel
                        rowIcon
                        rowText
                        preview
                            .frame(width: 166, alignment: .trailing)
                    }
                }
            }
            .padding(.vertical, 12)
            Rectangle()
                .fill(AppPalette.outlineSoft.opacity(0.45))
                .frame(height: 1)
                .padding(.leading, 40)
        }
    }

    private var rowLabel: some View {
        Text(marker)
            .font(.system(size: 17, weight: .heavy, design: .rounded))
            .foregroundStyle(.white)
            .frame(width: 28, height: 28)
            .background(markerColor, in: Circle())
    }

    private var rowIcon: some View {
        Image(systemName: icon)
            .font(.system(size: 28, weight: .bold))
            .foregroundStyle(iconColor)
            .frame(width: 56, height: 56)
            .background(iconBackground, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var rowText: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 16, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            Text(bodyText)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct GuidePreviewSurface<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            content
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppPalette.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(AppPalette.outlineSoft.opacity(0.7), lineWidth: 1)
        }
    }
}

private struct ShareToRinbamPreview: View {
    var body: some View {
        GuidePreviewSurface {
            HStack(spacing: 7) {
                MiniAppIcon(label: "Safari", text: "S")
                MiniAppIcon(label: "他アプリ", text: "…")
                Text("→").foregroundStyle(AppPalette.textSecondary)
                MiniIconBox(icon: "square.and.arrow.up", label: "共有")
                Text("→").foregroundStyle(AppPalette.textSecondary)
                RinbamAppIcon()
            }
        }
    }
}

private struct GuideTagChipsPreview: View {
    var body: some View {
        GuidePreviewSurface {
            Text("タグ").font(.system(size: 12, weight: .bold)).foregroundStyle(AppPalette.textPrimary)
            HStack(spacing: 6) {
                MiniChip("旅行", background: Color(hex: 0xE5F6E7), foreground: Color(hex: 0x128A2E))
                MiniChip("レシピ", background: Color(hex: 0xEAF2FF), foreground: AppPalette.primaryStrong)
                MiniChip("仕事", background: AppPalette.surfaceSoft, foreground: AppPalette.textSecondary)
                MiniChip("+", background: AppPalette.surface, foreground: AppPalette.textPrimary)
            }
        }
    }
}

private struct GuideSearchPreview: View {
    var body: some View {
        GuidePreviewSurface {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass")
                Text("温泉")
                Spacer()
                Text("×")
            }
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(AppPalette.textPrimary)
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(AppPalette.surfaceSoft.opacity(0.65), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            HStack(spacing: 6) {
                MiniChip("旅行", background: Color(hex: 0xE5F6E7), foreground: Color(hex: 0x128A2E))
                MiniChip("温泉", background: Color(hex: 0xEAF2FF), foreground: AppPalette.primaryStrong)
            }
        }
    }
}

private struct GuideRenameTagPreview: View {
    var body: some View {
        GuidePreviewSurface {
            Text("ダブルタップ").font(.system(size: 12, weight: .bold)).foregroundStyle(AppPalette.textPrimary)
            HStack(spacing: 5) {
                MiniChip("旅行", background: Color(hex: 0xE5F6E7), foreground: Color(hex: 0x128A2E))
                MiniChip("レシピ", background: Color(hex: 0xEAF2FF), foreground: AppPalette.primaryStrong)
                Text("→").foregroundStyle(AppPalette.textSecondary)
                Text("旅行")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(AppPalette.textPrimary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .stroke(AppPalette.primaryStrong, lineWidth: 1.5)
                    )
            }
        }
    }
}

private struct GuideSwipePreview: View {
    var body: some View {
        GuidePreviewSurface {
            HStack {
                Text("右へスワイプでアーカイブ")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(AppPalette.textPrimary)
                Spacer()
                GuideSwipeArrow(direction: "→", color: AppPalette.primaryStrong)
            }
            ZStack(alignment: .leading) {
                ArchiveActionBlock()
                DetailedMiniURLCard()
                    .padding(.leading, 42)
            }
            .frame(height: 54)
            HStack {
                Text("左へスワイプで削除")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(AppPalette.textPrimary)
                Spacer()
                GuideSwipeArrow(direction: "←", color: AppPalette.danger)
            }
            ZStack(alignment: .trailing) {
                DetailedMiniURLCard()
                    .padding(.trailing, 44)
                DeleteActionBlock()
            }
            .frame(height: 54)
        }
    }
}

private struct GuideSwipeArrow: View {
    let direction: String
    let color: Color

    var body: some View {
        HStack(spacing: 4) {
            Rectangle()
                .fill(color.opacity(0.55))
                .frame(width: 44, height: 2)
            Text(direction)
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(color)
        }
    }
}

private struct GuideSharedTagsPreview: View {
    var body: some View {
        GuidePreviewSurface {
            Text("共有タグ").font(.system(size: 12, weight: .bold)).foregroundStyle(AppPalette.textPrimary)
            HStack(spacing: 6) {
                MiniChip("家族旅行", background: Color(hex: 0xE5F6E7), foreground: Color(hex: 0x128A2E))
                MiniChip("読みたい本", background: Color(hex: 0xF3E8FF), foreground: Color(hex: 0x7C3AED))
                MiniChip("勉強会", background: Color(hex: 0xEAF2FF), foreground: AppPalette.primaryStrong)
            }
        }
    }
}

private struct GuideAIExportPreview: View {
    var body: some View {
        GuidePreviewSurface {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("エクスポート形式")
                        .font(.system(size: 10, weight: .bold))
                    HStack(spacing: 8) {
                        ExportFileChip(label: "ZIP", color: Color(hex: 0x16A34A))
                        ExportFileChip(label: "JSON", color: AppPalette.primaryStrong)
                    }
                }
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(AppPalette.outlineSoft.opacity(0.65), lineWidth: 1)
                )
                Text("→").foregroundStyle(AppPalette.textSecondary)
                VStack(alignment: .leading, spacing: 6) {
                    Text("AIに渡す")
                        .font(.system(size: 10, weight: .bold))
                    Text("Claude")
                    Text("ChatGPT など")
                }
                .font(.system(size: 12, weight: .heavy))
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(AppPalette.outlineSoft.opacity(0.65), lineWidth: 1)
                )
            }
        }
    }
}

private struct ExportFileChip: View {
    let label: String
    let color: Color

    var body: some View {
        VStack(spacing: 3) {
            RoundedRectangle(cornerRadius: 5, style: .continuous)
                .stroke(color, lineWidth: 2)
                .frame(width: 24, height: 24)
            Text(label)
                .font(.system(size: 7.5, weight: .bold))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.65)
                .frame(width: 28)
        }
    }
}

private struct MiniAppIcon: View {
    let label: String
    let text: String

    var body: some View {
        VStack(spacing: 4) {
            Text(text)
                .font(.system(size: 14, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.primaryStrong)
                .frame(width: 28, height: 28)
                .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            Text(label)
                .font(.system(size: 7.5, weight: .bold))
                .foregroundStyle(AppPalette.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.65)
                .frame(width: 34)
        }
    }
}

private struct MiniIconBox: View {
    let icon: String
    let label: String

    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(AppPalette.textPrimary)
                .frame(width: 28, height: 28)
                .background(AppPalette.surfaceSoft.opacity(0.75), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            Text(label)
                .font(.system(size: 7.5, weight: .bold))
                .foregroundStyle(AppPalette.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.65)
                .frame(width: 34)
        }
    }
}

private struct RinbamAppIcon: View {
    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: "book.fill")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(AppPalette.primaryStrong, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            Text("りんばむ")
                .font(.system(size: 7.5, weight: .bold))
                .foregroundStyle(AppPalette.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.65)
                .frame(width: 38)
        }
    }
}

private struct MiniChip: View {
    let text: String
    let background: Color
    let foreground: Color

    init(_ text: String, background: Color, foreground: Color) {
        self.text = text
        self.background = background
        self.foreground = foreground
    }

    var body: some View {
        Text(text)
            .font(.system(size: 9.5, weight: .heavy))
            .foregroundStyle(foreground)
            .lineLimit(1)
            .minimumScaleFactor(0.65)
            .fixedSize(horizontal: true, vertical: false)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(background, in: Capsule())
    }
}

private struct ArchiveActionBlock: View {
    var body: some View {
        VStack(spacing: 2) {
            Image(systemName: "archivebox")
                .font(.system(size: 13, weight: .bold))
            Text("アーカイブ")
                .font(.system(size: 9, weight: .bold))
        }
        .foregroundStyle(Color.white.opacity(0.95))
        .frame(width: 60, height: 50)
        .background(AppPalette.secondarySurface, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct DeleteActionBlock: View {
    var body: some View {
        VStack(spacing: 2) {
            Image(systemName: "trash")
                .font(.system(size: 13, weight: .bold))
            Text("削除")
                .font(.system(size: 9, weight: .bold))
        }
        .foregroundStyle(.white)
        .frame(width: 48, height: 50)
        .background(AppPalette.danger, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct DetailedMiniURLCard: View {
    var body: some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(AppPalette.primaryStrong.opacity(0.5))
                .frame(width: 30, height: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text("週末に行きたい温泉まとめ10選")
                    .font(.system(size: 8.5, weight: .bold))
                    .foregroundStyle(AppPalette.textPrimary)
                    .lineLimit(1)
                Text("example.com/trip/10")
                    .font(.system(size: 8.5, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
                    .lineLimit(1)
                HStack(spacing: 4) {
                    MiniChip("旅行", background: Color(hex: 0xE5F6E7), foreground: Color(hex: 0x128A2E))
                    MiniChip("温泉", background: Color(hex: 0xEAF2FF), foreground: AppPalette.primaryStrong)
                }
            }
        }
        .padding(.horizontal, 8)
        .frame(height: 50)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppPalette.surfaceSoft.opacity(0.72), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct UsageGuideNote: View {
    var body: some View {
        HStack(spacing: 12) {
            Text("✦")
                .font(.system(size: 24, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.primaryStrong)
            Text("もっと詳しい使い方や、よくある質問は「使い方」を随時更新しています。\nブックマークからいつでも見返せます。")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(AppPalette.primaryStrong)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppPalette.primaryStrong.opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(AppPalette.primaryStrong.opacity(0.22), lineWidth: 1)
        )
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

struct OnboardingGuidePage: Sendable {
    let title: String
    let body: String
    let spotlight: @Sendable (CGSize) -> CGRect
    let arrow: @Sendable (CGRect) -> CGPoint
    let arrowText: String
    let panelOnTop: Bool
    let bodyFontSize: CGFloat
    let bodyFontDesign: Font.Design
    let bodyFontWeight: Font.Weight
    let arrowYOffset: CGFloat
    let panelYOffset: CGFloat

    init(
        title: String,
        body: String,
        spotlight: @escaping @Sendable (CGSize) -> CGRect,
        arrow: @escaping @Sendable (CGRect) -> CGPoint,
        arrowText: String,
        panelOnTop: Bool,
        bodyFontSize: CGFloat = 17,
        bodyFontDesign: Font.Design = .default,
        bodyFontWeight: Font.Weight = .medium,
        arrowYOffset: CGFloat = 0,
        panelYOffset: CGFloat = 0
    ) {
        self.title = title
        self.body = body
        self.spotlight = spotlight
        self.arrow = arrow
        self.arrowText = arrowText
        self.panelOnTop = panelOnTop
        self.bodyFontSize = bodyFontSize
        self.bodyFontDesign = bodyFontDesign
        self.bodyFontWeight = bodyFontWeight
        self.arrowYOffset = arrowYOffset
        self.panelYOffset = panelYOffset
    }
}

let onboardingGuidePages: [OnboardingGuidePage] = [
    OnboardingGuidePage(
        title: "自作タグを作成",
        body: "＋を押すと、自分用のタグを作れます。保存するURLを用途ごとに整理できます。",
        spotlight: { _ in CGRect(x: 14, y: 106, width: 56, height: 40) },
        arrow: { rect in CGPoint(x: rect.maxX + 22, y: rect.maxY - 18) },
        arrowText: "↖",
        panelOnTop: false
    ),
    OnboardingGuidePage(
        title: "タグを移動",
        body: "タグを長押ししたまま左右へ動かすと、好きな順番に並び替えできます。",
        spotlight: { size in CGRect(x: 76, y: 106, width: max(size.width - 94, 160), height: 44) },
        arrow: { rect in CGPoint(x: rect.midX, y: rect.maxY - 23) },
        arrowText: "↑",
        panelOnTop: false
    ),
    OnboardingGuidePage(
        title: "共有タグ",
        body: "共有タグはサインイン後に使えます。招待されたタグのURL一覧だけを端末間で同期します。",
        spotlight: { _ in CGRect(x: 14, y: 180, width: 56, height: 40) },
        arrow: { rect in CGPoint(x: rect.maxX + 18, y: rect.maxY - 12) },
        arrowText: "↖",
        panelOnTop: false
    ),
    OnboardingGuidePage(
        title: "問い合わせ場所",
        body: "プロフィールを開いた後、問い合わせから不具合や改善点を送れます。",
        spotlight: { size in CGRect(x: 20, y: size.height - 268, width: max(size.width - 40, 160), height: 58) },
        arrow: { rect in CGPoint(x: rect.midX, y: rect.minY - 54) },
        arrowText: "↓",
        panelOnTop: true,
        arrowYOffset: 19,
        panelYOffset: 76
    ),
    OnboardingGuidePage(
        title: "称賛のお気持ちも受け付けております！",
        body: "あまり怒らないでね、、、",
        spotlight: { size in CGRect(x: 20, y: size.height - 268, width: max(size.width - 40, 160), height: 58) },
        arrow: { rect in CGPoint(x: rect.midX, y: rect.minY - 54) },
        arrowText: "↓",
        panelOnTop: true,
        bodyFontSize: 16,
        bodyFontDesign: .rounded,
        bodyFontWeight: .heavy,
        arrowYOffset: 19,
        panelYOffset: 76
    ),
]

struct OnboardingGuideOverlay: View {
    let pageIndex: Int
    let onFinish: () -> Void
    let onNext: (Int) -> Void

    var body: some View {
        GeometryReader { proxy in
            let page = onboardingGuidePages[pageIndex]
            let size = proxy.size
            let spotlight = page.spotlight(size)
            let baseArrow = page.arrow(spotlight)
            let arrow = CGPoint(x: baseArrow.x, y: baseArrow.y + page.arrowYOffset)
            let isLast = pageIndex == onboardingGuidePages.count - 1

            ZStack {
                Color.black.opacity(0.72)
                    .overlay {
                        RoundedRectangle(cornerRadius: 22, style: .continuous)
                            .frame(width: spotlight.width, height: spotlight.height)
                            .position(x: spotlight.midX, y: spotlight.midY)
                            .blendMode(.destinationOut)
                    }
                    .compositingGroup()
                    .ignoresSafeArea()

                Text(page.arrowText)
                    .font(.system(size: 44, weight: .heavy, design: .rounded))
                    .foregroundStyle(.white)
                    .position(arrow)

                OnboardingGuidePanel(
                    page: page,
                    pageIndex: pageIndex,
                    pageCount: onboardingGuidePages.count,
                    isLast: isLast,
                    onSkip: onFinish,
                    onNext: {
                        if isLast {
                            onFinish()
                        } else {
                            withAnimation(.spring(response: 0.28, dampingFraction: 0.9)) {
                                onNext(pageIndex + 1)
                            }
                        }
                    }
                )
                .padding(.horizontal, 20)
                .frame(maxHeight: .infinity, alignment: guidePanelAlignment(for: spotlight, in: size, page: page))
                .padding(.top, guidePanelTopPadding(for: spotlight, in: size, page: page) + page.panelYOffset)
            }
        }
    }
}

private func guidePanelAlignment(
    for spotlight: CGRect,
    in size: CGSize,
    page: OnboardingGuidePage
) -> Alignment {
    if page.panelOnTop {
        return .top
    }
    return spotlight.midY < size.height * 0.42 ? .center : .top
}

private func guidePanelTopPadding(
    for spotlight: CGRect,
    in size: CGSize,
    page: OnboardingGuidePage
) -> CGFloat {
    if page.panelOnTop {
        let minimumTop: CGFloat = 42
        let preferredTop = spotlight.maxY + 32
        let maximumTop = max(minimumTop, size.height * 0.46)
        return min(max(preferredTop, minimumTop), maximumTop)
    }
    return spotlight.midY < size.height * 0.42 ? 0 : min(spotlight.maxY + 42, size.height * 0.46)
}

private struct OnboardingGuidePanel: View {
    let page: OnboardingGuidePage
    let pageIndex: Int
    let pageCount: Int
    let isLast: Bool
    let onSkip: () -> Void
    let onNext: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("\(pageIndex + 1)/\(pageCount)")
                .font(.system(size: 15, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.primaryStrong)

            Text(page.title)
                .font(.system(size: 25, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)

            Text(page.body)
                .font(.system(size: page.bodyFontSize, weight: page.bodyFontWeight, design: page.bodyFontDesign))
                .foregroundStyle(AppPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            HStack {
                Button("スキップ", action: onSkip)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(AppPalette.textSecondary)

                Spacer()

                Button(isLast ? "はじめる" : "次へ", action: onNext)
                    .font(.system(size: 17, weight: .heavy, design: .rounded))
                    .foregroundStyle(AppPalette.textPrimary)
                    .padding(.horizontal, 20)
                    .frame(height: 46)
                    .background(AppPalette.primary, in: Capsule())
            }
            .padding(.top, 2)
        }
        .padding(20)
        .background(AppPalette.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(AppPalette.outlineSoft, lineWidth: 1.2)
        )
        .shadow(color: .black.opacity(0.22), radius: 22, x: 0, y: 10)
    }
}

func searchFilteredEntries(
    _ entries: [URLRecord],
    query: String,
    localTags: [LocalTagSummary] = [],
    localTagAssignments: [Int64: Set<Int64>] = [:]
) -> [URLRecord] {
    let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    guard !needle.isEmpty else { return entries }
    let localTagNamesByID = Dictionary(uniqueKeysWithValues: localTags.map { ($0.id, $0.name.lowercased()) })
    return entries.filter { entry in
        let entryLocalTagNames = (localTagAssignments[entry.id] ?? [])
            .compactMap { localTagNamesByID[$0] }
        return [
            entry.originalURL,
            entry.normalizedURL,
            entry.displayURL,
            entry.openURL,
            entry.normalizedHost,
            entry.rawSourceHost,
            entry.userTitle ?? "",
            entry.fetchedTitle ?? "",
            entry.fetchedAuthorName ?? "",
            entry.fetchedBody ?? "",
            entry.bodySummary ?? "",
            entry.description ?? "",
            entry.memo,
            entry.effectiveTitle,
        ].contains { $0.lowercased().contains(needle) }
            || entryLocalTagNames.contains { $0.contains(needle) }
    }
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
                    case .ready(let displayName, let inviteType):
                        inviteTitle(inviteType == .group ? "グループに参加しますか？" : "共有タグに参加しますか？")
                        Text("「\(displayName)」")
                            .font(.system(size: 30, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textPrimary)
                            .multilineTextAlignment(.center)
                            .lineLimit(3)
                            .minimumScaleFactor(0.75)

                        Text(inviteType == .group ? "参加すると、このグループの共有タグが同期されます。" : "参加すると、この共有タグのURL一覧が同期されます。")
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
        case .success(let displayName, let inviteType):
            previewState = .ready(displayName: displayName, inviteType: inviteType)
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
            case .accepted(_, let inviteType, _):
                didJoin = true
                message = inviteType == .group ? "グループに参加しました。" : "共有タグに参加しました。"
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
        case ready(displayName: String, inviteType: SharedInviteType)
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

private struct SharedTagGroupScreen: View {
    @ObservedObject var model: URLSaverAppModel
    let groups: [SharedTagGroupSummary]
    let sharedTags: [SharedTagSummary]
    let onBack: () -> Void
    let onCreateGroup: () -> Void
    let onOpenSharedTag: (String) -> Void
    let onShareInvite: (String) -> Void

    @State private var selectedGroupID: String?
    @State private var isWorking = false
    @State private var selectedTab: SharedTagGroupDetailTab = .manage
    @State private var managementContent: SharedTagGroupManagementContent = .roleGuide
    @State private var pendingAction: SharedTagGroupPendingAction?
    @State private var renameDraft = ""
    @State private var isShowingRename = false

    private var selectedGroup: SharedTagGroupSummary? {
        groups.first { $0.remoteGroupID == selectedGroupID }
    }

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(
                title: "グループ",
                leadingButton: ScreenHeaderButton(
                    icon: "arrow.left",
                    accessibilityLabel: "戻る",
                    action: {
                        if selectedGroupID == nil {
                            onBack()
                        } else {
                            selectedGroupID = nil
                            managementContent = .roleGuide
                        }
                    }
                ),
                trailingButtons: [
                    ScreenHeaderButton(
                        icon: "plus",
                        title: "グループ作成",
                        accessibilityLabel: "グループを作成",
                        action: onCreateGroup
                    ),
                ]
            )

            GeometryReader { proxy in
                let cardWidth = max(proxy.size.width - 32, 0)
                ScrollView(showsIndicators: false) {
                    LazyVStack(spacing: 14) {
                        if let selectedGroup {
                            groupDetail(group: selectedGroup, cardWidth: cardWidth)
                        } else {
                            if groups.isEmpty {
                                AppPanel {
                                    Text("グループはまだありません")
                                        .font(.system(size: 24, weight: .heavy, design: .rounded))
                                        .foregroundStyle(AppPalette.textPrimary)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    Text("グループを作ると、複数の共有タグをまとめて相手に共有できます。")
                                        .font(.system(size: 16, weight: .medium))
                                        .foregroundStyle(AppPalette.textSecondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                    AppActionButton(tone: .primary, action: onCreateGroup) {
                                        Text("グループを作成")
                                    }
                                }
                                .frame(width: cardWidth)
                                .padding(.top, 160)
                            } else {
                                ForEach(groups) { group in
                                    Button {
                                        selectedGroupID = group.remoteGroupID
                                        selectedTab = .manage
                                        managementContent = .roleGuide
                                    } label: {
                                        SharedTagGroupCard(group: group)
                                            .frame(width: cardWidth)
                                    }
                                    .buttonStyle(.plain)
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
        .alert(pendingAction?.title ?? "", isPresented: Binding(
            get: { pendingAction != nil },
            set: { if !$0 { pendingAction = nil } }
        )) {
            Button("キャンセル", role: .cancel) { pendingAction = nil }
            Button(pendingAction?.confirmLabel ?? "実行", role: pendingAction?.isDangerous == true ? .destructive : nil) {
                if let action = pendingAction {
                    run(action)
                }
            }
        } message: {
            Text(pendingAction?.message ?? "")
        }
        .alert("グループ名を変更", isPresented: $isShowingRename) {
            TextField("グループ名", text: $renameDraft)
            Button("キャンセル", role: .cancel) {}
            Button("変更する") {
                guard let selectedGroup else { return }
                Task { _ = await model.renameSharedTagGroup(remoteGroupID: selectedGroup.remoteGroupID, name: renameDraft) }
            }
        } message: {
            Text("参加者に表示されるグループ名を変更します。")
        }
    }

    @ViewBuilder
    private func groupDetail(group: SharedTagGroupSummary, cardWidth: CGFloat) -> some View {
        let groupTags = model.loadGroupTags(remoteGroupID: group.remoteGroupID)
        let members = model.loadGroupMembers(remoteGroupID: group.remoteGroupID)
        let groupedTagIDs = Set(groupTags.map(\.remoteTagID))
        let addableTags = sharedTags
            .filter { $0.currentUserRole == .owner && !groupedTagIDs.contains($0.remoteTagID) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }

        AppPanel {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(group.name)
                        .font(.system(size: 24, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                        .lineLimit(1)
                    Text("権限: \(group.currentUserRole?.displayName ?? "同期中") / 共有タグ \(groupTags.count)件 / メンバー \(members.count)人")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
                Button {
                    renameDraft = group.name
                    isShowingRename = true
                } label: {
                    Image(systemName: "pencil")
                        .font(.system(size: 17, weight: .bold))
                        .frame(width: 38, height: 38)
                }
                .disabled(group.currentUserRole != .owner)
                .accessibilityLabel("グループ名を変更")
            }
        }
        .frame(width: cardWidth)

        Picker("", selection: $selectedTab) {
            ForEach(SharedTagGroupDetailTab.allCases) { tab in
                Text(tab.title).tag(tab)
            }
        }
        .pickerStyle(.segmented)
        .frame(width: cardWidth)

        switch selectedTab {
        case .manage:
            management(
                group: group,
                groupTags: groupTags,
                addableTags: addableTags,
                cardWidth: cardWidth
            )
        case .tags:
            groupTagCards(groupTags: groupTags, cardWidth: cardWidth)
        case .members:
            memberManagement(group: group, members: members, cardWidth: cardWidth)
        }
    }

    @ViewBuilder
    private func groupTagCards(
        groupTags: [SharedTagGroupTagSummary],
        cardWidth: CGFloat
    ) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if groupTags.isEmpty {
                Text("このグループには共有タグがありません")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
            } else {
                ForEach(groupTags) { tag in
                    Button {
                        onOpenSharedTag(tag.remoteTagID)
                    } label: {
                        SharedTagGroupTagCard(
                            tag: tag,
                            urlCount: sharedTagURLCount(remoteTagID: tag.remoteTagID)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .frame(width: cardWidth)
    }

    private func sharedTagURLCount(remoteTagID: String) -> Int? {
        sharedTags.first { $0.remoteTagID == remoteTagID }?.activeURLCount
    }

    @ViewBuilder
    private func memberManagement(
        group: SharedTagGroupSummary,
        members: [SharedTagGroupMemberSummary],
        cardWidth: CGFloat
    ) -> some View {
        AppPanel {
            Text("メンバー")
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
            if members.isEmpty {
                Text("メンバー情報を同期中です")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
            } else {
                ForEach(members) { member in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(memberLabel(member))
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(AppPalette.textPrimary)
                            .lineLimit(1)
                        Text(member.role.displayName)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                        if group.currentUserRole == .owner && !member.isCurrentUser {
                            HStack {
                                if member.role != .editor {
                                    Button("編集者にする") { pendingAction = .changeRole(group: group, member: member, role: .editor) }
                                }
                                if member.role != .viewer {
                                    Button("閲覧者にする") { pendingAction = .changeRole(group: group, member: member, role: .viewer) }
                                }
                            }
                            HStack {
                                Button("オーナー移譲") { pendingAction = .transferOwnership(group: group, member: member) }
                                Button("削除", role: .destructive) { pendingAction = .removeMember(group: group, member: member) }
                            }
                            .font(.system(size: 14, weight: .bold))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
                }
            }
        }
        .frame(width: cardWidth)
    }

    @ViewBuilder
    private func management(
        group: SharedTagGroupSummary,
        groupTags: [SharedTagGroupTagSummary],
        addableTags: [SharedTagSummary],
        cardWidth: CGFloat
    ) -> some View {
        AppPanel {
            HStack(spacing: 6) {
                AppActionButton(tone: .secondary, enabled: group.currentUserRole == .owner && !isWorking) {
                    createInvite(group: group, role: .editor)
                } label: {
                    Text("編集者招待")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                AppActionButton(tone: .secondary, enabled: group.currentUserRole == .owner && !isWorking) {
                    createInvite(group: group, role: .viewer)
                } label: {
                    Text("閲覧招待")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                AppActionButton(tone: .secondary) {
                    managementContent = .tagManagement
                } label: {
                    Text("共有タグ")
                        .font(.system(size: 13, weight: .bold, design: .rounded))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
            if group.currentUserRole != .owner {
                Text("招待リンクを作成できるのはグループオーナーだけです。")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
            }
            switch managementContent {
            case .roleGuide:
                Divider()
                Text("権限の違い")
                    .font(.system(size: 16, weight: .heavy))
                    .foregroundStyle(AppPalette.textPrimary)
                roleGuideLine(label: "オーナー:", body: "グループ設定、招待、メンバー管理、配下共有タグの管理ができます。")
                roleGuideLine(label: "編集者:", body: "配下共有タグにURLを追加・削除できます。")
                roleGuideLine(label: "閲覧者:", body: "配下共有タグとURLを見られます。編集はできません。")
                    .padding(.bottom, 2)
                Text("同期エラーがある場合は、再同期後にこの画面へ反映されます。")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
            case .tagManagement:
                Divider()
                groupTagManagement(group: group, groupTags: groupTags, addableTags: addableTags)
            }
            Divider()
            Text("危険な操作")
                .font(.system(size: 16, weight: .heavy))
                .foregroundStyle(AppPalette.danger)
            Button("グループを削除", role: .destructive) {
                pendingAction = .deleteGroup(group: group)
            }
            .disabled(group.currentUserRole != .owner)
        }
        .frame(width: cardWidth)
    }

    @ViewBuilder
    private func groupTagManagement(
        group: SharedTagGroupSummary,
        groupTags: [SharedTagGroupTagSummary],
        addableTags: [SharedTagSummary]
    ) -> some View {
        HStack {
            Text("共有タグ管理")
                .font(.system(size: 16, weight: .heavy))
                .foregroundStyle(AppPalette.textPrimary)
            Spacer(minLength: 0)
            Button("権限の違いに戻る") {
                managementContent = .roleGuide
            }
            .font(.system(size: 13, weight: .bold))
        }
        if groupTags.isEmpty {
            Text("このグループには共有タグがありません")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)
        } else {
            ForEach(groupTags) { tag in
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(tag.tagName)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(AppPalette.textPrimary)
                            .lineLimit(1)
                        Text(groupTagMetaText(tag: tag))
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                    }
                    Spacer(minLength: 0)
                    Button("外す") {
                        pendingAction = .removeTag(group: group, tag: tag)
                    }
                    .font(.system(size: 14, weight: .bold))
                    .disabled(isWorking || !(group.currentUserRole == .owner || tag.currentUserRole == .owner))
                }
                .padding(.vertical, 8)
            }
        }
        Text("共有タグを追加")
            .font(.system(size: 15, weight: .heavy))
            .foregroundStyle(AppPalette.textPrimary)
        if addableTags.isEmpty {
            Text("追加できるオーナー権限の共有タグはありません。")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)
        } else {
            ForEach(addableTags) { tag in
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(tag.name)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(AppPalette.textPrimary)
                            .lineLimit(1)
                        Text("\(tag.activeURLCount)件")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                    }
                    Spacer(minLength: 0)
                    Button("追加") {
                        Task { await addTag(tag, to: group) }
                    }
                    .font(.system(size: 14, weight: .bold))
                    .disabled(isWorking)
                }
                .padding(.vertical, 8)
            }
        }
    }

    private func groupTagMetaText(tag: SharedTagGroupTagSummary) -> String {
        var parts = [tag.currentUserRole?.displayName ?? "同期中"]
        if let count = sharedTagURLCount(remoteTagID: tag.remoteTagID) {
            parts.append("\(count)件")
        }
        return parts.joined(separator: " / ")
    }

    private func roleGuideLine(label: String, body: String) -> some View {
        HStack(alignment: .top, spacing: 0) {
            Text(label)
                .frame(width: 70, alignment: .leading)
            Text(body)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.system(size: 15, weight: .medium))
        .foregroundStyle(AppPalette.textSecondary)
    }

    private func addTag(_ tag: SharedTagSummary, to group: SharedTagGroupSummary) async {
        guard !isWorking else { return }
        isWorking = true
        _ = await model.addSharedTag(remoteTagID: tag.remoteTagID, toGroup: group.remoteGroupID)
        isWorking = false
    }

    private func removeTag(_ tag: SharedTagGroupTagSummary, from group: SharedTagGroupSummary) async {
        guard !isWorking else { return }
        isWorking = true
        _ = await model.removeSharedTag(remoteTagID: tag.remoteTagID, fromGroup: group.remoteGroupID)
        isWorking = false
    }

    private func memberLabel(_ member: SharedTagGroupMemberSummary) -> String {
        if member.isCurrentUser { return "あなた" }
        if let displayName = member.displayName, !displayName.isEmpty { return displayName }
        return "メンバー \(member.userID.prefix(8))"
    }

    private func run(_ action: SharedTagGroupPendingAction) {
        pendingAction = nil
        Task {
            switch action {
            case .removeTag(let group, let tag):
                await removeTag(tag, from: group)
            case .changeRole(let group, let member, let role):
                _ = await model.changeSharedTagGroupMemberRole(remoteGroupID: group.remoteGroupID, userID: member.userID, role: role)
            case .transferOwnership(let group, let member):
                _ = await model.transferSharedTagGroupOwnership(remoteGroupID: group.remoteGroupID, userID: member.userID)
            case .removeMember(let group, let member):
                _ = await model.removeSharedTagGroupMember(remoteGroupID: group.remoteGroupID, userID: member.userID)
            case .deleteGroup(let group):
                if await model.deleteSharedTagGroup(remoteGroupID: group.remoteGroupID) {
                    selectedGroupID = nil
                }
            }
        }
    }

    private func createInvite(group: SharedTagGroupSummary, role: SharedTagMemberRole) {
        guard !isWorking else { return }
        isWorking = true
        Task {
            let result = await model.createInviteForSharedTagGroup(remoteGroupID: group.remoteGroupID, role: role)
            if case .success(let inviteURL, _) = result {
                UIPasteboard.general.string = inviteURL
                onShareInvite(inviteURL)
            }
            isWorking = false
        }
    }
}

private struct SharedTagGroupTagCard: View {
    let tag: SharedTagGroupTagSummary
    let urlCount: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(tag.tagName)
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(1)
            Text(metaText)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(AppPalette.outlineSoft, lineWidth: 1.5)
        )
    }

    private var metaText: String {
        var parts = [tag.currentUserRole?.displayName ?? "同期中"]
        if let urlCount {
            parts.append("\(urlCount)件")
        }
        return parts.joined(separator: " / ")
    }
}

private enum SharedTagGroupManagementContent {
    case roleGuide
    case tagManagement
}

private enum SharedTagGroupDetailTab: String, CaseIterable, Identifiable {
    case manage
    case tags
    case members

    var id: String { rawValue }

    var title: String {
        switch self {
        case .manage: return "管理"
        case .tags: return "共有タグ"
        case .members: return "メンバー"
        }
    }
}

private enum SharedTagGroupPendingAction: Identifiable {
    case removeTag(group: SharedTagGroupSummary, tag: SharedTagGroupTagSummary)
    case changeRole(group: SharedTagGroupSummary, member: SharedTagGroupMemberSummary, role: SharedTagMemberRole)
    case transferOwnership(group: SharedTagGroupSummary, member: SharedTagGroupMemberSummary)
    case removeMember(group: SharedTagGroupSummary, member: SharedTagGroupMemberSummary)
    case deleteGroup(group: SharedTagGroupSummary)

    var id: String { title + message }

    var title: String {
        switch self {
        case .removeTag: return "共有タグを外す"
        case .changeRole: return "権限を変更"
        case .transferOwnership: return "オーナー権限を移譲"
        case .removeMember: return "メンバーを削除"
        case .deleteGroup: return "グループを削除"
        }
    }

    var message: String {
        switch self {
        case .removeTag(_, let tag):
            return "「\(tag.tagName)」をこのグループから外します。タグ自体やURLは削除されません。"
        case .changeRole(_, let member, let role):
            return "「\(memberDisplayName(member))」を\(role.displayName)に変更します。"
        case .transferOwnership(_, let member):
            return "「\(memberDisplayName(member))」をグループオーナーにします。移譲後、あなたは編集者になります。"
        case .removeMember(_, let member):
            return "「\(memberDisplayName(member))」をこのグループから削除します。"
        case .deleteGroup:
            return "このグループを削除します。配下タグのまとめ共有とグループ招待は無効になります。"
        }
    }

    var confirmLabel: String {
        switch self {
        case .removeTag: return "外す"
        case .changeRole: return "変更する"
        case .transferOwnership: return "移譲する"
        case .removeMember, .deleteGroup: return "削除する"
        }
    }

    var isDangerous: Bool {
        switch self {
        case .changeRole: return false
        case .removeTag, .transferOwnership, .removeMember, .deleteGroup: return true
        }
    }

    private func memberDisplayName(_ member: SharedTagGroupMemberSummary) -> String {
        if member.isCurrentUser { return "あなた" }
        if let displayName = member.displayName, !displayName.isEmpty { return displayName }
        return "メンバー \(member.userID.prefix(8))"
    }
}

private struct SharedTagGroupCard: View {
    let group: SharedTagGroupSummary

    var body: some View {
        AppPanel {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: "person.3.fill")
                    .font(.system(size: 20, weight: .heavy))
                    .foregroundStyle(AppPalette.primaryStrong)
                    .frame(width: 42, height: 42)
                    .background(AppPalette.background, in: Circle())

                VStack(alignment: .leading, spacing: 5) {
                    Text(group.name)
                        .font(.system(size: 20, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                        .lineLimit(2)
                    Text(group.currentUserRole?.displayName ?? "メンバー")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(AppPalette.textSecondary)
                    Text("共有タグ \(group.tagCount)件 / メンバー \(group.memberCount)人")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                    Text(group.lastSyncedAt == nil ? "同期状態を確認中" : "同期済み")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                }
                Spacer(minLength: 0)
            }
        }
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

private struct SharedTagGroupCreateSheet: View {
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var model: URLSaverAppModel
    let onOpenProfile: () -> Void

    @State private var groupName = ""
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
                    Text("グループを作成")
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
            Text("新しいグループ")
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            TextField(
                "",
                text: $groupName,
                prompt: Text("グループ名").foregroundStyle(AppPalette.textMuted)
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
                enabled: !groupName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isWorking
            ) {
                guard !isWorking else { return }
                isWorking = true
                Task {
                    if await model.createSharedTagGroup(name: groupName) {
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
            Text("グループを作るにはサインインが必要です")
                .font(.system(size: 20, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            Text("グループはクラウド同期を使うため、先にプロフィール画面でサインインしてください。")
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
    let localLinkText: (LocalTagSummary) -> String
    let payloadText: (LocalTagSummary) -> String?
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
                                LocalTagManagementPill(
                                    tag: tag,
                                    localLinkText: localLinkText(tag),
                                    payloadText: payloadText(tag),
                                    onDelete: onDelete
                                )
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
    let localLinkText: String
    let payloadText: String?
    let onDelete: (LocalTagSummary) -> Void

    var body: some View {
        HStack(spacing: 6) {
            Text(tag.name)
                .font(.system(size: 21, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(1)
                .frame(maxWidth: 104, alignment: .leading)

            ShareLink(item: localLinkText) {
                Label("リンク", systemImage: "link")
                    .font(.system(size: 18, weight: .heavy))
                    .lineLimit(1)
            }
            .buttonStyle(.plain)
            .foregroundStyle(AppPalette.primaryStrong)

            if let payloadText {
                ShareLink(item: payloadText) {
                    Label("JSON", systemImage: "square.and.arrow.up")
                        .font(.system(size: 18, weight: .heavy))
                        .lineLimit(1)
                }
                .buttonStyle(.plain)
                .foregroundStyle(AppPalette.primaryStrong)
            }

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
