#!/usr/bin/env bash
set -e

# Build LightTable app and CLI and place in builds/.
# Specify $VERSION to override default build version.
# Pass `--release` to build a release version.
# This script primarily installs dependencies and sets up
# the app before calling build-app.sh to build it.

# Check if npm is installed
[ "`which npm`" ] || { echo >&2 "Please install npm before running this script."; exit 1; }

# Ensure we start in project root
cd "$(dirname "${BASH_SOURCE[0]}")"; cd ..

# Ensure we have current version of electron.
#
# Two steps, because Electron 43 dropped the postinstall that used to fetch the
# binary — `npm install` now brings down the package and nothing else, and the
# download is an explicit `install-electron`. It is a no-op once the binary is
# there, so re-running this is cheap.
pushd deploy/electron
  npm install
  npx --no-install install-electron
popd

# Ensure we have current version of core
pushd deploy/core
  npm install
popd

# Build the core cljs

# Build tooling lives at the repo root
npm install

# The main process. The window's own TypeScript modules are build:cljs's first
# step, because shadow-cljs consumes their output — running them here as well
# compiled src-window twice per build for identical output.
npm run build:main

# The window bundle, the default user plugin, the worker thread that backs
# lt.objs.thread, and the plugins. The worker is a separate target because it
# runs under node rather than in the window.
#
# Plugins are in here rather than a step of their own: `build:plugins` is a
# subsequence of `build:cljs` — the same TypeScript compile, the same dependency
# install, the same directory copy — so running both did the plugin half twice.
# Every plugin lives in this repository and is built from source against the
# editor it extends; nothing is cloned and nothing is downloaded but each
# plugin's own npm dependencies.
rm -f deploy/core/lighttable/bootstrap.js
npm run build:cljs

script/build-app.sh $@
