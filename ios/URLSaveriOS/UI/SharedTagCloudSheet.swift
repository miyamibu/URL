import AuthenticationServices
import CryptoKit
import PhotosUI
import SafariServices
import SwiftUI
import UIKit

private enum ChatGptPersonalLinkSyncAction: Equatable {
    case change(enabled: Bool)
    case sync
}

private enum GoogleOAuthPresentationPurpose {
    case signIn
    case identityLink

    var isIdentityLink: Bool {
        if case .identityLink = self { return true }
        return false
    }
}

struct SharedTagCloudSheet: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage(HomeBackgroundPreferenceStore.key) private var homeBackgroundRaw = HomeBackgroundStyle.defaultStyle.rawValue

    @ObservedObject var model: URLSaverAppModel
    @State private var email = ""
    @State private var password = ""
    @State private var isPasswordVisible = false
    @State private var isShowingEmailPassword = false
    @State private var displayNameDraft = ""
    @State private var isEditingDisplayName = false
    @State private var selectedAvatarItem: PhotosPickerItem?
    @State private var avatarDraftData: Data?
    @State private var isAvatarDraftActive = false
    @State private var isWorking = false
    @State private var isShowingDeleteConfirmation = false
    @State private var isShowingContactSheet = false
    @State private var isShowingColorPicker = false
    @State private var isShowingPlanDetails = false
    @State private var isShowingUsageDetails = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var promoCode = ""
    @State private var promoMessage: String?
    @State private var isRedeemingPromoCode = false
    @State private var googleOAuthURL: URL?
    @State private var googleOAuthPurpose: GoogleOAuthPresentationPurpose = .signIn
    @State private var pendingAppleNonce: String?
    @State private var pendingAppleState: String?
    @State private var pendingAppleExpectedAuthUserID: String?
    @State private var pendingChatGptPersonalLinkSyncAction: ChatGptPersonalLinkSyncAction?
    @State private var isShowingAiTransparency = false
    @StateObject private var appleSignInCoordinator = AppleSignInCoordinator()

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
                    if let pendingInvite = model.pendingInviteRecord {
                        AppPanel {
                            Text("保留中の招待")
                                .font(.system(size: 20, weight: .heavy, design: .rounded))
                                .foregroundStyle(AppPalette.textPrimary)
                            Text("参加リンクを受信済みです。サインイン後に参加できます。")
                                .font(.system(size: 15, weight: .medium))
                                .foregroundStyle(AppPalette.textSecondary)
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
                    if let cleanupState = model.sharedTagAccountLocalCleanupState {
                        accountLocalCleanupSection(cleanupState)
                    }
                    usageSummarySection
                    displaySettingsSection
                    accountSharingSection
                    supportSection
                    #if DEBUG
                    if AiTransparencyFeature.isEnabled {
                        aiTransparencyEntry
                    }
                    #endif
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 22)
            }
        }
        .task {
            displayNameDraft = model.profile.trimmedDisplayName
            applyPendingPromoCodeIfNeeded()
            if model.pendingPromoCode != nil { isShowingPlanDetails = true }
            await model.refreshSharedTagCloudState()
            await model.refreshSharedTagNotificationAuthorizationStatus()
        }
        .onChange(of: model.profile.displayName) { _, newValue in
            displayNameDraft = newValue
        }
        .onChange(of: model.pendingPromoCode) { _, value in
            applyPendingPromoCodeIfNeeded()
            if value != nil { isShowingPlanDetails = true }
        }
        .onChange(of: model.sharedTagCloudState.isSignedIn) { _, isSignedIn in
            if isSignedIn {
                email = ""
                password = ""
                isShowingEmailPassword = false
            }
        }
        .onChange(of: selectedAvatarItem) { _, newItem in
            guard let newItem else { return }
            Task {
                if let imageData = try? await newItem.loadTransferable(type: Data.self) {
                    let normalizedData = normalizeAvatarImageData(imageData)
                    await MainActor.run {
                        avatarDraftData = normalizedData
                        isAvatarDraftActive = true
                        model.showProfileStatusMessage("プロフィール写真を選択しました")
                        selectedAvatarItem = nil
                    }
                } else {
                    await MainActor.run {
                        model.showProfileStatusMessage("プロフィール写真を読み込めませんでした")
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
        .sheet(isPresented: $isShowingColorPicker) {
            NavigationStack {
                ScreenContainer {
                    ScrollView {
                        HomeBackgroundPicker(
                            selectedStyle: HomeBackgroundPreferenceStore.resolve(rawValue: homeBackgroundRaw),
                            onSelect: { homeBackgroundRaw = $0.rawValue }
                        )
                        .padding(24)
                    }
                }
                .navigationTitle("カラー")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button("閉じる") { isShowingColorPicker = false } }
                }
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $isShowingContactSheet) {
            ContactSupportSheet(
                initialEmail: model.sharedTagCloudState.signedInEmail ?? email,
                initialName: model.profile.trimmedDisplayName,
                onSend: { email, name, message, idempotencyKey in
                    await model.sendContactSupport(
                        email: email,
                        name: name,
                        message: message,
                        idempotencyKey: idempotencyKey
                    )
                }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(32)
        }
        .sheet(isPresented: $isShowingAiTransparency) {
            #if DEBUG
            AiTransparencySheet(model: model)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(32)
            #endif
        }
        .sheet(
            isPresented: Binding(
                get: { googleOAuthURL != nil },
                set: { isPresented in
                    if !isPresented {
                        dismissGoogleOAuthSheet()
                    }
                }
            )
        ) {
            if let googleOAuthURL {
                SafariAuthView(url: googleOAuthURL)
                    .ignoresSafeArea()
            }
        }
    }

    private var profileSection: some View {
        let currentAvatarImageData = activeAvatarImageData
        let hasAvatarImage = currentAvatarImageData != nil

        return ProfilePanel {
            HStack(alignment: .center, spacing: 16) {
                VStack(spacing: 8) {
                    ProfileAvatarView(imageData: currentAvatarImageData)
                    VStack(spacing: 0) {
                        PhotosPicker(selection: $selectedAvatarItem, matching: .images) {
                            Text(hasAvatarImage ? "写真を変更" : "写真を追加")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundStyle(AppPalette.primaryStrong)
                                .multilineTextAlignment(.center)
                                .frame(minHeight: 44)
                        }
                        if hasAvatarImage {
                            Button("削除") {
                                avatarDraftData = nil
                                isAvatarDraftActive = true
                                model.showProfileStatusMessage("プロフィール写真を削除対象にしました")
                            }
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(AppPalette.textSecondary)
                            .frame(minHeight: 44)
                        }
                    }
                }
                .frame(width: 108)

                VStack(alignment: .leading, spacing: 8) {
                    VStack(alignment: .leading, spacing: 8) {
                        if isEditingDisplayName {
                            TextField("", text: $displayNameDraft, prompt: Text("表示名を入力").foregroundStyle(AppPalette.textMuted))
                                .textInputAutocapitalization(.words)
                                .autocorrectionDisabled()
                                .font(.system(size: 21, weight: .heavy, design: .rounded))
                                .foregroundStyle(AppPalette.textPrimary)
                                .tint(AppPalette.primaryStrong)
                                .textFieldStyle(.plain)
                            HStack(spacing: 8) {
                                Button {
                                    let saved = model.saveProfile(
                                        displayName: displayNameDraft,
                                        avatarImageData: activeAvatarImageData,
                                        updatesAvatar: isAvatarDraftActive
                                    )
                                    if saved {
                                        avatarDraftData = nil
                                        isAvatarDraftActive = false
                                        isEditingDisplayName = false
                                    }
                                } label: {
                                    Image(systemName: "checkmark")
                                        .font(.system(size: 17, weight: .bold))
                                }
                                .accessibilityLabel("表示名を保存")
                                .frame(minWidth: 44, minHeight: 44)
                                .foregroundStyle(AppPalette.primaryStrong)
                                .padding(.top, 5)
                                Button {
                                    displayNameDraft = model.profile.trimmedDisplayName
                                    isEditingDisplayName = false
                                } label: {
                                    Image(systemName: "xmark")
                                        .font(.system(size: 17, weight: .bold))
                                }
                                .accessibilityLabel("表示名の編集をキャンセル")
                                .frame(minWidth: 44, minHeight: 44)
                                .foregroundStyle(AppPalette.primaryStrong)
                                .padding(.top, 5)
                            }
                        } else {
                            HStack(alignment: .top, spacing: 8) {
                                Text(model.profileDisplayName)
                                    .font(.system(size: 21, weight: .heavy, design: .rounded))
                                    .foregroundStyle(AppPalette.textPrimary)
                                    .lineLimit(2)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .onTapGesture {
                                        displayNameDraft = model.profile.trimmedDisplayName
                                        isEditingDisplayName = true
                                    }
                                Button {
                                    displayNameDraft = model.profile.trimmedDisplayName
                                    isEditingDisplayName = true
                                } label: {
                                    Image(systemName: "pencil")
                                        .font(.system(size: 17, weight: .bold))
                                }
                                .accessibilityLabel("表示名を編集")
                                .frame(minWidth: 44, minHeight: 44)
                                .foregroundStyle(AppPalette.primaryStrong)
                                .padding(.top, 5)
                            }
                        }
                    }
                    if let signedInEmail = model.sharedTagCloudState.signedInEmail, !signedInEmail.isEmpty {
                        Text(signedInEmail)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                    }

                }
                Spacer(minLength: 0)
            }

            if hasProfileChanges && !isEditingDisplayName {
                AppActionButton(
                    tone: .primary,
                    enabled: hasProfileChanges && !isEditingDisplayName
                ) {
                    let saved = model.saveProfile(
                        displayName: displayNameDraft,
                        avatarImageData: activeAvatarImageData,
                        updatesAvatar: isAvatarDraftActive
                    )
                    if saved {
                        avatarDraftData = nil
                        isAvatarDraftActive = false
                    }
                } label: {
                    Text("プロフィールを保存")
                }
                Button("変更をキャンセル") {
                    displayNameDraft = model.profile.trimmedDisplayName
                    avatarDraftData = nil
                    isAvatarDraftActive = false
                }
                .frame(minHeight: 44)
            }
        }
    }

    private var displaySettingsSection: some View {
        ProfilePanel {
            Text("表示設定").font(.system(.headline, design: .rounded).weight(.heavy))
            Button { isShowingColorPicker = true } label: {
                profileSettingRow(title: "カラー", value: HomeBackgroundPreferenceStore.resolve(rawValue: homeBackgroundRaw).label, icon: "paintpalette")
            }
            .buttonStyle(.plain)
        }
    }

    private var accountSharingSection: some View {
        ProfilePanel {
            Text("アカウント・共有").font(.system(.headline, design: .rounded).weight(.heavy))
            if !model.sharedTagCloudState.isConfigured {
                profileSettingRow(title: "共有アカウント", value: "現在利用できません", icon: "person.crop.circle", showsChevron: false)
            }
            if !model.sharedTagCloudState.isSignedIn && model.sharedTagAccountLocalCleanupState == nil {
                signedOutSection
            } else if model.sharedTagCloudState.isSignedIn && model.sharedTagCloudState.isConfigured {
                identityLinkSection
            }
            if model.chatGptPersonalLinkSettings.operationEnabled {
                DisclosureGroup {
                    chatGptPersonalLinkSyncSection
                } label: {
                    profileSettingRow(title: "ChatGPT連携", value: chatGptSyncStateLabel, icon: "link", showsChevron: false)
                }
            } else {
                profileSettingRow(title: "ChatGPT連携", value: "現在利用できません", icon: "link", showsChevron: false)
            }
            DisclosureGroup(isExpanded: $isShowingPlanDetails) {
                if !model.entitlements.planType.isPaidCourse {
                    paidCourseSection
                }
                promoCodeSection
            } label: {
                profileSettingRow(title: "プラン・優待", value: nil, icon: "checkmark.seal", showsChevron: false)
            }
            if model.sharedTagCloudState.isSignedIn {
                notificationSettingsRow
            }
            if model.sharedTagCloudState.isSignedIn {
                DisclosureGroup {
                    accountActionsSection
                } label: {
                    profileSettingRow(title: "アカウント管理", value: nil, icon: "person.badge.shield.checkmark", showsChevron: false)
                }
            }
        }
        .tint(AppPalette.textSecondary)
    }

    private var supportSection: some View {
        ProfilePanel {
            Text("サポート").font(.system(.headline, design: .rounded).weight(.heavy))
            Button { isShowingContactSheet = true } label: {
                profileSettingRow(title: "問い合わせ", value: nil, icon: "envelope")
            }
            .buttonStyle(.plain)
            .disabled(isWorking)
        }
    }

    private var currentPlanLabel: String {
        switch model.entitlements.planType {
        case .free: return "Free"
        case .launchStandard, .standard: return "Standard"
        case .pro: return "Pro"
        case .promoPro: return "Pro（優待）"
        }
    }

    private var chatGptSyncStateLabel: String {
        let settings = model.chatGptPersonalLinkSettings
        if !settings.operationEnabled { return "現在利用できません" }
        if !model.sharedTagCloudState.isSignedIn { return "サインインが必要です" }
        if settings.isLoading { return "同期中" }
        if case .failure = settings.status { return "再試行が必要です" }
        return settings.enabled ? "オン" : "オフ"
    }

    private func profileSettingRow(title: String, value: String?, icon: String, showsChevron: Bool = true) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(Color(UIColor(hex: 0x855B76)))
                .frame(width: 30)
            Text(title).font(.system(.body, design: .rounded).weight(.semibold))
            Spacer(minLength: 8)
            if let value {
                Text(value).font(.system(.subheadline)).foregroundStyle(AppPalette.textSecondary)
                    .multilineTextAlignment(.trailing)
            }
            if showsChevron {
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(AppPalette.textMuted)
            }
        }
        .foregroundStyle(AppPalette.textPrimary)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }

    private var signedOutSection: some View {
        AppPanel {
            profileSettingRow(title: "サインイン", value: "未ログイン", icon: "person.crop.circle", showsChevron: false)
            Text("共有タグに参加するにはサインインが必要です")
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)

            if !model.sharedTagCloudState.isConfigured {
                Text("アカウント連携は現在準備中です。設定の準備が整うまでサインイン操作は利用できません。")
                    .font(.footnote)
                    .foregroundStyle(AppPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            VStack(spacing: 10) {
                authProviderButton(
                    title: model.sharedTagCloudState.isConfigured ? "Googleで続ける" : "Googleで続ける（準備中）",
                    systemImage: "globe",
                    identifier: "auth_google",
                    background: AppPalette.surfaceSoft,
                    foreground: AppPalette.textPrimary,
                    enabled: model.sharedTagCloudState.isConfigured && !isWorking,
                    action: startGoogleSignIn
                )
                authProviderButton(
                    title: model.sharedTagCloudState.isConfigured ? "Appleで続ける" : "Appleで続ける（準備中）",
                    systemImage: "apple.logo",
                    identifier: "auth_apple",
                    background: Color.black,
                    foreground: Color.white,
                    enabled: model.sharedTagCloudState.isConfigured && !isWorking,
                    action: { startAppleSignIn() }
                )
                authProviderButton(
                    title: "ChatGPTで続ける（準備中）",
                    systemImage: "message",
                    identifier: "auth_chatgpt",
                    background: AppPalette.surfaceSoft,
                    foreground: AppPalette.textMuted,
                    enabled: false,
                    action: {}
                )
            }

            DisclosureGroup(isExpanded: $isShowingEmailPassword) {
                emailPasswordForm
            } label: {
                profileSettingRow(title: "メールアドレス・パスワード", value: model.sharedTagCloudState.isConfigured ? nil : "準備中", icon: "envelope", showsChevron: false)
            }
            .accessibilityIdentifier("auth_email")
        }
    }

    private var identityLinkSection: some View {
        AppPanel {
            Text("ログイン方法を追加")
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
            Text("GoogleまたはAppleを連携すると、AndroidとiPhoneで同じ共有タグアカウントを使えます。")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            VStack(spacing: 10) {
                authProviderButton(
                    title: "Googleをこのアカウントに連携",
                    systemImage: "globe",
                    identifier: "auth_link_google",
                    background: AppPalette.surfaceSoft,
                    foreground: AppPalette.textPrimary,
                    enabled: !isWorking && model.sharedTagCloudState.authUserID != nil,
                    action: startGoogleIdentityLink
                )
                authProviderButton(
                    title: "Appleをこのアカウントに連携",
                    systemImage: "apple.logo",
                    identifier: "auth_link_apple",
                    background: Color.black,
                    foreground: Color.white,
                    enabled: !isWorking && model.sharedTagCloudState.authUserID != nil,
                    action: startAppleIdentityLink
                )
                authProviderButton(
                    title: "ChatGPT　準備中",
                    systemImage: "message",
                    identifier: "auth_chatgpt",
                    background: AppPalette.surfaceSoft,
                    foreground: AppPalette.textMuted,
                    enabled: false,
                    action: {}
                )
            }
        }
    }

    private var emailPasswordForm: some View {
        VStack(alignment: .leading, spacing: 12) {
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
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(AppPalette.outlineSoft, lineWidth: 1.5))
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("パスワード")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(AppPalette.textSecondary)
                HStack(spacing: 10) {
                    Group {
                        if isPasswordVisible {
                            TextField("", text: $password, prompt: Text("8文字以上").foregroundStyle(AppPalette.textMuted))
                        } else {
                            SecureField("", text: $password, prompt: Text("8文字以上").foregroundStyle(AppPalette.textMuted))
                        }
                    }
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(AppPalette.textPrimary)
                    .tint(AppPalette.primaryStrong)

                    Button { isPasswordVisible.toggle() } label: {
                        Image(systemName: isPasswordVisible ? "eye.slash" : "eye")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(AppPalette.textSecondary)
                            .frame(width: 32, height: 32)
                    }
                    .accessibilityLabel(isPasswordVisible ? "パスワードを隠す" : "パスワードを表示")
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 18)
                .background(AppPalette.background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(AppPalette.outlineSoft, lineWidth: 1.5))
            }

            HStack(spacing: 10) {
                AppActionButton(tone: .primary, enabled: canSubmitAuth && !isWorking) {
                    guard !isWorking else { return }
                    isWorking = true
                    Task {
                        await model.signInToSharedTagCloud(email: email, password: password)
                        isWorking = false
                    }
                } label: {
                    if isWorking { ProgressView().tint(AppPalette.textPrimary) } else { Text("サインイン") }
                }
                AppActionButton(enabled: canSubmitAuth && !isWorking) {
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

            HStack(spacing: 10) {
                AppActionButton(enabled: canSubmitEmailOnly && !isWorking) {
                    guard !isWorking else { return }
                    isWorking = true
                    Task {
                        await model.resendSharedTagEmailConfirmation(email: email)
                        isWorking = false
                    }
                } label: {
                    Text("確認メール再送")
                }
                AppActionButton(enabled: canSubmitEmailOnly && !isWorking) {
                    guard !isWorking else { return }
                    isWorking = true
                    Task {
                        await model.sendSharedTagPasswordRecovery(email: email)
                        isWorking = false
                    }
                } label: {
                    Text("パスワード再設定")
                }
            }
        }
        .padding(.top, 8)
        .disabled(!model.sharedTagCloudState.isConfigured)
    }

    private func authProviderButton(
        title: String,
        systemImage: String,
        identifier: String,
        background: Color,
        foreground: Color,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(.body, design: .rounded).weight(.semibold))
                    .frame(width: 24)
                Text(title)
                    .font(.system(.body, design: .rounded).weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 52)
            .foregroundStyle(foreground)
            .background(background, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(AppPalette.outlineSoft, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.62)
        .accessibilityIdentifier(identifier)
        .accessibilityLabel(title)
        .accessibilityHint(enabled ? "" : (!model.sharedTagCloudState.isConfigured || title.contains("ChatGPT") ? "現在準備中です" : "サインイン状態を確認してください"))
    }

    private var chatGptPersonalLinkSyncSection: some View {
        let settings = model.chatGptPersonalLinkSettings
        return AppPanel {
            Label("ChatGPTに保存リンクを同期", systemImage: "link.badge.plus")
                .font(.system(size: 20, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            if !settings.operationEnabled {
                Text("現在は利用できません")
                    .font(.system(size: 17, weight: .heavy, design: .rounded))
                    .foregroundStyle(AppPalette.textPrimary)
                Text("同期をオンにするまでリンクは送信されません")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AppPalette.textSecondary)
            } else if !model.sharedTagCloudState.isSignedIn {
                Text("りんばむのアカウントでサインインしてください")
                    .font(.system(size: 16, weight: .heavy, design: .rounded))
                    .foregroundStyle(AppPalette.textPrimary)
            } else {
                HStack(spacing: 10) {
                    syncCountTile(title: "同期対象", value: settings.eligibleCount)
                    syncCountTile(title: "除外", value: settings.excludedCount)
                }

                if !settings.enabled {
                    Text("同期をオンにするまでリンクは送信されません")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(AppPalette.textSecondary)
                } else {
                    Text("同期はオンです。対象は自作タグ由来のアクティブなリンクだけです")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(AppPalette.textSecondary)
                }

                if !settings.exclusionReasons.isEmpty {
                    Text("共有タグ付き、アーカイブ、削除待ちなどを除外しています")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(AppPalette.textSecondary)
                }

                if settings.isLoading {
                    ProgressView("同期しています…")
                        .tint(AppPalette.primaryStrong)
                } else {
                    HStack(spacing: 10) {
                        AppActionButton(tone: settings.enabled ? .secondary : .primary, enabled: !isWorking) {
                            requestChatGptPersonalLinkSyncConfirmation(.change(enabled: !settings.enabled))
                        } label: {
                            Text(settings.enabled ? "同期をオフにする" : "同期をオンにする")
                        }

                        if settings.enabled {
                            AppActionButton(enabled: !isWorking) {
                                requestChatGptPersonalLinkSyncConfirmation(.sync)
                            } label: {
                                Text("今すぐ同期")
                            }
                        }
                    }

                    if case .failure = settings.status {
                        AppActionButton(enabled: !isWorking) {
                            requestChatGptPersonalLinkSyncConfirmation(.sync)
                        } label: {
                            Text("再試行")
                        }
                    }
                }

                if let lastSyncedAt = settings.lastSyncedAt {
                    Text("最終同期: \(DateFormatters.detailTimestamp.string(from: lastSyncedAt))")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(AppPalette.textMuted)
                }
            }
        }
        .confirmationDialog(
            "ChatGPT同期の確認",
            isPresented: Binding(
                get: { pendingChatGptPersonalLinkSyncAction != nil },
                set: { if !$0 { pendingChatGptPersonalLinkSyncAction = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("確認して実行") {
                confirmChatGptPersonalLinkSyncAction()
            }
            Button("キャンセル", role: .cancel) {
                pendingChatGptPersonalLinkSyncAction = nil
            }
        } message: {
            Text(chatGptPersonalLinkSyncConfirmationMessage(
                action: pendingChatGptPersonalLinkSyncAction,
                settings: settings
            ))
        }
    }

    private func requestChatGptPersonalLinkSyncConfirmation(_ action: ChatGptPersonalLinkSyncAction) {
        let settings = model.chatGptPersonalLinkSettings
        guard settings.operationEnabled,
              model.sharedTagCloudState.isSignedIn,
              !settings.isLoading else { return }
        if case .sync = action, !settings.enabled { return }
        pendingChatGptPersonalLinkSyncAction = action
    }

    private func confirmChatGptPersonalLinkSyncAction() {
        guard let action = pendingChatGptPersonalLinkSyncAction else { return }
        pendingChatGptPersonalLinkSyncAction = nil

        switch action {
        case .change(let enabled):
            model.requestChatGptPersonalLinkSyncChange(enabled: enabled)
            Task { await model.confirmChatGptPersonalLinkSyncChange() }
        case .sync:
            Task { await model.syncChatGptPersonalLinksNow() }
        }
    }

    private func chatGptPersonalLinkSyncConfirmationMessage(
        action: ChatGptPersonalLinkSyncAction?,
        settings: ChatGptPersonalLinkSettings
    ) -> String {
        let counts = "対象 \(settings.eligibleCount)件 / 除外 \(settings.excludedCount)件"
        switch action {
        case .change(enabled: true):
            return "\(counts)\n送信概要: URL・タイトル・メモ・自作タグ名・更新日時を同期します。本文や秘密情報は送信しません。\n同期をオンにします。"
        case .change(enabled: false):
            return "\(counts)\n送信概要: 同期をオフにするため、今回の送信はありません。\n同期をオフにします。"
        case .sync:
            return "\(counts)\n送信概要: URL・タイトル・メモ・自作タグ名・更新日時を同期します。本文や秘密情報は送信しません。"
        case nil:
            return counts
        }
    }

    private func syncCountTile(title: String, value: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(AppPalette.textSecondary)
            Text("\(value)件")
                .font(.system(size: 21, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    #if DEBUG
    private var aiTransparencyEntry: some View {
        AppPanel {
            Text("AI動作の確認（デバッグ）")
                .font(.system(size: 19, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
            Text("端末内のモックReceipt / 下書き / 変更案を確認できます")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)
            AppActionButton(enabled: !isWorking) {
                isShowingAiTransparency = true
            } label: {
                Text("AI動作の確認（デバッグ）")
            }
        }
    }
    #endif

    private var usageMetricLayout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
    }

    private func usageMetricRow(title: String, count: Int) -> some View {
        usageMetricLayout {
            Text(title)
                .font(.system(.body, design: .rounded).weight(.semibold))
                .foregroundStyle(AppPalette.textPrimary)
            if !dynamicTypeSize.isAccessibilitySize {
                Spacer(minLength: 8)
            }
            Text("\(count)件")
                .font(.system(.body, design: .rounded).weight(.bold))
                .foregroundStyle(AppPalette.textSecondary)
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var usageSummarySection: some View {
        ProfilePanel {
            Button {
                withAnimation { isShowingUsageDetails.toggle() }
            } label: {
                HStack {
                    Text("利用状況")
                        .font(.system(size: 18, weight: .heavy, design: .rounded))
                    Spacer()
                    Image(systemName: isShowingUsageDetails ? "chevron.up" : "chevron.down")
                        .font(.system(size: 14, weight: .semibold))
                }
                .foregroundStyle(AppPalette.textPrimary)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("利用状況")
            .accessibilityValue(isShowingUsageDetails ? "展開中" : "閉じています")
            .accessibilityHint(isShowingUsageDetails ? "タップして閉じます" : "タップして4項目を表示します")

            if isShowingUsageDetails {
                usageMetricRow(title: "保存リンク", count: personalSavedURLCount)
                Divider().overlay(AppPalette.outlineSoft.opacity(0.5))
                usageMetricRow(title: "共有タグ", count: model.sharedTags.count)
                Divider().overlay(AppPalette.outlineSoft.opacity(0.5))
                usageMetricRow(title: "グループ", count: model.sharedTagGroups.count)
                Divider().overlay(AppPalette.outlineSoft.opacity(0.5))
                usageMetricLayout {
                    Text("現在のプラン").font(.system(.body, design: .rounded).weight(.semibold))
                    if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 8) }
                    Text(currentPlanLabel)
                        .font(.system(.subheadline).weight(.bold))
                        .foregroundStyle(Color(UIColor(hex: 0x855B76)))
                }
                .foregroundStyle(AppPalette.textPrimary)
                .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                .accessibilityElement(children: .combine)
            }
        }
        .padding(8)
        .background(Color(UIColor(hex: 0xEFE5EC)), in: RoundedRectangle(cornerRadius: 28, style: .continuous))
    }

    @ViewBuilder
    private var notificationSettingsRow: some View {
        if model.sharedTagCloudState.isSignedIn &&
            model.sharedTagNotificationAuthorizationStatus == .notDetermined {
            AppActionButton(enabled: !isWorking) {
                guard !isWorking else { return }
                isWorking = true
                Task {
                    await model.requestSharedTagNotificationAuthorization()
                    isWorking = false
                }
            } label: {
                Text("共有タグの新着通知を有効にする")
            }
        } else if model.sharedTagCloudState.isSignedIn &&
            model.sharedTagNotificationAuthorizationStatus == .denied {
            AppActionButton(enabled: !isWorking) {
                guard let settingsURL = URL(string: UIApplication.openSettingsURLString) else { return }
                UIApplication.shared.open(settingsURL)
            } label: {
                Text("iPhoneの設定で通知を有効にする")
            }
        }

    }

    private var accountActionsSection: some View {
        VStack(spacing: 16) {
            if model.sharedTagCloudState.isSignedIn {
                HStack(spacing: 10) {
                    AppActionButton(enabled: !isWorking) {
                        guard !isWorking else { return }
                        isWorking = true
                        Task {
                            await model.signOutFromSharedTagCloud()
                            isWorking = false
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
            }


        }
    }

    private func accountLocalCleanupSection(_ state: SharedTagAccountLocalCleanupState) -> some View {
        AppPanel(strong: true) {
            Text("アカウント削除済み")
                .font(.system(size: 20, weight: .heavy, design: .rounded))
                .foregroundStyle(Color.white.opacity(0.96))

            if state.aiDataCleanupPending {
                Text("アカウント削除済み・端末内AIデータ消去未完了")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.96))
            }
            if state.signOutCleanupPending {
                Text("端末内のログイン情報の消去が未完了です")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.96))
            }
            if state.syncCancellationCleanupPending {
                Text("共有タグ同期処理の停止が未完了です")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.96))
            }
            if state.sharedDataCleanupPending {
                Text("共有タグ同期データの消去が未完了です")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.96))
            }
            if state.entitlementCleanupPending {
                Text("利用権限キャッシュの消去が未完了です")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.96))
            }
            if state.personalLinkSettingsCleanupPending {
                Text("ChatGPT連携設定の消去が未完了です")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.96))
            }

            Text("再試行では端末内の消去だけを行い、クラウドのアカウント削除は行いません。")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.82))

            AppActionButton(tone: .primary, enabled: !isWorking) {
                guard !isWorking else { return }
                isWorking = true
                Task {
                    await model.retrySharedTagAccountLocalCleanup()
                    isWorking = false
                }
            } label: {
                if isWorking {
                    ProgressView().tint(AppPalette.textPrimary)
                } else {
                    Text("端末内データ消去を再試行")
                }
            }
            .accessibilityHint("クラウドのアカウント削除は行わず、端末内の消去だけを再試行します")
        }
    }

    private var promoCodeSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("優待コード")
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
            TextField("", text: $promoCode)
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
                .onChange(of: promoCode) { _, _ in
                    promoMessage = nil
                }

            AppActionButton(
                tone: .primary,
                enabled: canRedeemPromoCode && !isRedeemingPromoCode
            ) {
                redeemPromoCode()
            } label: {
                if isRedeemingPromoCode {
                    ProgressView().tint(AppPalette.textPrimary)
                } else {
                    Text("優待Proを受け取る")
                }
            }

            if let promoMessage {
                Text(promoMessage)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(AppPalette.textSecondary)
            }
        }
    }

    private var paidCourseSection: some View {
        let options = availablePaidCoursePurchaseOptions(currentPlan: model.entitlements.planType)
        return AppPanel {
            Text("有料コース")
                .font(.system(size: 20, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            Text("購入後はサーバー検証が完了したコースだけ反映されます。")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)

            if options.isEmpty {
                Label("Proが有効です。追加購入は必要ありません。", systemImage: "checkmark.seal.fill")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(AppPalette.primaryStrong)
                    .accessibilityLabel("Proが有効です。追加購入は必要ありません")
            } else {
                let columns = dynamicTypeSize.isAccessibilitySize
                    ? [GridItem(.flexible())]
                    : [GridItem(.flexible()), GridItem(.flexible())]
                LazyVGrid(columns: columns, spacing: 10) {
                    ForEach(options, id: \.self) { option in
                        paidCourseButton(
                            title: paidCourseOptionTitle(option),
                            plan: option.planType,
                            period: option.billingPeriod
                        )
                    }
                }
            }
        }
    }

    private func paidCourseOptionTitle(_ option: PaidCoursePurchaseOption) -> String {
        let plan = option.planType == .pro ? "Pro" : "Standard"
        let period = option.billingPeriod == .yearly ? "年払い" : "月額"
        return "\(plan) \(period)"
    }

    private func paidCourseButton(title: String, plan: PlanType, period: BillingPeriod) -> some View {
        AppActionButton(
            tone: plan == .pro ? .primary : .secondary,
            enabled: model.sharedTagCloudState.isSignedIn && !isWorking
        ) {
            guard !isWorking else { return }
            isWorking = true
            Task {
                await model.purchasePaidCourse(planType: plan, billingPeriod: period)
                isWorking = false
            }
        } label: {
            Text(title)
                .font(.system(.subheadline, design: .rounded).weight(.heavy))
                .lineLimit(1)
                .minimumScaleFactor(0.74)
                .allowsTightening(true)
        }
        .accessibilityIdentifier("paid_course_\(plan.rawValue)_\(period.rawValue)")
    }

    private var canSubmitAuth: Bool {
        !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            password.trimmingCharacters(in: .whitespacesAndNewlines).count >= 8
    }

    private var canSubmitEmailOnly: Bool {
        !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var canRedeemPromoCode: Bool {
        !promoCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var personalSavedURLCount: Int {
        model.activeEntries.count + model.archivedEntries.count
    }

    private var activeAvatarImageData: Data? {
        isAvatarDraftActive ? avatarDraftData : model.profile.avatarImageData
    }

    private var hasProfileChanges: Bool {
        displayNameDraft.trimmingCharacters(in: .whitespacesAndNewlines) != model.profile.trimmedDisplayName ||
            isAvatarDraftActive
    }

    private func normalizeAvatarImageData(_ data: Data) -> Data {
        guard let image = UIImage(data: data) else { return data }
        return image.jpegData(compressionQuality: 0.82) ?? data
    }

    private func startGoogleSignIn() {
        guard !isWorking else { return }
        guard let url = model.googleOAuthURLForSharedTagCloud() else {
            model.showProfileStatusMessage("Googleサインインを開始できませんでした。クラウド設定を確認してください。")
            return
        }
        googleOAuthPurpose = .signIn
        googleOAuthURL = url
    }

    private func startGoogleIdentityLink() {
        guard !isWorking else { return }
        guard model.sharedTagCloudState.isSignedIn,
              let expectedAuthUserID = model.sharedTagCloudState.authUserID else {
            model.showProfileStatusMessage("連携する共有タグアカウントを確認できませんでした。")
            return
        }
        isWorking = true
        Task {
            let result = await model.googleIdentityLinkURLForSharedTagCloud()
            isWorking = false
            guard model.sharedTagCloudState.authUserID == expectedAuthUserID else {
                await model.cancelGoogleIdentityLinkForSharedTagCloud()
                model.showProfileStatusMessage("ログイン中のアカウントが変わったため、Google連携を開始しませんでした。")
                return
            }
            switch result {
            case .success(let url):
                googleOAuthPurpose = .identityLink
                googleOAuthURL = url
            case .failure(let error):
                model.showProfileStatusMessage(error.userMessage)
            }
        }
    }

    private func dismissGoogleOAuthSheet() {
        let shouldCancelIdentityLink = googleOAuthPurpose.isIdentityLink
        googleOAuthURL = nil
        googleOAuthPurpose = .signIn
        if shouldCancelIdentityLink {
            Task { await model.cancelGoogleIdentityLinkForSharedTagCloud() }
        }
    }

    private func startAppleIdentityLink() {
        guard let expectedAuthUserID = model.sharedTagCloudState.authUserID,
              model.sharedTagCloudState.isSignedIn else {
            model.showProfileStatusMessage("連携する共有タグアカウントを確認できませんでした。")
            return
        }
        startAppleSignIn(expectedAuthUserID: expectedAuthUserID)
    }

    private func redeemPromoCode() {
        let code = promoCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty, !isRedeemingPromoCode else { return }
        isRedeemingPromoCode = true
        Task {
            let result = await model.redeemPromoCode(code)
            await MainActor.run {
                switch result {
                case .success:
                    promoMessage = "優待Proを受け取りました。使用状況に反映されます。"
                    promoCode = ""
                case .authRequired:
                    promoMessage = "優待を受け取るにはサインインが必要です"
                case .invalidCode:
                    promoMessage = "優待コードを入力してください"
                case .failure(let message):
                    promoMessage = message
                }
                isRedeemingPromoCode = false
            }
        }
    }

    private func applyPendingPromoCodeIfNeeded() {
        guard let pendingCode = model.pendingPromoCode?.trimmingCharacters(in: .whitespacesAndNewlines),
              !pendingCode.isEmpty else {
            return
        }
        promoCode = pendingCode
        promoMessage = "メールの優待コードを読み込みました"
        model.clearPendingPromoCode()
    }

    private func handleAppleSignInCompletion(_ result: Result<ASAuthorization, Error>) {
        guard !isWorking else {
            pendingAppleNonce = nil
            pendingAppleState = nil
            pendingAppleExpectedAuthUserID = nil
            return
        }
        switch result {
        case .failure(let error):
            pendingAppleNonce = nil
            pendingAppleState = nil
            pendingAppleExpectedAuthUserID = nil
            if (error as? ASAuthorizationError)?.code == .canceled { return }
            model.showProfileStatusMessage("Apple認証を完了できませんでした。")
        case .success(let authorization):
            guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
                  let identityToken = credential.identityToken,
                  let token = String(data: identityToken, encoding: .utf8),
                  !token.isEmpty,
                  let nonce = pendingAppleNonce,
                  let expectedState = pendingAppleState,
                  credential.state == expectedState else {
                pendingAppleNonce = nil
                pendingAppleState = nil
                pendingAppleExpectedAuthUserID = nil
                model.showProfileStatusMessage("Apple認証の情報を検証できませんでした。")
                return
            }
            let expectedAuthUserID = pendingAppleExpectedAuthUserID
            pendingAppleNonce = nil
            pendingAppleState = nil
            pendingAppleExpectedAuthUserID = nil
            isWorking = true
            Task {
                if let expectedAuthUserID {
                    await model.linkAppleIdentityForSharedTagCloud(
                        idToken: token,
                        nonce: nonce,
                        expectedAuthUserID: expectedAuthUserID
                    )
                } else {
                    await model.signInWithAppleForSharedTagCloud(idToken: token, nonce: nonce)
                }
                isWorking = false
            }
        }
    }

    private func startAppleSignIn(expectedAuthUserID: String? = nil) {
        guard !isWorking else { return }
        if let expectedAuthUserID,
           model.sharedTagCloudState.authUserID != expectedAuthUserID || !model.sharedTagCloudState.isSignedIn {
            model.showProfileStatusMessage("ログイン中のアカウントが変わったため、Apple連携を開始しませんでした。")
            return
        }
        let nonce = randomAppleNonce()
        let state = randomAppleNonce(byteCount: 24)
        pendingAppleNonce = nonce
        pendingAppleState = state
        pendingAppleExpectedAuthUserID = expectedAuthUserID

        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.email]
        request.nonce = sha256Hex(nonce)
        request.state = state

        appleSignInCoordinator.start(
            request: request,
            presentationWindow: activePresentationWindow(),
            completion: handleAppleSignInCompletion
        )
    }

    private func activePresentationWindow() -> UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }
    }
}

