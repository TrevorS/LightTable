#!/usr/bin/env bash
set -e

# Create LightTable release using our local Electron installation
# (Mac, Linux, or Cygwin)

# Ensure we start in project root
cd "$(dirname "${BASH_SOURCE[0]}")"; cd ..

#----------------------------------------------------------------------
# Get OS-specific Electron details
#----------------------------------------------------------------------

ELECTRON_DIR="deploy/electron/node_modules/electron/dist"

# from: http://stackoverflow.com/a/17072017/142317
if [ "$(uname)" == "Darwin" ]; then
  OS="mac"
  # Two names for one bundle, and the order below is what makes that work:
  # RESOURCES is used while the app is still Electron.app, PLIST after it has
  # been renamed.
  RESOURCES="Electron.app/Contents/Resources"
  PLIST="LightTable.app/Contents/Info.plist"
  PLATFORM_DIR="deploy/platform/mac"

elif [ "$(expr substr $(uname -s) 1 5)" == "Linux" ]; then
  OS="linux"
  RESOURCES="resources"
  PLATFORM_DIR="deploy/platform/linux"

elif [ "$(expr substr $(uname -s) 1 9)" == "CYGWIN_NT" ]; then
  OS="windows"
  RESOURCES="resources"
  PLATFORM_DIR="deploy/platform/win"

else
  echo "Cannot detect a supported OS."
  exit 1
fi

#----------------------------------------------------------------------
# Determine release name and output location
#----------------------------------------------------------------------

# deploy/core/package.json is the app's own manifest — the one Electron reads
# at startup — so the release is named from it rather than from a build file.
# This used to parse project.clj's first line with `cut`, which stopped being a
# defproject form and became a comment; the release then came out as
# `Light-Table-mac`, with "Table" for a version, and nothing said so.
META=deploy/core/package.json
NAME=`node -p "require('./$META').name"`
DEFAULT_VERSION=`node -p "require('./$META').version"`
: ${VERSION:="$DEFAULT_VERSION"}

# Anything unset here would silently produce a release called `-` or `-mac`.
if [ -z "$NAME" ] || [ -z "$VERSION" ]; then
  echo "Could not read name and version from $META." >&2
  exit 1
fi

BUILDS=builds
RELEASE="$NAME-$VERSION-$OS"
RELEASE_DIR="$BUILDS/$RELEASE"
RELEASE_TARBALL="${RELEASE}.tar.gz"
RELEASE_ZIP="${RELEASE}.zip"
RELEASE_RSRC="$RELEASE_DIR/$RESOURCES"

rm -rf $RELEASE_DIR $RELEASE_TARBALL $RELEASE_ZIP
rm -rf $RELEASE_DIR "$BUILDS/$RELEASE_TARBALL" "$BUILDS/$RELEASE_ZIP"

#----------------------------------------------------------------------
# Copy Electron installation and app directory into output location
#----------------------------------------------------------------------

echo "Creating $RELEASE_DIR ..."
mkdir -p $RELEASE_DIR
cp -R $ELECTRON_DIR/* $RELEASE_DIR
rm -f $RELEASE_DIR/version
cp LICENSE.md $RELEASE_DIR/LICENSE

mkdir $RELEASE_RSRC/app
cp -R deploy/core $RELEASE_RSRC/app/
cp deploy/core/package.json $RELEASE_RSRC/app/
# sed -i with arg is only cross platform way. -i '' doesn't work across platforms
sed -i.bak 's/"main.js"/"core\/main.js"/' $RELEASE_RSRC/app/package.json
rm $RELEASE_RSRC/app/package.json.bak
cp -R deploy/settings $RELEASE_RSRC/app/
cp -R deploy/plugins "${RELEASE_RSRC}"/app/
rm -rf "${RELEASE_RSRC}"/app/plugins/*/.git

#----------------------------------------------------------------------
# Polishing
#----------------------------------------------------------------------

