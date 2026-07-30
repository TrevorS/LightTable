#!/usr/bin/env bash
set -e

# Opens current LightTable without needing to build it.
# Assumes script/build.sh has been run at least once

# Ensure we start in project root
cd "$(dirname "${BASH_SOURCE[0]}")"; cd ..
DIR=$(pwd)

# The electron package knows where its own binary is, and where that is differs
# by platform — dist/electron on Linux, dist/Electron.app/Contents/MacOS/Electron
# on a Mac. Asking it is the only version of this that stays correct: the three
# paths this used to hard-code were all wrong, and had been since the package
# moved its binaries under node_modules/electron/dist.
CLI=$(node -p "require('${DIR}/deploy/electron/node_modules/electron')" 2>/dev/null) || {
  echo >&2 "Electron is not installed. Run script/build.sh first."
  exit 1
}

LT_DEV_CLI=true "$CLI" deploy/core "$@"
