# Lumio Roadmap — Prototype to v1.0.0

Lumio is being developed as a **native Android media player first**. The browser player remains available, but native Android quality is the priority for v1.0.

## Release principles
- Preserve working features and make focused changes.
- Keep core playback free; no mandatory account, API key, or media upload.
- Use Android's document picker and scoped access rather than broad storage permissions.
- CI success is necessary but not sufficient: install and smoke-test each release on a physical Android device.
- Codec support depends on Android/Media3 and device capabilities; do not promise universal playback.

## v0.5.0 — Stability and release foundation (next)
- [ ] Verify launcher icon and animated splash on a physical Android phone.
- [ ] Smoke-test local audio/video selection, playback, pause, seek, and relaunch.
- [ ] Harden library behavior when a saved file is moved, deleted, or loses permission.
- [ ] Check subtitle loading and fullscreen entry/exit.
- [ ] Verify the APK build and document known limitations.

## v0.6.0 — Playback essentials
- [ ] Add previous/next track controls for the selected library.
- [ ] Remember playback position for the last played item.
- [ ] Improve errors for unsupported, unavailable, or permission-denied files.
- [ ] Verify audio-only playback and audio-focus behavior.

## v0.7.0 — Library usability
- [ ] Add sorting and video/audio filters.
- [ ] Let users remove stale or unwanted entries from the in-app library.
- [ ] Improve long filenames, empty states, and large-library scrolling.
- [ ] Validate library behavior across app restarts and device reboots.

## v0.8.0 — Player controls
- [ ] Improve fullscreen and orientation behavior.
- [ ] Add accessible playback-speed selection.
- [ ] Improve subtitle format detection and subtitle on/off selection.
- [ ] Test screen sizes, rotation, and navigation modes.

## v0.9.0 — Release candidate
- [ ] Run a documented regression checklist on a physical Android phone.
- [ ] Fix reproducible crashes, playback failures, and layout defects.
- [ ] Confirm app identity, version metadata, permissions, and release notes.
- [ ] Verify public APK download and installation.
- [ ] Publish known limitations and collect tester feedback.

## v1.0.0 — First stable public release
- [ ] Core local audio/video playback and library behavior pass the release checklist.
- [ ] Launcher icon and splash screen are verified on-device.
- [ ] Subtitles, fullscreen, playback speed, and saved library behavior are verified.
- [ ] No known release-blocking crash or data-loss issue remains.
- [ ] Publish a clearly versioned APK, release notes, and feedback instructions.

## Out of scope until core quality is solid
- Network streaming services or scraping third-party streams.
- Cloud sync, accounts, and media uploads.
- Claims of supporting every codec/container on every Android device.
- Paid services or mandatory API keys.

## Release gate
A version remains a test build until its APK is installed and key flows are verified on a physical device, even if CI passes.
