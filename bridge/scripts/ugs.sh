#!/usr/bin/env bash
# Kick off the ugs-bridge build of Universal Gcode Sender.
#
#   bridge/scripts/ugs.sh            run for the real machine (connect in the UGS window as usual)
#   bridge/scripts/ugs.sh sim        run against the built-in Shapeoko simulator (no machine needed)
#   bridge/scripts/ugs.sh build      rebuild, then exit
#   bridge/scripts/ugs.sh --build …  force a rebuild before running (otherwise it builds only when missing or stale)
#   bridge/scripts/ugs.sh mcp        print the command that registers Claude's MCP server
#
# Machine mode keeps its own UGS settings, separate from the installed UGS 2.1.23. On the first run it copies
# your installed settings (port, baud, macros, tool library) so nothing needs re-entering; after that the two
# never touch each other's settings. Claude starts LOCKED every launch: unlock it from the padlock in the toolbar.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export JAVA_HOME

APP="ugs-platform/application/target/ugsplatform/bin/ugsplatform"
BRIDGE_HOME="$HOME/Library/Application Support/ugs-bridge"      # token, notify.json, logs, origins
UGS_HOME="$BRIDGE_HOME/ugs-home"                                  # this build's own UGS settings + userdir

stale() {
  [ -x "$APP" ] || return 0
  # Rebuild when any tracked Java/resource file is newer than the built app launcher.
  [ -n "$(find ugs-core/src ugs-platform -path '*/target' -prune -o \( -name '*.java' -o -name 'pom.xml' -o -name '*.properties' \) -newer "$APP" -print -quit)" ] && return 0
  [ -n "$(find bridge/viewer/src bridge/viewer/index.html bridge/profiles -newer "$APP" -print -quit 2>/dev/null)" ] && return 0
  return 1
}

build() {
  echo "Building UGS with the bridge (a few minutes the first time)…"
  ./mvnw -B -q -o install -DskipTests -pl ugs-platform/application -am \
    || ./mvnw -B -q install -DskipTests -pl ugs-platform/application -am
  touch "$APP"
  echo "Build done."
}

seed_settings() {
  local dst="$UGS_HOME/home/Library/Preferences/ugs"
  [ -f "$dst/UniversalGcodeSender.json" ] && return 0
  mkdir -p "$dst"
  if [ -d "$HOME/Library/Preferences/ugs" ]; then
    cp -R "$HOME/Library/Preferences/ugs/." "$dst/"
    echo "First run: copied your installed UGS settings into $dst"
  fi
}

FORCE=0
if [ "${1:-}" = "--build" ]; then FORCE=1; shift; fi
MODE="${1:-machine}"

case "$MODE" in
  mcp)
    echo "claude mcp add ugs -- uv run --directory \"$REPO/bridge\" --package ugs-mcp ugs-mcp"
    exit 0 ;;
  build)
    build; exit 0 ;;
  sim)
    { [ "$FORCE" = 1 ] || stale; } && build
    exec "$REPO/bridge/scripts/dev.sh" run-sim ;;
  machine)
    { [ "$FORCE" = 1 ] || stale; } && build
    if pgrep -f "$UGS_HOME/userdir" >/dev/null; then
      echo "ugs-bridge UGS is already running."; exit 0
    fi
    if pgrep -f "Universal Gcode Sender.app" >/dev/null; then
      echo "Note: the installed UGS is also running. Only one of them can hold the machine's serial port."
    fi
    mkdir -p "$UGS_HOME/home" "$UGS_HOME/userdir" "$UGS_HOME/cache"
    for d in Downloads Desktop Documents; do   # so UGS's file dialogs reach your usual folders
      [ -e "$UGS_HOME/home/$d" ] || ln -s "$HOME/$d" "$UGS_HOME/home/$d"
    done
    seed_settings
    echo "Starting UGS (ugs-bridge). Claude starts locked; use the padlock in the toolbar to allow access."
    echo "Logs and session files: $BRIDGE_HOME/logs"
    exec "$APP" --jdkhome "$JAVA_HOME" --userdir "$UGS_HOME/userdir" --cachedir "$UGS_HOME/cache" \
      -J-Duser.home="$UGS_HOME/home" -J-Dugs.bridge.home="$BRIDGE_HOME" ;;
  *)
    sed -n '2,12p' "$0"; exit 1 ;;
esac
