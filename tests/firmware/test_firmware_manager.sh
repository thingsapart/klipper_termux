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
mkdir -p "$PREFIX/bin"
ln -s /bin/bash "$PREFIX/bin/bash"
cp -R "$ROOT/installer/firmware/configs" "$LIB_DIR/"
cp "$ROOT/installer/firmware/profiles.tsv" "$LIB_DIR/"
cp "$ROOT/installer/firmware/extract_toolchain.py" "$LIB_DIR/"
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
if [[ -n "${K4A_TEST_ARCHIVE:-}" ]]; then
  destination=''
  while (($#)); do
    if [[ "$1" == -o ]]; then shift; destination="$1"; fi
    shift
  done
  [[ -n "$destination" ]] || exit 2
  cp -- "$K4A_TEST_ARCHIVE" "$destination"
  exit
fi
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
cat >"$FAKE_BIN/apt-cache" <<'EOF'
#!/usr/bin/env bash
[[ -z "${K4A_TEST_NO_APT:-}" ]] || exit 1
[[ "${1:-}" == show && "${2:-}" == gcc-arm-none-eabi ]]
EOF
cat >"$FAKE_BIN/pkg" <<'EOF'
#!/usr/bin/env bash
[[ "${1:-}" == install && "${2:-}" == -y ]] || exit 1
if [[ "${3:-}" == gcc-arm-none-eabi ]]; then
  mv -- "$FAKE_BIN/arm-none-eabi-gcc.pending" "$FAKE_BIN/arm-none-eabi-gcc"
fi
EOF
cat >"$FAKE_BIN/grun" <<'EOF'
#!/usr/bin/env bash
[[ "${1:-}" != -f ]] || exit 64
exec "$@"
EOF
cat >"$FAKE_BIN/file" <<'EOF'
#!/usr/bin/env bash
case "${2:-}" in
  */arm-none-eabi-*) printf 'ELF 64-bit LSB pie executable, ARM aarch64\n' ;;
  *) exec /usr/bin/file "$@" ;;
