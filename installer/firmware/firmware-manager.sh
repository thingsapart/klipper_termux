#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

HOME_DIR="${K4A_HOME_DIR:-@HOME@}"
PREFIX="${K4A_PREFIX:-@PREFIX@}"
DATA_DIR="${K4A_DATA_DIR:-@DATA_DIR@}"
WEB_PORT="${K4A_WEB_PORT:-@WEB_PORT@}"
KLIPPER_DIR="${K4A_KLIPPER_DIR:-$HOME_DIR/klipper}"
LIB_DIR="${K4A_FIRMWARE_LIB:-$HOME_DIR/.local/lib/k4a/firmware}"
PROFILE_FILE="$LIB_DIR/profiles.tsv"
BUILD_ROOT="${K4A_FIRMWARE_BUILD_ROOT:-$HOME_DIR/.cache/k4a/firmware}"
PUBLISH_ROOT="${K4A_FIRMWARE_PUBLISH_ROOT:-$DATA_DIR/gcodes/firmware}"
PTY="${K4A_MCU_PTY:-$PREFIX/var/run/klipper-android/main}"

die() { printf 'firmware: %s\n' "$*" >&2; exit 1; }
usage() {
  cat <<'EOF'
Usage: klctl firmware COMMAND
  profiles                         List supported build profiles
  info PROFILE                     Show one profile
  toolchain-status                 Check for arm-none-eabi-gcc
  toolchain-install                Install/download the configured toolchain
  build PROFILE                    Build and publish firmware
  build-export PROFILE TARGET      Build, then export/share in one visible job
  list                             List published builds
  publish BUILD_ID                 Republish a completed build
  url BUILD_ID                     Print local and LAN download URLs
  share BUILD_ID                   Open the Android share sheet
  export BUILD_ID downloads        Copy to shared Downloads
  export BUILD_ID DIRECTORY        Copy safely to a writable directory
  flash BUILD_ID [--yes]           Run verified Klipper SD-card update
  clean builds|toolchain|all       Remove selected firmware caches
EOF
}

valid_id() { [[ "$1" =~ ^[a-z0-9][a-z0-9._-]{0,95}$ ]]; }
profile_line() {
  valid_id "$1" || return 1
  awk -F '|' -v id="$1" '$1 == id { print; found=1; exit } END { if (!found) exit 1 }' "$PROFILE_FILE"
}
sha256_file() { sha256sum "$1" | awk '{print $1}'; }
lan_ip() { ip -4 -o addr show scope global 2>/dev/null | awk 'NR == 1 { split($4,a,"/"); print a[1] }'; }

list_profiles() {
  printf '%-34s  %-22s  %-28s  %s\n' PROFILE BOARD REVISION DELIVERY
  awk -F '|' '!/^#/ && NF >= 10 { printf "%-34s  %-22s  %-28s  %s\n", $1, $2, $3, $7 }' "$PROFILE_FILE"
}

list_profiles_machine() {
  awk -F '|' '!/^#/ && NF >= 10 { printf "%s|%s|%s|%s|%s\n", $1, $2, $3, $6, $7 }' "$PROFILE_FILE"
}

list_storage_machine() {
  local candidate label writable
  printf 'web|Mainsail / web download||1\n'
  writable=0
  [[ -d "$HOME_DIR/storage/downloads" && -w "$HOME_DIR/storage/downloads" ]] && writable=1
  printf 'downloads|Downloads|downloads|%s\n' "$writable"
  for candidate in /storage/*; do
    [[ -d "$candidate" ]] || continue
    case "$candidate" in /storage/emulated|/storage/self) continue ;; esac
    label=${candidate##*/}
    writable=0
    [[ -w "$candidate" ]] && writable=1
    printf 'storage-%s|Storage card %s|%s|%s\n' "$label" "$label" "$candidate" "$writable"
  done
  printf 'share|Android share…|share|1\n'
}

show_profile() {
  local line
  line=$(profile_line "$1") || die "unknown profile: $1"
  IFS='|' read -r id name revision transport artifact required method flash_board config reference <<<"$line"
  printf 'Profile: %s\nBoard: %s\nRevision: %s\nTransport: %s\nArtifact: %s\nRequired filename: %s\nDelivery: %s\n' \
    "$id" "$name" "$revision" "$transport" "$artifact" "$required" "$method"
  [[ -n "$flash_board" ]] && printf 'Klipper flash-sdcard board: %s\n' "$flash_board"
  printf 'Reference: %s\n\nKconfig:\n' "$reference"
  sed 's/^/  /' "$LIB_DIR/configs/$config"
}

