import SwiftUI
import UIKit
import UniformTypeIdentifiers

enum LaunchAdVisibility {
    // Trial launch switch. Set this back to true when banner ads should return.
    static let showBannerAds = false
    static let mainNotificationBottomPadding: CGFloat = showBannerAds ? 172 : 96
}

enum AppPalette {
    static var background: Color {
        let style = HomeBackgroundPreferenceStore.load()
        return Color(UIColor(hex: AppDisplayColorPalette.hexColors(for: style)[1]))
    }
    static let surface = dynamic(light: 0xFFFFFF, dark: 0x101B2B)
    static let surfaceSoft = dynamic(light: 0xE7EDF5, dark: 0x17263A)
    static let panelStrong = dynamic(light: 0x1B2532, dark: 0x1B2532)
    static let outline = dynamic(light: 0x31435A, dark: 0x4E6684)
    static let outlineSoft = dynamic(light: 0xC5D0DD, dark: 0x26384F)
    static let textPrimary = dynamic(light: 0x102033, dark: 0xEEF5FF)
    static let textSecondary = dynamic(light: 0x506176, dark: 0xAFC0D5)
    static let textMuted = dynamic(light: 0x6B798E, dark: 0x8EA1BA)
    static let primary = dynamic(light: 0x65B0FF, dark: 0x65B0FF)
    static let primaryStrong = dynamic(light: 0x1F6FD1, dark: 0x8BC3FF)
    static let primarySurface = dynamic(light: 0x22436B, dark: 0x143558)
    static let selectedSurface = dynamic(light: 0xF2F7FF, dark: 0x173A5E)
    static let secondarySurface = dynamic(light: 0x144339, dark: 0x0E3B34)
    static let danger = dynamic(light: 0xB5261E, dark: 0xFF776A)
    static let dangerSurface = dynamic(light: 0x5B1B17, dark: 0x4B1714)
    static let warning = dynamic(light: 0x9B2C20, dark: 0xFF8F7F)
    static let webAccent = Color(red: 52 / 255, green: 199 / 255, blue: 89 / 255)
    static let youtubeAccent = Color(red: 1, green: 59 / 255, blue: 48 / 255)
    static let instagramAccent = Color(red: 228 / 255, green: 64 / 255, blue: 95 / 255)
    static let xAccent = Color(red: 152 / 255, green: 167 / 255, blue: 184 / 255)

