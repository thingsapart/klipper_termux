#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

bash -n "$ROOT/installer/install.sh"
for script in "$ROOT"/installer/services/*.run "$ROOT/installer/kabctl" \
  "$ROOT/installer/klipper-android-runner" "$ROOT/bridge/build-termux.sh" \
  "$ROOT/scripts/build-app.sh" "$ROOT/scripts/build-and-upload-app.sh" \
  "$ROOT/scripts/prepare-release.sh" "$ROOT/scripts/repository-env.sh"; do
  bash -n "$script"
done

(
  resolver_root="$(mktemp -d "${TMPDIR:-/tmp}/kab-repository-test.XXXXXX")"
  trap 'rm -rf "$resolver_root"' EXIT
  git -C "$resolver_root" init -q
  git -C "$resolver_root" remote add origin git@github.com:example-owner/example-repo.git
  unset KAB_REPOSITORY_URL KAB_INSTALLER_URL
  # shellcheck source=../../scripts/repository-env.sh
  source "$ROOT/scripts/repository-env.sh"
  kab_resolve_repository_urls "$resolver_root"
  [[ "$KAB_REPOSITORY_URL" == "https://github.com/example-owner/example-repo.git" ]]
  [[ "$KAB_INSTALLER_URL" == "https://raw.githubusercontent.com/example-owner/example-repo/main/installer/install.sh" ]]
)
rendered_runner="$(sed \
  -e 's|@SERVICES@|klipper-android-bridge klipper moonraker klipper-web|g' \
  -e 's|@PREFIX@|/data/data/com.termux/files/usr|g' \
  "$ROOT/installer/klipper-android-runner")"
bash -n - <<<"$rendered_runner"
if grep -q '@SERVICES@\|@PREFIX@' <<<"$rendered_runner"; then
  echo "runner contains an unrendered template value" >&2
  exit 1
fi

output="$(PREFIX=/not/termux HOME=/tmp/kab-installer-test \
  bash "$ROOT/installer/install.sh" --dry-run --source-dir "$ROOT" --klipper-only)"
grep -q 'Building native PTY bridge' <<<"$output"
grep -q 'Installing native Klipper' <<<"$output"
grep -q 'Installation complete' <<<"$output"
grep -q 'klipper-android-runner' <<<"$output"
grep -q 'allow-external-apps = true' <<<"$output"
if grep -q 'Installing native Moonraker' <<<"$output"; then
  echo "--klipper-only unexpectedly installed Moonraker" >&2
  exit 1
fi
echo "test_installer: ok"
