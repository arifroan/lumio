# Native Music migration (staged)

This branch introduces the first isolated Kotlin + Jetpack Compose building blocks for the LUMIO Music section.

## Safety boundary

- The existing `MainActivity`, WebView, `player.html`, Video section, player activities, and navigation remain untouched.
- The new `NativeMusicScreen` is not wired into app navigation yet. The current app should continue to launch through its existing WebView while the native path is built and validated.
- Do not merge this branch into the default branch until a Gradle build and device tests pass.

## Added

- Kotlin and Compose Gradle plugins/dependencies.
- `NativeMusicLibrary`: reads indexed music items from Android MediaStore and tolerates missing metadata/permission failures.
- `NativeMusicScreen`: isolated Compose UI for searching songs and browsing folder paths.

## Next integration steps

1. Run a clean debug build and resolve any toolchain/dependency issues.
2. Add lifecycle-aware loading and explicit permission handling around `NativeMusicLibrary.load`.
3. Connect `onPlay` to the existing Media3 `PlayerActivity` using each track's content URI and a serialized playlist.
4. Match LUMIO's current theme system, including Porcelain/Arctic Silver and Ocean Blue, before switching the Music route.
5. Test on physical Android devices: permission denial/regrant, empty library, duplicate filenames, nested folders, long lists, playback, background controls, and theme changes.
6. Only then wire the native Music route into navigation; retain a reversible fallback until validation is complete.

## Validation status

No Android build or physical-device test has been run from this GitHub-only editing session. This is an incremental implementation branch, not a finished APK or a completed migration.
