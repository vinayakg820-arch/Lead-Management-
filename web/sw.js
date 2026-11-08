// LeadMaster service worker: lets Chrome/Edge install the web app on a computer.
// Pages always come fresh from the website (so a new ZIP on EdgeOne is picked up at once);
// the last copy is kept only so the app still opens when the internet is down.
const CACHE = 'leadmaster-shell-v1';

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET' || req.mode !== 'navigate') return; // everything else goes straight to the network
  event.respondWith(
    fetch(req, { cache: 'no-store' })
      .then((res) => {
        if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put('./', copy)); }
        return res;
      })
      .catch(() => caches.match('./'))
  );
});
