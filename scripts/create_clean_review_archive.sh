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

case "$ARCHIVE_PATH" in
  "$ROOT_DIR"/*)
    printf 'FAIL clean review archive must be written outside the repository: %s\n' "$ARCHIVE_PATH" >&2
    exit 1
    ;;
esac

if [[ -e "$ARCHIVE_PATH" && "${URLSAVER_REPLACE_CLEAN_REVIEW_ARCHIVE:-false}" != "true" ]]; then
  printf 'FAIL %s already exists. Set URLSAVER_REPLACE_CLEAN_REVIEW_ARCHIVE=true to replace this generated archive.\n' "$ARCHIVE_PATH" >&2
  exit 1
fi
if [[ -e "$ARCHIVE_PATH" && "${URLSAVER_REPLACE_CLEAN_REVIEW_ARCHIVE:-false}" == "true" ]]; then
  rm -f "$ARCHIVE_PATH"
fi

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/urlsaver-clean-review.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

file_list="$tmpdir/tracked-files.txt"
safe_file_list="$tmpdir/safe-tracked-files.txt"
git ls-files >"$file_list"
while IFS= read -r relative_path; do
  case "$relative_path" in
    .git/*|.claude/*|.codex/*|.idea/*|.gradle/*|node_modules/*|*/node_modules/*|.next/*|*/.next/*|.vercel/*|*/.vercel/*)
      continue
      ;;
    build/*|*/build/*|ios/build-*/*|*/DerivedData/*|*/Index.noindex/*|*.xcarchive|*.ipa|*.dSYM/*|*.mobileprovision)
      continue
      ;;
    artifacts/*|*.db|*.sqlite|*.sqlite3|*.tgz|*.tar.gz|*.zip|.DS_Store|*/.DS_Store)
      continue
      ;;
    .env|.env.*|*/.env|*/.env.*|local.properties|*/local.properties|ios/Config/URLSaverSecrets.xcconfig)
      continue
      ;;
  esac
  printf '%s\n' "$relative_path" >>"$safe_file_list"
done <"$file_list"

if [[ ! -s "$safe_file_list" ]]; then
  printf 'FAIL no safe tracked files are available for the clean review archive\n' >&2
  exit 1
fi

rsync -a --files-from="$safe_file_list" ./ "$tmpdir/URLSaver/"

(cd "$tmpdir" && zip -qr "$ARCHIVE_PATH" URLSaver)

bash "$ROOT_DIR/scripts/verify_clean_review_archive.sh" "$ARCHIVE_PATH"

printf 'OK created %s\n' "$ARCHIVE_PATH"
