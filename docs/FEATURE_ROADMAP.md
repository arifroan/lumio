# Lumio Android feature and release status

Lumio is an offline-first Android media player with an original violet-and-lime interface. The native player is built on AndroidX Media3 ExoPlayer / MediaSession.

## Implemented in the current draft branch

- Native Android Media3 video/audio playback with an on-device playback screen.
- Android document picker for local files, including multi-select and persistent URI grants.
- MediaStore scans for indexed videos and audio, grouped by folder, with a remembered list of files picked manually.
- Folder/type playlist queue with next-item autoplay.
- Playback speed selection from 0.25x to 4x.
- Double-tap ±10-second seeking, horizontal swipe seeking, vertical brightness and volume gestures, pinch zoom/pan, and touch lock.
- Saves and restores playback position per local URI.
- MediaSessionService for background playback/lock-screen and Bluetooth media controls; pauses on audio becoming noisy (earphone disconnect).
- Picture-in-Picture entry point with Android PiP manifest support.
- Embedded audio-track selection and supported embedded subtitle-track selection.
- External subtitle chooser for SRT, WebVTT, ASS and SSA MIME types, plus native subtitle size/style controls where the Media3 decoder supports the selected format.
- Decoded PCM audio gain adjustment from 100% to 200%; it is not guaranteed for encoded passthrough formats or every codec.
- Encrypted Private Vault: files copied in are AES-GCM encrypted at rest with an Android Keystore key, vault index is encrypted too, and access is gated by the device's secure lock. Private playback uses a temporary decrypted cache file which the player attempts to remove when playback closes.
- GitHub Actions builds an installable debug APK artifact from this branch.

## Not yet implemented or not fully verified

- A manual Hardware / Hardware+ / Software decoder-mode selector. Media3 selects device-supported codecs; decoder fallback is not the same as a complete user-selectable HW/SW mode.
- Bidirectional audio/video delay adjustment in milliseconds.
- Pitch-preserving variable speed across all codecs and devices; speed is selectable, but pitch behavior is renderer/device dependent.
- Direct playback/scan of every microSD and USB-OTG filesystem. Files exposed by Android's media provider are scanned; the document picker can open provider-backed removable-storage files.
- MicroDVD `.sub` conversion and subtitle pinch gestures independent from video zoom.
- Fullly featured library maintenance (sort/search, thumbnails, recursive custom SAF folder browser, robust duplicate handling).
- Independent vault PIN, per-file key rotation, audit/recovery flow, and device-specific biometric prompt rather than requiring Android's configured secure screen lock.
- Production-grade handling and testing for all headset buttons, Bluetooth profiles, corrupt media, uncommon codecs, subtitles, display cutouts, lifecycle edge cases, and all Android versions.
- Release signing and physical-device acceptance tests.

## CI and installation notes

The GitHub workflow builds the debug APK. A successful CI build proves Android compilation and packaging only; it does not prove playback behavior on a physical device. Test the APK on a real Android phone before treating it as a release.

The APK artifact is retained by GitHub Actions for 14 days. The main branch is unchanged; work is on `draft/android-player`.
