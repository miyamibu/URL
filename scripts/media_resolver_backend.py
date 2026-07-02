#!/usr/bin/env python3
"""Small media resolver backend for Rinbam.

The mobile apps call POST /resolve with a public post URL. This server resolves
one or more downloadable media files with yt-dlp, caches the file locally, and
returns HTTPS/public URLs under /files/{name}.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.request
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, unquote, urlparse


VIDEO_EXTENSIONS = {".mp4", ".mov", ".m4v", ".webm"}
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".heic"}
AUDIO_EXTENSIONS = {".m4a", ".mp3", ".aac", ".opus", ".ogg", ".wav"}
SUPPORTED_HOST_RE = re.compile(
    r"(^|\.)youtube\.com$|^youtu\.be$|(^|\.)instagram\.com$|(^|\.)tiktok\.com$"
)


def _env_value(*names: str) -> str | None:
    for name in names:
        value = os.environ.get(name)
        if value and value.strip():
            return value.strip()
    return None


def _public_base_url(host: str, port: int, explicit: str | None) -> str:
    if explicit and explicit.strip():
        return explicit.strip().rstrip("/")
    render_url = os.environ.get("RENDER_EXTERNAL_URL")
    if render_url:
        return render_url.rstrip("/")
    railway_domain = os.environ.get("RAILWAY_PUBLIC_DOMAIN")
    if railway_domain:
        if railway_domain.startswith(("http://", "https://")):
            return railway_domain.rstrip("/")
        return f"https://{railway_domain.rstrip('/')}"
    return f"http://{host}:{port}"


def _safe_id(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:24]


def _is_supported_url(value: str) -> bool:
    try:
        parsed = urlparse(value)
        host = (parsed.hostname or "").lower()
        return parsed.scheme in {"http", "https"} and SUPPORTED_HOST_RE.search(host) is not None
    except Exception:
        return False


def _read_json(url: str, referer: str, timeout: int = 25) -> dict | None:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0",
            "Accept": "application/json,text/plain,*/*",
            "Accept-Language": "ja,en-US;q=0.9,en;q=0.8",
            "Referer": referer,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8", "replace"))
    except Exception:
        return None


def _is_tiktok_media_url(value: str) -> bool:
    try:
        parsed = urlparse(value)
        host = (parsed.hostname or "").lower()
        path = parsed.path.lower()
        return (
            parsed.scheme == "https"
            and ("tiktokcdn" in host or "tikwm.com" in host)
            and (
                ".mp4" in path
                or ".jpg" in path
                or ".jpeg" in path
                or ".webp" in path
                or ".heic" in path
                or "mime_type=video_mp4" in value
            )
        )
    except Exception:
        return False


def _is_instagram_media_url(value: str) -> bool:
    try:
        parsed = urlparse(value)
        host = (parsed.hostname or "").lower()
        return parsed.scheme == "https" and ("instagram" in host or "cdninstagram" in host)
    except Exception:
        return False


def _stable_unique(values: list[str]) -> list[str]:
    seen = set()
    result = []
    for value in values:
        key = value.split("?", 1)[0].rsplit("/", 1)[-1]
        if key in seen:
            continue
        seen.add(key)
        result.append(value)
    return result


def _load_tools() -> tuple[object, str | None]:
    try:
        import yt_dlp  # type: ignore
    except Exception as exc:
        raise RuntimeError("yt-dlp is required. Install requirements-media-resolver.txt") from exc

    ffmpeg_location = None
    try:
        import imageio_ffmpeg  # type: ignore

        ffmpeg_location = imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        ffmpeg_location = None
    return yt_dlp, ffmpeg_location


def _mime_type(path: pathlib.Path) -> str:
    guessed, _ = mimetypes.guess_type(path.name)
    if guessed:
        return guessed
    if path.suffix.lower() in VIDEO_EXTENSIONS:
        return "video/mp4"
    return "image/jpeg"


def _yt_dlp_cookie_options(provider: str) -> dict:
    provider_key = provider.upper().replace("-", "_")
    cookie_file = _env_value(
        f"MEDIA_RESOLVER_{provider_key}_COOKIES_FILE",
        f"{provider_key}_YTDLP_COOKIES_FILE",
        "MEDIA_RESOLVER_YTDLP_COOKIES_FILE",
        "YT_DLP_COOKIES_FILE",
    )
    if cookie_file:
        path = pathlib.Path(cookie_file).expanduser()
        if path.is_file():
            return {"cookiefile": str(path)}
    return {}


def _media_type(path: pathlib.Path) -> str:
    suffix = path.suffix.lower()
    if suffix in IMAGE_EXTENSIONS:
        return "IMAGE"
    return "VIDEO"


def _quality_label(info: dict, path: pathlib.Path) -> str | None:
    height = info.get("height")
    if isinstance(height, int) and height > 0:
        return f"{height}p"
    if path.suffix.lower() in IMAGE_EXTENSIONS:
        return "image"
    return None


def _yt_dlp_format(provider: str) -> str:
    if provider == "youtube":
        return "best[ext=mp4]/best"
    return "18/b[ext=mp4]/bv*[ext=mp4]+ba[ext=m4a]/best[ext=mp4]/best"


def _resolver_error(provider: str, exc: Exception) -> dict:
    message = str(exc)
    lowered = message.lower()
    if provider == "instagram" and ("empty media response" in lowered or "login" in lowered or "cookies" in lowered):
        code = "AUTH_REQUIRED"
    elif "requested format is not available" in lowered:
        code = "FORMAT_UNAVAILABLE"
    elif "no video could be found" in lowered:
        code = "MEDIA_NOT_FOUND"
    elif "private" in lowered or "sign in" in lowered or "cookies" in lowered:
        code = "AUTH_REQUIRED"
    else:
        code = "RESOLVE_FAILED"
    return {"ok": False, "provider": provider, "error": code, "message": message, "assets": []}


class MediaResolver:
    def __init__(self, cache_dir: pathlib.Path, public_base_url: str) -> None:
        self.cache_dir = cache_dir
        self.public_base_url = public_base_url.rstrip("/")
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def resolve(self, url: str, service_type: str | None) -> dict:
        if not _is_supported_url(url):
            return {"ok": False, "error": "UNSUPPORTED_URL", "assets": []}

        provider = self._provider(url, service_type)
        if provider == "tiktok":
            tiktok_assets = self._resolve_tiktok_tikwm(url)
            if tiktok_assets:
                return {"ok": True, "provider": "tiktok", "assets": tiktok_assets}
        if provider == "instagram":
            instagram_assets = self._resolve_instagram_embed(url)
            if instagram_assets:
                return {"ok": True, "provider": "instagram", "assets": instagram_assets}
            try:
                instagram_assets = self._resolve_instagram_ytdlp(url)
            except Exception as exc:
                return _resolver_error(provider, exc)
            if instagram_assets:
                return {"ok": True, "provider": "instagram", "assets": instagram_assets}

        yt_dlp, ffmpeg_location = _load_tools()
        stable = _safe_id(url)
        existing = self._find_existing(stable)
        info: dict = {}
        if existing is None:
            outtmpl = str(self.cache_dir / f"{stable}.%(ext)s")
            options = {
                "format": _yt_dlp_format(provider),
                "merge_output_format": "mp4",
                "outtmpl": outtmpl,
                "quiet": True,
                "no_warnings": True,
                "noplaylist": True,
                "retries": 2,
                "fragment_retries": 2,
                "socket_timeout": 30,
                "extractor_args": {"youtube": {"player_client": ["android"]}},
                **_yt_dlp_cookie_options(provider),
            }
            if ffmpeg_location:
                options["ffmpeg_location"] = ffmpeg_location
            try:
                with yt_dlp.YoutubeDL(options) as ydl:  # type: ignore[attr-defined]
                    info = ydl.extract_info(url, download=True) or {}
            except Exception as exc:
                return _resolver_error(provider, exc)
            existing = self._find_existing(stable)
        else:
            with yt_dlp.YoutubeDL({
                "quiet": True,
                "no_warnings": True,
                "noplaylist": True,
                "extractor_args": {"youtube": {"player_client": ["android"]}},
                **_yt_dlp_cookie_options(provider),
            }) as ydl:  # type: ignore[attr-defined]
                try:
                    info = ydl.extract_info(url, download=False) or {}
                except Exception:
                    info = {}

        if existing is None:
            return {"ok": False, "error": "MEDIA_NOT_CREATED", "assets": []}
        if existing.suffix.lower() not in IMAGE_EXTENSIONS and ffmpeg_location:
            existing = self._ensure_mobile_mp4(existing, ffmpeg_location)

        duration = info.get("duration")
        duration_ms = int(duration * 1000) if isinstance(duration, (int, float)) and duration > 0 else None
        return {
            "ok": True,
            "provider": provider,
            "assets": [
                {
                    "provider": provider,
                    "providerAssetId": f"{info.get('id') or stable}:backend:{existing.suffix.lower().lstrip('.')}",
                    "canonicalPostUrl": info.get("webpage_url") or url,
                    "authorName": info.get("uploader") or info.get("channel"),
                    "title": info.get("title"),
                    "thumbnailUrl": info.get("thumbnail"),
                    "durationMs": duration_ms,
                    "mediaType": _media_type(existing),
                    "downloadUrl": f"{self.public_base_url}/files/{existing.name}",
                    "mimeType": _mime_type(existing),
                    "qualityLabel": _quality_label(info, existing),
                    "width": info.get("width"),
                    "height": info.get("height"),
                    "bitrate": None,
                    "isPreferred": True,
                }
            ],
        }

    def _resolve_tiktok_tikwm(self, url: str) -> list[dict]:
        endpoint = f"https://www.tikwm.com/api/?url={quote(url, safe='')}"
        payload = _read_json(endpoint, referer="https://www.tiktok.com/")
        if not payload or payload.get("code") != 0:
            return []
        data = payload.get("data")
        if not isinstance(data, dict):
            return []
        post_id = str(data.get("id") or _safe_id(url))
        author = data.get("author") if isinstance(data.get("author"), dict) else {}
        unique_id = str(author.get("unique_id") or "").strip()
        title = str(data.get("title") or "").strip() or None
        author_name = str(author.get("nickname") or "").strip() or None
        canonical = f"https://www.tiktok.com/@{unique_id}/video/{post_id}" if unique_id else url
        assets: list[dict] = []
        image_urls = [
            item
            for item in data.get("images") or []
            if isinstance(item, str) and _is_tiktok_media_url(item)
        ]
        for index, media_url in enumerate(_stable_unique(image_urls)):
            assets.append(
                self._direct_asset(
                    provider="tiktok",
                    provider_asset_id=f"{post_id}:image:{index}",
                    canonical=canonical,
                    media_url=media_url,
                    media_type="IMAGE",
                    mime_type="image/jpeg",
                    title=title,
                    author_name=author_name,
                    thumbnail_url=media_url,
                    preferred=index == 0,
                )
            )
        play_url = data.get("play")
        if isinstance(play_url, str) and _is_tiktok_media_url(play_url) and not assets:
            cover = data.get("cover") if isinstance(data.get("cover"), str) else None
            assets.append(
                self._direct_asset(
                    provider="tiktok",
                    provider_asset_id=f"{post_id}:video:0",
                    canonical=canonical,
                    media_url=play_url,
                    media_type="VIDEO",
                    mime_type="video/mp4",
                    title=title,
                    author_name=author_name,
                    thumbnail_url=cover,
                    preferred=True,
                )
            )
        return assets

    def _resolve_instagram_ytdlp(self, url: str) -> list[dict]:
        yt_dlp, ffmpeg_location = _load_tools()
        stable = _safe_id(url)
        files = self._cached_provider_files(stable)
        if not files:
            outtmpl = str(self.cache_dir / f"{stable}_%(id)s_%(autonumber)03d.%(ext)s")
            options = {
                "format": "bv*[vcodec^=avc1][ext=mp4]+ba[ext=m4a]/bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/best",
                "merge_output_format": "mp4",
                "outtmpl": outtmpl,
                "quiet": True,
                "no_warnings": True,
                "noplaylist": False,
                "retries": 2,
                "fragment_retries": 2,
                "socket_timeout": 30,
                **_yt_dlp_cookie_options("instagram"),
            }
            if ffmpeg_location:
                options["ffmpeg_location"] = ffmpeg_location
            with yt_dlp.YoutubeDL(options) as ydl:  # type: ignore[attr-defined]
                ydl.extract_info(url, download=True)
            files = self._cached_provider_files(stable)
        assets = []
        for index, path in enumerate(files):
            if path.suffix.lower() in AUDIO_EXTENSIONS:
                continue
            media_type = _media_type(path)
            mime_type = _mime_type(path)
            if media_type == "VIDEO" and ffmpeg_location:
                path = self._ensure_mobile_mp4(path, ffmpeg_location)
                mime_type = "video/mp4"
            assets.append(
                {
                    "provider": "instagram",
                    "providerAssetId": f"{path.stem}:backend:{index}",
                    "canonicalPostUrl": url,
                    "authorName": None,
                    "title": None,
                    "thumbnailUrl": None,
                    "durationMs": None,
                    "mediaType": media_type,
                    "downloadUrl": f"{self.public_base_url}/files/{path.name}",
                    "mimeType": mime_type,
                    "qualityLabel": "image" if media_type == "IMAGE" else None,
                    "width": None,
                    "height": None,
                    "bitrate": None,
                    "isPreferred": index == 0,
                }
            )
        return assets

    def _resolve_instagram_embed(self, url: str) -> list[dict]:
        parsed = urlparse(url)
        parts = [part for part in parsed.path.strip("/").split("/") if part]
        if len(parts) < 2 or parts[0] not in {"p", "reel", "tv"}:
            return []
        kind, shortcode = parts[0], parts[1]
        embed_url = f"https://www.instagram.com/{kind}/{shortcode}/embed/captioned/"
        request = urllib.request.Request(
            embed_url,
            headers={
                "User-Agent": "Mozilla/5.0",
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "ja,en-US;q=0.9,en;q=0.8",
                "Referer": url,
            },
        )
        try:
            html = urllib.request.urlopen(request, timeout=20).read().decode("utf-8", "replace")
        except Exception:
            return []
        decoded = (
            html.replace(r"\"", '"')
            .replace(r"\\/", "/")
            .replace(r"\/", "/")
            .replace(r"\\u002F", "/")
            .replace(r"\u002F", "/")
            .replace(r"\\u0026", "&")
            .replace(r"\u0026", "&")
            .replace("&amp;", "&")
        )
        section = decoded
        if kind == "reel":
            parts = decoded.split('"__typename":"GraphVideo"', 1)
            section = parts[1] if len(parts) > 1 else decoded
        elif "edge_sidecar_to_children" in decoded:
            section = decoded.split("edge_sidecar_to_children", 1)[1]
        image_urls = [] if kind == "reel" else self._instagram_graph_urls(section, "GraphImage", "display_url")
        video_urls = self._instagram_graph_urls(section, "GraphVideo", "video_url")
        assets = [
            self._direct_asset(
                provider="instagram",
                provider_asset_id=f"{shortcode}:image:{index}",
                canonical=url,
                media_url=media_url,
                media_type="IMAGE",
                mime_type="image/jpeg",
                title=None,
                author_name=None,
                thumbnail_url=media_url,
                preferred=not video_urls and index == 0,
            )
            for index, media_url in enumerate(_stable_unique(image_urls))
        ]
        assets.extend(
            self._direct_asset(
                provider="instagram",
                provider_asset_id=f"{shortcode}:video:{index}",
                canonical=url,
                media_url=media_url,
                media_type="VIDEO",
                mime_type="video/mp4",
                title=None,
                author_name=None,
                thumbnail_url=None,
                preferred=index == 0,
            )
            for index, media_url in enumerate(_stable_unique(video_urls))
        )
        return assets

    @staticmethod
    def _instagram_graph_urls(html: str, typename: str, field: str) -> list[str]:
        results = []
        for match in re.finditer(rf'"__typename":"{typename}"', html):
            chunk = html[match.start() : match.start() + 16000]
            if "profile_pic" in chunk or "video_default_cover_frame" in chunk:
                continue
            url_match = re.search(rf'"{field}":"(https:[^"]+)"', chunk)
            if url_match and _is_instagram_media_url(url_match.group(1)):
                results.append(url_match.group(1))
        return results

    @staticmethod
    def _direct_asset(
        provider: str,
        provider_asset_id: str,
        canonical: str,
        media_url: str,
        media_type: str,
        mime_type: str,
        title: str | None,
        author_name: str | None,
        thumbnail_url: str | None,
        preferred: bool,
    ) -> dict:
        return {
            "provider": provider,
            "providerAssetId": provider_asset_id,
            "canonicalPostUrl": canonical,
            "authorName": author_name,
            "title": title,
            "thumbnailUrl": thumbnail_url,
            "durationMs": None,
            "mediaType": media_type,
            "downloadUrl": media_url,
            "mimeType": mime_type,
            "qualityLabel": "image" if media_type == "IMAGE" else None,
            "width": None,
            "height": None,
            "bitrate": None,
            "isPreferred": preferred,
        }

    def file_path(self, name: str) -> pathlib.Path | None:
        safe_name = pathlib.Path(unquote(name)).name
        path = self.cache_dir / safe_name
        if not path.exists() or not path.is_file():
            return None
        return path

    def _find_existing(self, stable: str) -> pathlib.Path | None:
        candidates = [
            path
            for path in self.cache_dir.glob(f"{stable}.*")
            if path.suffix.lower() in VIDEO_EXTENSIONS | IMAGE_EXTENSIONS
        ]
        if not candidates:
            return None
        candidates.sort(key=lambda path: (path.suffix.lower() not in VIDEO_EXTENSIONS, -path.stat().st_mtime))
        return candidates[0]

    def _cached_provider_files(self, stable: str) -> list[pathlib.Path]:
        return sorted(
            [
                path
                for path in self.cache_dir.glob(f"{stable}_*")
                if path.is_file()
                and path.suffix.lower() in VIDEO_EXTENSIONS | IMAGE_EXTENSIONS
                and not path.name.endswith(".part")
            ],
            key=lambda path: path.name,
        )

    def _ensure_mobile_mp4(self, path: pathlib.Path, ffmpeg_location: str) -> pathlib.Path:
        if path.suffix.lower() != ".mp4":
            target = path.with_suffix(".mp4")
        else:
            target = path
        probe = subprocess.run(
            [ffmpeg_location, "-hide_banner", "-i", str(path)],
            capture_output=True,
            text=True,
        )
        if path.suffix.lower() == ".mp4" and "Video: h264" in probe.stderr and "Audio:" in probe.stderr:
            return path

        temp_path = target.with_name(f"{target.stem}.mobile-tmp{target.suffix}")
        try:
            subprocess.run(
                [
                    ffmpeg_location,
                    "-y",
                    "-i",
                    str(path),
                    "-map",
                    "0:v:0",
                    "-map",
                    "0:a?",
                    "-c:v",
                    "libx264",
                    "-preset",
                    "veryfast",
                    "-pix_fmt",
                    "yuv420p",
                    "-c:a",
                    "aac",
                    "-movflags",
                    "+faststart",
                    str(temp_path),
                ],
                capture_output=True,
                text=True,
                check=True,
            )
            temp_path.replace(target)
            if target != path:
                path.unlink(missing_ok=True)
            return target
        except Exception:
            temp_path.unlink(missing_ok=True)
            return path

    @staticmethod
    def _provider(url: str, service_type: str | None) -> str:
        if service_type:
            return service_type.lower()
        host = (urlparse(url).hostname or "").lower()
        if "youtube" in host or host == "youtu.be":
            return "youtube"
        if "instagram" in host:
            return "instagram"
        if "tiktok" in host:
            return "tiktok"
        return "web"


class Handler(BaseHTTPRequestHandler):
    resolver: MediaResolver

    def do_GET(self) -> None:
        if self.path == "/health":
            self._json({"ok": True, "service": "rinbam-media-resolver", "time": int(time.time())})
            return
        if self.path.startswith("/files/"):
            path = self.resolver.file_path(self.path.removeprefix("/files/"))
            if path is None:
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", _mime_type(path))
            self.send_header("Content-Length", str(path.stat().st_size))
            self.end_headers()
            with path.open("rb") as file:
                while chunk := file.read(1024 * 1024):
                    self.wfile.write(chunk)
            return
        self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        if self.path != "/resolve":
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            url = str(payload.get("url") or "").strip()
            service_type = payload.get("serviceType") or payload.get("provider")
            result = self.resolver.resolve(url, str(service_type) if service_type else None)
            status = HTTPStatus.OK if result.get("ok") else HTTPStatus.BAD_REQUEST
            self._json(result, status=status)
        except Exception as exc:
            self._json({"ok": False, "error": str(exc), "assets": []}, status=HTTPStatus.INTERNAL_SERVER_ERROR)

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _json(self, payload: dict, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=os.environ.get("HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8080")))
    parser.add_argument("--cache-dir", default=os.environ.get("MEDIA_RESOLVER_CACHE_DIR", "/tmp/rinbam-media-cache"))
    parser.add_argument("--public-base-url", default=os.environ.get("MEDIA_RESOLVER_PUBLIC_BASE_URL"))
    args = parser.parse_args()

    public_base_url = _public_base_url(args.host, args.port, args.public_base_url)
    Handler.resolver = MediaResolver(pathlib.Path(args.cache_dir), public_base_url)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"media resolver listening on {args.host}:{args.port} public_base_url={public_base_url}", flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
