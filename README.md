# Lumio — Native Android Media Player

Lumio is a free media-player project. **The primary development target is the native Android app** in `android-native/`; the repository also contains a browser-based player.

The Android app uses AndroidX Media3 (ExoPlayer) and Android's document picker to let people choose local audio and video files. Selected media stays on the device; Lumio does not upload it.

## Current Android prototype
- Native Android interface with Lumio branding
- Launcher icon and animated splash screen
- Choose local audio/video files through Android's document picker
- In-app media list with persisted URI references when Android permissions remain valid
- Media3 playback controls
- External subtitle selection for video
- Fullscreen toggle
- Automated APK builds through GitHub Actions

**Status:** early prototype, version 0.5.0 in preparation. CI success does not replace testing on a physical Android phone. File/container/codec support varies by device and Android/Media3 decoder support.

## Download a test APK
- [Latest Android APK release](../../releases/tag/android-latest)
- [Android APK build workflow](../../actions/workflows/android-apk.yml)

Download `app-debug.apk` from the release page. This is a debug/test build, not a signed production release. Android may ask you to allow installation from your browser or file manager.

## Build
Open the repository's **Actions** tab and select **Build Lumio Native Android APK**. The workflow builds the native project in `android-native/` and publishes the APK when the workflow completes successfully.

## Roadmap to v1.0.0
See [ROADMAP.md](ROADMAP.md) for the milestone plan from v0.5.0 through the first stable public release.

## Privacy and permissions
Lumio uses Android's file picker so users choose which media it can access. The library stores selected content URI references locally; access can become invalid if a file is moved, deleted, or permission is revoked. No account or API key is required.

## Project structure
- `android-native/` — native Android app
- `index.html`, `app.js`, `styles.css` — browser player
- `.github/workflows/android-apk.yml` — automated Android APK build
