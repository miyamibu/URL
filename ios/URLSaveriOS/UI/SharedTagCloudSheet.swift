import AuthenticationServices
import CryptoKit
import PhotosUI
import SafariServices
import SwiftUI
import UIKit

struct SharedTagCloudSheet: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appThemeMode") private var themeModeRaw = AppThemeMode.system.rawValue

    @ObservedObject var model: URLSaverAppModel
    @State private var email = ""
    @State private var password = ""
    @State private var isPasswordVisible = false
    @State private var displayNameDraft = ""
    @State private var isEditingDisplayName = false
    @State private var selectedAvatarItem: PhotosPickerItem?
    @State private var avatarDraftData: Data?
    @State private var isAvatarDraftActive = false
    @State private var isWorking = false
    @State private var isShowingDeleteConfirmation = false
    @State private var isShowingContactSheet = false
    @State private var promoCode = ""
    @State private var promoMessage: String?
    @State private var isRedeemingPromoCode = false
    @State private var googleOAuthURL: URL?
    @State private var pendingAppleNonce: String?
    @State private var pendingAppleState: String?
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
                    usageSummarySection
                    accountActionsSection
                    paidCourseSection

                    if model.sharedTagCloudState.isConfigured && !model.sharedTagCloudState.isSignedIn {
                        signedOutSection
                    }

                    promoCodeSection

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
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 22)
            }
        }
        .task {
            displayNameDraft = model.profile.trimmedDisplayName
            applyPendingPromoCodeIfNeeded()
            await model.refreshSharedTagCloudState()
        }
        .onChange(of: model.profile.displayName) { _, newValue in
            displayNameDraft = newValue
        }
        .onChange(of: model.pendingPromoCode) { _, _ in
            applyPendingPromoCodeIfNeeded()
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
        .sheet(isPresented: $isShowingContactSheet) {
            ContactSupportSheet(
                initialEmail: model.sharedTagCloudState.signedInEmail ?? email,
                initialName: model.profile.trimmedDisplayName,
                onSend: { email, name, message in
                    await model.sendContactSupport(email: email, name: name, message: message)
                }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(32)
        }
        .sheet(
            isPresented: Binding(
                get: { googleOAuthURL != nil },
                set: { isPresented in
                    if !isPresented {
                        googleOAuthURL = nil
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

        return AppPanel {
            HStack(alignment: .center, spacing: 16) {
                ProfileAvatarView(imageData: currentAvatarImageData)

                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top, spacing: 8) {
                        if isEditingDisplayName {
                            TextField("", text: $displayNameDraft, prompt: Text("表示名を入力").foregroundStyle(AppPalette.textMuted))
                                .textInputAutocapitalization(.words)
                                .autocorrectionDisabled()
                                .font(.system(size: 21, weight: .heavy, design: .rounded))
                                .foregroundStyle(AppPalette.textPrimary)
                                .tint(AppPalette.primaryStrong)
                                .textFieldStyle(.plain)
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
                            .foregroundStyle(AppPalette.primaryStrong)
                            .padding(.top, 5)
                        } else {
                            Text(model.profileDisplayName)
                                .font(.system(size: 21, weight: .heavy, design: .rounded))
                                .foregroundStyle(AppPalette.textPrimary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.72)
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
                            .foregroundStyle(AppPalette.primaryStrong)
                            .padding(.top, 5)
                        }
                    }
                    if let signedInEmail = model.sharedTagCloudState.signedInEmail, !signedInEmail.isEmpty {
                        Text(signedInEmail)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                    }
                    HStack(spacing: 8) {
                        PhotosPicker(selection: $selectedAvatarItem, matching: .images) {
                            Text(hasAvatarImage ? "写真を変更" : "写真を追加")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundStyle(AppPalette.primaryStrong)
                        }
                        if hasAvatarImage {
                            Button("削除") {
                                avatarDraftData = nil
                                isAvatarDraftActive = true
                                model.showProfileStatusMessage("プロフィール写真を削除対象にしました")
                            }
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(AppPalette.textSecondary)
                        }
                    }
                }
                Spacer(minLength: 0)
            }

            ThemeModePicker(
                selectedMode: AppThemeMode(rawValue: themeModeRaw) ?? .system,
                onSelect: { themeModeRaw = $0.rawValue }
            )

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
        }
    }

    private var signedOutSection: some View {
        AppPanel {
            Text("共有タグに参加するにはサインインが必要です")
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.68)

            HStack(spacing: 10) {
                AppActionButton(
                    enabled: !isWorking
                ) {
                    guard !isWorking else { return }
                    if let url = model.googleOAuthURLForSharedTagCloud() {
                        googleOAuthURL = url
                    } else {
                        model.showProfileStatusMessage("Googleサインインを開始できませんでした。クラウド設定を確認してください。")
                    }
                } label: {
                    Text("Googleで続ける")
                }

                Button {
                    startAppleSignIn()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "apple.logo")
                            .font(.system(size: 17, weight: .semibold))
                        Text("Appleで続ける")
                            .font(.system(size: 15, weight: .semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.72)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .foregroundStyle(Color.white)
                    .background(Color.black, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Appleで続ける")
                .disabled(isWorking)
            }

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

                    Button {
                        isPasswordVisible.toggle()
                    } label: {
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
    }

    private var usageSummarySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("使用状況")
                .font(.system(size: 18, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            HStack(alignment: .top, spacing: 10) {
                UsageMetricTile(
                    title: "保存タグ",
                    valueText: "\(personalSavedURLCount)"
                )
                UsageMetricTile(
                    title: "共有タグ",
                    valueText: "\(model.sharedTags.count)"
                )
                UsageMetricTile(
                    title: "グループ",
                    valueText: "\(model.sharedTagGroups.count)"
                )
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

            AppActionButton(tone: .primary, enabled: !isWorking) {
                isShowingContactSheet = true
            } label: {
                Text("問い合わせ")
            }
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
        AppPanel {
            Text("有料コース")
                .font(.system(size: 20, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)

            Text("購入後はサーバー検証が完了したコースだけ反映されます。")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                paidCourseButton(title: "Standard 月額", plan: .standard, period: .monthly)
                paidCourseButton(title: "Standard 年払い", plan: .standard, period: .yearly)
                paidCourseButton(title: "Pro 月額", plan: .pro, period: .monthly)
                paidCourseButton(title: "Pro 年払い", plan: .pro, period: .yearly)
            }
        }
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
                .font(.system(size: 15, weight: .heavy, design: .rounded))
                .lineLimit(2)
                .minimumScaleFactor(0.82)
        }
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
        guard !isWorking else { return }
        switch result {
        case .failure(let error):
            if (error as? ASAuthorizationError)?.code == .canceled { return }
            model.showProfileStatusMessage("Appleサインインを完了できませんでした。")
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
                model.showProfileStatusMessage("Appleサインインの認証情報を検証できませんでした。")
                return
            }
            pendingAppleNonce = nil
            pendingAppleState = nil
            isWorking = true
            Task {
                await model.signInWithAppleForSharedTagCloud(idToken: token, nonce: nonce)
                isWorking = false
            }
        }
    }

    private func startAppleSignIn() {
        guard !isWorking else { return }
        let nonce = randomAppleNonce()
        let state = randomAppleNonce(byteCount: 24)
        pendingAppleNonce = nonce
        pendingAppleState = state

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

private struct ContactSupportSheet: View {
    @Environment(\.dismiss) private var dismiss

    let initialEmail: String
    let initialName: String
    let onSend: (String, String, String) async -> ContactSupportSendResult

    @State private var email: String
    @State private var name: String
    @State private var message = ""
    @State private var didAttemptSubmit = false
    @State private var isSending = false
    @State private var sendError: String?

    init(
        initialEmail: String,
        initialName: String,
        onSend: @escaping (String, String, String) async -> ContactSupportSendResult
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
                            isSending = true
                            sendError = nil
                            Task {
                                let result = await onSend(email, name, message)
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
