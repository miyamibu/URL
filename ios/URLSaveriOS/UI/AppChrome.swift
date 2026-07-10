import SwiftUI
import UIKit
import UniformTypeIdentifiers

enum LaunchAdVisibility {
    // Trial launch switch. Set this back to true when banner ads should return.
    static let showBannerAds = false
    static let mainNotificationBottomPadding: CGFloat = showBannerAds ? 172 : 96
}

enum AppPalette {
    static let background = dynamic(light: 0xF4F7FB, dark: 0x07111F)
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
    static let secondarySurface = dynamic(light: 0x144339, dark: 0x0E3B34)
    static let danger = dynamic(light: 0xB5261E, dark: 0xFF776A)
    static let dangerSurface = dynamic(light: 0x5B1B17, dark: 0x4B1714)
    static let warning = dynamic(light: 0xE66A57, dark: 0xFF8F7F)
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

struct ScreenContainer<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ZStack(alignment: .top) {
            AppPalette.background.ignoresSafeArea()
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

    init(
        title: String,
        leadingButton: ScreenHeaderButton?,
        trailingButtons: [ScreenHeaderButton],
        onTitleTap: (() -> Void)? = nil
    ) {
        self.title = title
        self.leadingButton = leadingButton
        self.trailingButtons = trailingButtons
        self.onTitleTap = onTitleTap
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
        }
        .padding(.leading, 12)
        .padding(.trailing, 6)
        .padding(.top, 8)
        .padding(.bottom, 12)
    }

    private var headerTitleSize: CGFloat {
        if title == "保存したURL" {
            return 25
        }

        return leadingButton == nil ? 32 : 31
    }

    private var headerTitle: some View {
        Text(title)
            .font(.system(size: headerTitleSize, weight: .heavy, design: .rounded))
            .foregroundStyle(AppPalette.textPrimary)
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .layoutPriority(1)
    }
}

private struct IconChromeButton: View {
    let button: ScreenHeaderButton