esac
EOF
cat >"$FAKE_BIN/glibc-runner" <<'EOF'
#!/usr/bin/env bash
[[ "${1:-}" == -c && -f "${2:-}" ]] || exit 64
printf '%s\n' "$2" >>"$K4A_TEST_CONFIG_LOG"
EOF
cat >"$FAKE_BIN/python" <<'EOF'
#!/usr/bin/env bash
exec /usr/bin/python3 "$@"
EOF
chmod +x "$FAKE_BIN"/*

export PATH="$FAKE_BIN:/usr/bin:/bin"
export FAKE_BIN
export K4A_TEST_CONFIG_LOG="$TMP/glibc-configured.log"
export K4A_HOME_DIR="$HOME_DIR"
export K4A_PREFIX="$PREFIX"
export K4A_DATA_DIR="$DATA_DIR"
export K4A_WEB_PORT=8080
export K4A_KLIPPER_DIR="$HOME_DIR/klipper"
export K4A_FIRMWARE_LIB="$LIB_DIR"
MANAGER="$ROOT/installer/firmware/firmware-manager.sh"
grep -q '87330bab085dd8749d4ed0ad633674b9dc48b237b61069e3b481abd364d0a684' "$MANAGER"

mkdir -p "$TMP/archive/toolchain/bin" "$TMP/extracted"
printf binary >"$TMP/archive/toolchain/bin/ld.bfd"
ln "$TMP/archive/toolchain/bin/ld.bfd" "$TMP/archive/toolchain/bin/arm-none-eabi-ld"
tar -cJf "$TMP/toolchain.tar.xz" -C "$TMP/archive" toolchain
python3 "$LIB_DIR/extract_toolchain.py" "$TMP/toolchain.tar.xz" "$TMP/extracted"
[[ $(<"$TMP/extracted/bin/ld.bfd") == binary ]]
[[ $(<"$TMP/extracted/bin/arm-none-eabi-ld") == binary ]]
[[ "$TMP/extracted/bin/ld.bfd" -ef "$TMP/extracted/bin/arm-none-eabi-ld" ]] && {
  echo 'extractor recreated a hard link instead of copying its contents' >&2
  exit 1
}

# The downloaded-toolchain path must create a first-install destination and its
# cleanup trap must retain the function-local staging path until shell exit.
mkdir -p "$TMP/minimal/toolchain/bin"
for tool in gcc as ld objcopy objdump strip; do
  cp "$FAKE_BIN/arm-none-eabi-gcc" "$TMP/minimal/toolchain/bin/arm-none-eabi-$tool"
done
tar -cJf "$TMP/minimal-toolchain.tar.xz" -C "$TMP/minimal" toolchain
minimal_checksum=$(/usr/bin/shasum -a 256 "$TMP/minimal-toolchain.tar.xz" | awk '{print $1}')
cat >"$FAKE_BIN/uname" <<'EOF'
#!/usr/bin/env bash
[[ "${1:-}" == -m ]] && { printf 'aarch64\n'; exit; }
exec /usr/bin/uname "$@"
EOF
chmod +x "$FAKE_BIN/uname"
mv "$FAKE_BIN/arm-none-eabi-gcc" "$FAKE_BIN/arm-none-eabi-gcc.pending"
fallback_output=$(K4A_TEST_NO_APT=1 K4A_TEST_ARCHIVE="$TMP/minimal-toolchain.tar.xz" \
  K4A_ARM_TOOLCHAIN_AARCH64_URL=https://example.invalid/minimal-toolchain.tar.xz \
  K4A_ARM_TOOLCHAIN_AARCH64_SHA256="$minimal_checksum" \
  /bin/bash "$MANAGER" toolchain-install)
grep -q 'arm-none-eabi-gcc fake 1.0' <<<"$fallback_output"
[[ -x "$HOME_DIR/.local/opt/k4a-arm-toolchain/bin/arm-none-eabi-gcc" ]]
grep -q '/arm-none-eabi-gcc$' "$K4A_TEST_CONFIG_LOG"
mv "$HOME_DIR/.local/opt/k4a-arm-toolchain" "$HOME_DIR/.cache/k4a-toolchain.resumable"
# Model wrappers generated by the broken release.  The updater must restore
# their real executables before configuring the toolchain in place.
for tool in gcc as ld objcopy objdump strip; do
  wrapper="$HOME_DIR/.cache/k4a-toolchain.resumable/bin/arm-none-eabi-$tool"
  mv "$wrapper" "$wrapper.k4a-real"
  printf '#!%s/bin/bash\nexec grun -f %q "$@"\n' "$PREFIX" "$wrapper.k4a-real" >"$wrapper"
  chmod +x "$wrapper"
done
resume_output=$(K4A_TEST_NO_APT=1 K4A_TEST_ARCHIVE="$TMP/minimal-toolchain.tar.xz" \
  K4A_ARM_TOOLCHAIN_AARCH64_URL=https://example.invalid/minimal-toolchain.tar.xz \
  K4A_ARM_TOOLCHAIN_AARCH64_SHA256="$minimal_checksum" \
  /bin/bash "$MANAGER" toolchain-install)
grep -q 'Reusing a complete toolchain' <<<"$resume_output"
[[ -x "$HOME_DIR/.local/opt/k4a-arm-toolchain/bin/arm-none-eabi-gcc" ]]
[[ ! -e "$HOME_DIR/.local/opt/k4a-arm-toolchain/bin/arm-none-eabi-gcc.k4a-real" ]]
! grep -q 'exec grun ' "$HOME_DIR/.local/opt/k4a-arm-toolchain/bin/arm-none-eabi-gcc"
[[ ! -d "$HOME_DIR/.cache/k4a-toolchain.resumable" ]]
rm -rf -- "$HOME_DIR/.local/opt/k4a-arm-toolchain"
mv "$FAKE_BIN/arm-none-eabi-gcc.pending" "$FAKE_BIN/arm-none-eabi-gcc"
rm -f -- "$FAKE_BIN/uname"

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

mv "$FAKE_BIN/arm-none-eabi-gcc" "$FAKE_BIN/arm-none-eabi-gcc.pending"
output=$(/bin/bash "$MANAGER" build btt-skr-mini-e3-v3)
[[ -x "$FAKE_BIN/arm-none-eabi-gcc" ]]
build_id=$(sed -n 's/^Build complete: //p' <<<"$output")
[[ -n "$build_id" ]]
[[ -f "$DATA_DIR/gcodes/firmware/$build_id/firmware.bin" ]]
[[ -f "$DATA_DIR/gcodes/firmware/index.html" ]]
grep -q "$build_id" "$DATA_DIR/gcodes/firmware/index.html"

combined_output=$(/bin/bash "$MANAGER" build-export btt-skr-pico-v1 web)
grep -q '^Build complete: btt-skr-pico-v1-' <<<"$combined_output"
grep -q 'ready on the Mainsail firmware downloads page' <<<"$combined_output"

destination="$TMP/card"
/bin/bash "$MANAGER" export "$build_id" "$destination"
[[ $(<"$destination/firmware.bin") == bin ]]
(cd "$destination" && shasum -a 256 -c SHA256SUMS >/dev/null)

if /bin/bash "$MANAGER" info '../../bad' >/dev/null 2>&1; then
  echo 'unsafe profile id was accepted' >&2
  exit 1
fi

echo 'firmware manager tests passed'
