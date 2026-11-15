#!/usr/bin/env bash
# Prove UGS itself can drive the simulator: ugs-core's GrblController over the TCP driver,
# headless (no GUI, no settings files, no serial ports, mDNS disabled). Maven runs offline and
# only compiles ugs-core into this checkout's target/ (nothing is installed into ~/.m2).
#
#   bridge/sim/scripts/ugs-compat.sh [file.nc] [time-scale]
#
# Starts ugs-sim on 127.0.0.1 with ephemeral ports and kills it on exit.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
bridge="$(cd "$here/../.." && pwd)"
repo="$(cd "$bridge/.." && pwd)"
file="${1:-}"
scale="${2:-20}"
work="$(mktemp -d "${TMPDIR:-/tmp}/ugs-sim-check.XXXXXX")"
sim_pid=""

cleanup() {
  if [[ -n "$sim_pid" ]]; then
    kill "$sim_pid" 2>/dev/null || true
    wait "$sim_pid" 2>/dev/null || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT

echo "== compile ugs-core (offline) and resolve its classpath"
"$bridge/scripts/dev.sh" mvn -B -o -q -pl ugs-core compile \
  org.apache.maven.plugins:maven-dependency-plugin:3.6.0:build-classpath \
  -Dmdep.outputFile="$work/cp.txt" 2> >(grep -v '^WARNING' >&2)
cp="$repo/ugs-core/target/classes:$(cat "$work/cp.txt")"
java_home="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
"$java_home/bin/javac" -nowarn -d "$work/classes" -cp "$cp" "$bridge/sim/java/UgsSimCheck.java"

echo "== start ugs-sim (time scale $scale)"
if ! "$bridge/.venv/bin/python" -c 'import ugs_sim' 2>/dev/null; then
  (cd "$bridge" && uv sync --all-packages -q)
fi
"$bridge/.venv/bin/python" -m ugs_sim.server --port 0 --time-scale "$scale" \
  >"$work/sim.out" 2>"$work/sim.err" &
sim_pid=$!
for _ in $(seq 100); do
  grep -q '^CONTROL ' "$work/sim.out" 2>/dev/null && break
  sleep 0.1
done
port="$(sed -n 's/^LISTENING 127\.0\.0\.1:\([0-9]*\)$/\1/p' "$work/sim.out")"
cport="$(sed -n 's/^CONTROL 127\.0\.0\.1:\([0-9]*\)$/\1/p' "$work/sim.out")"
[[ -n "$port" && -n "$cport" ]] || { cat "$work/sim.err" >&2; echo "ugs-sim did not start" >&2; exit 1; }
echo "   $(head -2 "$work/sim.out" | tr '\n' ' ')"

# user.home points at an empty temp dir: if anything tried to write UGS settings it lands
# there (and is reported below), never in the real ~/.ugs or ~/Library.
mkdir "$work/home"
set +e
"$java_home/bin/java" -Duser.home="$work/home" -Djava.awt.headless=true \
  -cp "$work/classes:$cp" UgsSimCheck "$port" "$cport" ${file:+"$file"}
status=$?
set -e
if [[ -n "$(find "$work/home" -mindepth 1 -print -quit)" ]]; then
  echo "WARNING: something wrote under user.home:" >&2
  find "$work/home" -mindepth 1 >&2
  status=1
else
  echo "== user.home stayed empty (no UGS settings written)"
fi
exit "$status"
