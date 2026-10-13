import SwiftUI

struct SharedTagDetailSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    @ObservedObject var model: URLSaverAppModel
    let remoteTagID: String
    @Binding var displayMode: EntryListDisplayMode

    @State private var tag: SharedTagSummary?
    @State private var entries: [URLRecord] = []
    @State private var members: [SharedTagMemberSummary] = []
    @State private var navigationPath: [Int64] = []
    @State private var isShowingAddEntrySheet = false
    @State private var isShowingDeleteConfirmation = false
    @State private var isShowingLeaveConfirmation = false
    @State private var isWorking = false
    @State private var isSyncing = false
    @State private var shareItems: [Any] = []
    @State private var inviteNotice: String?
    @State private var syncNotice: String?
    @State private var pendingRemovedEntryIDs: Set<Int64> = []
    @State private var pendingRemovalTasks: [Int64: Task<Void, Never>] = [:]
    @State private var undoNotice: SharedTagUndoNotice?
    @State private var pendingOwnershipTransferMember: SharedTagMemberSummary?

    var body: some View {
        GeometryReader { proxy in
            let contentWidth = min(proxy.size.width, 680)
            let sidePadding = max((proxy.size.width - contentWidth) / 2, 0) + 16
            let visibleEntries = entries.filter { !pendingRemovedEntryIDs.contains($0.id) }

            NavigationStack(path: $navigationPath) {
                ScreenContainer {
                    VStack(spacing: 0) {
                        ScreenHeader(
                            title: "共有タグ",
                            leadingButton: ScreenHeaderButton(
                                icon: "chevron.left",
                                accessibilityLabel: "戻る",
                                action: { dismiss() }
                            ),
                            trailingButtons: [],
                            trailingAccessory: tag.map { AnyView(sharedTagHeaderStatus(for: $0)) }
                        )

                        if let tag {
                            ScrollView {
                                VStack(alignment: .leading, spacing: 8) {
                                    actionRow(for: tag)
                                    syncedTagInfo(for: tag)
                                    Divider().overlay(AppPalette.outlineSoft.opacity(0.5))
                                    SharedTagMembersStrip(
                                        members: members,
                                        canTransferOwnership: tag.currentUserRole == .owner,
                                        onTransferOwnership: { pendingOwnershipTransferMember = $0 }
                                    )
                                    Divider().overlay(AppPalette.outlineSoft.opacity(0.5))
                                    sharedTagCountRow(count: visibleEntries.count, for: tag)

                                    if visibleEntries.isEmpty {
                                        SharedTagEmptyState(canAdd: canEdit(tag))
                                            .frame(maxWidth: .infinity, minHeight: 200)
                                            .padding(.vertical, 16)
                                    } else {
                                        ForEach(visibleEntries) { entry in
                                            SwipeableSharedTagEntryCard(
                                                entry: entry,
                                                displayMode: displayMode,
                                                cardWidth: contentWidth - 32,
                                                canRemove: canEdit(tag),
                                                onTap: { navigationPath.append(entry.id) },
                                                onRemove: { scheduleRemoveEntryFromSharedTag(entry.id) }
                                            )
                                        }
                                    }
                                }
                                .frame(width: contentWidth - 32, alignment: .leading)
                                .padding(.horizontal, sidePadding)
                                .padding(.top, 0)
                                .padding(.bottom, 32)
                            }
                        } else {
                            SharedTagNotFoundState()
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                                .padding(.horizontal, 24)
                        }
                    }
                }
                .navigationDestination(for: Int64.self) { entryID in
                    DetailView(entryID: entryID, model: model)
                }
                .toolbar(.hidden, for: .navigationBar)
            }
        }
        .overlay(alignment: .bottom) {
            if let undoNotice {
                SharedTagUndoBanner(notice: undoNotice) {
                    undoPendingRemoval(undoNotice.entryID)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 18)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .task { await syncAndReload(showSuccessNotice: false) }
        .onDisappear {
            pendingRemovalTasks.values.forEach { $0.cancel() }
            pendingRemovalTasks = [:]
        }
        .sheet(isPresented: $isShowingAddEntrySheet) {
            SharedTagEntryPickerSheet(
                model: model,
                remoteTagID: remoteTagID,
                assignedEntryIDs: Set(entries.map(\.id)),
                displayMode: displayMode,
                onDidAssign: {
                    reload()
                }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.hidden)
            .presentationCornerRadius(32)
        }
        .sheet(isPresented: Binding(
            get: { !shareItems.isEmpty },
            set: { if !$0 { shareItems = [] } }
        )) {
            ActivityShareSheet(items: shareItems)
        }
        .alert("共有タグを削除", isPresented: $isShowingDeleteConfirmation) {
            Button("キャンセル", role: .cancel) {}
            Button("削除する", role: .destructive) {
                guard !isWorking else { return }
                isWorking = true
                Task {
                    if await model.deleteSharedTag(remoteTagID: remoteTagID) {
                        dismiss()
                    }
                    isWorking = false
                }
            }
        } message: {
            Text("この共有タグを削除すると、参加中メンバーの一覧からも外れます。")
        }
        .alert("共有タグから抜ける", isPresented: $isShowingLeaveConfirmation) {
            Button("キャンセル", role: .cancel) {}
            Button("抜ける", role: .destructive) {
                guard !isWorking else { return }
                isWorking = true
                Task {
                    if await model.leaveSharedTag(remoteTagID: remoteTagID) {
                        dismiss()
                    }
                    isWorking = false
                }
            }
        } message: {
            Text("この端末の共有タグ一覧から外れます。共有タグ自体や他の参加者のURLは削除されません。")
        }
        .alert("オーナー権限を移譲", isPresented: Binding(
            get: { pendingOwnershipTransferMember != nil },
            set: { if !$0 { pendingOwnershipTransferMember = nil } }
        )) {
            Button("キャンセル", role: .cancel) {
                pendingOwnershipTransferMember = nil
            }
            Button("移譲する") {
                guard let member = pendingOwnershipTransferMember, !isWorking else { return }
                isWorking = true
                Task {
                    if await model.transferSharedTagOwnership(remoteTagID: remoteTagID, newOwnerUserID: member.userID) {
                        await syncAndReload(showSuccessNotice: false)
                    }
                    pendingOwnershipTransferMember = nil
                    isWorking = false
                }
            }
        } message: {
            if let member = pendingOwnershipTransferMember {
                Text("\(memberLabel(member))へオーナー権限を移します。移譲後、あなたは編集者になり、共有タグの削除や招待リンク作成はできなくなります。")
            }
        }
    }

    private func reload() {
        tag = model.loadSharedTag(remoteTagID: remoteTagID)
        entries = model.loadEntriesForSharedTag(remoteTagID: remoteTagID)
        members = model.loadMembersForSharedTag(remoteTagID: remoteTagID)
    }

    private func syncAndReload(showSuccessNotice: Bool = true) async {
        guard !isSyncing else { return }
        reload()
        isSyncing = true
        syncNotice = nil
        let success = await model.syncSharedTagCloud(showFailureNotification: false)
        reload()
        isSyncing = false
        if success && showSuccessNotice {
            syncNotice = "更新しました"
        } else if !success {
            syncNotice = "同期できませんでした。前回同期した内容を表示しています。"
        }
    }

    private func scheduleRemoveEntryFromSharedTag(_ entryID: Int64) {
        guard !isWorking else { return }
        guard !pendingRemovedEntryIDs.contains(entryID) else { return }
        pendingRemovedEntryIDs.insert(entryID)
        undoNotice = SharedTagUndoNotice(entryID: entryID, message: "共有タグから外しました")
        pendingRemovalTasks[entryID]?.cancel()
        pendingRemovalTasks[entryID] = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled else { return }
            let removed = await model.removeEntry(entryID, fromSharedTag: remoteTagID)
            await MainActor.run {
                pendingRemovalTasks[entryID] = nil
                pendingRemovedEntryIDs.remove(entryID)
                if undoNotice?.entryID == entryID {
                    undoNotice = nil
                }
                if removed {
                    reload()
                } else {
                    syncNotice = "共有タグから外せませんでした"
                }
            }
        }
    }

    private func undoPendingRemoval(_ entryID: Int64) {
        pendingRemovalTasks[entryID]?.cancel()
        pendingRemovalTasks[entryID] = nil
        pendingRemovedEntryIDs.remove(entryID)
        if undoNotice?.entryID == entryID {
            undoNotice = nil
        }
    }

    private func canEdit(_ tag: SharedTagSummary) -> Bool {
        tag.currentUserRole == .owner || tag.currentUserRole == .editor
    }

    private func canInvite(_ tag: SharedTagSummary) -> Bool {
        tag.currentUserRole == .owner
    }

    private func canDelete(_ tag: SharedTagSummary) -> Bool {
        tag.currentUserRole == .owner
    }

    private func canLeave(_ tag: SharedTagSummary) -> Bool {
        guard let role = tag.currentUserRole else { return false }
        return role != .owner
    }

    private func sharedTagRoleBadge(for tag: SharedTagSummary) -> some View {
        Text(tag.currentUserRole?.displayName ?? "権限を確認中")
            .font(.system(.caption, design: .rounded).weight(.semibold))
            .foregroundStyle(Color(UIColor(hex: 0x855B76)))
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Color(UIColor(hex: 0xF6E6EE)), in: Capsule())
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityLabel("権限: \(tag.currentUserRole?.displayName ?? "確認中")")
    }

    private func sharedTagHeaderStatus(for tag: SharedTagSummary) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            SharedTagTitleSyncStatus(isSyncing: isSyncing, syncNotice: syncNotice, lastSyncedAt: tag.lastSyncedAt)
            sharedTagRoleBadge(for: tag)
        }
        .padding(.trailing, 10)
    }

    private func actionRow(for tag: SharedTagSummary) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: dynamicTypeSize.isAccessibilitySize ? 112 : 60), spacing: 8)], spacing: 10) {
            captionAction(title: "表示", icon: displayMode == .rich ? "eye" : "list.bullet", tint: 0xE6EAF9,
                          accessibilityLabel: displayMode == .rich ? "画像なし表示へ切り替える" : "画像つき表示へ切り替える") {
                displayMode = displayMode == .rich ? .compact : .rich
            }
            captionAction(title: "更新", icon: "arrow.clockwise", tint: 0xDCF1EC, accessibilityLabel: "共有タグを更新") {
                Task { await syncAndReload() }
            }
            .disabled(isSyncing)
            if canEdit(tag) {
                captionAction(title: "リンク追加", icon: "link.badge.plus", tint: 0xF8E3EC,
                              accessibilityLabel: "保存済みリンクをこの共有タグに追加") {
                    isShowingAddEntrySheet = true
                }
                .disabled(isWorking)
            }
            if canInvite(tag) {
                captionAction(title: "招待", icon: "person.badge.plus", tint: 0xE3EAFB, accessibilityLabel: "共有招待リンクを共有") {
                    shareInviteLink()
                }
                .disabled(isWorking)
            }
            if canDelete(tag) || canLeave(tag) {
                Menu {
                    if canDelete(tag) {
                        Button("共有タグを削除", role: .destructive) { isShowingDeleteConfirmation = true }
                    }
                    if canLeave(tag) {
                        Button("この共有タグから抜ける", role: .destructive) { isShowingLeaveConfirmation = true }
                    }
                } label: {
                    captionActionLabel(title: "管理", icon: "gearshape", tint: 0xEFE4F6)
                }
                .disabled(isWorking)
                .accessibilityLabel("共有タグを管理")
            }
        }
        .padding(.vertical, 0)
    }

    private func captionAction(title: String, icon: String, tint: Int, accessibilityLabel: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            captionActionLabel(title: title, icon: icon, tint: tint)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }

    private func captionActionLabel(title: String, icon: String, tint: Int) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 21, weight: .semibold))
                .foregroundStyle(AppPalette.textSecondary)
                .frame(width: 44, height: 44)
                .background(Color(UIColor(hex: tint)), in: Circle())
            Text(title)
                .font(.system(.caption, design: .rounded).weight(.semibold))
                .foregroundStyle(AppPalette.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .top)
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private func syncedTagInfo(for _: SharedTagSummary) -> some View {
        if let syncNotice, syncNotice != "更新しました",
           syncNotice != "同期できませんでした。前回同期した内容を表示しています。", !syncNotice.isEmpty {
            Text(syncNotice)
                .font(.callout)
                .foregroundStyle(AppPalette.warning)
                .fixedSize(horizontal: false, vertical: true)
        }
        if let inviteNotice, !inviteNotice.isEmpty {
            Text(inviteNotice)
                .font(.callout)
                .foregroundStyle(AppPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func sharedTagCountRow(count: Int, for _: SharedTagSummary) -> some View {
        HStack(spacing: 10) {
            Text("保存リンク")
                .font(.system(.headline, design: .rounded).weight(.heavy))
                .foregroundStyle(AppPalette.textPrimary)
            Spacer()
            Text("\(count)件")
                .font(.system(.subheadline, design: .rounded).weight(.semibold))
                .foregroundStyle(AppPalette.textSecondary)
        }
        .accessibilityElement(children: .combine)
    }

    private func shareInviteLink() {
        guard let tag, canInvite(tag), !isWorking else { return }
        isWorking = true
        inviteNotice = nil
        Task {
            let result = await model.createInviteForSharedTag(remoteTagID: remoteTagID)
            switch result {
            case .success(let inviteURL, _):
                shareItems = [inviteShareText(inviteURL: inviteURL)]
            case .authRequired:
                inviteNotice = "先に共有タグクラウドへサインインしてください"
            case .ownerOnly:
                inviteNotice = "招待リンクを共有できるのはオーナーだけです"
            case .syncPending:
                inviteNotice = "同期が終わってから招待リンクを共有してください"
            case .failure(let message):
                inviteNotice = message
            }
            isWorking = false
        }
    }
}

private func inviteShareText(inviteURL: String) -> String {
    guard let token = inviteURL.split(separator: "/").last, !token.isEmpty else {
        return inviteURL
    }
    return """
    りんばむの共有タグに参加:
    \(inviteURL)

    開けない場合:
    urlsaver://invite/\(token)
    """
}

private struct SharedTagEmptyState: View {
    let canAdd: Bool
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "link.slash")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(AppPalette.textSecondary)

            Text("まだリンクがありません")
                .font(.system(size: 21, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .multilineTextAlignment(.center)

            Text(canAdd ? "「リンク追加」から保存リンクを選べます" : "追加されるとここに表示されます")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: 320)
    }
}

private struct SharedTagInlineTitle: View {
    let title: String
    let isSyncing: Bool
    let syncNotice: String?

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(title)
                .font(.system(size: 24, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

            SharedTagTitleSyncStatus(isSyncing: isSyncing, syncNotice: syncNotice)
        }
    }
}

private struct SharedTagTitleSyncStatus: View {
    let isSyncing: Bool
    let syncNotice: String?
    var lastSyncedAt: Date? = nil

    var body: some View {
        if isSyncing {
            HStack(spacing: 6) {
                ProgressView()
                    .tint(AppPalette.textSecondary)
                Text("同期中")
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .foregroundStyle(AppPalette.textSecondary)
            }
            .fixedSize(horizontal: false, vertical: true)
        } else if let syncNotice, !syncNotice.isEmpty {
            Text(displayText(for: syncNotice))
                .font(.system(size: 15, weight: .bold, design: .rounded))
                .foregroundStyle(syncNotice == "更新しました" ? AppPalette.textPrimary : AppPalette.warning)
                .fixedSize(horizontal: false, vertical: true)
        } else if let lastSyncedAt {
            Text("同期済み")
                .font(.system(.caption, design: .rounded).weight(.semibold))
                .foregroundStyle(AppPalette.textSecondary)
                .accessibilityValue("最終同期: \(DateFormatters.detailTimestamp.string(from: lastSyncedAt))")
        } else {
            Text("未同期")
                .font(.system(.caption, design: .rounded).weight(.semibold))
                .foregroundStyle(AppPalette.textSecondary)
        }
    }

    private func displayText(for notice: String) -> String {
        notice == "更新しました" ? notice : "同期失敗"
    }
}

private struct SharedTagUndoNotice: Identifiable, Equatable {
    let id = UUID()
    let entryID: Int64
    let message: String
}

private struct SharedTagUndoBanner: View {
    let notice: SharedTagUndoNotice
    let onUndo: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(notice.message)
                .font(.system(size: 15, weight: .bold, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(2)

            Spacer(minLength: 8)

            Button(action: onUndo) {
                Text("元に戻す")
                    .font(.system(size: 15, weight: .heavy, design: .rounded))
                    .foregroundStyle(AppPalette.primaryStrong)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(AppPalette.surface)
                .shadow(color: .black.opacity(0.18), radius: 18, x: 0, y: 10)
        )
    }
}

private struct SharedTagMembersStrip: View {
    let members: [SharedTagMemberSummary]
    let canTransferOwnership: Bool
    let onTransferOwnership: (SharedTagMemberSummary) -> Void

    @State private var selectedMember: SharedTagMemberSummary?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("参加者 \(members.count)名")
                .font(.system(size: 15, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            if members.isEmpty {
                Text("同期が完了すると参加者が表示されます。")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 10) {
                        ForEach(members) { member in
                            SharedTagMemberAvatar(member: member) {
                                selectedMember = member
                            }
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .sheet(item: $selectedMember) { member in
            SharedTagMemberProfileSheet(
                member: member,
                canTransferOwnership: canTransferOwnership && !member.isCurrentUser && member.role != .owner,
                onTransferOwnership: {
                    selectedMember = nil
                    onTransferOwnership(member)
                }
            )
                .presentationDetents([.height(340), .medium])
        }
    }
}

private struct SharedTagMemberAvatar: View {
    let member: SharedTagMemberSummary
    let onTap: () -> Void

    var body: some View {
        VStack(spacing: 3) {
            Image(systemName: member.isCurrentUser ? "person.crop.circle.fill.badge.checkmark" : "person.crop.circle.fill")
                .font(.system(size: 30, weight: .semibold))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(AppPalette.textSecondary)
                .frame(width: 38, height: 38)

            Text(memberLabel(member))
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(width: 72)

            Text(member.role.displayName)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(AppPalette.textSecondary)
                .lineLimit(1)
                .frame(width: 72)
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
    }
}

private struct SharedTagMemberProfileSheet: View {
    @Environment(\.dismiss) private var dismiss

    let member: SharedTagMemberSummary
    let canTransferOwnership: Bool
    let onTransferOwnership: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Capsule()
                .fill(AppPalette.outlineSoft)
                .frame(width: 38, height: 4)
                .padding(.top, 12)

            Image(systemName: member.isCurrentUser ? "person.crop.circle.fill.badge.checkmark" : "person.crop.circle.fill")
                .font(.system(size: 58, weight: .semibold))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(AppPalette.textSecondary)

            Text(memberLabel(member))
                .font(.system(size: 22, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .multilineTextAlignment(.center)

            Text(member.isCurrentUser ? "自分のプロフィール" : "共有タグの参加者")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)

            Text("権限: \(member.role.displayName)")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(AppPalette.textSecondary)

            if canTransferOwnership {
                Button("オーナー権限を移譲") {
                    dismiss()
                    onTransferOwnership()
                }
                .buttonStyle(.bordered)
                .padding(.top, 4)
            }

            Button("閉じる") {
                dismiss()
            }
            .buttonStyle(.borderedProminent)
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(.horizontal, 24)
        .background(AppDisplayBackground())
    }
}

private struct SharedTagNotFoundState: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "link.slash")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(AppPalette.textSecondary)

            Text("共有タグが見つかりませんでした")
                .font(.system(size: 21, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .multilineTextAlignment(.center)

            Text("削除されたか、まだ読み込めていない可能性があります")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: 320)
    }
}

private func memberLabel(_ member: SharedTagMemberSummary) -> String {
    if member.isCurrentUser { return "自分" }
    return "ユーザー \(shortMemberUserID(member.userID))"
}

private func shortMemberUserID(_ userID: String) -> String {
    userID.count <= 8 ? userID : "\(userID.prefix(8))..."
}

struct SharedTagEntryPickerSheet: View {
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var model: URLSaverAppModel
    let remoteTagID: String
    let assignedEntryIDs: Set<Int64>
    let displayMode: EntryListDisplayMode
    let onDidAssign: () -> Void

    @State private var isWorking = false

    private var availableEntries: [URLRecord] {
        let combined = model.activeEntries + model.archivedEntries
        var seen = Set<Int64>()
        return combined.filter { entry in
            guard !assignedEntryIDs.contains(entry.id) else { return false }
            return seen.insert(entry.id).inserted
        }
    }

    var body: some View {
        ScreenContainer {
            VStack(alignment: .leading, spacing: 16) {
                Capsule()
                    .fill(AppPalette.outlineSoft)
                    .frame(width: 72, height: 8)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 10)

                HStack {
                    Text("保存リンクを選ぶ")
                        .font(.system(size: 24, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                }

                ScrollView(showsIndicators: false) {
                    VStack(spacing: 10) {
                        if availableEntries.isEmpty {
                            AppPanel {
                                Text("追加できるURLはありません")
                                    .font(.system(size: 18, weight: .heavy, design: .rounded))
                                    .foregroundStyle(AppPalette.textSecondary)
                            }
                        } else {
                            ForEach(availableEntries) { entry in
                                EntryCardView(
                                    entry: entry,
                                    timestampLabel: "保存",
                                    displayMode: displayMode,
                                    footerContent: {
                                        AnyView(
                                            HStack {
                                                Spacer()
                                                AppActionButton(tone: .primary, enabled: !isWorking) {
                                                    guard !isWorking else { return }
                                                    isWorking = true
                                                    Task {
                                                        if await model.addEntry(entry.id, toSharedTag: remoteTagID) {
                                                            onDidAssign()
                                                        }
                                                        isWorking = false
                                                    }
                                                } label: {
                                                    Text("この共有タグに追加")
                                                }
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                    .padding(.bottom, 20)
                }

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
    }
}

struct EntrySharedTagAssignmentSheet: View {
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var model: URLSaverAppModel
    let entryID: Int64
    let onDidChange: () -> Void

    @State private var assignedTags: [SharedTagSummary] = []
    @State private var newTagName = ""
    @State private var isWorking = false
    @State private var operationMessage: String?
    @State private var isOperationMessageError = false
    @State private var selectedTagForDetail: String?
    @State private var sharedTagDisplayMode: EntryListDisplayMode = .rich

    var body: some View {
        ScreenContainer {
            VStack(alignment: .leading, spacing: 16) {
                Capsule()
                    .fill(AppPalette.outlineSoft)
                    .frame(width: 72, height: 8)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 10)

                HStack {
                    Text("共有タグを編集")
                        .font(.system(size: 24, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                    Spacer()
                    Button("閉じる") { dismiss() }
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(AppPalette.primaryStrong)
                }

                if let operationMessage {
                    Text(operationMessage)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(isOperationMessageError ? AppPalette.danger : AppPalette.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("shared-tag-assignment-message")
                }

                AppPanel {
                    Text("新しい共有タグ")
                        .font(.system(size: 18, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)

                    TextField(
                        "",
                        text: $newTagName,
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

                    AppActionButton(tone: .primary, enabled: !newTagName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isWorking) {
                        guard !isWorking else { return }
                        isWorking = true
                        operationMessage = nil
                        Task {
                            if await model.createSharedTag(name: newTagName) {
                                newTagName = ""
                                operationMessage = "共有タグを作成しました"
                                isOperationMessageError = false
                                reloadAssignedTags()
                            } else {
                                operationMessage = "共有タグを作成できませんでした"
                                isOperationMessageError = true
                            }
                            isWorking = false
                        }
                    } label: {
                        Text("共有タグを作成")
                    }
                }

                AppPanel {
                    Text("現在の共有タグ")
                        .font(.system(size: 18, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)

                    if assignedTags.isEmpty {
                        Text("このURLにはまだ共有タグが付いていません")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                    } else {
                        ForEach(assignedTags) { tag in
                            SharedTagAssignedPill(
                                tag: tag,
                                isWorking: isWorking,
                                onOpen: {
                                    selectedTagForDetail = tag.remoteTagID
                                },
                                onRemove: {
                                    guard !isWorking else { return }
                                    isWorking = true
                                    operationMessage = nil
                                    Task {
                                        if await model.removeEntry(entryID, fromSharedTag: tag.remoteTagID) {
                                            operationMessage = "共有タグから外しました"
                                            isOperationMessageError = false
                                            onDidChange()
                                            reloadAssignedTags()
                                        } else {
                                            operationMessage = "共有タグから外せませんでした"
                                            isOperationMessageError = true
                                        }
                                        isWorking = false
                                    }
                                }
                            )
                        }
                    }
                }

                AppPanel {
                    Text("追加できる共有タグ")
                        .font(.system(size: 18, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)

                    let unassignedTags = model.sharedTags.filter { summary in
                        !assignedTags.contains(where: { $0.remoteTagID == summary.remoteTagID })
                    }

                    if unassignedTags.isEmpty {
                        Text("追加できる共有タグはありません")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                    } else {
                        LazyVGrid(
                            columns: [GridItem(.adaptive(minimum: 178), spacing: 10, alignment: .leading)],
                            alignment: .leading,
                            spacing: 10
                        ) {
                            ForEach(unassignedTags) { tag in
                                HStack(spacing: 8) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(tag.name)
                                            .font(.system(size: 20, weight: .heavy, design: .rounded))
                                            .foregroundStyle(AppPalette.textPrimary)
                                            .lineLimit(1)
                                            .minimumScaleFactor(0.8)
                                        Text(tag.currentUserRole?.displayName ?? "メンバー")
                                            .font(.system(size: 13, weight: .medium))
                                            .foregroundStyle(AppPalette.textSecondary)
                                    }
                                    Button {
                                        guard !isWorking else { return }
                                        isWorking = true
                                        operationMessage = nil
                                        Task {
                                            switch await model.addEntryToSharedTag(entryID, remoteTagID: tag.remoteTagID) {
                                            case .success:
                                                operationMessage = "共有タグに追加しました"
                                                isOperationMessageError = false
                                                onDidChange()
                                                reloadAssignedTags()
                                            case .authRequired:
                                                operationMessage = "先に共有タグクラウドへサインインしてください"
                                                isOperationMessageError = true
                                            case .failure(let message):
                                                operationMessage = message
                                                isOperationMessageError = true
                                            }
                                            isWorking = false
                                        }
                                    } label: {
                                        Text("追加")
                                            .font(.system(size: 16, weight: .heavy, design: .rounded))
                                            .foregroundStyle(AppPalette.background)
                                            .lineLimit(1)
                                            .fixedSize(horizontal: true, vertical: false)
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 9)
                                            .frame(minWidth: 66)
                                            .background(AppPalette.primary, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                                    }
                                    .buttonStyle(.plain)
                                    .disabled(isWorking)
                                }
                                .padding(.horizontal, 14)
                                .padding(.vertical, 10)
                                .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            }
                        }
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .task { reloadAssignedTags() }
        .sheet(
            isPresented: Binding(
                get: { selectedTagForDetail != nil },
                set: { if !$0 { selectedTagForDetail = nil } }
            )
        ) {
            if let selectedTagForDetail {
                SharedTagDetailSheet(
                    model: model,
                    remoteTagID: selectedTagForDetail,
                    displayMode: $sharedTagDisplayMode
                )
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(32)
            }
        }
    }

    private func reloadAssignedTags() {
        assignedTags = model.loadSharedTagsForEntry(entryID: entryID)
    }
}

private struct SharedTagAssignedPill: View {
    let tag: SharedTagSummary
    let isWorking: Bool
    let onOpen: () -> Void
    let onRemove: () -> Void

    private var canRemove: Bool {
        tag.currentUserRole == .owner || tag.currentUserRole == .editor
    }

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(tag.name)
                        .font(.system(size: 19, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                        .lineLimit(1)
                    Text(tag.currentUserRole?.displayName ?? "メンバー")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: 132, alignment: .leading)
            }
            .buttonStyle(.plain)

            if canRemove {
                Button(action: onRemove) {
                    Text("外す")
                        .font(.system(size: 17, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.background)
                        .lineLimit(1)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 9)
                        .background(AppPalette.primary, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(isWorking)
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .padding(.vertical, 10)
        .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(AppPalette.outlineSoft, lineWidth: 1.5)
        )
        .fixedSize(horizontal: true, vertical: false)
    }
}

private struct SharedTagEntryRow: View {
    let entry: URLRecord
    let canRemove: Bool
    let onOpen: () -> Void
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(preferredDisplayTitle(for: entry))
                        .font(.system(size: 18, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(entry.displayURL)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .buttonStyle(.plain)

            if canRemove {
                HStack {
                    Spacer()
                    AppActionButton {
                        onRemove()
                    } label: {
                        Text("このURLを共有タグから外す")
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}
