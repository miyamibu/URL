#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

ARCHIVE_NAME="${URLSAVER_CLEAN_REVIEW_ARCHIVE:-${TMPDIR:-/tmp}/urlsaver-review/URLSaver-clean-review.zip}"
case "$ARCHIVE_NAME" in
  /*) ARCHIVE_PATH="$ARCHIVE_NAME" ;;
  *) ARCHIVE_PATH="$ROOT_DIR/$ARCHIVE_NAME" ;;
esac
ARCHIVE_DIR="$(dirname "$ARCHIVE_PATH")"
mkdir -p "$ARCHIVE_DIR"

if [[ -e "$ARCHIVE_PATH" && "${URLSAVER_REPLACE_CLEAN_REVIEW_ARCHIVE:-false}" != "true" ]]; then
  printf 'FAIL %s already exists. Set URLSAVER_REPLACE_CLEAN_REVIEW_ARCHIVE=true to replace this generated archive.\n' "$ARCHIVE_PATH" >&2
  exit 1
fi
if [[ -e "$ARCHIVE_PATH" && "${URLSAVER_REPLACE_CLEAN_REVIEW_ARCHIVE:-false}" == "true" ]]; then
  rm -f "$ARCHIVE_PATH"
fi

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/urlsaver-clean-review.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

rsync -a --delete \
  --exclude '.git/' \
  --exclude '.claude/' \
  --exclude '.codex/' \
  --exclude '.idea/' \
  --exclude '.gradle/' \
  --exclude 'node_modules/' \
  --exclude '.next/' \
  --exclude '.vercel/' \
  --exclude 'build/' \
  --exclude 'app/build/' \
  --exclude 'ios/build/' \
  --exclude 'ios/build-*/' \
  --exclude 'ios/**/DerivedData/' \
  --exclude 'ios/**/Index.noindex/' \
  --exclude 'ios/**/*.xcarchive' \
  --exclude 'ios/**/*.ipa' \
  --exclude 'ios/**/*.dSYM' \
  --exclude 'ios/**/*.mobileprovision' \
  --exclude 'local.properties' \
  --exclude 'local.properties.example' \
  --exclude 'ios/Config/URLSaverSecrets*' \
  --exclude 'artifacts/' \
  --exclude 'artifacts/**/*.db' \
  --exclude 'artifacts/**/*.sqlite' \
  --exclude 'artifacts/**/*.sqlite3' \
  --exclude '*.tgz' \
  --exclude '*.tar.gz' \
  --exclude '.DS_Store' \
  --exclude '__MACOSX/' \
  ./ "$tmpdir/URLSaver/"

(cd "$tmpdir" && zip -qr "$ARCHIVE_PATH" URLSaver)

forbidden='(\.git/|\.claude/|\.codex/|\.idea/|node_modules/|\.next/|\.vercel/|ios/build/|(^|/)local\.properties$|URLSaverSecrets|artifacts/|\.db|\.sqlite|\.tgz|\.ipa|\.dSYM|\.mobileprovision)'
if unzip -l "$ARCHIVE_PATH" | grep -E "$forbidden" >/dev/null; then
  printf 'FAIL clean archive contains forbidden paths\n' >&2
  unzip -l "$ARCHIVE_PATH" | grep -E "$forbidden" >&2 || true
  exit 1
fi

printf 'OK created %s\n' "$ARCHIVE_PATH"
