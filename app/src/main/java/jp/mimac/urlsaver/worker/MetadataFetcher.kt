package jp.mimac.urlsaver.worker

import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataBodyKind
import jp.mimac.urlsaver.domain.UrlRules
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

sealed interface FetchOutcome {
    data class Ready(
        val fetchedTitle: String? = null,
        val fetchedAuthorName: String? = null,
        val fetchedBody: String? = null,
        val fetchedBodyKind: MetadataBodyKind? = null,
        val bodySummary: String? = null,
        val description: String? = null,
        val thumbnailUrl: String? = null,
        val badgeImageUrl: String? = null,
        val canonicalId: String? = null,
        val normalizedHost: String? = null,
        val rawSourceHost: String? = null,
    ) : FetchOutcome

    data class Unavailable(val error: MetadataError) : FetchOutcome
    data class FailedRetryable(val error: MetadataError) : FetchOutcome
}

class MetadataFetcher(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val syndicationEndpointBuilder: (String) -> String = { statusId ->
        "$SYNDICATION_ENDPOINT?id=$statusId&token=$DEFAULT_SYNDICATION_TOKEN"
    },
    private val oEmbedEndpointBuilder: (String) -> String = { targetUrl ->
        "$OEMBED_ENDPOINT?omit_script=true&dnt=true&url=${URLEncoder.encode(targetUrl, Charsets.UTF_8.name())}"
    },
    private val xGuestActivationEndpoint: String = X_GUEST_ACTIVATION_ENDPOINT,
    private val xArticleGraphQLEndpointBuilder: (String) -> String = { statusId ->
        buildXArticleGraphQLEndpoint(statusId)
    },
    private val xPublicBearerToken: String = X_PUBLIC_BEARER_TOKEN,
    private val youtubeOEmbedEndpointBuilder: (String) -> String = { targetUrl ->
        "$YOUTUBE_OEMBED_ENDPOINT?format=json&url=${URLEncoder.encode(targetUrl, Charsets.UTF_8.name())}"
    },
    private val youtubePlayerEndpointBuilder: (String) -> String = {
        "$YOUTUBE_PLAYER_ENDPOINT?key=$YOUTUBE_INNERTUBE_API_KEY&prettyPrint=false"
    },
    private val tiktokOEmbedEndpointBuilder: (String) -> String = { targetUrl ->
        "$TIKTOK_OEMBED_ENDPOINT?url=${URLEncoder.encode(targetUrl, Charsets.UTF_8.name())}"
    },
    private val tiktokFallbackEndpointBuilder: ((String) -> String)? = { targetUrl ->
        "$TIKTOK_FALLBACK_ENDPOINT?url=${URLEncoder.encode(targetUrl, Charsets.UTF_8.name())}"
    },
    private val instagramPublicOEmbedEndpointBuilder: ((String) -> String)? = null,
    private val instagramOEmbedEndpointBuilder: ((String) -> String)? = null,
    private val instagramCaptionedEmbedEndpointBuilder: ((String) -> String)? = null,
    private val connectionFactory: (String) -> HttpURLConnection = { targetUrl ->
        URL(targetUrl).openConnection() as HttpURLConnection
    },
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 30_000,
) {
    fun fetch(url: String): FetchOutcome {
        return try {
            fetchInternal(url)
        } catch (_: SocketTimeoutException) {
            FetchOutcome.FailedRetryable(MetadataError.TIMEOUT)
        } catch (_: java.io.InterruptedIOException) {
            FetchOutcome.FailedRetryable(MetadataError.TIMEOUT)
        } catch (_: java.io.IOException) {
            FetchOutcome.FailedRetryable(MetadataError.NETWORK_IO)
        } catch (_: Exception) {
            FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
        }
    }

    private fun fetchInternal(inputUrl: String): FetchOutcome {
        val initialUri = runCatching { URI(inputUrl) }.getOrNull()
            ?: return FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
        if (!isFetchableScheme(initialUri)) {
            return FetchOutcome.Unavailable(MetadataError.UNSUPPORTED_SCHEME)
        }

        val xStatusId = UrlRules.extractXStatusId(inputUrl)
        if (xStatusId != null) {
            return fetchXMetadata(inputUrl, xStatusId)
        }

        return when (classifyHtmlMetadataService(initialUri.host)) {
            HtmlMetadataService.YOUTUBE -> fetchYouTubeMetadata(inputUrl)
            HtmlMetadataService.TIKTOK -> fetchTikTokMetadata(inputUrl)
            HtmlMetadataService.INSTAGRAM -> fetchInstagramMetadata(inputUrl)
            HtmlMetadataService.WEB -> fetchWebMetadata(inputUrl)
        }
    }

    private fun fetchXMetadata(inputUrl: String, statusId: String): FetchOutcome {
        val oEmbedOutcome = fetchOEmbedMetadata(inputUrl, statusId)
        if (oEmbedOutcome is FetchOutcome.Ready) {
            val syndicationOutcome = fetchSyndicationMetadata(statusId)
            return if (syndicationOutcome is FetchOutcome.Ready) {
                mergeXPrimaryWithSyndicationSupplement(
                    primary = oEmbedOutcome,
                    supplement = syndicationOutcome,
                )
            } else {
                oEmbedOutcome
            }
        }
        val syndicationOutcome = fetchSyndicationMetadata(statusId)
        if (syndicationOutcome is FetchOutcome.Ready) {
            return syndicationOutcome
        }

        return combineXFallbackOutcomes(
            primary = oEmbedOutcome,
            fallback = syndicationOutcome,
        )
    }

    private fun mergeXPrimaryWithSyndicationSupplement(
        primary: FetchOutcome.Ready,
        supplement: FetchOutcome.Ready,
    ): FetchOutcome.Ready {
        val preferSupplementBody = primary.fetchedBody?.isSingleHttpUrl() == true &&
            supplement.fetchedBody?.isSingleHttpUrl() == false
        return primary.copy(
            fetchedTitle = primary.fetchedTitle ?: supplement.fetchedTitle,
            fetchedAuthorName = primary.fetchedAuthorName ?: supplement.fetchedAuthorName,
            fetchedBody = if (preferSupplementBody) {
                supplement.fetchedBody
            } else {
                primary.fetchedBody ?: supplement.fetchedBody
            },
            fetchedBodyKind = if (preferSupplementBody) {
                supplement.fetchedBodyKind
            } else {
                primary.fetchedBodyKind ?: supplement.fetchedBodyKind
            },
            bodySummary = if (preferSupplementBody) supplement.bodySummary else primary.bodySummary ?: supplement.bodySummary,
            description = if (preferSupplementBody) supplement.description else primary.description ?: supplement.description,
            thumbnailUrl = primary.thumbnailUrl ?: supplement.thumbnailUrl,
            badgeImageUrl = primary.badgeImageUrl ?: supplement.badgeImageUrl,
            canonicalId = primary.canonicalId ?: supplement.canonicalId,
            normalizedHost = primary.normalizedHost ?: supplement.normalizedHost,
            rawSourceHost = primary.rawSourceHost ?: supplement.rawSourceHost,
        )
    }

    private fun fetchYouTubeMetadata(inputUrl: String): FetchOutcome {
        val oEmbedMetadata = fetchYouTubeOEmbedMetadata(inputUrl)
        val channelBadgeImageUrl = oEmbedMetadata.authorUrl?.let(::fetchSingleOgImage)
            ?: fetchSingleYouTubeBadge(inputUrl)
        val htmlOutcome = fetchHtmlMetadata(inputUrl) { document, finalUri ->
            val title = oEmbedMetadata.title ?: extractTitleForYouTube(document)
            val body = extractYouTubeBody(document)
            val description = extractDescription(document) ?: body
            val thumbnail = oEmbedMetadata.thumbnailUrl ?: extractOgImage(document)
            val badgeImage = channelBadgeImageUrl ?: extractYouTubeChannelBadge(document)
            if (title == null && body == null && thumbnail == null) {
                return@fetchHtmlMetadata FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }

            FetchOutcome.Ready(
                fetchedTitle = title,
                fetchedAuthorName = oEmbedMetadata.authorName,
                fetchedBody = body,
                fetchedBodyKind = body?.let { MetadataBodyKind.YOUTUBE_DESCRIPTION },
                bodySummary = body?.let(::buildRuleSummary),
                description = description,
                thumbnailUrl = thumbnail,
                badgeImageUrl = badgeImage,
                canonicalId = extractCanonical(document, finalUri),
                normalizedHost = finalUri.host,
                rawSourceHost = finalUri.host,
            )
        }

        if (htmlOutcome is FetchOutcome.Ready) {
            if (!htmlOutcome.fetchedBody.isNullOrBlank()) {
                return htmlOutcome
            }
            val playerMetadata = fetchYouTubePlayerMetadata(inputUrl)
            return mergeYouTubeMetadata(
                primary = htmlOutcome,
                supplement = playerMetadata,
            )
        }

        val playerMetadata = fetchYouTubePlayerMetadata(inputUrl)
        if (oEmbedMetadata.hasAnyMetadata || playerMetadata.hasAnyMetadata) {
            val parsed = runCatching { URI(inputUrl) }.getOrNull()
            return FetchOutcome.Ready(
                fetchedTitle = firstNonBlank(oEmbedMetadata.title, playerMetadata.title),
                fetchedAuthorName = oEmbedMetadata.authorName,
                fetchedBody = playerMetadata.body,
                fetchedBodyKind = playerMetadata.body?.let { MetadataBodyKind.YOUTUBE_DESCRIPTION },
                bodySummary = playerMetadata.body?.let(::buildRuleSummary),
                description = playerMetadata.body,
                thumbnailUrl = firstNonBlank(oEmbedMetadata.thumbnailUrl, playerMetadata.thumbnailUrl),
                badgeImageUrl = channelBadgeImageUrl,
                canonicalId = firstNonBlank(playerMetadata.canonicalId, parsed?.let(::extractServiceCanonical)),
                normalizedHost = parsed?.host,
                rawSourceHost = parsed?.host,
            )
        }

        return combineNonXFallbackOutcomes(
            primary = htmlOutcome,
            fallback = playerMetadata.failureOutcome ?: oEmbedMetadata.failureOutcome,
        )
    }

    private fun mergeYouTubeMetadata(
        primary: FetchOutcome.Ready,
        supplement: YouTubePlayerMetadata,
    ): FetchOutcome.Ready {
        if (!supplement.hasAnyMetadata) return primary
        return primary.copy(
            fetchedTitle = primary.fetchedTitle ?: supplement.title,
            fetchedAuthorName = primary.fetchedAuthorName,
            fetchedBody = primary.fetchedBody ?: supplement.body,
            fetchedBodyKind = primary.fetchedBodyKind ?: supplement.body?.let { MetadataBodyKind.YOUTUBE_DESCRIPTION },
            bodySummary = primary.bodySummary ?: supplement.body?.let(::buildRuleSummary),
            description = primary.description ?: supplement.body,
            thumbnailUrl = primary.thumbnailUrl ?: supplement.thumbnailUrl,
            canonicalId = primary.canonicalId ?: supplement.canonicalId,
        )
    }

    private fun fetchInstagramMetadata(inputUrl: String): FetchOutcome {
        val publicOEmbedMetadata = fetchInstagramPublicOEmbedMetadata(inputUrl)
        val graphOEmbedMetadata = fetchInstagramOEmbedMetadata(inputUrl)
        val oEmbedMetadata = mergeInstagramOEmbedMetadata(
            primary = publicOEmbedMetadata,
            supplement = graphOEmbedMetadata,
        )
        val embedMetadata = fetchInstagramCaptionedEmbedMetadata(inputUrl)
        val supplementalBody = firstNonBlank(
            oEmbedMetadata.body,
            embedMetadata.body,
        )
        val supplementalTitle = firstNonBlank(
            oEmbedMetadata.title,
            embedMetadata.title,
        )
        val supplementalThumbnail = firstNonBlank(
            oEmbedMetadata.thumbnailUrl,
            embedMetadata.thumbnailUrl,
        )
        val profileBadgeImageUrl = firstNonBlank(
            embedMetadata.badgeImageUrl,
            oEmbedMetadata.authorUrl?.let(::fetchSingleOgImage),
        )
        val htmlOutcome = fetchHtmlMetadata(inputUrl) { document, finalUri ->
            if (isInstagramErrorDocument(document)) {
                return@fetchHtmlMetadata FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }
            val jsonLdMetadata = extractInstagramJsonLdMetadata(document)
            val descriptionFromMeta = extractMetaContent(
                document,
                "meta[property=og:description]",
                "meta[name=description]",
                "meta[name=og:description]",
                "meta[name=twitter:description]",
            )
            val body = firstNonBlank(
                jsonLdMetadata.body,
                extractInstagramEmbeddedCaption(document),
                extractInstagramQuotedCaptionFromDescription(descriptionFromMeta),
                descriptionFromMeta,
                supplementalBody,
            )?.let(::normalizeFetchedBodyText)

            val title = firstNonBlank(
                extractMetaContent(document, "meta[property=og:title]", "meta[name=og:title]"),
                jsonLdMetadata.titleCandidate,
                extractInstagramEmbeddedUsername(document)?.let { "@$it" },
                supplementalTitle,
                extractTitleTag(document),
            )
            val thumbnail = extractOgImage(document) ?: supplementalThumbnail

            if (title == null && body == null && thumbnail == null) {
                return@fetchHtmlMetadata FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }

            FetchOutcome.Ready(
                fetchedTitle = title,
                fetchedBody = body,
                fetchedBodyKind = body?.let { MetadataBodyKind.INSTAGRAM_CAPTION },
                bodySummary = body?.let(::buildRuleSummary),
                thumbnailUrl = thumbnail,
                badgeImageUrl = profileBadgeImageUrl,
                canonicalId = extractCanonical(document, finalUri),
                normalizedHost = finalUri.host,
                rawSourceHost = finalUri.host,
            )
        }

        if (htmlOutcome is FetchOutcome.Ready) {
            if (!oEmbedMetadata.hasAnyMetadata && !embedMetadata.hasAnyMetadata) {
                return htmlOutcome.copy(badgeImageUrl = profileBadgeImageUrl)
            }
            return htmlOutcome.copy(
                fetchedTitle = htmlOutcome.fetchedTitle ?: supplementalTitle,
                fetchedAuthorName = htmlOutcome.fetchedAuthorName ?: supplementalTitle,
                fetchedBody = htmlOutcome.fetchedBody ?: supplementalBody,
                fetchedBodyKind = htmlOutcome.fetchedBodyKind
                    ?: supplementalBody?.let { MetadataBodyKind.INSTAGRAM_CAPTION },
                bodySummary = htmlOutcome.bodySummary ?: supplementalBody?.let(::buildRuleSummary),
                description = htmlOutcome.description ?: supplementalBody,
                thumbnailUrl = htmlOutcome.thumbnailUrl ?: supplementalThumbnail,
                badgeImageUrl = profileBadgeImageUrl,
            )
        }

        if (oEmbedMetadata.hasAnyMetadata || embedMetadata.hasAnyMetadata) {
            val parsed = runCatching { URI(inputUrl) }.getOrNull()
            return FetchOutcome.Ready(
                fetchedTitle = supplementalTitle,
                fetchedAuthorName = supplementalTitle,
                fetchedBody = supplementalBody,
                fetchedBodyKind = supplementalBody?.let { MetadataBodyKind.INSTAGRAM_CAPTION },
                bodySummary = supplementalBody?.let(::buildRuleSummary),
                description = supplementalBody,
                thumbnailUrl = supplementalThumbnail,
                badgeImageUrl = profileBadgeImageUrl,
                canonicalId = parsed?.let(::extractServiceCanonical),
                normalizedHost = parsed?.host,
                rawSourceHost = parsed?.host,
            )
        }

        // Instagram oEmbed is supplemental only; HTML outcome keeps failure semantics.
        return htmlOutcome
    }

    private fun fetchTikTokMetadata(inputUrl: String): FetchOutcome {
        val parsed = runCatching { URI(inputUrl) }.getOrNull()
            ?: return FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
        val oEmbedMetadata = fetchTikTokOEmbedMetadata(
            targetUrl = inputUrl,
            parsedUri = parsed,
        )
        val profileBadgeImageUrl = runCatching {
            oEmbedMetadata.authorUrl?.let(::fetchSingleOgImage)
        }.getOrNull()

        // oEmbed can be content-complete while the creator avatar exists only on the post page.
        if (oEmbedMetadata.hasCompleteMetadata &&
            (oEmbedMetadata.authorUrl == null || profileBadgeImageUrl != null)
        ) {
            return FetchOutcome.Ready(
                fetchedTitle = oEmbedMetadata.title,
                fetchedBody = oEmbedMetadata.body,
                fetchedBodyKind = oEmbedMetadata.body?.let { MetadataBodyKind.WEB_DESCRIPTION },
                bodySummary = oEmbedMetadata.body?.let(::buildRuleSummary),
                description = oEmbedMetadata.body,
                thumbnailUrl = oEmbedMetadata.thumbnailUrl,
                badgeImageUrl = profileBadgeImageUrl,
                canonicalId = oEmbedMetadata.canonicalId,
                normalizedHost = parsed.host,
                rawSourceHost = parsed.host,
            )
        }

        val htmlOutcome = fetchTikTokWebMetadata(
            inputUrl = inputUrl,
            oEmbedMetadata = oEmbedMetadata,
            profileBadgeImageUrl = profileBadgeImageUrl,
        )
        if (htmlOutcome is FetchOutcome.Ready && !htmlOutcome.fetchedBody.isNullOrBlank()) {
            return htmlOutcome
        }

        val fallbackOutcome = fetchTikTokFallbackMetadata(
            targetUrl = inputUrl,
            parsedUri = parsed,
        )
        if (fallbackOutcome is FetchOutcome.Ready) {
            return if (htmlOutcome is FetchOutcome.Ready) {
                mergeTikTokMetadata(
                    primary = fallbackOutcome,
                    supplement = htmlOutcome,
                )
            } else {
                fallbackOutcome
            }
        }

        if (htmlOutcome is FetchOutcome.Ready) {
            return htmlOutcome
        }

        if (oEmbedMetadata.hasAnyMetadata) {
            return FetchOutcome.Ready(
                fetchedTitle = oEmbedMetadata.title,
                fetchedBody = oEmbedMetadata.body,
                fetchedBodyKind = oEmbedMetadata.body?.let { MetadataBodyKind.WEB_DESCRIPTION },
                bodySummary = oEmbedMetadata.body?.let(::buildRuleSummary),
                description = oEmbedMetadata.body,
                thumbnailUrl = oEmbedMetadata.thumbnailUrl,
                badgeImageUrl = profileBadgeImageUrl,
                canonicalId = oEmbedMetadata.canonicalId,
                normalizedHost = parsed.host,
                rawSourceHost = parsed.host,
            )
        }

        return combineTikTokFallbackOutcomes(
            primary = oEmbedMetadata.failureOutcome ?: FetchOutcome.Unavailable(MetadataError.PARSE_FAILED),
            fallback = combineTikTokFallbackOutcomes(htmlOutcome, fallbackOutcome),
        )
    }

    private fun mergeTikTokMetadata(
        primary: FetchOutcome.Ready,
        supplement: FetchOutcome.Ready,
    ): FetchOutcome.Ready {
        return primary.copy(
            fetchedTitle = primary.fetchedTitle ?: supplement.fetchedTitle,
            fetchedAuthorName = primary.fetchedAuthorName ?: supplement.fetchedAuthorName,
            fetchedBody = primary.fetchedBody ?: supplement.fetchedBody,
            fetchedBodyKind = primary.fetchedBodyKind ?: supplement.fetchedBodyKind,
            bodySummary = primary.bodySummary ?: supplement.bodySummary,
            description = primary.description ?: supplement.description,
            thumbnailUrl = primary.thumbnailUrl ?: supplement.thumbnailUrl,
            badgeImageUrl = primary.badgeImageUrl ?: supplement.badgeImageUrl,
            canonicalId = primary.canonicalId ?: supplement.canonicalId,
            normalizedHost = primary.normalizedHost ?: supplement.normalizedHost,
            rawSourceHost = primary.rawSourceHost ?: supplement.rawSourceHost,
        )
    }

    private fun fetchTikTokWebMetadata(
        inputUrl: String,
        oEmbedMetadata: TikTokOEmbedMetadata,
        profileBadgeImageUrl: String?,
    ): FetchOutcome {
        return fetchHtmlMetadata(inputUrl, TIKTOK_BROWSER_USER_AGENT) { document, finalUri ->
            val embeddedMetadata = extractTikTokEmbeddedMetadata(document)
            val webBody = extractWebBodyWithKind(document)
            val htmlDescription = extractDescription(document)
            val body = firstNonBlank(
                embeddedMetadata.body,
                oEmbedMetadata.body,
                extractTikTokCaptionFromShareDescription(htmlDescription),
                htmlDescription,
                webBody.text,
            )
            val bodyKind = when {
                embeddedMetadata.body != null ||
                    oEmbedMetadata.body != null ||
                    htmlDescription != null -> MetadataBodyKind.WEB_DESCRIPTION
                body != null -> webBody.kind
                else -> null
            }
            val title = firstNonBlank(
                embeddedMetadata.title,
                oEmbedMetadata.title,
                normalizeTikTokTitleText(extractMetaContent(document, "meta[property=og:title]", "meta[name=og:title]")),
                normalizeTikTokTitleText(extractTitleTag(document)),
            )
            val thumbnail = firstNonBlank(embeddedMetadata.thumbnailUrl, oEmbedMetadata.thumbnailUrl, extractOgImage(document))
            val badgeImage = firstNonBlank(
                embeddedMetadata.badgeImageUrl,
                profileBadgeImageUrl,
                extractFavicon(document, finalUri),
            )
            val canonicalId = firstNonBlank(
                embeddedMetadata.canonicalId,
                oEmbedMetadata.canonicalId,
                extractCanonical(document, finalUri),
            )

            if (title == null && body == null && thumbnail == null && canonicalId == null) {
                return@fetchHtmlMetadata FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }

            FetchOutcome.Ready(
                fetchedTitle = title,
                fetchedBody = body,
                fetchedBodyKind = bodyKind,
                bodySummary = body?.let(::buildRuleSummary),
                description = firstNonBlank(embeddedMetadata.description, body, htmlDescription, webBody.text),
                thumbnailUrl = thumbnail,
                badgeImageUrl = badgeImage,
                canonicalId = canonicalId,
                normalizedHost = finalUri.host,
                rawSourceHost = finalUri.host,
            )
        }
    }

    private fun mergeInstagramOEmbedMetadata(
        primary: InstagramOEmbedMetadata,
        supplement: InstagramOEmbedMetadata,
    ): InstagramOEmbedMetadata {
        return InstagramOEmbedMetadata(
            title = primary.title ?: supplement.title,
            body = primary.body ?: supplement.body,
            thumbnailUrl = primary.thumbnailUrl ?: supplement.thumbnailUrl,
            authorUrl = primary.authorUrl ?: supplement.authorUrl,
            badgeImageUrl = primary.badgeImageUrl ?: supplement.badgeImageUrl,
        )
    }

    private fun fetchWebMetadata(inputUrl: String): FetchOutcome {
        return fetchHtmlMetadata(inputUrl) { document, finalUri ->
            val title = firstNonBlank(
                extractMetaContent(document, "meta[property=og:title]", "meta[name=og:title]"),
                extractTitleTag(document),
            )
            val webBody = extractWebBodyWithKind(document)
            val description = extractDescription(document) ?: webBody.text
            val thumbnail = extractOgImage(document)
            val badgeImage = extractFavicon(document, finalUri)

            if (title == null && webBody.text == null && thumbnail == null) {
                return@fetchHtmlMetadata FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }

            FetchOutcome.Ready(
                fetchedTitle = title,
                fetchedBody = webBody.text,
                fetchedBodyKind = webBody.kind,
                bodySummary = webBody.text?.let(::buildRuleSummary),
                description = description,
                thumbnailUrl = thumbnail,
                badgeImageUrl = badgeImage,
                canonicalId = extractCanonical(document, finalUri),
                normalizedHost = finalUri.host,
                rawSourceHost = finalUri.host,
            )
        }
    }

    private fun fetchSyndicationMetadata(statusId: String): FetchOutcome {
        val endpoint = syndicationEndpointBuilder(statusId)
        return fetchJsonPayload(endpoint) { payload ->
            val user = payload.jsonObjectOrNull("user")
            val title = user?.firstNonBlankString("name", "screen_name")
            val article = payload.jsonObjectOrNull("article")
            val articleTitle = article?.firstNonBlankString("title")
            val articlePreview = article?.firstNonBlankString("preview_text")
            val articleFallbackBody = listOfNotNull(articleTitle, articlePreview)
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
                ?.let(::normalizeFetchedBodyText)
            val articleFullBody = article?.let { fetchXArticlePlainText(statusId) }
            val body = articleFullBody ?: articleFallbackBody ?: payload.extractSyndicationBody()
            val summary = body?.let(::buildRuleSummary)
            val articleThumbnail = article
                ?.jsonObjectOrNull("cover_media")
                ?.jsonObjectOrNull("media_info")
                ?.firstNonBlankString("original_img_url")
            val photoThumbnail = articleThumbnail ?: payload.firstPhotoThumbnailUrl()
            val videoThumbnail = payload.firstVideoThumbnailUrl()
            val badgeImage = user?.firstNonBlankString("profile_image_url_https", "profile_image_url")
                ?.replace("_normal.", "_400x400.")
            val canonicalFromPayload = payload.firstNonBlankString("id_str", "id")

            if (
                title == null &&
                body == null &&
                photoThumbnail == null &&
                videoThumbnail == null &&
                canonicalFromPayload == null
            ) {
                return@fetchJsonPayload FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }

            FetchOutcome.Ready(
                fetchedTitle = title,
                fetchedAuthorName = title,
                fetchedBody = body,
                fetchedBodyKind = body?.let { MetadataBodyKind.X_POST_TEXT },
                bodySummary = summary,
                description = articlePreview ?: body,
                thumbnailUrl = photoThumbnail ?: videoThumbnail,
                badgeImageUrl = badgeImage,
                canonicalId = canonicalFromPayload ?: statusId,
                normalizedHost = null,
                rawSourceHost = null,
            )
        }
    }

    private fun fetchOEmbedMetadata(targetUrl: String, statusId: String): FetchOutcome {
        val endpoint = oEmbedEndpointBuilder(targetUrl)
        return fetchJsonPayload(endpoint) { payload ->
            val authorName = payload.firstNonBlankString("author_name")
            val body = payload.extractOEmbedBody()
            val summary = body?.let(::buildRuleSummary)
            val thumbnail = payload.firstNonBlankString("thumbnail_url")

            if (authorName == null && body == null && thumbnail == null) {
                return@fetchJsonPayload FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }

            FetchOutcome.Ready(
                fetchedTitle = authorName,
                fetchedBody = body,
                fetchedBodyKind = body?.let { MetadataBodyKind.X_POST_TEXT },
                bodySummary = summary,
                thumbnailUrl = thumbnail,
                canonicalId = statusId,
                normalizedHost = null,
                rawSourceHost = null,
            )
        }
    }

    private fun fetchXArticlePlainText(statusId: String): String? {
        val guestToken = fetchXGuestToken() ?: return null
        val connection = connectionFactory(xArticleGraphQLEndpointBuilder(statusId)).apply {
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            requestMethod = "GET"
            setRequestProperty("User-Agent", X_WEB_USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $xPublicBearerToken")
            setRequestProperty("X-Guest-Token", guestToken)
            setRequestProperty("X-Twitter-Active-User", "yes")
            setRequestProperty("X-Twitter-Client-Language", "ja")
        }
        return try {
            if (connection.responseCode !in 200..299 || connection.contentLengthLong > BODY_LIMIT_BYTES) return null
            val body = BufferedInputStream(connection.inputStream).use { input ->
                readLimitedBody(input, BODY_LIMIT_BYTES)
            } ?: return null
            val payload = parseJsonObject(body.toString(Charsets.UTF_8)) ?: return null
            traverseJsonObjects(payload)
                .firstNotNullOfOrNull { candidate ->
                    candidate.firstNonBlankString("plain_text").takeIf {
                        candidate["content_state"] is JsonObject
                    }
                }
                ?.let(::normalizeXArticleBodyText)
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchXGuestToken(): String? {
        val connection = connectionFactory(xGuestActivationEndpoint).apply {
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(0)
            setRequestProperty("User-Agent", X_WEB_USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $xPublicBearerToken")
        }
        return try {
            if (connection.responseCode !in 200..299 || connection.contentLengthLong > BODY_LIMIT_BYTES) return null
            val body = BufferedInputStream(connection.inputStream).use { input ->
                readLimitedBody(input, BODY_LIMIT_BYTES)
            } ?: return null
            parseJsonObject(body.toString(Charsets.UTF_8))?.firstNonBlankString("guest_token")
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchYouTubeOEmbedMetadata(targetUrl: String): YouTubeOEmbedMetadata {
        val endpoint = youtubeOEmbedEndpointBuilder(targetUrl)
        var authorUrl: String? = null
        val outcome = fetchJsonPayload(endpoint) { payload ->
            val title = payload.firstNonBlankString("title")
            val authorName = normalizeTitleText(payload.firstNonBlankString("author_name"))
            val thumbnail = payload.firstNonBlankString("thumbnail_url")
            authorUrl = payload.firstNonBlankString("author_url")
            if (title == null && thumbnail == null) {
                return@fetchJsonPayload FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }
            FetchOutcome.Ready(
                fetchedTitle = title,
                fetchedAuthorName = authorName,
                thumbnailUrl = thumbnail,
            )
        }

        return when (outcome) {
            is FetchOutcome.Ready -> YouTubeOEmbedMetadata(
                title = outcome.fetchedTitle,
                authorName = outcome.fetchedAuthorName,
                thumbnailUrl = outcome.thumbnailUrl,
                authorUrl = authorUrl,
                failureOutcome = null,
            )
            is FetchOutcome.Unavailable -> YouTubeOEmbedMetadata(
                title = null,
                thumbnailUrl = null,
                authorUrl = null,
                failureOutcome = outcome,
            )
            is FetchOutcome.FailedRetryable -> YouTubeOEmbedMetadata(
                title = null,
                thumbnailUrl = null,
                authorUrl = null,
                failureOutcome = outcome,
            )
        }
    }

    private fun fetchYouTubePlayerMetadata(targetUrl: String): YouTubePlayerMetadata {
        val parsed = runCatching { URI(targetUrl) }.getOrNull()
        val videoId = parsed?.let(::extractServiceCanonical)
            ?: return YouTubePlayerMetadata(failureOutcome = FetchOutcome.Unavailable(MetadataError.PARSE_FAILED))
        val endpoint = youtubePlayerEndpointBuilder(videoId)
        val requestBody = buildYouTubePlayerRequest(videoId)
        val outcome = fetchJsonPayloadPost(endpoint, requestBody) { payload ->
            val videoDetails = payload.jsonObjectOrNull("videoDetails")
            val microformat = payload.jsonObjectOrNull("microformat")
                ?.jsonObjectOrNull("playerMicroformatRenderer")
            val title = firstNonBlank(
                videoDetails?.firstNonBlankString("title"),
                microformat?.jsonObjectOrNull("title")?.richTextString(),
            )
            val body = firstNonBlank(
                videoDetails?.firstNonBlankString("shortDescription"),
                microformat?.jsonObjectOrNull("description")?.richTextString(),
            )?.let(::normalizeYouTubeDescriptionText)
            val thumbnail = firstNonBlank(
                videoDetails?.jsonObjectOrNull("thumbnail")?.bestThumbnailUrl(),
                microformat?.jsonObjectOrNull("thumbnail")?.bestThumbnailUrl(),
            )

            if (title == null && body == null && thumbnail == null) {
                return@fetchJsonPayloadPost FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }

            FetchOutcome.Ready(
                fetchedTitle = title,
                fetchedBody = body,
                fetchedBodyKind = body?.let { MetadataBodyKind.YOUTUBE_DESCRIPTION },
                bodySummary = body?.let(::buildRuleSummary),
                description = body,
                thumbnailUrl = thumbnail,
                canonicalId = videoId,
            )
        }

        return when (outcome) {
            is FetchOutcome.Ready -> YouTubePlayerMetadata(
                title = outcome.fetchedTitle,
                body = outcome.fetchedBody,
                thumbnailUrl = outcome.thumbnailUrl,
                canonicalId = outcome.canonicalId ?: videoId,
                failureOutcome = null,
            )
            is FetchOutcome.Unavailable -> YouTubePlayerMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                canonicalId = videoId,
                failureOutcome = outcome,
            )
            is FetchOutcome.FailedRetryable -> YouTubePlayerMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                canonicalId = videoId,
                failureOutcome = outcome,
            )
        }
    }

    private fun fetchTikTokOEmbedMetadata(targetUrl: String, parsedUri: URI): TikTokOEmbedMetadata {
        val endpoint = tiktokOEmbedEndpointBuilder(targetUrl)
        var authorUrl: String? = null
        val outcome = fetchJsonPayload(endpoint) { payload ->
            val authorName = normalizeTikTokTitleText(payload.firstNonBlankString("author_name"))
            val html = payload.firstNonBlankString("html")
            val body = firstNonBlank(
                payload.firstNonBlankString("title"),
                extractTikTokCaptionFromOEmbedHtml(html),
            )?.let(::normalizeTikTokBodyText)
            val thumbnail = normalizeUrlAttribute(payload.firstNonBlankString("thumbnail_url"))
            authorUrl = normalizeTikTokAuthorUrl(payload.firstNonBlankString("author_url"))
            val canonicalFromPayload = firstNonBlank(
                payload.firstNonBlankString("embed_product_id"),
                extractTikTokVideoIdFromOEmbedHtml(html),
            )

            if (authorName == null && body == null && thumbnail == null && canonicalFromPayload == null) {
                return@fetchJsonPayload FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }

            FetchOutcome.Ready(
                fetchedTitle = authorName,
                fetchedBody = body,
                bodySummary = body?.let(::buildRuleSummary),
                description = body,
                thumbnailUrl = thumbnail,
                canonicalId = canonicalFromPayload ?: extractServiceCanonical(parsedUri),
                normalizedHost = parsedUri.host,
                rawSourceHost = parsedUri.host,
            )
        }

        return when (outcome) {
            is FetchOutcome.Ready -> TikTokOEmbedMetadata(
                title = outcome.fetchedTitle,
                body = outcome.fetchedBody,
                thumbnailUrl = outcome.thumbnailUrl,
                authorUrl = authorUrl,
                canonicalId = outcome.canonicalId,
                failureOutcome = null,
            )
            is FetchOutcome.Unavailable -> TikTokOEmbedMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                authorUrl = null,
                canonicalId = null,
                failureOutcome = outcome,
            )
            is FetchOutcome.FailedRetryable -> TikTokOEmbedMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                authorUrl = null,
                canonicalId = null,
                failureOutcome = outcome,
            )
        }
    }

    private fun fetchTikTokFallbackMetadata(targetUrl: String, parsedUri: URI): FetchOutcome {
        val endpointBuilder = tiktokFallbackEndpointBuilder
            ?: return FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
        val endpoint = endpointBuilder(targetUrl)
        return fetchJsonPayload(endpoint) { payload ->
            val data = payload.jsonObjectOrNull("data")
                ?: return@fetchJsonPayload FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            val author = data.jsonObjectOrNull("author")
            val body = normalizeTikTokBodyText(data.firstNonBlankString("title"))
            val title = firstNonBlank(
                normalizeTikTokTitleText(author?.firstNonBlankString("nickname")),
                author?.firstNonBlankString("unique_id", "uniqueId")?.let { uniqueId ->
                    normalizeTikTokTitleText("@${uniqueId.trimStart('@')}")
                },
            )
            val thumbnail = normalizeUrlAttribute(
                data.firstNonBlankString("cover", "origin_cover", "dynamic_cover"),
            )
            val badgeImage = normalizeUrlAttribute(author?.firstNonBlankString("avatar"))
            val canonicalId = data.firstNonBlankString("id") ?: extractServiceCanonical(parsedUri)

            if (title == null && body == null && thumbnail == null && badgeImage == null) {
                return@fetchJsonPayload FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }

            FetchOutcome.Ready(
                fetchedTitle = title,
                fetchedBody = body,
                fetchedBodyKind = body?.let { MetadataBodyKind.WEB_DESCRIPTION },
                bodySummary = body?.let(::buildRuleSummary),
                description = body,
                thumbnailUrl = thumbnail,
                badgeImageUrl = badgeImage,
                canonicalId = canonicalId,
                normalizedHost = parsedUri.host,
                rawSourceHost = parsedUri.host,
            )
        }
    }

    private fun fetchInstagramPublicOEmbedMetadata(targetUrl: String): InstagramOEmbedMetadata {
        val endpointBuilder = instagramPublicOEmbedEndpointBuilder
            ?: return InstagramOEmbedMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                authorUrl = null,
                badgeImageUrl = null,
            )
        val endpoint = endpointBuilder(targetUrl)
        var authorUrl: String? = null
        val outcome = fetchJsonPayload(endpoint) { payload ->
            val authorName = normalizeTitleText(payload.firstNonBlankString("author_name"))
            val body = firstNonBlank(
                payload.firstNonBlankString("title"),
                extractInstagramCaptionFromOEmbedHtml(payload.firstNonBlankString("html")),
            )?.let(::normalizeFetchedBodyText)
            val thumbnail = payload.firstNonBlankString("thumbnail_url")
            authorUrl = payload.firstNonBlankString("author_url")
            if (authorName == null && body == null && thumbnail == null) {
                return@fetchJsonPayload FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }
            FetchOutcome.Ready(
                fetchedTitle = authorName,
                fetchedBody = body,
                thumbnailUrl = thumbnail,
            )
        }

        return when (outcome) {
            is FetchOutcome.Ready -> InstagramOEmbedMetadata(
                title = outcome.fetchedTitle,
                body = outcome.fetchedBody,
                thumbnailUrl = outcome.thumbnailUrl,
                authorUrl = authorUrl,
                badgeImageUrl = null,
            )
            is FetchOutcome.Unavailable -> InstagramOEmbedMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                authorUrl = null,
                badgeImageUrl = null,
            )
            is FetchOutcome.FailedRetryable -> InstagramOEmbedMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                authorUrl = null,
                badgeImageUrl = null,
            )
        }
    }

    private fun fetchInstagramOEmbedMetadata(targetUrl: String): InstagramOEmbedMetadata {
        val endpointBuilder = instagramOEmbedEndpointBuilder
            ?: return InstagramOEmbedMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                authorUrl = null,
                badgeImageUrl = null,
            )
        val endpoint = endpointBuilder(targetUrl)
        var authorUrl: String? = null
        val outcome = fetchJsonPayload(endpoint) { payload ->
            val authorName = normalizeTitleText(payload.firstNonBlankString("author_name"))
            val body = firstNonBlank(
                payload.firstNonBlankString("title"),
                extractInstagramCaptionFromOEmbedHtml(payload.firstNonBlankString("html")),
            )?.let(::normalizeFetchedBodyText)
            val thumbnail = payload.firstNonBlankString("thumbnail_url")
            authorUrl = payload.firstNonBlankString("author_url")
            val providerName = normalizeTitleText(payload.firstNonBlankString("provider_name"))
            val providerUrlHost = payload.firstNonBlankString("provider_url")
                ?.let { providerUrl ->
                    runCatching { URI(providerUrl).host }.getOrNull()
                }
                ?.let(::normalizeTitleText)
            val titleCandidate = firstNonBlank(
                authorName,
                providerName?.let { "$it のリンク" },
                providerUrlHost?.let { "$it のリンク" },
            )
            if (titleCandidate == null && body == null && thumbnail == null) {
                return@fetchJsonPayload FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }
            FetchOutcome.Ready(
                fetchedTitle = titleCandidate,
                fetchedBody = body,
                thumbnailUrl = thumbnail,
            )
        }

        return when (outcome) {
            is FetchOutcome.Ready -> InstagramOEmbedMetadata(
                title = outcome.fetchedTitle,
                body = outcome.fetchedBody,
                thumbnailUrl = outcome.thumbnailUrl,
                authorUrl = authorUrl,
                badgeImageUrl = null,
            )
            is FetchOutcome.Unavailable -> InstagramOEmbedMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                authorUrl = null,
                badgeImageUrl = null,
            )
            is FetchOutcome.FailedRetryable -> InstagramOEmbedMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                authorUrl = null,
                badgeImageUrl = null,
            )
        }
    }

    private fun fetchInstagramCaptionedEmbedMetadata(targetUrl: String): InstagramOEmbedMetadata {
        val endpointBuilder = instagramCaptionedEmbedEndpointBuilder
            ?: return InstagramOEmbedMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                authorUrl = null,
                badgeImageUrl = null,
            )
        val endpoint = endpointBuilder(targetUrl)
        var authorUrl: String? = null
        val outcome = fetchHtmlMetadata(endpoint) { document, _ ->
            val body = extractInstagramCaptionedEmbedCaption(document)
            val title = extractInstagramCaptionedEmbedUsername(document)
            val thumbnail = extractInstagramCaptionedEmbedThumbnail(document)
            authorUrl = extractInstagramCaptionedEmbedAuthorUrl(document)
            val badgeImage = extractInstagramCaptionedEmbedBadgeImage(document)

            if (title == null && body == null && thumbnail == null && badgeImage == null) {
                return@fetchHtmlMetadata FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            }

            FetchOutcome.Ready(
                fetchedTitle = title,
                fetchedBody = body,
                thumbnailUrl = thumbnail,
                badgeImageUrl = badgeImage,
                rawSourceHost = "www.instagram.com",
            )
        }

        return when (outcome) {
            is FetchOutcome.Ready -> InstagramOEmbedMetadata(
                title = outcome.fetchedTitle,
                body = outcome.fetchedBody,
                thumbnailUrl = outcome.thumbnailUrl,
                authorUrl = authorUrl,
                badgeImageUrl = outcome.badgeImageUrl,
            )
            is FetchOutcome.Unavailable -> InstagramOEmbedMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                authorUrl = null,
                badgeImageUrl = null,
            )
            is FetchOutcome.FailedRetryable -> InstagramOEmbedMetadata(
                title = null,
                body = null,
                thumbnailUrl = null,
                authorUrl = null,
                badgeImageUrl = null,
            )
        }
    }

    private fun combineXFallbackOutcomes(primary: FetchOutcome, fallback: FetchOutcome): FetchOutcome {
        val retryable = sequenceOf(primary, fallback)
            .filterIsInstance<FetchOutcome.FailedRetryable>()
            .firstOrNull()
        if (retryable != null) {
            return retryable
        }

        val unavailable = sequenceOf(primary, fallback)
            .filterIsInstance<FetchOutcome.Unavailable>()
            .firstOrNull { it.error != MetadataError.PARSE_FAILED }
            ?: sequenceOf(primary, fallback)
                .filterIsInstance<FetchOutcome.Unavailable>()
                .firstOrNull()
        if (unavailable != null) {
            return unavailable
        }

        return FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
    }

    private fun combineNonXFallbackOutcomes(primary: FetchOutcome, fallback: FetchOutcome?): FetchOutcome {
        val candidates = buildList {
            add(primary)
            if (fallback != null) add(fallback)
        }

        val unavailable = candidates
            .asSequence()
            .filterIsInstance<FetchOutcome.Unavailable>()
            .firstOrNull { it.error != MetadataError.PARSE_FAILED }
            ?: candidates.asSequence().filterIsInstance<FetchOutcome.Unavailable>().firstOrNull()
        if (unavailable != null) {
            return unavailable
        }

        val retryable = candidates.asSequence().filterIsInstance<FetchOutcome.FailedRetryable>().firstOrNull()
        if (retryable != null) {
            return retryable
        }

        return FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
    }

    private fun combineTikTokFallbackOutcomes(primary: FetchOutcome, fallback: FetchOutcome): FetchOutcome {
        val nonParseUnavailable = sequenceOf(primary, fallback)
            .filterIsInstance<FetchOutcome.Unavailable>()
            .firstOrNull { it.error != MetadataError.PARSE_FAILED }
        if (nonParseUnavailable != null) {
            return nonParseUnavailable
        }

        val retryable = sequenceOf(primary, fallback)
            .filterIsInstance<FetchOutcome.FailedRetryable>()
            .firstOrNull()
        if (retryable != null) {
            return retryable
        }

        return sequenceOf(primary, fallback)
            .filterIsInstance<FetchOutcome.Unavailable>()
            .firstOrNull()
            ?: FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
    }

    private fun fetchJsonPayload(
        initialUrl: String,
        mapPayload: (JsonObject) -> FetchOutcome,
    ): FetchOutcome {
        var current = initialUrl
        var redirects = 0

        while (true) {
            val currentUri = runCatching { URI(current) }.getOrNull()
                ?: return FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            if (!isFetchableScheme(currentUri)) {
                return FetchOutcome.Unavailable(MetadataError.UNSUPPORTED_SCHEME)
            }

            val connection = connectionFactory(current).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json,text/plain,*/*")
            }

            try {
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                        if (location.isNullOrBlank()) {
                            return FetchOutcome.Unavailable(MetadataError.TOO_MANY_REDIRECTS)
                        }
                        redirects += 1
                        if (redirects > 5) {
                            return FetchOutcome.Unavailable(MetadataError.TOO_MANY_REDIRECTS)
                        }
                        current = currentUri.resolve(location).toString()
                        continue
                    }

                    code == 404 -> return FetchOutcome.Unavailable(MetadataError.HTTP_404)
                    code in 400..499 -> return FetchOutcome.Unavailable(MetadataError.HTTP_4XX)
                    code in 500..599 -> return FetchOutcome.FailedRetryable(MetadataError.HTTP_5XX)
                }

                if (connection.contentLengthLong > BODY_LIMIT_BYTES) {
                    return FetchOutcome.Unavailable(MetadataError.OVERSIZED)
                }

                val body = BufferedInputStream(connection.inputStream).use { input ->
                    readLimitedBody(input, BODY_LIMIT_BYTES)
                }
                if (body == null) {
                    return FetchOutcome.Unavailable(MetadataError.OVERSIZED)
                }

                val bodyText = body.toString(Charsets.UTF_8)
                val payload = parseJsonObject(bodyText)
                    ?: return FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
                return mapPayload(payload)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun fetchJsonPayloadPost(
        initialUrl: String,
        requestBody: String,
        mapPayload: (JsonObject) -> FetchOutcome,
    ): FetchOutcome {
        var current = initialUrl
        var redirects = 0

        while (true) {
            val currentUri = runCatching { URI(current) }.getOrNull()
                ?: return FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            if (!isFetchableScheme(currentUri)) {
                return FetchOutcome.Unavailable(MetadataError.UNSUPPORTED_SCHEME)
            }

            val bodyBytes = requestBody.toByteArray(Charsets.UTF_8)
            val connection = connectionFactory(current).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", YOUTUBE_BROWSER_USER_AGENT)
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Content-Length", bodyBytes.size.toString())
            }

            try {
                connection.outputStream.use { output ->
                    output.write(bodyBytes)
                }

                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                        if (location.isNullOrBlank()) {
                            return FetchOutcome.Unavailable(MetadataError.TOO_MANY_REDIRECTS)
                        }
                        redirects += 1
                        if (redirects > 5) {
                            return FetchOutcome.Unavailable(MetadataError.TOO_MANY_REDIRECTS)
                        }
                        current = currentUri.resolve(location).toString()
                        continue
                    }

                    code == 404 -> return FetchOutcome.Unavailable(MetadataError.HTTP_404)
                    code in 400..499 -> return FetchOutcome.Unavailable(MetadataError.HTTP_4XX)
                    code in 500..599 -> return FetchOutcome.FailedRetryable(MetadataError.HTTP_5XX)
                }

                if (connection.contentLengthLong > BODY_LIMIT_BYTES) {
                    return FetchOutcome.Unavailable(MetadataError.OVERSIZED)
                }

                val body = BufferedInputStream(connection.inputStream).use { input ->
                    readLimitedBody(input, BODY_LIMIT_BYTES)
                }
                if (body == null) {
                    return FetchOutcome.Unavailable(MetadataError.OVERSIZED)
                }

                val payload = parseJsonObject(body.toString(Charsets.UTF_8))
                    ?: return FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
                return mapPayload(payload)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun fetchHtmlMetadata(
        inputUrl: String,
        requestUserAgent: String = userAgent,
        mapDocument: (Document, URI) -> FetchOutcome,
    ): FetchOutcome {
        var current = inputUrl
        var redirects = 0

        while (true) {
            val currentUri = runCatching { URI(current) }.getOrNull()
                ?: return FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
            if (!isFetchableScheme(currentUri)) {
                return FetchOutcome.Unavailable(MetadataError.UNSUPPORTED_SCHEME)
            }

            val connection = connectionFactory(current).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                requestMethod = "GET"
                setRequestProperty("User-Agent", requestUserAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }

            try {
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                        if (location.isNullOrBlank()) {
                            return FetchOutcome.Unavailable(MetadataError.TOO_MANY_REDIRECTS)
                        }
                        redirects += 1
                        if (redirects > 5) {
                            return FetchOutcome.Unavailable(MetadataError.TOO_MANY_REDIRECTS)
                        }
                        current = currentUri.resolve(location).toString()
                        continue
                    }

                    code == 404 -> return FetchOutcome.Unavailable(MetadataError.HTTP_404)
                    code in 400..499 -> return FetchOutcome.Unavailable(MetadataError.HTTP_4XX)
                    code in 500..599 -> return FetchOutcome.FailedRetryable(MetadataError.HTTP_5XX)
                }

                if (connection.contentLengthLong > BODY_LIMIT_BYTES) {
                    return FetchOutcome.Unavailable(MetadataError.OVERSIZED)
                }

                val body = BufferedInputStream(connection.inputStream).use { input ->
                    readLimitedBody(input, BODY_LIMIT_BYTES)
                }
                if (body == null) {
                    return FetchOutcome.Unavailable(MetadataError.OVERSIZED)
                }

                val contentType = connection.contentType?.lowercase().orEmpty()
                val isHtmlByHeader = contentType.contains("text/html") ||
                    contentType.contains("application/xhtml+xml")
                val bodyText = body.toString(Charsets.UTF_8)
                val looksHtml = bodyText.contains("<html", ignoreCase = true) ||
                    bodyText.contains("<!doctype html", ignoreCase = true)

                val isHtml = if (contentType.isBlank()) looksHtml else isHtmlByHeader
                if (!isHtml) {
                    return FetchOutcome.Unavailable(MetadataError.NON_HTML)
                }

                val document = Jsoup.parse(bodyText, current)
                return mapDocument(document, currentUri)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun extractTitleTag(document: Document): String? {
        return normalizeTitleText(document.title())
    }

    private fun extractTitleForYouTube(document: Document): String? {
        val title = firstNonBlank(
            extractMetaContent(document, "meta[property=og:title]", "meta[name=og:title]"),
            extractTitleTag(document),
        ) ?: return null
        return title
            .replace(Regex("\\s*-\\s*YouTube(?:\\s+Music)?$", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun extractYouTubeBody(document: Document): String? {
        return firstNonBlank(
            extractYouTubeEmbeddedDescription(document),
            extractYouTubeJsonLdDescription(document),
            extractMetaContent(
                document,
                "meta[name=description]",
                "meta[property=og:description]",
                "meta[name=og:description]",
                "meta[name=twitter:description]",
                "meta[property=twitter:description]",
            ),
        )?.let(::normalizeYouTubeDescriptionText)
    }

    private fun extractYouTubeEmbeddedDescription(document: Document): String? {
        for (script in document.select("script")) {
            val payload = script.data().ifBlank { script.html() }
            if (payload.isBlank()) continue

            for (pattern in YOUTUBE_EMBEDDED_DESCRIPTION_PATTERNS) {
                val match = pattern.find(payload) ?: continue
                val decoded = decodeJsonEscapedString(match.groupValues[1]) ?: continue
                val normalized = normalizeFetchedBodyText(decoded)
                if (normalized != null) {
                    return normalized
                }
            }

            val runsDescription = extractYouTubeRunsDescription(payload)
            if (runsDescription != null) {
                return runsDescription
            }
        }
        return null
    }

    private fun extractYouTubeRunsDescription(payload: String): String? {
        for (runsMatch in YOUTUBE_RUNS_DESCRIPTION_BLOCK_PATTERN.findAll(payload)) {
            val runTexts = YOUTUBE_RUNS_TEXT_PATTERN.findAll(runsMatch.groupValues[1])
                .mapNotNull { textMatch ->
                    decodeJsonEscapedString(textMatch.groupValues[1])
                }
                .toList()
            if (runTexts.isEmpty()) continue

            val combined = runTexts.joinToString("")
            val normalized = normalizeFetchedBodyText(combined)
            if (normalized != null) {
                return normalized
            }
        }
        return null
    }

    private fun extractYouTubeJsonLdDescription(document: Document): String? {
        for (script in document.select("script[type=application/ld+json]")) {
            val payload = script.data().trim().ifBlank { script.html().trim() }
            if (payload.isBlank()) continue

            val parsed = parseJsonElement(payload) ?: continue
            val description = findVideoObjectDescription(parsed)
            if (description != null) {
                return normalizeFetchedBodyText(description)
            }
        }
        return null
    }

    private fun extractInstagramQuotedCaptionFromDescription(description: String?): String? {
        val source = description?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val quoted = INSTAGRAM_QUOTED_TEXT_PATTERN.find(source)?.groupValues?.getOrNull(1)
        return normalizeFetchedBodyText(quoted)
    }

    private fun extractInstagramCaptionFromOEmbedHtml(rawHtml: String?): String? {
        val html = rawHtml?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val document = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull() ?: return null
        val candidates = document.select("p, div")
            .asSequence()
            .map { it.text() }
            .mapNotNull(::normalizeBodyText)
            .filterNot { candidate ->
                candidate.equals("View this post on Instagram", ignoreCase = true) ||
                    candidate.startsWith("A post shared by ", ignoreCase = true)
            }
            .toList()
        return candidates.firstOrNull { it.length >= 12 }
    }

    private fun extractTikTokCaptionFromOEmbedHtml(rawHtml: String?): String? {
        val html = rawHtml?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val document = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull() ?: return null
        val candidates = document.select("blockquote.tiktok-embed p, p")
            .asSequence()
            .map { it.text() }
            .mapNotNull(::normalizeBodyText)
            .filterNot { candidate ->
                candidate.startsWith("♬") ||
                    candidate.startsWith("@") ||
                    candidate.equals("Discover more on TikTok", ignoreCase = true)
            }
            .toList()
        return candidates.firstOrNull { it.length >= 8 } ?: candidates.firstOrNull()
    }

    private fun extractTikTokVideoIdFromOEmbedHtml(rawHtml: String?): String? {
        val html = rawHtml?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val document = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull() ?: return null
        val videoId = firstNonBlank(
            document.selectFirst("blockquote.tiktok-embed[data-video-id]")?.attr("data-video-id"),
            document.selectFirst("blockquote.tiktok-embed[cite]")?.attr("cite")
                ?.let { cite -> runCatching { URI(cite) }.getOrNull() }
                ?.let(::extractServiceCanonical),
        )
        return videoId
    }

    private fun extractTikTokEmbeddedMetadata(document: Document): TikTokEmbeddedMetadata {
        var result = TikTokEmbeddedMetadata()
        for (script in document.select("script")) {
            val payload = script.data().ifBlank { script.html() }.trim()
            if (payload.isBlank()) continue
            if (!payload.startsWith("{") && !payload.startsWith("[")) continue

            val parsed = parseJsonElement(payload) ?: continue
            result = result.merge(extractTikTokEmbeddedMetadata(parsed))
        }
        return result.merge(extractTikTokRegexMetadata(document.outerHtml()))
    }

    private fun extractTikTokEmbeddedMetadata(root: JsonElement): TikTokEmbeddedMetadata {
        var result = TikTokEmbeddedMetadata()
        for (obj in traverseJsonObjects(root)) {
            val itemStruct = firstTikTokItemStruct(obj)
            if (itemStruct != null) {
                result = result.merge(extractTikTokItemStructMetadata(itemStruct))
            }

            val shareMeta = obj.jsonObjectOrNull("shareMeta")
                ?: obj.takeIf(::looksLikeTikTokShareMeta)
            if (shareMeta != null) {
                result = result.merge(extractTikTokShareMetaMetadata(shareMeta))
            }

            val canonical = obj.firstNonBlankString("canonical")
                ?.takeIf { value -> value.contains("tiktok.com/", ignoreCase = true) }
                ?.let { value -> runCatching { URI(value) }.getOrNull() }
                ?.let(::extractServiceCanonical)
            if (canonical != null) {
                result = result.merge(TikTokEmbeddedMetadata(canonicalId = canonical))
            }
        }
        return result
    }

    private fun firstTikTokItemStruct(obj: JsonObject): JsonObject? {
        obj.jsonObjectOrNull("itemStruct")?.let { return it }
        obj.jsonObjectOrNull("itemInfo")
            ?.jsonObjectOrNull("itemStruct")
            ?.let { return it }
        return obj.takeIf(::looksLikeTikTokItemStruct)
    }

    private fun looksLikeTikTokItemStruct(obj: JsonObject): Boolean {
        return obj.firstNonBlankString("id") != null &&
            obj.jsonObjectOrNull("author") != null &&
            (
                obj.firstNonBlankString("desc") != null ||
                    obj.jsonObjectOrNull("video") != null
                )
    }

    private fun looksLikeTikTokShareMeta(obj: JsonObject): Boolean {
        return obj.firstNonBlankString("cover_url") != null &&
            (
                obj.firstNonBlankString("desc") != null ||
                    obj.firstNonBlankString("title") != null
                )
    }

    private fun extractTikTokItemStructMetadata(item: JsonObject): TikTokEmbeddedMetadata {
        val author = item.jsonObjectOrNull("author")
        val video = item.jsonObjectOrNull("video")
        val shareInfo = item.jsonObjectOrNull("shareInfo")
        val title = firstNonBlank(
            normalizeTikTokTitleText(author?.firstNonBlankString("nickname")),
            author?.firstNonBlankString("uniqueId")?.let { uniqueId ->
                normalizeTikTokTitleText("@${uniqueId.trimStart('@')}")
            },
            normalizeTikTokTitleText(shareInfo?.firstNonBlankString("title")),
        )
        val body = firstNonBlank(
            normalizeTikTokBodyText(item.firstNonBlankString("desc")),
            extractTikTokCaptionFromShareDescription(shareInfo?.firstNonBlankString("desc")),
        )
        val thumbnail = firstNonBlank(
            normalizeUrlAttribute(video?.firstNonBlankString("cover", "originCover", "dynamicCover")),
            normalizeUrlAttribute(video?.firstArrayString("shareCover")),
        )
        val badgeImage = normalizeUrlAttribute(
            author?.firstNonBlankString("avatarLarger", "avatarMedium", "avatarThumb"),
        )

        return TikTokEmbeddedMetadata(
            title = title,
            body = body,
            description = body,
            thumbnailUrl = thumbnail,
            badgeImageUrl = badgeImage,
            canonicalId = item.firstNonBlankString("id"),
        )
    }

    private fun extractTikTokShareMetaMetadata(shareMeta: JsonObject): TikTokEmbeddedMetadata {
        val body = extractTikTokCaptionFromShareDescription(shareMeta.firstNonBlankString("desc"))
        return TikTokEmbeddedMetadata(
            title = normalizeTikTokTitleText(shareMeta.firstNonBlankString("title")),
            body = body,
            description = body ?: normalizeTikTokBodyText(shareMeta.firstNonBlankString("desc")),
            thumbnailUrl = normalizeUrlAttribute(shareMeta.firstNonBlankString("cover_url")),
        )
    }

    private fun extractTikTokCaptionFromShareDescription(rawDescription: String?): String? {
        val source = normalizeTikTokBodyText(rawDescription) ?: return null
        val quoted = TIKTOK_QUOTED_TEXT_PATTERN.find(source)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::normalizeTikTokBodyText)
        if (quoted != null) return quoted

        val withoutStats = source
            .replace(TIKTOK_SHARE_STATS_PREFIX_PATTERN, "")
            .trim()
        return normalizeTikTokBodyText(withoutStats)
    }

    private fun extractTikTokRegexMetadata(html: String): TikTokEmbeddedMetadata {
        val itemMatch = TIKTOK_ITEM_STRUCT_PATTERN.find(html)
        val itemId = itemMatch?.groupValues?.getOrNull(1)
        val itemBody = itemMatch?.groupValues?.getOrNull(2)
            ?.let(::decodeJsonEscapedString)
            ?.let(::normalizeTikTokBodyText)

        val authorMatch = TIKTOK_AUTHOR_PATTERN.find(html)
        val authorUniqueId = authorMatch?.groupValues?.getOrNull(1)
            ?.let(::decodeJsonEscapedString)
        val authorNickname = authorMatch?.groupValues?.getOrNull(2)
            ?.let(::decodeJsonEscapedString)
        val title = firstNonBlank(
            normalizeTikTokTitleText(authorNickname),
            authorUniqueId?.let { uniqueId -> normalizeTikTokTitleText("@${uniqueId.trimStart('@')}") },
        )

        val shareDescription = TIKTOK_SHARE_META_DESC_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::decodeJsonEscapedString)
        val shareTitle = TIKTOK_SHARE_META_TITLE_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::decodeJsonEscapedString)
            ?.let(::normalizeTikTokTitleText)
        val shareCover = TIKTOK_SHARE_META_COVER_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::decodeJsonEscapedString)
            ?.let(::normalizeUrlAttribute)
        val videoCover = TIKTOK_VIDEO_COVER_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::decodeJsonEscapedString)
            ?.let(::normalizeUrlAttribute)
        val badgeImage = TIKTOK_AUTHOR_AVATAR_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::decodeJsonEscapedString)
            ?.let(::normalizeUrlAttribute)

        val body = firstNonBlank(
            itemBody,
            extractTikTokCaptionFromShareDescription(shareDescription),
        )

        return TikTokEmbeddedMetadata(
            title = firstNonBlank(title, shareTitle),
            body = body,
            description = body ?: normalizeTikTokBodyText(shareDescription),
            thumbnailUrl = firstNonBlank(videoCover, shareCover),
            badgeImageUrl = badgeImage,
            canonicalId = itemId,
        )
    }

    private fun extractInstagramCaptionedEmbedCaption(document: Document): String? {
        val caption = document.selectFirst(".Caption") ?: return null
        val cleaned = caption.clone()
        cleaned.select(".CaptionComments, .CaptionCommentsExpand, .CaptionUsername").remove()
        return normalizeFetchedBodyText(cleaned.text())
    }

    private fun extractInstagramCaptionedEmbedUsername(document: Document): String? {
        val username = firstNonBlank(
            document.selectFirst(".HeaderText .Username")?.text(),
            document.selectFirst(".CaptionUsername")?.text(),
        )
        return normalizeTitleText(username)
    }

    private fun extractInstagramCaptionedEmbedAuthorUrl(document: Document): String? {
        val href = firstNonBlank(
            document.selectFirst(".HeaderText .Username")?.attr("href"),
            document.selectFirst(".CaptionUsername")?.attr("href"),
            document.selectFirst("a.Avatar")?.attr("href"),
        ) ?: return null
        return normalizeUrlAttribute(href)
    }

    private fun extractInstagramCaptionedEmbedBadgeImage(document: Document): String? {
        val src = firstNonBlank(
            document.selectFirst(".AvatarContainer img")?.attr("src"),
            document.selectFirst("a.Avatar img")?.attr("src"),
        ) ?: return null
        return normalizeUrlAttribute(src)
    }

    private fun extractInstagramCaptionedEmbedThumbnail(document: Document): String? {
        val candidate = firstNonBlank(
            document.selectFirst(".EmbeddedMediaImage")?.attr("src"),
            document.selectFirst(".EmbeddedMedia video[poster]")?.attr("poster"),
            document.selectFirst("video[poster]")?.attr("poster"),
        ) ?: return null
        return normalizeUrlAttribute(candidate)
    }

    private fun extractYouTubeChannelBadge(document: Document): String? {
        val candidates = YOUTUBE_CHANNEL_BADGE_PATTERN.findAll(document.outerHtml())
            .map { matchResult ->
                matchResult.value
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
            }
            .toList()
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.contains("=s400") || it.contains("=s250") }
            ?: candidates.first()
    }

    private fun findVideoObjectDescription(element: JsonElement): String? {
        return when (element) {
            is JsonObject -> {
                if (element.hasType("VideoObject")) {
                    element.firstNonBlankString("description")?.let { return it }
                }
                element.values.asSequence().mapNotNull { child ->
                    findVideoObjectDescription(child)
                }.firstOrNull()
            }

            is JsonArray -> element.asSequence().mapNotNull { child ->
                findVideoObjectDescription(child)
            }.firstOrNull()

            else -> null
        }
    }

    private fun JsonObject.hasType(typeName: String): Boolean {
        val typeElement = this["@type"] ?: return false
        return when (typeElement) {
            is JsonPrimitive -> typeElement.contentOrNull?.equals(typeName, ignoreCase = true) == true
            is JsonArray -> typeElement.any { item ->
                (item as? JsonPrimitive)?.contentOrNull?.equals(typeName, ignoreCase = true) == true
            }

            else -> false
        }
    }

    private fun extractInstagramJsonLdMetadata(document: Document): InstagramJsonLdMetadata {
        var bodyCandidate: String? = null
        var titleCandidate: String? = null

        for (script in document.select("script[type=application/ld+json]")) {
            val payload = script.data().trim().ifBlank { script.html().trim() }
            if (payload.isBlank()) continue

            val parsed = parseJsonElement(payload) ?: continue
            for (jsonObject in traverseJsonObjects(parsed)) {
                if (bodyCandidate == null) {
                    bodyCandidate = jsonObject.firstNonBlankString(
                        "caption",
                        "description",
                        "articleBody",
                        "text",
                    )
                }
                if (titleCandidate == null) {
                    titleCandidate = jsonObject.firstNonBlankString("headline", "name")
                        ?: jsonObject.jsonObjectOrNull("author")
                            ?.firstNonBlankString("name", "alternateName")
                }
                if (bodyCandidate != null && titleCandidate != null) {
                    break
                }
            }
            if (bodyCandidate != null && titleCandidate != null) {
                break
            }
        }

        return InstagramJsonLdMetadata(
            body = normalizeFetchedBodyText(bodyCandidate),
            titleCandidate = normalizeTitleText(titleCandidate),
        )
    }

    private fun extractInstagramEmbeddedCaption(document: Document): String? {
        for (script in document.select("script")) {
            val payload = script.data().ifBlank { script.html() }
            if (payload.isBlank()) continue

            for (pattern in INSTAGRAM_CAPTION_PATTERNS) {
                val match = pattern.find(payload) ?: continue
                val decoded = decodeJsonEscapedString(match.groupValues[1]) ?: continue
                val normalized = normalizeFetchedBodyText(decoded)
                if (normalized != null) {
                    return normalized
                }
            }
        }
        return null
    }

    private fun extractInstagramEmbeddedUsername(document: Document): String? {
        for (script in document.select("script")) {
            val payload = script.data().ifBlank { script.html() }
            if (payload.isBlank()) continue

            for (pattern in INSTAGRAM_USERNAME_PATTERNS) {
                val match = pattern.find(payload) ?: continue
                val decoded = decodeJsonEscapedString(match.groupValues[1]) ?: continue
                val normalized = normalizeTitleText(decoded)
                if (normalized != null) {
                    return normalized.removePrefix("@")
                }
            }
        }
        return null
    }

    private fun isInstagramErrorDocument(document: Document): Boolean {
        val outerHtml = document.outerHtml()
        return outerHtml.contains("PolarisErrorRoute") ||
            outerHtml.contains("\"httpErrorPage\"") ||
            outerHtml.contains("\"show_lox_redesigned_404_page\":true")
    }

    private fun decodeJsonEscapedString(escaped: String): String? {
        return runCatching {
            jsonParser.decodeFromString<String>("\"$escaped\"")
        }.getOrNull() ?: escaped
            .replace("\\n", "\n")
            .replace("\\r", "")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
    }

    private fun normalizeUrlAttribute(rawValue: String?): String? {
        val source = rawValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return Jsoup.parseBodyFragment(source).text().trim().ifBlank { null }
    }

    private fun extractWebBodyWithKind(document: Document): WebBodyCandidate {
        val description = extractMetaContent(
            document,
            "meta[name=description]",
            "meta[property=og:description]",
            "meta[name=og:description]",
            "meta[name=twitter:description]",
            "meta[property=twitter:description]",
        )?.let(::normalizeFetchedBodyText)

        if (description != null) {
            return WebBodyCandidate(
                text = description,
                kind = MetadataBodyKind.WEB_DESCRIPTION,
            )
        }

        val articleParagraphs = extractParagraphCandidates(document, "article p")
        if (articleParagraphs.isNotEmpty()) {
            return WebBodyCandidate(
                text = joinParagraphs(articleParagraphs),
                kind = MetadataBodyKind.WEB_EXCERPT,
            )
        }

        val mainParagraphs = extractParagraphCandidates(document, "main p")
        if (mainParagraphs.isNotEmpty()) {
            return WebBodyCandidate(
                text = joinParagraphs(mainParagraphs),
                kind = MetadataBodyKind.WEB_EXCERPT,
            )
        }

        val genericParagraphs = extractParagraphCandidates(document, "p")
        if (genericParagraphs.isNotEmpty()) {
            return WebBodyCandidate(
                text = joinParagraphs(genericParagraphs),
                kind = MetadataBodyKind.WEB_EXCERPT,
            )
        }

        return WebBodyCandidate(text = null, kind = null)
    }

    private fun extractParagraphCandidates(document: Document, selector: String): List<String> {
        val normalized = document.select(selector)
            .asSequence()
            .map { it.text() }
            .mapNotNull(::normalizeBodyText)
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        if (normalized.isEmpty()) {
            return emptyList()
        }

        val preferred = normalized.filter { it.length >= MIN_WEB_PARAGRAPH_LENGTH }
        val source = if (preferred.isNotEmpty()) preferred else normalized
        return source.take(MAX_WEB_PARAGRAPHS)
    }

    private fun joinParagraphs(paragraphs: List<String>): String? {
        if (paragraphs.isEmpty()) return null
        return truncateWithEllipsis(
            text = paragraphs.joinToString("\n\n"),
            maxLength = BODY_TEXT_MAX_LENGTH,
        )
    }

    private fun extractMetaContent(document: Document, vararg selectors: String): String? {
        return selectors.asSequence()
            .mapNotNull { selector ->
                document.selectFirst(selector)
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .firstOrNull()
    }

    private fun extractDescription(document: Document): String? {
        return extractMetaContent(
            document,
            "meta[property=og:description]",
            "meta[name=twitter:description]",
            "meta[name=description]",
        )?.let(::normalizeFetchedBodyText)
    }

    private fun extractOgImage(document: Document): String? {
        val metaContent = sequenceOf(
            "meta[property=og:image]",
            "meta[name=og:image]",
            "meta[property=twitter:image]",
            "meta[name=twitter:image]",
        ).mapNotNull { selector ->
            document.selectFirst(selector)?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        }.firstOrNull()

        if (metaContent == null) {
            return null
        }

        val absolute = runCatching { URI(document.baseUri()).resolve(metaContent).toString() }.getOrNull()
        return absolute ?: metaContent
    }

    private fun extractFavicon(document: Document, finalUri: URI): String? {
        val appleTouch = document.select("link[rel]").firstOrNull { element ->
            val rel = element.attr("rel").lowercase(Locale.ROOT)
            rel == "apple-touch-icon" || rel == "apple-touch-icon-precomposed"
        }?.attr("href")

        val genericIcon = document.select("link[rel]").firstOrNull { element ->
            val rel = element.attr("rel").lowercase(Locale.ROOT)
            rel.contains("icon")
        }?.attr("href")

        val candidate = appleTouch ?: genericIcon
        val resolved = resolveAbsoluteUrl(candidate, finalUri)
        if (!resolved.isNullOrBlank()) {
            return resolved
        }

        val host = finalUri.host ?: return null
        val scheme = finalUri.scheme?.takeIf { it.isNotBlank() } ?: "https"
        return "$scheme://$host/favicon.ico"
    }

    private fun resolveAbsoluteUrl(rawUrl: String?, baseUri: URI): String? {
        val candidate = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { baseUri.resolve(candidate).toString() }.getOrElse { candidate }
    }

    private fun fetchSingleOgImage(url: String): String? {
        return fetchFromHtmlPrefix(url) { document ->
            extractOgImage(document)
        }
    }

    private fun fetchSingleYouTubeBadge(url: String): String? {
        return fetchFromHtmlPrefix(url) { document ->
            extractYouTubeChannelBadge(document)
        }
    }

    private fun fetchFromHtmlPrefix(url: String, extract: (Document) -> String?): String? {
        var current = url
        var redirects = 0

        while (true) {
            val currentUri = runCatching { URI(current) }.getOrNull() ?: return null
            if (!isFetchableScheme(currentUri)) return null

            val connection = connectionFactory(current).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }

            try {
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location") ?: return null
                        redirects += 1
                        if (redirects > 5) return null
                        current = currentUri.resolve(location).toString()
                        continue
                    }

                    code !in 200..299 -> return null
                }

                val headBytes = BufferedInputStream(connection.inputStream).use { input ->
                    readPrefixBody(input, OG_IMAGE_PROBE_LIMIT_BYTES)
                }
                if (headBytes.isEmpty()) return null

                val bodyText = headBytes.toString(Charsets.UTF_8)
                val document = Jsoup.parse(bodyText, current)
                return extract(document)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun extractCanonical(document: Document, currentUri: URI): String? {
        val canonicalHref = document.selectFirst("link[rel=canonical]")
            ?.attr("abs:href")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val canonicalUri = canonicalHref?.let { href ->
            runCatching { URI(href) }.getOrNull()
        }

        return extractServiceCanonical(canonicalUri ?: currentUri)
    }

    private fun extractServiceCanonical(uri: URI): String? {
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path.orEmpty()
        return when {
            host.endsWith("youtube.com") -> {
                val segments = path.split('/').filter { it.isNotBlank() }
                when {
                    segments.size >= 2 && segments[0] in setOf("shorts", "embed", "live") -> segments[1]
                    else -> URI("https", host, "/watch", uri.rawQuery, null).rawQuery
                        ?.split('&')
                        ?.firstOrNull { it.startsWith("v=") }
                        ?.removePrefix("v=")
                }
            }
            host == "youtu.be" -> path.trim('/').ifBlank { null }
            host.endsWith("instagram.com") -> {
                val segments = path.split('/').filter { it.isNotBlank() }
                if (segments.size >= 2 &&
                    segments[0] in setOf("p", "reel", "tv")
                ) {
                    segments[1]
                } else {
                    null
                }
            }
            host.endsWith("tiktok.com") -> path.substringAfterLast('/', "").ifBlank { null }
            else -> UrlRules.extractXStatusId(uri.toString())
        }
    }

    private fun readLimitedBody(input: BufferedInputStream, maxBytes: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun readPrefixBody(input: BufferedInputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(buffer.size, maxBytes - total)
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun normalizeTitleText(raw: String?): String? {
        return raw
            ?.replace('\u00A0', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizeTikTokTitleText(raw: String?): String? {
        val normalized = normalizeTitleText(raw)
            ?.removePrefix("TikTok · ")
            ?.removeSuffix(" on TikTok")
            ?.trim()
            ?: return null
        return normalized.takeUnless { title ->
            title == "@" ||
                title.equals("TikTok", ignoreCase = true) ||
                title.equals("TikTok - Make Your Day", ignoreCase = true)
        }
    }

    private fun normalizeBodyText(raw: String?): String? {
        return raw
            ?.replace('\u00A0', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizeFetchedBodyText(raw: String?): String? {
        val normalized = normalizeBodyText(raw) ?: return null
        return truncateWithEllipsis(normalized, BODY_TEXT_MAX_LENGTH)
    }

    private fun normalizeXArticleBodyText(raw: String?): String? {
        val normalized = raw
            ?.replace('\u00A0', ' ')
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.lines()
            ?.joinToString("\n") { it.trim() }
            ?.replace(Regex("\n{3,}"), "\n\n")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return truncateWithEllipsis(normalized, X_ARTICLE_BODY_MAX_LENGTH)
    }

    private fun normalizeYouTubeDescriptionText(raw: String?): String? {
        val normalized = normalizeFetchedBodyText(raw) ?: return null
        return normalized.takeUnless { body ->
            body == "作成した動画を友だち、家族、世界中の人たちと共有" ||
                body.equals("Enjoy the videos and music you love, upload original content, and share it all with friends, family, and the world on YouTube.", ignoreCase = true)
        }
    }

    private fun normalizeTikTokBodyText(raw: String?): String? {
        val normalized = normalizeFetchedBodyText(raw) ?: return null
        return normalized.takeUnless { body ->
            body.equals("TikTok - Make Your Day", ignoreCase = true) ||
                body.equals("Discover more on TikTok", ignoreCase = true)
        }
    }

    private fun normalizeTikTokAuthorUrl(rawUrl: String?): String? {
        val url = normalizeUrlAttribute(rawUrl) ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path.orEmpty().trim('/')
        if (host.endsWith("tiktok.com") && path.isBlank()) {
            return null
        }
        return url
    }

    private fun buildRuleSummary(body: String): String? {
        val normalized = normalizeBodyText(body) ?: return null
        val withoutUrls = normalized
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val source = withoutUrls.ifBlank { normalized }
        if (source.isBlank()) return null

        val sentenceBoundary = SENTENCE_BOUNDARY_REGEX.find(source)
        val firstSentence = if (sentenceBoundary != null) {
            source.substring(0, sentenceBoundary.range.last + 1).trim()
        } else {
            source
        }

        return truncateWithEllipsis(firstSentence, SUMMARY_MAX_LENGTH)
    }

    private fun truncateWithEllipsis(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        return text.take(maxLength - 1).trimEnd() + "…"
    }

    private fun buildYouTubePlayerRequest(videoId: String): String {
        val escapedVideoId = videoId
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return """
            {
              "context": {
                "client": {
                  "clientName": "WEB",
                  "clientVersion": "$YOUTUBE_WEB_CLIENT_VERSION",
                  "hl": "ja",
                  "gl": "JP"
                }
              },
              "videoId": "$escapedVideoId"
            }
        """.trimIndent()
    }

    private fun parseJsonElement(bodyText: String): JsonElement? {
        return runCatching { jsonParser.parseToJsonElement(bodyText) }.getOrNull()
    }

    private fun parseJsonObject(bodyText: String): JsonObject? {
        return parseJsonElement(bodyText) as? JsonObject
    }

    private fun traverseJsonObjects(element: JsonElement): Sequence<JsonObject> = sequence {
        when (element) {
            is JsonObject -> {
                yield(element)
                for (value in element.values) {
                    yieldAll(traverseJsonObjects(value))
                }
            }

            is JsonArray -> {
                for (item in element) {
                    yieldAll(traverseJsonObjects(item))
                }
            }

            else -> Unit
        }
    }

    private fun JsonObject.firstPhotoThumbnailUrl(): String? {
        val photos = this["photos"] as? JsonArray ?: return null
        for (item in photos) {
            val photo = item as? JsonObject ?: continue
            val candidate = photo.firstNonBlankString("url", "media_url_https", "media_url", "expandedUrl", "expanded_url")
            if (candidate != null) {
                return candidate
            }
        }
        return null
    }

    private fun JsonObject.firstVideoThumbnailUrl(): String? {
        val video = jsonObjectOrNull("video")
        val directCandidate = video?.firstNonBlankString("poster", "poster_url", "thumbnail_url", "url")
        if (directCandidate != null) {
            return directCandidate
        }

        val mediaDetails = this["mediaDetails"] as? JsonArray ?: return null
        for (item in mediaDetails) {
            val media = item as? JsonObject ?: continue
            val type = media.firstNonBlankString("type")
            if (type != null &&
                !type.equals("video", ignoreCase = true) &&
                !type.equals("animated_gif", ignoreCase = true)
            ) {
                continue
            }
            val candidate = media.firstNonBlankString("media_url_https", "media_url", "thumbnail_url", "url")
            if (candidate != null) {
                return candidate
            }
        }
        return null
    }

    private fun JsonObject.extractSyndicationBody(): String? {
        val direct = firstNonBlankString(
            "text",
            "full_text",
            "display_text",
            "tweet_text",
        )
        if (direct != null) {
            return normalizeFetchedBodyText(direct)
        }

        val nestedCandidates = sequenceOf(
            jsonObjectOrNull("legacy"),
            jsonObjectOrNull("tweet"),
            jsonObjectOrNull("status"),
            jsonObjectOrNull("data"),
        ).filterNotNull()

        for (nested in nestedCandidates) {
            val candidate = nested.firstNonBlankString("full_text", "text")
            if (candidate != null) {
                return normalizeFetchedBodyText(candidate)
            }
        }

        return null
    }

    private fun JsonObject.extractOEmbedBody(): String? {
        val direct = firstNonBlankString("text")
        if (direct != null) {
            return normalizeFetchedBodyText(direct)
        }

        val html = firstNonBlankString("html") ?: return null
        val parsed = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull() ?: return null
        val fromTweetBlock = parsed.selectFirst("blockquote.twitter-tweet p")?.text()
        val fromParagraph = parsed.selectFirst("p")?.text()
        return normalizeFetchedBodyText(fromTweetBlock ?: fromParagraph)
    }

    private fun JsonObject.firstNonBlankString(vararg keys: String): String? {
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (value != null) {
                return value
            }
        }
        return null
    }

    private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? {
        return this[key] as? JsonObject
    }

    private fun JsonObject.jsonArrayOrNull(key: String): JsonArray? {
        return this[key] as? JsonArray
    }

    private fun JsonObject.firstArrayString(vararg keys: String): String? {
        for (key in keys) {
            val array = jsonArrayOrNull(key) ?: continue
            val value = array.firstNonBlankString()
            if (value != null) return value
        }
        return null
    }

    private fun JsonArray.firstNonBlankString(): String? {
        for (item in this) {
            val value = (item as? JsonPrimitive)
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }

    private fun JsonObject.richTextString(): String? {
        firstNonBlankString("simpleText")?.let { return it }
        val runs = jsonArrayOrNull("runs") ?: return null
        val combined = runs.asSequence()
            .mapNotNull { item ->
                (item as? JsonObject)?.firstNonBlankString("text")
            }
            .joinToString("")
        return combined.trim().takeIf { it.isNotBlank() }
    }

    private fun JsonObject.bestThumbnailUrl(): String? {
        val thumbnails = jsonArrayOrNull("thumbnails") ?: return null
        return thumbnails.asSequence()
            .mapNotNull { item -> item as? JsonObject }
            .mapNotNull { thumbnail ->
                val url = thumbnail.firstNonBlankString("url") ?: return@mapNotNull null
                val width = thumbnail.intOrNull("width") ?: 0
                val height = thumbnail.intOrNull("height") ?: 0
                Triple(url, width, height)
            }
            .maxByOrNull { (_, width, height) -> width * height }
            ?.first
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        return (this[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.toIntOrNull()
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun String.isSingleHttpUrl(): Boolean {
        val trimmed = trim()
        return !trimmed.any(Char::isWhitespace) &&
            runCatching {
                val uri = URI(trimmed)
                uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
    }

    private fun isFetchableScheme(uri: URI): Boolean {
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> true
            // Allow loopback http for local test infrastructure only.
            "http" -> {
                val host = uri.host?.lowercase(Locale.ROOT)
                host == "127.0.0.1" || host == "localhost" || host == "::1"
            }

            else -> false
        }
    }

    private fun classifyHtmlMetadataService(host: String?): HtmlMetadataService {
        val lowered = host?.lowercase(Locale.ROOT).orEmpty()
        return when {
            lowered.endsWith("youtube.com") || lowered == "youtu.be" -> HtmlMetadataService.YOUTUBE
            lowered.endsWith("tiktok.com") -> HtmlMetadataService.TIKTOK
            lowered.endsWith("instagram.com") -> HtmlMetadataService.INSTAGRAM
            else -> HtmlMetadataService.WEB
        }
    }

    private data class YouTubeOEmbedMetadata(
        val title: String?,
        val authorName: String? = null,
        val thumbnailUrl: String?,
        val authorUrl: String?,
        val failureOutcome: FetchOutcome?,
    ) {
        val hasAnyMetadata: Boolean
            get() = !title.isNullOrBlank() || !authorName.isNullOrBlank() || !thumbnailUrl.isNullOrBlank()
    }

    private data class YouTubePlayerMetadata(
        val title: String? = null,
        val body: String? = null,
        val thumbnailUrl: String? = null,
        val canonicalId: String? = null,
        val failureOutcome: FetchOutcome? = null,
    ) {
        val hasAnyMetadata: Boolean
            get() = !title.isNullOrBlank() ||
                !body.isNullOrBlank() ||
                !thumbnailUrl.isNullOrBlank()
    }

    private data class InstagramOEmbedMetadata(
        val title: String?,
        val body: String?,
        val thumbnailUrl: String?,
        val authorUrl: String?,
        val badgeImageUrl: String?,
    ) {
        val hasAnyMetadata: Boolean
            get() = !title.isNullOrBlank() || !body.isNullOrBlank() || !thumbnailUrl.isNullOrBlank()
    }

    private data class TikTokOEmbedMetadata(
        val title: String?,
        val body: String?,
        val thumbnailUrl: String?,
        val authorUrl: String?,
        val canonicalId: String?,
        val failureOutcome: FetchOutcome?,
    ) {
        val hasAnyMetadata: Boolean
            get() = !title.isNullOrBlank() ||
                !body.isNullOrBlank() ||
                !thumbnailUrl.isNullOrBlank()

        val hasCompleteMetadata: Boolean
            get() = !title.isNullOrBlank() &&
                !body.isNullOrBlank() &&
                !thumbnailUrl.isNullOrBlank() &&
                !canonicalId.isNullOrBlank()
    }

    private data class TikTokEmbeddedMetadata(
        val title: String? = null,
        val body: String? = null,
        val description: String? = null,
        val thumbnailUrl: String? = null,
        val badgeImageUrl: String? = null,
        val canonicalId: String? = null,
    ) {
        fun merge(supplement: TikTokEmbeddedMetadata): TikTokEmbeddedMetadata {
            return TikTokEmbeddedMetadata(
                title = title ?: supplement.title,
                body = body ?: supplement.body,
                description = description ?: supplement.description,
                thumbnailUrl = thumbnailUrl ?: supplement.thumbnailUrl,
                badgeImageUrl = badgeImageUrl ?: supplement.badgeImageUrl,
                canonicalId = canonicalId ?: supplement.canonicalId,
            )
        }
    }

    private data class InstagramJsonLdMetadata(
        val body: String?,
        val titleCandidate: String?,
    )

    private data class WebBodyCandidate(
        val text: String?,
        val kind: MetadataBodyKind?,
    )

    private enum class HtmlMetadataService {
        YOUTUBE,
        TIKTOK,
        INSTAGRAM,
        WEB,
    }

    companion object {
        private const val BODY_LIMIT_BYTES = 512L * 1024L
        private const val OG_IMAGE_PROBE_LIMIT_BYTES = 2 * 1024 * 1024
        private const val BODY_TEXT_MAX_LENGTH = 4000
        private const val MIN_WEB_PARAGRAPH_LENGTH = 24
        private const val MAX_WEB_PARAGRAPHS = 8

        private const val DEFAULT_USER_AGENT = "UrlSaver/unknown"
        private const val YOUTUBE_BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val TIKTOK_BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val DEFAULT_SYNDICATION_TOKEN = "1"
        private const val SYNDICATION_ENDPOINT = "https://cdn.syndication.twimg.com/tweet-result"
        private const val OEMBED_ENDPOINT = "https://publish.twitter.com/oembed"
        private const val X_GUEST_ACTIVATION_ENDPOINT = "https://api.x.com/1.1/guest/activate.json"
        private const val X_ARTICLE_GRAPHQL_ENDPOINT =
            "https://api.x.com/graphql/-4_LMahNlI4MuLJ-EAFEog/TweetResultByRestId"
        private const val X_PUBLIC_BEARER_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        private const val X_WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val X_ARTICLE_BODY_MAX_LENGTH = 200_000
        private const val YOUTUBE_OEMBED_ENDPOINT = "https://www.youtube.com/oembed"
        private const val YOUTUBE_PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
        private const val YOUTUBE_INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val YOUTUBE_WEB_CLIENT_VERSION = "2.20260422.08.00"
        private const val TIKTOK_OEMBED_ENDPOINT = "https://www.tiktok.com/oembed"
        private const val TIKTOK_FALLBACK_ENDPOINT = "https://www.tikwm.com/api/"
        private const val INSTAGRAM_PUBLIC_OEMBED_ENDPOINT = "https://www.instagram.com/api/v1/oembed/"

        private const val SUMMARY_MAX_LENGTH = 110
        private val SENTENCE_BOUNDARY_REGEX = Regex("[。.!?！？]")

        private fun buildXArticleGraphQLEndpoint(statusId: String): String {
            val variables = """{"tweetId":"$statusId","withCommunity":false,"includePromotedContent":false,"withVoice":false}"""
            val features = """{"creator_subscriptions_tweet_preview_api_enabled":true,"premium_content_api_read_enabled":false,"communities_web_enable_tweet_community_results_fetch":true,"c9s_tweet_anatomy_moderator_badge_enabled":true,"articles_preview_enabled":true,"responsive_web_edit_tweet_api_enabled":true,"graphql_is_translatable_rweb_tweet_is_translatable_enabled":true,"view_counts_everywhere_api_enabled":true,"longform_notetweets_consumption_enabled":true,"responsive_web_twitter_article_tweet_consumption_enabled":true,"creator_subscriptions_quote_tweet_preview_enabled":false,"freedom_of_speech_not_reach_fetch_enabled":true,"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled":true,"longform_notetweets_rich_text_read_enabled":true,"longform_notetweets_inline_media_enabled":true,"responsive_web_graphql_exclude_directive_enabled":true,"verified_phone_label_enabled":false,"responsive_web_graphql_skip_user_profile_image_extensions_enabled":false,"responsive_web_graphql_timeline_navigation_enabled":true,"responsive_web_enhance_cards_enabled":false}"""
            val toggles = """{"withAuxiliaryUserLabels":false,"withArticleRichContentState":true,"withArticlePlainText":true,"withGrokAnalyze":false,"withDisallowedReplyControls":false}"""
            return "$X_ARTICLE_GRAPHQL_ENDPOINT?variables=${urlEncode(variables)}&features=${urlEncode(features)}&fieldToggles=${urlEncode(toggles)}"
        }

        private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
        private val YOUTUBE_CHANNEL_BADGE_PATTERN =
            Regex("""https:(?:\\\\/\\\\/|//)yt3\.ggpht\.com[^"'\\\s<]+""")
        private val YOUTUBE_EMBEDDED_DESCRIPTION_PATTERNS = listOf(
            Regex("\"shortDescription\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", RegexOption.DOT_MATCHES_ALL),
            Regex("\"description\"\\s*:\\s*\\{\\s*\"simpleText\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", RegexOption.DOT_MATCHES_ALL),
        )
        private val YOUTUBE_RUNS_DESCRIPTION_BLOCK_PATTERN = Regex(
            "\"description\"\\s*:\\s*\\{\\s*\"runs\"\\s*:\\s*\\[(.*?)\\]\\s*\\}",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val YOUTUBE_RUNS_TEXT_PATTERN = Regex(
            "\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            RegexOption.DOT_MATCHES_ALL,
        )

        private val INSTAGRAM_CAPTION_PATTERNS = listOf(
            Regex("\"edge_media_to_caption\"\\s*:\\s*\\{.*?\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", RegexOption.DOT_MATCHES_ALL),
            Regex("\"caption\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", RegexOption.DOT_MATCHES_ALL),
            Regex("\"caption_text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", RegexOption.DOT_MATCHES_ALL),
            Regex("\"edge_media_to_caption\"\\s*:\\s*\\{[^\\}]*\"edges\"\\s*:\\s*\\[.*?\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", RegexOption.DOT_MATCHES_ALL),
        )
        private val INSTAGRAM_QUOTED_TEXT_PATTERN =
            Regex("[\"“「『]((?:.|\\n){8,2000}?)[\"”」』]")
        private val INSTAGRAM_USERNAME_PATTERNS = listOf(
            Regex("\"username\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", RegexOption.DOT_MATCHES_ALL),
            Regex("\"ownerUsername\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", RegexOption.DOT_MATCHES_ALL),
        )
        private val TIKTOK_QUOTED_TEXT_PATTERN =
            Regex("[\"“「『]((?:.|\\n){1,4000}?)[\"”」』]")
        private val TIKTOK_SHARE_STATS_PREFIX_PATTERN =
            Regex("^\\s*[\\d,.KMkm]+\\s+likes?,\\s*[\\d,.KMkm]+\\s+comments?\\.\\s*")
        private val TIKTOK_ITEM_STRUCT_PATTERN = Regex(
            "\"itemStruct\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*\"([^\"\\\\]+)\"\\s*,\\s*\"desc\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val TIKTOK_AUTHOR_PATTERN = Regex(
            "\"author\"\\s*:\\s*\\{.*?\"uniqueId\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\".*?\"nickname\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val TIKTOK_VIDEO_COVER_PATTERN = Regex(
            "\"video\"\\s*:\\s*\\{.*?\"cover\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val TIKTOK_AUTHOR_AVATAR_PATTERN = Regex(
            "\"avatar(?:Larger|Medium|Thumb)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val TIKTOK_SHARE_META_TITLE_PATTERN = Regex(
            "\"shareMeta\"\\s*:\\s*\\{.*?\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val TIKTOK_SHARE_META_DESC_PATTERN = Regex(
            "\"shareMeta\"\\s*:\\s*\\{.*?\"desc\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val TIKTOK_SHARE_META_COVER_PATTERN = Regex(
            "\"shareMeta\"\\s*:\\s*\\{.*?\"cover_url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            RegexOption.DOT_MATCHES_ALL,
        )

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
