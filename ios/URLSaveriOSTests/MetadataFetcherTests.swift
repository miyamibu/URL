import XCTest
@testable import URLSaveriOS

final class MetadataFetcherTests: XCTestCase {
    override func setUp() {
        super.setUp()
        MockURLProtocol.responses = [:]
    }

    override func tearDown() {
        MockURLProtocol.responses = [:]
        super.tearDown()
    }

    func testTikTokUsesOEmbedMetadata() async {
        let endpoint = URL(string: "https://mock.local/tiktok-oembed")!
        MockURLProtocol.responses[endpoint.absoluteString] = .json(
            """
            {
              "author_name": "Scout, Suki & Stella",
              "author_url": "https://www.tiktok.com/@scout2015",
              "title": "Scramble up ur name",
              "thumbnail_url": "https://images.example/tiktok.jpg",
              "embed_product_id": "6718335390845095173"
            }
            """
        )
        MockURLProtocol.responses["https://www.tiktok.com/@scout2015"] = .html(
            #"<html><head><meta property="og:image" content="https://images.example/tiktok-profile.jpg"></head></html>"#
        )

        let update = await makeFetcher(
            tiktokOEmbedEndpointBuilder: { _ in endpoint }
        ).fetch(for: makeRecord(serviceType: .tiktok, url: "https://www.tiktok.com/@scout2015/video/6718335390845095173"))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "Scout, Suki & Stella")
        XCTAssertEqual(update.fetchedBody, "Scramble up ur name")
        XCTAssertEqual(update.thumbnailURL, "https://images.example/tiktok.jpg")
        XCTAssertEqual(update.badgeImageURL, "https://images.example/tiktok-profile.jpg")
        XCTAssertEqual(update.canonicalID, "6718335390845095173")
    }

    func testTikTokEmbeddedJSONRescuesShortURLPostContentAndImages() async {
        let oEmbed = URL(string: "https://mock.local/tiktok-oembed-short")!
        let original = URL(string: "https://vt.tiktok.com/ZS981yAnN")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            """
            {
              "author_name": "@",
              "title": "",
              "thumbnail_url": "",
              "author_url": "https://www.tiktok.com/",
              "embed_product_id": "7622724848539782408"
            }
            """
        )
        MockURLProtocol.responses[original.absoluteString] = .html(
            """
            <html>
              <head><title>TikTok - Make Your Day</title></head>
              <body>
                <script type="application/json" id="__UNIVERSAL_DATA_FOR_REHYDRATION__">
                {
                  "webapp.video-detail": {
                    "itemInfo": {
                      "itemStruct": {
                        "id": "7622724848539782408",
                        "desc": "今回はカルフォルニアを回った中でおすすめだったスポットを紹介しました♪",
                        "video": {
                          "cover": "https://p16-common-sign.tiktokcdn.com/cover.jpeg",
                          "shareCover": ["https://p16-common-sign.tiktokcdn.com/share.jpeg"]
                        },
                        "author": {
                          "uniqueId": "trip123suuu",
                          "nickname": "ス",
                          "avatarThumb": "https://p16-common-sign.tiktokcdn.com/avatar.jpeg"
                        }
                      }
                    }
                  }
                }
                </script>
              </body>
            </html>
            """
        )

        let update = await makeFetcher(
            tiktokOEmbedEndpointBuilder: { _ in oEmbed }
        ).fetch(for: makeRecord(serviceType: .tiktok, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "ス")
        XCTAssertEqual(update.fetchedBody, "今回はカルフォルニアを回った中でおすすめだったスポットを紹介しました♪")
        XCTAssertEqual(update.thumbnailURL, "https://p16-common-sign.tiktokcdn.com/cover.jpeg")
        XCTAssertEqual(update.badgeImageURL, "https://p16-common-sign.tiktokcdn.com/avatar.jpeg")
        XCTAssertEqual(update.canonicalID, "7622724848539782408")
    }

    func testTikTokPhotoPostEmbeddedJSONUsesNestedImageAndAvatarURLLists() async {
        let oEmbed = URL(string: "https://mock.local/tiktok-oembed-photo")!
        let original = URL(string: "https://www.tiktok.com/@trip123suuu/photo/7622724848539782408")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            """
            {
              "author_name": "@",
              "title": "",
              "thumbnail_url": "",
              "author_url": "https://www.tiktok.com/",
              "embed_product_id": "7622724848539782408"
            }
            """
        )
        MockURLProtocol.responses[original.absoluteString] = .html(
            """
            <html>
              <head><title>TikTok - Make Your Day</title></head>
              <body>
                <script type="application/json" id="__UNIVERSAL_DATA_FOR_REHYDRATION__">
                {
                  "webapp.video-detail": {
                    "itemInfo": {
                      "itemStruct": {
                        "id": "7622724848539782408",
                        "desc": "カルフォルニア旅のおすすめスポット",
                        "author": {
                          "uniqueId": "trip123suuu",
                          "nickname": "ス",
                          "avatarThumb": {
                            "urlList": [
                              "https://p16-common-sign.tiktokcdn.com/photo-avatar.jpeg"
                            ]
                          }
                        },
                        "imagePost": {
                          "cover": {
                            "imageURL": {
                              "urlList": [
                                "https://p16-common-sign.tiktokcdn.com/photo-cover.jpeg"
                              ]
                            }
                          },
                          "images": [
                            {
                              "imageURL": {
                                "urlList": [
                                  "https://p16-common-sign.tiktokcdn.com/photo-first.jpeg"
                                ]
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
                </script>
              </body>
            </html>
            """
        )

        let update = await makeFetcher(
            tiktokOEmbedEndpointBuilder: { _ in oEmbed }
        ).fetch(for: makeRecord(serviceType: .tiktok, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "ス")
        XCTAssertEqual(update.fetchedBody, "カルフォルニア旅のおすすめスポット")
        XCTAssertEqual(update.thumbnailURL, "https://p16-common-sign.tiktokcdn.com/photo-cover.jpeg")
        XCTAssertEqual(update.badgeImageURL, "https://p16-common-sign.tiktokcdn.com/photo-avatar.jpeg")
        XCTAssertEqual(update.canonicalID, "7622724848539782408")
    }

    func testTikTokFreeFallbackRescuesPostContentWhenOfficialPathsAreEmpty() async {
        let oEmbed = URL(string: "https://mock.local/tiktok-oembed-empty")!
        let fallback = URL(string: "https://mock.local/tiktok-free-fallback")!
        let original = URL(string: "https://vt.tiktok.com/ZS96QKESc")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            """
            {
              "author_name": "@",
              "title": "",
              "thumbnail_url": "",
              "author_url": "https://www.tiktok.com/",
              "embed_product_id": "7632251073494781204"
            }
            """
        )
        MockURLProtocol.responses[original.absoluteString] = .html(
            "<html><head><title>TikTok - Make Your Day</title></head><body>empty shell</body></html>"
        )
        MockURLProtocol.responses[fallback.absoluteString] = .json(
            """
            {
              "code": 0,
              "msg": "success",
              "data": {
                "id": "7632251073494781204",
                "title": "中央線沿いならココが1番オススメ。ここでしか味わえない絶品です。",
                "cover": "https://p16-common-sign.tiktokcdn.com/free-cover.webp",
                "author": {
                  "nickname": "ぽよログ I 東京グルメ",
                  "unique_id": "poyo__log",
                  "avatar": "https://p16-common-sign.tiktokcdn.com/free-avatar.jpeg"
                }
              }
            }
            """
        )

        let update = await makeFetcher(
            tiktokOEmbedEndpointBuilder: { _ in oEmbed },
            tiktokFallbackEndpointBuilder: { _ in fallback }
        ).fetch(for: makeRecord(serviceType: .tiktok, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "ぽよログ I 東京グルメ")
        XCTAssertEqual(update.fetchedBody, "中央線沿いならココが1番オススメ。ここでしか味わえない絶品です。")
        XCTAssertEqual(update.thumbnailURL, "https://p16-common-sign.tiktokcdn.com/free-cover.webp")
        XCTAssertEqual(update.badgeImageURL, "https://p16-common-sign.tiktokcdn.com/free-avatar.jpeg")
        XCTAssertEqual(update.canonicalID, "7632251073494781204")
    }

    func testTikTokFreeFallbackUsesNestedImageAndAvatarURLLists() async {
        let fallback = URL(string: "https://mock.local/tiktok-free-fallback-nested")!
        let original = URL(string: "https://vt.tiktok.com/ZS96Nested")!
        MockURLProtocol.responses[original.absoluteString] = .html(
            "<html><head><title>TikTok - Make Your Day</title></head><body>empty shell</body></html>"
        )
        MockURLProtocol.responses[fallback.absoluteString] = .json(
            """
            {
              "code": 0,
              "msg": "success",
              "data": {
                "id": "7632251073494781204",
                "title": "中央線沿いならココが1番オススメ。",
                "imagePost": {
                  "images": [
                    {
                      "imageURL": {
                        "urlList": [
                          "https://p16-common-sign.tiktokcdn.com/fallback-photo.jpeg"
                        ]
                      }
                    }
                  ]
                },
                "author": {
                  "nickname": "ぽよログ I 東京グルメ",
                  "unique_id": "poyo__log",
                  "avatar_thumb": {
                    "url_list": [
                      "https://p16-common-sign.tiktokcdn.com/fallback-avatar.jpeg"
                    ]
                  }
                }
              }
            }
            """
        )

        let update = await makeFetcher(
            tiktokFallbackEndpointBuilder: { _ in fallback }
        ).fetch(for: makeRecord(serviceType: .tiktok, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.thumbnailURL, "https://p16-common-sign.tiktokcdn.com/fallback-photo.jpeg")
        XCTAssertEqual(update.badgeImageURL, "https://p16-common-sign.tiktokcdn.com/fallback-avatar.jpeg")
    }

    func testXUsesOEmbedAndSyndicationMetadata() async {
        let oEmbed = URL(string: "https://mock.local/x-oembed")!
        let syndication = URL(string: "https://mock.local/x-syndication")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            """
            {
              "author_name": "US Department of the Interior",
              "html": "<blockquote><p>Sunsets don&#39;t get much better.</p></blockquote>"
            }
            """
        )
        MockURLProtocol.responses[syndication.absoluteString] = .json(
            """
            {
              "id_str": "463440424141459456",
              "text": "Syndication tweet body",
              "user": {
                "profile_image_url_https": "https://pbs.twimg.com/profile_images/432081479/DOI_LOGO_normal.jpg"
              },
              "photos": [{"url": "https://images.example/x.jpg"}]
            }
            """
        )

        let update = await makeFetcher(
            xOEmbedEndpointBuilder: { _ in oEmbed },
            xSyndicationEndpointBuilder: { _ in syndication }
        ).fetch(for: makeRecord(serviceType: .x, url: "https://x.com/Interior/status/463440424141459456"))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "US Department of the Interior")
        XCTAssertEqual(update.fetchedBody, "Sunsets don't get much better.")
        XCTAssertEqual(update.thumbnailURL, "https://images.example/x.jpg")
        XCTAssertEqual(update.badgeImageURL, "https://pbs.twimg.com/profile_images/432081479/DOI_LOGO_400x400.jpg")
        XCTAssertEqual(update.canonicalID, "463440424141459456")
    }

    func testXArticleUsesArticleTitlePreviewAndCoverInsteadOfShortURL() async {
        let oEmbed = URL(string: "https://mock.local/x-article-oembed")!
        let syndication = URL(string: "https://mock.local/x-article-syndication")!
        let guestActivation = URL(string: "https://mock.local/x-guest-activate")!
        let articleGraphQL = URL(string: "https://mock.local/x-article-graphql")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            #"{"author_name":"mana","html":"<blockquote><p><a href=\"https://t.co/example\">https://t.co/example</a></p></blockquote>"}"#
        )
        MockURLProtocol.responses[syndication.absoluteString] = .json(
            """
            {
              "id_str": "2075435864532852921",
              "text": "https://t.co/example",
              "user": {"name": "mana"},
              "article": {
                "title": "海外の天才が見つけたChatGPT活用大全",
                "preview_text": "OpenAIは新世代モデルを発表した。",
                "cover_media": {"media_info": {"original_img_url": "https://pbs.twimg.com/media/article.jpg"}}
              }
            }
            """
        )
        MockURLProtocol.responses[guestActivation.absoluteString] = .json(
            #"{"guest_token":"guest-test-token"}"#
        )
        MockURLProtocol.responses[articleGraphQL.absoluteString] = .json(
            """
            {
              "data": {"tweetResult": {"result": {"article": {"article_results": {"result": {
                "content_state": {"blocks": []},
                "plain_text": "全文1行目。\\n全文2行目。"
              }}}}}}
            }
            """
        )

        let update = await makeFetcher(
            xOEmbedEndpointBuilder: { _ in oEmbed },
            xSyndicationEndpointBuilder: { _ in syndication },
            xGuestActivationEndpoint: guestActivation,
            xArticleGraphQLEndpointBuilder: { _ in articleGraphQL },
            xPublicBearerToken: "public-test-token"
        ).fetch(for: makeRecord(serviceType: .x, url: "https://x.com/i/status/2075435864532852921"))

        XCTAssertEqual(update.fetchedBody, "全文1行目。\n全文2行目。")
        XCTAssertEqual(update.description, "OpenAIは新世代モデルを発表した。")
        XCTAssertEqual(update.thumbnailURL, "https://pbs.twimg.com/media/article.jpg")
        XCTAssertEqual(update.fetchedAuthorName, "mana")
    }

    func testXListUsesOfficialOEmbedMetadataAndServiceIcon() async {
        let oEmbed = URL(string: "https://mock.local/x-list-oembed")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            #"{"title":"Official Twitter Accounts","description":"Official accounts","url":"https://x.com/i/lists/84839422","html":"<a>An X List by X</a>"}"#
        )

        let update = await makeFetcher(
            xOEmbedEndpointBuilder: { _ in oEmbed },
            xSyndicationEndpointBuilder: { _ in XCTFail("X List must not use tweet syndication"); return nil }
        ).fetch(for: makeRecord(
            serviceType: .x,
            url: "https://x.com/i/lists/84839422",
            contentContext: .list
        ))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "Official Twitter Accounts")
        XCTAssertEqual(update.fetchedAuthorName, "X")
        XCTAssertEqual(update.fetchedBody, "Official accounts")
        XCTAssertEqual(update.badgeImageURL, "https://x.com/apple-touch-icon.png")
        XCTAssertEqual(update.canonicalID, "84839422")
    }

    func testXSpaceWithoutRuntimeBearerTokenIsLoginRequiredAndClearsMetadata() async {
        let update = await makeFetcher(
            xSpaceMetadataEndpointBuilder: { _ in XCTFail("No endpoint should be called without a token"); return nil },
            xPublicBearerToken: nil
        ).fetch(for: makeRecord(
            serviceType: .x,
            url: "https://x.com/i/spaces/1vJpPrZrbEaJE",
            contentContext: .space
        ))

        XCTAssertEqual(update.metadataState, .unavailable)
        XCTAssertEqual(update.metadataError, .loginRequired)
        XCTAssertTrue(update.clearExistingMetadata)
        XCTAssertNil(update.fetchedTitle)
        XCTAssertNil(update.thumbnailURL)
        XCTAssertNil(update.badgeImageURL)
    }

    func testTikTokUnavailablePlaylistDoesNotBecomeReadyWithGenericPageMetadata() async {
        let original = URL(string: "https://www.tiktok.com/playlist-music/Buzz-Tracker-7081543689138391809")!
        MockURLProtocol.responses[original.absoluteString] = .html(
            "<html><head><title>TikTok - Make Your Day</title></head><body><div>Page not available</div></body></html>"
        )

        let update = await makeFetcher().fetch(for: makeRecord(
            serviceType: .tiktok,
            url: original.absoluteString,
            contentContext: .playlist
        ))

        XCTAssertEqual(update.metadataState, .unavailable)
        XCTAssertEqual(update.metadataError, .providerUnavailable)
        XCTAssertTrue(update.clearExistingMetadata)
        XCTAssertNil(update.fetchedTitle)
        XCTAssertNil(update.fetchedBody)
        XCTAssertNil(update.thumbnailURL)
        XCTAssertNil(update.badgeImageURL)
    }

    func testTikTokProfileUsesOEmbedWhenTheHTMLShellHasNoProfileMetadata() async {
        let oEmbed = URL(string: "https://mock.local/tiktok-profile-oembed")!
        let original = URL(string: "https://www.tiktok.com/@tiktok")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            #"{"title":"TikTok's Creator Profile","author_name":"TikTok","author_url":"https://www.tiktok.com/@tiktok","embed_product_id":"tiktok"}"#
        )
        MockURLProtocol.responses[original.absoluteString] = .html(
            #"<html><head><title>TikTok - Make Your Day</title></head></html>"#
        )

        let update = await makeFetcher(
            tiktokOEmbedEndpointBuilder: { _ in oEmbed }
        ).fetch(for: makeRecord(
            serviceType: .tiktok,
            url: original.absoluteString,
            contentContext: .profile
        ))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "TikTok's Creator Profile")
        XCTAssertNil(update.fetchedBody)
        XCTAssertEqual(update.canonicalID, "@tiktok")
        XCTAssertNotNil(update.badgeImageURL)
    }

    func testTikTokPlaylistIgnoresHiddenUnavailableCopyWhenPlaylistShellHasContentSpecificTitle() async {
        let original = URL(string: "https://www.tiktok.com/@tiktok/playlist/In-The-Mix-7516638364301265695")!
        MockURLProtocol.responses[original.absoluteString] = .html(
            #"<html><head><title>@tiktokが作成したプレイリストIn The Mix</title><meta name="description" content="Enjoy a curated video list and find more videos on TikTok!"></head><body><div hidden>Page not available</div></body></html>"#
        )

        let update = await makeFetcher().fetch(for: makeRecord(
            serviceType: .tiktok,
            url: original.absoluteString,
            contentContext: .playlist
        ))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertNil(update.fetchedBody)
        XCTAssertEqual(update.fetchedTitle, "In The Mix")
        XCTAssertEqual(update.canonicalID, "In-The-Mix-7516638364301265695")
        XCTAssertNotNil(update.badgeImageURL)
    }

    func testInstagramUsesPublicOEmbedMetadataWhenHTMLIsUnavailable() async {
        let oEmbed = URL(string: "https://mock.local/instagram-oembed")!
        let original = URL(string: "https://www.instagram.com/p/fA9uwTtkSN")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            """
            {
              "author_name": "diegoquinteiro",
              "author_url": "https://www.instagram.com/diegoquinteiro/",
              "title": "Wii Gato (Lipe Sleep)",
              "thumbnail_url": "https://images.example/instagram.jpg",
              "media_id": "12345"
            }
            """
        )
        MockURLProtocol.responses["https://www.instagram.com/diegoquinteiro/"] = .html(
            #"<html><head><meta property="og:image" content="https://images.example/instagram-profile.jpg"></head></html>"#
        )
        MockURLProtocol.responses[original.absoluteString] = .html("", statusCode: 404)

        let update = await makeFetcher(
            instagramPublicOEmbedEndpointBuilder: { _ in oEmbed },
            instagramCaptionedEmbedEndpointBuilder: { _ in nil }
        ).fetch(for: makeRecord(serviceType: .instagram, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "diegoquinteiro")
        XCTAssertEqual(update.fetchedBody, "Wii Gato (Lipe Sleep)")
        XCTAssertEqual(update.thumbnailURL, "https://images.example/instagram.jpg")
        XCTAssertEqual(update.badgeImageURL, "https://images.example/instagram-profile.jpg")
        XCTAssertEqual(update.canonicalID, "12345")
    }

    func testInstagramUsesCaptionedEmbedAvatarWhenPublicProfileImageIsUnavailable() async {
        let publicOEmbed = URL(string: "https://mock.local/instagram-public-oembed")!
        let captionedEmbed = URL(string: "https://mock.local/instagram-captioned")!
        let original = URL(string: "https://www.instagram.com/p/avatarTest")!
        MockURLProtocol.responses[publicOEmbed.absoluteString] = .json(
            """
            {
              "author_name": "avataruser",
              "author_url": "https://www.instagram.com/avataruser/",
              "title": "Caption from public oEmbed",
              "thumbnail_url": "https://images.example/instagram-post.jpg",
              "media_id": "67890"
            }
            """
        )
        MockURLProtocol.responses["https://www.instagram.com/avataruser/"] = .html(
            #"<html><head><meta property="og:image" content="https://images.example/instagram-public-profile.jpg"></head></html>"#
        )
        MockURLProtocol.responses[captionedEmbed.absoluteString] = .html(
            """
            <html>
              <body>
                <div class="Header">
                  <a class="Avatar" href="/avataruser/">
                    <span class="AvatarContainer"><img src="https://images.example/instagram-avatar.jpg"></span>
                  </a>
                </div>
                <div class="Caption"><a class="CaptionUsername">avataruser</a> Caption from embed</div>
                <img class="EmbeddedMediaImage" src="https://images.example/instagram-embed-post.jpg">
              </body>
            </html>
            """
        )
        MockURLProtocol.responses[original.absoluteString] = .html("", statusCode: 404)

        let update = await makeFetcher(
            instagramPublicOEmbedEndpointBuilder: { _ in publicOEmbed },
            instagramCaptionedEmbedEndpointBuilder: { _ in captionedEmbed }
        ).fetch(for: makeRecord(serviceType: .instagram, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.badgeImageURL, "https://images.example/instagram-avatar.jpg")
    }

    func testYouTubeUsesOEmbedWhenHTMLIsUnavailable() async {
        let oEmbed = URL(string: "https://mock.local/youtube-oembed")!
        let original = URL(string: "https://www.youtube.com/watch?v=dQw4w9WgXcQ")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            """
            {
              "title": "Rick Astley - Never Gonna Give You Up",
              "author_url": "https://www.youtube.com/@RickAstleyYT",
              "thumbnail_url": "https://images.example/youtube.jpg"
            }
            """
        )
        MockURLProtocol.responses["https://www.youtube.com/@RickAstleyYT"] = .html(
            #"<html><head><meta property="og:image" content="https://yt3.googleusercontent.com/profile.jpg"></head></html>"#
        )
        MockURLProtocol.responses[original.absoluteString] = .html("", statusCode: 404)

        let update = await makeFetcher(
            youtubeOEmbedEndpointBuilder: { _ in oEmbed }
        ).fetch(for: makeRecord(serviceType: .youtube, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "Rick Astley - Never Gonna Give You Up")
        XCTAssertEqual(update.thumbnailURL, "https://images.example/youtube.jpg")
        XCTAssertEqual(update.badgeImageURL, "https://yt3.googleusercontent.com/profile.jpg")
    }

    func testYouTubeEmbedUsesCanonicalWatchPageForDescription() async {
        let oEmbed = URL(string: "https://mock.local/youtube-embed-oembed")!
        let embed = URL(string: "https://www.youtube.com/embed/abc123")!
        let watch = URL(string: "https://www.youtube.com/watch?v=abc123")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            #"{"title":"Canonical video title","author_name":"Canonical author","thumbnail_url":"https://images.example/embed.jpg"}"#
        )
        MockURLProtocol.responses[embed.absoluteString] = .html(
            #"<html><head><title>Embed</title></head></html>"#
        )
        MockURLProtocol.responses[watch.absoluteString] = .html(
            #"<html><head><meta name="description" content="Canonical description"></head></html>"#
        )

        let update = await makeFetcher(
            youtubeOEmbedEndpointBuilder: { _ in oEmbed }
        ).fetch(for: makeRecord(
            serviceType: .youtube,
            url: embed.absoluteString,
            contentContext: .video
        ))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "Canonical video title")
        XCTAssertEqual(update.fetchedAuthorName, "Canonical author")
        XCTAssertEqual(update.fetchedBody, "Canonical description")
        XCTAssertEqual(update.thumbnailURL, "https://images.example/embed.jpg")
    }

    func testInstagramSoundDoesNotPersistLocalizedGenericLoginTitle() async {
        let original = URL(string: "https://www.instagram.com/reels/audio/28532324479739746")!
        MockURLProtocol.responses[original.absoluteString] = .html(
            #"<html><head><meta property="og:title" content="Instagramでnasajohnsonを聴いて、オリジナル音源を使ったリール動画を見よう"><meta name="description" content="Instagramをまたご利用いただきありがとうございます。ログインして、友達や家族のコンテンツをチェックしよう。"></head></html>"#
        )

        let update = await makeFetcher().fetch(for: makeRecord(
            serviceType: .instagram,
            url: original.absoluteString,
            contentContext: .sound
        ))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertNil(update.fetchedTitle)
        XCTAssertNil(update.fetchedBody)
        XCTAssertNil(update.description)
        XCTAssertEqual(update.badgeImageURL, "https://www.google.com/s2/favicons?domain=instagram.com&sz=128")
    }

    func testInstagramSoundUsesContentSpecificTitleTagWhenOgTitleIsLocalizedGeneric() async {
        let original = URL(string: "https://www.instagram.com/reels/audio/28532324479739746")!
        MockURLProtocol.responses[original.absoluteString] = .html(
            #"<html><head><title>nasajohnson | オリジナル音源(Instagram)</title><meta property="og:title" content="Instagramでnasajohnsonを聴いて、オリジナル音源を使ったリール動画を見よう"><meta name="description" content="Instagramでnasajohnsonを聴いて、オリジナル音源を使ったリール動画を見よう"></head></html>"#
        )

        let update = await makeFetcher().fetch(for: makeRecord(
            serviceType: .instagram,
            url: original.absoluteString,
            contentContext: .sound
        ))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "nasajohnson | オリジナル音源(Instagram)")
        XCTAssertNil(update.fetchedBody)
        XCTAssertNil(update.description)
    }

    func testInstagramSoundPrefersContentSpecificOEmbedTitleOverLocalizedGenericPageTitle() async {
        let original = URL(string: "https://www.instagram.com/reels/audio/28532324479739746")!
        let oEmbed = URL(string: "https://mock.local/instagram-sound-oembed")!
        let author = URL(string: "https://www.instagram.com/nasajohnson")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            """
            {
              "author_name": "nasajohnson | オリジナル音源(Instagram)",
              "author_url": "https://www.instagram.com/nasajohnson",
              "title": "Instagramでnasajohnsonを聴いて、オリジナル音源を使ったリール動画を見よう"
            }
            """
        )
        MockURLProtocol.responses[author.absoluteString] = .html(
            #"<html><head><meta property="og:image" content="https://images.example/nasajohnson.jpg"></head></html>"#
        )
        MockURLProtocol.responses[original.absoluteString] = .html(
            #"<html><head><meta property="og:title" content="Instagramでnasajohnsonを聴いて、オリジナル音源を使ったリール動画を見よう"><meta name="description" content="Instagramをまたご利用いただきありがとうございます。ログインして、友達や家族のコンテンツをチェックしよう。"></head></html>"#
        )

        let update = await makeFetcher(
            instagramPublicOEmbedEndpointBuilder: { _ in oEmbed }
        ).fetch(for: makeRecord(
            serviceType: .instagram,
            url: original.absoluteString,
            contentContext: .sound
        ))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.fetchedTitle, "nasajohnson | オリジナル音源(Instagram)")
        XCTAssertNil(update.fetchedAuthorName)
        XCTAssertNil(update.fetchedBody)
        XCTAssertEqual(update.badgeImageURL, "https://images.example/nasajohnson.jpg")
    }

    func testYouTubeUsesChannelBadgeFromAuthorPageWhenOgImageIsUnavailable() async {
        let oEmbed = URL(string: "https://mock.local/youtube-oembed-no-og")!
        let original = URL(string: "https://www.youtube.com/watch?v=authorBadge")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            """
            {
              "title": "Channel badge test",
              "author_url": "https://www.youtube.com/@BadgeChannel",
              "thumbnail_url": "https://images.example/youtube-thumb.jpg"
            }
            """
        )
        MockURLProtocol.responses["https://www.youtube.com/@BadgeChannel"] = .html(
            #"<html><script>{"avatar":{"thumbnails":[{"url":"https:\/\/yt3.ggpht.com\/badge-avatar=s176-c-k-c0x00ffffff-no-rj"}]}}</script></html>"#
        )
        MockURLProtocol.responses[original.absoluteString] = .html("", statusCode: 404)

        let update = await makeFetcher(
            youtubeOEmbedEndpointBuilder: { _ in oEmbed }
        ).fetch(for: makeRecord(serviceType: .youtube, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.badgeImageURL, "https://yt3.ggpht.com/badge-avatar=s176-c-k-c0x00ffffff-no-rj")
    }

    func testYouTubePrefersChannelBadgeOverAuthorPageOgImage() async {
        let oEmbed = URL(string: "https://mock.local/youtube-oembed-og-and-avatar")!
        let original = URL(string: "https://www.youtube.com/watch?v=authorAvatar")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            """
            {
              "title": "Author avatar test",
              "author_url": "https://www.youtube.com/@AvatarChannel",
              "thumbnail_url": "https://images.example/youtube-thumb.jpg"
            }
            """
        )
        MockURLProtocol.responses["https://www.youtube.com/@AvatarChannel"] = .html(
            #"""
            <html>
              <head><meta property="og:image" content="https://images.example/channel-og-image.jpg"></head>
              <script>{"avatar":{"thumbnails":[{"url":"https:\/\/yt3.googleusercontent.com\/real-avatar=s900-c-k-c0x00ffffff-no-rj"}]}}</script>
            </html>
            """#
        )
        MockURLProtocol.responses[original.absoluteString] = .html("", statusCode: 404)

        let update = await makeFetcher(
            youtubeOEmbedEndpointBuilder: { _ in oEmbed }
        ).fetch(for: makeRecord(serviceType: .youtube, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.badgeImageURL, "https://yt3.googleusercontent.com/real-avatar=s176-c-k-c0x00ffffff-no-rj")
    }

    func testYouTubeNormalizesLargeChannelBadgeForListIcon() async {
        let oEmbed = URL(string: "https://mock.local/youtube-oembed-large-badge")!
        let original = URL(string: "https://www.youtube.com/watch?v=largeBadge")!
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            """
            {
              "title": "Large badge test",
              "author_url": "https://www.youtube.com/@LargeBadgeChannel",
              "thumbnail_url": "https://images.example/youtube-thumb.jpg"
            }
            """
        )
        MockURLProtocol.responses["https://www.youtube.com/@LargeBadgeChannel"] = .html(
            #"<html><script>{"avatar":{"thumbnails":[{"url":"https:\/\/yt3.googleusercontent.com\/badge-avatar=s900-c-k-c0x00ffffff-no-rj\u0026v=1"}]}}</script></html>"#
        )
        MockURLProtocol.responses[original.absoluteString] = .html("", statusCode: 404)

        let update = await makeFetcher(
            youtubeOEmbedEndpointBuilder: { _ in oEmbed }
        ).fetch(for: makeRecord(serviceType: .youtube, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.badgeImageURL, "https://yt3.googleusercontent.com/badge-avatar=s176-c-k-c0x00ffffff-no-rj&v=1")
    }

    func testYouTubeChannelBadgeCanBeExtractedFromLargeChannelPage() async {
        let oEmbed = URL(string: "https://mock.local/youtube-oembed-large-page")!
        let original = URL(string: "https://www.youtube.com/watch?v=largePage")!
        let padding = String(repeating: "x", count: 560_000)
        MockURLProtocol.responses[oEmbed.absoluteString] = .json(
            """
            {
              "title": "Large page test",
              "author_url": "https://www.youtube.com/@LargePageChannel",
              "thumbnail_url": "https://images.example/youtube-thumb.jpg"
            }
            """
        )
        MockURLProtocol.responses["https://www.youtube.com/@LargePageChannel"] = .html(
            #"<html><script>{"avatar":{"thumbnails":[{"url":"https:\/\/yt3.googleusercontent.com\/large-page-avatar=s900-c-k-c0x00ffffff-no-rj"}]}}</script>"#
            + padding
            + "</html>"
        )
        MockURLProtocol.responses[original.absoluteString] = .html("", statusCode: 404)

        let update = await makeFetcher(
            youtubeOEmbedEndpointBuilder: { _ in oEmbed }
        ).fetch(for: makeRecord(serviceType: .youtube, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.badgeImageURL, "https://yt3.googleusercontent.com/large-page-avatar=s176-c-k-c0x00ffffff-no-rj")
    }

    func testWebUsesAppleTouchIconForBadge() async {
        let original = URL(string: "https://example.com/article")!
        MockURLProtocol.responses[original.absoluteString] = .html(
            #"""
            <html>
              <head>
                <title>Example Article</title>
                <link rel="icon" href="/favicon.ico">
                <link rel="apple-touch-icon" href="/apple-touch-icon.png">
              </head>
              <body><p>Article text that is long enough to be treated as useful metadata.</p></body>
            </html>
            """#
        )

        let update = await makeFetcher()
            .fetch(for: makeRecord(serviceType: .web, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.badgeImageURL, "https://example.com/apple-touch-icon.png")
    }

    func testWebUsesRenderableFaviconServiceFallback() async {
        let original = URL(string: "https://plain.example/article")!
        MockURLProtocol.responses[original.absoluteString] = .html(
            """
            <html>
              <head><title>Plain Example</title></head>
              <body><p>Article text that is long enough to be treated as useful metadata.</p></body>
            </html>
            """
        )

        let update = await makeFetcher()
            .fetch(for: makeRecord(serviceType: .web, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.badgeImageURL, "https://www.google.com/s2/favicons?domain=plain.example&sz=128")
    }

    func testWebSkipsIcoFaviconForRenderableFallback() async {
        let original = URL(string: "https://ico.example/article")!
        MockURLProtocol.responses[original.absoluteString] = .html(
            #"""
            <html>
              <head>
                <title>ICO Example</title>
                <link rel="icon" href="/favicon.ico">
              </head>
              <body><p>Article text that is long enough to be treated as useful metadata.</p></body>
            </html>
            """#
        )

        let update = await makeFetcher()
            .fetch(for: makeRecord(serviceType: .web, url: original.absoluteString))

        XCTAssertEqual(update.metadataState, .ready)
        XCTAssertEqual(update.badgeImageURL, "https://www.google.com/s2/favicons?domain=ico.example&sz=128")
    }

    private func makeFetcher(
        youtubeOEmbedEndpointBuilder: @escaping @Sendable (URL) -> URL? = { _ in nil },
        tiktokOEmbedEndpointBuilder: @escaping @Sendable (URL) -> URL? = { _ in nil },
        tiktokFallbackEndpointBuilder: @escaping @Sendable (URL) -> URL? = { _ in nil },
        xOEmbedEndpointBuilder: @escaping @Sendable (URL) -> URL? = { _ in nil },
        xSyndicationEndpointBuilder: @escaping @Sendable (String) -> URL? = { _ in nil },
        xGuestActivationEndpoint: URL? = nil,
        xArticleGraphQLEndpointBuilder: @escaping @Sendable (String) -> URL? = { _ in nil },
        xSpaceMetadataEndpointBuilder: @escaping @Sendable (String) -> URL? = { _ in nil },
        xPublicBearerToken: String? = "public-test-token",
        instagramPublicOEmbedEndpointBuilder: @escaping @Sendable (URL) -> URL? = { _ in nil },
        instagramCaptionedEmbedEndpointBuilder: @escaping @Sendable (URL) -> URL? = { _ in nil }
    ) -> MetadataFetcher {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        return MetadataFetcher(
            session: URLSession(configuration: configuration),
            youtubeOEmbedEndpointBuilder: youtubeOEmbedEndpointBuilder,
            tiktokOEmbedEndpointBuilder: tiktokOEmbedEndpointBuilder,
            tiktokFallbackEndpointBuilder: tiktokFallbackEndpointBuilder,
            xOEmbedEndpointBuilder: xOEmbedEndpointBuilder,
            xSyndicationEndpointBuilder: xSyndicationEndpointBuilder,
            xGuestActivationEndpoint: xGuestActivationEndpoint,
            xArticleGraphQLEndpointBuilder: xArticleGraphQLEndpointBuilder,
            xSpaceMetadataEndpointBuilder: xSpaceMetadataEndpointBuilder,
            xPublicBearerToken: xPublicBearerToken,
            instagramPublicOEmbedEndpointBuilder: instagramPublicOEmbedEndpointBuilder,
            instagramCaptionedEmbedEndpointBuilder: instagramCaptionedEmbedEndpointBuilder
        )
    }

    private func makeRecord(
        serviceType: ServiceType,
        url: String,
        contentContext: ContentContext = .standard
    ) -> URLRecord {
        let host = URL(string: url)?.host ?? "example.com"
        return URLRecord(
            id: 1,
            originalURL: url,
            normalizedURL: url,
            displayURL: url,
            openURL: url,
            normalizedHost: host,
            rawSourceHost: host,
            collectionID: 1,
            serviceType: serviceType,
            contentContext: contentContext,
            userTitle: nil,
            fetchedTitle: nil,
            fetchedBody: nil,
            fetchedBodyKind: nil,
            bodySummary: nil,
            description: nil,
            memo: "",
            thumbnailURL: nil,
            badgeImageURL: nil,
            canonicalID: nil,
            metadataState: .pending,
            metadataError: nil,
            metadataRequestedAt: nil,
            metadataFetchedAt: nil,
            recordState: .active,
            localProvenanceCount: 1,
            sharedReferenceCount: 0,
            createdAt: .distantPast,
            updatedAt: .distantPast,
            archivedAt: nil,
            pendingDeletionUntil: nil
        )
    }
}

private final class MockURLProtocol: URLProtocol, @unchecked Sendable {
    struct Response: Sendable {
        let statusCode: Int
        let contentType: String
        let body: Data

        static func json(_ body: String, statusCode: Int = 200) -> Response {
            Response(statusCode: statusCode, contentType: "application/json", body: Data(body.utf8))
        }

        static func html(_ body: String, statusCode: Int = 200) -> Response {
            Response(statusCode: statusCode, contentType: "text/html", body: Data(body.utf8))
        }
    }

    nonisolated(unsafe) static var responses: [String: Response] = [:]

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let url = request.url,
              let response = Self.responses[url.absoluteString] else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }

        let httpResponse = HTTPURLResponse(
            url: url,
            statusCode: response.statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": response.contentType]
        )!
        client?.urlProtocol(self, didReceive: httpResponse, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: response.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
