#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT_NAME="klipper-android"
CHANNEL="stable"
INSTALL_UI=1
INSTALL_MOONRAKER=1
NON_INTERACTIVE=0
DRY_RUN=0
ENABLE_APP_CONTROL=1
SOURCE_DIR=""
INSTANCE="printer"
WEB_PORT=8080

usage() {
  cat <<'EOF'
Usage: install.sh [options]
  --klipper-only       Install Klipper and bridge, without Moonraker/UI
  --without-ui         Install Moonraker but not Mainsail/nginx
  --channel NAME       stable (tested manifest) or edge (upstream heads)
  --instance NAME      Instance name (default: printer)
  --port NUMBER        Mainsail HTTP port (default: 8080)
  --source-dir PATH    Use an existing project checkout
  --non-interactive    Accept defaults
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
    --dry-run) DRY_RUN=1 ;;
    --no-app-control) ENABLE_APP_CONTROL=0 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
  shift
done

[[ "$CHANNEL" == stable || "$CHANNEL" == edge ]] || die "channel must be stable or edge"
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
    run git -C "$SOURCE_INSTALL_DIR" fetch --tags origin
  else
    log "Downloading bridge source"
    run mkdir -p "$(dirname "$SOURCE_INSTALL_DIR")"
    run git clone "$REPOSITORY" "$SOURCE_INSTALL_DIR"
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

KLIPPER_DIR="$TERMUX_HOME/klipper"
KLIPPER_ENV="$TERMUX_HOME/klippy-env"
log "Installing native Klipper"
if [[ -d "$KLIPPER_DIR/.git" ]]; then
  run git -C "$KLIPPER_DIR" fetch origin
else
  run git clone https://github.com/Klipper3d/klipper.git "$KLIPPER_DIR"
fi
if [[ "$CHANNEL" == edge ]]; then
  run git -C "$KLIPPER_DIR" checkout master
  run git -C "$KLIPPER_DIR" pull --ff-only origin master
else
  run git -C "$KLIPPER_DIR" checkout --detach "$KLIPPER_REF"
fi
run python -m venv "$KLIPPER_ENV"
run "$KLIPPER_ENV/bin/pip" install --upgrade pip wheel
run "$KLIPPER_ENV/bin/pip" install -r "$KLIPPER_DIR/scripts/klippy-requirements.txt"

MOONRAKER_DIR="$TERMUX_HOME/moonraker"
MOONRAKER_ENV="$TERMUX_HOME/moonraker-env"
if (( INSTALL_MOONRAKER )); then
  log "Installing native Moonraker"
  if [[ -d "$MOONRAKER_DIR/.git" ]]; then
    run git -C "$MOONRAKER_DIR" fetch origin
  else
    run git clone https://github.com/Arksine/moonraker.git "$MOONRAKER_DIR"
  fi
  if [[ "$CHANNEL" == edge ]]; then
    run git -C "$MOONRAKER_DIR" checkout master
    run git -C "$MOONRAKER_DIR" pull --ff-only origin master
  else
    run git -C "$MOONRAKER_DIR" checkout --detach "$MOONRAKER_REF"
  fi
  run python -m venv "$MOONRAKER_ENV"
  run "$MOONRAKER_ENV/bin/pip" install --upgrade pip wheel
  run "$MOONRAKER_ENV/bin/pip" install -r "$MOONRAKER_DIR/scripts/moonraker-requirements.txt"
fi

render_template() {
  local source="$1" destination="$2"
  if [[ -e "$destination" ]]; then return 0; fi
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
render_template "$SOURCE_DIR/installer/config/bridge.conf.example" "$DATA_DIR/config/bridge.conf.example"
render_template "$SOURCE_DIR/installer/config/printer.cfg.example" "$DATA_DIR/config/printer.cfg.example"
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
  render_template "$template" "$directory/run"
  render_template "$SOURCE_DIR/installer/services/log.run" "$directory/log/run"
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
  MAINSAIL_DIR="$TERMUX_HOME/mainsail"
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
  render_template "$SOURCE_DIR/installer/config/nginx.conf" "$DATA_DIR/config/nginx.conf"
  install_service "klipper-web" "$SOURCE_DIR/installer/services/nginx.run"
fi

SERVICE_NAMES="klipper-android-bridge klipper"
(( INSTALL_MOONRAKER )) && SERVICE_NAMES+=" moonraker"
(( INSTALL_UI )) && SERVICE_NAMES+=" klipper-web"
render_template "$SOURCE_DIR/installer/kabctl" "$BIN_DIR/kabctl"
render_template "$SOURCE_DIR/installer/klipper-android-runner" "$BIN_DIR/klipper-android-runner"
run chmod 0755 "$BIN_DIR/kabctl" "$BIN_DIR/klipper-android-runner"

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
