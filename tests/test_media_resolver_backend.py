import importlib.util
import os
import pathlib
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


if __name__ == "__main__":
    unittest.main()
