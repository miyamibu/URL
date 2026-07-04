import importlib.util
import http.client
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
            },
            "https://youtu.be/abc123",
            "stable",
        )

        self.assertIsNotNone(result)
        asset = result["assets"][0]
        self.assertTrue(asset["downloadUrl"].startswith("https://example.test/proxy/"))
        self.assertEqual(len(media_resolver_backend.DIRECT_MEDIA_PROXIES), 1)
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
            mock.patch.object(resolver, "_resolve_youtube_direct_asset", return_value=(None, "bot challenge")),
            mock.patch.object(resolver, "_resolve_youtube_cli_download") as cli_download,
            mock.patch.dict(os.environ, {}, clear=True),
        ):
            result = resolver.resolve("https://youtu.be/abc123", "youtube")

        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "RESOLVE_FAILED")
        cli_download.assert_not_called()


class HandlerTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
