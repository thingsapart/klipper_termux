#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

PROJECT_NAME="klipper-android"
CHANNEL="stable"
INSTALL_UI=1
INSTALL_MOONRAKER=1
NON_INTERACTIVE=0
REINSTALL=0
UPDATE=0
DRY_RUN=0
ENABLE_APP_CONTROL=1
SOURCE_DIR=""
INSTANCE="printer"
WEB_PORT=8080
RESTART_AFTER_UPDATE=0
UPDATE_SERVICES_STOPPED=0
INSTALL_LOG=""

usage() {
  cat <<'EOF'
Usage: install.sh [options]
  --klipper-only       Install Klipper and bridge, without Moonraker/UI
  --without-ui         Install Moonraker but not Mainsail/nginx
  --channel NAME       stable (tested manifest) or edge (upstream heads)
  --instance NAME      Instance name (default: printer)
  --port NUMBER        Mainsail HTTP port (default: 8080)
  --source-dir PATH    Use an existing project checkout
  --non-interactive    Accept defaults; fail on an existing install without a mode
  --update             Refresh software while preserving printer data and configuration
  --reinstall          Remove an existing managed installation without prompting
  --dry-run            Print commands without changing the system
  --no-app-control     Do not allow the companion app to run Termux commands
  -h, --help           Show this help
EOF
}

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
log() { printf '\n==> %s\n' "$*"; }
run() {
  if (( DRY_RUN )); then printf '+ '; printf '%q ' "$@"; printf '\n';
  else "$@"; fi
}

while (($#)); do
  case "$1" in
    --klipper-only) INSTALL_MOONRAKER=0; INSTALL_UI=0 ;;
    --without-ui) INSTALL_UI=0 ;;
    --channel) shift; CHANNEL="${1:-}" ;;
    --instance) shift; INSTANCE="${1:-}" ;;
    --port) shift; WEB_PORT="${1:-}" ;;
    --source-dir) shift; SOURCE_DIR="${1:-}" ;;
    --non-interactive) NON_INTERACTIVE=1 ;;
    --update) UPDATE=1 ;;
    --reinstall) REINSTALL=1 ;;
    --dry-run) DRY_RUN=1 ;;
    --no-app-control) ENABLE_APP_CONTROL=0 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
  shift
done

[[ "$CHANNEL" == stable || "$CHANNEL" == edge ]] || die "channel must be stable or edge"
(( !(UPDATE && REINSTALL) )) || die "--update and --reinstall are mutually exclusive"
[[ "$INSTANCE" =~ ^[a-zA-Z0-9_-]+$ ]] || die "invalid instance name"
[[ "$WEB_PORT" =~ ^[0-9]+$ ]] && (( WEB_PORT >= 1024 && WEB_PORT <= 65535 )) ||
  die "port must be between 1024 and 65535"

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
DATA_DIR="$TERMUX_HOME/${INSTANCE}_data"
STATE_DIR="$TERMUX_HOME/.local/state/$PROJECT_NAME"
SOURCE_INSTALL_DIR="$TERMUX_HOME/.local/share/$PROJECT_NAME/source"
BIN_DIR="$TERMUX_HOME/.local/bin"
SERVICE_ROOT="$PREFIX/var/service"
KLIPPER_DIR="$TERMUX_HOME/klipper"
KLIPPER_ENV="$TERMUX_HOME/klippy-env"
MOONRAKER_DIR="$TERMUX_HOME/moonraker"
MOONRAKER_ENV="$TERMUX_HOME/moonraker-env"
MAINSAIL_DIR="$TERMUX_HOME/mainsail"
INSTALL_MANIFEST="$STATE_DIR/install-manifest"
PREVIOUS_MAINSAIL_RELEASE=""
if [[ -f "$SOURCE_INSTALL_DIR/installer/versions.env" ]]; then
  previous_mainsail_version="$(sed -n 's/^MAINSAIL_VERSION=//p' \
    "$SOURCE_INSTALL_DIR/installer/versions.env" | head -n 1)"
  previous_mainsail_sha="$(sed -n 's/^MAINSAIL_SHA256=//p' \
    "$SOURCE_INSTALL_DIR/installer/versions.env" | head -n 1)"
  PREVIOUS_MAINSAIL_RELEASE="$previous_mainsail_version:$previous_mainsail_sha"
