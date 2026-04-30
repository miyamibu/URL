import Foundation

struct MetadataFetcher: Sendable {
    private let session: URLSession
    private let youtubeOEmbedEndpointBuilder: @Sendable (URL) -> URL?
    private let tiktokOEmbedEndpointBuilder: @Sendable (URL) -> URL?
    private let tiktokFallbackEndpointBuilder: @Sendable (URL) -> URL?
    private let xOEmbedEndpointBuilder: @Sendable (URL) -> URL?
    private let xSyndicationEndpointBuilder: @Sendable (String) -> URL?
    private let instagramPublicOEmbedEndpointBuilder: @Sendable (URL) -> URL?
    private let instagramCaptionedEmbedEndpointBuilder: @Sendable (URL) -> URL?

    init(
        session: URLSession = .shared,
        youtubeOEmbedEndpointBuilder: @escaping @Sendable (URL) -> URL? = MetadataFetcher.youtubeOEmbedURL(for:),
        tiktokOEmbedEndpointBuilder: @escaping @Sendable (URL) -> URL? = MetadataFetcher.tiktokOEmbedURL(for:),
        tiktokFallbackEndpointBuilder: @escaping @Sendable (URL) -> URL? = MetadataFetcher.tiktokFallbackURL(for:),
        xOEmbedEndpointBuilder: @escaping @Sendable (URL) -> URL? = MetadataFetcher.xOEmbedURL(for:),
        xSyndicationEndpointBuilder: @escaping @Sendable (String) -> URL? = MetadataFetcher.xSyndicationURL(for:),
        instagramPublicOEmbedEndpointBuilder: @escaping @Sendable (URL) -> URL? = MetadataFetcher.instagramPublicOEmbedURL(for:),
        instagramCaptionedEmbedEndpointBuilder: @escaping @Sendable (URL) -> URL? = MetadataFetcher.instagramCaptionedEmbedURL(for:)
    ) {
        self.session = session
        self.youtubeOEmbedEndpointBuilder = youtubeOEmbedEndpointBuilder
        self.tiktokOEmbedEndpointBuilder = tiktokOEmbedEndpointBuilder
        self.tiktokFallbackEndpointBuilder = tiktokFallbackEndpointBuilder
        self.xOEmbedEndpointBuilder = xOEmbedEndpointBuilder
        self.xSyndicationEndpointBuilder = xSyndicationEndpointBuilder
        self.instagramPublicOEmbedEndpointBuilder = instagramPublicOEmbedEndpointBuilder
        self.instagramCaptionedEmbedEndpointBuilder = instagramCaptionedEmbedEndpointBuilder
    }

    func fetch(for record: URLRecord) async -> MetadataUpdate {
        guard let url = URL(string: record.openURL) else {
            return MetadataUpdate(
                fetchedTitle: nil,
                fetchedBody: nil,
                fetchedBodyKind: nil,
                bodySummary: nil,
                description: nil,
                thumbnailURL: nil,
                badgeImageURL: nil,
                metadataState: .unavailable,
                metadataFetchedAt: Date(),
                metadataError: .unsupportedScheme,
                canonicalID: nil,
                normalizedHost: nil,
                rawSourceHost: nil
            )
        }

        switch record.serviceType {
        case .youtube:
            return await fetchYouTubeMetadata(inputURL: url)
        case .tiktok:
            return await fetchTikTokMetadata(inputURL: url)
        case .x:
            return await fetchXMetadata(inputURL: url)
        case .instagram:
            return await fetchInstagramMetadata(inputURL: url)
        case .web, .all:
            return await fetchHTMLMetadataUpdate(url: url, service: record.serviceType)
        }
    }

    private func fetchYouTubeMetadata(inputURL: URL) async -> MetadataUpdate {
        let oEmbedMetadata = await fetchYouTubeOEmbedMetadata(inputURL: inputURL)
        let htmlUpdate = await fetchHTMLMetadataUpdate(url: inputURL, service: .youtube)

        if htmlUpdate.metadataState == .ready {
            return merge(update: htmlUpdate, with: oEmbedMetadata)
        }
        if oEmbedMetadata.hasAnyContent {
            return readyUpdate(from: oEmbedMetadata, finalURL: inputURL)
        }
        return htmlUpdate
    }

    private func fetchTikTokMetadata(inputURL: URL) async -> MetadataUpdate {
        let oEmbedMetadata = await fetchTikTokOEmbedMetadata(inputURL: inputURL)
        let htmlUpdate = await fetchHTMLMetadataUpdate(url: inputURL, service: .tiktok)

        if htmlUpdate.metadataState == .ready, htmlUpdate.fetchedBody != nil {
            return merge(update: htmlUpdate, with: oEmbedMetadata)
        }

        let fallbackMetadata = await fetchTikTokFallbackMetadata(inputURL: inputURL)
        if fallbackMetadata.hasAnyContent {
            var fallbackUpdate = readyUpdate(from: fallbackMetadata, finalURL: inputURL)
            if htmlUpdate.metadataState == .ready {
                fallbackUpdate = merge(primary: fallbackUpdate, supplement: htmlUpdate)
            }
            return merge(update: fallbackUpdate, with: oEmbedMetadata)
        }

        if htmlUpdate.metadataState == .ready {
            return merge(update: htmlUpdate, with: oEmbedMetadata)
        }
        if oEmbedMetadata.hasAnyContent {
            return readyUpdate(from: oEmbedMetadata, finalURL: inputURL)
        }

        return htmlUpdate
    }

    private func fetchXMetadata(inputURL: URL) async -> MetadataUpdate {
        guard let statusID = URLRules.extractXStatusID(from: inputURL.absoluteString) else {
            return await fetchHTMLMetadataUpdate(url: inputURL, service: .x)
        }

        let oEmbedMetadata = await fetchXOEmbedMetadata(inputURL: inputURL, statusID: statusID)
        let syndicationMetadata = await fetchXSyndicationMetadata(statusID: statusID)
        let mergedMetadata = oEmbedMetadata.merging(with: syndicationMetadata)

        if mergedMetadata.hasAnyContent {
            return readyUpdate(from: mergedMetadata, finalURL: inputURL)
        }

        return await fetchHTMLMetadataUpdate(url: inputURL, service: .x)
    }

    private func fetchInstagramMetadata(inputURL: URL) async -> MetadataUpdate {
        let oEmbedMetadata = await fetchInstagramPublicOEmbedMetadata(inputURL: inputURL)
        let captionedEmbedMetadata = await fetchInstagramCaptionedEmbedMetadata(inputURL: inputURL)
        let supplementalMetadata = mergeInstagramMetadata(
            oEmbedMetadata: oEmbedMetadata,
            captionedEmbedMetadata: captionedEmbedMetadata
        )
        let htmlUpdate = await fetchHTMLMetadataUpdate(url: inputURL, service: .instagram)

        if htmlUpdate.metadataState == .ready {
            return merge(update: htmlUpdate, with: supplementalMetadata)
        }
        if supplementalMetadata.hasAnyContent {
            return readyUpdate(from: supplementalMetadata, finalURL: inputURL)
        }

        return htmlUpdate
    }