if [ "$OS" == "mac" ]; then

  cp $PLATFORM_DIR/light $RELEASE_DIR/
  mv $RELEASE_DIR/Electron.app $RELEASE_DIR/LightTable.app

  # Patch Electron's own Info.plist rather than replace it.
  #
  # This used to drop a 2014-era plist over the top, which silently discarded
  # every key Electron sets deliberately: NSHighResolutionCapable (so the app
  # rendered non-Retina), NSRequiresAquaSystemAppearance=false (so it was
  # forced into light mode, which a dark editor notices), DTSDKName (so macOS
  # applied the behavioural defaults of a decade-old SDK), the camera,
  # microphone and Bluetooth usage descriptions the browser tab needs to not be
  # killed on request, NSAppTransportSecurity, and CFBundleVersion.
  #
  # What Light Table actually needs to say is below. plutil ships with macOS.
  PLIST_PATH="$RELEASE_DIR/$PLIST"
  plutil -replace CFBundleDisplayName        -string "Light Table"           "$PLIST_PATH"
  plutil -replace CFBundleName               -string "LightTable"            "$PLIST_PATH"
  plutil -replace CFBundleIdentifier         -string "com.kodowa.LightTable" "$PLIST_PATH"
  # Resolved against Contents/Resources, where deploy/core lands as app/core.
  plutil -replace CFBundleIconFile           -string "app/core/img/app.icns" "$PLIST_PATH"
  plutil -replace CFBundleShortVersionString -string "$VERSION"              "$PLIST_PATH"
  plutil -replace CFBundleVersion            -string "$VERSION"              "$PLIST_PATH"
  plutil -replace CFBundleDevelopmentRegion  -string "en"                    "$PLIST_PATH"
  plutil -replace LSFileQuarantineEnabled    -bool   true                    "$PLIST_PATH"
  # The file types Light Table registers as an editor for.
  plutil -replace CFBundleDocumentTypes -json "$(cat $PLATFORM_DIR/document-types.json)" "$PLIST_PATH"
  # Electron checks this against default_app.asar, which this build replaces
  # with a plain directory. Leaving a hash of something no longer there is
  # worse than saying nothing.
  plutil -remove ElectronAsarIntegrity "$PLIST_PATH" 2>/dev/null || true

  # Ad-hoc signing, so macOS stops asking to accept incoming connections and so
  # the bundle runs at all on Apple Silicon, where an unsigned or
  # inconsistently signed app is refused. Inner-out: --deep is deprecated and
  # is unreliable for Electron, whose frameworks and helper apps are separate
  # bundles that have to be signed before the one containing them.
  # -depth so the deepest bundles are signed first: a helper inside a framework
  # has to be sealed before the framework that contains it.
  find "$RELEASE_DIR/LightTable.app/Contents/Frameworks" -depth \
       \( -name '*.app' -o -name '*.framework' -o -name '*.dylib' \) -print0 2>/dev/null |
    xargs -0 -I{} codesign --force --sign - "{}" 2>/dev/null || true
  codesign --force --sign - "$RELEASE_DIR/LightTable.app"
  # Say plainly whether the bundle is actually valid, rather than leaving it to
  # be discovered by a launch that dies with no message.
  codesign --verify --deep --strict "$RELEASE_DIR/LightTable.app" ||
    echo "WARNING: the signature did not verify; the app may not launch." >&2

elif [ "$OS" == "linux" ]; then

  cp $PLATFORM_DIR/light $RELEASE_DIR/

  mv $RELEASE_DIR/electron $RELEASE_DIR/LightTable

elif [ "$OS" == "windows" ]; then

  mv $RELEASE_DIR/electron.exe $RELEASE_DIR/LightTable.exe
  RCEDIT_PATH=`which rcedit` || { echo "expected to find rcedit; unable to rebrand the exe"; }
  if [ "$RCEDIT_PATH" != "" ]; then
    rcedit "$RELEASE_DIR/LightTable.exe" \
      --set-icon deploy/core/img/lticon.ico \
      --set-file-version "$VERSION" \
      --set-product-version "$VERSION" \
      --set-version-string "FileDescription" "Light Table" \
      --set-version-string "ProductName" "Light Table" \
      --set-version-string "CompanyName" "" \
      --set-version-string "LegalCopyright" "" \
      --set-version-string "OriginalFilename" ""
  fi

fi

#----------------------------------------------------------------------
# Create release version: tarball or zip file
#----------------------------------------------------------------------

if [ "$1" == "--release" ]; then
  # Create zip file for Cygwin (Windows) using 7-Zip
  if [ "$OS" == "windows" ]; then
    pushd "$BUILDS"
    "/cygdrive/c/Program Files/7-Zip/7z.exe" a $RELEASE_ZIP "$RELEASE/*"
    popd
  else
    pushd "$BUILDS"
    tar -zcvf $RELEASE_TARBALL $RELEASE/*
    popd
  fi
fi

echo DONE!
