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
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
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
DIRECT_MEDIA_PROXIES: dict[str, dict[str, str | float]] = {}
DIRECT_MEDIA_PROXY_TTL_SECONDS = 10 * 60
YOUTUBE_DELEGATE_HEADER = "X-Rinbam-Resolver-Hop"
YOUTUBE_DELEGATE_HEADER_VALUE = "youtube-delegate"


def _env_value(*names: str) -> str | None:
    for name in names:
        value = os.environ.get(name)
        if value and value.strip():
            return value.strip()
    return None


def _env_secret_content(*names: str) -> str | None:
    for name in names:
        value = os.environ.get(name)
        if value and value.strip():
            return value
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


def _youtube_delegate_url() -> str | None:
    value = _env_value("MEDIA_RESOLVER_YOUTUBE_DELEGATE_URL")
    if not value:
        return None
    return value.rstrip("/")


def _safe_url_host(value: str | None) -> str | None:
    if not value:
        return None
    try:
        return (urlparse(value).hostname or "").lower() or None
    except Exception:
        return None


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
    provider_cookie_file = _env_value(
        f"MEDIA_RESOLVER_{provider_key}_COOKIES_FILE",
        f"{provider_key}_YTDLP_COOKIES_FILE",
    )
    provider_cookie_content = _env_secret_content(
        f"MEDIA_RESOLVER_{provider_key}_COOKIES",
        f"{provider_key}_YTDLP_COOKIES",
    )
    shared_cookie_file = _env_value(
        "MEDIA_RESOLVER_YTDLP_COOKIES_FILE",
        "YT_DLP_COOKIES_FILE",
    )
    shared_cookie_content = _env_secret_content(
        "MEDIA_RESOLVER_YTDLP_COOKIES",
        "YT_DLP_COOKIES",
    )
    file_candidates = [
        provider_cookie_file,
        shared_cookie_file,
    ]
    content_candidates = [
        None if provider_cookie_file else provider_cookie_content,
        None if provider_cookie_file or shared_cookie_file else shared_cookie_content,
    ]
    for cookie_file in file_candidates:
        if not cookie_file:
            continue
        path = pathlib.Path(cookie_file).expanduser()
        if path.is_file():
            writable_dir = pathlib.Path(os.environ.get("MEDIA_RESOLVER_COOKIES_RUNTIME_DIR", "/tmp/rinbam-media-resolver-cookies"))
            writable_dir.mkdir(parents=True, exist_ok=True)
            runtime_path = writable_dir / f"{hashlib.sha256(str(path).encode('utf-8')).hexdigest()[:16]}.txt"
            if not runtime_path.exists() or runtime_path.read_bytes() != path.read_bytes():
                shutil.copyfile(path, runtime_path)
            return {"cookiefile": str(runtime_path)}
    for cookie_content in content_candidates:
        if not cookie_content:
            continue
        writable_dir = pathlib.Path(os.environ.get("MEDIA_RESOLVER_COOKIES_RUNTIME_DIR", "/tmp/rinbam-media-resolver-cookies"))
        writable_dir.mkdir(parents=True, exist_ok=True)
        runtime_path = writable_dir / f"{hashlib.sha256(f'{provider}:{cookie_content}'.encode('utf-8')).hexdigest()[:16]}.txt"
        if not runtime_path.exists() or runtime_path.read_text(encoding="utf-8", errors="replace") != cookie_content:
            runtime_path.write_text(cookie_content, encoding="utf-8")
        return {"cookiefile": str(runtime_path)}
    return {}


def _yt_dlp_cookie_cli_args(provider: str) -> list[str]:
    cookie_file = _yt_dlp_cookie_options(provider).get("cookiefile")
    if isinstance(cookie_file, str) and cookie_file:
        return ["--cookies", cookie_file]
    return []


def _yt_dlp_cookie_status(provider: str) -> dict:
    provider_key = provider.upper().replace("-", "_")
    provider_file = _env_value(
        f"MEDIA_RESOLVER_{provider_key}_COOKIES_FILE",
        f"{provider_key}_YTDLP_COOKIES_FILE",
    )
    provider_content = _env_secret_content(
        f"MEDIA_RESOLVER_{provider_key}_COOKIES",
        f"{provider_key}_YTDLP_COOKIES",
    )
    shared_file = _env_value(
        "MEDIA_RESOLVER_YTDLP_COOKIES_FILE",
        "YT_DLP_COOKIES_FILE",
    )
    shared_content = _env_secret_content(
        "MEDIA_RESOLVER_YTDLP_COOKIES",
        "YT_DLP_COOKIES",
    )
    file_candidates = [
        provider_file,
        shared_file,
    ]
    content_candidates = [
        None if provider_file else provider_content,
        None if provider_file or shared_file else shared_content,
    ]
    configured_file = None
    configured_content = None
    for candidate in content_candidates:
        if candidate:
            configured_content = candidate
            break
    if not configured_content:
        for candidate in file_candidates:
            if candidate and pathlib.Path(candidate).expanduser().is_file():
                configured_file = candidate
                break
    file_readable = False
    diagnostics: dict[str, object] = {
        "lineCount": 0,
        "domainCount": 0,
        "domains": [],
    }
    if configured_file:
        configured_path = pathlib.Path(configured_file).expanduser()
        file_readable = configured_path.is_file()
        if file_readable:
            diagnostics = _cookie_file_diagnostics(configured_path)
    elif configured_content:
        diagnostics = _cookie_content_diagnostics(configured_content)
    return {
        "fileConfigured": bool(configured_file),
        "fileReadable": file_readable,
        "contentConfigured": bool(configured_content),
        **diagnostics,
    }


