#!/usr/bin/env bash
set -e

# Runs script/smoke-test.mts. Needs a build: script/build.sh.
#
# Headless by default — the windows are created hidden, so nothing appears on
# your desktop and nothing steals focus from what you were typing into. Pass
# --headed to watch it, which is the only way to see what a failing check was
# looking at.
#
# Hidden is not the same as displayless: Chromium still needs a display server,
# which is why Linux without one still goes through xvfb.

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ "$1" = "--headed" ]; then
  export LT_HEADED=1
  shift
else
  export LT_HEADLESS=1
fi

if [ -n "$DISPLAY" ]; then
  exec node script/smoke-test.mts "$@"
elif [ "$(uname)" = "Darwin" ]; then
  # macOS has no X display and never will. Electron talks to the window server
  # directly, which is present on a Mac and on GitHub's macOS runners, so there
  # is nothing to arrange — asking for xvfb here would fail on a working
  # machine.
  exec node script/smoke-test.mts "$@"
elif command -v xvfb-run >/dev/null 2>&1; then
  exec xvfb-run -a --server-args="-screen 0 1280x820x24" node script/smoke-test.mts "$@"
else
  echo "No DISPLAY and no xvfb-run; install xvfb or run with a display." >&2
  exit 1
fi
