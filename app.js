(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const media = $('media');
  const fileInput = $('fileInput');
  const subtitleInput = $('subtitleInput');
  const libraryList = $('libraryList');
  const seek = $('seek');
  let items = [];
  let currentId = null;
  let filter = 'all';
  let toastTimer;
  let deferredInstallPrompt = null;
  let subtitleUrl = null;

  const icons = {
    video: '<svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="m10 9 5 3-5 3z"/></svg>',
    audio: '<svg viewBox="0 0 24 24"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="m8 5 12 7-12 7z"/></svg>'
  };
  const formatTime = (seconds) => {
    if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
    const s = Math.floor(seconds);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = String(s % 60).padStart(2, '0');
    return h ? h + ':' + String(m).padStart(2, '0') + ':' + sec : m + ':' + sec;
  };
  const prettySize = (bytes) => bytes < 1024 * 1024 ? Math.max(1, Math.round(bytes / 1024)) + ' KB' : (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  const isAudio = (file) => file.type.startsWith('audio/') || /\.(mp3|m4a|aac|wav|ogg|flac|opus|weba)$/i.test(file.name);
  const showToast = (message) => {
    const toast = $('toast');
    toast.textContent = message;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('show'), 2600);
  };
  const getCurrent = () => items.find(item => item.id === currentId);
  const setPlayState = () => {
    const playing = !media.paused && !media.ended;
    $('playBtn').classList.toggle('is-playing', playing);
    $('playBtn').setAttribute('aria-label', playing ? 'Pause' : 'Play');
    $('playBtn').title = playing ? 'Pause' : 'Play';
    $('centerPlay').hidden = !media.paused || !currentId;
    $('playingLabel').textContent = playing ? 'NOW PLAYING' : (currentId ? 'PAUSED' : 'READY WHEN YOU ARE');
  };
  const updateMeta = () => {
    $('currentTime').textContent = formatTime(media.currentTime);
    $('duration').textContent = formatTime(media.duration);
    seek.value = media.duration ? String(Math.round(media.currentTime / media.duration * 1000)) : '0';
    const item = getCurrent();
    if (item) $('trackMeta').textContent = (item.kind === 'audio' ? 'Audio' : 'Video') + ' · ' + (Number.isFinite(media.duration) && media.duration > 0 ? formatTime(media.duration) : prettySize(item.file.size));
  };
  const renderLibrary = () => {
    $('fileCount').textContent = String(items.length);
    const shown = items.filter(item => filter === 'all' || item.kind === filter);
    document.querySelectorAll('.tab').forEach(tab => tab.classList.toggle('active', tab.dataset.filter === filter));
    if (!shown.length) {
      libraryList.innerHTML = '<div class="library-empty"><div class="empty-folder"><svg viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg></div><strong>' + (items.length ? 'Nothing in this filter' : 'Your library is waiting') + '</strong><span>' + (items.length ? 'Choose another category to see your media.' : 'Open files to build a temporary playlist.') + '</span></div>';
      return;
    }
    libraryList.innerHTML = shown.map(item => '<div class="media-item ' + (item.id === currentId ? 'selected' : '') + '" data-id="' + item.id + '" role="button" tabindex="0" aria-label="Play ' + escapeHtml(item.file.name) + '"><span class="media-thumb ' + item.kind + '">' + icons[item.kind] + '</span><span class="media-details"><strong>' + escapeHtml(item.file.name) + '</strong><span>' + item.kind.toUpperCase() + ' · ' + prettySize(item.file.size) + '</span></span><button class="item-play" data-play="' + item.id + '" aria-label="Play ' + escapeHtml(item.file.name) + '">' + icons.play + '</button></div>').join('');
  };
  const escapeHtml = (value) => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const playItem = async (item) => {
    if (!item) return;
    currentId = item.id;
    if (media.src !== item.url) {
      media.pause();
      media.src = item.url;
      media.load();
    }
    $('emptyPlayer').hidden = true;
    $('audioArt').hidden = item.kind !== 'audio';
    $('dropZone').classList.toggle('audio-mode', item.kind === 'audio');
    $('trackName').textContent = item.file.name;
    $('audioTitle').textContent = item.file.name.replace(/\.[^.]+$/, '');
    $('trackMeta').textContent = item.kind === 'audio' ? 'Audio · ' + prettySize(item.file.size) : 'Video · ' + prettySize(item.file.size);
    $('trackArt').innerHTML = icons[item.kind];
    renderLibrary();
    try {
      await media.play();
    } catch (err) {
      if (err && err.name !== 'AbortError') showToast('Tap play to start this file.');
    }
    setPlayState();
  };
  const addFiles = (fileList) => {
    const incoming = Array.from(fileList || []).filter(file => file.type.startsWith('video/') || file.type.startsWith('audio/') || /\.(mp4|m4v|mkv|webm|mov|avi|mpg|mpeg|3gp|ts|mp3|m4a|aac|wav|ogg|flac|opus|weba)$/i.test(file.name));
    if (!incoming.length) {
      showToast('Choose a supported audio or video file.');
      return;
    }
    let firstNew = null;
    incoming.forEach(file => {
      const duplicate = items.some(item => item.file.name === file.name && item.file.size === file.size && item.file.lastModified === file.lastModified);
      if (duplicate) return;
      const item = { id: (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random()), file, url: URL.createObjectURL(file), kind: isAudio(file) ? 'audio' : 'video' };
      items.push(item);
      if (!firstNew) firstNew = item;
    });
    renderLibrary();
    if (firstNew) playItem(firstNew);
    else showToast('Those files are already in your library.');
    if (incoming.length > 1) showToast(incoming.length + ' media files added to this session.');
  };
  const togglePlayback = async () => {
    if (!currentId) { fileInput.click(); return; }
    if (media.paused) {
      try { await media.play(); } catch (_) { showToast('Playback could not start for this file.'); }
    } else media.pause();
    setPlayState();
  };

  $('chooseMain').addEventListener('click', () => fileInput.click());
  $('openPlayerBtn').addEventListener('click', () => fileInput.click());
  $('browseBtn').addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', e => { addFiles(e.target.files); fileInput.value = ''; });
  $('playBtn').addEventListener('click', togglePlayback);
  $('centerPlay').querySelector('button').addEventListener('click', togglePlayback);
  media.addEventListener('click', togglePlayback);
  media.addEventListener('play', setPlayState);
  media.addEventListener('pause', setPlayState);
  media.addEventListener('ended', () => {
    setPlayState();
    const index = items.findIndex(item => item.id === currentId);
    if (index >= 0 && index < items.length - 1) playItem(items[index + 1]);
  });
  media.addEventListener('timeupdate', updateMeta);
  media.addEventListener('loadedmetadata', updateMeta);
  media.addEventListener('durationchange', updateMeta);
  media.addEventListener('error', () => {
    if (currentId) showToast('This format or codec may not be supported by your browser.');
  });
  seek.addEventListener('input', () => {
    if (Number.isFinite(media.duration) && media.duration > 0) media.currentTime = Number(seek.value) / 1000 * media.duration;
  });
  $('backBtn').addEventListener('click', () => { media.currentTime = Math.max(0, media.currentTime - 10); });
  $('forwardBtn').addEventListener('click', () => { if (Number.isFinite(media.duration)) media.currentTime = Math.min(media.duration, media.currentTime + 10); });
  $('volume').addEventListener('input', e => { media.volume = Number(e.target.value); media.muted = media.volume === 0; });
  const speeds = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
  $('speedBtn').addEventListener('click', () => {
    const next = (speeds.indexOf(media.playbackRate) + 1) % speeds.length;
    media.playbackRate = speeds[next];
    $('speedBtn').textContent = speeds[next] + '×';
    showToast('Playback speed: ' + speeds[next] + '×');
  });
  $('fullscreenBtn').addEventListener('click', async () => {
    const stage = $('dropZone');
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if (stage.requestFullscreen) await stage.requestFullscreen();
      else if (media.webkitEnterFullscreen) media.webkitEnterFullscreen();
      else showToast('Fullscreen is not available in this browser.');
    } catch (_) { showToast('Fullscreen could not be opened.'); }
  });
  $('pipBtn').addEventListener('click', async () => {
    try {
      if (document.pictureInPictureElement) await document.exitPictureInPicture();
      else if (document.pictureInPictureEnabled && media.requestPictureInPicture) await media.requestPictureInPicture();
      else showToast('Picture in picture is not supported for this media/browser.');
    } catch (_) { showToast('Picture in picture is unavailable for this file.'); }
  });
  libraryList.addEventListener('click', e => {
    const playButton = e.target.closest('[data-play]');
    const row = e.target.closest('.media-item');
    const id = playButton ? playButton.dataset.play : row && row.dataset.id;
    if (id) playItem(items.find(item => item.id === id));
  });
  libraryList.addEventListener('keydown', e => {
    if ((e.key === 'Enter' || e.key === ' ') && e.target.classList.contains('media-item')) {
      e.preventDefault();
      playItem(items.find(item => item.id === e.target.dataset.id));
    }
  });
  document.querySelectorAll('.tab').forEach(tab => tab.addEventListener('click', () => { filter = tab.dataset.filter; renderLibrary(); }));
  const dropZone = $('dropZone');
  ['dragenter', 'dragover'].forEach(type => dropZone.addEventListener(type, e => { e.preventDefault(); dropZone.classList.add('dragging'); }));
  ['dragleave', 'drop'].forEach(type => dropZone.addEventListener(type, e => { e.preventDefault(); dropZone.classList.remove('dragging'); }));
  dropZone.addEventListener('drop', e => addFiles(e.dataTransfer.files));
  document.addEventListener('keydown', e => {
    const tag = document.activeElement && document.activeElement.tagName;
    if (['INPUT','TEXTAREA','BUTTON'].includes(tag) || e.altKey || e.ctrlKey || e.metaKey) return;
    if (e.code === 'Space') { e.preventDefault(); togglePlayback(); }
    if (e.key === 'ArrowLeft' && currentId) media.currentTime = Math.max(0, media.currentTime - 5);
    if (e.key === 'ArrowRight' && currentId && Number.isFinite(media.duration)) media.currentTime = Math.min(media.duration, media.currentTime + 5);
    if (e.key.toLowerCase() === 'f') $('fullscreenBtn').click();
  });
  document.addEventListener('dragover', e => e.preventDefault());
  document.addEventListener('drop', e => { if (e.target !== dropZone && !dropZone.contains(e.target)) { e.preventDefault(); addFiles(e.dataTransfer.files); } });
  document.addEventListener('visibilitychange', () => { if (document.hidden) media.pause(); });
  window.addEventListener('beforeunload', () => {
    items.forEach(item => URL.revokeObjectURL(item.url));
    if (subtitleUrl) URL.revokeObjectURL(subtitleUrl);
  });

  // Load a sidecar subtitle and convert SRT to browser-native WebVTT.
  $('trackMeta').title = 'Browser codec support varies by device';
  const subtitleButton = document.createElement('button');
  subtitleButton.type = 'button';
  subtitleButton.className = 'outline-button full-button';
  subtitleButton.innerHTML = '<svg viewBox="0 0 24 24"><path d="M4 5h16v12H8l-4 3z"/><path d="M8 10h8M8 13h5"/></svg> Load subtitles (.srt / .vtt)';
  subtitleButton.addEventListener('click', () => {
    if (!currentId || getCurrent().kind === 'audio') { showToast('Open a video before loading subtitles.'); return; }
    subtitleInput.click();
  });
  $('browseBtn').insertAdjacentElement('afterend', subtitleButton);
  subtitleInput.addEventListener('change', async e => {
    const file = e.target.files && e.target.files[0];
    subtitleInput.value = '';
    if (!file) return;
    if (!currentId || getCurrent().kind !== 'video') { showToast('Open a video before loading subtitles.'); return; }
    try {
      let text = await file.text();
      if (/\.srt$/i.test(file.name) || !/^WEBVTT/m.test(text)) {
        text = 'WEBVTT\n\n' + text.replace(/\r/g, '').replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2').replace(/^\d+\s*\n/gm, '');
      }
      if (subtitleUrl) URL.revokeObjectURL(subtitleUrl);
      subtitleUrl = URL.createObjectURL(new Blob([text], { type: 'text/vtt' }));
      media.querySelectorAll('track').forEach(track => track.remove());
      const track = document.createElement('track');
      track.kind = 'subtitles'; track.label = 'Loaded subtitles'; track.srclang = 'en'; track.src = subtitleUrl; track.default = true;
      media.appendChild(track);
      track.track.mode = 'showing';
      showToast('Subtitle file loaded.');
    } catch (_) { showToast('Could not read that subtitle file.'); }
  });

  window.addEventListener('beforeinstallprompt', e => {
    e.preventDefault();
    deferredInstallPrompt = e;
    $('installBtn').hidden = false;
  });
  $('installBtn').addEventListener('click', async () => {
    if (!deferredInstallPrompt) return;
    deferredInstallPrompt.prompt();
    await deferredInstallPrompt.userChoice;
    deferredInstallPrompt = null;
    $('installBtn').hidden = true;
  });
  if ('serviceWorker' in navigator && location.protocol.startsWith('http')) {
    window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js').catch(() => {}));
  }
  renderLibrary();
  setPlayState();
})();