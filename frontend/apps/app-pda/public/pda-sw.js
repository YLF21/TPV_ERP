const CACHE_NAME = "espos-pda-shell-v2";
const APP_SHELL = ["/", "/index.html", "/manifest.webmanifest", "/pda-icon.svg"];
const MAX_RUNTIME_ENTRIES = 60;
async function trimRuntimeCache(cache) {
  const keys = await cache.keys();
  const runtime = keys.filter((request) => !APP_SHELL.includes(new URL(request.url).pathname));
  await Promise.all(runtime.slice(0, Math.max(0, runtime.length - MAX_RUNTIME_ENTRIES)).map((request) => cache.delete(request)));
}

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key.startsWith("espos-pda-shell-") && key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith("/api/")) return;

  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          void caches.open(CACHE_NAME).then((cache) => cache.put("/index.html", copy));
          return response;
        })
        .catch(() => caches.match("/index.html"))
    );
    return;
  }

  event.respondWith(
    caches.match(request).then((cached) => cached ?? fetch(request).then((response) => {
      if (response.ok && ["script", "style", "font", "image"].includes(request.destination)) {
        const copy = response.clone();
        void caches.open(CACHE_NAME).then(async (cache) => { await cache.put(request, copy); await trimRuntimeCache(cache); });
      }
      return response;
    }))
  );
});
