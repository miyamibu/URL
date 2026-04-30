import PhotosUI
import SwiftUI
import UIKit

struct SharedTagCloudSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @AppStorage("appThemeMode") private var themeModeRaw = AppThemeMode.system.rawValue

    @ObservedObject var model: URLSaverAppModel
    @State private var email = ""
    @State private var password = ""
    @State private var displayNameDraft = ""
    @State private var selectedAvatarItem: PhotosPickerItem?
    @State private var isWorking = false
    @State private var isShowingDeleteConfirmation = false
    @State private var isShowingContactSheet = false

    var body: some View {
        ScreenContainer {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Text("プロフィール")
                            .font(.system(size: 28, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textPrimary)
                        Spacer()
                        Button("閉じる") { dismiss() }
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(AppPalette.primaryStrong)
                    }

                    if let profileStatusMessage = model.profileStatusMessage {
                        AppPanel(strong: true) {
                            HStack(alignment: .top, spacing: 12) {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(profileStatusMessage)
                                        .font(.system(size: 18, weight: .heavy, design: .rounded))
                                        .foregroundStyle(Color.white.opacity(0.96))
                                }
                                Spacer()
                                Button {
                                    model.clearProfileStatusMessage()
                                } label: {
                                    Image(systemName: "xmark")
                                        .font(.system(size: 11, weight: .bold))
                                        .foregroundStyle(Color.white.opacity(0.9))
                                        .frame(width: 28, height: 28)
                                        .background(Color.white.opacity(0.12), in: Circle())
                                }
                            }
                        }
                    }

                    profileSection
                    usageSummarySection

                    if !model.sharedTagCloudState.isConfigured {
                        AppPanel {
                            Text("このビルドでは共有タグのクラウド同期が未設定です")
                                .font(.system(size: 20, weight: .heavy, design: .rounded))
                                .foregroundStyle(AppPalette.textPrimary)
                            Text("Supabase の URL と anon key を設定すると、iPhone でも招待参加と同期が使えます。")
                                .font(.system(size: 16, weight: .medium))
                                .foregroundStyle(AppPalette.textSecondary)
                        }
                    } else if model.sharedTagCloudState.isSignedIn {
                        signedInSection
                    } else {
                        signedOutSection
                    }

                    if let pendingInvite = model.pendingInviteRecord {
                        AppPanel {
                            Text("保留中の招待")
                                .font(.system(size: 20, weight: .heavy, design: .rounded))
                                .foregroundStyle(AppPalette.textPrimary)
                            Text("招待トークン: \(pendingInvite.inviteToken)")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(AppPalette.textSecondary)
                                .textSelection(.enabled)
                            Text("受信時刻: \(DateFormatters.detailTimestamp.string(from: pendingInvite.savedAt))")
                                .font(.system(size: 15, weight: .medium))
                                .foregroundStyle(AppPalette.textSecondary)

                            HStack(spacing: 10) {
                                AppActionButton(
                                    tone: .primary,
                                    enabled: model.sharedTagCloudState.isSignedIn && !isWorking
                                ) {
                                    guard !isWorking else { return }
                                    isWorking = true
                                    Task {
                                        await model.acceptPendingInvite()
                                        isWorking = false
                                    }
                                } label: {
                                    if isWorking {
                                        ProgressView().tint(AppPalette.textPrimary)
                                    } else {
                                        Text("参加する")
                                    }
                                }

                                AppActionButton(enabled: !isWorking) {
                                    Task { await model.clearPendingInvite() }
                                } label: {
                                    Text("取り消す")
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 22)
            }
        }
        .task {
            displayNameDraft = model.profile.trimmedDisplayName
            await model.refreshSharedTagCloudState()
        }
        .onChange(of: model.profile.displayName) { _, newValue in
            displayNameDraft = newValue
        }
        .onChange(of: model.sharedTagCloudState.isSignedIn) { _, isSignedIn in
            if isSignedIn {
                email = ""
                password = ""
            }
        }
        .onChange(of: selectedAvatarItem) { _, newItem in
            guard let newItem else { return }
            Task {
                if let imageData = try? await newItem.loadTransferable(type: Data.self) {
                    let normalizedData = normalizeAvatarImageData(imageData)
                    await MainActor.run {
                        model.saveProfileAvatar(imageData: normalizedData)
                        selectedAvatarItem = nil
                    }
                } else {
                    await MainActor.run {
                        model.clearProfileStatusMessage()
                        selectedAvatarItem = nil
                    }
                }
            }
        }
        .alert("共有タグアカウントを削除しますか？", isPresented: $isShowingDeleteConfirmation) {
            Button("キャンセル", role: .cancel) {}
            Button("削除", role: .destructive) {
                guard !isWorking else { return }
                isWorking = true
                Task {
                    await model.deleteSharedTagCloudAccount()
                    isWorking = false
                }
            }
        } message: {
            Text("他のメンバーがいる共有タグのオーナーは、権限移譲が終わるまで削除できません。")
        }
        .sheet(isPresented: $isShowingContactSheet) {
            ContactSupportSheet(
                initialEmail: model.sharedTagCloudState.signedInEmail ?? email,
                initialName: model.profile.trimmedDisplayName,
                onOpenURL: { openURL($0) }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(32)
        }
    }

    private var profileSection: some View {
        AppPanel {
            HStack(alignment: .center, spacing: 16) {
                ProfileAvatarView(imageData: model.profile.avatarImageData)

                VStack(alignment: .leading, spacing: 8) {
                    Text(model.profileDisplayName)
                        .font(.system(size: 21, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                    Text(model.sharedTagCloudState.signedInEmail ?? "このiPhoneで使うプロフィール")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                    HStack(spacing: 8) {
                        PhotosPicker(selection: $selectedAvatarItem, matching: .images) {
                            Text(model.profile.avatarImageData == nil ? "写真を追加" : "写真を変更")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundStyle(AppPalette.primaryStrong)
                        }
                        if model.profile.avatarImageData != nil {
                            Button("削除") {
                                model.saveProfileAvatar(imageData: nil)
                            }
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(AppPalette.textSecondary)
                        }
                    }
                }
                Spacer(minLength: 0)
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("表示名")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(AppPalette.textSecondary)
                TextField("", text: $displayNameDraft, prompt: Text("表示名を入力").foregroundStyle(AppPalette.textMuted))
                    .textInputAutocapitalization(.words)
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
            }

            ThemeModePicker(
                selectedMode: AppThemeMode(rawValue: themeModeRaw) ?? .system,
                onSelect: { themeModeRaw = $0.rawValue }
            )

            AppActionButton(
                tone: .primary,
                enabled: displayNameDraft.trimmingCharacters(in: .whitespacesAndNewlines) != model.profile.trimmedDisplayName
            ) {
                model.saveProfile(displayName: displayNameDraft)
            } label: {
                Text("プロフィールを保存")
            }
        }
    }

    private var signedOutSection: some View {
        AppPanel {
            Text("共有タグに参加するにはサインインが必要です")
                .font(.system(size: 20, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            Text("未サインインのため共有タグは同期されません。アプリ削除後や機種変更後でも、同じメールアドレスでサインインし直すと共有タグを復元できます。")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)

            Text("通常のURL保存はサインインしなくても使えます。共有タグの同期、招待参加、複数端末での共有だけサインインが必要です。")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)

            VStack(alignment: .leading, spacing: 10) {
                Text("メールアドレス")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(AppPalette.textSecondary)
                TextField("", text: $email, prompt: Text("name@example.com").foregroundStyle(AppPalette.textMuted))
                    .keyboardType(.emailAddress)
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
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("パスワード")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(AppPalette.textSecondary)
                SecureField("", text: $password, prompt: Text("8文字以上").foregroundStyle(AppPalette.textMuted))
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
            }

            HStack(spacing: 10) {
                AppActionButton(
                    tone: .primary,
                    enabled: canSubmitAuth && !isWorking
                ) {
                    guard !isWorking else { return }
                    isWorking = true
                    Task {
                        await model.signInToSharedTagCloud(email: email, password: password)
                        isWorking = false
                    }
                } label: {
                    if isWorking {
                        ProgressView().tint(AppPalette.textPrimary)
                    } else {
                        Text("サインイン")
                    }
                }

                AppActionButton(
                    enabled: canSubmitAuth && !isWorking
                ) {
                    guard !isWorking else { return }
                    isWorking = true
                    Task {
                        await model.signUpForSharedTagCloud(email: email, password: password)
                        isWorking = false
                    }
                } label: {
                    Text("新規登録")
                }
            }

            Text("新規登録が完了すると、この画面のままサインイン済み表示へ切り替わります。")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)
        }
    }

    private var usageSummarySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("使用状況")
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            HStack(alignment: .top, spacing: 10) {
                UsageMetricTile(
                    title: "通常タグで保存URL",
                    valueText: "\(personalSavedURLCount) / 200"
                )
                UsageMetricTile(
                    title: "共有タグURL",
                    valueText: sharedTagUsageText
                )
            }
        }
    }

    private var signedInSection: some View {
        VStack(spacing: 16) {
            HStack(spacing: 10) {
                AppActionButton(enabled: !isWorking) {
                    Task {
                        await model.signOutFromSharedTagCloud()
                    }
                } label: {
                    Text("サインアウト")
                }

                AppActionButton(tone: .danger, enabled: !isWorking) {
                    isShowingDeleteConfirmation = true
                } label: {
                    Text("アカウント削除")
                }
            }

            AppActionButton(tone: .primary, enabled: !isWorking) {
                isShowingContactSheet = true
            } label: {
                Text("問い合わせ")
            }
        }
    }

    private var canSubmitAuth: Bool {
        !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            password.trimmingCharacters(in: .whitespacesAndNewlines).count >= 8
    }

    private var personalSavedURLCount: Int {
        model.activeEntries.count + model.archivedEntries.count
    }

    private var sharedTagUsageText: String {
        guard !model.sharedTags.isEmpty else {
            return "共有タグなし"
        }
        return model.sharedTags
            .map { "\($0.name) \($0.activeURLCount) / 20" }
            .joined(separator: "\n")
    }

    private func normalizeAvatarImageData(_ data: Data) -> Data {
        guard let image = UIImage(data: data) else { return data }
        return image.jpegData(compressionQuality: 0.82) ?? data
    }
}

