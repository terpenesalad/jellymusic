/*
 * Native bridge, injected into every page load.
 *
 * The design goal here is that index.html needs no knowledge of the app at
 * all: the same file keeps working as a plain website, and this script adapts
 * it to the native media session from the outside. It does that by watching
 * the audio element through document-level capture listeners and by wrapping
 * the navigator.mediaSession calls the page already makes, rather than
 * requiring any new hooks in the app itself.
 */
(function () {
  'use strict';

  if (window.__MA_BRIDGE__) return;
  var host = window.MAHost;
  if (!host) return;
  window.__MA_BRIDGE__ = 1;

  var el = null;               // the audio element actually producing sound
  var handlers = {};           // mediaSession action handlers the page registered
  var lastArtKey = '';
  var lastMetaKey = '';
  var pauseTimer = null;
  var lastPush = 0;

  function log(m) { try { host.log(String(m)); } catch (e) {} }

  /*
   * The page runs two <audio> elements: the real one, and a muted element used
   * to pre-buffer the next track. Only the audible one should ever drive the
   * notification, or the lock screen would flicker to the wrong song every
   * time the next one starts buffering.
   */
  function isPlayer(t) {
    return t && t.tagName === 'AUDIO' && t.id !== 'audio-preload' && !t.muted;
  }

  function duration() {
    if (!el) return 0;
    var d = el.duration;
    // Live radio reports Infinity; report 0 so the scrubber hides itself
    // rather than drawing a bar of unknown length.
    return (typeof d === 'number' && isFinite(d) && d > 0) ? d : 0;
  }

  function pushState(playing) {
    try {
      host.setState(!!playing, el ? (el.currentTime || 0) : 0, duration(),
                    el ? (el.playbackRate || 1) : 1);
    } catch (e) {}
  }

  /*
   * A track change shows up as pause → (new src) → play in quick succession.
   * Reporting that pause immediately would blink the notification to "paused"
   * between every song, so a real pause is only believed if nothing resumes
   * within a moment. Manual pauses still feel instant.
   */
  function schedulePause() {
    clearTimeout(pauseTimer);
    pauseTimer = setTimeout(function () {
      pauseTimer = null;
      if (el && !el.paused) return;
      pushState(false);
    }, 350);
  }

  function cancelPendingPause() {
    if (pauseTimer) { clearTimeout(pauseTimer); pauseTimer = null; }
  }

  ['play', 'playing'].forEach(function (ev) {
    document.addEventListener(ev, function (e) {
      if (!isPlayer(e.target)) return;
      el = e.target;
      cancelPendingPause();
      pushState(true);
      // The page sets its mediaSession metadata around the same moment; give
      // that the first go, then fill in anything it didn't provide.
      setTimeout(fallbackMetadata, 60);
    }, true);
  });

  ['pause', 'ended', 'emptied'].forEach(function (ev) {
    document.addEventListener(ev, function (e) {
      if (!isPlayer(e.target)) return;
      el = e.target;
      schedulePause();
    }, true);
  });

  document.addEventListener('ratechange', function (e) {
    if (!isPlayer(e.target)) return;
    el = e.target;
    pushState(!el.paused);
  }, true);

  document.addEventListener('timeupdate', function (e) {
    if (!isPlayer(e.target)) return;
    el = e.target;
    var now = Date.now();
    if (now - lastPush < 1000) return;   // ~1 Hz is plenty for a scrubber
    lastPush = now;
    try { host.setPosition(el.currentTime || 0, duration()); } catch (err) {}
  }, true);

  document.addEventListener('loadedmetadata', function (e) {
    if (!isPlayer(e.target)) return;
    el = e.target;
    try { host.setPosition(el.currentTime || 0, duration()); } catch (err) {}
  }, true);

  /* ── metadata ─────────────────────────────────────────────────────────
   * The page already builds a MediaMetadata for every track. Rather than
   * asking it to also tell us, we intercept the assignment.
   */
  var ms = navigator.mediaSession;
  if (ms) {
    var stored = null;
    try {
      Object.defineProperty(ms, 'metadata', {
        configurable: true,
        get: function () { return stored; },
        set: function (v) { stored = v; onMetadata(v); }
      });
    } catch (e) {
      log('could not wrap mediaSession.metadata: ' + e);
    }

    // Capture the page's own action handlers so notification and lock-screen
    // buttons run the app's real logic — which also keeps its internal
    // "user wants playback" flag correct — instead of poking the element.
    var origSet = ms.setActionHandler && ms.setActionHandler.bind(ms);
    try {
      ms.setActionHandler = function (action, fn) {
        handlers[action] = fn;
        if (origSet) { try { origSet(action, fn); } catch (e) {} }
      };
    } catch (e) {}
  }

  /**
   * Single funnel for "what's playing", whichever route the information
   * arrived by. Deduped on the values themselves so the mediaSession path and
   * the fallback below can both fire for the same track without the
   * notification being rebuilt twice.
   */
  function applyMetadata(title, artist, album, artUrl) {
    var key = (title || '') + '\u0000' + (artist || '') + '\u0000' + (album || '');
    if (key === lastMetaKey) {
      // Same track, but the cover may not have been resolved yet.
      if (artUrl) pushArtwork(artUrl);
      return;
    }
    lastMetaKey = key;
    try {
      host.setMetadata(title || '', artist || '', album || '');
    } catch (e) {}
    pushArtwork(artUrl || '');
  }

  function onMetadata(m) {
    if (!m) return;
    var art = '';
    try {
      if (m.artwork && m.artwork.length) art = m.artwork[0].src || '';
    } catch (e) {}
    applyMetadata(m.title, m.artist, m.album, art);
  }

  /**
   * Belt and braces for the track details.
   *
   * Everything above depends on navigator.mediaSession existing and on its
   * metadata property being interceptable. That holds in a Chromium WebView,
   * but if it ever doesn't — an odd WebView build, a locked-down device, a
   * future change to the API — the notification would silently degrade to a
   * bare "Music Archive" with no song on it, which is a miserable failure
   * mode for something whose whole job is the lock screen. Reading the app's
   * own player state costs nothing and removes that single point of failure.
   */
  /*
   * index.html declares its state as `const S = {...}` at the top level of a
   * classic script. That kind of binding lives in the global *lexical*
   * environment, not on `window` — so `window.S` is undefined even though `S`
   * is perfectly reachable by name from another script in the same realm.
   * Same trap applies to `let`; only `function` declarations land on `window`.
   */
  function appState() {
    try { return (typeof S !== 'undefined') ? S : null; } catch (e) { return null; }
  }
  function appFn(name) {
    try {
      var f = (typeof window !== 'undefined') ? window[name] : null;
      return (typeof f === 'function') ? f : null;
    } catch (e) { return null; }
  }

  function fallbackMetadata() {
    try {
      var st = appState();
      var t = st && st.track;
      if (!t || !t.Id) return;
      var artist = (t.Artists && t.Artists.length)
        ? t.Artists.join(', ')
        : (t.AlbumArtist || '');
      var artUrl = '';
      var artId = t.AlbumId || t.ParentId;
      var imgSrcFn = appFn('imgSrc');
      if (artId && imgSrcFn) {
        try { artUrl = imgSrcFn(artId, 'Primary', 512, true); } catch (e) {}
      }
      applyMetadata(t.Name || '', artist, t.Album || '', artUrl);
    } catch (e) {}
  }

  /*
   * Fetched here rather than natively because the URL carries the Jellyfin API
   * key and may be plain http on the LAN — the page already has the session
   * and the right origin, so it is the cheapest place to get the bytes.
   */
  function pushArtwork(url) {
    if (url === lastArtKey) return;
    lastArtKey = url;

    if (!url) {
      try { host.setArtwork('', ''); } catch (e) {}
      return;
    }

    fetch(url, { credentials: 'omit' })
      .then(function (r) { return r.ok ? r.blob() : Promise.reject(r.status); })
      .then(function (blob) {
        if (blob.size > 3000000) return Promise.reject('cover too large');
        return new Promise(function (resolve, reject) {
          var fr = new FileReader();
          fr.onload = function () {
            var s = String(fr.result || '');
            var i = s.indexOf(',');
            resolve(i >= 0 ? s.slice(i + 1) : '');
          };
          fr.onerror = reject;
          fr.readAsDataURL(blob);
        });
      })
      .then(function (b64) {
        try { host.setArtwork(url, b64); } catch (e) {}
      })
      .catch(function () {
        // No art is fine — the notification just shows the app icon.
        try { host.setArtwork(url, ''); } catch (e) {}
      });
  }

  /* ── native → page ────────────────────────────────────────────────────
   * Called from the notification, the lock screen, headset buttons and car
   * head units. Prefers the page's own handlers; the element is only touched
   * if the page hasn't registered one yet.
   */
  window.__MA_cmd = function (action, argMs) {
    try {
      if (action === 'play') {
        if (handlers.play) return handlers.play();
        if (el) el.play().catch(function () {});
        return;
      }
      if (action === 'pause') {
        if (handlers.pause) return handlers.pause();
        if (el) el.pause();
        return;
      }
      if (action === 'next') {
        if (handlers.nexttrack) return handlers.nexttrack();
        var nf = appFn('nextTrack');
        if (nf) return nf();
        return;
      }
      if (action === 'prev') {
        if (handlers.previoustrack) return handlers.previoustrack();
        var pf = appFn('prevTrack');
        if (pf) return pf();
        return;
      }
      if (action === 'seek') {
        var sec = (argMs || 0) / 1000;
        if (handlers.seekto) return handlers.seekto({ seekTime: sec });
        if (el) el.currentTime = sec;
        return;
      }
    } catch (e) {
      log('command ' + action + ' failed: ' + e);
    }
  };

  // If a track is already loaded when the bridge attaches (a reload mid-song,
  // say), report it straight away instead of waiting for the next event.
  try {
    var existing = document.querySelectorAll('audio');
    for (var i = 0; i < existing.length; i++) {
      if (isPlayer(existing[i]) && existing[i].src) {
        el = existing[i];
        pushState(!el.paused);
        break;
      }
    }
  } catch (e) {}

  log('bridge ready');
})();
