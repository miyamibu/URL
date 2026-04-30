#!/usr/bin/env bash
set -euo pipefail

APP_ID="jp.mimac.urlsaver"
MAIN_ACTIVITY=".MainActivity"
ADB_BIN="${ADB_BIN:-adb}"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/install_on_device.sh [--serial <device-serial>] [--connect <host:port>] [--allow-emulator] [--force-reinstall]

Options:
  --serial   Target device serial from `adb devices` (recommended when multiple devices exist)
  --connect  Run `adb connect <host:port>` before install (for wireless debugging)
  --allow-emulator
             Allow installing to emulator if physical device is not connected
  --force-reinstall
             If install fails with signature/version conflict, uninstall and retry automatically
EOF
}

SERIAL=""
CONNECT_TARGET=""
ALLOW_EMULATOR="false"
FORCE_REINSTALL="false"

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
    --force-reinstall)
      FORCE_REINSTALL="true"
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

echo "[info] Installing APK..."
set +e
INSTALL_OUTPUT="$("$ADB_BIN" -s "$SERIAL" install -r "$APK_PATH" 2>&1)"
INSTALL_CODE=$?
set -e

if [[ $INSTALL_CODE -ne 0 ]]; then
  echo "$INSTALL_OUTPUT"
  NEED_REINSTALL="false"
  if echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    NEED_REINSTALL="true"
    cat <<EOF
[hint] Signature mismatch detected.
Run:
  adb -s $SERIAL uninstall $APP_ID
Then retry this script.
EOF
  elif echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_VERSION_DOWNGRADE"; then
    NEED_REINSTALL="true"
    cat <<'EOF'
[hint] A newer versionCode is already installed.
Uninstall the existing app or bump versionCode.
EOF
  elif echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_OLDER_SDK"; then
    cat <<'EOF'
[hint] Device Android version is below minSdk (26).
Use Android 8.0+ device/emulator.
EOF
  fi

  if [[ "$FORCE_REINSTALL" == "true" && "$NEED_REINSTALL" == "true" ]]; then
    echo "[info] Trying force reinstall: uninstall -> install"
    "$ADB_BIN" -s "$SERIAL" uninstall "$APP_ID" || true
    set +e
    RETRY_OUTPUT="$("$ADB_BIN" -s "$SERIAL" install -r "$APK_PATH" 2>&1)"
    RETRY_CODE=$?
    set -e
    echo "$RETRY_OUTPUT"
    if [[ $RETRY_CODE -ne 0 ]]; then
      exit "$RETRY_CODE"
    fi
  else
    exit "$INSTALL_CODE"
  fi
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
