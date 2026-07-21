/* Music Archive service worker
   Place this file NEXT TO index.html in your repo, so it's served at:
   https://terpenesalad.github.io/jellymusic/sw.js
   Bump CACHE_VERSION whenever you change index.html to force an update. */

const CACHE_VERSION = 'ma-v1';
const APP_SHELL = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './icon-512-maskable.png'
];

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

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;

  // Never intercept media / range requests — let audio stream directly.
  // Caching partial responses breaks seeking and background playback.
  if (req.headers.get('range')) return;
  const url = new URL(req.url);
  if (/\.(mp3|m4a|aac|flac|ogg|opus|wav|webm)(\?|$)/i.test(url.pathname)) return;

  // Same-origin app shell: network-first, cache fallback (works offline,
  // but you always get the newest index.html when online).
  if (url.origin === self.location.origin) {
    e.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE_VERSION).then((c) => c.put(req, copy)).catch(() => {});
          return res;
        })
        .catch(() => caches.match(req).then((m) => m || caches.match('./index.html')))
    );
  }
  // Cross-origin (fonts, your music server API): pass through untouched.
});