    var body: some View {
        Button(action: button.action) {
            HStack(spacing: button.title == nil ? 0 : 6) {
                Image(systemName: button.icon)
                    .font(.system(size: AppIconMetrics.chrome, weight: .semibold))
                if let title = button.title {
                    Text(title)
                        .font(.system(size: 15, weight: .heavy, design: .rounded))
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
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
        VStack(alignment: .leading, spacing: 14) {
            content
        }
        .padding(padded ? 20 : 0)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(strong ? AppPalette.panelStrong : AppPalette.surface, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .stroke(strong ? AppPalette.panelStrong : AppPalette.outline, lineWidth: strong ? 0 : 1.5)
        )
    }
}

struct AppActionButton<Label: View>: View {
    let tone: AppButtonTone
    let enabled: Bool
    let action: () -> Void
    let label: Label

    init(
        tone: AppButtonTone = .secondary,
        enabled: Bool = true,
        action: @escaping () -> Void,
        @ViewBuilder label: () -> Label
    ) {
        self.tone = tone
        self.enabled = enabled
        self.action = action
        self.label = label()
    }

    var body: some View {
        Button(action: action) {
            label
                .font(.system(size: 19, weight: .bold, design: .rounded))
                .foregroundStyle(foregroundColor)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 18)
                .background(backgroundColor, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 26, style: .continuous)
                        .stroke(borderColor, lineWidth: borderWidth)
                )
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }

    private var backgroundColor: Color {
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
        tone == .primary ? 0 : 1.5
    }
}

struct EntryCardView: View {
    let entry: URLRecord
    let timestampLabel: String
    let displayMode: EntryListDisplayMode
    let cardWidth: CGFloat?
    let selected: Bool
    let localTagNames: [String]
    let footerContent: (() -> AnyView)?

    init(
        entry: URLRecord,
        timestampLabel: String,
        displayMode: EntryListDisplayMode,
        cardWidth: CGFloat? = nil,
        selected: Bool = false,
        localTagNames: [String] = [],
        footerContent: (() -> AnyView)? = nil
    ) {
        self.entry = entry
        self.timestampLabel = timestampLabel
        self.displayMode = displayMode
        self.cardWidth = cardWidth
        self.selected = selected
        self.localTagNames = localTagNames
        self.footerContent = footerContent
    }

    var body: some View {
        let visibleLocalTagNames = entryCardVisibleLocalTagNames(localTagNames)
        AppPanel(padded: false) {
            VStack(alignment: .leading, spacing: 0) {
                if displayMode == .rich, let thumbnailURL = entry.thumbnailURL, let url = URL(string: thumbnailURL) {
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
                            topLeadingRadius: 30,
                            bottomLeadingRadius: 0,
                            bottomTrailingRadius: 0,
                            topTrailingRadius: 30,
                            style: .continuous
                        )
                    )
                }

                HStack(alignment: .top, spacing: 12) {
                    RoundedRectangle(cornerRadius: 999, style: .continuous)
                        .fill(serviceAccentGradient(for: entry.serviceType))
                        .frame(width: 7, height: 64)
                        .padding(.top, 4)

                    VStack(alignment: .leading, spacing: 10) {
                        HStack(alignment: .top, spacing: 10) {
                            ServiceBadgeView(serviceType: entry.serviceType, badgeImageURL: entry.badgeImageURL)
                                .padding(.top, 1)

                            if visibleLocalTagNames.isEmpty {
                                HStack(spacing: 10) {
                                    Text(entryCardHeaderFallbackText(for: entry))
                                        .font(.system(size: 16, weight: .medium))
                                        .foregroundStyle(AppPalette.textSecondary)
                                        .lineLimit(1)

                                    if entry.contentContext != .standard {
                                        Text(contentContextLabel(for: entry.contentContext))
                                            .font(.system(size: 13, weight: .bold, design: .rounded))
                                            .foregroundStyle(AppPalette.textSecondary)
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 6)
                                            .background(AppPalette.panelStrong, in: Capsule())
                                            .overlay(
                                                Capsule()
                                                    .stroke(AppPalette.outline, lineWidth: 1.5)
                                            )
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            } else {
                                EntryLocalTagFlow(tagNames: visibleLocalTagNames)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }

                        Text(preferredDisplayTitle(for: entry))
                            .font(.system(size: 16, weight: .heavy, design: .rounded))
                            .foregroundStyle(AppPalette.textPrimary)
                            .lineLimit(displayMode == .rich ? 3 : 2)
                            .multilineTextAlignment(.leading)

                        if displayMode == .rich, let summary = entry.description ?? entry.bodySummary, !summary.isEmpty {
                            Text(summary)
                                .font(.system(size: 15, weight: .medium))
                                .foregroundStyle(AppPalette.textMuted)
                                .lineLimit(3)
                        }

                        if let metadataText = MetadataStatusText.listText(for: entry) {
                            Text(metadataText)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(metadataTextColor(for: entry.metadataState))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)

                if let footerContent {
                    footerContent()
                        .padding(.horizontal, 20)
                        .padding(.bottom, 16)
                }
            }
        }
        .frame(width: cardWidth)
        .overlay(
            RoundedRectangle(cornerRadius: 30, style: .continuous)
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

    private var thumbnailHeight: CGFloat {
        guard let cardWidth else { return 220 }
        return min(max(cardWidth * 0.58, 212), 236)
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

    var body: some View {
        EntryLocalTagFlowLayout(horizontalSpacing: 6, verticalSpacing: 6) {
            ForEach(tagNames, id: \.self) { tagName in
                Text(tagName)
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundStyle(AppPalette.primaryStrong)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .frame(maxWidth: 150)
                    .background(AppPalette.primarySurface, in: Capsule())
                    .overlay(
                        Capsule()
                            .stroke(AppPalette.primaryStrong.opacity(0.45), lineWidth: 1)
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

struct SwipeableEntryCard: View {
    let entry: URLRecord
    let displayMode: EntryListDisplayMode
    let cardWidth: CGFloat
    let localTagNames: [String]
    let onTap: () -> Void
    let onArchive: () -> Void
    let onDelete: () -> Void
    let onLongPress: () -> Void

    @State private var dragOffset: CGFloat = 0

    var body: some View {
        ZStack {
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

            EntryCardView(
                entry: entry,
                timestampLabel: "保存",
                displayMode: displayMode,
                cardWidth: cardWidth,
                localTagNames: localTagNames
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
                localTagNames: localTagNames
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

    init(serviceType: ServiceType, badgeImageURL: String? = nil) {
        self.serviceType = serviceType
        self.badgeImageURL = badgeImageURL
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
    @State private var failedURL: URL?

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
        guard failedURL != url else { return }

        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.setValue(Self.browserLikeUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("image/avif,image/webp,image/apng,image/*,*/*;q=0.8", forHTTPHeaderField: "Accept")
        request.setValue("https://www.tiktok.com/", forHTTPHeaderField: "Referer")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse,
                  (200...299).contains(httpResponse.statusCode),
                  let loadedImage = UIImage(data: data) else {
                await MainActor.run { failedURL = url }
                return
            }
            await MainActor.run {
                image = loadedImage
                loadedURL = url
                failedURL = nil
            }
        } catch {
            await MainActor.run { failedURL = url }
        }
    }

    private static var browserLikeUserAgent: String {
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    }
}

struct ServiceFilterRow: View {
    @State private var draggingLocalTagID: Int64?

    @Binding var selectedService: ServiceType
    @Binding var selectedLocalTagID: Int64?
    @Binding var selectedCollectionID: Int64?
    let showsCreateChip: Bool
    let createAction: (() -> Void)?
    let collectionCreateAction: (() -> Void)?
    let collectionManageAction: (() -> Void)?
    let localTags: [LocalTagSummary]
    let collections: [CollectionSummary]
    let onSelectLocalTag: (Int64?) -> Void
    let onSelectCollection: (Int64?) -> Void
    let onRenameLocalTag: (LocalTagSummary) -> Void
    let onReorderLocalTags: ([Int64]) -> Void

    init(
        selectedService: Binding<ServiceType>,
        selectedLocalTagID: Binding<Int64?> = .constant(nil),
        selectedCollectionID: Binding<Int64?> = .constant(nil),
        showsCreateChip: Bool = false,
        createAction: (() -> Void)? = nil,
        collectionCreateAction: (() -> Void)? = nil,
        collectionManageAction: (() -> Void)? = nil,
        localTags: [LocalTagSummary] = [],
        collections: [CollectionSummary] = [],
        onSelectLocalTag: @escaping (Int64?) -> Void = { _ in },
        onSelectCollection: @escaping (Int64?) -> Void = { _ in },
        onRenameLocalTag: @escaping (LocalTagSummary) -> Void = { _ in },
        onReorderLocalTags: @escaping ([Int64]) -> Void = { _ in }
    ) {
        _selectedService = selectedService
        _selectedLocalTagID = selectedLocalTagID
        _selectedCollectionID = selectedCollectionID
        self.showsCreateChip = showsCreateChip
        self.createAction = createAction
        self.collectionCreateAction = collectionCreateAction
        self.collectionManageAction = collectionManageAction
        self.localTags = localTags
        self.collections = collections
        self.onSelectLocalTag = onSelectLocalTag
        self.onSelectCollection = onSelectCollection
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

                ForEach(localTags) { tag in
                    FilterChipButton(
                        label: tag.name,
                        selected: selectedLocalTagID == tag.id
                    ) {
                        selectedService = .all
                        selectedCollectionID = nil
                        selectedLocalTagID = tag.id
                        onSelectCollection(nil)
                        onSelectLocalTag(tag.id)
                    }
                    .simultaneousGesture(
                        TapGesture(count: 2).onEnded {
                            onRenameLocalTag(tag)
                        }
                    )
                    .onDrag {
                        draggingLocalTagID = tag.id
                        return NSItemProvider(object: String(tag.id) as NSString)
                    }
                    .onDrop(
                        of: [UTType.text],
                        delegate: LocalTagChipDropDelegate(
                            targetTag: tag,
                            localTags: localTags,
                            draggingLocalTagID: $draggingLocalTagID,
                            onReorderLocalTags: onReorderLocalTags
                        )
                    )
                }

                ForEach(serviceFilterOrder, id: \.self) { service in
                    FilterChipButton(
                        label: chipLabel(for: service),
                        selected: selectedLocalTagID == nil && selectedCollectionID == nil && selectedService == service
                    ) {
                        selectedLocalTagID = nil
                        selectedCollectionID = nil
                        onSelectLocalTag(nil)
                        onSelectCollection(nil)
                        selectedService = service
                    }
                }

                if let collectionCreateAction {
                    FilterChipButton(
                        label: "+保存先",
                        selected: false,
                        action: collectionCreateAction
                    )
                }

                if let collectionManageAction, !collections.isEmpty {
                    FilterChipButton(
                        label: "保存先管理",
                        selected: false,
                        action: collectionManageAction
                    )
                }

                ForEach(collections) { collection in
                    FilterChipButton(
                        label: collection.name,
                        selected: selectedCollectionID == collection.id
                    ) {
                        selectedService = .all
                        selectedLocalTagID = nil
                        selectedCollectionID = selectedCollectionID == collection.id ? nil : collection.id
                        onSelectLocalTag(nil)
                        onSelectCollection(selectedCollectionID)
                    }
                }
            }
            .padding(.horizontal, 14)
        }
    }

    private func chipLabel(for service: ServiceType) -> String {
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

private struct LocalTagChipDropDelegate: DropDelegate {
    let targetTag: LocalTagSummary
    let localTags: [LocalTagSummary]
    @Binding var draggingLocalTagID: Int64?
    let onReorderLocalTags: ([Int64]) -> Void

    func dropEntered(info: DropInfo) {
        guard let draggingLocalTagID,
              draggingLocalTagID != targetTag.id else {
            return
        }
        var orderedIDs = localTags.map(\.id)
        guard let fromIndex = orderedIDs.firstIndex(of: draggingLocalTagID),
              let toIndex = orderedIDs.firstIndex(of: targetTag.id) else {
            return
        }
        orderedIDs.move(
            fromOffsets: IndexSet(integer: fromIndex),
            toOffset: toIndex > fromIndex ? toIndex + 1 : toIndex
        )
        onReorderLocalTags(orderedIDs)
    }

    func performDrop(info: DropInfo) -> Bool {
        draggingLocalTagID = nil
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
    let action: (() -> Void)?

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
                    .frame(minWidth: 54)
                    .background(selected ? AppPalette.primarySurface : AppPalette.panelStrong, in: Capsule())
                    .overlay {
                        Text(label)
                            .font(.system(size: 22, weight: .heavy, design: .rounded))
                            .foregroundStyle(selected ? AppPalette.primaryStrong : Color.white.opacity(0.78))
                    }
            } else {
                Text(label)
                    .font(.system(size: 14, weight: .heavy, design: .rounded))
                    .foregroundStyle(selected ? AppPalette.primaryStrong : Color.white.opacity(0.78))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 11)
                    .frame(maxWidth: 190)
                    .background(selected ? AppPalette.primarySurface : AppPalette.panelStrong, in: Capsule())
            }
        }
        .buttonStyle(.plain)
    }
}

struct SharedTagSection: View {
    let tags: [SharedTagSummary]
    let onCreateTag: (() -> Void)?
    let onOpenTag: ((String) -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("共有タグ")
                .font(.system(size: 15, weight: .heavy, design: .rounded))
                .foregroundStyle(AppPalette.textSecondary)
                .padding(.top, 1)
                .padding(.horizontal, 14)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    FilterChipButton(
                        label: "+",
                        selected: false,
                        action: onCreateTag
                    )

                    ForEach(tags) { tag in
                        FilterChipButton(
                            label: tag.name,
                            selected: false
                        ) {
                            onOpenTag?(tag.remoteTagID)
                        }
                    }
                }
                .padding(.horizontal, 14)
            }
        }
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
    selectedCollectionID: Int64? = nil,
    localTagAssignments: [Int64: Set<Int64>] = [:],
    localTags: [LocalTagSummary] = [],
    collections: [CollectionSummary] = []
) -> [URLRecord] {
    let selectedCollection = selectedCollectionID.flatMap { collectionID in
        collections.first { $0.id == collectionID }
    }
    let matchingLocalTagIDs = Set(
        localTags
            .filter { tag in
                guard let selectedCollection else { return false }
                return tag.name.caseInsensitiveCompare(selectedCollection.name) == .orderedSame
            }
            .map(\.id)
    )
    return entries.filter { entry in
        let serviceMatches = selectedService == .all || entry.serviceType == selectedService
        let tagMatches = selectedLocalTagID.map { tagID in
            localTagAssignments[entry.id]?.contains(tagID) == true
        } ?? true
        let collectionMatches = selectedCollectionID.map { collectionID in
            entry.collectionID == collectionID
                || !(localTagAssignments[entry.id] ?? []).isDisjoint(with: matchingLocalTagIDs)
        } ?? true
        return serviceMatches && tagMatches && collectionMatches
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
        if let body = nonBlank(entry.fetchedBody) ?? nonBlank(entry.originalURL) {
            return URLRules.textCardTitle(body)
        }
        return "テキスト"
    }

    if isSocialPostTitleContentFirstService(entry.serviceType),
       let contentText = firstNonBlank(entry.fetchedBody, entry.bodySummary, entry.description) {
        return contentText
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
    case .music:
        return "音楽"
    case .post:
        return "投稿"
    case .reel:
        return "リール"
    case .profile:
        return "プロフィール"
    case .sound:
        return "サウンド"
    case .hashtag:
        return "ハッシュタグ"
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
