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
const SW_VERSION = '3';

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
  // Network-only. This is a genuine (non-empty) fetch handler, which is what
  // Chrome's installability check looks for, while guaranteeing fresh content.
  event.respondWith(fetch(event.request));
});
