# Lumio Media Player

Lumio is a free, mobile-first media player prototype for the web and Android. This first version plays local audio and video files directly in your browser.

## Features in this prototype

- Open one or multiple local audio/video files
- Temporary playlist with All media, Videos, and Audio filters
- Play/pause, seek, skip backward/forward, volume, and playback speed
- Fullscreen and Picture-in-Picture when supported by the browser
- Load sidecar subtitles in `.srt` or `.vtt` format
- Responsive dark interface designed for phones
- Installable/offline app shell on supported browsers
- No account, API key, or upload server required

## Run it

Open `index.html` in a browser for a basic local preview. For the best experience, use a local static server or host it with GitHub Pages. Browser codec support varies by device; some formats (including some MKV/AVI files and certain audio codecs) may not play in every browser.

## Publish free with GitHub Pages

1. Open this repository's **Settings → Pages**.
2. Under **Build and deployment**, set **Source** to **GitHub Actions**.
3. Open **Actions**, allow workflows if prompted, and run the **Deploy Lumio to GitHub Pages** workflow (or push another commit to `main`).
4. After the workflow succeeds, GitHub will show the public site URL under **Settings → Pages**.

The workflow deploys this static site; no paid backend is needed.

## Privacy

Media files are read locally using browser file access and object URLs. Lumio does not upload selected media. The current playlist is temporary and clears when the page is closed or refreshed.

## Roadmap

- [x] First mobile-friendly web player prototype
- [x] Local audio/video selection and playlist
- [x] Playback controls and sidecar subtitles
- [x] Installable web app shell
- [ ] Test on more Android browsers and formats
- [ ] Native Android app with broader codec support
- [ ] Public APK releases

## Project status

Early prototype (v0.1). Features and browser compatibility may change as development continues.
