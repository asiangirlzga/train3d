#!/usr/bin/env bash
# Installs the APK on a smart TV over the network and (optionally) pre-loads the theme pack,
# so the game also works when the TV has no internet.
#
# Usage: scripts/install-tv.sh <TV_IP> [path/to/TrainRushLite.apk] [themes-json-url-or-file]
# On the TV: Settings > About > tap "Build" 7 times, then Developer options > enable
# "USB debugging" / "ADB debugging over network".
set -euo pipefail

TV="${1:?usage: install-tv.sh <TV_IP> [apk] [themes.json url or file]}"
APK="${2:-TrainRushLite.apk}"
THEMES="${3:-themes/themes.json}"
PKG="com.trainrush.lite"

command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools first (scripts/setup.sh does it)."; exit 1; }

adb connect "$TV:5555"
adb -s "$TV:5555" install -r "$APK"

# Theme pack: download it here if it is a URL, then push it where the game looks for extra themes.
tmp="$(mktemp)"
case "$THEMES" in
  https://*) curl -fsSL -o "$tmp" "$THEMES" ;;
  *) cp "$THEMES" "$tmp" ;;
esac
adb -s "$TV:5555" shell mkdir -p "/sdcard/Android/data/$PKG/files"
adb -s "$TV:5555" push "$tmp" "/sdcard/Android/data/$PKG/files/themes.json" || \
  echo "Could not push themes (the game will download them itself on first start)."
rm -f "$tmp"

adb -s "$TV:5555" shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 || true
echo "Installed. Launch 'Train Rush Lite' from the TV's app row."