    private static func dynamic(light: Int, dark: Int) -> Color {
        Color(UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

extension UIColor {
    convenience init(hex: Int) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}

enum AppThemeMode: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: return "システム"
        case .light: return "ライト"
        case .dark: return "ダーク"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

enum EntryListDisplayMode: String {
    case rich
    case compact
}

enum HomeEntryLayout: String, CaseIterable, Identifiable {
    case list
    case thumbnailGrid

    var id: String { rawValue }

    var label: String {
        switch self {
        case .list: return "一覧"
        case .thumbnailGrid: return "2列"
        }
    }
}

enum EntryListSortOption: String, CaseIterable, Identifiable {
    case addedDate
    case title

    var id: String { rawValue }

    var label: String {
        switch self {
        case .addedDate: return "追加日"
        case .title: return "タイトル"
        }
    }
}

func canRenameSharedTag(currentUserRole: SharedTagMemberRole?) -> Bool {
    currentUserRole == .owner || currentUserRole == .editor
}

func canContributeSharedTagToGroup(
    groupRole: SharedTagMemberRole?,
    tagRole: SharedTagMemberRole?
) -> Bool {
    groupRole != nil && tagRole == .owner
}

func sortedEntriesForDisplay(
    _ entries: [URLRecord],
    by option: EntryListSortOption,
    ascending: Bool
) -> [URLRecord] {
    entries.sorted { lhs, rhs in
        let primaryOrder: ComparisonResult
        switch option {
        case .addedDate:
            primaryOrder = lhs.createdAt == rhs.createdAt
                ? .orderedSame
                : (lhs.createdAt < rhs.createdAt ? .orderedAscending : .orderedDescending)
        case .title:
            primaryOrder = lhs.effectiveTitle.localizedStandardCompare(rhs.effectiveTitle)
        }

        if primaryOrder != .orderedSame {
            return ascending ? primaryOrder == .orderedAscending : primaryOrder == .orderedDescending
        }
        if lhs.createdAt != rhs.createdAt {
            return ascending ? lhs.createdAt < rhs.createdAt : lhs.createdAt > rhs.createdAt
        }
        return ascending ? lhs.id < rhs.id : lhs.id > rhs.id
    }
}

enum AppButtonTone {
    case primary
    case secondary
    case danger
}

enum AppIconMetrics {
    static let chrome: CGFloat = 22
    static let serviceBadge: CGFloat = 16
    static let bottomAction: CGFloat = 15
}

struct ScreenHeaderButton: Identifiable {
    let id = UUID()
    let icon: String
    let title: String?
    let accessibilityLabel: String
    let action: () -> Void

    init(
        icon: String,
        title: String? = nil,
        accessibilityLabel: String,
        action: @escaping () -> Void
    ) {
        self.icon = icon
        self.title = title
        self.accessibilityLabel = accessibilityLabel
        self.action = action
    }
}

enum AppDisplayColorPalette {
    static func hexColors(for style: HomeBackgroundStyle) -> [Int] {
        switch style {
        case .current: return [0xF7F3EC, 0xEDE7DF, 0xE9E1D7]
        case .sakura: return [0xFCF3F6, 0xF5E6EC, 0xEFDBE4]
        case .lavender: return [0xF6F3FC, 0xECE7F5, 0xE3DDEC]
        case .mint: return [0xF2F9F5, 0xE4F1E9, 0xD8E9DE]
        }
    }
    static func colors(for style: HomeBackgroundStyle) -> [Color] {
        hexColors(for: style).map { Color(UIColor(hex: $0)) }
    }
    static var colorScheme: ColorScheme { .light }
}

struct AppDisplayBackground: View {
    @AppStorage(HomeBackgroundPreferenceStore.key) private var colorRaw = HomeBackgroundStyle.defaultStyle.rawValue
    var body: some View {
        LinearGradient(
            colors: AppDisplayColorPalette.colors(for: HomeBackgroundPreferenceStore.resolve(rawValue: colorRaw)),
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea()
    }
}

struct ScreenContainer<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ZStack(alignment: .top) {
            AppDisplayBackground()
            content
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

struct ScreenHeader: View {
    let title: String
    let leadingButton: ScreenHeaderButton?
    let trailingButtons: [ScreenHeaderButton]
    let onTitleTap: (() -> Void)?
    let trailingAccessory: AnyView?

    init(
        title: String,
        leadingButton: ScreenHeaderButton?,
        trailingButtons: [ScreenHeaderButton],
        onTitleTap: (() -> Void)? = nil,
        trailingAccessory: AnyView? = nil
    ) {
        self.title = title
        self.leadingButton = leadingButton
        self.trailingButtons = trailingButtons
        self.onTitleTap = onTitleTap
        self.trailingAccessory = trailingAccessory
    }

    var body: some View {
        HStack(spacing: 2) {
            if let leadingButton {
                IconChromeButton(button: leadingButton)
            }

            if let onTitleTap {
                Button(action: onTitleTap) {
                    headerTitle
                }
                .buttonStyle(.plain)
                .accessibilityLabel(title)
            } else {
                headerTitle
            }

            Spacer(minLength: 4)

            ForEach(trailingButtons) { button in
                IconChromeButton(button: button)
            }
            if let trailingAccessory {
                trailingAccessory
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, 6)
        .padding(.top, 6)
        .padding(.bottom, 10)
    }

    private var headerTitle: some View {
        Text(title)
            .font(.system(.largeTitle, design: .rounded).weight(.heavy))
            .foregroundStyle(AppPalette.textPrimary)
            .lineLimit(2)
            .fixedSize(horizontal: false, vertical: true)
            .layoutPriority(1)
    }
}

private struct IconChromeButton: View {
    let button: ScreenHeaderButton

    var body: some View {
        Button(action: button.action) {
            HStack(spacing: button.title == nil ? 0 : 6) {
                Image(systemName: button.icon)
                    .font(.system(.title3).weight(.semibold))
                if let title = button.title {
                    Text(title)
                        .font(.system(.callout, design: .rounded).weight(.heavy))
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .foregroundStyle(AppPalette.textPrimary)
            .frame(minWidth: 44, minHeight: 48)
            .padding(.horizontal, button.title == nil ? 0 : 4)
        }
        .accessibilityLabel(button.accessibilityLabel)
    }
}

struct AppPanel<Content: View>: View {
    let strong: Bool
    let padded: Bool
    let content: Content

    init(
        strong: Bool = false,
        padded: Bool = true,
        @ViewBuilder content: () -> Content
    ) {
        self.strong = strong
        self.padded = padded
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            content
        }
        .padding(padded ? 18 : 0)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(strong ? AppPalette.panelStrong : AppPalette.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(strong ? AppPalette.panelStrong : AppPalette.outline, lineWidth: strong ? 0 : 1)
        )
    }
}

struct AppActionButton<Label: View>: View {
    let tone: AppButtonTone
    let enabled: Bool
    let readableDisabled: Bool
    let action: () -> Void
    let label: Label

    init(
        tone: AppButtonTone = .secondary,
        enabled: Bool = true,
        readableDisabled: Bool = false,
        action: @escaping () -> Void,
        @ViewBuilder label: () -> Label
    ) {
        self.tone = tone
        self.enabled = enabled
        self.readableDisabled = readableDisabled
        self.action = action
        self.label = label()
    }

    @ViewBuilder
    var body: some View {
        if readableDisabled {
            actionButton.buttonStyle(ReadableActionButtonStyle())
        } else {
            actionButton.buttonStyle(.plain)
        }
    }

    private var actionButton: some View {
        Button(action: action) {
            label
                .font(.system(.body, design: .rounded).weight(.bold))
                .foregroundStyle(foregroundColor)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(backgroundColor, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .stroke(borderColor, lineWidth: borderWidth)
                )
        }
        .disabled(!enabled)
        .opacity(enabled || readableDisabled ? 1 : 0.5)
    }

    private var backgroundColor: Color {
        if !enabled && readableDisabled { return AppPalette.surfaceSoft }
        switch tone {
        case .primary:
            return AppPalette.primary
        case .secondary:
            return AppPalette.panelStrong
        case .danger:
            return AppPalette.dangerSurface
        }
    }

    private var foregroundColor: Color {
        if !enabled && readableDisabled { return AppPalette.textSecondary }
        switch tone {
        case .primary:
            return AppPalette.textPrimary
        case .secondary:
            return Color.white.opacity(0.95)
        case .danger:
            return Color(red: 1.0, green: 0.45, blue: 0.40)
        }
    }

    private var borderColor: Color {
        if !enabled && readableDisabled { return AppPalette.outlineSoft }
        switch tone {
        case .primary:
            return AppPalette.primary
        case .secondary:
            return AppPalette.outline
        case .danger:
            return AppPalette.warning
        }
    }

    private var borderWidth: CGFloat {
        !enabled && readableDisabled ? 0.5 : (tone == .primary ? 0 : 1.5)
    }
}

private struct ReadableActionButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.85 : 1)
    }
}

struct EntryCardView: View {
    let entry: URLRecord
    let timestampLabel: String
    let displayMode: EntryListDisplayMode
    let cardWidth: CGFloat?
    let selected: Bool
    let localTagNames: [String]
    let localTagTints: [String: TagTint]
    let gridInfoVisible: Bool?
    let footerContent: (() -> AnyView)?

    init(
        entry: URLRecord,
        timestampLabel: String,
        displayMode: EntryListDisplayMode,
        cardWidth: CGFloat? = nil,
        selected: Bool = false,
        localTagNames: [String] = [],
        localTagTints: [String: TagTint] = [:],
        gridInfoVisible: Bool? = nil,
        footerContent: (() -> AnyView)? = nil
    ) {
        self.entry = entry
        self.timestampLabel = timestampLabel
        self.displayMode = displayMode
        self.cardWidth = cardWidth
        self.selected = selected
        self.localTagNames = localTagNames
        self.localTagTints = localTagTints
        self.gridInfoVisible = gridInfoVisible
        self.footerContent = footerContent
    }

    var body: some View {
        AppPanel(padded: false) {
            VStack(alignment: .leading, spacing: 0) {
                if let gridInfoVisible {
                    homeGridThumbnail(infoVisible: gridInfoVisible)
                } else if displayMode == .rich, let thumbnailURL = entry.thumbnailURL, let url = URL(string: thumbnailURL) {
                    RemoteURLImage(url: url) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Rectangle().fill(AppPalette.surfaceSoft)
                    }
                    .frame(width: cardWidth, height: thumbnailHeight)
                    .frame(maxWidth: .infinity)
                    .clipped()
                    .clipShape(
                        UnevenRoundedRectangle(
                            topLeadingRadius: 24,
                            bottomLeadingRadius: 0,
                            bottomTrailingRadius: 0,
                            topTrailingRadius: 24,
                            style: .continuous
                        )
                    )
                }

                if gridInfoVisible != false {
                    entryInformation
                }

                if let footerContent {
                    footerContent()
                        .padding(.horizontal, 20)
                        .padding(.bottom, 16)
                }
            }
        }
        .frame(width: cardWidth)
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(selected ? AppPalette.primary : Color.clear, lineWidth: selected ? 3 : 0)
        )
        .overlay(alignment: .topTrailing) {
            if selected {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 28, weight: .bold))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(AppPalette.textPrimary, AppPalette.primary)
                    .padding(14)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityValue(selected ? "選択中" : "")
    }

    private var visibleLocalTagNames: [String] {
        entryCardVisibleLocalTagNames(localTagNames)
    }

    private var entryInformation: some View {
        HStack(alignment: .top, spacing: 12) {
            RoundedRectangle(cornerRadius: 999, style: .continuous)
                .fill(serviceAccentGradient(for: entry.serviceType))
                .frame(width: 4, height: 48)
                .padding(.top, 3)

            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 8) {
                    ServiceBadgeView(serviceType: entry.serviceType, badgeImageURL: entry.badgeImageURL, reloadKey: entry.metadataFetchedAt)

                    if visibleLocalTagNames.isEmpty {
                        HStack(spacing: 8) {
                            if let headerText = entryCardDistinctHeaderText(for: entry) {
                                Text(headerText)
                                    .font(.system(.body).weight(.medium))
                                    .foregroundStyle(AppPalette.textSecondary)
                                    .lineLimit(gridInfoVisible == nil ? 2 : 1)
                                    .truncationMode(.tail)
                                    .fixedSize(horizontal: false, vertical: true)
                            }

                            if entry.contentContext != .standard {
                                Text(contentContextLabel(for: entry.contentContext))
                                    .font(.system(.caption, design: .rounded).weight(.bold))
                                    .foregroundStyle(AppPalette.textSecondary)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(AppPalette.surfaceSoft, in: Capsule())
                                    .overlay(Capsule().stroke(AppPalette.outline, lineWidth: 1.5))
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    } else {
                        EntryLocalTagFlow(tagNames: visibleLocalTagNames, tints: localTagTints)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }

                Text(preferredDisplayTitle(for: entry))
                    .font(.system(.headline, design: .rounded).weight(.bold))
                    .foregroundStyle(AppPalette.textPrimary)
                    .lineLimit(gridInfoVisible == nil ? (displayMode == .rich ? 3 : 2) : 2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)

                if (displayMode == .rich || gridInfoVisible == true),
                   let summary = entry.description ?? entry.bodySummary,
                   !summary.isEmpty {
                    Text(summary)
                        .font(.system(.subheadline).weight(.medium))
                        .foregroundStyle(AppPalette.textMuted)
                        .lineLimit(gridInfoVisible == nil ? 3 : 2)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if let metadataText = MetadataStatusText.listText(for: entry) {
                    Text(metadataText)
                        .font(.system(.footnote).weight(.semibold))
                        .foregroundStyle(metadataTextColor(for: entry.metadataState))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func homeGridThumbnail(infoVisible: Bool) -> some View {
        let tileSize = cardWidth ?? 180
        return Group {
            if let thumbnailURL = entry.thumbnailURL, let url = URL(string: thumbnailURL) {
                RemoteURLImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    homeGridThumbnailPlaceholder
                }
            } else {
                homeGridThumbnailPlaceholder
            }
        }
        .frame(width: tileSize, height: tileSize)
        .frame(maxWidth: .infinity)
        .clipped()
        .clipShape(
            UnevenRoundedRectangle(
                topLeadingRadius: 24,
                bottomLeadingRadius: infoVisible ? 0 : 24,
                bottomTrailingRadius: infoVisible ? 0 : 24,
                topTrailingRadius: 24,
                style: .continuous
            )
        )
        .accessibilityHidden(true)
    }

    private var homeGridThumbnailPlaceholder: some View {
        Rectangle()
            .fill(AppPalette.surfaceSoft)
            .overlay {
                Image(systemName: "photo")
                    .font(.system(size: 28, weight: .regular))
                    .foregroundStyle(AppPalette.textMuted)
                    .accessibilityHidden(true)
            }
    }

    private var thumbnailHeight: CGFloat {
        guard let cardWidth else { return 220 }
        return min(max(cardWidth * 0.55, 196), 224)
    }

    private func timestampDate(for entry: URLRecord) -> Date {
        if timestampLabel == "アーカイブ", let archivedAt = entry.archivedAt {
            return archivedAt
        }
        return entry.createdAt
    }
}

private struct EntryLocalTagFlow: View {
    let tagNames: [String]
    let tints: [String: TagTint]

    var body: some View {
        EntryLocalTagFlowLayout(horizontalSpacing: 6, verticalSpacing: 6) {
            ForEach(tagNames, id: \.self) { tagName in
                let tint = tints[tagName] ?? .standard
                Text(tagName)
                    .font(.system(.caption, design: .rounded).weight(.bold))
                    .foregroundStyle(tint == .standard ? AppPalette.primaryStrong : Color(UIColor(hex: 0x283144)))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .frame(maxWidth: 150)
                    .background(tint.color ?? AppPalette.selectedSurface, in: Capsule())
                    .overlay(
                        Capsule()
                            .stroke(tint == .standard ? AppPalette.primaryStrong.opacity(0.45) : Color(UIColor(hex: 0x283144)).opacity(0.2), lineWidth: 1)
                    )
            }
        }
    }
}

private struct EntryLocalTagFlowLayout: Layout {
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

private func entryCardHeaderFallbackText(for entry: URLRecord) -> String {
    if let authorName = nonBlank(entry.fetchedAuthorName) {
        return authorName
    }
    return serviceLabel(for: entry)
}

func entryCardDistinctHeaderText(for entry: URLRecord) -> String? {
    let header = entryCardHeaderFallbackText(for: entry).trimmingCharacters(in: .whitespacesAndNewlines)
    let title = preferredDisplayTitle(for: entry).trimmingCharacters(in: .whitespacesAndNewlines)
    guard !header.isEmpty,
          header.compare(title, options: [.caseInsensitive, .diacriticInsensitive]) != .orderedSame else {
        return nil
    }
    return header
}

func entryCardVisibleLocalTagNames(_ localTagNames: [String]) -> [String] {
    localTagNames
        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        .filter { !$0.isEmpty }
        .reduce(into: [String]()) { result, tagName in
            if !result.contains(where: { $0.caseInsensitiveCompare(tagName) == .orderedSame }) {
                result.append(tagName)
            }
        }
}

func entryCardUsesLocalTagHeader(_ localTagNames: [String]) -> Bool {
    !entryCardVisibleLocalTagNames(localTagNames).isEmpty
}

func entryCardAccessibilityLabel(for entry: URLRecord, localTagNames: [String] = []) -> String {
    var parts = [preferredDisplayTitle(for: entry)]
    let visibleLocalTagNames = entryCardVisibleLocalTagNames(localTagNames)
    if visibleLocalTagNames.isEmpty {
        if let headerText = entryCardDistinctHeaderText(for: entry) {
            parts.append(headerText)
        }
    } else {
        parts.append("自作タグ: \(visibleLocalTagNames.joined(separator: ", "))")
    }
    if let metadataText = MetadataStatusText.listText(for: entry) {
        parts.append(metadataText)
    }
    return parts.joined(separator: "。")
}

struct SwipeableEntryCard: View {
    let entry: URLRecord
    let displayMode: EntryListDisplayMode
    let cardWidth: CGFloat
    let localTagNames: [String]
    var localTagTints: [String: TagTint] = [:]
    var gridInfoVisible: Bool? = nil
    let onTap: () -> Void
    let onArchive: () -> Void
    let onDelete: () -> Void
    let onLongPress: () -> Void

    @State private var dragOffset: CGFloat = 0

    var body: some View {
        ZStack {
            swipeActionBackground

            EntryCardView(
                entry: entry,
                timestampLabel: "保存",
                displayMode: displayMode,
                cardWidth: cardWidth,
                localTagNames: localTagNames,
                localTagTints: localTagTints,
                gridInfoVisible: gridInfoVisible
            )
            .offset(x: dragOffset)
            .contentShape(Rectangle())
        }
        .overlay {
            HorizontalCardSwipeRecognizer(
                cardWidth: cardWidth,
                onTap: onTap,
                onLongPress: onLongPress,
                onChanged: { offset in
                    dragOffset = offset
                },
                onEnded: { offset, velocity in
                    handleSwipeEnd(offset: offset, velocity: velocity)
                }
            )
        }
        .frame(width: cardWidth)
        .clipped()
        .animation(.interactiveSpring(response: 0.26, dampingFraction: 0.88), value: dragOffset)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(entryCardAccessibilityLabel(for: entry, localTagNames: localTagNames))
        .accessibilityHint("ダブルタップで詳細を開きます。追加の操作からアーカイブまたは削除を選べます。")
        .accessibilityAction { onTap() }
        .accessibilityAction(named: Text("アーカイブ")) { onArchive() }
        .accessibilityAction(named: Text("削除")) { onDelete() }
    }

    @ViewBuilder
    private var swipeActionBackground: some View {
        if gridInfoVisible == nil {
            HStack(spacing: 0) {
                SwipeActionHint(
                    systemImage: "archivebox.fill",
                    label: "アーカイブ",
                    color: AppPalette.secondarySurface,
                    alignment: .leading
                )

                Spacer(minLength: 0)

                SwipeActionHint(
                    systemImage: "trash.fill",
                    label: "削除",
                    color: AppPalette.dangerSurface,
                    alignment: .trailing
                )
            }
            .frame(width: cardWidth)
            .opacity(dragOffset == 0 ? 0 : 1)
        } else {
            HStack(spacing: 0) {
                gridSwipeActionHint(
                    systemImage: "archivebox.fill",
                    label: "アーカイブ",
                    color: AppPalette.secondarySurface
                )
                gridSwipeActionHint(
                    systemImage: "trash.fill",
                    label: "削除",
                    color: AppPalette.dangerSurface
                )
            }
            .frame(width: cardWidth)
            .opacity(dragOffset == 0 ? 0 : 1)
            .clipped()
            .accessibilityHidden(true)
        }
    }

    private func gridSwipeActionHint(systemImage: String, label: String, color: Color) -> some View {
        VStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .bold))
            Text(label)
                .font(.system(.caption2, design: .rounded).weight(.heavy))
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
        .foregroundStyle(Color.white.opacity(0.95))
        .frame(width: cardWidth / 2)
        .frame(maxHeight: .infinity)
        .background(color)
        .accessibilityHidden(true)
    }

    private func handleSwipeEnd(offset: CGFloat, velocity: CGFloat) {
        let triggerWidth = swipeActionTriggerWidth(containerWidth: cardWidth)
        let projectedOffset = offset + velocity * 0.12

        if offset >= triggerWidth || (projectedOffset >= triggerWidth && offset >= triggerWidth * 0.75) {
            onArchive()
        } else if offset <= -triggerWidth || (projectedOffset <= -triggerWidth && offset <= -triggerWidth * 0.75) {
            onDelete()
        }

        dragOffset = 0
    }
}

struct SwipeableArchivedEntryCard: View {
    let entry: URLRecord
    let displayMode: EntryListDisplayMode
    let cardWidth: CGFloat
    let localTagNames: [String]
    var localTagTints: [String: TagTint] = [:]
    let onTap: () -> Void
    let onRestore: () -> Void
    let onDelete: () -> Void

    @State private var dragOffset: CGFloat = 0

    var body: some View {
        ZStack {
            HStack(spacing: 0) {
                SwipeActionHint(
                    systemImage: "arrow.uturn.backward.circle.fill",
                    label: "戻す",
                    color: AppPalette.secondarySurface,
                    alignment: .leading
                )

                Spacer(minLength: 0)

                SwipeActionHint(
                    systemImage: "trash.fill",
                    label: "削除",
                    color: AppPalette.dangerSurface,
                    alignment: .trailing
                )
            }
            .frame(width: cardWidth)
            .opacity(dragOffset == 0 ? 0 : 1)

            EntryCardView(
                entry: entry,
                timestampLabel: "アーカイブ",
                displayMode: displayMode,
                cardWidth: cardWidth,
                localTagNames: localTagNames,
                localTagTints: localTagTints
            )
            .offset(x: dragOffset)
            .contentShape(Rectangle())
        }
        .overlay {
            HorizontalCardSwipeRecognizer(
                cardWidth: cardWidth,
                onTap: onTap,
                onLongPress: {},
                onChanged: { offset in
                    dragOffset = offset
                },
                onEnded: { offset, velocity in
                    handleSwipeEnd(offset: offset, velocity: velocity)
                }
            )
        }
        .frame(width: cardWidth)
        .clipped()
        .animation(.interactiveSpring(response: 0.26, dampingFraction: 0.88), value: dragOffset)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(entryCardAccessibilityLabel(for: entry, localTagNames: localTagNames))
        .accessibilityHint("ダブルタップで詳細を開きます。追加の操作から戻すまたは削除を選べます。")
        .accessibilityAction { onTap() }
        .accessibilityAction(named: Text("戻す")) { onRestore() }
        .accessibilityAction(named: Text("削除")) { onDelete() }
    }

    private func handleSwipeEnd(offset: CGFloat, velocity: CGFloat) {
        let triggerWidth = swipeActionTriggerWidth(containerWidth: cardWidth)
        let projectedOffset = offset + velocity * 0.12

        if offset >= triggerWidth || (projectedOffset >= triggerWidth && offset >= triggerWidth * 0.75) {
            onRestore()
        } else if offset <= -triggerWidth || (projectedOffset <= -triggerWidth && offset <= -triggerWidth * 0.75) {
            onDelete()
        }

        dragOffset = 0
    }
}

struct SwipeableSharedTagEntryCard: View {
    let entry: URLRecord
    let displayMode: EntryListDisplayMode
    let cardWidth: CGFloat
    let canRemove: Bool
    let onTap: () -> Void
    let onRemove: () -> Void

    @State private var dragOffset: CGFloat = 0

    var body: some View {
        ZStack {
            HStack(spacing: 0) {
                SwipeActionHint(
                    systemImage: "link.badge.minus",
                    label: "外す",
                    color: AppPalette.dangerSurface,
                    alignment: .leading
                )

                Spacer(minLength: 0)

                SwipeActionHint(
                    systemImage: "link.badge.minus",
                    label: "外す",
                    color: AppPalette.dangerSurface,
                    alignment: .trailing
                )
            }
            .frame(width: cardWidth)
            .opacity(canRemove && dragOffset != 0 ? 1 : 0)

            EntryCardView(
                entry: entry,
                timestampLabel: "保存",
                displayMode: displayMode,
                cardWidth: cardWidth
            )
            .offset(x: dragOffset)
            .contentShape(Rectangle())
        }
        .overlay {
            HorizontalCardSwipeRecognizer(
                cardWidth: cardWidth,
                onTap: onTap,
                onLongPress: {},
                onChanged: { offset in
                    dragOffset = canRemove ? offset : 0
                },
                onEnded: { offset, velocity in
                    handleSwipeEnd(offset: offset, velocity: velocity)
                }
            )
        }
        .frame(width: cardWidth)
        .clipped()
        .animation(.interactiveSpring(response: 0.26, dampingFraction: 0.88), value: dragOffset)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(entryCardAccessibilityLabel(for: entry))
        .accessibilityHint(canRemove ? "ダブルタップで詳細を開きます。追加の操作から共有タグを外せます。" : "ダブルタップで詳細を開きます。")
        .accessibilityAction { onTap() }
        .accessibilityAction(named: Text("外す")) {
            if canRemove {
                onRemove()
            }
        }
    }

    private func handleSwipeEnd(offset: CGFloat, velocity: CGFloat) {
        defer { dragOffset = 0 }
        guard canRemove else { return }

        let triggerWidth = swipeActionTriggerWidth(containerWidth: cardWidth)
        let projectedOffset = offset + velocity * 0.12
        let shouldRemove =
            abs(offset) >= triggerWidth ||
            (abs(projectedOffset) >= triggerWidth && abs(offset) >= triggerWidth * 0.75)

        if shouldRemove {
            onRemove()
        }
    }
}

enum CardSwipeAxis: Equatable {
    case horizontal
    case vertical
}

func cardSwipeAxis(horizontal: CGFloat, vertical: CGFloat) -> CardSwipeAxis? {
    let horizontalDistance = abs(horizontal)
    let verticalDistance = abs(vertical)
    let startDistance: CGFloat = 18
    let horizontalDominance: CGFloat = 1.35

    if horizontalDistance < startDistance && verticalDistance < startDistance {
        return nil
    }

    if horizontalDistance >= startDistance && horizontalDistance >= verticalDistance * horizontalDominance {
        return .horizontal
    }

    return .vertical
}

func cardSwipeShouldBegin(horizontal: CGFloat, vertical: CGFloat, velocityX: CGFloat, velocityY: CGFloat) -> Bool {
    if cardSwipeAxis(horizontal: horizontal, vertical: vertical) == .horizontal {
        return true
    }

    let horizontalVelocity = abs(velocityX)
    let verticalVelocity = abs(velocityY)
    return horizontalVelocity >= 120 && horizontalVelocity >= verticalVelocity * 1.35
}

private struct HorizontalCardSwipeRecognizer: UIViewRepresentable {
    let cardWidth: CGFloat
    let onTap: () -> Void
    let onLongPress: () -> Void
    let onChanged: (CGFloat) -> Void
    let onEnded: (CGFloat, CGFloat) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(cardWidth: cardWidth, onTap: onTap, onLongPress: onLongPress, onChanged: onChanged, onEnded: onEnded)
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.backgroundColor = .clear

        let panRecognizer = UIPanGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handlePan(_:))
        )
        panRecognizer.delegate = context.coordinator
        panRecognizer.cancelsTouchesInView = false
        panRecognizer.delaysTouchesBegan = false
        panRecognizer.delaysTouchesEnded = false
        view.addGestureRecognizer(panRecognizer)

        let tapRecognizer = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTap(_:))
        )
        tapRecognizer.delegate = context.coordinator
        tapRecognizer.cancelsTouchesInView = false
        tapRecognizer.delaysTouchesBegan = false
        tapRecognizer.delaysTouchesEnded = false
        tapRecognizer.require(toFail: panRecognizer)
        view.addGestureRecognizer(tapRecognizer)

        let longPressRecognizer = UILongPressGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleLongPress(_:))
        )
        longPressRecognizer.delegate = context.coordinator
        longPressRecognizer.minimumPressDuration = 0.45
        longPressRecognizer.cancelsTouchesInView = true
        tapRecognizer.require(toFail: longPressRecognizer)
        view.addGestureRecognizer(longPressRecognizer)

        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.cardWidth = cardWidth
        context.coordinator.onTap = onTap
        context.coordinator.onLongPress = onLongPress
        context.coordinator.onChanged = onChanged
        context.coordinator.onEnded = onEnded
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var cardWidth: CGFloat
        var onTap: () -> Void
        var onLongPress: () -> Void
        var onChanged: (CGFloat) -> Void
        var onEnded: (CGFloat, CGFloat) -> Void
        private var lastLongPressAt = Date.distantPast

