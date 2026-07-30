#!/usr/bin/env bash
set -e

# Runs script/smoke-test.js, supplying a virtual display when there isn't one.
# Needs a build: script/build.sh.

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ -n "$DISPLAY" ]; then
  exec node script/smoke-test.js
elif [ "$(uname)" = "Darwin" ]; then
  # macOS has no X display and never will. Electron talks to the window server
  # directly, which is present on a Mac and on GitHub's macOS runners, so there
  # is nothing to arrange — asking for xvfb here would fail on a working
  # machine.
  exec node script/smoke-test.js
elif command -v xvfb-run >/dev/null 2>&1; then
  exec xvfb-run -a --server-args="-screen 0 1280x820x24" node script/smoke-test.js
else
  echo "No DISPLAY and no xvfb-run; install xvfb or run with a display." >&2
  exit 1
fi