def _cookie_content_diagnostics(content: str) -> dict[str, object]:
    domains = set()
    line_count = 0
    for raw_line in content.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        line_count += 1
        parts = line.split("\t")
        if parts:
            domain = parts[0].strip().lstrip(".").lower()
            if domain:
                domains.add(domain)
    return {
        "lineCount": line_count,
        "domainCount": len(domains),
        "domains": sorted(domains)[:12],
    }


def _cookie_file_diagnostics(path: pathlib.Path) -> dict[str, object]:
    try:
        return _cookie_content_diagnostics(path.read_text(encoding="utf-8", errors="replace"))
    except Exception:
        return {
            "lineCount": 0,
            "domainCount": 0,
            "domains": [],
        }


def _yt_dlp_cli_env() -> dict[str, str]:
    env = dict(os.environ)
    deno_bins = [
        pathlib.Path.cwd() / ".deno" / "bin",
        pathlib.Path("/opt/render/project/src/.deno/bin"),
    ]
    existing_path = env.get("PATH", "")
    prefixes = [str(path) for path in deno_bins if path.is_dir()]
    if prefixes:
        env["PATH"] = os.pathsep.join([*prefixes, existing_path])
    return env


def _media_type(path: pathlib.Path) -> str:
    suffix = path.suffix.lower()
    if suffix in IMAGE_EXTENSIONS:
        return "IMAGE"
    return "VIDEO"


def _is_youtube_video_candidate(candidate: dict) -> bool:
    media_url = candidate.get("url")
    if not isinstance(media_url, str) or not media_url.startswith(("http://", "https://")):
        return False
    ext = str(candidate.get("ext") or "").lower()
    mime_type = str(candidate.get("mime_type") or candidate.get("mimeType") or "").lower()
    vcodec = str(candidate.get("vcodec") or "").lower()
    if ext not in {suffix.lstrip(".") for suffix in VIDEO_EXTENSIONS} and not mime_type.startswith("video/"):
        return False
    return vcodec not in {"", "none"}


def _youtube_info_has_video_candidate(info: dict) -> bool:
    if _is_youtube_video_candidate(info):
        return True
    formats = info.get("formats")
    return isinstance(formats, list) and any(
        isinstance(candidate, dict) and _is_youtube_video_candidate(candidate)
        for candidate in formats
    )


def _quality_label(info: dict, path: pathlib.Path) -> str | None:
    height = info.get("height")
    if isinstance(height, int) and height > 0:
        return f"{height}p"
    if path.suffix.lower() in IMAGE_EXTENSIONS:
        return "image"
    return None


def _yt_dlp_format(provider: str) -> str:
    if provider == "youtube":
        return "b[ext=mp4][height<=360]/b[ext=mp4][height<=480]/18/best[ext=mp4]/best"
    return "18/b[ext=mp4]/bv*[ext=mp4]+ba[ext=m4a]/best[ext=mp4]/best"


def _youtube_fallback_formats() -> list[str | None]:
    return [
        "bv*+ba/b",
        "bestvideo*+bestaudio/best",
        "best[ext=mp4]/best",
        "best",
        None,
    ]


def _youtube_client_variants() -> list[list[str]]:
    return [
        _youtube_extractor_args_cli(None),
        _youtube_extractor_args_cli("web"),
        _youtube_extractor_args_cli("mweb"),
        _youtube_extractor_args_cli("ios"),
        _youtube_extractor_args_cli("android"),
        _youtube_extractor_args_cli("tv"),
    ]


def _youtube_extractor_args_cli(player_client: str | None) -> list[str]:
    raw_args = _env_value("MEDIA_RESOLVER_YOUTUBE_EXTRACTOR_ARGS", "YOUTUBE_YTDLP_EXTRACTOR_ARGS")
    po_token = _env_value("MEDIA_RESOLVER_YOUTUBE_PO_TOKEN", "YOUTUBE_YTDLP_PO_TOKEN")
    args = []
    if raw_args:
        args.append(raw_args)
    if po_token:
        args.append(f"po_token={po_token}")
    if player_client:
        args.append(f"player_client={player_client}")
    if not args:
        return []
    return ["--extractor-args", "youtube:" + ";".join(args)]


def _youtube_impersonate_cli_args() -> list[str]:
    target = _env_value("MEDIA_RESOLVER_YOUTUBE_IMPERSONATE", "YOUTUBE_YTDLP_IMPERSONATE")
    if target is None:
        target = "chrome"
    target = target.strip()
    if not target or target.lower() in {"0", "false", "none", "off"}:
        return []
    return ["--impersonate", target]


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


def _safe_log(message: str) -> None:
    sys.stderr.write(f"{message}\n")
    sys.stderr.flush()