        init(
            cardWidth: CGFloat,
            onTap: @escaping () -> Void,
            onLongPress: @escaping () -> Void,
            onChanged: @escaping (CGFloat) -> Void,
            onEnded: @escaping (CGFloat, CGFloat) -> Void
        ) {
            self.cardWidth = cardWidth
            self.onTap = onTap
            self.onLongPress = onLongPress
            self.onChanged = onChanged
            self.onEnded = onEnded
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            if gestureRecognizer is UITapGestureRecognizer {
                return true
            }

            if gestureRecognizer is UILongPressGestureRecognizer {
                return true
            }

            guard let recognizer = gestureRecognizer as? UIPanGestureRecognizer,
                  let view = recognizer.view else {
                return false
            }

            let translation = recognizer.translation(in: view)
            let velocity = recognizer.velocity(in: view)
            return cardSwipeShouldBegin(
                horizontal: translation.x,
                vertical: translation.y,
                velocityX: velocity.x,
                velocityY: velocity.y
            )
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }

        @objc func handlePan(_ recognizer: UIPanGestureRecognizer) {
            guard let view = recognizer.view else { return }
            let translation = recognizer.translation(in: view)
            let velocity = recognizer.velocity(in: view)
            let limit = swipeVisualOffsetLimit(containerWidth: cardWidth)
            let offset = min(max(translation.x, -limit), limit)

            switch recognizer.state {
            case .began, .changed:
                onChanged(offset)
            case .ended:
                onEnded(offset, velocity.x)
            case .cancelled, .failed:
                onEnded(0, 0)
            default:
                break
            }
        }

