/* Music Archive — service worker
 *
 * Purpose: satisfy Chrome's PWA install requirement (a real, same-origin
 * service worker that controls the page) WITHOUT ever serving a stale page.
 *
 * Strategy: network-only pass-through. Every request goes straight to the
 * network, so reloading the app always loads the newest files from the
 * server. No app-shell caching is done here, which means you never have to
 * clear site data to see updates.
 *
 * Bump SW_VERSION whenever you want to force every client to update.
 */
const SW_VERSION = '4';

self.addEventListener('install', (event) => {
  // Activate this new worker immediately, replacing any old one.
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    // Drop any caches left over from older versions of this worker.
    try {
      const keys = await caches.keys();
      await Promise.all(keys.map((k) => caches.delete(k)));
    } catch (e) {}
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const req = event.request;

  // CRITICAL: only handle GET requests to OUR OWN origin (the app's static
  // files served from GitHub Pages). Everything else — especially the
  // cross-origin Jellyfin API calls (which carry custom auth headers like
  // X-Emby-Authorization) and media streams — must pass through completely
  // untouched. Re-issuing those through the worker can break their headers/
  // credentials/range requests, which is what caused "Could not load."
  let sameOrigin = false;
  try { sameOrigin = new URL(req.url).origin === self.location.origin; } catch (e) {}

  if (req.method !== 'GET' || !sameOrigin) {
    // Do NOT call event.respondWith — let the browser handle it natively.
    return;
  }

  // Same-origin GET: network-only pass-through (no caching → always fresh).
  event.respondWith(fetch(req));
});
