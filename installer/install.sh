#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

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

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || true)"
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
    if (( DRY_RUN )); then
      printf '+ stop managed services before update (if running)\n'
    elif command -v sv >/dev/null 2>&1; then
      for service in klipper-android-bridge klipper moonraker klipper-web; do
        if SVDIR="$SERVICE_ROOT" sv status "$service" 2>/dev/null | grep -q '^run:'; then
          RESTART_AFTER_UPDATE=1
        fi
      done
      SVDIR="$SERVICE_ROOT" sv down \
        klipper-web moonraker klipper klipper-android-bridge >/dev/null 2>&1 || true
    fi
    # These are generated/replaceable artifacts. Recreate them to avoid stale
    # Python packages or static UI files while leaving DATA_DIR untouched.
    run rm -rf -- "$KLIPPER_ENV"
    (( INSTALL_MOONRAKER )) && run rm -rf -- "$MOONRAKER_ENV"
    (( INSTALL_UI )) && run rm -rf -- "$MAINSAIL_DIR"
  fi
fi

log "Installing native Termux dependencies"
PACKAGES=(git python clang make ndk-sysroot libffi openssl zlib curl unzip termux-services)
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
    run git -C "$SOURCE_INSTALL_DIR" checkout --detach FETCH_HEAD
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

log "Building native PTY bridge"
BRIDGE_OUTPUT="$STATE_DIR/klipper-android-bridge"
run "$SOURCE_DIR/bridge/build-termux.sh" "$BRIDGE_OUTPUT"
run install -m 0755 "$BRIDGE_OUTPUT" "$BIN_DIR/klipper-android-bridge"

shallow_checkout() {
  local repository="$1" destination="$2" revision="$3"
  if [[ -d "$destination/.git" ]]; then
    run git -C "$destination" remote set-url origin "$repository"
  else
    [[ ! -e "$destination" ]] || run rm -rf -- "$destination"
    run mkdir -p "$destination"
    run git -C "$destination" init -q
    run git -C "$destination" remote add origin "$repository"
  fi
  run git -C "$destination" fetch --depth=1 --no-tags origin "$revision"
  run git -C "$destination" checkout --detach --force FETCH_HEAD
}

log "Installing native Klipper"
shallow_checkout https://github.com/Klipper3d/klipper.git "$KLIPPER_DIR" \
  "$([[ "$CHANNEL" == edge ]] && printf master || printf %s "$KLIPPER_REF")"
run python -m venv "$KLIPPER_ENV"
run "$KLIPPER_ENV/bin/pip" install --no-cache-dir --upgrade pip wheel
run "$KLIPPER_ENV/bin/pip" install --no-cache-dir -r "$KLIPPER_DIR/scripts/klippy-requirements.txt"

if (( INSTALL_MOONRAKER )); then
  log "Installing native Moonraker"
  shallow_checkout https://github.com/Arksine/moonraker.git "$MOONRAKER_DIR" \
    "$([[ "$CHANNEL" == edge ]] && printf master || printf %s "$MOONRAKER_REF")"
  run python -m venv "$MOONRAKER_ENV"
  run "$MOONRAKER_ENV/bin/pip" install --no-cache-dir --upgrade pip wheel
  run "$MOONRAKER_ENV/bin/pip" install --no-cache-dir -r "$MOONRAKER_DIR/scripts/moonraker-requirements.txt"
fi

render_template() {
  local source="$1" destination="$2" force="${3:-0}"
  if [[ -e "$destination" && "$force" != 1 ]]; then return 0; fi
  if (( DRY_RUN )); then printf '+ render %s -> %s\n' "$source" "$destination"; return 0; fi
  sed -e "s|@PREFIX@|$PREFIX|g" \
      -e "s|@HOME@|$TERMUX_HOME|g" \
      -e "s|@DATA_DIR@|$DATA_DIR|g" \
      -e "s|@INSTANCE@|$INSTANCE|g" \
      -e "s|@WEB_PORT@|$WEB_PORT|g" \
      -e "s|@SERVICE@|${CURRENT_SERVICE:-service}|g" \
      -e "s|@SERVICES@|${SERVICE_NAMES:-klipper-android-bridge klipper}|g" \
      "$source" >"$destination"
}

