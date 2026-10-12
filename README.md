# Train Rush Lite

A small 3D endless train game for Android TV / smart TVs, controlled with the TV remote.
Drive a steam train down three tracks, collect coins, dodge barriers, rocks, parked wagons and oncoming trains.

**Built to be light:** no libraries, no textures, no audio files. All 3D shapes and all sounds are generated in code.
The APK should be well under 1 MB and the game uses very little RAM.

> Note: this project was written without an Android SDK available, so it has been checked with a stub compile only,
> not run on a real TV. Build it with the GitHub workflow below and report anything that misbehaves on your model.

## Get the APK from GitHub (no setup on your PC)

1. Create a GitHub repository and upload this whole folder (keep the `.github` folder).
2. Open the **Actions** tab, run **Build APK** (it also runs on every push).
3. Download `TrainRushLite-apk` from the finished run. Push a tag like `v1.0` to get it on the **Releases** page too.

The workflow points the app at `themes/themes.json` in *your* repository automatically.

## Install on the TV

- Easiest: copy `TrainRushLite.apk` to a USB stick and open it with a file manager app on the TV, or use "Send Files to TV".
- Or over the network: `scripts/install-tv.sh <TV_IP> TrainRushLite.apk`
  (enable Developer options and ADB debugging on the TV first). This also pre-loads the themes.

## Controls (remote)

| Where | Key | Action |
|---|---|---|
| Menu | UP / DOWN | choose Theme, Quality, Music |
| Menu | LEFT / RIGHT | change the selected option |
| Menu | OK | start |
| Game | LEFT / RIGHT | change track |
| Game | UP / DOWN (hold) | faster / brake |
| Game | OK | horn |
| Game | BACK or Play/Pause | pause |
| Pause / Game over | OK / BACK | continue or retry / back to menu |

Keyboard (WASD, Enter, Esc), gamepad (D-pad, A, B, Start) and touch (left / middle / right third of the screen) also work.

## Themes and the install-time download

- Two themes are built in, so the game works offline.
- On the **first launch** the game downloads `themes.json` (a few KB of colours and settings, never code) from
  `themeUrl` and stores it in its private storage. It refreshes at most once a week and silently falls back to
  the built-in themes when offline.
- To use your own themes: edit `themes/themes.json`, push to GitHub. Each theme can set sky, ground, track,
  train and obstacle colours, scenery (`pine`, `cactus`, `city`, `round`), fog distance and the music tempo/key.
  Missing fields use the Sunny defaults.
- No internet on the TV? Run `scripts/install-tv.sh`, or `adb push themes/themes.json /sdcard/Android/data/com.trainrush.lite/files/`.
- To change the download address locally, edit `themeUrl` in `gradle.properties`.

## "Required libraries" script

The game itself needs none. `scripts/setup.sh` installs what is needed to **build** on your own Linux/macOS machine
(JDK 17, Gradle, Android SDK 34); then run `gradle assembleRelease`. On GitHub this is done for you.

## Low-end TV tips

- Menu > **Quality: Low** renders at 540p and caps 30 fps; the TV scales it up. **Medium** is 720p.
- Memory: 16-bit colour and depth buffers, no textures, no object allocation while playing.
- Signing: the workflow creates a throw-away key. To keep updates installable over each other, add the repository
  secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Files

- `app/src/main/java/com/trainrush/lite/` - `Game` (rules), `GameRenderer` + `Mesh` (OpenGL ES 2.0),
  `SoundEngine` (synthesised audio), `Theme` / `ThemeManager` (themes + download), `HudView` (menus and text),
  `MainActivity` (remote keys).
- `themes/themes.json` - the downloadable theme pack.
- `.github/workflows/build-apk.yml` - builds the APK.
