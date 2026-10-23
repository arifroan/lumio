# Lumio Android feature and release status

Lumio is an offline-first Android media player with an original violet-and-lime interface. The native playback engine uses AndroidX Media3 ExoPlayer and MediaSession.

## Implemented in the draft branch

- Native video/audio playback screen built on Media3.
- Android document picker for local files, multi-select, persistent URI grants, and remembered picked-file library entries.
- MediaStore scanning for indexed videos/audio, grouped by folder, with file type, size and duration summaries.
- Videos library search by filename; sorting by name, newest, size, and duration; grid/list layout switching; and an on/off folder-grouping control.
- On-demand native video thumbnails generated asynchronously, resized, cached in app cache, and loaded as visible list/grid rows approach the viewport.
- Exact-URI duplicate merging and replacement of stale scan results after a successful refresh; unknown timestamps for picked files remain unknown instead of being guessed.
- Persistent Continue Watching and Recently Played sections for local videos, with playback position, duration, completion state, and last-played time stored locally.
- Persistent video Favorites with an All Videos/Favorites filter, per-video favorite controls, and a native actions menu for play, favorite, remove from recent history, and file details.
- Periodic per-item playback progress saving and resume from each URI's saved position; completed items restart at the beginning.
- Persistent Android Storage Access Framework folder grants using the system folder picker, with recursive media scanning through granted folders, including supported SD-card and USB-OTG document providers.
- In-app folder navigation with breadcrumbs, visible child folders, direct folder contents, and a direct jump into a newly selected folder.
- Storage-aware media identities that deduplicate MediaStore and Android external-storage document URIs for the same physical path, while keeping different storage volumes distinct.
- Background serialized media scanning with a bounded folder traversal; partial permission, revoked folder, and scan-limit conditions preserve existing library entries rather than clearing the list.
- Same-folder/type playlist queue with next-item autoplay.
- Speed selection from 0.25x to 4x.
- Double-tap seeking by ±10 seconds, horizontal seek gesture, vertical brightness and volume gestures, pinch zoom/pan, and playback touch lock.
- Resume last position per local content URI.
- MediaSessionService for background playback and lock-screen/Bluetooth media controls; pause when audio becomes noisy, such as a headset disconnect.
- Android Picture-in-Picture support and a manual PiP control for video.
- Embedded audio-track selection and supported subtitle-track selection.
- External SRT, WebVTT, SSA/ASS subtitles; MicroDVD SUB conversion with an assumed frame rate of 25 fps.
- Subtitle font size and appearance controls (where supported by the selected subtitle renderer).
- Audio timestamp offset slider from -2000 ms to +2000 ms in 50 ms steps. Actual sync behavior varies by codec/device and needs physical testing.
- Decoder preference selector: Automatic, Hardware preferred, Software preferred. Media3 is allowed to use fallback codecs; these are preferences, not hard guarantees, and “HW+” is vendor-specific.
- Decoded PCM gain control from 100% to 200%. This cannot guarantee amplification for every encoded passthrough format; samples may clip at high gain.
- Private Vault that encrypts imported files and its index at rest using AES-GCM with a key stored in Android Keystore. Access requires Android's secure screen lock; vault playback decrypts a temporary app-private cache file, which the app attempts to delete after playback.
- GitHub Actions CI builds an installable development/debug APK.

## Still to finish or validate

- Physical-device verification for gestures, brightness/volume controls, PiP, background audio/notification controls, audio sync, decoder preferences, subtitles, unusual media, and different Android versions.
- Independent subtitle-pinching gesture without also zooming the video, and a selectable MicroDVD frame rate; current conversion assumes 25 fps.
- Physical-device validation of SD-card/USB-OTG folder grants, provider-specific listing behavior, revoked grants, and very large folders. External-storage document-provider URIs are deduplicated by stable volume/path identity; generic third-party providers without a stable filesystem path remain deduplicated by exact URI only.
- Further optimization/testing of thumbnail cache behavior and very large collections on low-memory devices.
- Direct browsing of every SD-card and USB-OTG filesystem. Lumio scans files exposed by Android's MediaStore and lets users choose provider-backed external-storage files through Android's document picker.
- Fully reliable pitch preservation across all codecs/devices; the speed control is present but the renderer/device determines pitch behavior.
- Exhaustive headphone/Bluetooth profile and media-button tests.
- Production-grade vault recovery/key-management, dedicated in-app PIN or biometric prompt, and review of decrypted-cache behavior under process termination.
- A signed release APK and release acceptance tests.

## Build and branch notes

The current workflow compiles and packages the debug APK. A successful CI run verifies build/package success, not real-device playback of every feature. The main branch remains unchanged; this work is on `draft/android-player`.

GitHub retains each uploaded artifact for 14 days. Use the most recent successful workflow run to get the APK matching the latest source.
