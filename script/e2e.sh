#!/usr/bin/env bash
set -e

# Runs the Playwright integration tests. Needs a build: script/build.sh.
#
# Headless by default, for the reason script/smoke-test.sh gives: sixteen
# windows appearing and taking focus is how a suite stops being run locally.
# Pass --headed to watch it. Every other argument goes to `playwright test`,
# so `script/e2e.sh --headed --grep window` works.

cd "$(dirname "${BASH_SOURCE[0]}")/.."

ARGS=()
export LT_HEADLESS=1
for arg in "$@"; do
  if [ "$arg" = "--headed" ]; then
    export LT_HEADED=1
    unset LT_HEADLESS
  else
    ARGS+=("$arg")
  fi
done

if [ -n "$DISPLAY" ] || [ "$(uname)" = "Darwin" ]; then
  exec npx playwright test "${ARGS[@]}"
elif command -v xvfb-run >/dev/null 2>&1; then
  exec xvfb-run -a --server-args="-screen 0 1280x820x24" npx playwright test "${ARGS[@]}"
else
  echo "No DISPLAY and no xvfb-run; install xvfb or run with a display." >&2
  exit 1
fi
