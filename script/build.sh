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

# Ensure we have current version of electron
pushd deploy/electron
  npm install
popd

# Ensure we have current version of core
pushd deploy/core
  npm install
popd

# Build the core cljs

# Build tooling lives at the repo root
npm install

# The main process and the window's own TypeScript modules.
npm run build:main
npm run build:window

# The window bundle, the default user plugin, and the worker thread that backs
# lt.objs.thread. The worker is a separate target because it runs under node
# rather than in the window.
rm -f deploy/core/lighttable/bootstrap.js
npm run build:cljs

# Plugins that live in this repository, built from source against the editor
# they extend rather than fetched as prebuilt artifacts.
npm run build:plugins

# Fetch plugins
# Paredit is not here: it is built from source in plugins/ instead.
PLUGINS=("Clojure,0.3.3" "CSS,0.0.6" "HTML,0.1.0" "Javascript,0.2.0"
         "Python,0.0.7" "Rainbow,0.0.8")

# Plugins cache
mkdir -p deploy/plugins

pushd deploy/plugins
  for plugin in "${PLUGINS[@]}" ; do
      NAME="${plugin%%,*}"
      VERSION="${plugin##*,}"
      if [ -d $NAME ]; then
        echo "Updating plugin $NAME $VERSION..."
        cd $NAME
        git checkout --quiet master
        git pull --quiet
        git checkout --quiet $VERSION
        cd -
      else
        echo "Cloning plugin $NAME $VERSION..."
        git clone "https://github.com/LightTable/$NAME"
        cd $NAME
        git checkout --quiet $VERSION
        cd -
      fi
  done
popd

script/build-app.sh $@
