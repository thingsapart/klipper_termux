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

rendered_kabctl="$(sed \
  -e 's|@SERVICES@|klipper-android-bridge klipper moonraker klipper-web|g' \
  -e 's|@PREFIX@|/data/data/com.termux/files/usr|g' \
  -e 's|@HOME@|/data/data/com.termux/files/home|g' \
  -e 's|@DATA_DIR@|/data/data/com.termux/files/home/printer_data|g' \
  -e 's|@WEB_PORT@|8080|g' \
  "$ROOT/installer/kabctl")"
bash -n - <<<"$rendered_kabctl"
if grep -q '@SERVICES@\|@PREFIX@\|@HOME@\|@DATA_DIR@\|@WEB_PORT@' <<<"$rendered_kabctl"; then
  echo "kabctl contains an unrendered template value" >&2
  exit 1
fi
doctor_output="$(NO_COLOR=1 bash -c "$rendered_kabctl" kabctl doctor 2>&1 || true)"
grep -Fq '[FAIL] native bridge executable' <<<"$doctor_output"
grep -Fq '[FAIL] doctor found' <<<"$doctor_output"

output="$(PREFIX=/not/termux HOME=/tmp/kab-installer-test \
  bash "$ROOT/installer/install.sh" --dry-run --source-dir "$ROOT" --klipper-only)"
grep -q 'Building native PTY bridge' <<<"$output"
grep -q 'Installing native Klipper' <<<"$output"
grep -q 'Installation complete' <<<"$output"
grep -q 'klipper-android-runner' <<<"$output"
grep -q 'ln -sfn /tmp/kab-installer-test/.local/bin/kabctl /not/termux/bin/kabctl' <<<"$output"
grep -q 'allow-external-apps = true' <<<"$output"
grep -q 'fetch --depth=1 --no-tags origin' <<<"$output"
grep -q 'pip install --no-cache-dir' <<<"$output"
if grep -q 'Installing native Moonraker' <<<"$output"; then
  echo "--klipper-only unexpectedly installed Moonraker" >&2
  exit 1
fi

reinstall_home="$(mktemp -d "${TMPDIR:-/tmp}/kab-reinstall-test.XXXXXX")"
trap 'rm -rf "$reinstall_home"' EXIT
mkdir -p "$reinstall_home/printer_data/config"
touch "$reinstall_home/printer_data/config/printer.cfg"
if PREFIX=/not/termux HOME="$reinstall_home" \
  bash "$ROOT/installer/install.sh" --dry-run --source-dir "$ROOT" \
    --klipper-only --non-interactive >/dev/null 2>&1; then
  echo "non-interactive reinstall unexpectedly accepted deletion" >&2
  exit 1
fi
reinstall_output="$(PREFIX=/not/termux HOME="$reinstall_home" \
  bash "$ROOT/installer/install.sh" --dry-run --source-dir "$ROOT" \
    --klipper-only --reinstall)"
grep -q 'Stopping and removing the existing managed installation' <<<"$reinstall_output"
grep -q "rm -rf -- $reinstall_home/printer_data" <<<"$reinstall_output"
update_output="$(PREFIX=/not/termux HOME="$reinstall_home" \
  bash "$ROOT/installer/install.sh" --dry-run --source-dir "$ROOT" \
    --klipper-only --update)"
grep -q 'printer data and configuration will be preserved' <<<"$update_output"
if grep -q "rm -rf -- $reinstall_home/printer_data" <<<"$update_output"; then
  echo "update mode attempted to remove printer data" >&2
  exit 1
fi
echo "test_installer: ok"
