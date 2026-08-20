#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OUTPUT="${1:-$ROOT/klipper-android-bridge}"
CC_BIN="${CC:-clang}"
PLATFORM_DEFINES=(-D_GNU_SOURCE)
[[ "$(uname -s)" == Darwin ]] && PLATFORM_DEFINES+=(-DK4A_APPLE=1)

"$CC_BIN" -std=c11 -O2 -DNDEBUG "${PLATFORM_DEFINES[@]}" \
  -Wall -Wextra -Wpedantic \
  -I"$ROOT/bridge/include" \
  "$ROOT/bridge/src/bridge.c" "$ROOT/bridge/src/config.c" \
  -o "$OUTPUT"
printf 'Bridge: %s\n' "$OUTPUT"