private struct UsageMetricTile: View {
    let title: String
    let valueText: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.system(size: 13, weight: .bold, design: .rounded))
                .foregroundStyle(AppPalette.textSecondary)
                .lineLimit(2)
                .minimumScaleFactor(0.82)
            Text(valueText)
                .font(.system(size: 16, weight: .medium, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(3)
                .minimumScaleFactor(0.82)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

private struct ContactSupportSheet: View {
    @Environment(\.dismiss) private var dismiss

    let initialEmail: String
    let initialName: String
    let onOpenURL: (URL) -> Void

    @State private var email: String
    @State private var name: String
    @State private var message = ""
    @State private var didAttemptSubmit = false

    init(
        initialEmail: String,
        initialName: String,
        onOpenURL: @escaping (URL) -> Void
    ) {
        self.initialEmail = initialEmail
        self.initialName = initialName
        self.onOpenURL = onOpenURL
        _email = State(initialValue: initialEmail)
        _name = State(initialValue: initialName)
    }

    var body: some View {
        ScreenContainer {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 16) {
                    Capsule()
                        .fill(AppPalette.outlineSoft)
                        .frame(width: 72, height: 8)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 10)

                    HStack {
                        Text("問い合わせ")
                            .font(.system(size: 28, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textPrimary)
                        Spacer()
                        Button("閉じる") { dismiss() }
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(AppPalette.primaryStrong)
                    }

                    AppPanel {
                        contactField(
                            title: "メールアドレス",
                            text: $email,
                            prompt: "name@example.com",
                            keyboardType: .emailAddress
                        )

                        contactField(
                            title: "氏名",
                            text: $name,
                            prompt: "お名前"
                        )

                        VStack(alignment: .leading, spacing: 10) {
                            Text("問い合わせ内容")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(AppPalette.textSecondary)
                            TextEditor(text: $message)
                                .font(.system(size: 17, weight: .medium))
                                .foregroundStyle(AppPalette.textPrimary)
                                .tint(AppPalette.primaryStrong)
                                .frame(minHeight: 180)
                                .padding(14)
                                .scrollContentBackground(.hidden)
                                .background(AppPalette.background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                                        .stroke(AppPalette.outlineSoft, lineWidth: 1.5)
                                )
                        }

                        if didAttemptSubmit && !canSubmit {
                            Text("メールアドレス、氏名、問い合わせ内容を入力してください。")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(AppPalette.danger)
                        }

                        AppActionButton(tone: .primary) {
                            guard canSubmit else {
                                didAttemptSubmit = true
                                return
                            }
                            if let url = mailURL {
                                onOpenURL(url)
                            }
                        } label: {
                            Text("メールアプリで送信")
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 22)
            }
        }
    }

    private var canSubmit: Bool {
        !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var mailURL: URL? {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = ""
        components.queryItems = [
            URLQueryItem(name: "subject", value: "URL Saver 問い合わせ"),
            URLQueryItem(name: "body", value: """
            メールアドレス:
            \(email.trimmingCharacters(in: .whitespacesAndNewlines))

            氏名:
            \(name.trimmingCharacters(in: .whitespacesAndNewlines))

            問い合わせ内容:
            \(message.trimmingCharacters(in: .whitespacesAndNewlines))
            """),
        ]
        return components.url
    }

    @ViewBuilder
    private func contactField(
        title: String,
        text: Binding<String>,
        prompt: String,
        keyboardType: UIKeyboardType = .default
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(AppPalette.textSecondary)
            TextField("", text: text, prompt: Text(prompt).foregroundStyle(AppPalette.textMuted))
                .keyboardType(keyboardType)
                .textInputAutocapitalization(keyboardType == .emailAddress ? .never : .words)
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
        }
    }
}

private struct ThemeModePicker: View {
    let selectedMode: AppThemeMode
    let onSelect: (AppThemeMode) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("画面モード")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(AppPalette.textSecondary)
            HStack(spacing: 8) {
                ForEach(AppThemeMode.allCases) { mode in
                    Button {
                        onSelect(mode)
                    } label: {
                        Text(mode.label)
                            .font(.system(size: 15, weight: .bold, design: .rounded))
                            .foregroundStyle(selectedMode == mode ? AppPalette.textPrimary : Color.white.opacity(0.9))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(
                                selectedMode == mode ? AppPalette.primary : AppPalette.panelStrong,
                                in: RoundedRectangle(cornerRadius: 18, style: .continuous)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .stroke(selectedMode == mode ? AppPalette.primary : AppPalette.outline, lineWidth: 1.2)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

private struct ProfileAvatarView: View {
    let imageData: Data?

    var body: some View {
        Group {
            if let imageData,
               let uiImage = UIImage(data: imageData) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "person.crop.circle.fill")
                    .resizable()
                    .scaledToFit()
                    .padding(22)
                    .foregroundStyle(AppPalette.primaryStrong)
                    .background(AppPalette.surfaceSoft)
            }
        }
        .frame(width: 88, height: 88)
        .clipShape(Circle())
        .overlay(
            Circle()
                .stroke(AppPalette.outlineSoft, lineWidth: 1.5)
        )
        .background(AppPalette.background, in: Circle())
    }
}

struct PendingInviteBanner: View {
    let pendingInviteRecord: PendingInviteRecord
    let onOpenCloud: () -> Void

    var body: some View {
        AppPanel {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("共有招待を受け取りました")
                        .font(.system(size: 18, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                    Text("プロフィール画面から参加できます")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                    Text(DateFormatters.detailTimestamp.string(from: pendingInviteRecord.savedAt))
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(AppPalette.textMuted)
                }
                Spacer()
                Button("開く", action: onOpenCloud)
                    .font(.system(size: 16, weight: .heavy))
                    .foregroundStyle(AppPalette.primaryStrong)
            }
        }
    }
}