        @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended else { return }
            guard Date().timeIntervalSince(lastLongPressAt) > 0.6 else { return }
            onTap()
        }

        @objc func handleLongPress(_ recognizer: UILongPressGestureRecognizer) {
            guard recognizer.state == .began else { return }
            lastLongPressAt = Date()
            onLongPress()
        }
    }
}

private struct SwipeActionHint: View {
    let systemImage: String
    let label: String
    let color: Color
    let alignment: HorizontalAlignment

    var body: some View {
        VStack(alignment: alignment, spacing: 8) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .bold))

            Text(label)
                .font(.system(size: 13, weight: .heavy, design: .rounded))
        }
        .foregroundStyle(Color.white.opacity(0.95))
        .frame(width: 116)
        .frame(maxHeight: .infinity, alignment: frameAlignment)
        .padding(.horizontal, 20)
        .background(color, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
    }

    private var frameAlignment: Alignment {
        if alignment == HorizontalAlignment.leading {
            return .leading
        }
        return .trailing
    }
}

func swipeActionTriggerWidth(containerWidth: CGFloat) -> CGFloat {
    containerWidth * 0.4
}

func swipeVisualOffsetLimit(containerWidth: CGFloat) -> CGFloat {
    max(swipeActionTriggerWidth(containerWidth: containerWidth), containerWidth * 0.46)
}

