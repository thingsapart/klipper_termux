#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle}"

if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then
  JAVA_CANDIDATE="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  [[ -n "$JAVA_CANDIDATE" ]] && export JAVA_HOME="$JAVA_CANDIDATE"
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  case "$(uname -s)" in
    Darwin) SDK_CANDIDATE="$HOME/Library/Android/sdk" ;;
    *) SDK_CANDIDATE="$HOME/Android/Sdk" ;;
  esac
  [[ -d "$SDK_CANDIDATE" ]] && export ANDROID_HOME="$SDK_CANDIDATE"
fi

cd "$ROOT"
./gradlew --no-daemon \
  :android:configurator-core:test \
  :android:configurator-app:lintDebug \
  :android:configurator-app:assembleDebug
printf 'APK: %s\n' "$ROOT/android/configurator-app/build/outputs/apk/debug/configurator-app-debug.apk"
