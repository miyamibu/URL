import SwiftUI

struct SharedTagDetailSheet: View {
    @Environment(\.dismiss) private var dismiss

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
    @State private var isShowingInfo = false
    @State private var isWorking = false
    @State private var isSyncing = false
    @State private var shareItems: [Any] = []
    @State private var inviteNotice: String?
    @State private var syncNotice: String?
    @State private var pendingOwnershipTransferMember: SharedTagMemberSummary?

    var body: some View {
        GeometryReader { proxy in
            let contentWidth = min(proxy.size.width, 680)
            let sidePadding = max((proxy.size.width - contentWidth) / 2, 0) + 16

            NavigationStack(path: $navigationPath) {
                ScreenContainer {
                    VStack(spacing: 0) {
                        ScreenHeader(
                            title: tag?.name ?? "共有タグ",
                            leadingButton: ScreenHeaderButton(
                                icon: "chevron.left",
                                accessibilityLabel: "戻る",
                                action: { dismiss() }
                            ),
                            trailingButtons: headerButtons(for: tag)
                        )

                        if let tag {
                            if entries.isEmpty {
                                ScrollView {
                                    VStack(alignment: .leading, spacing: 16) {
                                        syncedTagInfo(for: tag)
                                        SharedTagMembersStrip(
                                            members: members,
                                            canTransferOwnership: tag.currentUserRole == .owner,
                                            onTransferOwnership: { pendingOwnershipTransferMember = $0 }
                                        )

                                        SharedTagEmptyState(isSyncing: isSyncing, syncNotice: syncNotice)
                                            .frame(maxWidth: .infinity, minHeight: 260)
                                            .padding(.vertical, 24)

                                        sharedTagActions(for: tag)
                                    }
                                    .frame(width: contentWidth - 32, alignment: .leading)
                                    .padding(.horizontal, sidePadding)
                                    .padding(.top, 18)
                                    .padding(.bottom, 32)
                                }
                            } else {
                                ScrollView {
                                    VStack(alignment: .leading, spacing: 16) {
                                        syncedTagInfo(for: tag)
                                        SharedTagMembersStrip(
                                            members: members,
                                            canTransferOwnership: tag.currentUserRole == .owner,
                                            onTransferOwnership: { pendingOwnershipTransferMember = $0 }
                                        )

                                        Text("\(entries.count)件")
                                            .font(.system(size: 19, weight: .heavy, design: .rounded))
                                            .foregroundStyle(AppPalette.textSecondary)
                                            .padding(.top, 8)

                                        sharedTagActions(for: tag)

                                        ForEach(entries) { entry in
                                            SwipeableSharedTagEntryCard(
                                                entry: entry,
                                                displayMode: displayMode,
                                                cardWidth: contentWidth - 32,
                                                canRemove: canEdit(tag),
                                                onTap: {
                                                    navigationPath.append(entry.id)
                                                },
                                                onRemove: {
                                                    removeEntryFromSharedTag(entry.id)
                                                }
                                            )
                                        }
                                    }
                                    .frame(width: contentWidth - 32, alignment: .leading)
                                    .padding(.horizontal, sidePadding)
                                    .padding(.top, 18)
                                    .padding(.bottom, 32)
                                }
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
        .task { await syncAndReload(showSuccessNotice: false) }
        .sheet(isPresented: $isShowingAddEntrySheet) {
            SharedTagEntryPickerSheet(
                model: model,
                remoteTagID: remoteTagID,
                assignedEntryIDs: Set(entries.map(\.id)),
                onDidAssign: {
                    reload()
                }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
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
        .alert("共有タグについて", isPresented: $isShowingInfo) {
            Button("閉じる", role: .cancel) {}
        } message: {
            Text(sharedTagInfoMessage(for: tag))
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

    private func removeEntryFromSharedTag(_ entryID: Int64) {
        guard !isWorking else { return }
        isWorking = true
        Task {
            if await model.removeEntry(entryID, fromSharedTag: remoteTagID) {
                reload()
            }
            isWorking = false
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

    private func sharedTagInfoMessage(for tag: SharedTagSummary?) -> String {
        let roleText: String
        switch tag?.currentUserRole {
        case .owner:
            roleText = "あなたはオーナーです。招待リンクの共有、参加者の削除、タグ削除ができます。"
        case .editor:
            roleText = "あなたは編集者です。URL の追加と削除、共有タグから抜ける操作ができます。"
        case .viewer:
            roleText = "あなたは閲覧者です。URL の閲覧と共有タグから抜ける操作ができます。"
        case nil:
            roleText = "同期が完了すると、この共有タグでの権限が表示されます。"
        }
        return "この共有タグでは URL 一覧だけを同期します。\n\n\(roleText)"
    }

    private func headerButtons(for tag: SharedTagSummary?) -> [ScreenHeaderButton] {
        var buttons: [ScreenHeaderButton] = []

        if tag != nil {
            buttons.append(
                ScreenHeaderButton(
                    icon: "exclamationmark.circle",
                    accessibilityLabel: "共有タグの説明",
                    action: { isShowingInfo = true }
                )
            )
        }

        buttons.append(
            ScreenHeaderButton(
                icon: displayMode == .rich ? "list.bullet.rectangle" : "rectangle.grid.1x2",
                accessibilityLabel: displayMode == .rich ? "画像なし表示へ切り替える" : "画像つき表示へ切り替える",
                action: { displayMode = displayMode == .rich ? .compact : .rich }
            )
        )

        if tag != nil {
            buttons.append(
                ScreenHeaderButton(
                    icon: "arrow.triangle.2.circlepath",
                    accessibilityLabel: "共有タグを更新",
                    action: {
                        Task {
                            await syncAndReload()
                        }
                    }
                )
            )
        }

        if let tag, canEdit(tag) {
            buttons.append(
                ScreenHeaderButton(
                    icon: "plus",
                    accessibilityLabel: "保存済みURLを追加",
                    action: { isShowingAddEntrySheet = true }
                )
            )
        }

        if let tag, canInvite(tag) {
            buttons.append(
                ScreenHeaderButton(
                    icon: "square.and.arrow.up",
                    accessibilityLabel: "共有招待リンクを共有",
                    action: { shareInviteLink() }
                )
            )
        }

        return buttons
    }

    @ViewBuilder
    private func syncedTagInfo(for _: SharedTagSummary) -> some View {
        if isSyncing || !(inviteNotice?.isEmpty ?? true) || !(syncNotice?.isEmpty ?? true) {
            VStack(alignment: .leading, spacing: 8) {
                if let inviteNotice, !inviteNotice.isEmpty {
                    Text(inviteNotice)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if isSyncing {
                    HStack(spacing: 8) {
                        ProgressView()
                            .tint(AppPalette.textSecondary)
                        Text("同期中")
                            .font(.system(size: 15, weight: .bold, design: .rounded))
                            .foregroundStyle(AppPalette.textSecondary)
                    }
                } else if let syncNotice, !syncNotice.isEmpty {
                    Text(syncNotice)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    @ViewBuilder
    private func sharedTagActions(for tag: SharedTagSummary) -> some View {
        if canDelete(tag) || canLeave(tag) {
            VStack(alignment: .leading, spacing: 10) {
                if canLeave(tag) {
                    Button {
                        isShowingLeaveConfirmation = true
                    } label: {
                        Text("この共有タグから抜ける")
                            .font(.system(size: 20, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.primaryStrong)
                    }
                    .buttonStyle(.plain)
                }

                if canDelete(tag) {
                    Button {
                        isShowingDeleteConfirmation = true
                    } label: {
                        Text("この共有タグを削除")
                            .font(.system(size: 20, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.primaryStrong)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 8)
        }
    }

    private func shareInviteLink() {
        guard let tag, canInvite(tag), !isWorking else { return }
        isWorking = true
        inviteNotice = nil
        Task {
            let result = await model.createInviteForSharedTag(remoteTagID: remoteTagID)
            switch result {
            case .success(let inviteURL, _):
                shareItems = ["UrlSaver の共有タグに参加する\n\(inviteURL)"]
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

private struct SharedTagEmptyState: View {
    let isSyncing: Bool
    let syncNotice: String?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "link.slash")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(AppPalette.textSecondary)

            Text("この共有タグにはまだURLがありません")
                .font(.system(size: 21, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .multilineTextAlignment(.center)

            Text("詳細画面からURLに共有タグを追加すると、ここにまとまって表示されます")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            if isSyncing {
                HStack(spacing: 8) {
                    ProgressView()
                        .tint(AppPalette.textSecondary)
                    Text("同期中")
                        .font(.system(size: 15, weight: .bold, design: .rounded))
                        .foregroundStyle(AppPalette.textSecondary)
                }
                .padding(.top, 6)
            } else if let syncNotice, !syncNotice.isEmpty {
                Text(syncNotice)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
            }
        }
        .frame(maxWidth: 320)
    }
}

private struct SharedTagMembersStrip: View {
    let members: [SharedTagMemberSummary]
    let canTransferOwnership: Bool
    let onTransferOwnership: (SharedTagMemberSummary) -> Void

    @State private var selectedMember: SharedTagMemberSummary?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("参加者 \(members.count)名")
                .font(.system(size: 17, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            if members.isEmpty {
                Text("同期が完了すると参加者が表示されます。")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 14) {
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
        VStack(spacing: 5) {
            Image(systemName: member.isCurrentUser ? "person.crop.circle.fill.badge.checkmark" : "person.crop.circle.fill")
                .font(.system(size: 42, weight: .semibold))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(AppPalette.textSecondary)
                .frame(width: 54, height: 54)

            Text(memberLabel(member))
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(width: 76)

            Text(member.role.displayName)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(AppPalette.textSecondary)
                .lineLimit(1)
                .frame(width: 76)
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
                    Text("保存済みURLを追加")
                        .font(.system(size: 24, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                    Spacer()
                    Button("閉じる") { dismiss() }
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(AppPalette.primaryStrong)
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
                                AppPanel {
                                    HStack(alignment: .top, spacing: 12) {
                                        VStack(alignment: .leading, spacing: 6) {
                                            Text(preferredDisplayTitle(for: entry))
                                                .font(.system(size: 18, weight: .heavy, design: .rounded))
                                                .foregroundStyle(AppPalette.textPrimary)
                                                .lineLimit(2)
                                            Text(entry.displayURL)
                                                .font(.system(size: 14, weight: .medium))
                                                .foregroundStyle(AppPalette.textSecondary)
                                                .lineLimit(1)
                                        }
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
                                            Text("追加")
                                        }
                                    }
                                }
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
                        Task {
                            if await model.createSharedTag(name: newTagName) {
                                newTagName = ""
                                reloadAssignedTags()
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
                            HStack(spacing: 10) {
                                Button {
                                    selectedTagForDetail = tag.remoteTagID
                                } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(tag.name)
                                            .font(.system(size: 17, weight: .heavy, design: .rounded))
                                            .foregroundStyle(AppPalette.textPrimary)
                                        Text(tag.currentUserRole?.displayName ?? "メンバー")
                                            .font(.system(size: 13, weight: .medium))
                                            .foregroundStyle(AppPalette.textSecondary)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                }
                                .buttonStyle(.plain)

                                if tag.currentUserRole == .owner || tag.currentUserRole == .editor {
                                    AppActionButton(enabled: !isWorking) {
                                        guard !isWorking else { return }
                                        isWorking = true
                                        Task {
                                            if await model.removeEntry(entryID, fromSharedTag: tag.remoteTagID) {
                                                onDidChange()
                                                reloadAssignedTags()
                                            }
                                            isWorking = false
                                    }
                                } label: {
                                    Text("このURLから外す")
                                }
                            }
                        }
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
                                        Task {
                                            if await model.addEntry(entryID, toSharedTag: tag.remoteTagID) {
                                                onDidChange()
                                                reloadAssignedTags()
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