struct ServiceBadgeView: View {
    let serviceType: ServiceType
    let badgeImageURL: String?
    let reloadKey: Date?

    init(serviceType: ServiceType, badgeImageURL: String? = nil, reloadKey: Date? = nil) {
        self.serviceType = serviceType
        self.badgeImageURL = badgeImageURL
        self.reloadKey = reloadKey
    }

    var body: some View {
        if let badgeImageURL = displayBadgeImageURL, let url = URL(string: badgeImageURL) {
            RemoteURLImage(url: url) { image in
                image
                    .resizable()
                    .scaledToFill()
            } placeholder: {
                fallbackBadge
            }
            .id(reloadKey)
            .frame(width: 36, height: 36)
            .clipShape(Circle())
            .background(AppPalette.surfaceSoft, in: Circle())
        } else {
            fallbackBadge
        }
    }

    private var displayBadgeImageURL: String? {
        guard let badgeImageURL else { return nil }
        if serviceType == .youtube,
           !badgeImageURL.contains("yt3.ggpht.com"),
           !badgeImageURL.contains("yt3.googleusercontent.com") {
            return nil
        }
        return badgeImageURL
    }

    private var fallbackBadge: some View {
        Circle()
            .fill(AppPalette.surfaceSoft)
            .frame(width: 36, height: 36)
            .overlay(
                Image(systemName: badgeSymbol)
                    .font(.system(size: AppIconMetrics.serviceBadge, weight: .semibold))
                    .foregroundStyle(AppPalette.textSecondary)
            )
    }

    private var badgeSymbol: String {
        switch serviceType {
        case .youtube:
            return "play.rectangle.fill"
        case .instagram:
            return "camera.fill"
        case .x:
            return "bubble.left.and.text.bubble.right"
        case .tiktok:
            return "music.note"
        case .web, .all:
            return "globe"
        }
    }
}

private struct RemoteURLImage<Content: View, Placeholder: View>: View {
    let url: URL
    @ViewBuilder let content: (Image) -> Content
    @ViewBuilder let placeholder: () -> Placeholder

    @State private var image: UIImage?
    @State private var loadedURL: URL?

    var body: some View {
        Group {
            if let image, loadedURL == url {
                content(Image(uiImage: image))
            } else {
                placeholder()
            }
        }
        .task(id: url) {
            await loadImage()
        }
    }

    private func loadImage() async {
        await MainActor.run {
            if loadedURL != url {
                image = nil
            }
        }
        guard image == nil || loadedURL != url else { return }

        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.setValue(Self.browserLikeUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("image/avif,image/webp,image/apng,image/*,*/*;q=0.8", forHTTPHeaderField: "Accept")
        let imageHost = url.host?.lowercased() ?? ""
        if imageHost.contains("tiktok") || imageHost.contains("tiktokcdn") || imageHost.contains("byteoversea") {
            request.setValue("https://www.tiktok.com/", forHTTPHeaderField: "Referer")
        } else if imageHost.contains("instagram") || imageHost.hasSuffix("cdninstagram.com") || imageHost.hasSuffix("fbcdn.net") {
            request.setValue("https://www.instagram.com/", forHTTPHeaderField: "Referer")
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse,
                  (200...299).contains(httpResponse.statusCode),
                  let loadedImage = UIImage(data: data) else {
                return
            }
            await MainActor.run {
                image = loadedImage
                loadedURL = url
            }
        } catch {
            return
        }
    }

    private static var browserLikeUserAgent: String {
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    }
}

private enum TopFilterItem: Hashable {
    case localTag(Int64)
    case service(ServiceType)

    var token: String {
        switch self {
        case .localTag(let id): return "local:\(id)"
        case .service(let service): return "service:\(service.rawValue)"
        }
    }

    var localTagID: Int64? {
        if case .localTag(let id) = self {
            return id
        }
        return nil
    }
}

private func mergeTopFilterItems(
    baseItems: [TopFilterItem],
    storedTokens: [String]
) -> [TopFilterItem] {
    guard !storedTokens.isEmpty else { return baseItems }

    let baseByToken = Dictionary(uniqueKeysWithValues: baseItems.map { ($0.token, $0) })
    var orderedItems = storedTokens.compactMap { baseByToken[$0] }

    baseItems.forEach { item in
        guard !orderedItems.contains(item) else { return }
        if case .localTag = item {
            let insertIndex = orderedItems.firstIndex { current in
                if case .localTag = current {
                    return false
                }
                return true
            } ?? orderedItems.endIndex
            orderedItems.insert(item, at: insertIndex)
        } else {
            orderedItems.append(item)
        }
    }
    return orderedItems
}

enum TagTint: String, CaseIterable, Identifiable {
    case standard, rose, peach, butter, mint, aqua, sky, lavender, lilac
    var id: String { rawValue }
    var label: String {
        switch self {
        case .standard: return "標準"
        case .rose: return "さくら"
        case .peach: return "もも"
        case .butter: return "クリーム"
        case .mint: return "ミント"
        case .aqua: return "アクア"
        case .sky: return "そら"
        case .lavender: return "ラベンダー"
        case .lilac: return "ライラック"
        }
    }
    var color: Color? {
        let hex: Int
        switch self {
        case .standard: return nil
        case .rose: hex = 0xF4D9E4
        case .peach: hex = 0xF6DEC9
        case .butter: hex = 0xF2E8BA
        case .mint: hex = 0xD9EADA
        case .aqua: hex = 0xD3ECE8
        case .sky: hex = 0xD7E6F7
        case .lavender: hex = 0xE2DCF4
        case .lilac: hex = 0xEDD8EE
        }
        return Color(UIColor(hex: hex))
    }
}

@MainActor
final class TagTintPreferences: ObservableObject {
    static let shared = TagTintPreferences()
    static let storageKey = "tag_tints_local_v1"
    @Published private(set) var values: [String: String]
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        values = defaults.dictionary(forKey: Self.storageKey) as? [String: String] ?? [:]
    }
    static func localKey(_ id: Int64) -> String { "local:\(id)" }
    static func sharedKey(accountID: String?, remoteID: String) -> String? {
        guard let accountID, !accountID.isEmpty, !remoteID.isEmpty else { return nil }
        return "shared:\(accountID):\(remoteID)"
    }
    func tint(for key: String?) -> TagTint {
        guard let key, let raw = values[key] else { return .standard }
        return TagTint(rawValue: raw) ?? .standard
    }
    func save(_ tint: TagTint, for key: String) {
        values[key] = tint.rawValue
        defaults.set(values, forKey: Self.storageKey)
    }
}

struct TagAppearanceSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let key: String
    let allowsRename: Bool
    let onSaveName: ((String) async -> Bool)?
    @State private var name: String
    @State private var tint: TagTint
    @State private var isSaving = false
    @State private var error: String?

    init(
        name: String,
        key: String,
        title: String? = nil,
        allowsRename: Bool = false,
        onSaveName: ((String) async -> Bool)? = nil
    ) {
        self.title = title ?? (allowsRename ? "自作タグを編集" : "共有タグの色")
        self.key = key
        self.allowsRename = allowsRename
        self.onSaveName = onSaveName
        _name = State(initialValue: name)
        _tint = State(initialValue: TagTintPreferences.shared.tint(for: key))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    if allowsRename {
                        TextField("タグ名", text: $name)
                            .textFieldStyle(.roundedBorder)
                            .onChange(of: name) { _, _ in error = nil }
                    }
                    Text("プレビュー")
                        .font(.headline)
                    FilterChipButton(label: name.isEmpty ? "タグ" : name, selected: false, tint: tint)
                    Text("色")
                        .font(.headline)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 76))], spacing: 14) {
                        ForEach(TagTint.allCases) { choice in
                            Button { tint = choice } label: {
                                VStack(spacing: 7) {
                                    Circle()
                                        .fill(choice.color ?? AppPalette.panelStrong)
                                        .frame(width: 44, height: 44)
                                        .overlay {
                                            if tint == choice {
                                                Image(systemName: "checkmark")
                                                    .font(.system(size: 18, weight: .bold))
                                                    .foregroundStyle(choice == .standard ? Color.white : Color(UIColor(hex: 0x283144)))
                                            }
                                        }
                                    Text(choice.label).font(.caption)
                                }
                                .frame(minWidth: 70, minHeight: 72)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(choice.label)
                            .accessibilityValue(tint == choice ? "選択中" : "未選択")
                            .accessibilityAddTraits(tint == choice ? .isSelected : [])
                        }
                    }
                    Text(allowsRename ? "色はこの端末だけに保存されます。" : "この端末での表示だけを変えます。共有タグの名前や他の人の表示は変わりません。")
                        .font(.callout).foregroundStyle(AppPalette.textSecondary)
                    if let error { Text(error).foregroundStyle(AppPalette.danger) }
                }
                .padding(24)
            }
            .background(AppDisplayBackground())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("キャンセル") { dismiss() }.disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        Task { @MainActor in
                            isSaving = true
                            if allowsRename, let onSaveName, !(await onSaveName(name)) {
                                error = "タグ名を保存できませんでした。40文字以内で、ほかのタグと異なる名前にしてください。"
                                isSaving = false
                                return
                            }
                            TagTintPreferences.shared.save(tint, for: key)
                            isSaving = false
                            dismiss()
                        }
                    }
                    .disabled(isSaving || (allowsRename && name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
                }
            }
        }
    }
}