fi

MANAGED_TREES=(
  "$DATA_DIR"
  "$STATE_DIR"
  "$TERMUX_HOME/.local/share/$PROJECT_NAME"
  "$KLIPPER_DIR"
  "$KLIPPER_ENV"
  "$MOONRAKER_DIR"
  "$MOONRAKER_ENV"
  "$MAINSAIL_DIR"
  "$PREFIX/var/run/klipper-android"
  "$SERVICE_ROOT/klipper-android-bridge"
  "$SERVICE_ROOT/klipper"
  "$SERVICE_ROOT/moonraker"
  "$SERVICE_ROOT/klipper-web"
)
MANAGED_FILES=(
  "$BIN_DIR/klipper-android-bridge"
  "$BIN_DIR/klippy-android.py"
  "$BIN_DIR/moonraker-android.py"
  "$BIN_DIR/klipper-android-runner"
  "$BIN_DIR/kabctl"
)
MANAGED_LINKS=(
  "$PREFIX/bin/klipper-android-bridge"
  "$PREFIX/bin/klipper-android-runner"
  "$PREFIX/bin/kabctl"
)

if (( ! DRY_RUN )); then
  [[ "$PREFIX" == /data/data/*/files/usr || "$PREFIX" == /data/user/*/files/usr ]] ||
    die "this installer must run in native Termux, not proot or generic Linux"
  command -v getprop >/dev/null || die "Android getprop command is unavailable"
  SDK="$(getprop ro.build.version.sdk)"
  [[ "$SDK" =~ ^[0-9]+$ ]] && (( SDK >= 24 )) || die "Android 7/API 24 or newer is required"
fi

SCRIPT_DIR=""
SCRIPT_PATH="${BASH_SOURCE[0]:-}"
if [[ -n "$SCRIPT_PATH" ]]; then
  SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$SCRIPT_PATH")" 2>/dev/null && pwd || true)"
fi
if [[ -z "$SOURCE_DIR" && -f "$SCRIPT_DIR/../CMakeLists.txt" ]]; then
  SOURCE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
fi
# A locally invoked copy from our managed source tree is about to delete itself.
# Bash has already read the script, so switch back to download mode for the rebuild.
if [[ "$SOURCE_DIR" == "$SOURCE_INSTALL_DIR" ]]; then
  SOURCE_DIR=""
fi

has_existing_installation() {
  local path
  for path in "${MANAGED_TREES[@]}" "${MANAGED_FILES[@]}"; do
    [[ -e "$path" || -L "$path" ]] && return 0
  done
  return 1
}

purge_existing_installation() {
  local path
  log "Stopping and removing the existing managed installation"
  if (( DRY_RUN )); then
    printf '+ sv down klipper-web moonraker klipper klipper-android-bridge (if available)\n'
  elif command -v sv >/dev/null 2>&1; then
    SVDIR="$SERVICE_ROOT" sv down klipper-web moonraker klipper klipper-android-bridge \
      >/dev/null 2>&1 || true
  fi
  for path in "${MANAGED_TREES[@]}"; do
    case "$path" in
      "$TERMUX_HOME"/*|"$PREFIX"/var/run/klipper-android|"$SERVICE_ROOT"/*) ;;
      *) die "refusing unsafe removal path: $path" ;;
    esac
    run rm -rf -- "$path"
  done
  for path in "${MANAGED_FILES[@]}"; do
    case "$path" in "$BIN_DIR"/*) ;; *) die "refusing unsafe removal path: $path" ;; esac
    run rm -f -- "$path"
  done
  for path in "${MANAGED_LINKS[@]}"; do
    target="$BIN_DIR/${path##*/}"
    if [[ -L "$path" && "$(readlink "$path")" == "$target" ]]; then
      run rm -f -- "$path"
    fi
  done
}

