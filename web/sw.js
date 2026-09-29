/*
 * Campus Print website as an app: lets phones "Add to Home screen" and still
 * open the page on a weak connection.
 *
 * Pages always come fresh from the network when there is one; the saved copy
 * is only used when the network fails. It never touches the print service
 * (orders, payments), config.js, or any other website.
 */
const CACHE = "campusprint-v4";
const SHELL = ["./", "index.html", "css/app.css", "js/print-core.js", "js/app.js", "icon.svg", "icon-192.png",
               "icon-512.png", "manifest.webmanifest"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== self.location.origin || url.pathname.endsWith("/config.js")) {
    return;                                   // the browser handles it as usual
  }
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(e.request, copy));
        }
        return res;
      })
      .catch(() => caches.match(e.request).then((hit) => hit || caches.match("index.html")))
  );
});

/* "Your prints are ready" notification: tapping it opens the page again. */
self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  e.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true })
      .then((list) => (list.length ? list[0].focus() : self.clients.openWindow("./")))
  );
});