struct ServiceFilterRow: View {
    @ObservedObject private var tints = TagTintPreferences.shared
    @AppStorage("top_filter_order_v1") private var topFilterOrderRaw = ""
    @State private var draggingFilterToken: String?

    @Binding var selectedService: ServiceType
    @Binding var selectedLocalTagID: Int64?
    let showsCreateChip: Bool
    let createAction: (() -> Void)?
    let localTags: [LocalTagSummary]
    let onSelectLocalTag: (Int64?) -> Void
    let onRenameLocalTag: (LocalTagSummary) -> Void
    let onReorderLocalTags: ([Int64]) -> Void

    init(
        selectedService: Binding<ServiceType>,
        selectedLocalTagID: Binding<Int64?> = .constant(nil),
        showsCreateChip: Bool = false,
        createAction: (() -> Void)? = nil,
        localTags: [LocalTagSummary] = [],
        onSelectLocalTag: @escaping (Int64?) -> Void = { _ in },
        onRenameLocalTag: @escaping (LocalTagSummary) -> Void = { _ in },
        onReorderLocalTags: @escaping ([Int64]) -> Void = { _ in }
    ) {
        _selectedService = selectedService
        _selectedLocalTagID = selectedLocalTagID
        self.showsCreateChip = showsCreateChip
        self.createAction = createAction
        self.localTags = localTags
        self.onSelectLocalTag = onSelectLocalTag
        self.onRenameLocalTag = onRenameLocalTag
        self.onReorderLocalTags = onReorderLocalTags
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if showsCreateChip {
                    FilterChipButton(
                        label: "+",
                        selected: false,
                        action: createAction
                    )
                }

                ForEach(orderedItems, id: \.token) { item in
                    let localTag = item.localTagID.flatMap { id in
                        localTags.first { tag in tag.id == id }
                    }
                    FilterChipButton(
                        label: label(for: item, localTag: localTag),
                        selected: isSelected(item),
                        tint: tints.tint(for: localTag.map { TagTintPreferences.localKey($0.id) })
                    )
                    .highPriorityGesture(
                        TapGesture(count: 2).exclusively(before: TapGesture(count: 1)).onEnded { gesture in
                            switch gesture {
                            case .first:
                                if let localTag { onRenameLocalTag(localTag) }
                                else { select(item) }
                            case .second:
                                select(item)
                            }
                        }
                    )
                    .accessibilityAction { select(item) }
                    .accessibilityAction(named: Text("タグ名と色を編集")) {
                        if let localTag { onRenameLocalTag(localTag) }
                    }
                    .accessibilityHint("長押ししてドラッグすると並び替えできます")
                    .onDrag {
                        draggingFilterToken = item.token
                        return NSItemProvider(object: item.token as NSString)
                    }
                    .onDrop(
                        of: [UTType.text],
                        delegate: TopFilterChipDropDelegate(
                            targetItem: item,
                            items: orderedItems,
                            draggingFilterToken: $draggingFilterToken,
                            onReorder: handleReorder
                        )
                    )
                }
            }
            .padding(.horizontal, 14)
        }
    }

    private var baseItems: [TopFilterItem] {
        localTags.map { .localTag($0.id) } + serviceFilterOrder.map { .service($0) }
    }

    private var orderedItems: [TopFilterItem] {
        let storedTokens = topFilterOrderRaw
            .split(separator: ",")
            .map(String.init)
        return mergeTopFilterItems(baseItems: baseItems, storedTokens: storedTokens)
    }

    private func label(for item: TopFilterItem, localTag: LocalTagSummary?) -> String {
        switch item {
        case .localTag:
            return localTag?.name ?? "タグ"
        case .service(let service):
            switch service {
            case .all: return "すべて"
            case .youtube: return "YOUTUBE"
            case .x: return "X"
            case .instagram: return "INSTAGRAM"
            case .web: return "WEB"
            case .tiktok: return "TIKTOK"
            }
        }
    }

    private func isSelected(_ item: TopFilterItem) -> Bool {
        switch item {
        case .localTag(let id):
            return selectedLocalTagID == id
        case .service(let service):
            return selectedLocalTagID == nil && selectedService == service
        }
    }

    private func select(_ item: TopFilterItem) {
        switch item {
        case .localTag(let id):
            selectedService = .all
            selectedLocalTagID = id
            onSelectLocalTag(id)
        case .service(let service):
            selectedLocalTagID = nil
            onSelectLocalTag(nil)
            selectedService = service
        }
    }

    private func handleReorder(_ items: [TopFilterItem]) {
        topFilterOrderRaw = items.map(\.token).joined(separator: ",")

        let localTagIDs = items.compactMap(\.localTagID)
        if localTagIDs != localTags.map(\.id) {
            onReorderLocalTags(localTagIDs)
        }
    }
}

private struct TopFilterChipDropDelegate: DropDelegate {
    let targetItem: TopFilterItem
    let items: [TopFilterItem]
    @Binding var draggingFilterToken: String?
    let onReorder: ([TopFilterItem]) -> Void

    func dropUpdated(info: DropInfo) -> DropProposal? {
        DropProposal(operation: .move)
    }

    func dropEntered(info: DropInfo) {
        guard let draggingFilterToken,
              draggingFilterToken != targetItem.token else {
            return
        }
        var orderedItems = items
        guard let fromIndex = orderedItems.firstIndex(where: { $0.token == draggingFilterToken }),
              let toIndex = orderedItems.firstIndex(of: targetItem) else {
            return
        }
        orderedItems.move(
            fromOffsets: IndexSet(integer: fromIndex),
            toOffset: toIndex > fromIndex ? toIndex + 1 : toIndex
        )
        onReorder(orderedItems)
    }

    func performDrop(info: DropInfo) -> Bool {
        draggingFilterToken = nil
        return true
    }

}

let serviceFilterOrder: [ServiceType] = [
    .all,
    .youtube,
    .x,
    .instagram,
    .tiktok,
    .web,
]

struct FilterChipButton: View {
    let label: String
    let selected: Bool
    let tint: TagTint
    let accessibilityLabel: String?
    let action: (() -> Void)?

    init(
        label: String,
        selected: Bool,
        tint: TagTint = .standard,
        accessibilityLabel: String? = nil,
        action: (() -> Void)? = nil
    ) {
        self.label = label
        self.selected = selected
        self.tint = tint
        self.accessibilityLabel = accessibilityLabel
        self.action = action
    }