if has_existing_installation; then
  if (( ! REINSTALL && ! UPDATE )); then
    if (( NON_INTERACTIVE )); then
      die "an existing installation was found; rerun interactively or pass --update or --reinstall"
    fi
    printf '\nAn existing Klipper Android installation was found.\n'
    printf '  UPDATE  refreshes software and preserves configuration, gcodes, database, and logs.\n'
    printf '  DELETE  permanently removes the complete managed installation under:\n  %s\n' "$DATA_DIR"
    printf 'Both modes preserve Termux packages and unrelated Termux files.\n'
    printf 'Type UPDATE or DELETE to continue: '
    [[ -r /dev/tty ]] || die "confirmation requires a terminal; rerun with --update or --reinstall"
    read -r confirmation </dev/tty
    case "$confirmation" in
      UPDATE) UPDATE=1 ;;
      DELETE) REINSTALL=1 ;;
      *) die "installation change cancelled" ;;
    esac
  fi
  if (( REINSTALL )); then
    purge_existing_installation
  else
    log "Updating managed software; printer data and configuration will be preserved"
  fi
fi

prepare_update_mutation() {
  (( UPDATE && ! UPDATE_SERVICES_STOPPED )) || return 0
  UPDATE_SERVICES_STOPPED=1
  if (( DRY_RUN )); then
    printf '+ stop managed services before applying changes (if running)\n'
  elif command -v sv >/dev/null 2>&1; then
    for service in klipper-android-bridge klipper moonraker klipper-web; do
      if SVDIR="$SERVICE_ROOT" sv status "$service" 2>/dev/null | grep -q '^run:'; then
        RESTART_AFTER_UPDATE=1
      fi
    done
    if (( RESTART_AFTER_UPDATE )); then
      touch "$STATE_DIR/restart-after-update"
    fi
    SVDIR="$SERVICE_ROOT" sv down \
      klipper-web moonraker klipper klipper-android-bridge >/dev/null 2>&1 || true
  fi
}

start_installer_logging() {
  (( DRY_RUN )) && return 0
  mkdir -p "$STATE_DIR"
  INSTALL_LOG="$STATE_DIR/installer.log"
  if [[ -f "$INSTALL_LOG" ]] && (( $(wc -c <"$INSTALL_LOG") > 1048576 )); then
    mv -f "$INSTALL_LOG" "$INSTALL_LOG.previous"
  fi
  exec > >(tee -a "$INSTALL_LOG") 2>&1
  printf '\n===== installer started %s =====\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  trap 'status=$?; printf "===== installer finished %s (status %d) =====\n" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$status"' EXIT
  trap 'status=$?; printf "ERROR: installer command failed near line %d (status %d)\n" "${BASH_LINENO[0]:-${LINENO}}" "$status" >&2' ERR
}

start_installer_logging
if (( UPDATE )) && [[ -f "$STATE_DIR/restart-after-update" ]]; then
  RESTART_AFTER_UPDATE=1
fi

render_template() {
  local source="$1" destination="$2" force="${3:-0}" temporary=""
  if [[ -e "$destination" && "$force" != 1 ]]; then return 0; fi
  if (( DRY_RUN )); then printf '+ render %s -> %s\n' "$source" "$destination"; return 0; fi
  temporary="$destination.kab-new.$$"
  sed -e "s|@PREFIX@|$PREFIX|g" \
      -e "s|@HOME@|$TERMUX_HOME|g" \
      -e "s|@DATA_DIR@|$DATA_DIR|g" \
      -e "s|@INSTANCE@|$INSTANCE|g" \
      -e "s|@WEB_PORT@|$WEB_PORT|g" \
      -e "s|@SERVICE@|${CURRENT_SERVICE:-service}|g" \
      -e "s|@SERVICES@|${SERVICE_NAMES:-klipper-android-bridge klipper}|g" \
      "$source" >"$temporary"
  if [[ -e "$destination" ]] && cmp -s "$temporary" "$destination"; then
    rm -f -- "$temporary"
    return 0
  fi
  prepare_update_mutation
  mv -f -- "$temporary" "$destination"
}

log "Installing native Termux dependencies"
PACKAGES=(git python clang make ndk-sysroot libffi openssl zlib curl unzip iproute2 termux-services)
(( INSTALL_MOONRAKER )) && PACKAGES+=(libsodium libjpeg-turbo)
(( INSTALL_UI )) && PACKAGES+=(nginx)
run pkg install -y "${PACKAGES[@]}"

run mkdir -p "$BIN_DIR" "$STATE_DIR" "$DATA_DIR/config" "$DATA_DIR/logs" \
  "$DATA_DIR/gcodes" "$DATA_DIR/database" "$SERVICE_ROOT"

