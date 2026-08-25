import importlib.util
import http.client
import json
import os
import pathlib
import threading
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "media_resolver_backend.py"


spec = importlib.util.spec_from_file_location("media_resolver_backend", MODULE_PATH)
media_resolver_backend = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(media_resolver_backend)


class SupportedUrlTest(unittest.TestCase):
    def test_x_links_are_not_media_resolver_targets(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")

        result = resolver.resolve("https://x.com/i/status/2061285320088523185", None)

        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "UNSUPPORTED_URL")


class CookieOptionsTest(unittest.TestCase):
    def test_provider_specific_cookie_file_is_copied_to_runtime_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = pathlib.Path(tmp) / "instagram-cookies.txt"
            source.write_text("# Netscape HTTP Cookie File\n", encoding="utf-8")
            runtime = pathlib.Path(tmp) / "runtime"
            with mock.patch.dict(
                os.environ,
                {
                    "MEDIA_RESOLVER_INSTAGRAM_COOKIES_FILE": str(source),
                    "MEDIA_RESOLVER_COOKIES_RUNTIME_DIR": str(runtime),
                },
                clear=True,
            ):
                options = media_resolver_backend._yt_dlp_cookie_options("instagram")

            copied = pathlib.Path(options["cookiefile"])
            self.assertTrue(copied.is_file())
            self.assertEqual(copied.parent, runtime)
            self.assertEqual(copied.read_text(encoding="utf-8"), source.read_text(encoding="utf-8"))

    def test_provider_specific_cookie_file_replaces_stale_runtime_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = pathlib.Path(tmp) / "youtube-cookies.txt"
            source.write_text("# Netscape HTTP Cookie File\nold\n", encoding="utf-8")
            runtime = pathlib.Path(tmp) / "runtime"
            with mock.patch.dict(
                os.environ,
                {
                    "MEDIA_RESOLVER_YOUTUBE_COOKIES_FILE": str(source),
                    "MEDIA_RESOLVER_COOKIES_RUNTIME_DIR": str(runtime),
                },
                clear=True,
            ):
                first = pathlib.Path(media_resolver_backend._yt_dlp_cookie_options("youtube")["cookiefile"])
                source.write_text("# Netscape HTTP Cookie File\nnew\n", encoding="utf-8")
                second = pathlib.Path(media_resolver_backend._yt_dlp_cookie_options("youtube")["cookiefile"])

            self.assertEqual(first, second)
            self.assertEqual(second.read_text(encoding="utf-8"), source.read_text(encoding="utf-8"))

    def test_shared_cookie_file_is_used_for_youtube_when_provider_specific_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = pathlib.Path(tmp) / "shared-cookies.txt"
            source.write_text("# Netscape HTTP Cookie File\n", encoding="utf-8")
            runtime = pathlib.Path(tmp) / "runtime"
            with mock.patch.dict(
                os.environ,
                {
                    "MEDIA_RESOLVER_YTDLP_COOKIES_FILE": str(source),
                    "MEDIA_RESOLVER_COOKIES_RUNTIME_DIR": str(runtime),
                },
                clear=True,
            ):
                options = media_resolver_backend._yt_dlp_cookie_options("youtube")

            self.assertTrue(pathlib.Path(options["cookiefile"]).is_file())

    def test_cookie_cli_args_include_runtime_cookie_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = pathlib.Path(tmp) / "youtube-cookies.txt"
            source.write_text("# Netscape HTTP Cookie File\n", encoding="utf-8")
            runtime = pathlib.Path(tmp) / "runtime"
            with mock.patch.dict(
                os.environ,
                {
                    "MEDIA_RESOLVER_YOUTUBE_COOKIES_FILE": str(source),
                    "MEDIA_RESOLVER_COOKIES_RUNTIME_DIR": str(runtime),
                },
                clear=True,
            ):
                args = media_resolver_backend._yt_dlp_cookie_cli_args("youtube")

            self.assertEqual(args[0], "--cookies")
            self.assertTrue(pathlib.Path(args[1]).is_file())

    def test_missing_cookie_file_is_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(
                os.environ,
                {"MEDIA_RESOLVER_YOUTUBE_COOKIES_FILE": str(pathlib.Path(tmp) / "missing.txt")},
                clear=True,
            ):
                self.assertEqual(media_resolver_backend._yt_dlp_cookie_options("youtube"), {})

    def test_cookie_content_env_is_written_to_runtime_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            content = "# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tTRUE\t0\tname\tvalue\n"
            with mock.patch.dict(
                os.environ,
                {
                    "YOUTUBE_YTDLP_COOKIES": content,
                    "MEDIA_RESOLVER_COOKIES_RUNTIME_DIR": str(pathlib.Path(tmp) / "runtime"),
                },
                clear=True,
            ):
                options = media_resolver_backend._yt_dlp_cookie_options("youtube")

            copied = pathlib.Path(options["cookiefile"])
            self.assertTrue(copied.is_file())
            self.assertEqual(copied.read_text(encoding="utf-8"), content)

    def test_cookie_status_reports_safe_diagnostics_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = pathlib.Path(tmp) / "youtube-cookies.txt"
            source.write_text(
                "# Netscape HTTP Cookie File\n"
                ".youtube.com\tTRUE\t/\tTRUE\t0\tSID\tsecret-value\n"
                ".google.com\tTRUE\t/\tTRUE\t0\tLOGIN_INFO\tsecret-value\n",
                encoding="utf-8",
            )
            with mock.patch.dict(
                os.environ,
                {"MEDIA_RESOLVER_YOUTUBE_COOKIES_FILE": str(source)},
                clear=True,
            ):
                status = media_resolver_backend._yt_dlp_cookie_status("youtube")

            self.assertTrue(status["fileConfigured"])
            self.assertTrue(status["fileReadable"])
            self.assertEqual(status["lineCount"], 2)
            self.assertEqual(status["domainCount"], 2)
            self.assertEqual(status["domains"], ["google.com", "youtube.com"])
            self.assertNotIn("secret-value", repr(status))