private final class AppleSignInCoordinator: NSObject, ObservableObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    private var completion: ((Result<ASAuthorization, Error>) -> Void)?
    private weak var presentationWindow: UIWindow?

    func start(
        request: ASAuthorizationAppleIDRequest,
        presentationWindow: UIWindow?,
        completion: @escaping (Result<ASAuthorization, Error>) -> Void
    ) {
        self.completion = completion
        self.presentationWindow = presentationWindow
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        controller.performRequests()
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        completion?(.success(authorization))
        completion = nil
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        completion?(.failure(error))
        completion = nil
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        presentationWindow ?? ASPresentationAnchor()
    }
}

private struct ProfilePanel<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            content
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppPalette.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(AppPalette.outlineSoft.opacity(0.7), lineWidth: 0.5)
        )
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
                .lineLimit(1)
                .minimumScaleFactor(0.82)
            Text(valueText)
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.horizontal, 8)
        .padding(.vertical, 10)
        .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

#if DEBUG
private struct AiTransparencySheet: View {
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var model: URLSaverAppModel
    @State private var preview: AiSendPreview?
    @State private var receipt: AiSendReceipt?
    @State private var draft: AiDraft?
    @State private var diff: AiDiffProposal?
    @State private var message: String?
    @State private var isShowingApplyConfirmation = false