if [[ -z "$SOURCE_DIR" ]]; then
  REPOSITORY="${KAB_REPOSITORY:-}"
  [[ -n "$REPOSITORY" ]] || die "set KAB_REPOSITORY to this project's public Git URL"
  if [[ -d "$SOURCE_INSTALL_DIR/.git" ]]; then
    log "Updating bridge source"
    run git -C "$SOURCE_INSTALL_DIR" fetch --depth=1 --no-tags origin HEAD
    if (( DRY_RUN )) || [[ "$(git -C "$SOURCE_INSTALL_DIR" rev-parse HEAD)" != \
        "$(git -C "$SOURCE_INSTALL_DIR" rev-parse FETCH_HEAD)" ]] || \
        ! git -C "$SOURCE_INSTALL_DIR" diff --quiet || \
        ! git -C "$SOURCE_INSTALL_DIR" diff --cached --quiet; then
      run git -C "$SOURCE_INSTALL_DIR" checkout --detach --force FETCH_HEAD
    else
      log "Bridge source is already current"
    fi
  else
    log "Downloading bridge source"
    run mkdir -p "$(dirname "$SOURCE_INSTALL_DIR")"
    [[ ! -e "$SOURCE_INSTALL_DIR" ]] || run rm -rf -- "$SOURCE_INSTALL_DIR"
    run git clone --depth=1 --single-branch --no-tags "$REPOSITORY" "$SOURCE_INSTALL_DIR"
  fi
  SOURCE_DIR="$SOURCE_INSTALL_DIR"
fi
[[ -f "$SOURCE_DIR/CMakeLists.txt" || $DRY_RUN -eq 1 ]] || die "invalid project source: $SOURCE_DIR"
if [[ -f "$SOURCE_DIR/installer/versions.env" ]]; then
  # shellcheck source=versions.env
  source "$SOURCE_DIR/installer/versions.env"
fi
: "${KLIPPER_REF:=master}" "${MOONRAKER_REF:=master}" "${MAINSAIL_VERSION:=latest}" "${MAINSAIL_SHA256:=}"

SERVICE_NAMES="klipper-android-bridge klipper"
(( INSTALL_MOONRAKER )) && SERVICE_NAMES+=" moonraker"
(( INSTALL_UI )) && SERVICE_NAMES+=" klipper-web"

repair_existing_service() {
  local name="$1" template="$2" directory="$SERVICE_ROOT/$1"
  [[ -d "$directory" ]] || return 0
  run mkdir -p "$directory/log"
  CURRENT_SERVICE="$name"
  render_template "$template" "$directory/run" 1
  render_template "$SOURCE_DIR/installer/services/log.run" "$directory/log/run" 1
  unset CURRENT_SERVICE
  run chmod 0755 "$directory/run" "$directory/log/run"
}

if (( UPDATE )); then
  log "Repairing generated launchers and existing services"
  render_template "$SOURCE_DIR/installer/klippy-android.py" "$BIN_DIR/klippy-android.py" 1
  if (( INSTALL_MOONRAKER )); then
    render_template "$SOURCE_DIR/installer/moonraker-android.py" "$BIN_DIR/moonraker-android.py" 1
  fi
  repair_existing_service "klipper-android-bridge" "$SOURCE_DIR/installer/services/bridge.run"
  repair_existing_service "klipper" "$SOURCE_DIR/installer/services/klipper.run"
  if (( INSTALL_MOONRAKER )); then
    repair_existing_service "moonraker" "$SOURCE_DIR/installer/services/moonraker.run"
  fi
  if (( INSTALL_UI )); then
    render_template "$SOURCE_DIR/installer/config/nginx.conf" "$DATA_DIR/config/nginx.conf" 1
    repair_existing_service "klipper-web" "$SOURCE_DIR/installer/services/nginx.run"
  fi
  render_template "$SOURCE_DIR/installer/kabctl" "$BIN_DIR/kabctl" 1
  render_template "$SOURCE_DIR/installer/klipper-android-runner" "$BIN_DIR/klipper-android-runner" 1
  run chmod 0755 "$BIN_DIR/kabctl" "$BIN_DIR/klipper-android-runner"
fi

