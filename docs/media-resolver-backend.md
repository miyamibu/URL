# Media Resolver Backend

## Goal

Deploy `scripts/media_resolver_backend.py` as the backend used by the app-side
`MediaResolverBackendURL` setting.

## Context

The Android and iOS apps call:

```text
POST {MediaResolverBackendURL}/resolve
```

The request body should contain `url` and `serviceType`. The backend also
accepts the older `provider` key for compatibility with earlier deployments.

The backend also exposes:

```text
GET /health
GET /files/{name}
```

It uses `yt-dlp` and `imageio-ffmpeg` to resolve public YouTube, Instagram,
TikTok, and X/Twitter URLs into downloadable media candidates.

## Constraints

- Do not commit the production backend URL into source.
- Use a public HTTPS host for physical-device and release verification.
- Set Android with `URLSAVER_MEDIA_RESOLVER_BACKEND_URL` or
  `media.resolver.backend.url`.
- Set iOS in `ios/Config/URLSaverSecrets.xcconfig`:

```xcconfig
URLSAVER_MEDIA_RESOLVER_BACKEND_URL = https:/$()/your-host.example.com
```

## Run Locally

```bash
python3 -m pip install -r requirements-media-resolver.txt
python3 scripts/media_resolver_backend.py --host 127.0.0.1 --port 8080 --public-base-url http://127.0.0.1:8080
curl -i http://127.0.0.1:8080/health
```

## Deploy

Use a server-style host such as Render or Railway.

For Render Blueprint deploys, `render.yaml` defines the service, build command,
start command, and `/health` check for `rinbam-media-resolver`.

Recommended start command:

```bash
python3 scripts/media_resolver_backend.py
```

Recommended build command:

```bash
pip install --upgrade -r requirements-media-resolver.txt
```

For Instagram posts that are not accessible anonymously, configure a Netscape
cookies file as a host secret and point one of these environment variables to
that mounted file path:

```text
MEDIA_RESOLVER_INSTAGRAM_COOKIES_FILE
INSTAGRAM_YTDLP_COOKIES_FILE
MEDIA_RESOLVER_YTDLP_COOKIES_FILE
YT_DLP_COOKIES_FILE
```

Do not commit cookies or account secrets to the repository.

On Render, store the cookies file as a Secret File and set
`MEDIA_RESOLVER_INSTAGRAM_COOKIES_FILE` to that mounted file path.

## Done When

- `GET /health` returns HTTP 200 on the public host.
- `POST /resolve` returns at least one asset for a supported public URL.
- Instagram reel/photo/sidecar posts that require login return assets when the
  host has an Instagram cookies file configured.
- Android and iOS builds contain a non-empty `MediaResolverBackendURL`.
- Physical-device verification confirms that a supported entry can start media
  saving and produce a saved download record.

## Failure Handling

- If `/health` is 404, the deployed service is not running this script.
- If `/resolve` returns `UNSUPPORTED_URL`, confirm the URL host is supported and public.
- If `/resolve` returns `MEDIA_NOT_CREATED`, check provider availability and host logs.
- If mobile apps show no candidates, verify the app build contains the backend URL.
