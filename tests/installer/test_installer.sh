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

rendered_moonraker_service="$(sed \
  -e 's|@HOME@|/data/data/com.termux/files/home|g' \
  -e 's|@DATA_DIR@|/data/data/com.termux/files/home/printer_data|g' \
  "$ROOT/installer/services/moonraker.run")"
sh -n - <<<"$rendered_moonraker_service"
grep -q 'PYTHONPATH="/data/data/com.termux/files/home/moonraker"' \
  <<<"$rendered_moonraker_service"
grep -q '/.local/bin/moonraker-android.py' <<<"$rendered_moonraker_service"

moonraker_wrapper_home="$(mktemp -d "${TMPDIR:-/tmp}/kab-moonraker-wrapper.XXXXXX")"
mkdir -p "$moonraker_wrapper_home/moonraker" "$moonraker_wrapper_home/python-hooks"
cp "$ROOT/installer/moonraker-android.py" "$moonraker_wrapper_home/moonraker-android.py"
cat >"$moonraker_wrapper_home/python-hooks/sitecustomize.py" <<'PY'
import os
def denied_scandir(path):
    raise PermissionError(path)
os.scandir = denied_scandir
PY
touch "$moonraker_wrapper_home/moonraker/__init__.py"
cat >"$moonraker_wrapper_home/moonraker/__main__.py" <<'PY'
import os
assert list(os.scandir("/sys/class/hwmon/")) == []
print("Android hwmon denial handled")
PY
PYTHONPATH="$moonraker_wrapper_home/python-hooks:$moonraker_wrapper_home" \
  python3 "$moonraker_wrapper_home/moonraker-android.py" \
  | grep -q 'Android hwmon denial handled'
cat >"$moonraker_wrapper_home/moonraker/__main__.py" <<'PY'
import os
list(os.scandir("/tmp/not-android-hwmon"))
PY
if PYTHONPATH="$moonraker_wrapper_home/python-hooks:$moonraker_wrapper_home" \
    python3 "$moonraker_wrapper_home/moonraker-android.py" >/dev/null 2>&1; then
  echo "Moonraker wrapper unexpectedly ignored an unrelated scandir failure" >&2
  exit 1
fi
rm -rf "$moonraker_wrapper_home"

wrapper_home="$(mktemp -d "${TMPDIR:-/tmp}/kab-klippy-wrapper.XXXXXX")"
mkdir -p "$wrapper_home/klipper/klippy" "$wrapper_home/python-hooks"
sed -e "s|@HOME@|$wrapper_home|g" \
  "$ROOT/installer/klippy-android.py" >"$wrapper_home/klippy-android.py"
cat >"$wrapper_home/python-hooks/sitecustomize.py" <<'PY'
import os
def denied_chmod(path, mode, *args, **kwargs):
    raise PermissionError(path)
os.chmod = denied_chmod
PY
cat >"$wrapper_home/klipper/klippy/klippy.py" <<'PY'
import os
os.chmod("/dev/pts/123", 0o660)
print("android PTY chmod ignored")
PY
PYTHONPATH="$wrapper_home/python-hooks" python3 "$wrapper_home/klippy-android.py" \
  | grep -q 'android PTY chmod ignored'
cat >"$wrapper_home/klipper/klippy/klippy.py" <<'PY'
import os
os.chmod("/tmp/not-an-android-pty", 0o660)
PY
if PYTHONPATH="$wrapper_home/python-hooks" python3 "$wrapper_home/klippy-android.py" \
    >/dev/null 2>&1; then
  echo "Klipper wrapper unexpectedly ignored a non-PTY chmod failure" >&2
  exit 1
fi
rm -rf "$wrapper_home"

rendered_klipper_service="$(sed \
  -e 's|@PREFIX@|/data/data/com.termux/files/usr|g' \
  -e 's|@HOME@|/data/data/com.termux/files/home|g' \
  -e 's|@DATA_DIR@|/data/data/com.termux/files/home/printer_data|g' \
  "$ROOT/installer/services/klipper.run")"
sh -n - <<<"$rendered_klipper_service"
grep -q '/.local/bin/klippy-android.py' <<<"$rendered_klipper_service"
grep -q '/var/run/klipper-android/klippy-gcode' <<<"$rendered_klipper_service"

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
grep -Fq '[FAIL] Klipper Android launcher' <<<"$doctor_output"
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
grep -q 'klippy-android.py' <<<"$output"
grep -q 'moonraker-android.py' "$ROOT/installer/install.sh"
if grep -q 'Installing native Moonraker' <<<"$output"; then
  echo "--klipper-only unexpectedly installed Moonraker" >&2
  exit 1
fi
grep -q '^provider: none$' "$ROOT/installer/config/moonraker.conf"
grep -q '^enable_config_write_access: True$' "$ROOT/installer/config/moonraker.conf"

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
full_update_output="$(PREFIX=/not/termux HOME="$reinstall_home" \
  bash "$ROOT/installer/install.sh" --dry-run --source-dir "$ROOT" --update)"
grep -q 'mainsail.zip.part' <<<"$full_update_output"
grep -q 'mainsail.new.' <<<"$full_update_output"
grep -q 'Mainsail archive did not contain index.html' "$ROOT/installer/install.sh"
grep -q 'restart-after-update' "$ROOT/installer/install.sh"
grep -q 'installer.log' "$ROOT/installer/install.sh"
if grep -q '(( is_new )) &&' "$ROOT/installer/install.sh"; then
  echo "install_service still returns failure for an existing service" >&2
  exit 1
fi
echo "test_installer: ok"
