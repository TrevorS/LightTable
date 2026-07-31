#!/usr/bin/env bash
set -e

# Runs the Playwright integration tests, supplying a virtual display when there
# isn't one. Needs a build: script/build.sh.
#
# The same three cases as script/smoke-test.sh, for the same reason — these
# launch the real application, and Electron needs somewhere to draw. Arguments
# are handed to `playwright test`, so `script/e2e.sh --grep window` works.

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ -n "$DISPLAY" ]; then
  exec npx playwright test "$@"
elif [ "$(uname)" = "Darwin" ]; then
  # macOS has no X display and never will. Electron talks to the window server
  # directly, which is present on a Mac and on GitHub's macOS runners.
  exec npx playwright test "$@"
elif command -v xvfb-run >/dev/null 2>&1; then
  exec xvfb-run -a --server-args="-screen 0 1280x820x24" npx playwright test "$@"
else
  echo "No DISPLAY and no xvfb-run; install xvfb or run with a display." >&2
  exit 1
fi