    private func fetchHTMLMetadataUpdate(url: URL, service: ServiceType) async -> MetadataUpdate {
        do {
            let document = try await fetchHTMLDocument(url: url)
            let metadata = extractMetadata(html: document.html, url: document.finalURL, service: service)
            let state: MetadataState = metadata.hasAnyContent ? .ready : .unavailable
            let error: MetadataError? = metadata.hasAnyContent ? nil : .parseFailed

            return MetadataUpdate(
                fetchedTitle: metadata.title,
                fetchedBody: metadata.body,
                fetchedBodyKind: metadata.bodyKind,
                bodySummary: metadata.summary,
                description: metadata.description,
                thumbnailURL: metadata.thumbnail,
                badgeImageURL: metadata.badgeImageURL,
                metadataState: state,
                metadataFetchedAt: Date(),
                metadataError: error,
                canonicalID: metadata.canonicalID,
                normalizedHost: document.finalURL.host?.lowercased(),
                rawSourceHost: document.finalURL.host?.lowercased()
            )
        } catch FetchFailure.failed(let error) {
            return failedUpdate(error: error)
        } catch FetchFailure.unavailable(let error) {
            return unavailableUpdate(error: error)
        } catch {
            let nsError = error as NSError
            if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorTimedOut {
                return failedUpdate(error: .timeout)
            }
            return failedUpdate(error: .networkIO)
        }
    }

    private func fetchHTMLDocument(url: URL) async throws -> HTMLDocument {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue(Self.browserLikeUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml", forHTTPHeaderField: "Accept")
        request.setValue(Locale.preferredLanguages.first ?? "ja-JP", forHTTPHeaderField: "Accept-Language")

        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw FetchFailure.unavailable(.parseFailed)
        }

        try validateHTTPResponse(httpResponse)
        if data.count > Self.maxBodyBytes {
            throw FetchFailure.unavailable(.oversized)
        }

        let mime = httpResponse.mimeType?.lowercased()
        if let mime, mime != "text/html" && mime != "application/xhtml+xml" {
            throw FetchFailure.unavailable(.nonHTML)
        }

        guard let html = decodeHTML(data: data) else {
            throw FetchFailure.unavailable(.parseFailed)
        }

        return HTMLDocument(html: html, finalURL: httpResponse.url ?? url)
    }

    private func fetchBadgeImageURL(from pageURLString: String?) async -> String? {
        guard let pageURLString,
              let pageURL = URL(string: pageURLString),
              let document = try? await fetchHTMLDocument(url: pageURL) else {
            return nil
        }

        return absoluteURLString(metaContent(property: "og:image", html: document.html), relativeTo: document.finalURL)
            .flatMap(normalizeYouTubeBadgeImageURL)
            ?? absoluteURLString(metaContent(property: "twitter:image", html: document.html), relativeTo: document.finalURL)
                .flatMap(normalizeYouTubeBadgeImageURL)
            ?? extractYouTubeChannelBadge(html: document.html)
    }

    private func fetchYouTubePageBadgeImageURL(from pageURL: URL) async -> String? {
        guard let document = try? await fetchHTMLDocument(url: pageURL) else {
            return nil
        }
        return extractYouTubeChannelBadge(html: document.html)
    }