find_compiler() {
  local compiler
  compiler=$(command -v arm-none-eabi-gcc 2>/dev/null || true)
  [[ -n "$compiler" ]] || compiler="$HOME_DIR/.local/opt/k4a-arm-toolchain/bin/arm-none-eabi-gcc"
  [[ -x "$compiler" ]] || die "arm-none-eabi-gcc is not installed; run: klctl firmware toolchain-install"
  printf '%s\n' "$compiler"
}

toolchain_status() {
  local compiler
  compiler=$(find_compiler)
  printf '%s\n' "$compiler"
  "$compiler" --version | head -n 1
}

install_toolchain() {
  command -v arm-none-eabi-gcc >/dev/null 2>&1 && { toolchain_status; return; }
  if apt-cache show gcc-arm-none-eabi >/dev/null 2>&1; then
    pkg install -y gcc-arm-none-eabi
    toolchain_status
    return
  fi
  local arch url checksum archive staging destination="$HOME_DIR/.local/opt/k4a-arm-toolchain"
  arch=$(uname -m)
  case "$arch" in
    aarch64|arm64) url="${K4A_ARM_TOOLCHAIN_AARCH64_URL:-}"; checksum="${K4A_ARM_TOOLCHAIN_AARCH64_SHA256:-}" ;;
    armv7l|armv8l) url="${K4A_ARM_TOOLCHAIN_ARM_URL:-}"; checksum="${K4A_ARM_TOOLCHAIN_ARM_SHA256:-}" ;;
    *) die "no Android toolchain is configured for architecture $arch" ;;
  esac
  [[ -n "$url" && "$checksum" =~ ^[0-9a-fA-F]{64}$ ]] || die "this release has no checksum-pinned Bionic toolchain for $arch; install arm-none-eabi-gcc manually or configure K4A_ARM_TOOLCHAIN_*_URL and _SHA256"
  archive=$(mktemp "$HOME_DIR/.cache/k4a-toolchain.XXXXXX.tar.xz")
  staging=$(mktemp -d "$HOME_DIR/.cache/k4a-toolchain.XXXXXX")
  trap 'rm -f -- "$archive"; rm -rf -- "$staging"' EXIT
  curl -fL --retry 3 "$url" -o "$archive"
  printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c -
  tar -xJf "$archive" -C "$staging" --strip-components=1
  [[ -x "$staging/bin/arm-none-eabi-gcc" ]] || die "toolchain archive has no bin/arm-none-eabi-gcc"
  rm -rf -- "$destination.previous"
  [[ -d "$destination" ]] && mv -- "$destination" "$destination.previous"
  mv -- "$staging" "$destination"
  rm -rf -- "$destination.previous"
  toolchain_status
}

assert_not_printing() {
  local response
  response=$(curl -fsS --max-time 2 'http://127.0.0.1:7125/printer/objects/query?print_stats' 2>/dev/null || true)
  if grep -qE '"state"[[:space:]]*:[[:space:]]*"(printing|paused)"' <<<"$response"; then
    die "refusing firmware work while a print is active or paused"
  fi
  return 0
}