def _truncate_log(message: str, limit: int = 500) -> str:
    compact = " ".join(str(message).split())
    if len(compact) <= limit:
        return compact
    return compact[: limit - 1] + "…"


def _purge_expired_direct_proxies(now: float | None = None) -> None:
    current = time.time() if now is None else now
    expired = [
        token
        for token, item in DIRECT_MEDIA_PROXIES.items()
        if float(item.get("expiresAt") or 0) < current
    ]
    for token in expired:
        DIRECT_MEDIA_PROXIES.pop(token, None)


def _safe_proxy_headers(value: object) -> dict[str, str]:
    if not isinstance(value, dict):
        return {}
    blocked = {"authorization", "cookie", "x-goog-visitor-id"}
    result: dict[str, str] = {}
    for raw_name, raw_value in value.items():
        name = str(raw_name).strip()
        lower_name = name.lower()
        if not name or lower_name in blocked or "\n" in name or "\r" in name:
            continue
        if raw_value is None:
            continue
        string_value = str(raw_value).strip()
        if not string_value or "\n" in string_value or "\r" in string_value:
            continue
        result[name] = string_value
    return result


class MediaResolver:
    def __init__(self, cache_dir: pathlib.Path, public_base_url: str) -> None:
        self.cache_dir = cache_dir
        self.public_base_url = public_base_url.rstrip("/")
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def resolve(self, url: str, service_type: str | None, allow_delegate: bool = True) -> dict:
        if not _is_supported_url(url):
            return {"ok": False, "error": "UNSUPPORTED_URL", "assets": []}

        provider = self._provider(url, service_type)
        if provider == "youtube" and allow_delegate:
            delegated = self._resolve_youtube_delegate(url)
            if delegated is not None:
                return delegated
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
                return _resolver_error("instagram", exc)
            if instagram_assets:
                return {"ok": True, "provider": "instagram", "assets": instagram_assets}

        yt_dlp, ffmpeg_location = _load_tools()
        stable = _safe_id(url)
        if provider == "youtube":
            server_download_enabled = os.environ.get("MEDIA_RESOLVER_YOUTUBE_SERVER_DOWNLOAD_ENABLED", "").lower() in {"1", "true", "yes"}
            direct_error = None
            if not server_download_enabled:
                direct_result, direct_error = self._resolve_youtube_direct_asset(yt_dlp, url, stable)
                if direct_result is not None:
                    return direct_result
                if direct_error:
                    _safe_log(f"youtube direct resolver failed: {_truncate_log(direct_error)}")
            if not server_download_enabled:
                return _resolver_error(
                    provider,
                    RuntimeError(
                        direct_error
                        or "YouTube direct resolver returned no usable video. Configure valid YouTube cookies and PO token, or enable server download fallback explicitly."
                    ),
                )
        existing = self._find_existing(stable)
        info: dict = {}
        if existing is None:
            if provider == "youtube":
                info, existing, cli_error = self._resolve_youtube_cli_download(url, stable, ffmpeg_location)
                if existing is None:
                    if cli_error:
                        _safe_log(f"youtube resolver failed: {_truncate_log(cli_error)}")
                    return _resolver_error(provider, RuntimeError(cli_error or "YouTube media file was not created"))
                if ffmpeg_location:
                    existing = self._ensure_mobile_mp4(existing, ffmpeg_location)
            else:
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
                **_yt_dlp_cookie_options(provider),
            }) as ydl:  # type: ignore[attr-defined]
                try:
                    info = ydl.extract_info(url, download=False) or {}
                except Exception:
                    info = {}

        if existing is None:
            return {"ok": False, "error": "MEDIA_NOT_CREATED", "assets": []}

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
                    sort_index=index,
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
        info = {}
        if files:
            try:
                with yt_dlp.YoutubeDL(
                    {
                        "quiet": True,
                        "no_warnings": True,
                        "noplaylist": False,
                        **_yt_dlp_cookie_options("instagram"),
                    }
                ) as ydl:  # type: ignore[attr-defined]
                    info = ydl.extract_info(url, download=False) or {}
            except Exception:
                info = {}
        ordered_files, order_verified = self._ordered_instagram_cached_files(stable, files, info)
        assets = []
        for index, path in enumerate(ordered_files):
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
                    "providerAssetId": f"{path.stem}:item:{index}",
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
                    "sortIndex": index,
                    "orderVerified": order_verified,
                    "isPreferred": index == 0,
                }
            )
        return assets

    def _resolve_youtube_direct_asset(self, yt_dlp, url: str, stable: str) -> tuple[dict | None, str | None]:
        cli_info, cli_error = self._resolve_youtube_direct_info_cli(url)
        if cli_info is not None:
            return self._youtube_direct_result(cli_info, url, stable), None

        options = {
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            "socket_timeout": 20,
            "skip_download": True,
            "format": "all",
            **_yt_dlp_cookie_options("youtube"),
        }
        try:
            with yt_dlp.YoutubeDL(options) as ydl:  # type: ignore[attr-defined]
                info = ydl.extract_info(url, download=False) or {}
        except Exception as exc:
            return None, cli_error or str(exc)
        return self._youtube_direct_result(info, url, stable), cli_error

    def _resolve_youtube_cli_download(
        self,
        url: str,
        stable: str,
        ffmpeg_location: str | None,
    ) -> tuple[dict, pathlib.Path | None, str | None]:
        info, info_error = self._resolve_youtube_direct_info_cli(url)
        outtmpl = str(self.cache_dir / f"{stable}.%(ext)s")
        direct_existing, direct_error = self._download_youtube_direct_format(info or {}, stable, ffmpeg_location)
        if direct_existing is not None:
            return info or {}, direct_existing, None
        base_command = [
            sys.executable,
            "-m",
            "yt_dlp",
            "--quiet",
            "--no-warnings",
            "--no-playlist",
            "--remote-components",
            "ejs:github",
            "--merge-output-format",
            "mp4",
            "--retries",
            "2",
            "--fragment-retries",
            "2",
            "--output",
            outtmpl,
            *_yt_dlp_cookie_cli_args("youtube"),
            *_youtube_impersonate_cli_args(),
        ]
        if ffmpeg_location:
            base_command.extend(["--ffmpeg-location", ffmpeg_location])
        timeout = int(os.environ.get("MEDIA_RESOLVER_YOUTUBE_DOWNLOAD_TIMEOUT_SECONDS", "180"))
        formats: list[str | None] = []
        for item in [_yt_dlp_format("youtube"), *_youtube_fallback_formats()]:
            if item not in formats:
                formats.append(item)

        errors: list[str] = []
        if direct_error:
            errors.append(f"direct: {direct_error}")
        for client_variant in _youtube_client_variants():
            client_label = "default"
            if client_variant:
                client_label = client_variant[-1].split("=", 1)[-1]
            for format_selector in formats:
                command = [*base_command, *client_variant]
                if format_selector:
                    command.extend(["--format", format_selector])
                try:
                    subprocess.run(
                        [*command, url],
                        capture_output=True,
                        text=True,
                        timeout=timeout,
                        check=True,
                        env=_yt_dlp_cli_env(),
                    )
                except subprocess.TimeoutExpired as exc:
                    stderr = (exc.stderr or "").strip() if isinstance(exc.stderr, str) else ""
                    errors.append(f"{client_label}/{format_selector or 'default'}: yt-dlp download timed out after {exc.timeout} seconds. {stderr}".strip())
                    continue
                except subprocess.CalledProcessError as exc:
                    stderr = (exc.stderr or "").strip()
                    errors.append(f"{client_label}/{format_selector or 'default'}: {stderr or str(exc)}")
                    continue
                except Exception as exc:
                    errors.append(f"{client_label}/{format_selector or 'default'}: {exc}")
                    continue

                existing = self._find_existing(stable)
                if existing is not None:
                    return info or {}, existing, None
                errors.append(f"{client_label}/{format_selector or 'default'}: yt-dlp download completed but no media file was found")

        if info_error:
            errors.append(f"info: {info_error}")
        return info or {}, None, " | ".join(errors) or "yt-dlp download completed but no media file was found"

    def _download_youtube_direct_format(
        self,
        info: dict,
        stable: str,
        ffmpeg_location: str | None,
    ) -> tuple[pathlib.Path | None, str | None]:
        formats = info.get("formats")
        if not isinstance(formats, list):
            return None, "yt-dlp info did not include formats"

        progressive_candidates = []
        hls_candidates = []
        for item in formats:
            if not isinstance(item, dict):
                continue
            media_url = item.get("url")
            if not isinstance(media_url, str) or not media_url:
                continue
            ext = str(item.get("ext") or "").lower()
            protocol = str(item.get("protocol") or "").lower()
            vcodec = str(item.get("vcodec") or "").lower()
            acodec = str(item.get("acodec") or "").lower()
            if ext != "mp4":
                continue
            if vcodec in {"none", ""} or acodec in {"none", ""}:
                continue
            height = item.get("height")
            height_value = height if isinstance(height, int) else 10_000
            row = (height_value > 480, height_value, item)
            if protocol.startswith("http"):
                progressive_candidates.append(row)
            elif "m3u8" in protocol:
                hls_candidates.append(row)

        if progressive_candidates:
            progressive_candidates.sort(key=lambda row: (row[0], row[1]))
            return self._download_youtube_progressive_mp4(progressive_candidates[0][2], stable)
        if hls_candidates and ffmpeg_location:
            hls_candidates.sort(key=lambda row: (row[0], row[1]))
            return self._download_youtube_hls_mp4(hls_candidates[0][2], stable, ffmpeg_location)
        if hls_candidates and not ffmpeg_location:
            return None, "direct HLS mp4 format was available but ffmpeg was not available"
        return None, "no direct progressive or HLS mp4 format was available"

    def _download_youtube_progressive_mp4(self, selected: dict, stable: str) -> tuple[pathlib.Path | None, str | None]:
        media_url = str(selected["url"])
        headers = selected.get("http_headers") if isinstance(selected.get("http_headers"), dict) else {}
        request = urllib.request.Request(media_url, headers={str(k): str(v) for k, v in headers.items()})
        target = self.cache_dir / f"{stable}.mp4"
        temp = self.cache_dir / f"{stable}.direct-tmp.mp4"
        try:
            with urllib.request.urlopen(request, timeout=90) as response, temp.open("wb") as output:
                shutil.copyfileobj(response, output)
            if temp.stat().st_size <= 0:
                temp.unlink(missing_ok=True)
                return None, "direct progressive mp4 download produced an empty file"
            temp.replace(target)
            return target, None
        except Exception as exc:
            temp.unlink(missing_ok=True)
            return None, str(exc)

    def _download_youtube_hls_mp4(
        self,
        selected: dict,
        stable: str,
        ffmpeg_location: str,
    ) -> tuple[pathlib.Path | None, str | None]:
        media_url = str(selected["url"])
        headers = selected.get("http_headers") if isinstance(selected.get("http_headers"), dict) else {}
        header_lines = "".join(f"{key}: {value}\r\n" for key, value in headers.items())
        target = self.cache_dir / f"{stable}.mp4"
        temp = self.cache_dir / f"{stable}.hls-tmp.mp4"
        command = [
            ffmpeg_location,
            "-y",
            "-hide_banner",
        ]
        if header_lines:
            command.extend(["-headers", header_lines])
        command.extend(
            [
                "-i",
                media_url,
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
                str(temp),
            ]
        )
        try:
            subprocess.run(command, capture_output=True, text=True, timeout=180, check=True)
            if temp.stat().st_size <= 0:
                temp.unlink(missing_ok=True)
                return None, "direct HLS mp4 download produced an empty file"
            temp.replace(target)
            return target, None
        except Exception as exc:
            temp.unlink(missing_ok=True)
            return None, str(exc)

    @staticmethod
    def _resolve_youtube_direct_info_cli(url: str) -> tuple[dict | None, str | None]:
        base_command = [
            sys.executable,
            "-m",
            "yt_dlp",
            "--dump-json",
            "--skip-download",
            "--no-warnings",
            "--remote-components",
            "ejs:github",
            "--format",
            "all",
            *_yt_dlp_cookie_cli_args("youtube"),
            *_youtube_impersonate_cli_args(),
        ]
        client_variants = _youtube_client_variants()
        last_error = None
        timeout = int(os.environ.get("MEDIA_RESOLVER_YOUTUBE_CLI_TIMEOUT_SECONDS", "45"))
        for variant in client_variants:
            try:
                completed = subprocess.run(
                    [*base_command, *variant, url],
                    capture_output=True,
                    text=True,
                    timeout=timeout,
                    check=True,
                    env=_yt_dlp_cli_env(),
                )
                entries = []
                for line in completed.stdout.splitlines():
                    line = line.strip()
                    if line:
                        entries.append(json.loads(line))
                if entries:
                    if isinstance(entries[0].get("formats"), list):
                        if _youtube_info_has_video_candidate(entries[0]):
                            return entries[0], None
                        last_error = "yt-dlp CLI returned no usable video formats"
                        continue
                    merged = dict(entries[0])
                    merged["formats"] = entries
                    if _youtube_info_has_video_candidate(merged):
                        return merged, None
                    last_error = "yt-dlp CLI returned no usable video formats"
                    continue
                last_error = "yt-dlp CLI produced no JSON output"
            except subprocess.TimeoutExpired as exc:
                stderr = (exc.stderr or "").strip() if isinstance(exc.stderr, str) else ""
                last_error = f"yt-dlp CLI timed out after {exc.timeout} seconds. {stderr}".strip()
            except subprocess.CalledProcessError as exc:
                stderr = (exc.stderr or "").strip()
                last_error = stderr or str(exc)
            except Exception as exc:
                last_error = str(exc)
        return None, last_error

    def _youtube_direct_result(self, info: dict, url: str, stable: str) -> dict | None:
        selected_url = info.get("url")
        top_vcodec = str(info.get("vcodec") or "").lower()
        top_acodec = str(info.get("acodec") or "").lower()
        top_has_audio_video = top_vcodec not in {"", "none"} and top_acodec not in {"", "none"}
        formats = info.get("formats")
        if (
            top_has_audio_video
            and isinstance(selected_url, str)
            and selected_url.startswith(("http://", "https://"))
        ):
            duration = info.get("duration")
            duration_ms = int(duration * 1000) if isinstance(duration, (int, float)) and duration > 0 else None
            ext = str(info.get("ext") or "mp4").lower()
            media_type = "IMAGE" if ext in IMAGE_EXTENSIONS else "VIDEO"
            mime_type = _mime_type(pathlib.Path(f"media.{ext}"))
            return {
                "ok": True,
                "provider": "youtube",
                "assets": [
                    {
                        "provider": "youtube",
                        "providerAssetId": f"{info.get('id') or stable}:direct:{info.get('format_id') or ext}",
                        "canonicalPostUrl": info.get("webpage_url") or url,
                        "authorName": info.get("uploader") or info.get("channel"),
                        "title": info.get("title"),
                        "thumbnailUrl": info.get("thumbnail"),
                        "durationMs": duration_ms,
                        "mediaType": media_type,
                        "downloadUrl": self._direct_media_proxy_url(selected_url, mime_type, info.get("http_headers")),
                        "mimeType": mime_type,
                        "qualityLabel": _quality_label(info, pathlib.Path(f"media.{ext}")),
                        "width": info.get("width"),
                        "height": info.get("height"),
                        "bitrate": None,
                        "isPreferred": True,
                    }
                ],
            }

        if not isinstance(formats, list):
            if isinstance(selected_url, str) and selected_url.startswith(("http://", "https://")):
                duration = info.get("duration")
                duration_ms = int(duration * 1000) if isinstance(duration, (int, float)) and duration > 0 else None
                ext = str(info.get("ext") or "mp4").lower()
                media_type = "IMAGE" if ext in IMAGE_EXTENSIONS else "VIDEO"
                mime_type = _mime_type(pathlib.Path(f"media.{ext}"))
                return {
                    "ok": True,
                    "provider": "youtube",
                    "assets": [
                        {
                            "provider": "youtube",
                            "providerAssetId": f"{info.get('id') or stable}:direct:{info.get('format_id') or ext}",
                            "canonicalPostUrl": info.get("webpage_url") or url,
                            "authorName": info.get("uploader") or info.get("channel"),
                            "title": info.get("title"),
                            "thumbnailUrl": info.get("thumbnail"),
                            "durationMs": duration_ms,
                            "mediaType": media_type,
                            "downloadUrl": self._direct_media_proxy_url(selected_url, mime_type, info.get("http_headers")),
                            "mimeType": mime_type,
                            "qualityLabel": _quality_label(info, pathlib.Path(f"media.{ext}")),
                            "width": info.get("width"),
                            "height": info.get("height"),
                            "bitrate": None,
                            "isPreferred": True,
                        }
                    ],
                }
            return None

        def score(candidate: dict) -> tuple[int, int, int, int]:
            format_id = str(candidate.get("format_id") or "")
            ext = str(candidate.get("ext") or "").lower()
            vcodec = str(candidate.get("vcodec") or "").lower()
            acodec = str(candidate.get("acodec") or "").lower()
            height = candidate.get("height")
            has_audio_video = int(vcodec not in {"", "none"} and acodec not in {"", "none"})
            is_mp4 = int(ext == "mp4")
            is_18 = int(format_id == "18")
            normalized_height = height if isinstance(height, int) else 0
            return (has_audio_video, is_mp4, is_18, normalized_height)

        candidates = [
            candidate
            for candidate in formats
            if isinstance(candidate, dict)
            and _is_youtube_video_candidate(candidate)
            and str(candidate.get("vcodec") or "").lower() not in {"", "none"}
            and str(candidate.get("acodec") or "").lower() not in {"", "none"}
        ]
        if not candidates:
            format_dicts = [candidate for candidate in formats if isinstance(candidate, dict)]
            with_url = [
                candidate
                for candidate in format_dicts
                if _is_youtube_video_candidate(candidate)
            ]
            video_only = [
                candidate
                for candidate in with_url
                if str(candidate.get("vcodec") or "").lower() not in {"", "none"}
                and str(candidate.get("acodec") or "").lower() in {"", "none"}
            ]
            audio_only = [
                candidate
                for candidate in with_url
                if str(candidate.get("vcodec") or "").lower() in {"", "none"}
                and str(candidate.get("acodec") or "").lower() not in {"", "none"}
            ]
            _safe_log(
                "youtube direct resolver produced no combined asset: "
                f"formats={len(format_dicts)} with_url={len(with_url)} "
                f"video_only={len(video_only)} audio_only={len(audio_only)} "
                "sample="
                + ",".join(
                    f"{candidate.get('format_id')}:{candidate.get('ext')}:{candidate.get('height')}:{candidate.get('protocol')}"
                    for candidate in with_url[:6]
                )
            )
            return None
        if not candidates:
            return None
        selected = max(candidates, key=score)
        duration = info.get("duration")
        duration_ms = int(duration * 1000) if isinstance(duration, (int, float)) and duration > 0 else None
        ext = str(selected.get("ext") or "mp4").lower()
        mime_type = "video/mp4" if ext == "mp4" else f"video/{ext}"
        return {
            "ok": True,
            "provider": "youtube",
            "assets": [
                {
                    "provider": "youtube",
                    "providerAssetId": f"{info.get('id') or stable}:direct:{selected.get('format_id') or ext}",
                    "canonicalPostUrl": info.get("webpage_url") or url,
                    "authorName": info.get("uploader") or info.get("channel"),
                    "title": info.get("title"),
                    "thumbnailUrl": info.get("thumbnail"),
                    "durationMs": duration_ms,
                    "mediaType": "VIDEO",
                    "downloadUrl": self._direct_media_proxy_url(
                        selected["url"],
                        mime_type,
                        selected.get("http_headers") or info.get("http_headers"),
                    ),
                    "mimeType": mime_type,
                    "qualityLabel": f"{selected.get('height')}p" if isinstance(selected.get("height"), int) else None,
                    "width": selected.get("width"),
                    "height": selected.get("height"),
                    "bitrate": selected.get("tbr"),
                    "isPreferred": True,
                }
            ],
        }

    def _direct_media_proxy_url(self, media_url: str, mime_type: str, headers: object | None = None) -> str:
        _purge_expired_direct_proxies()
        token = secrets.token_urlsafe(18)
        DIRECT_MEDIA_PROXIES[token] = {
            "url": media_url,
            "mimeType": mime_type,
            "headers": _safe_proxy_headers(headers),
            "expiresAt": time.time() + DIRECT_MEDIA_PROXY_TTL_SECONDS,
        }
        return f"{self.public_base_url}/proxy/{quote(token)}"

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
        ordered_media = self._instagram_graph_media(section, include_images=kind != "reel")
        order_verified = bool(ordered_media)
        if not ordered_media:
            ordered_media = self._instagram_embed_fallback_media(section, include_images=kind != "reel")
        assets = []
        for index, media in enumerate(ordered_media):
            media_type, media_url, mime_type = media
            assets.append(
                self._direct_asset(
                    provider="instagram",
                    provider_asset_id=f"{shortcode}:item:{index}",
                    canonical=url,
                    media_url=media_url,
                    media_type=media_type,
                    mime_type=mime_type,
                    title=None,
                    author_name=None,
                    thumbnail_url=media_url if media_type == "IMAGE" else None,
                    preferred=index == 0,
                    sort_index=index,
                    order_verified=order_verified,
                )
            )
        return assets

    @staticmethod
    def _instagram_graph_media(html: str, include_images: bool) -> list[tuple[str, str, str]]:
        results: list[tuple[str, str, str]] = []
        seen: set[str] = set()
        for match in re.finditer(r'"__typename":"(GraphImage|GraphVideo)"', html):
            typename = match.group(1)
            if typename == "GraphImage" and not include_images:
                continue
            chunk = html[match.start() : match.start() + 16000]
            if "profile_pic" in chunk or "video_default_cover_frame" in chunk:
                continue
            field = "display_url" if typename == "GraphImage" else "video_url"
            url_match = re.search(rf'"{field}":"(https:[^"]+)"', chunk)
            if not url_match:
                continue
            media_url = url_match.group(1)
            if not _is_instagram_media_url(media_url) or media_url in seen:
                continue
            seen.add(media_url)
            results.append(("IMAGE", media_url, "image/jpeg") if typename == "GraphImage" else ("VIDEO", media_url, "video/mp4"))
        return results

    @staticmethod
    def _instagram_embed_fallback_media(html: str, include_images: bool) -> list[tuple[str, str, str]]:
        image_candidates: dict[str, tuple[int, int, str]] = {}
        video_candidates: list[tuple[int, str]] = []
        seen_videos: set[str] = set()
        for match in re.finditer(r'https:(?:\\?/\\?/|//)[^"<> ]+?\.jpg[^"<> ]*', html):
            if not include_images:
                continue
            media_url = (
                match.group(0)
                .replace(r"\/", "/")
                .replace(r"\u0026", "&")
                .replace("\\u00253D", "%3D")
                .replace("&amp;", "&")
            )
            if not _is_instagram_media_url(media_url):
                continue
            if "t51.2885-19" in media_url or "profile_pic" in media_url:
                continue
            if "/v/t51." not in media_url:
                continue
            key = pathlib.PurePosixPath(urlparse(media_url).path).name
            if not key:
                continue
            score = MediaResolver._instagram_image_score(media_url)
            current = image_candidates.get(key)
            if current is None or score > current[1]:
                first_pos = current[0] if current is not None else match.start()
                image_candidates[key] = (first_pos, score, media_url)
        for match in re.finditer(r'https:(?:\\?/\\?/|//)[^"<> ]+?\.mp4[^"<> ]*', html):
            media_url = (
                match.group(0)
                .replace(r"\/", "/")
                .replace(r"\u0026", "&")
                .replace("&amp;", "&")
            )
            if _is_instagram_media_url(media_url) and media_url not in seen_videos:
                seen_videos.add(media_url)
                video_candidates.append((match.start(), media_url))
        combined = [
            (position, "IMAGE", url, "image/jpeg")
            for position, _score, url in image_candidates.values()
        ] + [
            (position, "VIDEO", url, "video/mp4")
            for position, url in video_candidates
        ]
        return [(media_type, url, mime_type) for position, media_type, url, mime_type in sorted(combined, key=lambda item: item[0])]

    @staticmethod
    def _instagram_image_score(media_url: str) -> int:
        score = 0
        for token, value in (
            ("p1080", 100),
            ("s1080", 100),
            ("p720", 80),
            ("s750", 75),
            ("p640", 65),
            ("s640", 65),
            ("p480", 50),
            ("s480", 50),
            ("p320", 30),
            ("s320", 30),
            ("p240", 20),
            ("s240", 20),
            ("s150", 10),
        ):
            if token in media_url:
                score = max(score, value)
        if "dst-jpg_e35_tt" in media_url:
            score += 5
        return score

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
        sort_index: int = 0,
        order_verified: bool = True,
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
            "sortIndex": sort_index,
            "orderVerified": order_verified,
            "isPreferred": preferred,
        }

    @staticmethod
    def _ordered_instagram_cached_files(stable: str, files: list[pathlib.Path], info: dict) -> tuple[list[pathlib.Path], bool]:
        entries = info.get("entries")
        if not isinstance(entries, list) or not entries:
            return files, False
        remaining = list(files)
        ordered: list[pathlib.Path] = []
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            entry_id = str(entry.get("id") or "").strip()
            if not entry_id:
                continue
            candidates = [
                path
                for path in remaining
                if path.name.startswith(f"{stable}_{entry_id}_") or path.name.startswith(f"{stable}_{entry_id}.")
            ]
            if not candidates:
                continue
            path = sorted(candidates, key=lambda item: item.name)[0]
            ordered.append(path)
            remaining.remove(path)
        if not ordered:
            return files, False
        ordered.extend(remaining)
        return ordered, len(remaining) == 0

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
        if host in {"x.com", "twitter.com"}:
            return "x"
        return "web"

    def _resolve_youtube_delegate(self, url: str) -> dict | None:
        delegate_base = _youtube_delegate_url()
        if not delegate_base:
            return None
        if _safe_url_host(delegate_base) == _safe_url_host(self.public_base_url):
            _safe_log("youtube delegate ignored because it points to the current resolver host")
            return None

        endpoint = f"{delegate_base}/resolve"
        body = json.dumps({"url": url, "serviceType": "youtube"}, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            endpoint,
            data=body,
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Accept": "application/json",
                YOUTUBE_DELEGATE_HEADER: YOUTUBE_DELEGATE_HEADER_VALUE,
            },
            method="POST",
        )
        timeout = int(os.environ.get("MEDIA_RESOLVER_YOUTUBE_DELEGATE_TIMEOUT_SECONDS", "70"))
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError as exc:
            try:
                payload = json.loads(exc.read().decode("utf-8", "replace"))
                if isinstance(payload, dict):
                    return payload
            except Exception:
                pass
            return _resolver_error("youtube", RuntimeError(f"YouTube delegate returned HTTP {exc.code}"))
        except Exception as exc:
            return _resolver_error("youtube", RuntimeError(f"YouTube delegate failed: {exc}"))