log "Creating configuration and runit services"
render_template "$SOURCE_DIR/installer/config/bridge.conf.example" "$DATA_DIR/config/bridge.conf.example" 1
render_template "$SOURCE_DIR/installer/config/printer.cfg.example" "$DATA_DIR/config/printer.cfg.example" 1
if [[ ! -e "$DATA_DIR/config/printer.cfg" ]]; then
  run cp "$DATA_DIR/config/printer.cfg.example" "$DATA_DIR/config/printer.cfg"
fi
if (( INSTALL_MOONRAKER )); then
  render_template "$SOURCE_DIR/installer/config/moonraker.conf" "$DATA_DIR/config/moonraker.conf"
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
  (( is_new )) && run touch "$directory/down"
}
install_service "klipper-android-bridge" "$SOURCE_DIR/installer/services/bridge.run"
install_service "klipper" "$SOURCE_DIR/installer/services/klipper.run"
if (( INSTALL_MOONRAKER )); then
  install_service "moonraker" "$SOURCE_DIR/installer/services/moonraker.run"
fi

if (( INSTALL_UI )); then
  log "Installing Mainsail"
  MAINSAIL_ARCHIVE="$STATE_DIR/mainsail.zip"
  if [[ "$CHANNEL" == stable ]]; then
    MAINSAIL_URL="https://github.com/mainsail-crew/mainsail/releases/download/$MAINSAIL_VERSION/mainsail.zip"
  else
    MAINSAIL_URL="https://github.com/mainsail-crew/mainsail/releases/latest/download/mainsail.zip"
  fi
  run curl -fL "$MAINSAIL_URL" -o "$MAINSAIL_ARCHIVE"
  if [[ "$CHANNEL" == stable && -n "$MAINSAIL_SHA256" ]]; then
    if (( DRY_RUN )); then
      printf '+ verify sha256 %s  %s\n' "$MAINSAIL_SHA256" "$MAINSAIL_ARCHIVE"
    else
      printf '%s  %s\n' "$MAINSAIL_SHA256" "$MAINSAIL_ARCHIVE" | sha256sum -c -
    fi
  fi
  run mkdir -p "$MAINSAIL_DIR"
  run unzip -o "$MAINSAIL_ARCHIVE" -d "$MAINSAIL_DIR"
  run rm -f -- "$MAINSAIL_ARCHIVE"
  render_template "$SOURCE_DIR/installer/config/nginx.conf" "$DATA_DIR/config/nginx.conf"
  install_service "klipper-web" "$SOURCE_DIR/installer/services/nginx.run"
fi

SERVICE_NAMES="klipper-android-bridge klipper"
(( INSTALL_MOONRAKER )) && SERVICE_NAMES+=" moonraker"
(( INSTALL_UI )) && SERVICE_NAMES+=" klipper-web"
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
  MANIFEST="$STATE_DIR/install-manifest"
  {
    printf 'installed_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'channel=%s\ninstance=%s\ndata_dir=%s\n' "$CHANNEL" "$INSTANCE" "$DATA_DIR"
    git -C "$KLIPPER_DIR" rev-parse HEAD | sed 's/^/klipper_commit=/'
    (( INSTALL_MOONRAKER )) && git -C "$MOONRAKER_DIR" rev-parse HEAD | sed 's/^/moonraker_commit=/'
  } >"$MANIFEST"
fi

if (( UPDATE && RESTART_AFTER_UPDATE )); then
  log "Restarting the updated stack"
  run "$BIN_DIR/klipper-android-runner" start
fi

log "Installation complete"
cat <<EOF
Next steps:
  1. Install and start the Android companion app.
  2. Grant USB access and copy its token and device UUID.
  3. Copy $DATA_DIR/config/bridge.conf.example to bridge.conf and fill them in.
  4. Confirm the PTY path in $DATA_DIR/config/printer.cfg.
  5. Run: kabctl doctor
  6. Start the supervised stack with: klipper-android-runner start
  7. To start it from the companion app, grant its Termux command permission
     in Android Settings > Apps > Klipper USB Bridge > Permissions.
EOF
