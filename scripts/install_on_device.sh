#!/usr/bin/env bash
set -euo pipefail

APP_ID="${APP_ID:-jp.miyamibu.urlalbum}"
MAIN_ACTIVITY="jp.mimac.urlsaver.MainActivity"
ADB_BIN="${ADB_BIN:-adb}"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/install_on_device.sh [--serial <device-serial>] [--connect <host:port>] [--allow-emulator] [--allow-fresh-install]

Options:
  --serial   Target device serial from `adb devices` (recommended when multiple devices exist)
  --connect  Run `adb connect <host:port>` before install (for wireless debugging)
  --allow-emulator
             Allow installing to emulator if physical device is not connected
  --allow-fresh-install
             Allow installing when jp.miyamibu.urlalbum is not already installed.
             Without this flag, the script refuses fresh installs to avoid silently
             replacing a data-bearing app with an empty database.
EOF
}

SERIAL=""
CONNECT_TARGET=""
ALLOW_EMULATOR="false"
ALLOW_FRESH_INSTALL="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="${2:-}"
      shift 2
      ;;
    --connect)
      CONNECT_TARGET="${2:-}"
      shift 2
      ;;
    --allow-emulator)
      ALLOW_EMULATOR="true"
      shift 1
      ;;
    --allow-fresh-install)
      ALLOW_FRESH_INSTALL="true"
      shift 1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[error] Unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

if [[ -n "$CONNECT_TARGET" ]]; then
  echo "[info] Connecting wireless device: $CONNECT_TARGET"
  "$ADB_BIN" connect "$CONNECT_TARGET"
fi

DEVICE_LINES="$("$ADB_BIN" devices | tail -n +2 | sed '/^[[:space:]]*$/d')"

if [[ -z "$SERIAL" ]]; then
  SERIAL="$(echo "$DEVICE_LINES" | awk '$2 == "device" && $1 !~ /^emulator-/ {print $1; exit}')"
fi

if [[ -z "$SERIAL" ]]; then
  if [[ "$ALLOW_EMULATOR" == "true" ]]; then
    SERIAL="$(echo "$DEVICE_LINES" | awk '$2 == "device" {print $1; exit}')"
  fi
fi

if [[ -z "$SERIAL" ]]; then
  echo "[error] No physical device in 'device' state."
  echo "$DEVICE_LINES"
  echo
  echo "Try this:"
  echo "  1) USB: reconnect cable and accept the debug authorization dialog on the phone."
  echo "  2) Wireless: run 'adb pair <ip:pairing-port>' then 'adb connect <ip:debug-port>'."
  echo "  3) Recheck with: adb devices -l"
  echo "  4) If you intentionally target emulator, pass --allow-emulator."
  exit 1
fi

STATE="$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)"
if [[ "$STATE" != "device" ]]; then
  echo "[error] Selected serial '$SERIAL' is not ready (state=$STATE)."
  "$ADB_BIN" devices -l
  exit 1
fi

echo "[info] Using device: $SERIAL"
DEVICE_MODEL="$("$ADB_BIN" -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
if [[ -n "$DEVICE_MODEL" ]]; then
  echo "[info] Device model: $DEVICE_MODEL"
fi
echo "[info] Building debug APK..."
./gradlew assembleDebug

APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "[error] APK not found: $APK_PATH"
  exit 1
fi

PACKAGE_PATH_BEFORE="$("$ADB_BIN" -s "$SERIAL" shell pm path "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$PACKAGE_PATH_BEFORE" && "$ALLOW_FRESH_INSTALL" != "true" ]]; then
  cat <<EOF
[error] '$APP_ID' is not currently installed on $SERIAL.

Refusing to do a fresh install because it would create a new empty app data
directory. If this is intentional for a clean device, rerun with:
  ./scripts/install_on_device.sh --serial $SERIAL --allow-fresh-install
EOF
  exit 1
fi

if [[ -n "$PACKAGE_PATH_BEFORE" ]]; then
  BACKUP_DIR="$PROJECT_ROOT/artifacts/device-backups/android"
  BACKUP_STAMP="$(date +%Y%m%d-%H%M%S)"
  BACKUP_PATH="$BACKUP_DIR/${BACKUP_STAMP}-${SERIAL}-${APP_ID}.tgz"
  BACKUP_PARTIAL_PATH="${BACKUP_PATH}.partial"
  mkdir -p "$BACKUP_DIR"
  echo "[info] Backing up app databases/shared preferences before install..."
  set +e
  "$ADB_BIN" -s "$SERIAL" exec-out run-as "$APP_ID" sh -c "cd /data/data/$APP_ID && tar -czf - databases shared_prefs 2>/dev/null" > "$BACKUP_PARTIAL_PATH"
  BACKUP_CODE=$?
  set -e
  if [[ $BACKUP_CODE -ne 0 || ! -s "$BACKUP_PARTIAL_PATH" ]] || ! tar -tzf "$BACKUP_PARTIAL_PATH" >/dev/null 2>&1; then
    rm -f "$BACKUP_PARTIAL_PATH"
    cat <<EOF
[error] Could not back up existing app data from $SERIAL.
Refusing to install because preserving local URL data is more important than
continuing blindly.
EOF
    exit 1
  fi
  mv "$BACKUP_PARTIAL_PATH" "$BACKUP_PATH"
  echo "[info] Backup saved: $BACKUP_PATH"
fi

echo "[info] Installing APK..."
set +e
INSTALL_OUTPUT="$("$ADB_BIN" -s "$SERIAL" install -r -d "$APK_PATH" 2>&1)"
INSTALL_CODE=$?
set -e

if [[ $INSTALL_CODE -ne 0 ]]; then
  echo "$INSTALL_OUTPUT"
  if echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    cat <<EOF
[hint] Signature mismatch detected.
Do not auto-uninstall this app on a data-bearing device. Export or back up data
first, then use a clean test device/emulator if a destructive reinstall is
really needed.
EOF
  elif echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_VERSION_DOWNGRADE"; then
    cat <<'EOF'
[hint] A newer versionCode is already installed.
Use a matching/newer versionCode. Do not uninstall a data-bearing device just to
work around this.
EOF
  elif echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_OLDER_SDK"; then
    cat <<'EOF'
[hint] Device Android version is below minSdk (26).
Use Android 8.0+ device/emulator.
EOF
  fi
  exit "$INSTALL_CODE"
fi

echo "$INSTALL_OUTPUT"

PACKAGE_PATH="$("$ADB_BIN" -s "$SERIAL" shell pm path "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
if [[ -z "$PACKAGE_PATH" ]]; then
  echo "[error] Install command succeeded, but package '$APP_ID' is not visible on device."
  exit 1
fi

echo "[info] Launching app..."
"$ADB_BIN" -s "$SERIAL" shell am start -n "$APP_ID/$MAIN_ACTIVITY"
echo "[done] App installed and launched on $SERIAL"
