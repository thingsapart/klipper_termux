#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
source "$ROOT/scripts/repository-env.sh"
: "${K4A_SIGNING_STORE_FILE:=${KAB_SIGNING_STORE_FILE:-}}"
: "${K4A_SIGNING_STORE_PASSWORD:=${KAB_SIGNING_STORE_PASSWORD:-}}"
: "${K4A_SIGNING_KEY_ALIAS:=${KAB_SIGNING_KEY_ALIAS:-}}"
: "${K4A_SIGNING_KEY_PASSWORD:=${KAB_SIGNING_KEY_PASSWORD:-}}"
: "${K4A_TERMUX_DOWNLOAD_URL:=${KAB_TERMUX_DOWNLOAD_URL:-}}"
: "${K4A_TERMUX_GITHUB_RELEASES_URL:=${KAB_TERMUX_GITHUB_RELEASES_URL:-}}"
VERSION=""
VERSION_CODE=""
TEST_SIGNING=0

usage() {
  cat <<'EOF' >&2
Usage: scripts/prepare-release.sh VERSION [--version-code NUMBER] [--test-signing]

Builds, verifies, and places a universal APK plus SHA-256 file in dist/.

Normal releases require these environment variables:
  K4A_SIGNING_STORE_FILE
  K4A_SIGNING_STORE_PASSWORD
  K4A_SIGNING_KEY_ALIAS
  K4A_SIGNING_KEY_PASSWORD

--test-signing uses Android's local debug key. It is suitable only for private
hardware tests or an explicitly marked GitHub prerelease.
EOF
}

while (($#)); do
  case "$1" in
    --version-code) shift; VERSION_CODE="${1:-}" ;;
    --test-signing) TEST_SIGNING=1 ;;
    -h|--help) usage; exit 0 ;;
    --*) usage; exit 2 ;;
    *)
      [[ -z "$VERSION" ]] || { usage; exit 2; }
      VERSION="$1"
      ;;
  esac
  shift
done

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || {
  echo "error: VERSION must look like 0.1.0 or 0.1.0-test.1" >&2
  exit 2
}
if [[ -z "$VERSION_CODE" ]]; then
  IFS=. read -r VERSION_MAJOR VERSION_MINOR VERSION_PATCH_EXTRA <<<"$VERSION"
  VERSION_PATCH="${VERSION_PATCH_EXTRA%%[-.]*}"
  VERSION_CODE=$((10#$VERSION_MAJOR * 1000000 + 10#$VERSION_MINOR * 1000 + 10#$VERSION_PATCH))
  (( VERSION_CODE > 0 )) || VERSION_CODE=1
fi
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] && (( VERSION_CODE <= 2100000000 )) || {
  echo "error: version code must be an integer from 1 through 2100000000" >&2
  exit 2
}

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
command -v java >/dev/null 2>&1 || { echo "error: JDK 17-21 is required" >&2; exit 1; }
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$SDK_ROOT" && -d "$SDK_ROOT" ]] || { echo "error: Android SDK not found" >&2; exit 1; }

k4a_resolve_repository_urls "$ROOT"
[[ -n "${K4A_REPOSITORY_URL:-}" ]] || {
  echo "error: set K4A_REPOSITORY_URL or configure a GitHub origin remote" >&2
  exit 1
}
[[ -n "${K4A_INSTALLER_URL:-}" ]] || {
  echo "error: set K4A_INSTALLER_URL" >&2
  exit 1
}

SIGNING_NAMES=(
  K4A_SIGNING_STORE_FILE K4A_SIGNING_STORE_PASSWORD
  K4A_SIGNING_KEY_ALIAS K4A_SIGNING_KEY_PASSWORD
)
GRADLE_ARGS=(
  --no-daemon
  "-Pk4aVersionName=$VERSION"
  "-Pk4aVersionCode=$VERSION_CODE"
  "-Pk4aInstallerUrl=$K4A_INSTALLER_URL"
  "-Pk4aRepositoryUrl=$K4A_REPOSITORY_URL"
)
[[ -n "${K4A_TERMUX_DOWNLOAD_URL:-}" ]] && GRADLE_ARGS+=("-Pk4aTermuxDownloadUrl=$K4A_TERMUX_DOWNLOAD_URL")
[[ -n "${K4A_TERMUX_GITHUB_RELEASES_URL:-}" ]] && GRADLE_ARGS+=("-Pk4aTermuxGithubReleasesUrl=$K4A_TERMUX_GITHUB_RELEASES_URL")
if (( TEST_SIGNING )); then
  GRADLE_ARGS+=("-Pk4aUseDebugSigning=true")
else
  for name in "${SIGNING_NAMES[@]}"; do
    [[ -n "${!name:-}" ]] || {
      echo "error: $name is required, or pass --test-signing" >&2
      exit 1
    }
  done
  [[ -f "$K4A_SIGNING_STORE_FILE" ]] || {
    echo "error: signing store does not exist: $K4A_SIGNING_STORE_FILE" >&2
    exit 1
  }
  SIGNING_STORE_DIR="$(CDPATH= cd -- "$(dirname -- "$K4A_SIGNING_STORE_FILE")" && pwd)"
  K4A_SIGNING_STORE_FILE="$SIGNING_STORE_DIR/$(basename "$K4A_SIGNING_STORE_FILE")"
  export K4A_SIGNING_STORE_FILE
fi

cd "$ROOT"
./gradlew "${GRADLE_ARGS[@]}" \
  :android:app:testDebugUnitTest \
  :android:app:lintRelease \
  :android:app:assembleRelease

SOURCE_APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
[[ -f "$SOURCE_APK" ]] || { echo "error: signed release APK was not produced" >&2; exit 1; }
OUTPUT_DIR="$ROOT/dist"
OUTPUT_APK="$OUTPUT_DIR/klipper-usb-bridge-$VERSION-universal.apk"
mkdir -p "$OUTPUT_DIR"
cp "$SOURCE_APK" "$OUTPUT_APK"

APKSIGNER=""
while IFS= read -r candidate; do APKSIGNER="$candidate"; done < <(
  find "$SDK_ROOT/build-tools" -name apksigner -type f | sort
)
[[ -x "$APKSIGNER" ]] || { echo "error: apksigner was not found in the Android SDK" >&2; exit 1; }
"$APKSIGNER" verify --verbose --print-certs "$OUTPUT_APK"

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUTPUT_DIR" && sha256sum "$(basename "$OUTPUT_APK")") >"$OUTPUT_APK.sha256"
else
  (cd "$OUTPUT_DIR" && shasum -a 256 "$(basename "$OUTPUT_APK")") >"$OUTPUT_APK.sha256"
fi

printf '\nRelease artifacts:\n  %s\n  %s\n' "$OUTPUT_APK" "$OUTPUT_APK.sha256"
if (( TEST_SIGNING )); then
  echo "WARNING: APK uses debug signing; publish only as a test prerelease."
fi