    private func fetchJSONObject(url: URL) async -> [String: Any]? {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue(Self.browserLikeUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json,text/plain,*/*", forHTTPHeaderField: "Accept")
        request.setValue(Locale.preferredLanguages.first ?? "ja-JP", forHTTPHeaderField: "Accept-Language")

        do {
            let (data, response) = try await session.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else { return nil }
            try validateHTTPResponse(httpResponse)
            guard data.count <= Self.maxBodyBytes else { return nil }
            return try JSONSerialization.jsonObject(with: data) as? [String: Any]
        } catch {
            return nil
        }
    }

    private func validateHTTPResponse(_ response: HTTPURLResponse) throws {
        if response.statusCode == 404 {
            throw FetchFailure.unavailable(.http404)
        }
        if (400...499).contains(response.statusCode) {
            throw FetchFailure.unavailable(.http4xx)
        }
        if (500...599).contains(response.statusCode) {
            throw FetchFailure.failed(.http5xx)
        }
    }

    private func failedUpdate(error: MetadataError) -> MetadataUpdate {
        MetadataUpdate(
            fetchedTitle: nil,
            fetchedBody: nil,
            fetchedBodyKind: nil,
            bodySummary: nil,
            description: nil,
            thumbnailURL: nil,
            badgeImageURL: nil,
            metadataState: .failed,
            metadataFetchedAt: Date(),
            metadataError: error,
            canonicalID: nil,
            normalizedHost: nil,
            rawSourceHost: nil
        )
    }

    private func unavailableUpdate(error: MetadataError) -> MetadataUpdate {
        MetadataUpdate(
            fetchedTitle: nil,
            fetchedBody: nil,
            fetchedBodyKind: nil,
            bodySummary: nil,
            description: nil,
            thumbnailURL: nil,
            badgeImageURL: nil,
            metadataState: .unavailable,
            metadataFetchedAt: Date(),
            metadataError: error,
            canonicalID: nil,
            normalizedHost: nil,
            rawSourceHost: nil
        )
    }

    private func decodeHTML(data: Data) -> String? {
        if let html = String(data: data, encoding: .utf8) {
            return html
        }
        if let html = String(data: data, encoding: .shiftJIS) {
            return html
        }
        return String(data: data, encoding: .isoLatin1)
    }

    private func fetchYouTubeOEmbedMetadata(inputURL: URL) async -> ExtractedMetadata {
        guard let endpoint = youtubeOEmbedEndpointBuilder(inputURL),
              let payload = await fetchJSONObject(url: endpoint) else {
            return .empty
        }
        var badgeImageURL = await fetchBadgeImageURL(from: stringField(payload, "author_url"))
        if badgeImageURL == nil {
            badgeImageURL = await fetchYouTubePageBadgeImageURL(from: inputURL)
        }

        return ExtractedMetadata(
            title: stringField(payload, "title"),
            body: nil,
            bodyKind: nil,
            summary: nil,
            description: nil,
            thumbnail: stringField(payload, "thumbnail_url"),
            badgeImageURL: badgeImageURL,
            canonicalID: nil
        )
    }

    private func fetchTikTokOEmbedMetadata(inputURL: URL) async -> ExtractedMetadata {
        guard let endpoint = tiktokOEmbedEndpointBuilder(inputURL),
              let payload = await fetchJSONObject(url: endpoint) else {
            return .empty
        }

        let body = firstNonBlank(
            stringField(payload, "title"),
            oEmbedParagraphBody(from: stringField(payload, "html"))
        ).flatMap(normalizeTikTokBodyText)
        let badgeImageURL = await fetchBadgeImageURL(from: normalizeTikTokAuthorURL(stringField(payload, "author_url")))

        return ExtractedMetadata(
            title: normalizeTikTokTitleText(stringField(payload, "author_name")),
            body: body,
            bodyKind: body == nil ? nil : .webDescription,
            summary: summarize(body),
            description: body,
            thumbnail: normalizeURLAttribute(stringField(payload, "thumbnail_url")),
            badgeImageURL: badgeImageURL,
            canonicalID: stringField(payload, "embed_product_id")
        )
    }

    private func fetchTikTokFallbackMetadata(inputURL: URL) async -> ExtractedMetadata {
        guard let endpoint = tiktokFallbackEndpointBuilder(inputURL),
              let payload = await fetchTikTokFallbackJSONObject(url: endpoint) else {
            return .empty
        }

        let data = (payload["data"] as? [String: Any]) ?? payload
        let author = data["author"] as? [String: Any]
        let title = firstNonBlank(
            normalizeTikTokTitleText(author.flatMap { stringField($0, "nickname") }),
            author.flatMap { stringField($0, "unique_id", "uniqueId") }.flatMap { normalizeTikTokTitleText("@\($0.trimmingCharacters(in: CharacterSet(charactersIn: "@")))") }
        )
        let body = normalizeTikTokBodyText(stringField(data, "title", "desc", "description"))
        let thumbnail = firstNonBlank(
            tikTokURLField(data, "cover", "origin_cover", "originCover", "dynamic_cover", "dynamicCover", "thumbnail_url", "cover_url"),
            tikTokFirstURL(in: data["imagePost"]),
            tikTokImagePostURL(from: data),
            tikTokFirstURL(in: data["images"]),
            tikTokFirstURL(in: data["image"])
        )
        let badgeImageURL = author.flatMap {
            firstNonBlank(
                tikTokURLField($0, "avatar", "avatar_thumb", "avatarThumb", "avatar_medium", "avatarMedium", "avatar_larger", "avatarLarger"),
                tikTokFirstURL(in: $0)
            )
        }

        return ExtractedMetadata(
            title: title,
            body: body,
            bodyKind: body == nil ? nil : .webDescription,
            summary: summarize(body),
            description: body,
            thumbnail: thumbnail,
            badgeImageURL: badgeImageURL,
            canonicalID: stringField(data, "id", "aweme_id", "video_id")
        )
    }

    private func fetchTikTokFallbackJSONObject(url: URL) async -> [String: Any]? {
        for attempt in 0..<3 {
            guard let payload = await fetchJSONObject(url: url) else {
                return nil
            }
            if isTikTokFallbackRateLimited(payload), attempt < 2 {
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                continue
            }
            return payload
        }
        return nil
    }

    private func isTikTokFallbackRateLimited(_ payload: [String: Any]) -> Bool {
        let code = (payload["code"] as? NSNumber)?.intValue ?? payload["code"] as? Int
        let message = stringField(payload, "msg", "message")?.lowercased() ?? ""
        return code == -1 && (message.contains("limit") || message.contains("request/second"))
    }

    private func fetchXOEmbedMetadata(inputURL: URL, statusID: String) async -> ExtractedMetadata {
        guard let endpoint = xOEmbedEndpointBuilder(inputURL),
              let payload = await fetchJSONObject(url: endpoint) else {
            return .empty
        }

        let body = oEmbedParagraphBody(from: stringField(payload, "html"))
        return ExtractedMetadata(
            title: stringField(payload, "author_name"),
            body: body,
            bodyKind: body == nil ? nil : .xPostText,
            summary: summarize(body),
            description: body,
            thumbnail: stringField(payload, "thumbnail_url"),
            badgeImageURL: nil,
            canonicalID: statusID
        )
    }

    private func fetchXSyndicationMetadata(statusID: String) async -> ExtractedMetadata {
        guard let endpoint = xSyndicationEndpointBuilder(statusID),
              let payload = await fetchJSONObject(url: endpoint) else {
            return .empty
        }

        let user = payload["user"] as? [String: Any]
        let body = stringField(payload, "text", "full_text")
        let thumbnail = firstXPhotoURL(payload) ?? firstXMediaURL(payload)
        let badgeImageURL = user
            .flatMap { stringField($0, "profile_image_url_https", "profile_image_url") }
            .map { $0.replacingOccurrences(of: "_normal.", with: "_400x400.") }

        return ExtractedMetadata(
            title: user.flatMap { stringField($0, "name", "screen_name") },
            body: body,
            bodyKind: body == nil ? nil : .xPostText,
            summary: summarize(body),
            description: body,
            thumbnail: thumbnail,
            badgeImageURL: badgeImageURL,
            canonicalID: stringField(payload, "id_str", "id") ?? statusID
        )
    }

    private func fetchInstagramPublicOEmbedMetadata(inputURL: URL) async -> ExtractedMetadata {
        guard let endpoint = instagramPublicOEmbedEndpointBuilder(inputURL),
              let payload = await fetchJSONObject(url: endpoint) else {
            return .empty
        }

        let body = firstNonBlank(
            stringField(payload, "title"),
            oEmbedParagraphBody(from: stringField(payload, "html"))
        )
        let badgeImageURL = await fetchBadgeImageURL(from: stringField(payload, "author_url"))

        return ExtractedMetadata(
            title: stringField(payload, "author_name"),
            body: body,
            bodyKind: body == nil ? nil : .instagramCaption,
            summary: summarize(body),
            description: body,
            thumbnail: stringField(payload, "thumbnail_url"),
            badgeImageURL: badgeImageURL,
            canonicalID: stringField(payload, "media_id")
        )
    }

    private func fetchInstagramCaptionedEmbedMetadata(inputURL: URL) async -> ExtractedMetadata {
        guard let endpoint = instagramCaptionedEmbedEndpointBuilder(inputURL),
              let document = try? await fetchHTMLDocument(url: endpoint) else {
            return .empty
        }

        let body = firstJSONMatch(
            pattern: #""edge_media_to_caption"\s*:\s*\{"edges"\s*:\s*\[\s*\{"node"\s*:\s*\{"text"\s*:\s*"([^"]+)""#,
            html: document.html
        )
        let username = firstJSONMatch(pattern: #""owner"\s*:\s*\{[^}]*"username"\s*:\s*"([^"]+)""#, html: document.html)
        let thumbnail = firstJSONMatch(pattern: #""display_url"\s*:\s*"([^"]+)""#, html: document.html)
            ?? firstJSONMatch(pattern: #""thumbnail_src"\s*:\s*"([^"]+)""#, html: document.html)
        let badgeImageURL = instagramProfileBadgeImage(in: document.html)

        return ExtractedMetadata(
            title: username.map { "@\($0)" },
            body: body,
            bodyKind: body == nil ? nil : .instagramCaption,
            summary: summarize(body),
            description: body,
            thumbnail: thumbnail,
            badgeImageURL: badgeImageURL,
            canonicalID: nil
        )
    }

    private func extractMetadata(html: String, url: URL, service: ServiceType) -> ExtractedMetadata {
        let title = metaContent(property: "og:title", html: html)
            ?? titleTag(in: html)
        let ogDescription = metaContent(property: "og:description", html: html)
        let twitterDescription = metaContent(property: "twitter:description", html: html)
        let metaDescription = metaContent(name: "description", html: html)
        let thumbnail = metaContent(property: "og:image", html: html)
            ?? metaContent(property: "twitter:image", html: html)
        let canonicalURL = canonicalLink(in: html)
        let canonicalID = URLRules.extractXStatusID(from: canonicalURL ?? url.absoluteString)

        switch service {
        case .youtube:
            let jsonLDDescription = firstMatch(
                pattern: #""description"\s*:\s*"([^"]+)""#,
                html: html
            )
            let body = jsonLDDescription ?? ogDescription ?? twitterDescription ?? metaDescription
            return ExtractedMetadata(
                title: title,
                body: body,
                bodyKind: body == nil ? nil : .youtubeDescription,
                summary: summarize(body),
                description: ogDescription ?? twitterDescription ?? metaDescription,
                thumbnail: thumbnail,
                badgeImageURL: extractYouTubeChannelBadge(html: html),
                canonicalID: canonicalID
            )
        case .instagram:
            let body = ogDescription ?? twitterDescription ?? metaDescription
            return ExtractedMetadata(
                title: title,
                body: body,
                bodyKind: body == nil ? nil : .instagramCaption,
                summary: summarize(body),
                description: ogDescription ?? metaDescription,
                thumbnail: thumbnail,
                badgeImageURL: instagramProfileBadgeImage(in: html),
                canonicalID: canonicalID
            )
        case .x:
            let body = ogDescription ?? twitterDescription ?? metaDescription
            return ExtractedMetadata(
                title: title,
                body: body,
                bodyKind: body == nil ? nil : .xPostText,
                summary: summarize(body),
                description: ogDescription ?? twitterDescription ?? metaDescription,
                thumbnail: thumbnail,
                badgeImageURL: nil,
                canonicalID: canonicalID
            )
        case .tiktok:
            let embedded = extractTikTokEmbeddedMetadata(html: html)
            let excerpt = articleExcerpt(in: html)
            let body = firstNonBlank(
                embedded.body,
                extractTikTokCaptionFromShareDescription(ogDescription ?? twitterDescription ?? metaDescription),
                normalizeTikTokBodyText(metaDescription),
                normalizeTikTokBodyText(excerpt)
            )
            let favicon = faviconURL(in: html, pageURL: url)
            return ExtractedMetadata(
                title: firstNonBlank(
                    embedded.title,
                    normalizeTikTokTitleText(title)
                ),
                body: body,
                bodyKind: body == nil ? nil : .webDescription,
                summary: summarize(body),
                description: firstNonBlank(embedded.description, body, ogDescription, twitterDescription, metaDescription),
                thumbnail: firstNonBlank(embedded.thumbnail, normalizeURLAttribute(thumbnail)),
                badgeImageURL: firstNonBlank(embedded.badgeImageURL, favicon),
                canonicalID: firstNonBlank(
                    embedded.canonicalID,
                    extractTikTokVideoID(from: canonicalURL),
                    extractTikTokVideoID(from: url.absoluteString)
                )
            )
        case .web, .all:
            let excerpt = articleExcerpt(in: html)
            let body = metaDescription ?? excerpt
            let favicon = faviconURL(in: html, pageURL: url)
            return ExtractedMetadata(
                title: title,
                body: body,
                bodyKind: body == nil ? nil : (metaDescription != nil ? .webDescription : .webExcerpt),
                summary: summarize(body),
                description: ogDescription ?? twitterDescription ?? metaDescription,
                thumbnail: thumbnail,
                badgeImageURL: favicon,
                canonicalID: canonicalID
            )
        }
    }

    private func metaContent(property: String, html: String) -> String? {
        metaContent(attribute: "property", value: property, html: html)
    }

    private func metaContent(name: String, html: String) -> String? {
        metaContent(attribute: "name", value: name, html: html)
    }

    private func metaContent(attribute: String, value: String, html: String) -> String? {
        let escapedValue = NSRegularExpression.escapedPattern(for: value)
        let pattern = #"<meta\b[^>]*\#(attribute)=["']\#(escapedValue)["'][^>]*>"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return nil
        }
        let range = NSRange(location: 0, length: (html as NSString).length)
        for match in regex.matches(in: html, options: [], range: range) {
            guard let tagRange = Range(match.range, in: html) else { continue }
            if let content = attributeValue("content", in: String(html[tagRange])) {
                return content
            }
        }
        return nil
    }

    private func titleTag(in html: String) -> String? {
        firstMatch(pattern: #"<title[^>]*>(.*?)</title>"#, html: html)
    }

    private func canonicalLink(in html: String) -> String? {
        let pattern = #"<link\b[^>]*rel=["']canonical["'][^>]*>"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return nil
        }
        let range = NSRange(location: 0, length: (html as NSString).length)
        for match in regex.matches(in: html, options: [], range: range) {
            guard let tagRange = Range(match.range, in: html) else { continue }
            if let href = attributeValue("href", in: String(html[tagRange])) {
                return href
            }
        }
        return nil
    }

    private func faviconURL(in html: String, pageURL: URL) -> String? {
        let pattern = #"<link\b[^>]*rel=["'][^"']*(?:icon|apple-touch-icon)[^"']*["'][^>]*>"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return defaultFaviconURL(for: pageURL)
        }
        let range = NSRange(location: 0, length: (html as NSString).length)
        var genericIcon: String?
        for match in regex.matches(in: html, options: [], range: range) {
            guard let tagRange = Range(match.range, in: html),
                  let href = attributeValue("href", in: String(html[tagRange])) else {
                continue
            }
            let tag = String(html[tagRange])
            let resolved = absoluteURLString(href, relativeTo: pageURL)
            if tag.range(of: "apple-touch-icon", options: .caseInsensitive) != nil {
                return resolved
            }
            if let resolved, isRenderableBadgeImageURL(resolved) {
                genericIcon = genericIcon ?? resolved
            }
        }
        return genericIcon ?? defaultFaviconURL(for: pageURL)
    }

    private func isRenderableBadgeImageURL(_ value: String) -> Bool {
        let lowercased = value.lowercased()
        return !lowercased.hasSuffix(".ico") && !lowercased.hasSuffix(".svg")
    }

    private func defaultFaviconURL(for pageURL: URL) -> String? {
        guard let host = pageURL.host else { return nil }
        var components = URLComponents(string: "https://www.google.com/s2/favicons")
        components?.queryItems = [
            URLQueryItem(name: "domain", value: host),
            URLQueryItem(name: "sz", value: "128"),
        ]
        return components?.url?.absoluteString
    }

    private func absoluteURLString(_ value: String?, relativeTo baseURL: URL) -> String? {
        guard let value = normalizeText(value) else { return nil }
        return URL(string: value, relativeTo: baseURL)?.absoluteURL.absoluteString ?? value
    }

    private func extractYouTubeChannelBadge(html: String) -> String? {
        let normalizedHTML = html
            .replacingOccurrences(of: #"\/"#, with: "/")
            .replacingOccurrences(of: #"\\/"#, with: "/")
            .replacingOccurrences(of: #"\\u002F"#, with: "/")
            .replacingOccurrences(of: #"\u002F"#, with: "/")
            .replacingOccurrences(of: #"\\u0026"#, with: "&")
            .replacingOccurrences(of: #"\u0026"#, with: "&")
            .replacingOccurrences(of: #"\\u003d"#, with: "=")
            .replacingOccurrences(of: #"\u003d"#, with: "=")
            .replacingOccurrences(of: "&amp;", with: "&")
        let pattern = #"https://yt3\.(?:ggpht|googleusercontent)\.com[^"'\\\s<)]+"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return nil
        }
        let range = NSRange(location: 0, length: (normalizedHTML as NSString).length)
        let candidates = regex.matches(in: normalizedHTML, options: [], range: range).compactMap { match -> String? in
            guard let swiftRange = Range(match.range, in: normalizedHTML) else { return nil }
            return normalizeText(String(normalizedHTML[swiftRange]))
                .flatMap(normalizeYouTubeBadgeImageURL)
        }
        let preferredSizes = ["=s176", "=s200", "=s240", "=s250", "=s288", "=s400", "=s512", "=s800", "=s900"]
        for size in preferredSizes {
            if let match = candidates.first(where: { $0.contains(size) }) {
                return match
            }
        }
        return candidates.first { candidate in
            candidate.contains("=s")
        } ?? candidates.first
    }

    private func normalizeYouTubeBadgeImageURL(_ value: String?) -> String? {
        guard var value = normalizeText(value) else { return nil }
        guard value.contains("yt3.ggpht.com") || value.contains("yt3.googleusercontent.com") else {
            return value
        }
        value = value
            .replacingOccurrences(of: #"\/"#, with: "/")
            .replacingOccurrences(of: #"\\/"#, with: "/")
            .replacingOccurrences(of: #"\\u0026"#, with: "&")
            .replacingOccurrences(of: #"\u0026"#, with: "&")
            .replacingOccurrences(of: #"\\u003d"#, with: "=")
            .replacingOccurrences(of: #"\u003d"#, with: "=")
            .replacingOccurrences(of: "&amp;", with: "&")

        if let regex = try? NSRegularExpression(pattern: #"=s\d+"#, options: []) {
            let range = NSRange(location: 0, length: (value as NSString).length)
            if regex.firstMatch(in: value, options: [], range: range) != nil {
                return regex.stringByReplacingMatches(
                    in: value,
                    options: [],
                    range: range,
                    withTemplate: "=s176"
                )
            }
        }
        return value
    }

    private func instagramProfileBadgeImage(in html: String) -> String? {
        firstJSONMatch(pattern: #""profile_pic_url_hd"\s*:\s*"([^"]+)""#, html: html)
            ?? firstJSONMatch(pattern: #""profile_pic_url"\s*:\s*"([^"]+)""#, html: html)
            ?? firstHTMLAttributeMatch(
                pattern: #"<[^>]*class=["'][^"']*AvatarContainer[^"']*["'][\s\S]*?<img[^>]*\bsrc=["']([^"']+)["']"#,
                html: html
            )
            ?? firstHTMLAttributeMatch(
                pattern: #"<a[^>]*class=["'][^"']*Avatar[^"']*["'][\s\S]*?<img[^>]*\bsrc=["']([^"']+)["']"#,
                html: html
            )
    }

    private func extractTikTokEmbeddedMetadata(html: String) -> ExtractedMetadata {
        var result = ExtractedMetadata.empty
        for payload in jsonScriptPayloads(in: html) {
            guard let data = payload.data(using: .utf8),
                  let root = try? JSONSerialization.jsonObject(with: data) else {
                continue
            }
            result = result.merging(with: extractTikTokEmbeddedMetadata(from: root))
        }
        return result.merging(with: extractTikTokRegexMetadata(html: html))
    }

    private func jsonScriptPayloads(in html: String) -> [String] {
        guard let regex = try? NSRegularExpression(
            pattern: #"<script\b[^>]*>([\s\S]*?)</script>"#,
            options: [.caseInsensitive]
        ) else {
            return []
        }

        let range = NSRange(location: 0, length: (html as NSString).length)
        return regex.matches(in: html, options: [], range: range).compactMap { match in
            guard let swiftRange = Range(match.range(at: 1), in: html) else { return nil }
            let payload = String(html[swiftRange]).trimmingCharacters(in: .whitespacesAndNewlines)
            guard payload.hasPrefix("{") || payload.hasPrefix("[") else { return nil }
            return payload
        }
    }

    private func extractTikTokEmbeddedMetadata(from root: Any) -> ExtractedMetadata {
        var result = ExtractedMetadata.empty
        traverseJSONObjects(root) { object in
            if let item = firstTikTokItemStruct(in: object) {
                result = result.merging(with: extractTikTokItemStructMetadata(item))
            }

            if let shareMeta = (object["shareMeta"] as? [String: Any]) ?? (looksLikeTikTokShareMeta(object) ? object : nil) {
                result = result.merging(with: extractTikTokShareMetaMetadata(shareMeta))
            }

            if let canonical = stringField(object, "canonical"),
               canonical.localizedCaseInsensitiveContains("tiktok.com/"),
               let videoID = extractTikTokVideoID(from: canonical) {
                result = result.merging(with: ExtractedMetadata(
                    title: nil,
                    body: nil,
                    bodyKind: nil,
                    summary: nil,
                    description: nil,
                    thumbnail: nil,
                    badgeImageURL: nil,
                    canonicalID: videoID
                ))
            }
        }
        return result
    }

    private func traverseJSONObjects(_ value: Any, visit: ([String: Any]) -> Void) {
        if let object = value as? [String: Any] {
            visit(object)
            for child in object.values {
                traverseJSONObjects(child, visit: visit)
            }
        } else if let array = value as? [Any] {
            for child in array {
                traverseJSONObjects(child, visit: visit)
            }
        }
    }

    private func firstTikTokItemStruct(in object: [String: Any]) -> [String: Any]? {
        if let itemStruct = object["itemStruct"] as? [String: Any] {
            return itemStruct
        }
        if let itemInfo = object["itemInfo"] as? [String: Any],
           let itemStruct = itemInfo["itemStruct"] as? [String: Any] {
            return itemStruct
        }
        return looksLikeTikTokItemStruct(object) ? object : nil
    }

    private func looksLikeTikTokItemStruct(_ object: [String: Any]) -> Bool {
        stringField(object, "id") != nil
            && object["author"] as? [String: Any] != nil
            && (stringField(object, "desc") != nil || object["video"] as? [String: Any] != nil || object["imagePost"] as? [String: Any] != nil)
    }

    private func looksLikeTikTokShareMeta(_ object: [String: Any]) -> Bool {
        stringField(object, "cover_url") != nil
            && (stringField(object, "desc") != nil || stringField(object, "title") != nil)
    }

    private func extractTikTokItemStructMetadata(_ item: [String: Any]) -> ExtractedMetadata {
        let author = item["author"] as? [String: Any]
        let video = item["video"] as? [String: Any]
        let imagePost = item["imagePost"] as? [String: Any]
        let shareInfo = item["shareInfo"] as? [String: Any]
        let title = firstNonBlank(
            author.flatMap { normalizeTikTokTitleText(stringField($0, "nickname")) },
            author.flatMap { stringField($0, "uniqueId", "unique_id") }.flatMap { normalizeTikTokTitleText("@\($0.trimmingCharacters(in: CharacterSet(charactersIn: "@")))") },
            shareInfo.flatMap { normalizeTikTokTitleText(stringField($0, "title")) },
            imagePost.flatMap { normalizeTikTokTitleText(stringField($0, "title")) }
        )
        let body = firstNonBlank(
            normalizeTikTokBodyText(stringField(item, "desc")),
            shareInfo.flatMap { extractTikTokCaptionFromShareDescription(stringField($0, "desc")) },
            imagePost.flatMap { normalizeTikTokBodyText(stringField($0, "title")) }
        )
        let thumbnail = firstNonBlank(
            video.flatMap { tikTokURLField($0, "cover", "originCover", "dynamicCover", "reflowCover", "origin_cover", "dynamic_cover") },
            video.flatMap { tikTokFirstURLField($0, "shareCover") },
            imagePost.flatMap(tikTokImagePostURL)
        )
        let badgeImageURL = author.flatMap {
            firstNonBlank(
                tikTokURLField($0, "avatarLarger", "avatar_larger", "avatarMedium", "avatar_medium", "avatarThumb", "avatar_thumb", "avatar"),
                tikTokFirstURL(in: $0)
            )
        }

        return ExtractedMetadata(
            title: title,
            body: body,
            bodyKind: body == nil ? nil : .webDescription,
            summary: summarize(body),
            description: body,
            thumbnail: thumbnail,
            badgeImageURL: badgeImageURL,
            canonicalID: stringField(item, "id")
        )
    }

    private func tikTokImagePostURL(from imagePost: [String: Any]) -> String? {
        firstNonBlank(
            tikTokFirstURL(in: imagePost["cover"]),
            tikTokFirstURL(in: imagePost["shareCover"]),
            tikTokFirstURL(in: imagePost["images"]),
            tikTokFirstURL(in: imagePost["image"]),
            tikTokFirstURL(in: imagePost["imageURL"]),
            tikTokFirstURL(in: imagePost["image_url"])
        )
    }

    private func tikTokURLField(_ payload: [String: Any], _ keys: String...) -> String? {
        for key in keys {
            if let url = tikTokFirstURL(in: payload[key]) {
                return url
            }
        }
        return nil
    }

    private func tikTokFirstURLField(_ payload: [String: Any], _ keys: String...) -> String? {
        for key in keys {
            if let url = tikTokFirstURL(in: payload[key]) {
                return url
            }
        }
        return nil
    }

    private func tikTokFirstURL(in value: Any?) -> String? {
        if let string = value as? String {
            guard let normalized = normalizeURLAttribute(string),
                  normalized.lowercased().hasPrefix("http") else {
                return nil
            }
            return normalized
        }
        if let array = value as? [Any] {
            for item in array {
                if let url = tikTokFirstURL(in: item) {
                    return url
                }
            }
            return nil
        }
        if let object = value as? [String: Any] {
            let preferredKeys = [
                "urlList", "url_list", "urls",
                "imageURL", "image_url",
                "cover", "shareCover",
                "url", "uri",
            ]
            for key in preferredKeys {
                if let url = tikTokFirstURL(in: object[key]) {
                    return url
                }
            }
            for child in object.values {
                if let url = tikTokFirstURL(in: child) {
                    return url
                }
            }
        }
        return nil
    }

    private func extractTikTokShareMetaMetadata(_ shareMeta: [String: Any]) -> ExtractedMetadata {
        let body = extractTikTokCaptionFromShareDescription(stringField(shareMeta, "desc"))
        return ExtractedMetadata(
            title: normalizeTikTokTitleText(stringField(shareMeta, "title")),
            body: body,
            bodyKind: body == nil ? nil : .webDescription,
            summary: summarize(body),
            description: body ?? normalizeTikTokBodyText(stringField(shareMeta, "desc")),
            thumbnail: normalizeURLAttribute(stringField(shareMeta, "cover_url")),
            badgeImageURL: nil,
            canonicalID: nil
        )
    }

    private func extractTikTokRegexMetadata(html: String) -> ExtractedMetadata {
        let body = firstNonBlank(
            firstJSONMatch(pattern: #""desc"\s*:\s*"((?:\\.|[^"\\])*)""#, html: html).flatMap(normalizeTikTokBodyText),
            firstJSONMatch(pattern: #""description"\s*:\s*"((?:\\.|[^"\\])*)""#, html: html).flatMap(normalizeTikTokBodyText)
        )
        let title = firstNonBlank(
            firstJSONMatch(pattern: #""nickname"\s*:\s*"((?:\\.|[^"\\])*)""#, html: html).flatMap(normalizeTikTokTitleText),
            firstJSONMatch(pattern: #""uniqueId"\s*:\s*"((?:\\.|[^"\\])*)""#, html: html).flatMap { normalizeTikTokTitleText("@\($0.trimmingCharacters(in: CharacterSet(charactersIn: "@")))") }
        )
        let thumbnail = firstNonBlank(
            firstJSONMatch(pattern: #""cover"\s*:\s*"((?:\\.|[^"\\])*)""#, html: html).flatMap(normalizeURLAttribute),
            firstJSONMatch(pattern: #""cover_url"\s*:\s*"((?:\\.|[^"\\])*)""#, html: html).flatMap(normalizeURLAttribute)
        )
        let badgeImageURL = firstNonBlank(
            firstJSONMatch(pattern: #""avatarLarger"\s*:\s*"((?:\\.|[^"\\])*)""#, html: html).flatMap(normalizeURLAttribute),
            firstJSONMatch(pattern: #""avatarMedium"\s*:\s*"((?:\\.|[^"\\])*)""#, html: html).flatMap(normalizeURLAttribute),
            firstJSONMatch(pattern: #""avatarThumb"\s*:\s*"((?:\\.|[^"\\])*)""#, html: html).flatMap(normalizeURLAttribute),
            firstJSONMatch(pattern: #""avatar"\s*:\s*"((?:\\.|[^"\\])*)""#, html: html).flatMap(normalizeURLAttribute)
        )

        return ExtractedMetadata(
            title: title,
            body: body,
            bodyKind: body == nil ? nil : .webDescription,
            summary: summarize(body),
            description: body,
            thumbnail: thumbnail,
            badgeImageURL: badgeImageURL,
            canonicalID: firstJSONMatch(pattern: #""id"\s*:\s*"([0-9]{8,})""#, html: html)
        )
    }

    private func extractTikTokCaptionFromShareDescription(_ rawDescription: String?) -> String? {
        guard let source = normalizeTikTokBodyText(rawDescription) else { return nil }
        if let quoted = firstRawMatch(pattern: #"[“"]([^“”"]{4,})[”"]"#, html: source)
            .flatMap(normalizeTikTokBodyText) {
            return quoted
        }
        let withoutStats = source.replacingOccurrences(
            of: #"^\s*[\d.,KkMm万億]+\s+[^“"]*"#,
            with: "",
            options: .regularExpression
        )
        return normalizeTikTokBodyText(withoutStats)
    }

    private func normalizeTikTokTitleText(_ raw: String?) -> String? {
        guard var title = normalizeText(raw) else { return nil }
        title = title
            .replacingOccurrences(of: "TikTok · ", with: "")
            .replacingOccurrences(of: " on TikTok", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty,
              title != "@",
              title.caseInsensitiveCompare("TikTok") != .orderedSame,
              title.caseInsensitiveCompare("TikTok - Make Your Day") != .orderedSame else {
            return nil
        }
        return title
    }

    private func normalizeTikTokBodyText(_ raw: String?) -> String? {
        guard let body = normalizeText(raw) else { return nil }
        guard body.caseInsensitiveCompare("TikTok - Make Your Day") != .orderedSame,
              body.caseInsensitiveCompare("Discover more on TikTok") != .orderedSame else {
            return nil
        }
        return body
    }

    private func normalizeTikTokAuthorURL(_ raw: String?) -> String? {
        guard let url = normalizeURLAttribute(raw),
              let parsed = URL(string: url) else {
            return normalizeURLAttribute(raw)
        }
        let host = parsed.host?.lowercased() ?? ""
        let path = parsed.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if host.hasSuffix("tiktok.com") && path.isEmpty {
            return nil
        }
        return url
    }

    private func normalizeURLAttribute(_ raw: String?) -> String? {
        guard let raw else { return nil }
        return normalizeText(
            decodeHTMLEntities(raw)
                .replacingOccurrences(of: #"\/"#, with: "/")
                .replacingOccurrences(of: #"\\/"#, with: "/")
                .replacingOccurrences(of: #"\\u002F"#, with: "/")
                .replacingOccurrences(of: #"\u002F"#, with: "/")
                .replacingOccurrences(of: #"\\u0026"#, with: "&")
                .replacingOccurrences(of: #"\u0026"#, with: "&")
        )
    }

    private func firstArrayString(_ payload: [String: Any], _ keys: String...) -> String? {
        for key in keys {
            if let values = payload[key] as? [String],
               let first = values.first.flatMap(normalizeText) {
                return first
            }
            if let values = payload[key] as? [Any] {
                for value in values {
                    if let string = value as? String, let normalized = normalizeText(string) {
                        return normalized
                    }
                }
            }
        }
        return nil
    }

    private func extractTikTokVideoID(from value: String?) -> String? {
        guard let value else { return nil }
        return firstRawMatch(pattern: #"/video/([0-9]{8,})"#, html: value)
            ?? firstRawMatch(pattern: #"/photo/([0-9]{8,})"#, html: value)
            ?? firstRawMatch(pattern: #"embed_product_id[=/]([0-9]{8,})"#, html: value)
    }

    private func firstHTMLAttributeMatch(pattern: String, html: String, captureGroup: Int = 1) -> String? {
        firstRawMatch(pattern: pattern, html: html, captureGroup: captureGroup)
            .map(decodeHTMLEntities)
            .flatMap(normalizeText)
    }

    private func articleExcerpt(in html: String) -> String? {
        firstMatch(pattern: #"<p[^>]*>(.*?)</p>"#, html: html)
            .map(stripHTMLTags)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .flatMap { $0.isEmpty ? nil : $0 }
    }

    private func firstMatch(pattern: String, html: String, captureGroup: Int = 1) -> String? {
        firstRawMatch(pattern: pattern, html: html, captureGroup: captureGroup)
            .map(stripHTMLTags)
    }

    private func firstRawMatch(pattern: String, html: String, captureGroup: Int = 1) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else {
            return nil
        }
        let range = NSRange(location: 0, length: (html as NSString).length)
        guard let match = regex.firstMatch(in: html, options: [], range: range),
              let swiftRange = Range(match.range(at: captureGroup), in: html) else {
            return nil
        }
        return String(html[swiftRange])
    }

    private func firstJSONMatch(pattern: String, html: String, captureGroup: Int = 1) -> String? {
        firstRawMatch(pattern: pattern, html: html, captureGroup: captureGroup)
            .map(decodeJSONStringContent)
            .flatMap(normalizeText)
    }

    private func attributeValue(_ name: String, in tag: String) -> String? {
        let escapedName = NSRegularExpression.escapedPattern(for: name)
        return firstRawMatch(
            pattern: #"\#(escapedName)=["']([^"']*)["']"#,
            html: tag
        )
        .map(decodeHTMLEntities)
        .flatMap(normalizeText)
    }

    private func stripHTMLTags(_ value: String) -> String {
        decodeHTMLEntities(value)
            .replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private func decodeHTMLEntities(_ value: String) -> String {
        decodeNumericHTMLEntities(
            value
                .replacingOccurrences(of: "&quot;", with: "\"")
                .replacingOccurrences(of: "&#39;", with: "'")
                .replacingOccurrences(of: "&apos;", with: "'")
                .replacingOccurrences(of: "&lt;", with: "<")
                .replacingOccurrences(of: "&gt;", with: ">")
                .replacingOccurrences(of: "&nbsp;", with: " ")
                .replacingOccurrences(of: "&amp;", with: "&")
        )
    }

    private func decodeNumericHTMLEntities(_ value: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: #"&#(x?[0-9A-Fa-f]+);"#) else {
            return value
        }
        let nsValue = value as NSString
        let matches = regex.matches(in: value, range: NSRange(location: 0, length: nsValue.length)).reversed()
        var result = value
        for match in matches {
            guard let codeRange = Range(match.range(at: 1), in: result),
                  let fullRange = Range(match.range, in: result) else {
                continue
            }
            let rawCode = String(result[codeRange])
            let radix = rawCode.hasPrefix("x") || rawCode.hasPrefix("X") ? 16 : 10
            let digits = radix == 16 ? String(rawCode.dropFirst()) : rawCode
            guard let scalarValue = UInt32(digits, radix: radix),
                  let scalar = UnicodeScalar(scalarValue) else {
                continue
            }
            result.replaceSubrange(fullRange, with: String(Character(scalar)))
        }
        return result
    }

    private func decodeJSONStringContent(_ value: String) -> String {
        let literal = "\"\(value)\""
        if let data = literal.data(using: .utf8),
           let decoded = try? JSONSerialization.jsonObject(with: data) as? String {
            return decoded
        }
        return value.replacingOccurrences(of: #"\/"#, with: "/")
    }

    private func summarize(_ body: String?) -> String? {
        guard let body else { return nil }
        let noURLs = body.replacingOccurrences(of: #"https?://\S+"#, with: "", options: .regularExpression)
        let normalized = noURLs
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        guard !normalized.isEmpty else { return nil }
        return normalized.count > 140 ? String(normalized.prefix(140)) + "…" : normalized
    }

    private func oEmbedParagraphBody(from html: String?) -> String? {
        guard let html else { return nil }
        return firstMatch(pattern: #"<p\b[^>]*>(.*?)</p>"#, html: html)
            ?? normalizeText(stripHTMLTags(html))
    }

    private func firstXPhotoURL(_ payload: [String: Any]) -> String? {
        guard let photos = payload["photos"] as? [[String: Any]] else { return nil }
        for photo in photos {
            if let url = stringField(photo, "url") {
                return url
            }
        }
        return nil
    }

    private func firstXMediaURL(_ payload: [String: Any]) -> String? {
        guard let mediaDetails = payload["mediaDetails"] as? [[String: Any]] else { return nil }
        for media in mediaDetails {
            if let url = stringField(media, "media_url_https", "media_url", "url") {
                return url
            }
        }
        return nil
    }

    private func stringField(_ payload: [String: Any], _ keys: String...) -> String? {
        for key in keys {
            if let value = payload[key] as? String, let normalized = normalizeText(value) {
                return normalized
            }
            if let value = payload[key] as? NSNumber {
                return value.stringValue
            }
        }
        return nil
    }

    private func firstNonBlank(_ values: String?...) -> String? {
        for value in values {
            if let normalized = normalizeText(value) {
                return normalized
            }
        }
        return nil
    }

    private func normalizeText(_ value: String?) -> String? {
        guard let value else { return nil }
        let normalized = value
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return normalized.isEmpty ? nil : normalized
    }

    private func readyUpdate(from metadata: ExtractedMetadata, finalURL: URL) -> MetadataUpdate {
        MetadataUpdate(
            fetchedTitle: metadata.title,
            fetchedBody: metadata.body,
            fetchedBodyKind: metadata.bodyKind,
            bodySummary: metadata.summary,
            description: metadata.description,
            thumbnailURL: metadata.thumbnail,
            badgeImageURL: metadata.badgeImageURL,
            metadataState: .ready,
            metadataFetchedAt: Date(),
            metadataError: nil,
            canonicalID: metadata.canonicalID,
            normalizedHost: finalURL.host?.lowercased(),
            rawSourceHost: finalURL.host?.lowercased()
        )
    }

    private func merge(update: MetadataUpdate, with supplement: ExtractedMetadata) -> MetadataUpdate {
        guard supplement.hasAnyContent else { return update }
        let title = update.fetchedTitle ?? supplement.title
        let body = update.fetchedBody ?? supplement.body
        let thumbnail = update.thumbnailURL ?? supplement.thumbnail
        let badgeImageURL = update.badgeImageURL ?? supplement.badgeImageURL
        let state: MetadataState = (title != nil || body != nil || thumbnail != nil || badgeImageURL != nil) ? .ready : update.metadataState

        return MetadataUpdate(
            fetchedTitle: title,
            fetchedBody: body,
            fetchedBodyKind: update.fetchedBodyKind ?? supplement.bodyKind,
            bodySummary: update.bodySummary ?? supplement.summary,
            description: update.description ?? supplement.description,
            thumbnailURL: thumbnail,
            badgeImageURL: badgeImageURL,
            metadataState: state,
            metadataFetchedAt: Date(),
            metadataError: state == .ready ? nil : update.metadataError,
            canonicalID: update.canonicalID ?? supplement.canonicalID,
            normalizedHost: update.normalizedHost,
            rawSourceHost: update.rawSourceHost
        )
    }

    private func merge(primary: MetadataUpdate, supplement: MetadataUpdate) -> MetadataUpdate {
        let title = primary.fetchedTitle ?? supplement.fetchedTitle
        let body = primary.fetchedBody ?? supplement.fetchedBody
        let thumbnail = primary.thumbnailURL ?? supplement.thumbnailURL
        let badgeImageURL = primary.badgeImageURL ?? supplement.badgeImageURL
        let state: MetadataState = (title != nil || body != nil || thumbnail != nil || badgeImageURL != nil) ? .ready : primary.metadataState

        return MetadataUpdate(
            fetchedTitle: title,
            fetchedBody: body,
            fetchedBodyKind: primary.fetchedBodyKind ?? supplement.fetchedBodyKind,
            bodySummary: primary.bodySummary ?? supplement.bodySummary,
            description: primary.description ?? supplement.description,
            thumbnailURL: thumbnail,
            badgeImageURL: badgeImageURL,
            metadataState: state,
            metadataFetchedAt: Date(),
            metadataError: state == .ready ? nil : primary.metadataError,
            canonicalID: primary.canonicalID ?? supplement.canonicalID,
            normalizedHost: primary.normalizedHost ?? supplement.normalizedHost,
            rawSourceHost: primary.rawSourceHost ?? supplement.rawSourceHost
        )
    }

    private func mergeInstagramMetadata(
        oEmbedMetadata: ExtractedMetadata,
        captionedEmbedMetadata: ExtractedMetadata
    ) -> ExtractedMetadata {
        ExtractedMetadata(
            title: oEmbedMetadata.title ?? captionedEmbedMetadata.title,
            body: oEmbedMetadata.body ?? captionedEmbedMetadata.body,
            bodyKind: oEmbedMetadata.bodyKind ?? captionedEmbedMetadata.bodyKind,
            summary: oEmbedMetadata.summary ?? captionedEmbedMetadata.summary,
            description: oEmbedMetadata.description ?? captionedEmbedMetadata.description,
            thumbnail: oEmbedMetadata.thumbnail ?? captionedEmbedMetadata.thumbnail,
            badgeImageURL: captionedEmbedMetadata.badgeImageURL ?? oEmbedMetadata.badgeImageURL,
            canonicalID: oEmbedMetadata.canonicalID ?? captionedEmbedMetadata.canonicalID
        )
    }

    private static func youtubeOEmbedURL(for targetURL: URL) -> URL? {
        queryURL(
            base: "https://www.youtube.com/oembed",
            items: [
                URLQueryItem(name: "format", value: "json"),
                URLQueryItem(name: "url", value: targetURL.absoluteString),
            ]
        )
    }

    private static func tiktokOEmbedURL(for targetURL: URL) -> URL? {
        queryURL(
            base: "https://www.tiktok.com/oembed",
            items: [URLQueryItem(name: "url", value: targetURL.absoluteString)]
        )
    }

    private static func tiktokFallbackURL(for targetURL: URL) -> URL? {
        queryURL(
            base: "https://www.tikwm.com/api/",
            items: [URLQueryItem(name: "url", value: targetURL.absoluteString)]
        )
    }

    private static func xOEmbedURL(for targetURL: URL) -> URL? {
        queryURL(
            base: "https://publish.x.com/oembed",
            items: [
                URLQueryItem(name: "omit_script", value: "true"),
                URLQueryItem(name: "dnt", value: "true"),
                URLQueryItem(name: "url", value: targetURL.absoluteString),
            ]
        )
    }

    private static func xSyndicationURL(for statusID: String) -> URL? {
        queryURL(
            base: "https://cdn.syndication.twimg.com/tweet-result",
            items: [
                URLQueryItem(name: "id", value: statusID),
                URLQueryItem(name: "token", value: "1"),
            ]
        )
    }

    private static func instagramPublicOEmbedURL(for targetURL: URL) -> URL? {
        queryURL(
            base: "https://www.instagram.com/api/v1/oembed/",
            items: [
                URLQueryItem(name: "omitscript", value: "true"),
                URLQueryItem(name: "url", value: targetURL.absoluteString),
            ]
        )
    }

    private static func instagramCaptionedEmbedURL(for targetURL: URL) -> URL? {
        guard var components = URLComponents(url: targetURL, resolvingAgainstBaseURL: false) else {
            return nil
        }
        let path = components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath
        components.percentEncodedPath = path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).isEmpty
            ? "/embed/captioned/"
            : "/" + path.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/embed/captioned/"
        components.fragment = nil
        return components.url
    }

    private static func queryURL(base: String, items: [URLQueryItem]) -> URL? {
        guard var components = URLComponents(string: base) else { return nil }
        components.queryItems = items
        return components.url
    }

    private static let maxBodyBytes = 2_000_000
    private static let browserLikeUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1 URLSaveriOS/1.0"
}

private struct ExtractedMetadata {
    let title: String?
    let body: String?
    let bodyKind: MetadataBodyKind?
    let summary: String?
    let description: String?
    let thumbnail: String?
    let badgeImageURL: String?
    let canonicalID: String?

    var hasAnyContent: Bool {
        title != nil || body != nil || thumbnail != nil || badgeImageURL != nil
    }

    static let empty = ExtractedMetadata(
        title: nil,
        body: nil,
        bodyKind: nil,
        summary: nil,
        description: nil,
        thumbnail: nil,
        badgeImageURL: nil,
        canonicalID: nil
    )

    func merging(with supplement: ExtractedMetadata) -> ExtractedMetadata {
        ExtractedMetadata(
            title: title ?? supplement.title,
            body: body ?? supplement.body,
            bodyKind: bodyKind ?? supplement.bodyKind,
            summary: summary ?? supplement.summary,
            description: description ?? supplement.description,
            thumbnail: thumbnail ?? supplement.thumbnail,
            badgeImageURL: badgeImageURL ?? supplement.badgeImageURL,
            canonicalID: canonicalID ?? supplement.canonicalID
        )
    }
}

private struct HTMLDocument {
    let html: String
    let finalURL: URL
}

private enum FetchFailure: Error {
    case failed(MetadataError)
    case unavailable(MetadataError)
}
