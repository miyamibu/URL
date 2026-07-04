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
and TikTok URLs into downloadable media candidates.

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
PATH=$PWD/.deno/bin:$PATH python3 scripts/media_resolver_backend.py
```

Recommended build command:

```bash
DENO_INSTALL=$PWD/.deno curl -fsSL https://deno.land/install.sh | sh && pip install --upgrade -r requirements-media-resolver.txt
```

YouTube extraction can require yt-dlp's JavaScript challenge solver. Keep Deno
available on `PATH` in production hosts so `--remote-components ejs:github`
can run the solver when needed.

For Instagram or YouTube posts that are not accessible anonymously, configure a
Netscape cookies file as a host secret and point one of these environment
variables to that mounted file path:

```text
MEDIA_RESOLVER_INSTAGRAM_COOKIES_FILE
INSTAGRAM_YTDLP_COOKIES_FILE
MEDIA_RESOLVER_YOUTUBE_COOKIES_FILE
YOUTUBE_YTDLP_COOKIES_FILE
MEDIA_RESOLVER_YTDLP_COOKIES_FILE
YT_DLP_COOKIES_FILE
```

Do not commit cookies or account secrets to the repository.

`GET /health` intentionally reports only safe cookie diagnostics:

```text
fileConfigured / fileReadable / contentConfigured
lineCount / domainCount / domains
```

Use these values to confirm the mounted secret actually contains YouTube or
Instagram cookie rows. Cookie names and values are never returned.

For YouTube production, prefer direct/proxy resolution with a valid cookies file
and PO token. Configure one of:

```text
MEDIA_RESOLVER_YOUTUBE_PO_TOKEN
YOUTUBE_YTDLP_PO_TOKEN
```

If yt-dlp needs additional extractor arguments, configure:

```text
MEDIA_RESOLVER_YOUTUBE_EXTRACTOR_ARGS
YOUTUBE_YTDLP_EXTRACTOR_ARGS
```

Server-side YouTube downloading is disabled by default because it can keep
mobile clients waiting for minutes on hosts that YouTube challenges. Enable it
only for controlled debugging:

```text
MEDIA_RESOLVER_YOUTUBE_SERVER_DOWNLOAD_ENABLED=true
```

On Render, store the cookies file as a Secret File and set
`MEDIA_RESOLVER_INSTAGRAM_COOKIES_FILE`,
`MEDIA_RESOLVER_YOUTUBE_COOKIES_FILE`, or the shared
`MEDIA_RESOLVER_YTDLP_COOKIES_FILE` to that mounted file path.

If Secret Files are awkward in the host UI, store the Netscape cookies file
contents in a secret environment variable instead. The backend writes the secret
value to a runtime-only file under `/tmp` before passing it to `yt-dlp`.

```text
MEDIA_RESOLVER_INSTAGRAM_COOKIES
INSTAGRAM_YTDLP_COOKIES
MEDIA_RESOLVER_YOUTUBE_COOKIES
YOUTUBE_YTDLP_COOKIES
MEDIA_RESOLVER_YTDLP_COOKIES
YT_DLP_COOKIES
```

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
