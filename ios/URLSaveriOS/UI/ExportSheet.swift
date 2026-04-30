import SwiftUI
import UniformTypeIdentifiers

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
    @State private var isExporting = false
    @State private var errorMessage: String?
    @State private var successMessage: String?
    @State private var shareItems: [Any] = []
    @State private var isShowingShareSheet = false
    @State private var isShowingFileExporter = false
    @State private var fileExportDocument: ExportFileDocument?
    @State private var fileExportType: UTType = .zip
    @State private var fileExportDefaultName = "urlsaver-export"

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

    var body: some View {
        ScreenContainer {
            VStack(spacing: 0) {
                exportHeader

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
                                    dateFromInput = "2026-04-30"
                                    dateToInput = "2026-04-30"
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
            }
        }
        .sheet(isPresented: $isShowingShareSheet) {
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

            AppActionButton(tone: .primary, enabled: !isExporting) {
                exportArchive()
            } label: {
                if isExporting {
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

            Text("ChatGPTや\nCodexやClaudeに\n共有も可能だよ！")
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
            isExporting = true
            Task {
                do {
                    let archive = try await model.prepareExportArchive(request: request)
                    let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent(archive.fileName)
                    try archive.bytes.write(to: fileURL, options: [.atomic])
                    switch selectedDestination {
                    case .shareSheet:
                        shareItems = [fileURL]
                        isShowingShareSheet = true
                    case .file:
                        fileExportDocument = ExportFileDocument(data: archive.bytes)
                        fileExportType = archive.mimeType == "application/json" ? .json : .zip
                        fileExportDefaultName = archive.fileName
                        isShowingFileExporter = true
                    }
                    isExporting = false
                } catch {
                    isExporting = false
                    errorMessage = (error as? LocalizedError)?.errorDescription ?? "エクスポートできませんでした。"
                }
            }
        } catch {
            isExporting = false
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
