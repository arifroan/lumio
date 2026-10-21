# Lumio feature roadmap

Lumio is an offline-first Android media player. This document tracks the requested feature scope and separates implemented prototype behavior from work that still needs a native playback engine and device testing.

## Implemented in the current draft branch

- Local file picker for audio and video files; selected media is played locally and is not uploaded.
- Original Lumio library interface with video/audio filters and file size counters.
- Playback, seeking, ±10-second controls, fullscreen request, and speed selection from 0.25× to 4×.
- Touch gestures in the playback area: vertical brightness/volume adjustment, horizontal timeline scrubbing, double-tap ±10 seconds, and fit/zoom.
- Touch-control lock and unlock overlay.
- External SRT and VTT subtitle loading, basic subtitle size styling, and ±0.5-second subtitle timing adjustment.
- Saves the last playback position locally in WebView storage, keyed by filename and file size, and offers to resume when that same file is selected again.
- Android bridge for screen brightness and system media-volume adjustment; Android Picture-in-Picture entry point is enabled where supported.
- Build workflow uploads a debug APK artifact.

## Not yet implemented or not verified

These items remain part of the requested scope; the UI must not be treated as proof that they work:

- Persistent indexed media library and automatic scanning of internal storage, SD cards, folders, and USB-OTG.
- Native Media3/ExoPlayer-based playback engine and broader codec/container support.
- Reliable background playback with notification/media session and lock-screen controls.
- Verified Picture-in-Picture playback and PiP controls on real Android devices.
- True hardware/software decoder selection; this depends on the selected playback engine and device codecs.
- Embedded audio/subtitle track enumeration and language switching.
- SUB, ASS, and SSA subtitle parsing/rendering; current external subtitle support is SRT/VTT only.
- Audio delay in milliseconds, audio amplification beyond the device's supported output, and pitch-preserving speed changes.
- Earphone disconnect behavior and comprehensive wired/Bluetooth headset button handling.
- Secure private folder with PIN/biometric authentication and protected file storage.
- Full automated playback/device test suite.

## Implementation order

1. Stabilize and test local file playback, gestures, resume, speed, and SRT/VTT subtitles.
2. Move playback into Android Media3/ExoPlayer while retaining Lumio's visual identity.
3. Add media-session/background audio, PiP, audio/subtitle track selection, and decoder-related settings.
4. Add indexed folders and removable storage access, then secure private-folder storage.
5. Test on physical Android devices and document format/device limitations before a release build.

## Important testing note

A successful Gradle build confirms compilation only. It does not prove that every media format, gesture, subtitle, PiP behavior, or device-specific feature works. Validate on a physical phone before calling a feature complete.
