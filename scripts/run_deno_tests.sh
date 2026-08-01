#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v deno >/dev/null 2>&1; then
  echo "FAIL deno is required to run Supabase function tests" >&2
  exit 1
fi

test_files="$(rg --files supabase/functions -g '*.test.ts' | sort || true)"
if [[ -z "$test_files" ]]; then
  echo "FAIL no Deno test files found" >&2
  exit 1
fi

test_count=0
while IFS= read -r test_file; do
  [[ -z "$test_file" ]] && continue
  deno test --no-check --allow-env "$test_file"
  test_count=$((test_count + 1))
done <<< "$test_files"

echo "OK Deno test files passed: $test_count"