class FormatSelectionTest(unittest.TestCase):
    def test_youtube_format_prefers_low_resolution_combined_video(self):
        selected = media_resolver_backend._yt_dlp_format("youtube")

        self.assertIn("height<=360", selected)
        self.assertIn("ext=mp4", selected)
        self.assertIn("/18/", selected)

    def test_youtube_extractor_args_combine_po_token_and_client(self):
        with mock.patch.dict(
            os.environ,
            {"MEDIA_RESOLVER_YOUTUBE_PO_TOKEN": "token-value"},
            clear=True,
        ):
            args = media_resolver_backend._youtube_extractor_args_cli("ios")

        self.assertEqual(args, ["--extractor-args", "youtube:po_token=token-value;player_client=ios"])

    def test_innertube_client_version_accepts_only_numeric_dotted_override(self):
        with mock.patch.dict(
            os.environ,
            {"MEDIA_RESOLVER_YOUTUBE_INNERTUBE_CLIENT_VERSION": "21.4.7"},
            clear=True,
        ):
            self.assertEqual(media_resolver_backend._youtube_innertube_client_version(), "21.4.7")
        with mock.patch.dict(
            os.environ,
            {"MEDIA_RESOLVER_YOUTUBE_INNERTUBE_CLIENT_VERSION": "bad\r\nX-Test: injected"},
            clear=True,
        ):
            self.assertEqual(
                media_resolver_backend._youtube_innertube_client_version(),
                media_resolver_backend.YOUTUBE_INNERTUBE_CLIENT_VERSION,
            )

    def test_innertube_ios_client_version_accepts_only_numeric_dotted_override(self):
        with mock.patch.dict(
            os.environ,
            {"MEDIA_RESOLVER_YOUTUBE_INNERTUBE_IOS_CLIENT_VERSION": "20.11.5"},
            clear=True,
        ):
            self.assertEqual(media_resolver_backend._youtube_innertube_ios_client_version(), "20.11.5")
        with mock.patch.dict(
            os.environ,
            {"MEDIA_RESOLVER_YOUTUBE_INNERTUBE_IOS_CLIENT_VERSION": "20.11.5\r\nX-Test: injected"},
            clear=True,
        ):
            self.assertEqual(
                media_resolver_backend._youtube_innertube_ios_client_version(),
                media_resolver_backend.YOUTUBE_INNERTUBE_IOS_CLIENT_VERSION,
            )

    def test_proxy_dns_guard_accepts_only_global_addresses(self):
        public_result = [(2, 1, 6, "", ("8.8.8.8", 443))]
        private_result = [(2, 1, 6, "", ("169.254.169.254", 443))]
        with mock.patch.object(media_resolver_backend.socket, "getaddrinfo", return_value=public_result):
            self.assertTrue(media_resolver_backend._url_resolves_to_public_ip("https://media.example.test/file"))
        with mock.patch.object(media_resolver_backend.socket, "getaddrinfo", return_value=private_result):
            self.assertFalse(media_resolver_backend._url_resolves_to_public_ip("https://media.example.test/file"))

    def test_innertube_diagnostic_contains_only_bounded_status(self):
        media_resolver_backend._record_youtube_innertube_diagnostic(None, "failure " + ("x" * 200))
        diagnostic = media_resolver_backend.YOUTUBE_INNERTUBE_LAST_DIAGNOSTIC
        self.assertEqual(diagnostic["status"], "failed")
        self.assertLessEqual(len(diagnostic["error"]), 80)
        self.assertIsInstance(diagnostic["at"], int)
        media_resolver_backend._record_youtube_innertube_diagnostic({"ok": True}, None)
        self.assertEqual(diagnostic["status"], "success")
        self.assertIsNone(diagnostic["error"])