BRIDGE_OUTPUT="$STATE_DIR/klipper-android-bridge"
BRIDGE_HASH_FILE="$STATE_DIR/bridge-build.sha256"
if (( DRY_RUN )); then
  BRIDGE_BUILD_REQUIRED=1
else
  BRIDGE_BUILD_HASH="$(
    sha256sum "$SOURCE_DIR/bridge/build-termux.sh" \
      "$SOURCE_DIR"/bridge/include/*.h "$SOURCE_DIR"/bridge/src/*.c | sha256sum | awk '{print $1}'
  )"
  BRIDGE_BUILD_REQUIRED=1
  if [[ -x "$BIN_DIR/klipper-android-bridge" && -f "$BRIDGE_HASH_FILE" && \
      "$(<"$BRIDGE_HASH_FILE")" == "$BRIDGE_BUILD_HASH" ]]; then
    BRIDGE_BUILD_REQUIRED=0
  fi
fi
if (( BRIDGE_BUILD_REQUIRED )); then
  log "Building native PTY bridge"
  run "$SOURCE_DIR/bridge/build-termux.sh" "$BRIDGE_OUTPUT"
  prepare_update_mutation
  run install -m 0755 "$BRIDGE_OUTPUT" "$BIN_DIR/klipper-android-bridge"
  if (( ! DRY_RUN )); then printf '%s\n' "$BRIDGE_BUILD_HASH" >"$BRIDGE_HASH_FILE"; fi
else
  log "Native PTY bridge build inputs are unchanged"
fi

shallow_checkout() {
  local repository="$1" destination="$2" revision="$3" target=""
  if [[ -d "$destination/.git" ]]; then
    run git -C "$destination" remote set-url origin "$repository"
  else
    [[ ! -e "$destination" ]] || run rm -rf -- "$destination"
    run mkdir -p "$destination"
    run git -C "$destination" init -q
    run git -C "$destination" remote add origin "$repository"
  fi
  if (( ! DRY_RUN )) && [[ "$revision" =~ ^[0-9a-fA-F]{40}$ ]] && \
      git -C "$destination" cat-file -e "$revision^{commit}" 2>/dev/null; then
    target="$revision"
    log "$(basename "$destination") source revision is already present"
  else
    run git -C "$destination" fetch --depth=1 --no-tags origin "$revision"
    (( DRY_RUN )) || target="$(git -C "$destination" rev-parse FETCH_HEAD)"
  fi
  if (( DRY_RUN )) || [[ "$(git -C "$destination" rev-parse HEAD 2>/dev/null || true)" != "$target" ]] || \
      ! git -C "$destination" diff --quiet || ! git -C "$destination" diff --cached --quiet; then
    prepare_update_mutation
    run git -C "$destination" checkout --detach --force "${target:-FETCH_HEAD}"
  else
    log "$(basename "$destination") working tree is already current"
  fi
}

sync_python_environment() {
  local name="$1" environment="$2" requirements="$3"
  if (( DRY_RUN )); then
    run python -m venv "$environment"
    run "$environment/bin/pip" install --no-cache-dir --upgrade pip wheel
    run "$environment/bin/pip" install --no-cache-dir -r "$requirements"
    return
  fi
  if [[ ! -x "$environment/bin/python" || ! -x "$environment/bin/pip" ]]; then
    prepare_update_mutation
    [[ ! -e "$environment" ]] || run rm -rf -- "$environment"
    run python -m venv "$environment"
    run "$environment/bin/pip" install --no-cache-dir --upgrade pip wheel
  fi
  log "Checking $name Python dependencies"
  prepare_update_mutation
  # pip operates incrementally in the retained environment: already-satisfied
  # packages are reused and only changed/missing requirements are installed.
  run "$environment/bin/pip" install --no-cache-dir -r "$requirements"
}

log "Installing native Klipper"
shallow_checkout https://github.com/Klipper3d/klipper.git "$KLIPPER_DIR" \
  "$([[ "$CHANNEL" == edge ]] && printf master || printf %s "$KLIPPER_REF")"
sync_python_environment klipper "$KLIPPER_ENV" \
  "$KLIPPER_DIR/scripts/klippy-requirements.txt"

if (( INSTALL_MOONRAKER )); then
  log "Installing native Moonraker"
  shallow_checkout https://github.com/Arksine/moonraker.git "$MOONRAKER_DIR" \
    "$([[ "$CHANNEL" == edge ]] && printf master || printf %s "$MOONRAKER_REF")"
  sync_python_environment moonraker "$MOONRAKER_ENV" \
    "$MOONRAKER_DIR/scripts/moonraker-requirements.txt"
fi

log "Creating configuration and runit services"
render_template "$SOURCE_DIR/installer/klippy-android.py" "$BIN_DIR/klippy-android.py" 1
if (( INSTALL_MOONRAKER )); then
  render_template "$SOURCE_DIR/installer/moonraker-android.py" "$BIN_DIR/moonraker-android.py" 1
fi
render_template "$SOURCE_DIR/installer/config/bridge.conf.example" "$DATA_DIR/config/bridge.conf.example" 1
BRIDGE_CONFIG="$DATA_DIR/config/bridge.conf"
if [[ -f "$BRIDGE_CONFIG" ]] && grep -qE \
    '^device=main,[^,]+,250000,8,1,none,none,' "$BRIDGE_CONFIG"; then
  # Older generated configurations left CDC control lines deasserted. Linux's
  # cdc_acm driver and Octo4Android both assert DTR+RTS when opening a port.
  if (( DRY_RUN )); then
    printf '+ migrate legacy bridge flags to dtr+rts in %s\n' "$BRIDGE_CONFIG"
  else
    prepare_update_mutation
    sed -i -E \
      's|^(device=main,[^,]+,250000,8,1,none,)none(,.*)$|\1dtr+rts\2|' \
      "$BRIDGE_CONFIG"
  fi
fi
if [[ -f "$BRIDGE_CONFIG" ]] && grep -qE \
    '^device=main,offline,250000,8,1,none,(none|dtr\+rts),' "$BRIDGE_CONFIG"; then
  # "offline" was the old generated default. Auto keeps the PTY available too,
  # but also retries the Android service and selects a permitted port on attach.
  if (( DRY_RUN )); then
    printf '+ migrate generated bridge selector from offline to auto in %s\n' "$BRIDGE_CONFIG"
  else
    prepare_update_mutation
    sed -i -E \
      's#^(device=main,)offline(,250000,8,1,none,(none|dtr\+rts),.*)$#\1auto\2#' \
      "$BRIDGE_CONFIG"
  fi
fi
render_template "$SOURCE_DIR/installer/config/printer.cfg.example" "$DATA_DIR/config/printer.cfg.example" 1
PRINTER_CONFIG="$DATA_DIR/config/printer.cfg"
if [[ ! -s "$PRINTER_CONFIG" ]] || grep -qE \
    '^# (Managed starter configuration for Klipper Android|Replace this example with the configuration)' \
    "$PRINTER_CONFIG"; then
  # Install and update only configurations carrying one of our starter markers.
  # A real printer.cfg is user data and must never be replaced by UPDATE.
  run install -m 0600 "$DATA_DIR/config/printer.cfg.example" "$PRINTER_CONFIG"
  run mkdir -p "$STATE_DIR"
  run touch "$STATE_DIR/starter-config-installed"
fi
if (( INSTALL_MOONRAKER )); then
  MOONRAKER_CONFIG="$DATA_DIR/config/moonraker.conf"
  render_template "$SOURCE_DIR/installer/config/moonraker.conf" "$MOONRAKER_CONFIG"
  # Migrate configurations generated by older installer versions without
  # overwriting user configuration. The missing section otherwise selects
  # Moonraker's systemd_dbus provider, which cannot run under Termux.
  if [[ -f "$MOONRAKER_CONFIG" ]] && ! grep -qE '^[[:space:]]*\[machine\][[:space:]]*$' "$MOONRAKER_CONFIG"; then
    if (( DRY_RUN )); then
      printf '+ add Termux [machine] provider to %s\n' "$MOONRAKER_CONFIG"
    else
      prepare_update_mutation
      printf '\n# Added by klipper-android for native Termux.\n[machine]\nprovider: none\nvalidate_service: False\nvalidate_config: False\n' \
        >>"$MOONRAKER_CONFIG"
    fi
  fi
  if [[ -f "$MOONRAKER_CONFIG" ]] && ! grep -qE '^[[:space:]]*\[zeroconf\][[:space:]]*$' "$MOONRAKER_CONFIG"; then
    if (( DRY_RUN )); then
      printf '+ add Moonraker mDNS hostname to %s\n' "$MOONRAKER_CONFIG"
    else
      prepare_update_mutation
      printf '\n# Advertise the phone on the local network.\n[zeroconf]\nmdns_hostname: klipper-android\n' \
        >>"$MOONRAKER_CONFIG"
    fi
  fi
fi

install_service() {
  local name="$1" template="$2" directory is_new=0
  directory="$SERVICE_ROOT/$name"
  [[ ! -d "$directory" ]] && is_new=1
  run mkdir -p "$directory/log"
  CURRENT_SERVICE="$name"
  render_template "$template" "$directory/run" 1
  render_template "$SOURCE_DIR/installer/services/log.run" "$directory/log/run" 1
  unset CURRENT_SERVICE
  run chmod 0755 "$directory/run" "$directory/log/run"
  if (( is_new )); then
    run touch "$directory/down"
  fi
}
install_service "klipper-android-bridge" "$SOURCE_DIR/installer/services/bridge.run"
install_service "klipper" "$SOURCE_DIR/installer/services/klipper.run"
if (( INSTALL_MOONRAKER )); then
  install_service "moonraker" "$SOURCE_DIR/installer/services/moonraker.run"
fi

if (( INSTALL_UI )); then
  MAINSAIL_MARKER="$STATE_DIR/mainsail-release"
  MAINSAIL_RELEASE="$MAINSAIL_VERSION:$MAINSAIL_SHA256"
  INSTALL_MAINSAIL=1
  if (( ! DRY_RUN )) && [[ ! -f "$MAINSAIL_MARKER" && -f "$MAINSAIL_DIR/index.html" && \
      "$CHANNEL" == stable && "$PREVIOUS_MAINSAIL_RELEASE" == "$MAINSAIL_RELEASE" ]]; then
    printf '%s\n' "$MAINSAIL_RELEASE" >"$MAINSAIL_MARKER"
  fi
  if (( ! DRY_RUN )) && [[ "$CHANNEL" == stable && -f "$MAINSAIL_DIR/index.html" && \
      -f "$MAINSAIL_MARKER" && "$(<"$MAINSAIL_MARKER")" == "$MAINSAIL_RELEASE" ]]; then
    INSTALL_MAINSAIL=0
  fi
  MAINSAIL_ARCHIVE="$STATE_DIR/mainsail.zip"
  MAINSAIL_STAGING="$STATE_DIR/mainsail.new.$$"
  MAINSAIL_BACKUP="$STATE_DIR/mainsail.previous.$$"
  if [[ "$CHANNEL" == stable ]]; then
    MAINSAIL_URL="https://github.com/mainsail-crew/mainsail/releases/download/$MAINSAIL_VERSION/mainsail.zip"
  else
    MAINSAIL_URL="https://github.com/mainsail-crew/mainsail/releases/latest/download/mainsail.zip"
  fi
  if (( INSTALL_MAINSAIL )); then
    log "Installing Mainsail $MAINSAIL_VERSION"
    run rm -f -- "$MAINSAIL_ARCHIVE.part"
    run curl -fL --retry 3 --retry-delay 2 "$MAINSAIL_URL" -o "$MAINSAIL_ARCHIVE.part"
    run mv -f -- "$MAINSAIL_ARCHIVE.part" "$MAINSAIL_ARCHIVE"
    if [[ "$CHANNEL" == stable && -n "$MAINSAIL_SHA256" ]]; then
      if (( DRY_RUN )); then
        printf '+ verify sha256 %s  %s\n' "$MAINSAIL_SHA256" "$MAINSAIL_ARCHIVE"
      else
        printf '%s  %s\n' "$MAINSAIL_SHA256" "$MAINSAIL_ARCHIVE" | sha256sum -c -
      fi
    fi
    run rm -rf -- "$MAINSAIL_STAGING" "$MAINSAIL_BACKUP"
    run mkdir -p "$MAINSAIL_STAGING"
    run unzip -q -o "$MAINSAIL_ARCHIVE" -d "$MAINSAIL_STAGING"
    if (( ! DRY_RUN )) && [[ ! -f "$MAINSAIL_STAGING/index.html" ]]; then
      die "Mainsail archive did not contain index.html"
    fi
    prepare_update_mutation
    if [[ -d "$MAINSAIL_DIR" ]]; then run mv -- "$MAINSAIL_DIR" "$MAINSAIL_BACKUP"; fi
    run mv -- "$MAINSAIL_STAGING" "$MAINSAIL_DIR"
    run rm -rf -- "$MAINSAIL_BACKUP"
    run rm -f -- "$MAINSAIL_ARCHIVE"
    if (( ! DRY_RUN )); then printf '%s\n' "$MAINSAIL_RELEASE" >"$MAINSAIL_MARKER"; fi
  else
    log "Mainsail $MAINSAIL_VERSION is already installed"
  fi
  render_template "$SOURCE_DIR/installer/config/nginx.conf" "$DATA_DIR/config/nginx.conf" 1
  install_service "klipper-web" "$SOURCE_DIR/installer/services/nginx.run"
fi

render_template "$SOURCE_DIR/installer/kabctl" "$BIN_DIR/kabctl" 1
render_template "$SOURCE_DIR/installer/klipper-android-runner" "$BIN_DIR/klipper-android-runner" 1
run chmod 0755 "$BIN_DIR/kabctl" "$BIN_DIR/klipper-android-runner"

install_command_link() {
  local name="$1" target="$BIN_DIR/$1" link="$PREFIX/bin/$1"
  if [[ -e "$link" && ! -L "$link" ]]; then
    die "refusing to replace existing command: $link"
  fi
  if [[ -L "$link" && "$(readlink "$link")" != "$target" ]]; then
    die "refusing to replace unrelated command link: $link"
  fi
  run ln -sfn "$target" "$link"
}
install_command_link klipper-android-bridge
install_command_link klipper-android-runner
install_command_link kabctl

if (( ENABLE_APP_CONTROL )); then
  log "Enabling permission-gated companion app control"
  TERMUX_PROPERTIES="$TERMUX_HOME/.termux/termux.properties"
  run mkdir -p "$TERMUX_HOME/.termux"
  if (( DRY_RUN )); then
    printf '+ set allow-external-apps = true in %s\n' "$TERMUX_PROPERTIES"
  else
    if grep -qE '^[[:space:]]*allow-external-apps[[:space:]]*=' "$TERMUX_PROPERTIES" 2>/dev/null; then
      sed -i -E 's/^[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps = true/' "$TERMUX_PROPERTIES"
    else
      printf '\nallow-external-apps = true\n' >>"$TERMUX_PROPERTIES"
    fi
    command -v termux-reload-settings >/dev/null 2>&1 && termux-reload-settings || true
  fi
fi

if (( ! DRY_RUN )); then
  MANIFEST="$INSTALL_MANIFEST"
  {
    printf 'installed_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'channel=%s\ninstance=%s\ndata_dir=%s\n' "$CHANNEL" "$INSTANCE" "$DATA_DIR"
    git -C "$KLIPPER_DIR" rev-parse HEAD | sed 's/^/klipper_commit=/'
    (( INSTALL_MOONRAKER )) && git -C "$MOONRAKER_DIR" rev-parse HEAD | sed 's/^/moonraker_commit=/'
    (( INSTALL_UI )) && printf 'mainsail_release=%s\n' "$MAINSAIL_RELEASE"
  } >"$MANIFEST"
fi

if (( UPDATE && RESTART_AFTER_UPDATE )); then
  log "Restarting the updated stack"
  run "$BIN_DIR/klipper-android-runner" start
fi
if (( ! DRY_RUN )); then rm -f -- "$STATE_DIR/restart-after-update"; fi

log "Installation complete"
cat <<EOF
Next steps:
  1. Install and start the Android companion app.
  2. Copy its pairing token; grant USB access when a printer is attached.
  3. Copy $DATA_DIR/config/bridge.conf.example to bridge.conf and fill in the token.
  4. Confirm the PTY path in $DATA_DIR/config/printer.cfg.
  5. Run: kabctl doctor
  6. Start the supervised stack with: klipper-android-runner start
  7. To start it from the companion app, grant its Termux command permission
     in Android Settings > Apps > Klipper USB Bridge > Permissions.
EOF
