#!/usr/bin/env bash
# Start the bgutil-ytdlp-pot-provider HTTP server (Node flavor, image digest
# pinned in Railway.Dockerfile) inside the same container, then run the resolver
# in the foreground. If the provider fails to start, the resolver still starts:
# yt-dlp's POT framework then degrades to no-token behavior and the existing
# Innertube/CLI fallbacks remain in charge (fail-safe, never fail-open).
set -euo pipefail

PROVIDER_ROOT="${MEDIA_RESOLVER_POT_PROVIDER_HOME:-/opt/bgutil-ytdlp-pot-provider}"
PROVIDER_PORT="${MEDIA_RESOLVER_POT_PROVIDER_PORT:-4416}"
PROVIDER_BASE_URL="${MEDIA_RESOLVER_YOUTUBE_POT_PROVIDER_BASE_URL:-http://127.0.0.1:${PROVIDER_PORT}}"

if [ -f "${PROVIDER_ROOT}/build/main.js" ] && command -v node >/dev/null 2>&1; then
  (
    cd "${PROVIDER_ROOT}"
    exec node build/main.js --port "${PROVIDER_PORT}"
  ) >/dev/null 2>&1 &

  # Bounded startup wait: give the provider at most ~5s to answer, then move on.
  if MEDIA_RESOLVER_PROVIDER_PROBE_URL="${PROVIDER_BASE_URL}" python - <<'PYEOF'
import os
import time
import urllib.error
import urllib.request

base_url = os.environ.get("MEDIA_RESOLVER_PROVIDER_PROBE_URL", "")
for _ in range(20):
    try:
        urllib.request.urlopen(base_url + "/ping", timeout=1)
        raise SystemExit(0)
    except urllib.error.HTTPError:
        raise SystemExit(0)  # any HTTP response proves the process is up
    except Exception:
        time.sleep(0.25)
raise SystemExit(1)
PYEOF
  then
    export MEDIA_RESOLVER_YOUTUBE_POT_PROVIDER_BASE_URL="${PROVIDER_BASE_URL}"
  else
    unset MEDIA_RESOLVER_YOUTUBE_POT_PROVIDER_BASE_URL
    echo "bgutil POT provider did not become reachable; continuing without it" >&2
  fi
else
  unset MEDIA_RESOLVER_YOUTUBE_POT_PROVIDER_BASE_URL
  echo "bgutil POT provider bundle missing; starting resolver without it" >&2
fi

cd "$(dirname "$0")/.."
exec python scripts/media_resolver_backend.py