write_index() {
  local temporary="$PUBLISH_ROOT/index.html.tmp.$$" directory manifest id profile commit created artifact hash size
  mkdir -p "$PUBLISH_ROOT"
  {
    printf '%s\n' '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>K4A firmware builds</title>'
    printf '%s\n' '<style>body{font:15px system-ui;background:#11151b;color:#d8dee9;max-width:900px;margin:auto;padding:24px}a{color:#4da3ff}.build{background:#202631;border-radius:8px;padding:14px;margin:12px 0}.meta{color:#9aa4b2}</style><h1>Klipper // Android firmware</h1>'
    for directory in "$PUBLISH_ROOT"/*; do
      [[ -d "$directory" && -f "$directory/manifest.properties" ]] || continue
      manifest="$directory/manifest.properties"
      id=$(basename "$directory")
      profile=$(sed -n 's/^profile=//p' "$manifest")
      commit=$(sed -n 's/^klipper_commit=//p' "$manifest")
      created=$(sed -n 's/^created=//p' "$manifest")
      artifact=$(sed -n 's/^artifact=//p' "$manifest")
      hash=$(sed -n 's/^sha256=//p' "$manifest")
      size=$(sed -n 's/^size=//p' "$manifest")
      printf '<div class="build"><strong>%s</strong><div class="meta">%s · %s · %s bytes</div><a href="%s/%s">Download %s</a><br><code>%s</code></div>\n' \
        "$profile" "$created" "$commit" "$size" "$id" "$artifact" "$artifact" "$hash"
    done
  } >"$temporary"
  mv -f -- "$temporary" "$PUBLISH_ROOT/index.html"
}

publish_build() {
  local id source destination
  id="$1"
  source="$BUILD_ROOT/$id"
  destination="$PUBLISH_ROOT/$id"
  valid_id "$id" || die "invalid build id"
  [[ -f "$source/manifest.properties" ]] || die "unknown or incomplete build: $id"
  mkdir -p "$PUBLISH_ROOT"
  rm -rf -- "$destination.tmp"
  mkdir -p "$destination.tmp"
  cp -f -- "$source"/* "$destination.tmp/"
  rm -rf -- "$destination"
  mv -- "$destination.tmp" "$destination"
  write_index
  printf 'Published: http://127.0.0.1:%s/firmware/%s/\n' "$WEB_PORT" "$id"
}

build_firmware() (
  local profile="$1" line id name revision transport artifact required method flash_board config reference
  local compiler commit short build_id out build_dir effective jobs source_artifact descriptive hash size created
  assert_not_printing
  line=$(profile_line "$profile") || die "unknown profile: $profile"
  IFS='|' read -r id name revision transport artifact required method flash_board config reference <<<"$line"
  [[ -d "$KLIPPER_DIR/.git" && -f "$KLIPPER_DIR/Makefile" ]] || die "Klipper source not found at $KLIPPER_DIR"
  if ! compiler=$(find_compiler 2>/dev/null); then
    printf 'Required ARM toolchain is missing; installing it now...\n'
    install_toolchain
    compiler=$(find_compiler)
  fi
  export PATH="$(dirname "$compiler"):$PATH"
  commit=$(git -C "$KLIPPER_DIR" rev-parse HEAD)
  short=${commit:0:8}
  build_id="$id-$short-$(date -u +%Y%m%dt%H%M%Sz)-$$"
  out="$BUILD_ROOT/out/$id"
  build_dir="$BUILD_ROOT/$build_id"
  mkdir -p "$out" "$build_dir"
  cp -f -- "$LIB_DIR/configs/$config" "$out/.config"
  jobs=2
  [[ $(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2) -ge 4 ]] && jobs=4
  command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true
  trap 'status=$?; command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock || true; exit $status' EXIT
  make -C "$KLIPPER_DIR" OUT="$out/" KCONFIG_CONFIG="$out/.config" olddefconfig
  effective="$out/.config"
  while IFS= read -r setting; do
    [[ -z "$setting" || "$setting" == \#* ]] && continue
    grep -Fxq "$setting" "$effective" || die "Klipper rejected critical setting: $setting"
  done <"$LIB_DIR/configs/$config"
  make -C "$KLIPPER_DIR" OUT="$out/" KCONFIG_CONFIG="$out/.config" -j "$jobs"
  source_artifact="$out/$artifact"
  [[ -s "$source_artifact" ]] || die "build completed without $artifact"
  cp -f -- "$source_artifact" "$build_dir/$required"
  descriptive="klipper-$id-$short.${required##*.}"
  [[ "$descriptive" == "$required" ]] || cp -f -- "$source_artifact" "$build_dir/$descriptive"
  cp -f -- "$effective" "$build_dir/klipper.config"
  [[ -f "$out/klipper.dict" ]] && cp -f -- "$out/klipper.dict" "$build_dir/klipper.dict"
  hash=$(sha256_file "$build_dir/$required")
  size=$(wc -c <"$build_dir/$required" | tr -d ' ')
  created=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  {
    printf 'build_id=%s\nprofile=%s\nboard=%s\nrevision=%s\ntransport=%s\n' "$build_id" "$id" "$name" "$revision" "$transport"
    printf 'klipper_commit=%s\ntoolchain=%s\ncreated=%s\nartifact=%s\nsize=%s\nsha256=%s\n' \
      "$commit" "$("$compiler" -dumpfullversion -dumpversion)" "$created" "$required" "$size" "$hash"
    printf 'delivery=%s\nflash_board=%s\nreference=%s\n' "$method" "$flash_board" "$reference"
  } >"$build_dir/manifest.properties"
  printf '%s  %s\n' "$hash" "$required" >"$build_dir/SHA256SUMS"
  show_profile "$id" >"$build_dir/BUILD_SETTINGS.txt"
  publish_build "$build_id"
  printf 'Build complete: %s\nSHA-256: %s\n' "$build_id" "$hash"
)

build_and_export() {
  local profile="$1" destination="$2" transcript build_id
  valid_id "$profile" || die "invalid profile id"
  case "$destination" in
    web|downloads|share) ;;
    /storage/[A-Za-z0-9._-]*) [[ "$destination" != */*/*/* ]] || die "invalid export destination" ;;
    *) die "invalid export destination" ;;
  esac
  transcript=$(mktemp "$HOME_DIR/.cache/k4a-firmware-build.XXXXXX")
  trap 'rm -f -- "$transcript"' RETURN
  build_firmware "$profile" | tee "$transcript"
  build_id=$(sed -n 's/^Build complete: //p' "$transcript" | tail -n 1)
  valid_id "$build_id" || die "build completed without a valid build id"
  case "$destination" in
    web) printf 'Firmware is ready on the Mainsail firmware downloads page.\n' ;;
    share) share_build "$build_id" ;;
    *) export_build "$build_id" "$destination" ;;
  esac
}

resolve_build() {
  valid_id "$1" || die "invalid build id"
  [[ -f "$BUILD_ROOT/$1/manifest.properties" ]] || die "unknown build: $1"
  printf '%s\n' "$BUILD_ROOT/$1"
}

build_urls() {
  local id="$1" address
  resolve_build "$id" >/dev/null
  printf 'http://127.0.0.1:%s/firmware/%s/\n' "$WEB_PORT" "$id"
  address=$(lan_ip)
  [[ -n "$address" ]] && printf 'http://%s:%s/firmware/%s/\n' "$address" "$WEB_PORT" "$id"
}

export_build() {
  local id="$1" destination="$2" build manifest artifact target temporary
  build=$(resolve_build "$id")
  manifest="$build/manifest.properties"
  artifact=$(sed -n 's/^artifact=//p' "$manifest")
  if [[ "$destination" == downloads ]]; then
    destination="$HOME_DIR/storage/downloads/KlipperFirmware/$id"
  fi
  [[ "$destination" == /* ]] || die "export destination must be an absolute directory or 'downloads'"
  [[ "$destination" != / && "$destination" != "$HOME_DIR" && "$destination" != "$PREFIX" ]] || die "unsafe export destination"
  mkdir -p "$destination" 2>/dev/null || die "cannot create destination; grant storage access or use the Android file picker"
  [[ -d "$destination" && -w "$destination" && ! -L "$destination" ]] || die "destination is not a writable real directory"
  target="$destination/$artifact"
  if [[ -e "$target" && "${K4A_FIRMWARE_ASSUME_YES:-0}" != 1 ]]; then
    [[ -t 0 ]] || die "$target exists; rerun interactively or set K4A_FIRMWARE_ASSUME_YES=1"
    read -r -p "Replace $target? [y/N] " answer
    [[ "$answer" =~ ^[Yy]$ ]] || die "export cancelled"
  fi
  temporary="$destination/.$artifact.k4a.$$"
  cp -f -- "$build/$artifact" "$temporary"
  sync "$temporary" 2>/dev/null || true
  [[ "$(sha256_file "$temporary")" == "$(sha256_file "$build/$artifact")" ]] || { rm -f -- "$temporary"; die "export verification failed"; }
  mv -f -- "$temporary" "$target"
  cp -f -- "$build/SHA256SUMS" "$build/BUILD_SETTINGS.txt" "$destination/"
  printf 'Exported and verified: %s\n' "$target"
}

flash_build() (
  local id="$1" assume="${2:-}" build manifest method board profile artifact was_up=0 status=0
  local -a flash_args
  build=$(resolve_build "$id")
  manifest="$build/manifest.properties"
  method=$(sed -n 's/^delivery=//p' "$manifest")
  board=$(sed -n 's/^flash_board=//p' "$manifest")
  profile=$(sed -n 's/^profile=//p' "$manifest")
  artifact=$(sed -n 's/^artifact=//p' "$manifest")
  [[ "$method" == flash-sdcard && -n "$board" ]] || die "$profile is export-only; use 'klctl firmware export' or the /firmware/ web page"
  [[ -x "$KLIPPER_DIR/scripts/flash-sdcard.sh" ]] || die "Klipper flash-sdcard.sh is unavailable"
  [[ -e "$PTY" ]] || die "MCU bridge PTY is unavailable: $PTY"
  assert_not_printing
  if [[ "$assume" != --yes ]]; then
    [[ -t 0 ]] || die "direct flashing requires an interactive confirmation or --yes"
    printf 'Board profile: %s\nUpdater target: %s\nArtifact: %s\n' "$profile" "$board" "$artifact"
    read -r -p 'Flash this exact board now? [y/N] ' answer
    [[ "$answer" =~ ^[Yy]$ ]] || die "flash cancelled"
  fi
  service_is_up_local() { SVDIR="$PREFIX/var/service" sv status "$1" 2>/dev/null | grep -q '^run:'; }
  service_is_up_local klipper && was_up=1
  trap '(( was_up )) && SVDIR="$PREFIX/var/service" sv up klipper >/dev/null 2>&1 || true' EXIT
  (( was_up )) && SVDIR="$PREFIX/var/service" sv down klipper
  set +e
  flash_args=(-f "$build/$artifact")
  [[ -f "$build/klipper.dict" ]] && flash_args+=(-d "$build/klipper.dict")
  "$KLIPPER_DIR/scripts/flash-sdcard.sh" "${flash_args[@]}" "$PTY" "$board"
  status=$?
  set -e
  if (( was_up )); then
    SVDIR="$PREFIX/var/service" sv up klipper || true
    was_up=0
  fi
  (( status == 0 )) || die "flash-sdcard failed ($status); the verified artifact remains at $build/$artifact"
  printf 'Flash completed. Power-cycle if the board guide requires it, then verify the MCU version in Mainsail.\n'
)

clean_cache() {
  case "$1" in
    builds) rm -rf -- "$BUILD_ROOT"; mkdir -p "$BUILD_ROOT" ;;
    toolchain) rm -rf -- "$HOME_DIR/.local/opt/k4a-arm-toolchain" ;;
    all) rm -rf -- "$BUILD_ROOT" "$HOME_DIR/.local/opt/k4a-arm-toolchain"; mkdir -p "$BUILD_ROOT" ;;
    *) die "clean expects builds, toolchain, or all" ;;
  esac
}

[[ -r "$PROFILE_FILE" ]] || die "firmware profile catalog is missing: $PROFILE_FILE"
command="${1:-}"
case "$command" in
  profiles) [[ $# -eq 1 ]] || die "profiles takes no arguments"; list_profiles ;;
  profiles-machine) [[ $# -eq 1 ]] || die "profiles-machine takes no arguments"; list_profiles_machine ;;
  storage-machine) [[ $# -eq 1 ]] || die "storage-machine takes no arguments"; list_storage_machine ;;
  info) [[ $# -eq 2 ]] || die "info requires PROFILE"; show_profile "$2" ;;
  toolchain-status) [[ $# -eq 1 ]] || die "toolchain-status takes no arguments"; toolchain_status ;;
  toolchain-install) [[ $# -eq 1 ]] || die "toolchain-install takes no arguments"; install_toolchain ;;
  build) [[ $# -eq 2 ]] || die "build requires PROFILE"; build_firmware "$2" ;;
  build-export) [[ $# -eq 3 ]] || die "build-export requires PROFILE and TARGET"; build_and_export "$2" "$3" ;;
  list) find "$PUBLISH_ROOT" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort -r ;;
  publish) [[ $# -eq 2 ]] || die "publish requires BUILD_ID"; publish_build "$2" ;;
  url) [[ $# -eq 2 ]] || die "url requires BUILD_ID"; build_urls "$2" ;;
  share)
    [[ $# -eq 2 ]] || die "share requires BUILD_ID"
    build=$(resolve_build "$2"); artifact=$(sed -n 's/^artifact=//p' "$build/manifest.properties")
    command -v termux-open >/dev/null 2>&1 || die "termux-open is unavailable"
    termux-open --send "$build/$artifact"
    ;;
  export) [[ $# -eq 3 ]] || die "export requires BUILD_ID and destination"; export_build "$2" "$3" ;;
  flash) [[ $# -eq 2 || $# -eq 3 ]] || die "flash requires BUILD_ID [--yes]"; flash_build "$2" "${3:-}" ;;
  clean) [[ $# -eq 2 ]] || die "clean requires builds, toolchain, or all"; clean_cache "$2" ;;
  -h|--help|help|'') usage ;;
  *) usage >&2; exit 2 ;;
esac
