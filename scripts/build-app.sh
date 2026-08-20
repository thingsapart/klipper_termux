#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
source "$ROOT/scripts/repository-env.sh"
k4a_resolve_repository_urls "$ROOT"
: "${K4A_TERMUX_DOWNLOAD_URL:=${KAB_TERMUX_DOWNLOAD_URL:-}}"
: "${K4A_TERMUX_GITHUB_RELEASES_URL:=${KAB_TERMUX_GITHUB_RELEASES_URL:-}}"
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

command -v java >/dev/null 2>&1 || {
  echo "error: JDK 17-21 is required (Android Studio itself is not)." >&2
  exit 1
}
JAVA_MAJOR="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] && (( JAVA_MAJOR >= 17 && JAVA_MAJOR <= 21 )) || {
  echo "error: this Android build requires JDK 17-21; found ${JAVA_MAJOR:-unknown}." >&2
  exit 1
}

cd "$ROOT"
GRADLE_ARGS=(--no-daemon)
[[ -n "${K4A_INSTALLER_URL:-}" ]] && GRADLE_ARGS+=("-Pk4aInstallerUrl=$K4A_INSTALLER_URL")
[[ -n "${K4A_REPOSITORY_URL:-}" ]] && GRADLE_ARGS+=("-Pk4aRepositoryUrl=$K4A_REPOSITORY_URL")
[[ -n "${K4A_TERMUX_DOWNLOAD_URL:-}" ]] && GRADLE_ARGS+=("-Pk4aTermuxDownloadUrl=$K4A_TERMUX_DOWNLOAD_URL")
[[ -n "${K4A_TERMUX_GITHUB_RELEASES_URL:-}" ]] && GRADLE_ARGS+=("-Pk4aTermuxGithubReleasesUrl=$K4A_TERMUX_GITHUB_RELEASES_URL")
./gradlew "${GRADLE_ARGS[@]}" :android:app:testDebugUnitTest :android:app:assembleDebug
printf 'APK: %s\n' "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
