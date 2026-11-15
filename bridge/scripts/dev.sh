#!/usr/bin/env bash
# Dev helper for the ugs-bridge fork. Pins JDK 25 (Homebrew openjdk@25) without touching your shell profile.
#   bridge/scripts/dev.sh build          full build, no tests (~first run downloads deps)
#   bridge/scripts/dev.sh build-bridge   build only the bridge module and what it depends on
#   bridge/scripts/dev.sh run            launch the dev UGS Platform (separate userdir from the installed app)
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
  run)          ./mvnw nbm:run-platform -pl ugs-platform/application ;;
  test)         shift; ./mvnw -B test -pl "${1:?module path}" ;;
  mvn)          shift; ./mvnw "$@" ;;
  *)            sed -n '2,8p' "$0"; exit 1 ;;
esac