    var body: some View {
        Button {
            action?()
        } label: {
            if label == "+" {
                Text(label)
                    .font(.system(size: 14, weight: .heavy, design: .rounded))
                    .foregroundStyle(.clear)
                    .padding(.horizontal, 26)
                    .padding(.vertical, 11)
                    .frame(minWidth: 54, minHeight: 44)
                    .background(selected ? AppPalette.selectedSurface : AppPalette.panelStrong, in: Capsule())
                    .overlay {
                        Text(label)
                            .font(.system(size: 22, weight: .heavy, design: .rounded))
                            .foregroundStyle(selected ? AppPalette.primaryStrong : Color.white.opacity(0.78))
                    }
            } else {
                Text(label)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .font(.system(size: 14, weight: .heavy, design: .rounded))
                    .foregroundStyle(
                        selected || tint != .standard
                            ? Color(UIColor(hex: 0x08111D))
                            : Color.white.opacity(0.78)
                    )
                    .padding(.horizontal, 18)
                    .padding(.vertical, 11)
                    .frame(maxWidth: 190, minHeight: 44)
                    .background(
                        selected
                            ? tint.color ?? Color(UIColor(hex: 0x67B0FF))
                            : tint.color ?? AppPalette.panelStrong,
                        in: Capsule()
                    )
                    .overlay(Capsule().stroke(selected ? AppPalette.primaryStrong : .clear, lineWidth: 2))
            }
        }
        .buttonStyle(.plain)
        .contentShape(Capsule())
        .accessibilityLabel(accessibilityLabel ?? (label == "+" ? "タグを作成" : label))
        .accessibilityValue(selected ? "選択中" : "未選択")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct SharedTagSection: View {
    @ObservedObject private var tints = TagTintPreferences.shared
    @State private var editingTag: SharedTagSummary?
    let tags: [SharedTagSummary]
    var accountID: String? = nil
    let onCreateTag: (() -> Void)?
    let onOpenTag: ((String) -> Void)?
    let onRenameTag: ((String, String) async -> Bool)?

    var body: some View {
        VStack(alignment: .leading, spacing: 4.5) {
            Text("共有タグ")
                .font(.system(size: 15, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textSecondary)
                .padding(.top, 4.5)
                .padding(.horizontal, 14)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    FilterChipButton(
                        label: "+",
                        selected: false,
                        accessibilityLabel: "共有タグを作成",
                        action: onCreateTag
                    )

                    ForEach(tags) { tag in
                        let canRename = canRenameSharedTag(currentUserRole: tag.currentUserRole)
                        FilterChipButton(
                            label: tag.name,
                            selected: false,
                            tint: tints.tint(for: TagTintPreferences.sharedKey(accountID: accountID, remoteID: tag.remoteTagID))
                        )
                        .highPriorityGesture(TapGesture(count: 2).exclusively(before: TapGesture(count: 1)).onEnded { gesture in
                            switch gesture {
                            case .first: editingTag = tag
                            case .second: onOpenTag?(tag.remoteTagID)
                            }
                        })
                        .accessibilityAction { onOpenTag?(tag.remoteTagID) }
                        .accessibilityAction(named: Text(canRename ? "共有タグ名と色を編集" : "共有タグの色を変更")) { editingTag = tag }
                    }
                }
                .padding(.horizontal, 14)
            }
        }
        .sheet(item: $editingTag) { tag in
            if let key = TagTintPreferences.sharedKey(accountID: accountID, remoteID: tag.remoteTagID) {
                let canRename = canRenameSharedTag(currentUserRole: tag.currentUserRole)
                TagAppearanceSheet(
                    name: tag.name,
                    key: key,
                    title: canRename ? "共有タグを編集" : "共有タグの色",
                    allowsRename: canRename,
                    onSaveName: canRename ? { name in
                        guard name.trimmingCharacters(in: .whitespacesAndNewlines) != tag.name else { return true }
                        return await onRenameTag?(tag.remoteTagID, name) ?? false
                    } : nil
                )
            }
        }
    }
}

struct MainEntryListControlRow: View {
    @Binding var sortOption: EntryListSortOption
    @Binding var isAscending: Bool
    @Binding var displayMode: EntryListDisplayMode
    @Binding var homeEntryLayout: HomeEntryLayout
    @Binding var gridInfoVisible: Bool

