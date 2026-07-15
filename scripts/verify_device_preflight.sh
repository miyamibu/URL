#!/usr/bin/env bash
set -u

APP_BUNDLE_ID="${APP_BUNDLE_ID:-com.mibu.codebridge.ios}"
ANDROID_PACKAGE="${ANDROID_PACKAGE:-jp.miyamibu.urlalbum}"
IOS_DEVICE_UDID="${IOS_DEVICE_UDID:-00008101-00066D96340A001E}"
REMOTE_XPC_PORT="${REMOTE_XPC_PORT:-42314}"
APPIUM_PORT="${APPIUM_PORT:-4723}"

failures=0

check() {
  local label="$1"
  shift
  if "$@" >/tmp/rinbam-preflight-check.out 2>/tmp/rinbam-preflight-check.err; then
    printf 'PASS %s\n' "$label"
    cat /tmp/rinbam-preflight-check.out
  else
    failures=$((failures + 1))
    printf 'FAIL %s\n' "$label"
    cat /tmp/rinbam-preflight-check.out
    cat /tmp/rinbam-preflight-check.err
  fi
}

printf 'Rinbam device preflight\n'
printf 'iOS bundle: %s\n' "$APP_BUNDLE_ID"
printf 'iOS UDID: %s\n' "$IOS_DEVICE_UDID"
printf 'Android package: %s\n' "$ANDROID_PACKAGE"

check "Appium server ${APPIUM_PORT}" \
  curl -fsS --max-time 2 "http://127.0.0.1:${APPIUM_PORT}/status"

check "RemoteXPC tunnel ${REMOTE_XPC_PORT}" \
  bash -c 'payload=$(curl -fsS --max-time 2 "http://127.0.0.1:${1}/remotexpc/tunnels") && printf "%s" "$payload" | rg -q '"'"'"status"[[:space:]]*:[[:space:]]*"OK"'"'"' && printf "%s" "$payload" | rg -q "\\\"${2}\\\""' _ "${REMOTE_XPC_PORT}" "${IOS_DEVICE_UDID}"

if command -v xcrun >/dev/null 2>&1; then
  xcrun xctrace list devices >/tmp/rinbam-preflight-check.out 2>/tmp/rinbam-preflight-check.err
  if grep -q "$IOS_DEVICE_UDID" /tmp/rinbam-preflight-check.out && ! awk '/== Devices Offline ==/{offline=1} /== Simulators ==/{offline=0} offline && index($0, udid){found=1} END{exit found ? 0 : 1}' udid="$IOS_DEVICE_UDID" /tmp/rinbam-preflight-check.out; then
    printf 'PASS Xcode physical device visibility\n'
  else
    failures=$((failures + 1))
    printf 'FAIL Xcode physical device visibility\n'
  fi
  cat /tmp/rinbam-preflight-check.out
  cat /tmp/rinbam-preflight-check.err
else
  failures=$((failures + 1))
  printf 'FAIL Xcode physical device visibility\nxcrun not found\n'
fi

if command -v adb >/dev/null 2>&1; then
  adb devices -l >/tmp/rinbam-preflight-check.out 2>/tmp/rinbam-preflight-check.err
  if awk 'NR > 1 && $2 == "device" {found=1} END{exit found ? 0 : 1}' /tmp/rinbam-preflight-check.out; then
    printf 'PASS ADB devices\n'
  else
    failures=$((failures + 1))
    printf 'FAIL ADB devices\n'
  fi
  cat /tmp/rinbam-preflight-check.out
  cat /tmp/rinbam-preflight-check.err
else
  failures=$((failures + 1))
  printf 'FAIL ADB devices\nadb not found\n'
fi

rm -f /tmp/rinbam-preflight-check.out /tmp/rinbam-preflight-check.err

if [ "$failures" -eq 0 ]; then
  printf 'PASS device preflight\n'
  exit 0
fi

printf 'FAIL device preflight: %s check(s) failed\n' "$failures"
printf 'If RemoteXPC is missing, keep this running in a separate Terminal: sudo appium driver run xcuitest tunnel-creation\n'
exit 1