class YouTubeDelegateTest(unittest.TestCase):
    def test_youtube_resolve_uses_delegate_when_configured(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://render.example.test")
        response = mock.Mock()
        response.__enter__ = mock.Mock(return_value=response)
        response.__exit__ = mock.Mock(return_value=None)
        response.read.return_value = (
            b'{"ok": true, "provider": "youtube", "assets": '
            b'[{"mediaType": "VIDEO", "downloadUrl": "https://railway.example.test/proxy/abc"}]}'
        )

        with mock.patch.dict(
            os.environ,
            {"MEDIA_RESOLVER_YOUTUBE_DELEGATE_URL": "https://railway.example.test/"},
            clear=True,
        ), mock.patch.object(media_resolver_backend.urllib.request, "urlopen", return_value=response) as urlopen:
            result = resolver.resolve("https://youtu.be/abc123", "youtube")

        self.assertTrue(result["ok"])
        self.assertEqual(result["provider"], "youtube")
        self.assertEqual(result["assets"][0]["mediaType"], "VIDEO")
        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, "https://railway.example.test/resolve")
        self.assertEqual(
            request.get_header("X-rinbam-resolver-hop"),
            media_resolver_backend.YOUTUBE_DELEGATE_HEADER_VALUE,
        )

    def test_youtube_delegate_failure_falls_back_to_primary_resolver(self):
        resolver = media_resolver_backend.MediaResolver(
            pathlib.Path(tempfile.mkdtemp()),
            "https://render.example.test",
        )
        primary_result = {
            "ok": True,
            "provider": "youtube",
            "assets": [
                {
                    "mediaType": "VIDEO",
                    "downloadUrl": "https://render.example.test/proxy/primary",
                },
            ],
        }

        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(
            resolver,
            "_resolve_youtube_delegate",
            return_value={
                "ok": False,
                "provider": "youtube",
                "error": "MEDIA_NOT_CREATED",
                "message": "sensitive upstream detail",
                "assets": [],
            },
        ), mock.patch.object(
            media_resolver_backend,
            "_safe_log",
        ) as safe_log, mock.patch.object(
            media_resolver_backend,
            "_load_tools",
            return_value=(mock.Mock(), None),
        ), mock.patch.object(
            resolver,
            "_resolve_youtube_innertube_asset",
            return_value=(None, None),
        ), mock.patch.object(
            resolver,
            "_resolve_youtube_direct_asset",
            return_value=(primary_result, None),
        ) as primary:
            result = resolver.resolve("https://youtu.be/abc123", "youtube")

        primary.assert_called_once()
        safe_log.assert_called_once()
        log_message = safe_log.call_args.args[0]
        self.assertIn("error=MEDIA_NOT_CREATED", log_message)
        self.assertNotIn("sensitive upstream detail", log_message)
        self.assertEqual(result, primary_result)

    def test_youtube_delegate_failure_uses_innertube_before_ytdlp(self):
        resolver = media_resolver_backend.MediaResolver(
            pathlib.Path(tempfile.mkdtemp()),
            "https://render.example.test",
        )
        expected = {"ok": True, "provider": "youtube", "assets": [{"providerAssetId": "innertube"}]}
        with (
            mock.patch.object(resolver, "_resolve_youtube_delegate", return_value={"ok": False, "error": "UPSTREAM"}),
            mock.patch.object(resolver, "_resolve_youtube_innertube_asset", return_value=(expected, None)) as innertube,
            mock.patch.object(media_resolver_backend, "_load_tools") as load_tools,
            mock.patch.dict(os.environ, {}, clear=True),
        ):
            result = resolver.resolve("https://youtu.be/jNQXAC9IVRw", "youtube")

        self.assertEqual(result, expected)
        innertube.assert_called_once()
        load_tools.assert_not_called()

    def test_youtube_delegate_is_skipped_when_loop_guard_is_disabled(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://render.example.test")
        with mock.patch.dict(
            os.environ,
            {"MEDIA_RESOLVER_YOUTUBE_DELEGATE_URL": "https://railway.example.test"},
            clear=True,
        ), mock.patch.object(media_resolver_backend, "_load_tools", return_value=(mock.Mock(), None)), mock.patch.object(
            resolver, "_resolve_youtube_innertube_asset", return_value=(None, None)
        ), mock.patch.object(
            resolver, "_resolve_youtube_direct_asset", return_value=(None, "no formats")
        ), mock.patch.object(
            media_resolver_backend.urllib.request, "urlopen"
        ) as urlopen:
            result = resolver.resolve("https://youtu.be/abc123", "youtube", allow_delegate=False)

        urlopen.assert_not_called()
        self.assertFalse(result["ok"])
        self.assertEqual(result["provider"], "youtube")

    def test_youtube_delegate_is_skipped_when_host_matches_current_resolver(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://render.example.test")
        with mock.patch.dict(
            os.environ,
            {"MEDIA_RESOLVER_YOUTUBE_DELEGATE_URL": "https://render.example.test"},
            clear=True,
        ), mock.patch.object(media_resolver_backend, "_load_tools", return_value=(mock.Mock(), None)), mock.patch.object(
            resolver, "_resolve_youtube_innertube_asset", return_value=(None, None)
        ), mock.patch.object(
            resolver, "_resolve_youtube_direct_asset", return_value=(None, "no formats")
        ), mock.patch.object(
            media_resolver_backend.urllib.request, "urlopen"
        ) as urlopen:
            result = resolver.resolve("https://youtu.be/abc123", "youtube")

        urlopen.assert_not_called()
        self.assertFalse(result["ok"])
        self.assertEqual(result["provider"], "youtube")


class YouTubeDirectResultTest(unittest.TestCase):
    def setUp(self):
        media_resolver_backend.DIRECT_MEDIA_PROXIES.clear()

    def test_cli_direct_resolver_does_not_pin_format_selection(self):
        calls = []

        def fake_run(command, **kwargs):
            calls.append(command)
            return mock.Mock(
                stdout=(
                    '{"id":"abc123","url":"https://video.example.test/audio.m4a","ext":"m4a","vcodec":"none","acodec":"mp4a"}\n'
                    '{"id":"abc123","url":"https://video.example.test/media.mp4","ext":"mp4","vcodec":"avc1","acodec":"mp4a"}\n'
                ),
                stderr="",
            )

        with mock.patch.object(media_resolver_backend.subprocess, "run", side_effect=fake_run):
            info, error = media_resolver_backend.MediaResolver._resolve_youtube_direct_info_cli(
                "https://youtu.be/abc123"
            )

        self.assertIsNone(error)
        self.assertEqual(info["id"], "abc123")
        self.assertEqual(len(info["formats"]), 2)
        self.assertEqual(len(calls), 1)
        format_index = calls[0].index("--format")
        self.assertEqual(calls[0][format_index + 1], "all")

    def test_innertube_fallback_returns_only_combined_mp4_through_proxy(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b'''{
          "playabilityStatus":{"status":"OK"},
          "videoDetails":{"videoId":"jNQXAC9IVRw","title":"Me at the zoo","author":"jawed","lengthSeconds":"19","thumbnail":{"thumbnails":[{"url":"https://img.example.test/low.jpg"},{"url":"https://img.example.test/high.jpg"}]}},
          "streamingData":{"formats":[
            {"itag":18,"url":"https://rr1---sn.example.googlevideo.com/videoplayback","mimeType":"video/mp4; codecs=\\"avc1.42001E, mp4a.40.2\\"","width":320,"height":240,"bitrate":240000},
            {"itag":22,"signatureCipher":"cipher-only","mimeType":"video/mp4; codecs=\\"avc1.64001F, mp4a.40.2\\"","width":1280,"height":720}
          ]}
        }'''

        with mock.patch.object(media_resolver_backend.urllib.request, "urlopen", return_value=response) as urlopen:
            result, error = resolver._resolve_youtube_innertube_asset(
                "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                "stable",
            )

        self.assertIsNone(error)
        self.assertTrue(result["ok"])
        asset = result["assets"][0]
        self.assertEqual(asset["providerAssetId"], "jNQXAC9IVRw:direct:18")
        self.assertEqual(asset["title"], "Me at the zoo")
        self.assertEqual(asset["authorName"], "jawed")
        self.assertEqual(asset["thumbnailUrl"], "https://img.example.test/high.jpg")
        self.assertEqual(asset["durationMs"], 19000)
        self.assertTrue(asset["downloadUrl"].startswith("https://example.test/proxy/"))
        proxy_item = next(iter(media_resolver_backend.DIRECT_MEDIA_PROXIES.values()))
        self.assertEqual(proxy_item["url"], "https://rr1---sn.example.googlevideo.com/videoplayback")
        self.assertNotIn("Cookie", proxy_item["headers"])
        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, media_resolver_backend.YOUTUBE_INNERTUBE_ENDPOINT)
        self.assertNotIn(b"https://www.youtube.com/watch", request.data)

    def test_innertube_fallback_fails_closed_without_progressive_mp4(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b'''{
          "playabilityStatus":{"status":"OK"},
          "videoDetails":{"videoId":"jNQXAC9IVRw"},
          "streamingData":{"adaptiveFormats":[
            {"itag":137,"url":"https://video.example.test/video-only.mp4","mimeType":"video/mp4; codecs=\\"avc1.640028\\""}
          ]}
        }'''

        with mock.patch.object(media_resolver_backend.urllib.request, "urlopen", return_value=response):
            result, error = resolver._resolve_youtube_innertube_asset(
                "https://youtu.be/jNQXAC9IVRw",
                "stable",
            )

        self.assertIsNone(result)
        self.assertEqual(error, "no progressive formats")
        self.assertEqual(media_resolver_backend.DIRECT_MEDIA_PROXIES, {})

    def test_innertube_fallback_rejects_non_googlevideo_media_url(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b'''{
          "playabilityStatus":{"status":"OK"},
          "videoDetails":{"videoId":"jNQXAC9IVRw"},
          "streamingData":{"formats":[
            {"itag":18,"url":"https://internal.example.test/media.mp4","mimeType":"video/mp4; codecs=\\"avc1.42001E, mp4a.40.2\\""}
          ]}
        }'''

        with mock.patch.object(media_resolver_backend.urllib.request, "urlopen", return_value=response):
            result, error = resolver._resolve_youtube_innertube_asset(
                "https://youtu.be/jNQXAC9IVRw",
                "stable",
            )

        self.assertIsNone(result)
        self.assertEqual(error, "no combined mp4 format")
        self.assertEqual(media_resolver_backend.DIRECT_MEDIA_PROXIES, {})

    def test_innertube_fallback_fails_closed_for_unplayable_video(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b'{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}'

        with mock.patch.object(media_resolver_backend.urllib.request, "urlopen", return_value=response):
            result, error = resolver._resolve_youtube_innertube_asset(
                "https://youtube.com/shorts/jNQXAC9IVRw",
                "stable",
            )

        self.assertIsNone(result)
        self.assertEqual(error, "playability=LOGIN_REQUIRED")
        self.assertEqual(media_resolver_backend.DIRECT_MEDIA_PROXIES, {})

    def test_innertube_fallback_identifies_cipher_only_format_without_decoding(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b'''{
          "playabilityStatus":{"status":"OK"},
          "videoDetails":{"videoId":"jNQXAC9IVRw"},
          "streamingData":{"formats":[
            {"itag":18,"signatureCipher":"url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback&s=encrypted&sp=sig","mimeType":"video/mp4; codecs=\\"avc1.42001E, mp4a.40.2\\""}
          ]}
        }'''

        with mock.patch.object(media_resolver_backend.urllib.request, "urlopen", return_value=response):
            result, error = resolver._resolve_youtube_innertube_asset(
                "https://youtu.be/jNQXAC9IVRw",
                "stable",
            )

        self.assertIsNone(result)
        self.assertEqual(error, "signature required")
        self.assertEqual(media_resolver_backend.DIRECT_MEDIA_PROXIES, {})

    def test_innertube_ios_download_merges_bounded_video_and_best_audio(self):
        cache_dir = pathlib.Path(tempfile.mkdtemp())
        resolver = media_resolver_backend.MediaResolver(cache_dir, "https://example.test")
        player = {
            "videoDetails": {
                "videoId": "jNQXAC9IVRw",
                "title": "Me at the zoo",
                "author": "jawed",
                "lengthSeconds": "19",
            },
            "streamingData": {
                "adaptiveFormats": [
                    {
                        "itag": 133,
                        "url": "https://video.example.googlevideo.com/240",
                        "mimeType": 'video/mp4; codecs="avc1.4d400d"',
                        "height": 240,
                        "bitrate": 200000,
                    },
                    {
                        "itag": 134,
                        "url": "https://video.example.googlevideo.com/360",
                        "mimeType": 'video/mp4; codecs="avc1.4d401e"',
                        "height": 360,
                        "bitrate": 400000,
                    },
                    {
                        "itag": 137,
                        "url": "https://video.example.googlevideo.com/1080",
                        "mimeType": 'video/mp4; codecs="avc1.640028"',
                        "height": 1080,
                        "bitrate": 2000000,
                    },
                    {
                        "itag": 139,
                        "url": "https://audio.example.googlevideo.com/low",
                        "mimeType": 'audio/mp4; codecs="mp4a.40.5"',
                        "bitrate": 48000,
                    },
                    {
                        "itag": 140,
                        "url": "https://audio.example.googlevideo.com/high",
                        "mimeType": 'audio/mp4; codecs="mp4a.40.2"',
                        "bitrate": 128000,
                    },
                ]
            },
        }

        def fake_run(command, **kwargs):
            pathlib.Path(command[-1]).write_bytes(b"merged-media")
            return mock.Mock(stdout="", stderr="")

        with (
            mock.patch.object(media_resolver_backend, "_request_youtube_innertube_player", return_value=(player, None)),
            mock.patch.object(media_resolver_backend, "_url_resolves_to_public_ip", return_value=True) as dns_check,
            mock.patch.object(media_resolver_backend.subprocess, "run", side_effect=fake_run) as run,
        ):
            info, path, error = resolver._resolve_youtube_innertube_ios_download(
                "https://youtu.be/jNQXAC9IVRw",
                "stable",
                "/opt/ffmpeg",
            )

        self.assertIsNone(error)
        self.assertEqual(path, cache_dir / "stable.innertube-ios.mp4")
        self.assertEqual(path.read_bytes(), b"merged-media")
        self.assertEqual(info["height"], 360)
        self.assertEqual(info["title"], "Me at the zoo")
        command = run.call_args.args[0]
        self.assertIn("https://video.example.googlevideo.com/360", command)
        self.assertNotIn("https://video.example.googlevideo.com/1080", command)
        self.assertIn("https://audio.example.googlevideo.com/high", command)
        self.assertRegex(pathlib.Path(command[-1]).name, r"^stable\.innertube-[0-9a-f]{16}-tmp\.mp4$")
        self.assertEqual(dns_check.call_count, 2)

    def test_innertube_ios_download_rejects_non_public_dns_before_ffmpeg(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        player = {
            "videoDetails": {"videoId": "jNQXAC9IVRw"},
            "streamingData": {
                "adaptiveFormats": [
                    {
                        "url": "https://video.example.googlevideo.com/media",
                        "mimeType": 'video/mp4; codecs="avc1.4d401e"',
                        "height": 360,
                    },
                    {
                        "url": "https://audio.example.googlevideo.com/media",
                        "mimeType": 'audio/mp4; codecs="mp4a.40.2"',
                    },
                ]
            },
        }
        with (
            mock.patch.object(media_resolver_backend, "_request_youtube_innertube_player", return_value=(player, None)),
            mock.patch.object(media_resolver_backend, "_url_resolves_to_public_ip", side_effect=[True, False]),
            mock.patch.object(media_resolver_backend.subprocess, "run") as run,
        ):
            info, path, error = resolver._resolve_youtube_innertube_ios_download(
                "https://youtu.be/jNQXAC9IVRw",
                "stable",
                "/opt/ffmpeg",
            )

        self.assertEqual(info, {})
        self.assertIsNone(path)
        self.assertEqual(error, "adaptive media DNS rejected")
        run.assert_not_called()

    def test_innertube_ios_download_fails_closed_for_cipher_only_formats(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        player = {
            "videoDetails": {"videoId": "jNQXAC9IVRw"},
            "streamingData": {
                "adaptiveFormats": [
                    {
                        "signatureCipher": "encrypted",
                        "mimeType": 'video/mp4; codecs="avc1.4d401e"',
                    },
                    {
                        "signatureCipher": "encrypted",
                        "mimeType": 'audio/mp4; codecs="mp4a.40.2"',
                    },
                ]
            },
        }
        with mock.patch.object(
            media_resolver_backend,
            "_request_youtube_innertube_player",
            return_value=(player, None),
        ):
            info, path, error = resolver._resolve_youtube_innertube_ios_download(
                "https://youtu.be/jNQXAC9IVRw",
                "stable",
                "/opt/ffmpeg",
            )

        self.assertEqual(info, {})
        self.assertIsNone(path)
        self.assertEqual(error, "signature required")

    def test_youtube_resolve_prefers_innertube_before_loading_ytdlp(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        expected = {"ok": True, "provider": "youtube", "assets": [{"providerAssetId": "asset"}]}
        with (
            mock.patch.object(resolver, "_resolve_youtube_innertube_asset", return_value=(expected, None)) as innertube,
            mock.patch.object(media_resolver_backend, "_load_tools") as load_tools,
            mock.patch.dict(os.environ, {}, clear=True),
        ):
            result = resolver.resolve("https://youtu.be/jNQXAC9IVRw", "youtube", allow_delegate=False)

        self.assertEqual(result, expected)
        innertube.assert_called_once()
        load_tools.assert_not_called()

    def test_youtube_resolve_prefers_innertube_when_server_download_is_enabled(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        expected = {"ok": True, "provider": "youtube", "assets": [{"providerAssetId": "asset"}]}
        with (
            mock.patch.object(resolver, "_resolve_youtube_innertube_asset", return_value=(expected, None)) as innertube,
            mock.patch.object(media_resolver_backend, "_load_tools") as load_tools,
            mock.patch.dict(
                os.environ,
                {"MEDIA_RESOLVER_YOUTUBE_SERVER_DOWNLOAD_ENABLED": "true"},
                clear=True,
            ),
        ):
            result = resolver.resolve("https://youtu.be/jNQXAC9IVRw", "youtube", allow_delegate=False)

        self.assertEqual(result, expected)
        innertube.assert_called_once()
        load_tools.assert_not_called()

    def test_top_level_url_is_returned_as_preferred_asset(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        result = resolver._youtube_direct_result(
            {
                "id": "abc123",
                "url": "https://video.example.test/media.mp4",
                "ext": "mp4",
                "format_id": "18",
                "vcodec": "avc1",
                "acodec": "mp4a",
                "title": "Video title",
                "duration": 12,
                "http_headers": {
                    "User-Agent": "yt-dlp-user-agent",
                    "Referer": "https://www.youtube.com/",
                    "Cookie": "secret=value",
                },
            },
            "https://youtu.be/abc123",
            "stable",
        )

        self.assertIsNotNone(result)
        asset = result["assets"][0]
        self.assertTrue(asset["downloadUrl"].startswith("https://example.test/proxy/"))
        self.assertEqual(len(media_resolver_backend.DIRECT_MEDIA_PROXIES), 1)
        proxy_item = next(iter(media_resolver_backend.DIRECT_MEDIA_PROXIES.values()))
        self.assertEqual(proxy_item["headers"]["User-Agent"], "yt-dlp-user-agent")
        self.assertEqual(proxy_item["headers"]["Referer"], "https://www.youtube.com/")
        self.assertNotIn("Cookie", proxy_item["headers"])
        self.assertEqual(asset["mediaType"], "VIDEO")
        self.assertEqual(asset["providerAssetId"], "abc123:direct:18")

    def test_video_only_top_level_url_uses_combined_format_candidate(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        result = resolver._youtube_direct_result(
            {
                "id": "abc123",
                "url": "https://video.example.test/video-only.mp4",
                "ext": "mp4",
                "format_id": "401",
                "vcodec": "av01",
                "acodec": "none",
                "formats": [
                    {
                        "format_id": "91",
                        "url": "https://video.example.test/combined.mp4",
                        "ext": "mp4",
                        "vcodec": "avc1",
                        "acodec": "mp4a",
                        "height": 256,
                    }
                ],
            },
            "https://youtu.be/abc123",
            "stable",
        )

        self.assertIsNotNone(result)
        self.assertTrue(result["assets"][0]["downloadUrl"].startswith("https://example.test/proxy/"))
        self.assertEqual(result["assets"][0]["providerAssetId"], "abc123:direct:91")

    def test_codec_missing_format_candidate_is_rejected(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        result = resolver._youtube_direct_result(
            {
                "id": "abc123",
                "formats": [
                    {
                        "format_id": "fallback",
                        "url": "https://video.example.test/fallback.mp4",
                        "ext": "mp4",
                        "height": 360,
                    }
                ],
            },
            "https://youtu.be/abc123",
            "stable",
        )

        self.assertIsNone(result)

    def test_mhtml_format_candidate_is_rejected(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        result = resolver._youtube_direct_result(
            {
                "id": "abc123",
                "formats": [
                    {
                        "format_id": "sb0",
                        "url": "https://video.example.test/storyboard.mhtml",
                        "ext": "mhtml",
                        "mime_type": "video/mhtml",
                        "vcodec": "none",
                        "height": 45,
                    }
                ],
            },
            "https://youtu.be/abc123",
            "stable",
        )

        self.assertIsNone(result)

    def test_youtube_resolve_fails_fast_without_server_download_flag(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        with (
            mock.patch.object(media_resolver_backend, "_load_tools", return_value=(mock.Mock(), None)),
            mock.patch.object(resolver, "_resolve_youtube_innertube_asset", return_value=(None, "unavailable")),
            mock.patch.object(resolver, "_resolve_youtube_direct_asset", return_value=(None, "bot challenge")),
            mock.patch.object(resolver, "_resolve_youtube_cli_download") as cli_download,
            mock.patch.dict(os.environ, {}, clear=True),
        ):
            result = resolver.resolve("https://youtu.be/abc123", "youtube")

        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "RESOLVE_FAILED")
        cli_download.assert_not_called()

    def test_youtube_server_download_flag_falls_back_to_cached_file_after_innertube(self):
        cache_dir = pathlib.Path(tempfile.mkdtemp())
        stable = media_resolver_backend._safe_id("https://youtu.be/abc123")
        (cache_dir / f"{stable}.mp4").write_bytes(b"media")
        resolver = media_resolver_backend.MediaResolver(cache_dir, "https://example.test")
        ydl = mock.Mock()
        ydl.__enter__ = mock.Mock(return_value=ydl)
        ydl.__exit__ = mock.Mock(return_value=None)
        ydl.extract_info.return_value = {"id": "abc123", "title": "Video title"}
        yt_dlp = mock.Mock()
        yt_dlp.YoutubeDL.return_value = ydl

        with (
            mock.patch.object(media_resolver_backend, "_load_tools", return_value=(yt_dlp, None)),
            mock.patch.object(
                resolver,
                "_resolve_youtube_innertube_asset",
                return_value=(None, "unavailable"),
            ) as innertube,
            mock.patch.object(resolver, "_resolve_youtube_direct_asset") as direct_asset,
            mock.patch.dict(os.environ, {"MEDIA_RESOLVER_YOUTUBE_SERVER_DOWNLOAD_ENABLED": "true"}, clear=True),
        ):
            result = resolver.resolve("https://youtu.be/abc123", "youtube")

        direct_asset.assert_not_called()
        innertube.assert_called_once()
        self.assertTrue(result["ok"])
        self.assertTrue(result["assets"][0]["downloadUrl"].startswith("https://example.test/files/"))

    def test_youtube_server_download_uses_ios_innertube_merge_before_cli(self):
        cache_dir = pathlib.Path(tempfile.mkdtemp())
        stable = media_resolver_backend._safe_id("https://youtu.be/abc123")
        merged = cache_dir / f"{stable}.innertube-ios.mp4"
        resolver = media_resolver_backend.MediaResolver(cache_dir, "https://example.test")
        info = {"id": "abc123", "title": "Video title", "height": 360}

        def ios_download(*args):
            merged.write_bytes(b"merged")
            return info, merged, None

        with (
            mock.patch.object(media_resolver_backend, "_load_tools", return_value=(mock.Mock(), "/opt/ffmpeg")),
            mock.patch.object(resolver, "_resolve_youtube_innertube_asset", return_value=(None, "playability=LOGIN_REQUIRED")),
            mock.patch.object(resolver, "_resolve_youtube_innertube_ios_download", side_effect=ios_download) as ios,
            mock.patch.object(resolver, "_resolve_youtube_cli_download") as cli_download,
            mock.patch.object(resolver, "_ensure_mobile_mp4", return_value=merged) as ensure_mobile,
            mock.patch.dict(os.environ, {"MEDIA_RESOLVER_YOUTUBE_SERVER_DOWNLOAD_ENABLED": "true"}, clear=True),
        ):
            result = resolver.resolve("https://youtu.be/abc123", "youtube", allow_delegate=False)

        ios.assert_called_once()
        cli_download.assert_not_called()
        ensure_mobile.assert_called_once_with(merged, "/opt/ffmpeg")
        self.assertTrue(result["ok"])
        self.assertEqual(result["assets"][0]["title"], "Video title")
        self.assertTrue(result["assets"][0]["downloadUrl"].endswith(f"/files/{stable}.innertube-ios.mp4"))
        self.assertEqual(media_resolver_backend.YOUTUBE_INNERTUBE_LAST_DIAGNOSTIC["status"], "success")


class InstagramOrderTest(unittest.TestCase):
    def test_graph_sidecar_preserves_mixed_media_order(self):
        html = (
            '"edge_sidecar_to_children":{"edges":['
            '{"node":{"__typename":"GraphImage","display_url":"https://scontent.cdninstagram.com/v/t51.29350-15/first.jpg"}},'
            '{"node":{"__typename":"GraphVideo","video_url":"https://scontent.cdninstagram.com/o1/v/t16/f1/video.mp4"}},'
            '{"node":{"__typename":"GraphImage","display_url":"https://scontent.cdninstagram.com/v/t51.29350-15/second.jpg"}}'
            "]}"
        )

        media = media_resolver_backend.MediaResolver._instagram_graph_media(html, include_images=True)

        self.assertEqual([item[0] for item in media], ["IMAGE", "VIDEO", "IMAGE"])
        self.assertEqual([item[1] for item in media], [
            "https://scontent.cdninstagram.com/v/t51.29350-15/first.jpg",
            "https://scontent.cdninstagram.com/o1/v/t16/f1/video.mp4",
            "https://scontent.cdninstagram.com/v/t51.29350-15/second.jpg",
        ])

    def test_instagram_embed_assets_include_sort_index(self):
        resolver = media_resolver_backend.MediaResolver(pathlib.Path(tempfile.mkdtemp()), "https://example.test")
        response = mock.Mock()
        response.__enter__ = mock.Mock(return_value=response)
        response.__exit__ = mock.Mock(return_value=None)
        response.read.return_value = (
            b'"edge_sidecar_to_children":{"edges":['
            b'{"node":{"__typename":"GraphImage","display_url":"https://scontent.cdninstagram.com/v/t51.29350-15/first.jpg"}},'
            b'{"node":{"__typename":"GraphVideo","video_url":"https://scontent.cdninstagram.com/o1/v/t16/f1/video.mp4"}},'
            b'{"node":{"__typename":"GraphImage","display_url":"https://scontent.cdninstagram.com/v/t51.29350-15/second.jpg"}}'
            b"]}"
        )

        with mock.patch.object(media_resolver_backend.urllib.request, "urlopen", return_value=response):
            assets = resolver._resolve_instagram_embed("https://www.instagram.com/p/SHORTCODE/")

        self.assertEqual([asset["mediaType"] for asset in assets], ["IMAGE", "VIDEO", "IMAGE"])
        self.assertEqual([asset["sortIndex"] for asset in assets], [0, 1, 2])
        self.assertEqual([asset["providerAssetId"] for asset in assets], [
            "SHORTCODE:item:0",
            "SHORTCODE:item:1",
            "SHORTCODE:item:2",
        ])
        self.assertEqual([asset["isPreferred"] for asset in assets], [True, False, False])

    def test_instagram_media_url_decodes_double_escaped_percent_sequences(self):
        url = (
            "https:\\/\\/scontent.cdninstagram.com\\/o1\\/v\\/t16\\/f2\\/video.mp4"
            "?efg=abc\\u00253D\\u00253D&amp;ccb=17-1"
        )

        decoded = media_resolver_backend._decode_instagram_media_url(url)

        self.assertEqual(decoded, "https://scontent.cdninstagram.com/o1/v/t16/f2/video.mp4?efg=abc%3D%3D&ccb=17-1")
        self.assertNotIn("\\u0025", decoded)


class HandlerTest(unittest.TestCase):
    def setUp(self):
        media_resolver_backend.DIRECT_MEDIA_PROXIES.clear()

    def test_head_health_returns_ok_for_render_readiness(self):
        with tempfile.TemporaryDirectory() as tmp:
            media_resolver_backend.Handler.resolver = media_resolver_backend.MediaResolver(
                pathlib.Path(tmp),
                "https://example.test",
            )
            server = media_resolver_backend.ThreadingHTTPServer(
                ("127.0.0.1", 0),
                media_resolver_backend.Handler,
            )
            thread = threading.Thread(target=server.serve_forever)
            thread.daemon = True
            thread.start()
            try:
                connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
                connection.request("HEAD", "/health")
                response = connection.getresponse()
                self.assertEqual(response.status, 200)
                self.assertEqual(response.read(), b"")
                connection.close()
            finally:
                server.shutdown()
                thread.join(timeout=5)
                server.server_close()

    def test_get_health_exposes_innertube_status_without_error_detail(self):
        with tempfile.TemporaryDirectory() as tmp:
            media_resolver_backend.Handler.resolver = media_resolver_backend.MediaResolver(
                pathlib.Path(tmp),
                "https://example.test",
            )
            media_resolver_backend.YOUTUBE_INNERTUBE_LAST_DIAGNOSTIC.update(
                {"status": "failed", "error": "sensitive diagnostic", "at": 123}
            )
            server = media_resolver_backend.ThreadingHTTPServer(
                ("127.0.0.1", 0),
                media_resolver_backend.Handler,
            )
            thread = threading.Thread(target=server.serve_forever)
            thread.daemon = True
            thread.start()
            try:
                connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
                connection.request("GET", "/health")
                response = connection.getresponse()
                body = json.loads(response.read())
                self.assertEqual(response.status, 200)
                self.assertEqual(body["youtube"]["innertube"], {"status": "failed", "at": 123})
                self.assertNotIn("sensitive diagnostic", repr(body))
                connection.close()
            finally:
                server.shutdown()
                thread.join(timeout=5)
                server.server_close()

    def test_proxy_forwards_range_only_after_public_dns_check(self):
        media_resolver_backend.DIRECT_MEDIA_PROXIES["range-token"] = {
            "url": "https://rr1.googlevideo.com/videoplayback",
            "mimeType": "video/mp4",
            "headers": {"Referer": "https://www.youtube.com/"},
            "expiresAt": media_resolver_backend.time.time() + 60,
        }
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.status = 206
        response.headers = {
            "Content-Type": "video/mp4",
            "Content-Length": "4",
            "Content-Range": "bytes 0-3/4",
            "Accept-Ranges": "bytes",
        }
        response.read.side_effect = [b"data", b""]
        with tempfile.TemporaryDirectory() as tmp:
            media_resolver_backend.Handler.resolver = media_resolver_backend.MediaResolver(
                pathlib.Path(tmp),
                "https://example.test",
            )
            server = media_resolver_backend.ThreadingHTTPServer(("127.0.0.1", 0), media_resolver_backend.Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with (
                    mock.patch.object(media_resolver_backend, "_url_resolves_to_public_ip", return_value=True),
                    mock.patch.object(media_resolver_backend.urllib.request, "urlopen", return_value=response) as urlopen,
                ):
                    connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
                    connection.request("GET", "/proxy/range-token", headers={"Range": "bytes=0-3"})
                    downstream = connection.getresponse()
                    self.assertEqual(downstream.status, 206)
                    self.assertEqual(downstream.read(), b"data")
                    connection.close()
            finally:
                server.shutdown()
                thread.join(timeout=5)
                server.server_close()

        upstream_request = urlopen.call_args.args[0]
        self.assertEqual(upstream_request.get_header("Range"), "bytes=0-3")

    def test_proxy_rejects_non_public_dns_before_upstream_request(self):
        media_resolver_backend.DIRECT_MEDIA_PROXIES["blocked-token"] = {
            "url": "https://rr1.googlevideo.com/videoplayback",
            "mimeType": "video/mp4",
            "headers": {},
            "expiresAt": media_resolver_backend.time.time() + 60,
        }
        with tempfile.TemporaryDirectory() as tmp:
            media_resolver_backend.Handler.resolver = media_resolver_backend.MediaResolver(
                pathlib.Path(tmp),
                "https://example.test",
            )
            server = media_resolver_backend.ThreadingHTTPServer(("127.0.0.1", 0), media_resolver_backend.Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with (
                    mock.patch.object(media_resolver_backend, "_url_resolves_to_public_ip", return_value=False),
                    mock.patch.object(media_resolver_backend.urllib.request, "urlopen") as urlopen,
                ):
                    connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
                    connection.request("GET", "/proxy/blocked-token")
                    downstream = connection.getresponse()
                    self.assertEqual(downstream.status, 502)
                    downstream.read()
                    connection.close()
                    urlopen.assert_not_called()
            finally:
                server.shutdown()
                thread.join(timeout=5)
                server.server_close()


if __name__ == "__main__":
    unittest.main()