class Handler(BaseHTTPRequestHandler):
    resolver: MediaResolver

    def do_HEAD(self) -> None:
        if self.path in {"/", "/health"}:
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_error(HTTPStatus.NOT_FOUND)

    def do_GET(self) -> None:
        if self.path == "/health":
            self._json({
                "ok": True,
                "service": "rinbam-media-resolver",
                "version": os.environ.get("RENDER_GIT_COMMIT") or os.environ.get("RENDER_GIT_COMMIT_SHA"),
                "cookies": {
                    "youtube": _yt_dlp_cookie_status("youtube"),
                    "instagram": _yt_dlp_cookie_status("instagram"),
                },
                "youtube": {
                    "poTokenConfigured": bool(_env_value("MEDIA_RESOLVER_YOUTUBE_PO_TOKEN", "YOUTUBE_YTDLP_PO_TOKEN")),
                    "extractorArgsConfigured": bool(_env_value("MEDIA_RESOLVER_YOUTUBE_EXTRACTOR_ARGS", "YOUTUBE_YTDLP_EXTRACTOR_ARGS")),
                    "serverDownloadEnabled": os.environ.get("MEDIA_RESOLVER_YOUTUBE_SERVER_DOWNLOAD_ENABLED", "").lower() in {"1", "true", "yes"},
                    "delegateConfigured": bool(_youtube_delegate_url()),
                    "delegateHost": _safe_url_host(_youtube_delegate_url()),
                },
                "time": int(time.time()),
            })
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
        if self.path.startswith("/proxy/"):
            token = pathlib.Path(unquote(self.path.removeprefix("/proxy/"))).name
            _purge_expired_direct_proxies()
            item = DIRECT_MEDIA_PROXIES.get(token)
            media_url = str(item.get("url") or "") if item else ""
            if not media_url.startswith(("http://", "https://")):
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            headers = {
                "User-Agent": "Mozilla/5.0",
                "Accept": "*/*",
            }
            stored_headers = item.get("headers") if item else None
            if isinstance(stored_headers, dict):
                headers.update({
                    str(name): str(value)
                    for name, value in stored_headers.items()
                    if str(name).strip() and str(value).strip()
                })
            incoming_range = self.headers.get("Range")
            if incoming_range:
                headers["Range"] = incoming_range
            request = urllib.request.Request(media_url, headers=headers)
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    status = HTTPStatus.PARTIAL_CONTENT if response.status == 206 else HTTPStatus.OK
                    self.send_response(status)
                    self.send_header("Content-Type", response.headers.get("Content-Type") or str(item.get("mimeType") or "application/octet-stream"))
                    for header in ("Content-Length", "Content-Range", "Accept-Ranges"):
                        value = response.headers.get(header)
                        if value:
                            self.send_header(header, value)
                    self.end_headers()
                    while chunk := response.read(1024 * 1024):
                        self.wfile.write(chunk)
            except Exception:
                self.send_error(HTTPStatus.BAD_GATEWAY)
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
            allow_delegate = self.headers.get(YOUTUBE_DELEGATE_HEADER) != YOUTUBE_DELEGATE_HEADER_VALUE
            result = self.resolver.resolve(url, str(service_type) if service_type else None, allow_delegate=allow_delegate)
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