    var body: some View {
        ScreenContainer {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Text("AI動作の確認（デバッグ）")
                            .font(.system(size: 26, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textPrimary)
                        Spacer()
                        Button("閉じる") { dismiss() }
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(AppPalette.primaryStrong)
                    }

                    AppPanel(strong: true) {
                        Text("この画面は端末内のモックです。外部には送信しません。")
                            .font(.system(size: 16, weight: .heavy, design: .rounded))
                            .foregroundStyle(Color.white.opacity(0.96))
                    }

                    AppPanel {
                        Text("送信前の確認")
                            .font(.system(size: 19, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textPrimary)
                        if let preview {
                            Text("対象 \(preview.eligibleCount)件 / 除外 \(preview.blockedCount)件")
                                .font(.system(size: 17, weight: .bold))
                                .foregroundStyle(AppPalette.textPrimary)
                            Text("本文、prompt、token、端末内パス、DB IDは送信しません")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(AppPalette.textSecondary)
                        } else {
                            Text("確認データを作成できませんでした")
                                .foregroundStyle(AppPalette.danger)
                        }
                    }

                    AppPanel {
                        Text("処理記録")
                            .font(.system(size: 19, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textPrimary)
                        if let receipt {
                            Text("送信対象 \(receipt.sentSourceIDs.count)件 / 除外 \(receipt.blockedSourceIDs.count)件")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(AppPalette.textPrimary)
                            Text("サイズ: \(receipt.requestSizeBucket.rawValue) / \(receipt.responseSizeBucket.rawValue)")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(AppPalette.textSecondary)
                            Text("raw body / raw prompt: なし")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(AppPalette.textSecondary)
                        } else {
                            Text("まだ処理記録はありません")
                                .foregroundStyle(AppPalette.textSecondary)
                        }
                    }

                    AppPanel {
                        Text("下書き")
                            .font(.system(size: 19, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textPrimary)
                        if let draft {
                            Text(draft.title)
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(AppPalette.textPrimary)
                            Text(draft.body)
                                .font(.system(size: 14, weight: .medium, design: .monospaced))
                                .foregroundStyle(AppPalette.textSecondary)
                                .textSelection(.enabled)
                        } else {
                            Text("モック下書きはありません")
                                .foregroundStyle(AppPalette.textSecondary)
                        }
                    }

                    AppPanel {
                        Text("変更案")
                            .font(.system(size: 19, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textPrimary)
                        if let diff {
                            Text("変更案 \(diff.operations.count)件")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(AppPalette.textPrimary)
                            Text(diff.operations.isEmpty ? "今回のモックでは本体変更案はありません。適用対象はタイトルとメモだけです。" : "変更前後を確認してから明示的に適用します。")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(AppPalette.textSecondary)
                            ForEach(Array(diff.operations.enumerated()), id: \.offset) { _, operation in
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(operation.field == "memo" ? "メモの変更案" : "タイトルの変更案")
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundStyle(AppPalette.textPrimary)
                                    Text("変更前: \((operation.before?.isEmpty == false) ? operation.before! : "（空）")")
                                        .font(.system(size: 14, weight: .medium))
                                        .foregroundStyle(AppPalette.textSecondary)
                                    Text("変更後: \((operation.after?.isEmpty == false) ? operation.after! : "（空）")")
                                        .font(.system(size: 14, weight: .medium))
                                        .foregroundStyle(AppPalette.textPrimary)
                                }
                            }
                            if !diff.operations.isEmpty, !diff.applied {
                                AppActionButton(tone: .primary, enabled: true) {
                                    isShowingApplyConfirmation = true
                                } label: {
                                    Text("変更を反映")
                                }
                            }
                        } else {
                            Text("変更案はありません")
                                .foregroundStyle(AppPalette.textSecondary)
                        }
                    }

                    if let message {
                        Text(message)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(AppPalette.danger)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 24)
            }
        }
        .task { prepare() }
        .confirmationDialog("変更を反映", isPresented: $isShowingApplyConfirmation, titleVisibility: .visible) {
            Button("確認して反映") {
                guard let proposalID = diff?.proposalID else { return }
                Task {
                    let applied = await model.applyAiTransparencyDiff(proposalID: proposalID, confirm: true)
                    message = applied ? "変更案を反映しました" : "内容が変更されたため、変更案を作り直してください"
                    if applied { diff?.applied = true }
                }
            }
            Button("キャンセル", role: .cancel) {}
        } message: {
            Text("変更直前に対象条件と現在値を再確認します。1件でも条件を満たさない場合は反映しません。")
        }
    }

    private func prepare() {
        guard let preview = model.aiTransparencyPreview(),
              let receipt = model.saveAiTransparencyReceipt(preview: preview),
              let draft = model.generateAiTransparencyDraft(preview: preview, receipt: receipt) else {
            message = "デバッグflagが無効、またはローカル確認データを作成できませんでした"
            return
        }
        let operation: AiDiffOperation? = preview.sources
            .first(where: { $0.aiEligible })
            .flatMap { source in
                model.activeEntries.first(where: {
                    AiTransparencyPolicy.publicSafeID(for: $0) == source.publicSafeID
                }).map { entry in
                    AiDiffOperation(
                        targetPublicSafeID: source.publicSafeID,
                        field: "memo",
                        before: entry.memo,
                        after: String(draft.body.prefix(2_000))
                    )
                }
            }
        guard let diff = model.saveAiTransparencyDiff(
            draft: draft,
            operations: operation.map { [$0] } ?? []
        ) else {
            message = "変更案を作成できませんでした"
            return
        }
        self.preview = preview
        self.receipt = receipt
        self.draft = draft
        self.diff = diff
    }
}
#endif

private struct ContactSupportSheet: View {
    @Environment(\.dismiss) private var dismiss

    let initialEmail: String
    let initialName: String
    let onSend: (String, String, String, String) async -> ContactSupportSendResult

    @State private var email: String
    @State private var name: String
    @State private var message = ""
    @State private var idempotencyKey = UUID().uuidString
    @State private var didAttemptSubmit = false
    @State private var isSending = false
    @State private var sendError: String?

    init(
        initialEmail: String,
        initialName: String,
        onSend: @escaping (String, String, String, String) async -> ContactSupportSendResult
    ) {
        self.initialEmail = initialEmail
        self.initialName = initialName
        self.onSend = onSend
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

                        if let sendError {
                            Text(sendError)
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(AppPalette.danger)
                        }

                        AppActionButton(tone: .primary, enabled: !isSending) {
                            guard canSubmit else {
                                didAttemptSubmit = true
                                sendError = "メールアドレス、氏名、問い合わせ内容を入力してください。"
                                return
                            }
                            guard !isSending else { return }
                            didAttemptSubmit = true
                            isSending = true
                            sendError = nil
                            Task {
                                let result = await onSend(email, name, message, idempotencyKey)
                                await MainActor.run {
                                    switch result {
                                    case .success:
                                        dismiss()
                                    case .failure(let message):
                                        sendError = message
                                    }
                                    isSending = false
                                }
                            }
                        } label: {
                            Text(isSending ? "送信中..." : "送信")
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 22)
            }
        }
        .onChange(of: email) { _, _ in
            rotateIdempotencyKeyAfterEdit()
        }
        .onChange(of: name) { _, _ in
            rotateIdempotencyKeyAfterEdit()
        }
        .onChange(of: message) { _, _ in
            rotateIdempotencyKeyAfterEdit()
        }
    }

