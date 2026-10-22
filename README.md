# Lumio

Lumio is an offline-first Android media player with its own visual identity. It uses AndroidX Media3 / ExoPlayer for native playback instead of copying another player's source code.

## Current Android draft

Development branch: `draft/android-player`

The current draft includes local media selection/scanning, searchable and sortable video browsing with cached thumbnails, navigable storage folders, persistent SD-card/USB folder grants through Android's document picker, cross-URI deduplication for external-storage paths, queued playback, Continue Watching, Recently Played, Favorites, gesture seeking, brightness/volume controls, playback-speed options, subtitle/track menus, resume position, a background MediaSession, Picture-in-Picture support, decoder preferences, audio sync controls, a decoded-PCM gain control, and an encrypted private vault.

Feature-by-feature status and known limitations: [docs/FEATURE_ROADMAP.md](docs/FEATURE_ROADMAP.md).

## Get the APK

Download the latest direct APK from the [Lumio Android Preview release](https://github.com/arifroan/lumio/releases/download/android-preview/Lumio-Android-Preview.apk). No ZIP extraction is required. On Android, open the downloaded APK and follow the installer prompts.

Check the [Android APK workflow run](https://github.com/arifroan/lumio/actions/workflows/android-apk.yml) if you want to verify that the latest build succeeded. Lumio's preview APK is signed with the persistent preview signing key configured in the repository's GitHub Actions secrets. It is a development preview, not a production Play Store release.

## Build

The GitHub Actions workflow uses Java 17, Gradle 8.13, Android Gradle Plugin 8.13.2, and runs:

```sh
cd android-native
gradle --no-daemon assembleDebug
```

The Android project lives in `android-native/`. No OpenAI API key or network permission is needed for core offline playback. An API key should only be added if a future, explicitly optional AI/network feature requires it.

## Important testing note

A successful CI build confirms compilation and APK packaging, not that every codec, gesture, audio sync, subtitle, PiP, storage provider, or device-specific behavior works. Test with your own local files on a physical Android device. Work is on `draft/android-player`; `main` has not been modified by this implementation work.
