#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-}"
LAUNCH=1

usage() {
  echo "Usage: $0 [--serial ADB_SERIAL] [--no-launch]" >&2
}

while (($#)); do
  case "$1" in
    --serial) shift; SERIAL="${1:-}" ;;
    --no-launch) LAUNCH=0 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
  shift
done

"$ROOT/scripts/build-app.sh"

ADB="$(command -v adb 2>/dev/null || true)"
if [[ -z "$ADB" ]]; then
  SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [[ -n "$SDK_ROOT" && -x "$SDK_ROOT/platform-tools/adb" ]] && ADB="$SDK_ROOT/platform-tools/adb"
fi
[[ -n "$ADB" ]] || { echo "error: adb was not found; install Android SDK Platform-Tools." >&2; exit 1; }

ADB_ARGS=()
[[ -n "$SERIAL" ]] && ADB_ARGS=(-s "$SERIAL")
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" "${ADB_ARGS[@]}" install -r "$APK"
if (( LAUNCH )); then
  "$ADB" "${ADB_ARGS[@]}" shell am start \
    -n dev.klipper.androidbridge/.MainActivity
fi
