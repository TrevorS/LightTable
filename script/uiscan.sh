#!/usr/bin/env bash
set -e

# Screenshots the editor in every state worth looking at, and audits each one.
# Needs a build: script/build.sh, or npm run build:cljs && npm run build:main.
#
#   script/uiscan.sh                        every state
#   script/uiscan.sh --only settings,keys   just those
#   script/uiscan.sh --list                 what the states are
#   script/uiscan.sh --strict               exit 1 if anything is found
#
# Always headless. Not for the reason e2e.sh is — no window appearing is what
# makes this a thing you run while working rather than a thing that takes over
# your desktop for a minute. There is no --headed, and if you want to watch the
# editor do something, run it.

cd "$(dirname "${BASH_SOURCE[0]}")/.."

export LT_HEADLESS=1

if [ -n "$DISPLAY" ] || [ "$(uname)" = "Darwin" ]; then
  exec node script/uiscan.mts "$@"
elif command -v xvfb-run >/dev/null 2>&1; then
  exec xvfb-run -a --server-args="-screen 0 1600x1000x24" node script/uiscan.mts "$@"
else
  echo "No DISPLAY and no xvfb-run; install xvfb or run with a display." >&2
  exit 1
fi
