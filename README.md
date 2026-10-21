# Lumio

Lumio is an offline-first Android media player with its own visual identity. It uses AndroidX Media3 / ExoPlayer for native playback instead of copying another player's source code.

## Current Android draft

Development branch: `draft/android-player`

The current draft includes local media selection/scanning, a folder-based library, queued playback, gesture seeking, brightness/volume controls, playback-speed options, subtitle/track menus, resume position, a background MediaSession, Picture-in-Picture support, decoder preferences, audio sync controls, a decoded-PCM gain control, and an encrypted private vault.

Feature-by-feature status and known limitations: [docs/FEATURE_ROADMAP.md](docs/FEATURE_ROADMAP.md).

## Get the APK

1. Open [Android APK workflow runs](https://github.com/arifroan/lumio/actions/workflows/android-apk.yml).
2. Open the newest **successful** run on `draft/android-player`.
3. Under **Artifacts**, download `lumio-android-debug-apk`.
4. Extract the ZIP and install the APK on an Android phone. Android may ask you to allow installation from the app used to open the APK.

GitHub Actions keeps each uploaded artifact for 14 days. The debug APK is intended for testing and is not the signed production release.

## Build

The GitHub Actions workflow uses Java 17, Gradle 8.13, Android Gradle Plugin 8.13.2, and runs:

```sh
cd android-native
gradle --no-daemon assembleDebug
```

The Android project lives in `android-native/`. No OpenAI API key or network permission is needed for core offline playback. An API key should only be added if a future, explicitly optional AI/network feature requires it.

## Important testing note

A successful CI build confirms compilation and APK packaging, not that every codec, gesture, audio sync, subtitle, PiP, storage provider, or device-specific behavior works. Test with your own local files on a physical Android device. Work is on `draft/android-player`; `main` has not been modified by this implementation work.
