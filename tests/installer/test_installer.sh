#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

bash -n "$ROOT/installer/install.sh"
for script in "$ROOT"/installer/services/*.run "$ROOT/installer/klctl" \
  "$ROOT/installer/klipper-android-runner" "$ROOT/bridge/build-termux.sh" \
  "$ROOT/scripts/build-app.sh" "$ROOT/scripts/build-and-upload-app.sh" \
  "$ROOT/scripts/prepare-release.sh" "$ROOT/scripts/repository-env.sh"; do
  bash -n "$script"
done

(
  resolver_root="$(mktemp -d "${TMPDIR:-/tmp}/k4a-repository-test.XXXXXX")"
  trap 'rm -rf "$resolver_root"' EXIT
  git -C "$resolver_root" init -q
  git -C "$resolver_root" remote add origin git@github.com:example-owner/example-repo.git
  unset K4A_REPOSITORY_URL K4A_INSTALLER_URL
  # shellcheck source=../../scripts/repository-env.sh
  source "$ROOT/scripts/repository-env.sh"
  k4a_resolve_repository_urls "$resolver_root"
  [[ "$K4A_REPOSITORY_URL" == "https://github.com/example-owner/example-repo.git" ]]
  [[ "$K4A_INSTALLER_URL" == "https://raw.githubusercontent.com/example-owner/example-repo/main/installer/install.sh" ]]
)
rendered_runner="$(sed \
  -e 's|@SERVICES@|klipper-android-bridge klipper moonraker klipper-web|g' \
  -e 's|@PREFIX@|/data/data/com.termux/files/usr|g' \
  -e 's|@HOME@|/data/data/com.termux/files/home|g' \
  "$ROOT/installer/klipper-android-runner")"
bash -n - <<<"$rendered_runner"
if grep -q '@SERVICES@\|@PREFIX@\|@HOME@' <<<"$rendered_runner"; then
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

moonraker_wrapper_home="$(mktemp -d "${TMPDIR:-/tmp}/k4a-moonraker-wrapper.XXXXXX")"
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

wrapper_home="$(mktemp -d "${TMPDIR:-/tmp}/k4a-klippy-wrapper.XXXXXX")"
mkdir -p "$wrapper_home/klipper/klippy/chelper" "$wrapper_home/python-hooks"
sed -e "s|@HOME@|$wrapper_home|g" \
  -e "s|@PREFIX@|$wrapper_home/usr|g" \
  "$ROOT/installer/klippy-android.py" >"$wrapper_home/klippy-android.py"
cat >"$wrapper_home/python-hooks/sitecustomize.py" <<'PY'
import os
def denied_chmod(path, mode, *args, **kwargs):
    raise PermissionError(path)
os.chmod = denied_chmod
if hasattr(os, "getloadavg"):
    del os.getloadavg
PY
cat >"$wrapper_home/python-hooks/serial.py" <<'PY'
class Serial:
    def __init__(self, port=None, exclusive=None, **kwargs):
        self.port = port
        self._exclusive = exclusive
    def open(self):
        if "/var/run/klipper-android/" in self.port:
            assert self._exclusive is None
        else:
            assert self._exclusive is True
        self._set_special_baudrate(250000)
        self._update_dtr_state()
        self._update_rts_state()
        return None
    def _set_special_baudrate(self, baudrate):
        self.configured_baudrate = baudrate
    def _update_dtr_state(self):
        self.configured_dtr = True
    def _update_rts_state(self):
        self.configured_rts = True
PY
cat >"$wrapper_home/klipper/klippy/chelper/__init__.py" <<'PY'
import os
COMPILE_ARGS = "-shared -o %s %s"
def check_build_c_library():
    assert COMPILE_ARGS.endswith(" -lm")
    return os.path.join(os.path.dirname(__file__), "c_helper.so")
PY
cat >"$wrapper_home/klipper/klippy/klippy.py" <<'PY'
import os
import chelper
import serial
load_average = os.getloadavg()
assert len(load_average) == 3
assert all(isinstance(value, float) for value in load_average)
chelper.check_build_c_library()
os.chmod("/dev/pts/123", 0o660)
bridge = serial.Serial(exclusive=True)
bridge.port = os.path.join(os.environ["WRAPPER_PREFIX"], "var/run/klipper-android/main")
bridge.open()
assert not hasattr(bridge, "configured_baudrate")
assert not hasattr(bridge, "configured_dtr")
assert not hasattr(bridge, "configured_rts")
real = serial.Serial(port="/dev/ttyUSB0", exclusive=True)
real.open()
assert real.configured_baudrate == 250000
assert real.configured_dtr and real.configured_rts
print("android PTY chmod ignored")
PY
WRAPPER_PREFIX="$wrapper_home/usr" PYTHONPATH="$wrapper_home/python-hooks" \
  python3 "$wrapper_home/klippy-android.py" \
  | grep -q 'android PTY chmod ignored'
grep -q -- '-lm' "$wrapper_home/.local/state/klipper-android/c-helper-linked-libm"
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

rendered_klctl="$(sed \
  -e 's|@SERVICES@|klipper-android-bridge klipper moonraker klipper-web|g' \
  -e 's|@PREFIX@|/data/data/com.termux/files/usr|g' \
  -e 's|@HOME@|/data/data/com.termux/files/home|g' \
  -e 's|@DATA_DIR@|/data/data/com.termux/files/home/printer_data|g' \
  -e 's|@WEB_PORT@|8080|g' \
  "$ROOT/installer/klctl")"
bash -n - <<<"$rendered_klctl"
if grep -q '@SERVICES@\|@PREFIX@\|@HOME@\|@DATA_DIR@\|@WEB_PORT@' <<<"$rendered_klctl"; then
  echo "klctl contains an unrendered template value" >&2
  exit 1
fi
doctor_output="$(NO_COLOR=1 bash -c "$rendered_klctl" klctl doctor 2>&1 || true)"
grep -Fq '[FAIL] native bridge executable' <<<"$doctor_output"
grep -Fq '[FAIL] Klipper Android launcher' <<<"$doctor_output"
grep -Fq '[FAIL] doctor found' <<<"$doctor_output"

output="$(PREFIX=/not/termux HOME=/tmp/k4a-installer-test \
  bash "$ROOT/installer/install.sh" --dry-run --source-dir "$ROOT" --klipper-only)"
grep -q 'Building native PTY bridge' <<<"$output"
grep -q 'Installing native Klipper' <<<"$output"
grep -q 'Installation complete' <<<"$output"
grep -q 'klipper-android-runner' <<<"$output"
grep -q 'ssh-autostart' "$ROOT/installer/klctl"
grep -q 'var/service/sshd/down' "$ROOT/installer/klipper-android-runner"
grep -q 'ln -sfn /tmp/k4a-installer-test/.local/bin/klctl /not/termux/bin/klctl' <<<"$output"
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
grep -q '^\[zeroconf\]$' "$ROOT/installer/config/moonraker.conf"
grep -q '^mdns_hostname: klipper-android$' "$ROOT/installer/config/moonraker.conf"
grep -Fq 'proxy_set_header Host $http_host;' "$ROOT/installer/config/nginx.conf"
grep -q '^device=main,auto,250000,8,1,none,dtr+rts,' \
  "$ROOT/installer/config/bridge.conf.example"
grep -q 'migrate legacy bridge flags to dtr+rts' "$ROOT/installer/install.sh"
grep -q 'migrate generated bridge selector from offline to auto' "$ROOT/installer/install.sh"
grep -q 'Repairing previously configured SSH service' "$ROOT/installer/install.sh"
grep -q '"\$SSH_SETUP_MARKER" -nt "\$SSH_DOWN_FILE"' "$ROOT/installer/install.sh"
migrated_bridge_line="$(printf '%s\n' \
  'device=main,offline,250000,8,1,none,dtr+rts,/tmp/main' | \
  sed -E 's#^(device=main,)offline(,250000,8,1,none,(none|dtr\+rts),.*)$#\1auto\2#')"
[[ "$migrated_bridge_line" == \
  'device=main,auto,250000,8,1,none,dtr+rts,/tmp/main' ]] || {
  echo "offline-to-auto bridge migration failed: $migrated_bridge_line" >&2
  exit 1
}

stdin_home="$(mktemp -d "${TMPDIR:-/tmp}/k4a-stdin-test.XXXXXX")"
PREFIX=/not/termux HOME="$stdin_home" \
  bash -s -- --dry-run --source-dir "$ROOT" --klipper-only \
  <"$ROOT/installer/install.sh" >/dev/null
rm -rf "$stdin_home"

migration_home="$(mktemp -d "${TMPDIR:-/tmp}/k4a-config-migration-test.XXXXXX")"
mkdir -p "$migration_home/printer_data/config/kab"
printf '[include kab/current.cfg]\n' >"$migration_home/printer_data/config/printer.cfg"
printf '# legacy managed config\n' >"$migration_home/printer_data/config/kab/current.cfg"
migration_output="$(PREFIX=/not/termux HOME="$migration_home" \
  bash "$ROOT/installer/install.sh" --dry-run --update --source-dir "$ROOT" --klipper-only)"
grep -q 'Migrating managed configuration from KAB to K4A' <<<"$migration_output"
grep -q 'rewrite managed KAB include' <<<"$migration_output"
rm -rf "$migration_home"

reinstall_home="$(mktemp -d "${TMPDIR:-/tmp}/k4a-reinstall-test.XXXXXX")"
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
grep -q 'iproute2' <<<"$full_update_output"
grep -q 'config/nginx.conf.*printer_data/config/nginx.conf' <<<"$full_update_output"
grep -q 'Mainsail archive did not contain index.html' "$ROOT/installer/install.sh"
grep -q 'restart-after-update' "$ROOT/installer/install.sh"
grep -q 'installer.log' "$ROOT/installer/install.sh"
if grep -q '(( is_new )) &&' "$ROOT/installer/install.sh"; then
  echo "install_service still returns failure for an existing service" >&2
  exit 1
fi

hostname_home="$(mktemp -d "${TMPDIR:-/tmp}/k4a-hostname-test.XXXXXX")"
mkdir -p "$hostname_home/printer_data/config"
cp "$ROOT/installer/config/moonraker.conf" "$hostname_home/printer_data/config/moonraker.conf"
sed \
  -e 's|@PREFIX@|/not/termux|g' \
  -e "s|@DATA_DIR@|$hostname_home/printer_data|g" \
  "$ROOT/installer/config/printer.cfg.example" \
  >"$hostname_home/printer_data/config/printer.cfg.example"
hostname_klctl="$(sed \
  -e 's|@SERVICES@|klipper-android-bridge klipper moonraker klipper-web|g' \
  -e 's|@PREFIX@|/not/termux|g' \
  -e "s|@HOME@|$hostname_home|g" \
  -e "s|@DATA_DIR@|$hostname_home/printer_data|g" \
  -e 's|@WEB_PORT@|8080|g' \
  "$ROOT/installer/klctl")"
bash -c "$hostname_klctl" klctl hostname workshop-printer >/dev/null
grep -q '^mdns_hostname: workshop-printer$' \
  "$hostname_home/printer_data/config/moonraker.conf"
if bash -c "$hostname_klctl" klctl hostname invalid.name >/dev/null 2>&1; then
  echo "klctl accepted an invalid mDNS hostname" >&2
  exit 1
fi
bash -c "$hostname_klctl" klctl printer-starter >/dev/null
grep -q '^# Managed starter configuration for Klipper Android' \
  "$hostname_home/printer_data/config/printer.cfg"
grep -q '^\[virtual_sdcard\]$' "$hostname_home/printer_data/config/printer.cfg"
printf '[printer]\nkinematics: none\n# user-owned\n' \
  >"$hostname_home/printer_data/config/printer.cfg"
bash -c "$hostname_klctl" klctl printer-starter >/dev/null
grep -q '^# user-owned$' "$hostname_home/printer_data/config/printer.cfg"
grep -q 'pkg install -y openssh' "$ROOT/installer/klctl"
grep -q 'Port 2020' "$ROOT/installer/klctl"
grep -q '^[[:space:]]*passwd$' "$ROOT/installer/klctl"
grep -q 'SVDIR="\$PREFIX/var/service" sv-enable sshd' "$ROOT/installer/klctl"
grep -q '/dev/tcp/127.0.0.1/2020' "$ROOT/installer/klctl"
grep -q '"\$PREFIX/bin/timeout" 3' "$ROOT/installer/klctl"
if grep -q 'telnet://127.0.0.1:2020' "$ROOT/installer/klctl"; then
  echo "klctl doctor still uses curl's potentially hanging telnet handler" >&2
  exit 1
fi
if grep -q 'ss -ltn' "$ROOT/installer/klctl"; then
  echo "klctl doctor still relies on Android-restricted socket diagnostics" >&2
  exit 1
fi

ssh_test_root="$(mktemp -d "${TMPDIR:-/tmp}/k4a-ssh-service-test.XXXXXX")"
mkdir -p "$ssh_test_root/prefix/bin" "$ssh_test_root/prefix/var/service/sshd" \
  "$ssh_test_root/home" "$ssh_test_root/fakebin"
ssh_state="$ssh_test_root/ssh-listening"
cat >"$ssh_test_root/fakebin/service-daemon" <<'SH'
#!/usr/bin/env bash
exit 0
SH
cat >"$ssh_test_root/fakebin/sv" <<'SH'
#!/usr/bin/env bash
while [[ "${1:-}" == -* ]]; do shift; [[ "${1:-}" =~ ^[0-9]+$ ]] && shift; done
case "${1:-}" in
  up|once|restart) touch "$SSH_TEST_STATE" ;;
  down) rm -f "$SSH_TEST_STATE" ;;
esac
SH
cat >"$ssh_test_root/prefix/bin/timeout" <<'SH'
#!/usr/bin/env bash
[[ -e "$SSH_TEST_STATE" ]] && printf 'SSH-2.0-test\r\n'
SH
chmod +x "$ssh_test_root/fakebin/service-daemon" "$ssh_test_root/fakebin/sv" \
  "$ssh_test_root/prefix/bin/timeout"
ssh_klctl="$ssh_test_root/klctl"
sed -e 's|@SERVICES@|klipper-android-bridge klipper|g' \
  -e "s|@PREFIX@|$ssh_test_root/prefix|g" \
  -e "s|@HOME@|$ssh_test_root/home|g" \
  -e "s|@DATA_DIR@|$ssh_test_root/home/printer_data|g" \
  -e 's|@WEB_PORT@|8080|g' "$ROOT/installer/klctl" >"$ssh_klctl"
PATH="$ssh_test_root/fakebin:$PATH" SSH_TEST_STATE="$ssh_state" \
  bash "$ssh_klctl" ssh-start auto
[[ -e "$ssh_state" ]]
[[ -e "$ssh_test_root/home/.local/state/klipper-android/ssh-autostart" ]]
PATH="$ssh_test_root/fakebin:$PATH" SSH_TEST_STATE="$ssh_state" \
  bash "$ssh_klctl" ssh-stop
[[ ! -e "$ssh_state" ]]
[[ -e "$ssh_test_root/prefix/var/service/sshd/down" ]]
[[ -e "$ssh_test_root/home/.local/state/klipper-android/ssh-autostart" ]]
rm -rf "$ssh_test_root"
grep -q 'starter-config-installed' "$ROOT/installer/install.sh"
grep -q 'A real printer.cfg is user data and must never be replaced by UPDATE' \
  "$ROOT/installer/install.sh"
rm -rf "$hostname_home"
echo "test_installer: ok"
