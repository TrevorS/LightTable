#!/usr/bin/env bash
set -e

# Runs script/screenshot.mts, supplying a virtual display when there isn't one.
# Needs a build: script/build.sh.
#
#   script/screenshot.sh src/lt/objs/platform.cljs project.clj

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ -n "$DISPLAY" ]; then
  exec node script/screenshot.mts "$@"
elif command -v xvfb-run >/dev/null 2>&1; then
  exec xvfb-run -a --server-args="-screen 0 1440x900x24" node script/screenshot.mts "$@"
else
  echo "No DISPLAY and no xvfb-run; install xvfb or run with a display." >&2
  exit 1
fi
