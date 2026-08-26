FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    DENO_INSTALL=/opt/deno \
    PATH=/opt/deno/bin:$PATH

WORKDIR /app

COPY requirements-media-resolver.txt ./
RUN python -m pip install --upgrade -r requirements-media-resolver.txt

COPY scripts/install_deno_for_nixpacks.py scripts/
RUN python scripts/install_deno_for_nixpacks.py

COPY scripts/media_resolver_backend.py scripts/

CMD ["python", "scripts/media_resolver_backend.py"]
