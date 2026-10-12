#!/usr/bin/env bash
# Installs everything needed to build the APK on your own Linux/macOS machine:
#   JDK 17, Gradle, Android command-line tools, platform 34 and build-tools.
# (Building on GitHub needs none of this - the workflow does it for you.)
# The game itself has NO runtime libraries to install.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
[ "$(uname)" = "Darwin" ] && TOOLS_ZIP="commandlinetools-mac-11076708_latest.zip"

need() { command -v "$1" >/dev/null 2>&1; }

if ! need java || ! java -version 2>&1 | grep -q '"17\|"21'; then
  echo ">> Installing JDK 17"
  if need apt-get; then sudo apt-get update && sudo apt-get install -y openjdk-17-jdk unzip curl
  elif need brew; then brew install openjdk@17
  else echo "Install JDK 17 manually, then re-run."; exit 1; fi
fi

if ! need gradle; then
  echo ">> Installing Gradle"
  if need apt-get; then sudo apt-get install -y gradle
  elif need brew; then brew install gradle
  else echo "Install Gradle 8.x manually, then re-run."; exit 1; fi
fi

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo ">> Downloading Android command-line tools to $SDK"
  mkdir -p "$SDK/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

cd "$(dirname "$0")/.."
echo "sdk.dir=$SDK" > local.properties
echo
echo "Done. Build with:   gradle assembleRelease"
echo "APK appears in:     app/build/outputs/apk/release/app-release.apk"
