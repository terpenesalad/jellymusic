/* Music Archive service worker
   Place this file NEXT TO index.html in your repo, so it's served at:
   https://terpenesalad.github.io/jellymusic/sw.js
   Bump CACHE_VERSION whenever you change index.html to force an update. */

const CACHE_VERSION = 'ma-v4';
const APP_SHELL = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './icon-512-maskable.png'
];

// How long we wait for the network before falling back to the cached copy.
// Without this, a phone on a dying signal sits on a blank screen until the
// request finally times out, even though a perfectly good copy of the app is
// already on disk. Three seconds prefers fresh code on a normal connection
// and doesn't feel broken on a bad one.
const NETWORK_TIMEOUT = 3000;

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE_VERSION)
      .then((c) => c.addAll(APP_SHELL))
      .catch(() => {}) // don't fail install if an icon 404s
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys.filter((k) => k !== CACHE_VERSION).map((k) => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

// Let the page tell a waiting worker to take over immediately.
self.addEventListener('message', (e) => {
  if (e.data === 'skipWaiting') self.skipWaiting();
});

function networkFirst(req) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (res) => { if (!settled) { settled = true; resolve(res); } };

    // Race the network against the clock. If the timer wins we serve from
    // cache, but the network request is still allowed to finish and refresh
    // the cache in the background for next time.
    const timer = setTimeout(() => {
      caches.match(req).then((m) => { if (m) finish(m); });
    }, NETWORK_TIMEOUT);

    fetch(req)
      .then((res) => {
        clearTimeout(timer);
        // Only cache real, complete, same-origin successes. Caching opaque or
        // partial (206) responses corrupts the offline copy.
        if (res && res.ok && res.status === 200 && res.type === 'basic') {
          const copy = res.clone();
          caches.open(CACHE_VERSION).then((c) => c.put(req, copy)).catch(() => {});
        }
        finish(res);
      })
      .catch(() => {
        clearTimeout(timer);
        caches.match(req).then((m) => {
          if (m) return finish(m);
          // Only fall back to the app shell for actual page navigations. The
          // old worker returned index.html for ANY failed same-origin request,
          // so a missing icon or script resolved to a chunk of HTML.
          if (req.mode === 'navigate') {
            return caches.match('./index.html').then((idx) => finish(idx || Response.error()));
          }
          finish(Response.error());
        });
      });
  });
}

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;

  // Never intercept media / range requests — let audio stream directly.
  // Caching partial responses breaks seeking and background playback.
  if (req.headers.get('range')) return;

  let url;
  try { url = new URL(req.url); } catch (_) { return; }

  if (/\.(mp3|m4a|aac|flac|ogg|opus|wav|webm)(\?|$)/i.test(url.pathname)) return;
  // Jellyfin stream endpoints carry no file extension, so match paths too —
  // these must always go straight to the network.
  if (/\/(Audio|Videos)\/[^/]+\/(stream|universal)/i.test(url.pathname)) return;

  // Same-origin app shell: network-first with a timeout, cache fallback.
  if (url.origin === self.location.origin) {
    e.respondWith(networkFirst(req));
  }
  // Cross-origin (fonts, your music server API): pass through untouched.
});
