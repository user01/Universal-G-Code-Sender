#!/usr/bin/env bash
# Dev helper for the ugs-bridge fork. Pins JDK 25 (Homebrew openjdk@25) without touching your shell profile.
#   bridge/scripts/dev.sh build          full build, no tests (~first run downloads deps)
#   bridge/scripts/dev.sh build-bridge   build only the bridge module and what it depends on
#   bridge/scripts/dev.sh run            launch the built dev UGS with an isolated home (bridge/.dev-home):
#                                        its own UGS settings, NetBeans userdir and bridge folder; never the installed app's
#                                        touch bridge/.dev-home/snapshots/<name>.request → <name>.png of the main window
#   bridge/scripts/dev.sh run-sim        start bridge/sim on a loopback port, then the dev UGS connected to it over TCP
#   bridge/scripts/dev.sh test <module>  run tests for one module, e.g. ugs-core
#   bridge/scripts/dev.sh mvn <args…>    any other Maven invocation
set -euo pipefail
JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export JAVA_HOME
cd "$(dirname "$0")/../.."

BRIDGE_MODULE=ugs-platform/ugs-platform-plugin-bridge

case "${1:-}" in
  build)        ./mvnw -B install -DskipTests=true ;;
  build-bridge) ./mvnw -B install -DskipTests=true -pl "$BRIDGE_MODULE" -am ;;
  run)
    DEV_HOME="$PWD/bridge/.dev-home"; mkdir -p "$DEV_HOME/home" "$DEV_HOME/userdir" "$DEV_HOME/cache" "$DEV_HOME/bridge"
    APP=ugs-platform/application/target/ugsplatform/bin/ugsplatform
    [ -x "$APP" ] || { echo "build first: dev.sh mvn -B -o install -DskipTests -pl ugs-platform/ugs-platform-plugin-bridge,ugs-platform/application"; exit 1; }
    exec "$APP" --jdkhome "$JAVA_HOME" --userdir "$DEV_HOME/userdir" --cachedir "$DEV_HOME/cache" \
      -J-Duser.home="$DEV_HOME/home" -J-Dugs.bridge.home="$DEV_HOME/bridge" -J-Dugs.bridge.dev.snapshots="$DEV_HOME/snapshots" "${@:2}" ;;
  run-sim)
    LOG="$PWD/bridge/.dev-home/sim.log"; mkdir -p "$PWD/bridge/.dev-home"
    (cd bridge && exec uv run ugs-sim --host 127.0.0.1 --port 0 --time-scale "${SIM_TIME_SCALE:-5}") >"$LOG" 2>&1 &
    SIM_PID=$!; trap 'kill $SIM_PID 2>/dev/null' EXIT
    for _ in $(seq 1 100); do grep -q '^LISTENING' "$LOG" && break; sleep 0.1; done
    PORT=$(sed -n 's/^LISTENING 127.0.0.1:\([0-9]*\).*/\1/p' "$LOG" | head -1)
    [ -n "$PORT" ] || { echo "simulator did not start; see $LOG"; exit 1; }
    echo "simulator on 127.0.0.1:$PORT (log $LOG)"
    "$0" run -J-Dugs.bridge.dev.connect="127.0.0.1:$PORT" ;;
  test)         shift; ./mvnw -B test -pl "${1:?module path}" ;;
  mvn)          shift; ./mvnw "$@" ;;
  *)            sed -n '2,8p' "$0"; exit 1 ;;
esac