    private func rotateIdempotencyKeyAfterEdit() {
        guard didAttemptSubmit else { return }
        idempotencyKey = UUID().uuidString
        didAttemptSubmit = false
        sendError = nil
    }

    private var canSubmit: Bool {
        !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
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

private struct HomeBackgroundPicker: View {
    let selectedStyle: HomeBackgroundStyle
    let onSelect: (HomeBackgroundStyle) -> Void

    private func colors(for style: HomeBackgroundStyle) -> [Color] {
        AppDisplayColorPalette.colors(for: style)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("背景カラー")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(AppPalette.textSecondary)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                ForEach(HomeBackgroundStyle.allCases) { style in
                    let selected = selectedStyle == style
                    Button {
                        onSelect(style)
                    } label: {
                        VStack(alignment: .leading, spacing: 7) {
                            ZStack(alignment: .topTrailing) {
                                LinearGradient(
                                    colors: colors(for: style),
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                                .frame(height: 84)
                                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                                if selected {
                                    Image(systemName: "checkmark")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundStyle(Color.white)
                                        .frame(width: 28, height: 28)
                                        .background(Color(UIColor(hex: 0x855B76)), in: Circle())
                                        .padding(8)
                                }
                            }
                            Text(style.label)
                                .font(.system(size: 14, weight: selected ? .bold : .medium))
                                .foregroundStyle(AppPalette.textPrimary)
                        }
                        .padding(5)
                        .overlay(
                            RoundedRectangle(cornerRadius: 19, style: .continuous)
                                .stroke(selected ? Color(UIColor(hex: 0x855B76)) : AppPalette.outlineSoft, lineWidth: selected ? 2 : 1)
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityValue(selected ? "選択中" : "未選択")
                }
            }
        }
    }
}

private struct ProfileAvatarView: View {
    let imageData: Data?

    var body: some View {
        ZStack {
            AppPalette.surfaceSoft
            if let imageData,
               let uiImage = UIImage(data: imageData) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
            } else if let url = Bundle.main.url(forResource: "DefaultProfilePig", withExtension: "png"),
               let uiImage = UIImage(contentsOfFile: url.path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFit()
                    .padding(6)
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

private struct SafariAuthView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}

private func randomAppleNonce(byteCount: Int = 32) -> String {
    var bytes = [UInt8](repeating: 0, count: byteCount)
    let status = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
    if status == errSecSuccess {
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
    return UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
}

private func sha256Hex(_ value: String) -> String {
    SHA256.hash(data: Data(value.utf8))
        .map { String(format: "%02x", $0) }
        .joined()
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
                    Text("共有タグクラウド画面から参加できます")
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
