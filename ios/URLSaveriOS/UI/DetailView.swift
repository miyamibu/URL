import SwiftUI
import UIKit

struct DetailView: View {
    let entryID: Int64
    @ObservedObject var model: URLSaverAppModel

    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    @State private var titleText = ""
    @State private var memoText = ""
    @State private var isEditingTitle = false
    @State private var isShowingMemoEditor = false
    @State private var isShowingDeleteConfirm = false
    @State private var isShowingDetails = false
    @State private var isRetryingMetadata = false
    @State private var isShowingLocalTagEditor = false
    @State private var isShowingSharedTagEditor = false
    @State private var isShowingCollectionEditor = false
    @State private var isRemovingTag = false

    private var entry: URLRecord? {
        model.entry(for: entryID)
    }

    private var assignedSharedTags: [SharedTagSummary] {
        guard model.sharedTagCloudState.isConfigured else { return [] }
        return model.loadSharedTagsForEntry(entryID: entryID)
    }

    private var assignedLocalTags: [LocalTagSummary] {
        model.loadLocalTagsForEntry(entryID: entryID)
    }

    private var currentCollection: CollectionSummary? {
        guard let entry else { return nil }
        return model.collections.first { $0.id == entry.collectionID }
    }

    var body: some View {
        ScreenContainer {
            VStack(spacing: 0) {
                ScreenHeader(
                    title: "詳細",
                    leadingButton: ScreenHeaderButton(
                        icon: "arrow.left",
                        accessibilityLabel: "戻る",
                        action: { dismiss() }
                    ),
                    trailingButtons: entry == nil ? [] : [
                        ScreenHeaderButton(
                            icon: "arrow.clockwise",
                            accessibilityLabel: "読み込み",
                            action: { reloadMetadata() }
                        )
                    ]
                )

                if let entry {
                    GeometryReader { proxy in
                        ScrollView(showsIndicators: false) {
                            VStack(spacing: 14) {
                                if let thumbnailURL = entry.thumbnailURL, let url = URL(string: thumbnailURL) {
                                    AppPanel(strong: true, padded: false) {
                                        DetailThumbnailImage(url: url, serviceType: entry.serviceType)
                                    }
                                }

                                AppPanel(strong: true) {
                                    HStack(alignment: .top, spacing: 12) {
                                        VStack(alignment: .leading, spacing: 12) {
                                            if isEditingTitle {
                                                TextField("タイトル", text: $titleText, axis: .vertical)
                                                    .font(.system(size: 23, weight: .heavy, design: .rounded))
                                                    .textFieldStyle(.plain)
                                                    .padding(.horizontal, 14)
                                                    .padding(.vertical, 16)
                                                    .background(
                                                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                                                            .fill(Color.white.opacity(0.06))
                                                    )
                                                    .overlay(
                                                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                                                            .stroke(AppPalette.outline, lineWidth: 1.5)
                                                    )
                                            } else {
                                                Text(preferredDisplayTitle(for: entry))
                                                    .font(.system(size: 23, weight: .heavy, design: .rounded))
                                                    .foregroundStyle(Color.white.opacity(0.94))
                                                    .multilineTextAlignment(.leading)
                                                    .lineLimit(3)
                                                    .fixedSize(horizontal: false, vertical: true)
                                            }

                                            HStack(spacing: 10) {
                                                ServiceBadgeView(serviceType: entry.serviceType, badgeImageURL: entry.badgeImageURL)
                                                Text(detailServiceLabel(for: entry))
                                                    .font(.system(size: 18, weight: .medium))
                                                    .foregroundStyle(Color.white.opacity(0.75))
                                                    .lineLimit(1)
                                            }
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)

                                        Spacer(minLength: 10)

                                        Button {
                                            if isEditingTitle {
                                                isEditingTitle = false
                                                titleText = entry.userTitle ?? ""
                                            } else {
                                                isEditingTitle = true
                                                titleText = entry.userTitle ?? ""
                                            }
                                        } label: {
                                            Image(systemName: isEditingTitle ? "xmark" : "pencil")
                                                .font(.system(size: 14, weight: .bold))
                                                .foregroundStyle(Color.white.opacity(0.85))
                                                .frame(width: 42, height: 42)
                                        }
                                    }

                                    if isEditingTitle {
                                        HStack(spacing: 10) {
                                            AppActionButton {
                                                isEditingTitle = false
                                                titleText = entry.userTitle ?? ""
                                            } label: {
                                                Text("キャンセル")
                                            }

                                            AppActionButton(tone: .primary) {
                                                Task {
                                                    if await model.saveTitle(entryID: entryID, text: titleText) {
                                                        isEditingTitle = false
                                                    }
                                                }
                                            } label: {
                                                Text("保存")
                                            }
                                        }
                                    }
                                }

                                if let metadataMessage = detailMetadataMessage(for: entry) {
                                    AppPanel {
                                        Text(metadataMessage.title)
                                            .font(.system(size: 21, weight: .heavy, design: .rounded))
                                            .foregroundStyle(AppPalette.textPrimary)

                                        if let body = metadataMessage.body {
                                            Text(body)
                                                .font(.system(size: 16, weight: .medium))
                                                .foregroundStyle(AppPalette.textSecondary)
                                        }

                                        if metadataMessage.showsRetry {
                                            AppActionButton {
                                                guard !isRetryingMetadata else { return }
                                                isRetryingMetadata = true
                                                Task {
                                                    await model.retryMetadata(entryID: entryID)
                                                    isRetryingMetadata = false
                                                }
                                            } label: {
                                                if isRetryingMetadata {
                                                    ProgressView().tint(Color.white)
                                                } else {
                                                    Text("再取得")
                                                }
                                            }
                                        }
                                    }
                                }

                                if isSocialPostContentService(entry.serviceType) {
                                    if let body = socialDetailBodyText(for: entry) {
                                        AppPanel {
                                            ExpandableDetailBodySection(label: bodyLabel(for: entry), text: body)
                                        }
                                    }
                                } else {
                                    if let summary = entry.bodySummary, !summary.isEmpty {
                                        AppPanel {
                                            DetailSectionLabel(text: summaryLabel(for: entry))
                                            Text(summary)
                                                .font(.system(size: 17, weight: .medium))
                                                .foregroundStyle(AppPalette.textSecondary)
                                        }
                                    }

                                    if let body = detailBodyText(for: entry) {
                                        AppPanel {
                                            DetailSectionLabel(text: bodyLabel(for: entry))
                                            Text(body)
                                                .font(.system(size: 17, weight: .medium))
                                                .foregroundStyle(AppPalette.textSecondary)
                                        }
                                    }
                                }

                                VStack(spacing: 10) {
                                    HStack(spacing: 10) {
                                        AppActionButton(tone: .primary) {
                                            if let url = URL(string: entry.openURL) {
                                                openURL(url)
                                            }
                                        } label: {
                                            Text("開く")
                                        }

                                        AppActionButton {
                                            UIPasteboard.general.string = entry.openURL
                                        } label: {
                                            Text("コピー")
                                        }
                                    }

                                    HStack(spacing: 10) {
                                        AppActionButton {
                                            Task {
                                                if entry.recordState == .archived {
                                                    _ = await model.restoreFromArchive(entryID: entry.id)
                                                } else {
                                                    await model.archive(entryID: entry.id)
                                                }
                                                dismiss()
                                            }
                                        } label: {
                                            Text(entry.recordState == .archived ? "アーカイブ解除" : "アーカイブ")
                                        }

                                        AppActionButton(tone: .danger, enabled: entry.recordState == .active) {
                                            isShowingDeleteConfirm = true
                                        } label: {
                                            Text("削除")
                                        }
                                    }

                                    AppActionButton {
                                        memoText = entry.memo
                                        isShowingMemoEditor = true
                                    } label: {
                                        Text("メモを編集")
                                    }
                                }

                                AppPanel {
                                    DetailSectionLabel(text: "保存先")
                                    HStack(spacing: 10) {
                                        DetailTagValuePill(
                                            text: currentCollection?.name ?? "受信箱",
                                            isEmpty: false,
                                            canRemove: false,
                                            onRemove: nil
                                        )
                                        DetailTagEditButton(action: { isShowingCollectionEditor = true })
                                            .frame(maxWidth: 120)
                                    }
                                }

                                AppPanel {
                                    DetailSectionLabel(text: "メモ")
                                    Text(entry.memo.isEmpty ? "メモはまだありません" : entry.memo)
                                        .font(.system(size: 17, weight: .medium))
                                        .foregroundStyle(entry.memo.isEmpty ? AppPalette.textSecondary : AppPalette.textPrimary)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                }

                                HStack(alignment: .top, spacing: 10) {
                                    DetailTagSummaryPanel(
                                        title: "タグ",
                                        emptyText: "まだタグは付いていません",
                                        tags: assignedLocalTags.map { tag in
                                            DetailTagSummaryItem(
                                                id: "local-\(tag.id)",
                                                name: tag.name,
                                                canRemove: !isRemovingTag,
                                                onRemove: {
                                                    guard !isRemovingTag else { return }
                                                    isRemovingTag = true
                                                    Task {
                                                        _ = await model.removeEntry(entryID, fromLocalTag: tag.id)
                                                        isRemovingTag = false
                                                    }
                                                }
                                            )
                                        },
                                        onEdit: { isShowingLocalTagEditor = true }
                                    )

                                    if model.sharedTagCloudState.isConfigured {
                                        DetailTagSummaryPanel(
                                            title: "共有タグ",
                                            emptyText: "まだ共有タグは付いていません",
                                            tags: assignedSharedTags.map { tag in
                                                DetailTagSummaryItem(
                                                    id: "shared-\(tag.remoteTagID)",
                                                    name: tag.name,
                                                    canRemove: !isRemovingTag && (tag.currentUserRole == .owner || tag.currentUserRole == .editor),
                                                    onRemove: {
                                                        guard !isRemovingTag else { return }
                                                        isRemovingTag = true
                                                        Task {
                                                            _ = await model.removeEntry(entryID, fromSharedTag: tag.remoteTagID)
                                                            isRemovingTag = false
                                                        }
                                                    }
                                                )
                                            },
                                            onEdit: { isShowingSharedTagEditor = true }
                                        )
                                    }
                                }

                                AppPanel {
                                    Button {
                                        withAnimation(.spring(response: 0.24, dampingFraction: 0.88)) {
                                            isShowingDetails.toggle()
                                        }
                                    } label: {
                                        HStack {
                                            DetailSectionLabel(text: "詳細情報")
                                            Spacer()
                                            Image(systemName: isShowingDetails ? "chevron.up" : "chevron.down")
                                                .font(.system(size: 12, weight: .bold))
                                                .foregroundStyle(AppPalette.textSecondary)
                                        }
                                    }
                                    .buttonStyle(.plain)

                                    if isShowingDetails {
                                        VStack(alignment: .leading, spacing: 12) {
                                            DetailValue(
                                                label: "受信したURL",
                                                value: entry.originalURL,
                                                onTap: {
                                                    UIPasteboard.general.string = entry.originalURL
                                                }
                                            )
                                            DetailValue(label: "保存時刻", value: DateFormatters.detailTimestamp.string(from: entry.createdAt))
                                        }
                                    }
                                }
                            }
                            .frame(width: max(proxy.size.width - 32, 0))
                            .padding(.horizontal, 16)
                            .padding(.bottom, 24)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    }
                } else {
                    VStack {
                        Spacer()
                        AppPanel {
                            Text("項目が見つかりません")
                                .font(.system(size: 24, weight: .heavy, design: .rounded))
                                .foregroundStyle(AppPalette.textPrimary)
                                .frame(maxWidth: .infinity)
                            AppActionButton(tone: .primary) {
                                dismiss()
                            } label: {
                                Text("一覧に戻る")
                            }
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 16)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $isShowingMemoEditor) {
            MemoEditorSheet(
                memoText: $memoText,
                onSave: {
                    Task {
                        if await model.saveMemo(entryID: entryID, text: memoText) {
                            isShowingMemoEditor = false
                        }
                    }
                }
            )
            .presentationDetents([.height(430)])
            .presentationCornerRadius(28)
        }
        .sheet(isPresented: $isShowingLocalTagEditor) {
            EntryLocalTagAssignmentSheet(
                model: model,
                entryID: entryID
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.hidden)
            .presentationCornerRadius(32)
        }
        .sheet(isPresented: $isShowingCollectionEditor) {
            EntryCollectionAssignmentSheet(
                model: model,
                entryID: entryID,
                currentCollectionID: entry?.collectionID
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.hidden)
            .presentationCornerRadius(32)
        }
        .sheet(isPresented: $isShowingSharedTagEditor) {
            if model.sharedTagCloudState.isConfigured {
                EntrySharedTagAssignmentSheet(
                    model: model,
                    entryID: entryID,
                    onDidChange: {}
                )
                .presentationDetents([.large])
                .presentationDragIndicator(.hidden)
                .presentationCornerRadius(32)
            }
        }
        .confirmationDialog("削除しますか？", isPresented: $isShowingDeleteConfirm, titleVisibility: .visible) {
            Button("削除する", role: .destructive) {
                Task {
                    await model.markPendingDelete(entryID: entryID)
                    dismiss()
                }
            }
            Button("キャンセル", role: .cancel) {}
        } message: {
            Text("このURLは削除待ちに移動します。5秒以内なら元に戻せます。")
        }
        .onAppear {
            titleText = entry?.userTitle ?? ""
        }
    }

    private func reloadMetadata() {
        guard !isRetryingMetadata else { return }
        isRetryingMetadata = true
        Task {
            await model.refreshMetadata(entryID: entryID)
            isRetryingMetadata = false
        }
    }

    private func summaryLabel(for entry: URLRecord) -> String {
        switch entry.fetchedBodyKind {
        case .youtubeDescription:
            return "概要欄の要点"
        case .instagramCaption:
            return "キャプションの要点"
        case .xPostText:
            return "投稿内容の要点"
        case .webDescription:
            return "概要"
        case .webExcerpt:
            return "本文抜粋"
        case .none:
            return "要点"
        }
    }

    private func bodyLabel(for entry: URLRecord) -> String {
        if isSocialPostContentService(entry.serviceType) {
            return "投稿内容"
        }

        switch entry.fetchedBodyKind {
        case .youtubeDescription:
            return "投稿内容"
        case .instagramCaption:
            return "投稿内容"
        case .xPostText:
            return "投稿内容"
        case .webDescription:
            return "概要"
        case .webExcerpt:
            return "本文抜粋"
        case .none:
            return "内容"
        }
    }

    private func detailBodyText(for entry: URLRecord) -> String? {
        guard let body = entry.fetchedBody, !body.isEmpty else { return nil }
        if body == entry.bodySummary {
            return nil
        }
        return body
    }

    private func socialDetailBodyText(for entry: URLRecord) -> String? {
        firstNonBlank(entry.fetchedBody, entry.description, entry.bodySummary)
    }

    private func firstNonBlank(_ values: String?...) -> String? {
        values
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty }
    }
}

private struct DetailThumbnailImage: View {
    let url: URL
    let serviceType: ServiceType

    var body: some View {
        if serviceType == .tiktok {
            tiktokThumbnail
        } else {
            originalThumbnail
        }
    }

    private var originalThumbnail: some View {
        AsyncImage(url: url) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            Rectangle().fill(AppPalette.surfaceSoft)
        }
        .frame(height: 220)
        .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
    }

    private var tiktokThumbnail: some View {
        AsyncImage(url: url) { image in
            image.resizable().scaledToFit()
        } placeholder: {
            Rectangle().fill(AppPalette.surfaceSoft)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 220)
        .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
    }
}

private struct ExpandableDetailBodySection: View {
    let label: String
    let text: String

    @State private var availableWidth: CGFloat = 0
    @State private var isExpanded = false

    private var needsExpansion: Bool {
        availableWidth > 0 && Self.estimatedLineCount(for: text, width: availableWidth) >= 5
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                DetailSectionLabel(text: label)
                Spacer(minLength: 0)

                if needsExpansion {
                    Button {
                        withAnimation(.spring(response: 0.24, dampingFraction: 0.88)) {
                            isExpanded.toggle()
                        }
                    } label: {
                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(AppPalette.textSecondary)
                            .frame(width: 36, height: 36)
                    }
                    .accessibilityLabel(isExpanded ? "\(label)を閉じる" : "\(label)を表示")
                }
            }

            if !needsExpansion || isExpanded {
                Text(text)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(AppPalette.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            GeometryReader { proxy in
                Color.clear
                    .onAppear {
                        availableWidth = proxy.size.width
                    }
                    .onChange(of: proxy.size.width) { _, newWidth in
                        availableWidth = newWidth
                    }
            }
        )
    }

    private static func estimatedLineCount(for text: String, width: CGFloat) -> Int {
        guard width > 0 else { return 0 }

        let font = UIFont.systemFont(ofSize: 17, weight: .medium)
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byWordWrapping
        let boundingSize = CGSize(width: width, height: CGFloat.greatestFiniteMagnitude)
        let rect = NSString(string: text).boundingRect(
            with: boundingSize,
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [
                .font: font,
                .paragraphStyle: paragraph,
            ],
            context: nil
        )

        return max(1, Int(ceil(rect.height / font.lineHeight)))
    }
}

private struct MemoEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var memoText: String
    let onSave: () -> Void

    var body: some View {
        ScreenContainer {
            VStack(alignment: .leading, spacing: 18) {
                Capsule()
                    .fill(AppPalette.outlineSoft)
                    .frame(width: 72, height: 8)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 10)

                Text("メモを編集")
                    .font(.system(size: 24, weight: .heavy, design: .rounded))
                    .foregroundStyle(AppPalette.textPrimary)

                TextEditor(text: $memoText)
                    .font(.system(size: 17, weight: .medium))
                    .scrollContentBackground(.hidden)
                    .padding(16)
                    .background(AppPalette.background, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(AppPalette.outlineSoft, lineWidth: 1.5)
                    )
                    .frame(minHeight: 220)

                HStack(spacing: 10) {
                    AppActionButton {
                        dismiss()
                    } label: {
                        Text("キャンセル")
                    }

                    AppActionButton(tone: .primary) {
                        onSave()
                    } label: {
                        Text("保存")
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
    }
}

private struct EntryCollectionAssignmentSheet: View {
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var model: URLSaverAppModel
    let entryID: Int64
    let currentCollectionID: Int64?

    @State private var newCollectionName = ""
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
                    Text("保存先を変更")
                        .font(.system(size: 24, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                    Spacer()
                    Button("閉じる") { dismiss() }
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(AppPalette.primaryStrong)
                }

                AppPanel {
                    Text("新しいコレクション")
                        .font(.system(size: 18, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)

                    TextField(
                        "",
                        text: $newCollectionName,
                        prompt: Text("コレクション名").foregroundStyle(AppPalette.textMuted)
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

                    AppActionButton(tone: .primary, enabled: !newCollectionName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isWorking) {
                        guard !isWorking else { return }
                        isWorking = true
                        Task {
                            if let collection = await model.createCollection(name: newCollectionName),
                               await model.assignCollectionAndCreateLocalTag(entryID: entryID, collection: collection) {
                                newCollectionName = ""
                                dismiss()
                            }
                            isWorking = false
                        }
                    } label: {
                        Text("作成して移動")
                    }
                }

                AppPanel {
                    Text("保存先")
                        .font(.system(size: 18, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)

                    LazyVGrid(
                        columns: [GridItem(.adaptive(minimum: 150), spacing: 10, alignment: .leading)],
                        alignment: .leading,
                        spacing: 10
                    ) {
                        ForEach(model.collections) { collection in
                            LocalTagAssignmentPill(
                                name: collection.name,
                                actionTitle: currentCollectionID == collection.id ? "選択中" : "移動",
                                isWorking: isWorking || currentCollectionID == collection.id,
                                onAction: {
                                    guard !isWorking, currentCollectionID != collection.id else { return }
                                    isWorking = true
                                    Task {
                                        if await model.assignCollectionAndCreateLocalTag(entryID: entryID, collection: collection) {
                                            dismiss()
                                        }
                                        isWorking = false
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
    }
}

private struct EntryLocalTagAssignmentSheet: View {
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var model: URLSaverAppModel
    let entryID: Int64

    @State private var assignedTags: [LocalTagSummary] = []
    @State private var newTagName = ""
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
                    Text("タグを編集")
                        .font(.system(size: 24, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)
                    Spacer()
                    Button("閉じる") { dismiss() }
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(AppPalette.primaryStrong)
                }

                AppPanel {
                    Text("新しいタグ")
                        .font(.system(size: 18, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)

                    TextField(
                        "",
                        text: $newTagName,
                        prompt: Text("タグ名").foregroundStyle(AppPalette.textMuted)
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
                            if let tag = await model.createLocalTag(name: newTagName) {
                                _ = await model.addEntry(entryID, toLocalTag: tag.id)
                                newTagName = ""
                                reloadAssignedTags()
                            }
                            isWorking = false
                        }
                    } label: {
                        Text("タグを作成して追加")
                    }
                }

                if !assignedTags.isEmpty {
                    AppPanel {
                        Text("現在のタグ")
                            .font(.system(size: 18, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textPrimary)

                        LazyVGrid(
                            columns: [GridItem(.adaptive(minimum: 150), spacing: 10, alignment: .leading)],
                            alignment: .leading,
                            spacing: 10
                        ) {
                            ForEach(assignedTags) { tag in
                                LocalTagAssignmentPill(
                                    name: tag.name,
                                    actionTitle: "外す",
                                    isWorking: isWorking,
                                    onAction: {
                                        guard !isWorking else { return }
                                        isWorking = true
                                        Task {
                                            if await model.removeEntry(entryID, fromLocalTag: tag.id) {
                                                reloadAssignedTags()
                                            }
                                            isWorking = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                AppPanel {
                    Text("追加できるタグ")
                        .font(.system(size: 18, weight: .heavy, design: .rounded))
                        .foregroundStyle(AppPalette.textPrimary)

                    let unassignedTags = model.localTags.filter { summary in
                        !assignedTags.contains(where: { $0.id == summary.id })
                    }

                    if unassignedTags.isEmpty {
                        Text("追加できるタグはありません")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(AppPalette.textSecondary)
                    } else {
                        LazyVGrid(
                            columns: [GridItem(.adaptive(minimum: 150), spacing: 10, alignment: .leading)],
                            alignment: .leading,
                            spacing: 10
                        ) {
                            ForEach(unassignedTags) { tag in
                                LocalTagAssignmentPill(
                                    name: tag.name,
                                    actionTitle: "追加",
                                    isWorking: isWorking,
                                    onAction: {
                                        guard !isWorking else { return }
                                        isWorking = true
                                        Task {
                                            if await model.addEntry(entryID, toLocalTag: tag.id) {
                                                reloadAssignedTags()
                                            }
                                            isWorking = false
                                        }
                                    }
                                )
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
    }

    private func reloadAssignedTags() {
        assignedTags = model.loadLocalTagsForEntry(entryID: entryID)
    }
}

private struct LocalTagAssignmentPill: View {
    let name: String
    let actionTitle: String
    let isWorking: Bool
    let onAction: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(name)
                .font(.system(size: 20, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
                .frame(maxWidth: 148, alignment: .leading)

            Button(action: onAction) {
                Text(actionTitle)
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
        .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AppPalette.outlineSoft, lineWidth: 1.5)
        )
        .fixedSize(horizontal: true, vertical: false)
    }
}

private struct DetailSectionLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 15, weight: .heavy, design: .monospaced))
            .foregroundStyle(AppPalette.textSecondary)
            .textCase(.uppercase)
    }
}

private struct DetailTagSummaryItem: Identifiable {
    let id: String
    let name: String
    let canRemove: Bool
    let onRemove: () -> Void
}

private struct DetailTagSummaryPanel: View {
    let title: String
    let emptyText: String
    let tags: [DetailTagSummaryItem]
    let onEdit: () -> Void

    var body: some View {
        AppPanel {
            VStack(alignment: .leading, spacing: 9) {
                DetailSectionLabel(text: title)
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
                    .frame(maxWidth: .infinity, alignment: .leading)

                DetailTagEditButton(action: onEdit)

                if tags.isEmpty {
                    DetailTagValuePill(text: emptyText, isEmpty: true, canRemove: false, onRemove: nil)
                } else {
                    ScrollView(.vertical, showsIndicators: false) {
                        VStack(alignment: .leading, spacing: 7) {
                            ForEach(tags) { tag in
                                DetailTagValuePill(
                                    text: tag.name,
                                    isEmpty: false,
                                    canRemove: tag.canRemove,
                                    onRemove: tag.onRemove
                                )
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                    }
                    .frame(minHeight: 42, maxHeight: 91)
                }

                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, minHeight: 154, maxHeight: 154, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, minHeight: 194, maxHeight: 194, alignment: .topLeading)
    }
}

private struct DetailTagEditButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text("編集")
                .font(.system(size: 15, weight: .heavy, design: .rounded))
                .foregroundStyle(Color.white.opacity(0.95))
                .lineLimit(1)
                .minimumScaleFactor(0.85)
                .frame(maxWidth: .infinity, minHeight: 42, maxHeight: 42)
                .background(AppPalette.panelStrong, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("編集")
    }
}

private struct DetailTagValuePill: View {
    let text: String
    let isEmpty: Bool
    let canRemove: Bool
    let onRemove: (() -> Void)?

    var body: some View {
        HStack(spacing: 5) {
            Text(text)
                .font(.system(size: isEmpty ? 13 : 15, weight: isEmpty ? .medium : .bold))
                .foregroundStyle(isEmpty ? AppPalette.textSecondary : AppPalette.textPrimary)
                .lineLimit(2)
                .minimumScaleFactor(0.78)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, minHeight: 42, maxHeight: 42)

            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .heavy))
                        .foregroundStyle(AppPalette.textSecondary)
                        .frame(width: 30, height: 30)
                        .background(AppPalette.background.opacity(0.7), in: Circle())
                }
                .buttonStyle(.plain)
                .disabled(!canRemove)
                .accessibilityLabel("\(text)を外す")
            }
        }
        .padding(.leading, 8)
        .padding(.trailing, onRemove == nil ? 8 : 4)
        .background(AppPalette.surfaceSoft, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct DetailValue: View {
    let label: String
    let value: String
    var onTap: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.system(size: 14, weight: .bold, design: .monospaced))
                .foregroundStyle(AppPalette.textSecondary)
            Text(value)
                .font(.system(size: 15, weight: .medium, design: .monospaced))
                .foregroundStyle(AppPalette.textPrimary)
                .textSelection(.enabled)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            onTap?()
        }
    }
}

private func isSocialPostContentService(_ serviceType: ServiceType) -> Bool {
    switch serviceType {
    case .youtube, .tiktok, .x, .instagram:
        return true
    case .all, .web:
        return false
    }
}

private func detailServiceLabel(for entry: URLRecord) -> String {
    switch entry.serviceType {
    case .youtube, .x, .instagram, .tiktok:
        if let authorName = detailNonBlank(entry.fetchedAuthorName) {
            return authorName
        }
        if entry.serviceType != .youtube,
           let authorName = detailNonBlank(entry.fetchedTitle) {
            return authorName
        }
    case .all, .web:
        break
    }
    return serviceLabel(for: entry)
}

private func detailNonBlank(_ value: String?) -> String? {
    guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
        return nil
    }
    return trimmed
}

private func detailMetadataMessage(for entry: URLRecord) -> (title: String, body: String?, showsRetry: Bool)? {
    switch entry.metadataState {
    case .pending:
        return ("情報を更新中です", nil, false)
    case .failed:
        return ("一時的に情報を取得できませんでした", MetadataStatusText.technicalErrorText(for: entry.metadataError), true)
    case .unavailable:
        return (MetadataStatusText.detailText(for: entry) ?? "このURLは自動取得できません", MetadataStatusText.technicalErrorText(for: entry.metadataError), true)
    case .ready:
        let lacksFetchedContent = entry.fetchedTitle == nil && entry.fetchedBody == nil && entry.thumbnailURL == nil && entry.badgeImageURL == nil
        let lacksSocialBadge = [.youtube, .tiktok, .x, .instagram].contains(entry.serviceType) && entry.badgeImageURL == nil
        if lacksFetchedContent {
            return ("情報を更新できます", "再取得すると、取得できる場合はサムネイルや本文を表示します。", true)
        }
        if lacksSocialBadge {
            return ("アイコンを更新できます", "再取得すると、取得できる場合は投稿主のプロフィール画像を表示します。", true)
        }
        return nil
    }
}