    @ViewBuilder
    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            sortMenu(expanded: true)
                .frame(maxWidth: .infinity)
            displayMethodControl(expanded: true)
                .frame(maxWidth: .infinity)
            imageVisibilityControl(expanded: true)
                .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 14)
    }

    private var sortDirectionTitle: String {
        if sortOption == .addedDate {
            return isAscending ? "古い順" : "新しい順"
        }
        return isAscending ? "昇順" : "降順"
    }

    private var visibilityIsOn: Bool {
        homeEntryLayout == .list ? displayMode == .rich : gridInfoVisible
    }

    private var visibilityActionLabel: String {
        if homeEntryLayout == .list {
            return displayMode == .rich ? "画像を非表示" : "画像を表示"
        }
        return gridInfoVisible ? "タイトルなどを非表示" : "タイトルなどを表示"
    }

    private func sortMenu(expanded: Bool) -> some View {
        Menu {
            Section("並び替え") {
                Picker("項目", selection: $sortOption) {
                    ForEach(EntryListSortOption.allCases) { option in
                        Text(option.label).tag(option)
                    }
                }
                .accessibilityIdentifier("home_sort_key")
            }
            Section("表示順") {
                Picker("表示順", selection: $isAscending) {
                    Text(sortOption == .addedDate ? "新しい順" : "降順").tag(false)
                    Text(sortOption == .addedDate ? "古い順" : "昇順").tag(true)
                }
                .accessibilityIdentifier("home_sort_direction")
            }
        } label: {
            controlLabel(
                title: sortOption.label,
                icon: "arrow.up.arrow.down",
                trailingIcon: "chevron.down",
                expanded: expanded
            )
        }
        .accessibilityLabel("並び替え")
        .accessibilityValue("\(sortOption.label)、\(sortDirectionTitle)")
        .accessibilityIdentifier("home_sort_menu")
    }

    private func displayMethodControl(expanded: Bool) -> some View {
        Button {
            homeEntryLayout = homeEntryLayout == .list ? .thumbnailGrid : .list
        } label: {
            controlLabel(
                title: "表示方法",
                icon: homeEntryLayout == .list ? "list.bullet" : "square.grid.2x2",
                emphasized: true,
                expanded: expanded
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("表示方法")
        .accessibilityValue(homeEntryLayout == .list ? "一覧" : "2列サムネイル")
        .accessibilityHint("タップして一覧と2列表示を切り替えます")
        .accessibilityIdentifier("home_display_method")
    }

    private func imageVisibilityControl(expanded: Bool) -> some View {
        Button {
            if homeEntryLayout == .list {
                displayMode = displayMode == .rich ? .compact : .rich
            } else {
                gridInfoVisible.toggle()
            }
        } label: {
            controlLabel(
                title: visibilityIsOn ? "表示" : "非表示",
                icon: visibilityIsOn ? "eye" : "eye.slash",
                expanded: expanded
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(visibilityActionLabel)
        .accessibilityValue(visibilityIsOn ? "表示中" : "非表示")
        .accessibilityHint(homeEntryLayout == .list ? "タップして画像表示を切り替えます" : "タップしてタイトルなどの表示を切り替えます")
        .accessibilityIdentifier("home_image_visibility")
    }

    private func controlLabel(
        title: String,
        icon: String,
        trailingIcon: String? = nil,
        emphasized: Bool = false,
        expanded: Bool
    ) -> some View {
        HStack(spacing: 7) {
            Image(systemName: icon)
                .font(.system(.caption, design: .rounded).weight(.semibold))
            Text(title)
                .font(.system(.caption, design: .rounded).weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.65)
            if let trailingIcon {
                Image(systemName: trailingIcon)
                    .font(.system(size: 10, weight: .semibold))
            }
        }
        .foregroundStyle(emphasized ? Color.white : AppPalette.textSecondary)
        .padding(.horizontal, 10)
        .frame(maxWidth: expanded ? .infinity : nil, minHeight: 44, alignment: .center)
        .background(emphasized ? AppPalette.primaryStrong : AppPalette.surfaceSoft, in: Capsule())
        .contentShape(Capsule())
        .fixedSize(horizontal: !expanded, vertical: false)
    }
}

struct ArchiveEmptyStateCard: View {
    var body: some View {
        AppPanel {
            Text("アーカイブしたURLはまだありません")
                .font(.system(size: 28, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)

            Text("アーカイブしたURLがここに表示されます")
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(AppPalette.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        }
    }
}

struct BottomPrimaryBar: View {
    let label: String
    let systemImage: String
    let action: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            AppActionButton(tone: .primary, action: action) {
                HStack(spacing: 8) {
                    Image(systemName: systemImage)
                        .font(.system(size: AppIconMetrics.bottomAction, weight: .bold))
                    Text(label)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .background(AppPalette.background)
    }
}

struct IOSBannerAdSlot: View {
    @ViewBuilder
    var body: some View {
        if LaunchAdVisibility.showBannerAds {
            bannerContent
        }
    }

    private var bannerContent: some View {
        ZStack(alignment: .top) {
            RoundedRectangle(cornerRadius: 0)
                .fill(Color(UIColor { traits in
                    traits.userInterfaceStyle == .dark
                        ? UIColor(white: 0.96, alpha: 1)
                        : UIColor.white
                }))
                .overlay(
                    RoundedRectangle(cornerRadius: 0)
                        .stroke(Color.black.opacity(0.12), lineWidth: 1)
                )

            Text("テスト広告")
                .font(.system(size: 16, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(Color.black.opacity(0.58), in: RoundedRectangle(cornerRadius: 4, style: .continuous))
                .padding(.top, 4)

            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("developers.google.com")
                        .font(.system(size: 13, weight: .regular))
                        .foregroundStyle(Color.gray)
                        .lineLimit(1)

                    Text("AdMob Adaptive Banner")
                        .font(.system(size: 20, weight: .regular))
                        .foregroundStyle(Color(UIColor.darkGray))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }

                Spacer(minLength: 8)

                HStack(spacing: 6) {
                    Text("OPEN")
                        .font(.system(size: 15, weight: .semibold))
                    Image(systemName: "chevron.right")
                        .font(.system(size: 14, weight: .bold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .frame(height: 42)
                .background(Color(red: 0.05, green: 0.55, blue: 1), in: Capsule())
            }
            .padding(.horizontal, 14)
            .padding(.top, 20)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 64)
        .accessibilityLabel("テスト広告")
    }
}

func filteredEntries(
    _ entries: [URLRecord],
    selectedService: ServiceType,
    selectedLocalTagID: Int64? = nil,
    localTagAssignments: [Int64: Set<Int64>] = [:],
    localTags: [LocalTagSummary] = []
) -> [URLRecord] {
    return entries.filter { entry in
        let serviceMatches = selectedService == .all || entry.serviceType == selectedService
        let tagMatches = selectedLocalTagID.map { tagID in
            localTagAssignments[entry.id]?.contains(tagID) == true
        } ?? true
        return serviceMatches && tagMatches
    }
}

func serviceLabel(for entry: URLRecord) -> String {
    if URLRules.isTextCardHost(entry.normalizedHost) {
        return "テキスト"
    }

    switch entry.serviceType {
    case .youtube:
        return "YouTube"
    case .instagram:
        return "Instagram"
    case .x:
        return "X"
    case .tiktok:
        return "TikTok"
    case .web, .all:
        return entry.normalizedHost
    }
}

func preferredDisplayTitle(for entry: URLRecord) -> String {
    if let userTitle = nonBlank(entry.userTitle) {
        return userTitle
    }

    if URLRules.isTextCardHost(entry.normalizedHost) {
        if let fetchedTitle = nonBlank(entry.fetchedTitle) {
            return fetchedTitle
        }
        if let body = firstNonBlank(entry.fetchedBody, entry.bodyPreview) ?? nonBlank(entry.originalURL) {
            return URLRules.textCardTitle(body)
        }
        return "テキスト"
    }

    if entry.serviceType == .x,
       URL(string: entry.normalizedURL)?.path.hasPrefix("/i/article/") == true,
       let fetchedTitle = nonBlank(entry.fetchedTitle) {
        return fetchedTitle
    }

    let usesContentTitle = isSocialPostTitleContentFirstService(entry.serviceType) &&
        ![.profile, .channel, .sound, .music, .playlist, .list, .space, .hashtag, .highlight].contains(entry.contentContext)
    let fetchedTitle = nonBlank(entry.fetchedTitle)
    if let fetchedTitle, !usesContentTitle {
        return fetchedTitle
    }
    if usesContentTitle, entry.serviceType == .instagram, let fetchedTitle {
        let cleaned = fetchedTitle.replacingOccurrences(
            of: #"\s*[|•·]\s*Instagram(?:\s+photos\s+and\s+videos)?\s*$"#,
            with: "", options: [.regularExpression, .caseInsensitive]
        ).trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = cleaned.lowercased()
        let isAuthor = nonBlank(entry.fetchedAuthorName)?.caseInsensitiveCompare(cleaned) == .orderedSame
        let isEngagement = cleaned.range(
            of: #"(?:^[\d,.KM万]+\s+(?:likes?|comments?)\s*[,，]|^いいね[!！]?\s*[\d,.万]+件[、,]\s*コメント\s*[\d,.万]+件|[\d,.万]+件(?:の)?(?:いいね|コメント).*(?:Instagram|インスタグラム))"#,
            options: [.regularExpression, .caseInsensitive]
        ) != nil
        let isWrapper = lower.hasPrefix("@") || lower.contains(" on instagram") || lower.contains("instagram photos and videos") ||
            lower == "instagram" || cleaned.range(of: #"\(@[^)]+\)\s*$"#, options: .regularExpression) != nil ||
            isEngagement || cleaned.isEmpty
        if !isAuthor && !isWrapper {
            return cleaned
        }
    }

    if let contentText = firstNonBlank(entry.fetchedBody, entry.bodyPreview, entry.bodySummary, entry.description) {
        let caption = entry.serviceType == .instagram
            ? MetadataFetcher.instagramCaptionText(contentText) ?? contentText
            : contentText
        let compact = caption.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        return compact.count > 100 ? String(compact.prefix(100)) + "…" : compact
    }

    if usesContentTitle && entry.serviceType == .instagram {
        return URLRules.effectiveTitle(userTitle: nil, fetchedTitle: nil, serviceType: entry.serviceType, normalizedHost: entry.normalizedHost)
    }
    return entry.effectiveTitle
}

private func isSocialPostTitleContentFirstService(_ serviceType: ServiceType) -> Bool {
    switch serviceType {
    case .x, .instagram, .tiktok:
        return true
    case .youtube, .web, .all:
        return false
    }
}

private func firstNonBlank(_ values: String?...) -> String? {
    for value in values {
        if let trimmed = nonBlank(value) {
            return trimmed
        }
    }
    return nil
}

private func nonBlank(_ value: String?) -> String? {
    guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines),
          !trimmed.isEmpty else {
        return nil
    }
    return trimmed
}

func contentContextLabel(for contentContext: ContentContext) -> String {
    switch contentContext {
    case .standard:
        return ""
    case .video:
        return "動画"
    case .shorts:
        return "ショート"
    case .live:
        return "ライブ"
    case .space:
        return "スペース"
    case .list:
        return "リスト"
    case .music:
        return "音楽"
    case .playlist:
        return "プレイリスト"
    case .post:
        return "投稿"
    case .reel:
        return "リール"
    case .profile:
        return "プロフィール"
    case .channel:
        return "チャンネル"
    case .sound:
        return "サウンド"
    case .highlight:
        return "ハイライト"
    case .hashtag:
        return "ハッシュタグ"
    case .shortURL:
        return "短縮URL"
    }
}

func metadataDotColor(for state: MetadataState) -> Color {
    switch state {
    case .pending:
        return AppPalette.primary
    case .ready:
        return Color(red: 120 / 255, green: 240 / 255, blue: 209 / 255)
    case .failed:
        return Color(red: 1.0, green: 183 / 255, blue: 77 / 255)
    case .unavailable:
        return AppPalette.textMuted
    }
}

func metadataTextColor(for state: MetadataState) -> Color {
    switch state {
    case .failed, .unavailable:
        return AppPalette.warning
    case .pending:
        return AppPalette.textSecondary
    case .ready:
        return AppPalette.textSecondary
    }
}

func serviceAccent(for service: ServiceType) -> Color {
    switch service {
    case .youtube:
        return AppPalette.youtubeAccent
    case .instagram:
        return AppPalette.instagramAccent
    case .x:
        return AppPalette.xAccent
    case .tiktok:
        return AppPalette.primary
    case .web, .all:
        return AppPalette.webAccent
    }
}

func serviceAccentGradient(for service: ServiceType) -> LinearGradient {
    let colors: [Color]
    switch service {
    case .youtube:
        colors = [
            Color(red: 1, green: 59 / 255, blue: 48 / 255),
            Color(red: 1, green: 59 / 255, blue: 48 / 255),
        ]
    case .instagram:
        colors = [
            Color(red: 138 / 255, green: 58 / 255, blue: 185 / 255),
            Color(red: 233 / 255, green: 89 / 255, blue: 80 / 255),
            Color(red: 252 / 255, green: 175 / 255, blue: 69 / 255),
        ]
    case .x:
        colors = [
            Color(red: 152 / 255, green: 167 / 255, blue: 184 / 255),
            Color(red: 152 / 255, green: 167 / 255, blue: 184 / 255),
        ]
    case .tiktok:
        colors = [
            Color(red: 17 / 255, green: 17 / 255, blue: 17 / 255),
            Color(red: 36 / 255, green: 246 / 255, blue: 240 / 255),
            Color.white,
            Color(red: 1, green: 51 / 255, blue: 83 / 255),
            Color(red: 17 / 255, green: 17 / 255, blue: 17 / 255),
        ]
    case .web, .all:
        colors = [
            Color(red: 52 / 255, green: 199 / 255, blue: 89 / 255),
            Color(red: 52 / 255, green: 199 / 255, blue: 89 / 255),
        ]
    }
    return LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom)
}

enum DateFormatters {
    static let listTimestamp: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        formatter.locale = Locale(identifier: "ja_JP")
        return formatter
    }()

    static let detailTimestamp: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .long
        formatter.timeStyle = .short
        formatter.locale = Locale(identifier: "ja_JP")
        return formatter
    }()
}
