#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf -- "$TMP"' EXIT
HOME_DIR="$TMP/home"
PREFIX="$TMP/prefix"
DATA_DIR="$HOME_DIR/printer_data"
LIB_DIR="$HOME_DIR/.local/lib/k4a/firmware"
FAKE_BIN="$TMP/bin"
mkdir -p "$LIB_DIR" "$FAKE_BIN" "$HOME_DIR/klipper" "$PREFIX/var/service" "$DATA_DIR/gcodes"
cp -R "$ROOT/installer/firmware/configs" "$LIB_DIR/"
cp "$ROOT/installer/firmware/profiles.tsv" "$LIB_DIR/"
touch "$HOME_DIR/klipper/Makefile"
mkdir "$HOME_DIR/klipper/.git"

cat >"$FAKE_BIN/arm-none-eabi-gcc" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
  --version) echo 'arm-none-eabi-gcc fake 1.0' ;;
  -dumpfullversion) echo '1.0' ;;
esac
EOF
cat >"$FAKE_BIN/git" <<'EOF'
#!/usr/bin/env bash
echo 0123456789abcdef0123456789abcdef01234567
EOF
cat >"$FAKE_BIN/curl" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
cat >"$FAKE_BIN/make" <<'EOF'
#!/usr/bin/env bash
out=''
for arg in "$@"; do [[ "$arg" == OUT=* ]] && out=${arg#OUT=}; done
[[ -n "$out" ]] || exit 2
if [[ " $* " != *' olddefconfig '* ]]; then
  if grep -q CONFIG_MACH_RP2040=y "$out/.config"; then
    printf 'uf2' >"$out/klipper.uf2"
  else
    printf 'bin' >"$out/klipper.bin"
  fi
  printf 'dict' >"$out/klipper.dict"
fi
EOF
cat >"$FAKE_BIN/sha256sum" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == -c ]]; then
  shift
  exec shasum -a 256 -c "$@"
fi
exec shasum -a 256 "$@"
EOF
chmod +x "$FAKE_BIN"/*

export PATH="$FAKE_BIN:/usr/bin:/bin"
export K4A_HOME_DIR="$HOME_DIR"
export K4A_PREFIX="$PREFIX"
export K4A_DATA_DIR="$DATA_DIR"
export K4A_WEB_PORT=8080
export K4A_KLIPPER_DIR="$HOME_DIR/klipper"
export K4A_FIRMWARE_LIB="$LIB_DIR"
MANAGER="$ROOT/installer/firmware/firmware-manager.sh"

profiles=$(/bin/bash "$MANAGER" profiles)
grep -q btt-octopus-f446-v1 <<<"$profiles"
grep -q btt-skr-pico-v1 <<<"$profiles"
profiles_machine=$(/bin/bash "$MANAGER" profiles-machine)
grep -q '^btt-skr-mini-e3-v3|BTT SKR Mini E3|' <<<"$profiles_machine"
storage_machine=$(/bin/bash "$MANAGER" storage-machine)
grep -q '^web|Mainsail / web download||1$' <<<"$storage_machine"
grep -q '^share|Android share…|share|1$' <<<"$storage_machine"

info=$(/bin/bash "$MANAGER" info btt-octopus-pro-h723-v11)
grep -q 'STM32H723' <<<"$info"
grep -q 'CONFIG_STM32_FLASH_START_20000=y' <<<"$info"

output=$(/bin/bash "$MANAGER" build btt-skr-mini-e3-v3)
build_id=$(sed -n 's/^Build complete: //p' <<<"$output")
[[ -n "$build_id" ]]
[[ -f "$DATA_DIR/gcodes/firmware/$build_id/firmware.bin" ]]
[[ -f "$DATA_DIR/gcodes/firmware/index.html" ]]
grep -q "$build_id" "$DATA_DIR/gcodes/firmware/index.html"

destination="$TMP/card"
/bin/bash "$MANAGER" export "$build_id" "$destination"
[[ $(<"$destination/firmware.bin") == bin ]]
(cd "$destination" && shasum -a 256 -c SHA256SUMS >/dev/null)

if /bin/bash "$MANAGER" info '../../bad' >/dev/null 2>&1; then
  echo 'unsafe profile id was accepted' >&2
  exit 1
fi

echo 'firmware manager tests passed'
