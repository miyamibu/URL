#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
aab_path="${1:-$repo_root/app/build/outputs/bundle/release/app-release.aab}"

if [[ ! -s "$aab_path" ]]; then
  echo "ERROR Android release AAB is missing or empty: $aab_path" >&2
  exit 1
fi

bundle_entries="$(unzip -Z1 "$aab_path")"
mapping_entry="BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"

if ! grep -Fxq "$mapping_entry" <<<"$bundle_entries"; then
  echo "ERROR Android release AAB does not contain the R8 mapping metadata." >&2
  exit 1
fi

if grep -Eq '\.so$' <<<"$bundle_entries"; then
  if ! grep -q '^BUNDLE-METADATA/com.android.tools.build.debugsymbols/' <<<"$bundle_entries"; then
    echo "ERROR Android release AAB contains native libraries without native debug-symbol metadata." >&2
    exit 1
  fi
  echo "OK Android release AAB contains R8 mapping and native debug-symbol metadata"
else
  echo "OK Android release AAB contains R8 mapping and no native libraries"
fi
