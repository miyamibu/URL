FROM brainicism/bgutil-ytdlp-pot-provider:1.3.2-node@sha256:9a96e6385ce1928da87dea07b1cab0413d2cf8c07a3b8a8bd419f53df2c3843c AS pot-provider

FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    DENO_INSTALL=/opt/deno \
    MEDIA_RESOLVER_YOUTUBE_DENO_RUNTIME=/opt/deno/bin \
    PATH=/opt/deno/bin:$PATH

WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends libatomic1 \
 && useradd --create-home --uid 10001 --shell /usr/sbin/nologin resolver \
 && rm -rf /var/lib/apt/lists/*

COPY requirements-media-resolver.txt ./
RUN python -m pip install --upgrade -r requirements-media-resolver.txt

COPY scripts/install_deno_for_nixpacks.py scripts/
RUN python scripts/install_deno_for_nixpacks.py

# Import the official Node provider by immutable image digest. Deno remains in
# this image as yt-dlp's JavaScript runtime for YouTube EJS challenge solving.
COPY --from=pot-provider /usr/local/bin/node /usr/local/bin/node
COPY --from=pot-provider /app /opt/bgutil-ytdlp-pot-provider

COPY scripts/media_resolver_backend.py scripts/start_media_resolver.sh scripts/

USER resolver

CMD ["bash", "scripts/start_media_resolver.sh"]
