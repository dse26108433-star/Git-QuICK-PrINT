/*
 * Campus Print - the student website.
 *
 *   add files  ->  each is checked on the phone (what it really is, pages), then
 *                  uploaded straight to private storage and checked by the server
 *   set up     ->  every file on its own: pages, copies, colour, sides, paper, layout,
 *                  finishing... Only what the printers can really do is offered
 *                  (print-core.js), with a live preview of every sheet and the price
 *   review     ->  the server checks every choice against the real files and printers
 *                  and fixes the price; the summary shows the server's answer, which
 *                  is exactly what the Xerox PC prints
 *   pay, collect with one pickup code
 */
"use strict";
(function () {
  const C = window.PrintCore;
  const API = (window.CONFIG && window.CONFIG.apiBase || "").replace(/\/+$/, "");
  const PDFJS = "https://cdn.jsdelivr.net/npm/pdfjs-dist@4.4.168/build/pdf.min.mjs";
  const PDFJS_WORKER = "https://cdn.jsdelivr.net/npm/pdfjs-dist@4.4.168/build/pdf.worker.min.mjs";
  const RAZORPAY_JS = "https://checkout.razorpay.com/v1/checkout.js";
  const FINAL = ["COMPLETED", "FAILED", "CANCELLED", "EXPIRED"];
  const PARALLEL_UPLOADS = 3;
  const PARALLEL_READS = 2;
  const STORE = "campusprint.orders";
  const DRAFT = "campusprint.draft";

  const $ = (id) => document.getElementById(id);
  const state = {
    shop: null, printers: [], offered: null, limits: null,
    step: "choose",
    draft: null,               // { orderId, key, code } of the order being put together
    draftPromise: null,
    docs: [],                  // the files, in print order
    selected: null,            // the file being set up
    pvTab: "preview",
    order: null,               // the server's view after pricing / payment
    poll: null,
    reviewing: false
  };
  let nextLocal = 1;

  // ================================================================== small helpers

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function icon(name) {
    const s = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    const u = document.createElementNS("http://www.w3.org/2000/svg", "use");
    u.setAttribute("href", "#i-" + name);
    s.appendChild(u);
    s.setAttribute("aria-hidden", "true");
    return s;
  }
  const rupees = C.rupees, plural = C.plural;

  function toast(message, kind) {
    const t = el("div", "toast" + (kind ? " " + kind : ""), message);
    $("toasts").appendChild(t);
    setTimeout(() => t.remove(), kind === "bad" ? 6500 : 4200);
  }
  function showError(msg) {
    const box = $("globalError");
    if (!msg) { box.classList.add("hidden"); return; }
    box.textContent = msg;
    box.classList.remove("hidden");
    window.scrollTo({ top: 0, behavior: "smooth" });
  }
  function show(step) {
    state.step = step;
    ["choose", "setup", "review", "status"].forEach(s => $("s-" + s).classList.toggle("hidden", s !== step));
    const order = ["setup", "review", "status"];
    document.querySelectorAll(".steps li").forEach(li => {
      const i = order.indexOf(li.dataset.s), me = order.indexOf(step);
      li.classList.toggle("on", i === me);
      li.classList.toggle("done", i < me);
    });
    document.body.dataset.step = step;
    if (step !== "setup") document.body.classList.remove("editing");
    window.scrollTo({ top: 0 });
  }

  async function api(method, path, body, key) {
    const headers = {};
    if (body !== undefined && body !== null) headers["Content-Type"] = "application/json";
    if (key) headers["X-Order-Key"] = key;
    let res;
    try {
      res = await fetch(API + path, { method, headers, body: body == null ? undefined : JSON.stringify(body) });
    } catch (e) {
      const err = new Error("Cannot reach the print service. Check your internet connection and try again.");
      err.network = true;
      throw err;
    }
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { /* not JSON */ }
    if (!res.ok) {
      const err = new Error((data && data.message) || ("Something went wrong (" + res.status + ")."));
      err.code = data && data.error;
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  }
  function loadScript(src) {
    return new Promise((resolve, reject) => {
      if (document.querySelector('script[src="' + src + '"]')) return resolve();
      const s = document.createElement("script");
      s.src = src; s.onload = resolve;
      s.onerror = () => reject(new Error("Could not load the payment screen. Check your internet."));
      document.head.appendChild(s);
    });
  }
  function storageGet(k, fallback) {
    try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : fallback; } catch (e) { return fallback; }
  }
  function storageSet(k, v) {
    try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* private mode */ }
  }
  /** Runs at most n jobs at a time. */
  function limiter(n) {
    let active = 0;
    const waiting = [];
    const next = () => {
      if (active >= n || !waiting.length) return;
      active++;
      const [fn, ok, fail] = waiting.shift();
      Promise.resolve().then(fn).then(ok, fail).finally(() => { active--; next(); });
    };
    return (fn) => new Promise((ok, fail) => { waiting.push([fn, ok, fail]); next(); });
  }
  const readSlot = limiter(PARALLEL_READS);

  // ================================================================== the shop: prices, printers, what they can do

  async function loadShop() {
    try {
      const s = await api("GET", "/api/v1/shop");
      // What printers can do (not whether they are online right now): a change means new options.
      const features = (sh) => JSON.stringify(Object.assign({}, sh.printing,
        { printers: sh.printing.printers.map(p => Object.assign({}, p, { online: null })) }));
      const before = state.shop && features(state.shop);
      state.shop = s;
      (s.printing.paperSizes || []).forEach(p => { KNOWN_PAPER[p.id] = p; });
      state.printers = C.candidates(s);
      state.offered = C.offered(state.printers);
      if (before && before !== features(s)) printersChanged();
      state.limits = { maxCopies: s.maxCopies, maxPages: s.maxPages };
      $("centerName").textContent = s.centerName;
      document.querySelectorAll("[data-center]").forEach(e => { e.textContent = s.centerName; });
      document.querySelectorAll("[data-maxmb]").forEach(e => { e.textContent = Math.round(s.maxFileSizeBytes / 1048576); });
      $("priceBw").textContent = rupees(s.priceBwPaise);
      $("priceColor").textContent = rupees(s.priceColorPaise);
      $("priceExtra").textContent = priceExtras(s);
      const online = s.bwOnline || s.colorOnline;
      const chip = $("printerChip");
      chip.innerHTML = "";
      chip.append(el("span", "lamp " + (online ? "ready" : "stop")),
        el("span", "pill-text", (online ? "Printers online" : "Printers offline") +
          (s.ordersWaiting ? " · " + s.ordersWaiting + " in queue" : "")));
    } catch (e) {
      $("printerChip").innerHTML = '<span class="lamp stop"></span><span class="pill-text">Service unreachable</span>';
      showError(e.message);
    }
  }

  /**
   * The printers' features changed (a printer switched off or back on, staff
   * offered something new): show the new options, and which chosen settings
   * cannot be printed any more.
   */
  function printersChanged() {
    if (state.step !== "setup") return;
    renderQueue();
    renderCheckout();
    if (state.selected) {
      if (!document.activeElement || document.activeElement.id !== "pagesText") renderSettings();
      renderEditorPrice();
      schedulePreview();
    }
    toast("The printers changed: the options shown are up to date.", "warn");
  }

  /** "A3: double price. Stapling ₹2 per copy." */
  function priceExtras(s) {
    const p = s.printing && s.printing.pricing || {};
    const out = [];
    (s.printing.paperSizes || []).forEach(ps => {
      const pct = (p.paperSizePercent || {})[ps.id];
      if (ps.id !== "A4" && pct != null && pct !== 100) out.push(ps.id + " paper: " + (pct === 200 ? "double" : pct + " %") + " price");
    });
    const f = p.finishingPaise || {};
    const words = { STAPLE: "Stapling", PUNCH: "Hole punching", BIND: "Binding" };
    Object.keys(f).forEach(k => { if (f[k] > 0 && state.offered && state.offered.finishing.some(x => x.indexOf(k) === 0)) out.push(words[k] + " " + rupees(f[k]) + " per copy"); });
    return out.length ? out.join(" · ") + "." : "";
  }

  const KNOWN_PAPER = {};          // every size seen, so a size that disappears still has its name and dimensions
  function paper(id) {
    const p = (state.shop.printing.paperSizes || []).find(x => x.id === id) || KNOWN_PAPER[id];
    return p || { id: id || "A4", label: id || "A4", widthMm: 210, heightMm: 297 };
  }
  const finishingLabel = (id) => (state.shop.printing.finishing || {})[id] || id;
  const mediaLabel = (id) => (state.shop.printing.mediaTypes || {})[id] || id;

  // ================================================================== orders remembered on this device

  function recent() { return storageGet(STORE, []); }
  function forget(orderId) { storageSet(STORE, recent().filter(x => x.orderId !== orderId)); }
  function remember(o) {
    const list = recent().filter(x => x.orderId !== o.orderId);
    list.unshift(o);
    storageSet(STORE, list.slice(0, 15));
  }
  async function renderRecent() {
    const list = recent();
    $("recentBox").classList.toggle("hidden", list.length === 0);
    const box = $("recentList");
    box.innerHTML = "";
    for (const o of list) {
      const b = el("button", "recent-item");
      b.type = "button";
      const lamp = el("span", "lamp");
      const grow = el("span", "grow");
      grow.append(el("span", "file", o.fileName), el("span", "small muted stage", "…"));
      b.append(lamp, el("span", "code", o.code), grow);
      b.onclick = () => openStatus(o);
      box.appendChild(b);
      api("GET", "/api/v1/orders/" + o.orderId, null, o.key).then(v => {
        b.querySelector(".stage").textContent = v.stage;
        lamp.className = "lamp " + lampFor(v);
      }).catch(err => { if (err.status === 404) { forget(o.orderId); b.remove(); } });
    }
  }

  // ================================================================== adding files

  /** What a file really is, from its first bytes (never trust the name). */
  async function sniff(file) {
    const b = new Uint8Array(await file.slice(0, 1024).arrayBuffer());
    if (b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4E && b[3] === 0x47) return "PNG";
    if (b.length >= 3 && b[0] === 0xFF && b[1] === 0xD8 && b[2] === 0xFF) return "JPEG";
    for (let i = 0; i + 4 < b.length; i++) {
      if (b[i] === 0x25 && b[i + 1] === 0x50 && b[i + 2] === 0x44 && b[i + 3] === 0x46 && b[i + 4] === 0x2D) return "PDF";
    }
    return null;
  }

  /**
   * Adds files to the order, all at once. Each shows up straight away; the
   * slow parts (reading, uploading, the server's check) run in the background,
   * a few at a time, each with its own progress.
   */
  async function addFiles(fileList) {
    const files = Array.from(fileList || []).filter(f => f && f.size >= 0);
    if (!files.length) return;
    showError(null);
    if (!state.shop) await loadShop();
    if (!state.shop) return;
    if (state.step === "review") {
      toast("Tap “Change something” first to add more files.", "warn");
      return;
    }
    const room = state.shop.maxDocuments - state.docs.length;
    if (room <= 0) {
      toast("One order can have up to " + state.shop.maxDocuments + " files. Start a second order for the rest.", "warn");
      return;
    }
    let skipped = 0, over = 0;
    const added = [];
    for (const f of files) {
      if (state.docs.some(d => d.file && d.file.name === f.name && d.file.size === f.size && d.file.lastModified === f.lastModified)) {
        skipped++;
        continue;
      }
      if (added.length >= room) { over++; continue; }
      const d = newDoc(f);
      state.docs.push(d);
      added.push(d);
    }
    if (skipped) toast(plural(skipped, "file was", "files were") + " already in your list.", "warn");
    if (over) toast(plural(over, "file was", "files were") + " not added: one order can have up to " + state.shop.maxDocuments + " files.", "warn");
    if (!added.length) return;
    if (state.step !== "setup") show("setup");
    if (!state.selected || !state.docs.includes(state.selected)) select(added[0], false);
    renderQueue();
    renderCheckout();
    added.forEach(d => prepare(d));
  }

  function newDoc(file) {
    return {
      local: nextLocal++, id: null, file, name: file.name || "file", size: file.size, type: null,
      status: "reading",        // reading | queued | uploading | checking | ready | error | cancelled
      progress: 0, xhr: null, error: null, retry: false,
      pageCount: null, image: null, thumb: null, settings: null, sheet: 0,
      sizes: new Map(), thumbs: new Map(), serverError: null, removed: false
    };
  }

  /** On the phone first: is it a file we can print, and how many pages? Then upload it. */
  async function prepare(d) {
    try {
      const type = await sniff(d.file);
      if (!type) return fail(d, "Only PDF, JPG and PNG files can be printed.", false);
      d.type = type;
      if (d.size > state.shop.maxFileSizeBytes) {
        return fail(d, "Too big: files can be up to " + Math.round(state.shop.maxFileSizeBytes / 1048576) + " MB.", false);
      }
      if (d.size === 0) return fail(d, "This file is empty.", false);
      renderQueue();
      await readSlot(() => (type === "PDF" ? readPdf(d) : readImage(d)));
      if (d.removed) return;
      d.settings = startingSettings(d);
      d.status = "queued";
      renderQueue();
      renderCheckout();
      if (state.selected === d) renderEditor();
      upload(d);
    } catch (e) {
      fail(d, e.message || "This file cannot be opened.", false);
    }
  }

  function fail(d, message, canRetry) {
    d.status = "error";
    d.error = message;
    d.retry = canRetry;
    renderQueue();
    renderCheckout();
    if (state.selected === d) renderEditor();
  }

  /** Settings a new file starts with: the usual, adjusted to what the printers offer. */
  function startingSettings(d) {
    const s = C.defaults(d.type !== "PDF");
    const o = state.offered;
    if (!o.bw && o.color) s.color = true;
    if (o.paperSizes.indexOf("A4") < 0 && o.paperSizes.length) s.paperSize = o.paperSizes[0];
    return s;
  }

  async function readPdf(d) {
    let doc;
    try {
      doc = await openPdf(d);
    } catch (e) {
      if (e && e.name === "PasswordException") throw new Error("This PDF is locked with a password. Save a copy without the password and add it again.");
      throw new Error("This PDF cannot be opened. It may be damaged.");
    }
    d.pageCount = doc.numPages;
    const maxFilePages = state.shop.maxFilePages || state.shop.maxPages;
    if (doc.numPages > maxFilePages) {
      throw new Error("This PDF has " + doc.numPages + " pages. Files can have up to " + maxFilePages + " pages.");
    }
    d.thumb = await pageThumb(d, 1, 120);
  }

  function readImage(d) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(d.file);
      const img = new Image();
      img.onload = () => {
        d.img = img;
        d.imgUrl = url;
        // Browsers show photos turned the way the camera meant: these are the sizes as seen.
        d.localImage = { widthPx: img.naturalWidth, heightPx: img.naturalHeight, dpi: 96, exifOrientation: 1 };
        d.pageCount = 1;
        d.thumb = downscale(img, 120);
        resolve();
      };
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("This picture cannot be opened. It may be damaged.")); };
      img.src = url;
    });
  }

  function downscale(img, width) {
    const scale = Math.min(1, width * 2 / Math.max(img.naturalWidth, 1));
    const c = document.createElement("canvas");
    c.width = Math.max(1, Math.round(img.naturalWidth * scale));
    c.height = Math.max(1, Math.round(img.naturalHeight * scale));
    c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
    return c.toDataURL("image/jpeg", 0.8);
  }

  // ================================================================== uploading

  const uploads = { active: 0, waiting: [] };

  function upload(d) {
    if (!uploads.waiting.includes(d)) uploads.waiting.push(d);
    pumpUploads();
  }
  function pumpUploads() {
    while (uploads.active < PARALLEL_UPLOADS && uploads.waiting.length) {
      const d = uploads.waiting.shift();
      if (d.removed || d.status === "cancelled" || d.status === "error") continue;
      uploads.active++;
      sendFile(d).finally(() => {
        uploads.active--;
        pumpUploads();
        renderQueue();
        renderCheckout();
      });
    }
  }

  /** One order for all the files: made when the first file is sent. */
  function ensureOrder() {
    if (state.draft) return Promise.resolve(state.draft);
    if (!state.draftPromise) {
      state.draftPromise = api("POST", "/api/v1/orders", {}).then(c => {
        state.draft = { orderId: c.orderId, key: c.accessKey, code: c.pickupCode };
        saveDraft();
        return state.draft;
      }).finally(() => { state.draftPromise = null; });
    }
    return state.draftPromise;
  }

  /**
   * Upload, then the server's check. Any step can fail on a bad connection:
   * "Try again" picks up from where it stopped.
   */
  async function sendFile(d) {
    try {
      const o = await ensureOrder();
      if (d.removed) return;
      let ticket;
      if (!d.id) {
        ticket = await api("POST", "/api/v1/orders/" + o.orderId + "/documents",
          { fileName: d.name, fileType: d.type, fileSizeBytes: d.size }, o.key);
        d.id = ticket.document.id;
        saveDraft();
      } else {
        ticket = await api("POST", "/api/v1/orders/" + o.orderId + "/documents/" + d.id + "/upload-url", null, o.key);
      }
      if (d.removed) { dropOnServer(d); return; }
      d.status = "uploading";
      d.progress = 0;
      renderQueue();
      await put(d, ticket.uploadUrl, ticket.uploadContentType);
      d.status = "checking";
      renderQueue();
      const v = await api("POST", "/api/v1/orders/" + o.orderId + "/documents/" + d.id + "/uploaded", null, o.key);
      takeServerView(d, v);
    } catch (e) {
      if (d.removed) return;
      if (e.aborted) {
        d.status = "cancelled";
        d.error = null;
      } else if (e.code === "PRICED" || e.code === "BAD_STATE") {
        fail(d, e.message, false);
      } else {
        fail(d, e.message || "The upload failed.", true);
      }
    } finally {
      d.xhr = null;
      if (state.selected === d) renderEditor();
    }
  }

  function takeServerView(d, v) {
    if (v.status === "REJECTED") {
      fail(d, v.problem || "This file cannot be printed.", false);
      return;
    }
    d.status = "ready";
    d.error = null;
    d.pageCount = v.pageCount;
    if (v.image) d.image = v.image;           // the server's measurements: used for "actual size"
    d.type = v.fileType;
  }

  function put(d, url, contentType) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      d.xhr = xhr;
      xhr.open("PUT", url);
      xhr.setRequestHeader("Content-Type", contentType);
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
          d.progress = e.loaded / e.total;
          renderQueueProgress();
        }
      };
      xhr.onload = () => (xhr.status >= 200 && xhr.status < 300)
        ? resolve() : reject(new Error("The file could not be sent (" + xhr.status + "). Try again."));
      xhr.onerror = () => reject(new Error("The upload stopped. Check your internet and try again."));
      xhr.onabort = () => { const e = new Error("Cancelled"); e.aborted = true; reject(e); };
      xhr.send(d.file);
    });
  }

  function cancelUpload(d) {
    const i = uploads.waiting.indexOf(d);
    if (i >= 0) uploads.waiting.splice(i, 1);
    if (d.xhr) d.xhr.abort();
    else if (d.status === "queued") d.status = "cancelled";
    renderQueue();
    renderCheckout();
  }

  function retry(d) {
    d.error = null;
    d.serverError = null;
    if (!d.type || d.pageCount == null) {
      d.status = "reading";
      renderQueue();
      prepare(d);
      return;
    }
    d.status = "queued";
    renderQueue();
    upload(d);
  }

  function removeDoc(d) {
    d.removed = true;
    cancelUpload(d);
    dropOnServer(d);
    if (d.imgUrl) URL.revokeObjectURL(d.imgUrl);
    closePdf(d);
    const i = state.docs.indexOf(d);
    state.docs.splice(i, 1);
    if (state.selected === d) select(state.docs[Math.min(i, state.docs.length - 1)] || null, false);
    saveDraft();
    renderQueue();
    renderCheckout();
    if (!state.docs.length) {
      document.body.classList.remove("editing");
    }
  }

  function dropOnServer(d) {
    if (!d.id || !state.draft) return;
    const o = state.draft;
    api("DELETE", "/api/v1/orders/" + o.orderId + "/documents/" + d.id, null, o.key).catch(() => { /* the server expires it */ });
  }

  window.addEventListener("beforeunload", (e) => {
    if (state.docs.some(d => ["reading", "queued", "uploading", "checking"].includes(d.status))) {
      e.preventDefault();
      e.returnValue = "";
    }
  });

  // ================================================================== PDFs (PDF.js), opened a few at a time

  let pdfjsLib = null;
  function pdfjs() {
    if (!pdfjsLib) {
      pdfjsLib = import(PDFJS).then(m => { m.GlobalWorkerOptions.workerSrc = PDFJS_WORKER; return m; });
      pdfjsLib.catch(() => { pdfjsLib = null; });
    }
    return pdfjsLib;
  }
  const openDocs = [];           // most recently used last; only a few big PDFs stay in memory

  async function source(d) {
    if (d.file) return d.file;
    // A file restored after a page reload: fetch the student's own copy back.
    const o = state.draft;
    const r = await api("GET", "/api/v1/orders/" + o.orderId + "/documents/" + d.id + "/file-url", null, o.key);
    const res = await fetch(r.url);
    if (!res.ok) throw new Error("The file could not be loaded again.");
    d.file = new File([await res.blob()], d.name);
    return d.file;
  }

  function openPdf(d) {
    const i = openDocs.indexOf(d);
    if (i >= 0) openDocs.splice(i, 1);
    openDocs.push(d);
    if (!d.pdfPromise) {
      d.pdfPromise = (async () => {
        const lib = await pdfjs();
        const bytes = new Uint8Array(await (await source(d)).arrayBuffer());
        return lib.getDocument({ data: bytes, isEvalSupported: false }).promise;
      })();
      d.pdfPromise.catch(() => { d.pdfPromise = null; });
    }
    while (openDocs.length > 2) {
      const old = openDocs.find(x => x !== state.selected && x !== d);
      if (!old) break;
      closePdf(old);
    }
    return d.pdfPromise;
  }

  function closePdf(d) {
    const i = openDocs.indexOf(d);
    if (i >= 0) openDocs.splice(i, 1);
    if (d.pdfPromise) {
      const p = d.pdfPromise;
      d.pdfPromise = null;
      p.then(doc => setTimeout(() => doc.destroy(), 1500)).catch(() => {});
    }
  }

  /** A page's size as seen (points), after its own rotation: the same size the Xerox PC lays out. */
  async function pageSize(d, n) {
    if (d.sizes.has(n)) return d.sizes.get(n);
    const doc = await openPdf(d);
    const page = await doc.getPage(n);
    const vp = page.getViewport({ scale: 1 });
    const s = { w: vp.width, h: vp.height };
    d.sizes.set(n, s);
    return s;
  }

  /** Draws a page (as it prints: with its printable annotations) into a new canvas. */
  async function drawPage(d, n, widthPx) {
    const doc = await openPdf(d);
    const lib = await pdfjs();
    const page = await doc.getPage(n);
    const base = page.getViewport({ scale: 1 });
    const vp = page.getViewport({ scale: Math.max(0.05, widthPx / base.width) });
    const c = document.createElement("canvas");
    c.width = Math.max(1, Math.floor(vp.width));
    c.height = Math.max(1, Math.floor(vp.height));
    const ctx = c.getContext("2d");
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, c.width, c.height);
    await page.render({ canvasContext: ctx, viewport: vp, intent: "print", annotationMode: lib.AnnotationMode.ENABLE }).promise;
    return c;
  }

  const thumbCache = [];         // [doc, key], oldest first
  async function pageThumb(d, n, width) {
    const key = n + ":" + width;
    if (d.thumbs.has(key)) return d.thumbs.get(key);
    const c = await drawPage(d, n, width * Math.min(2, window.devicePixelRatio || 1));
    const url = c.toDataURL("image/jpeg", 0.82);
    d.thumbs.set(key, url);
    thumbCache.push([d, key]);
    while (thumbCache.length > 600) {
      const [od, ok] = thumbCache.shift();
      if (od !== d || ok !== key) od.thumbs.delete(ok);
    }
    return url;
  }

  // ================================================================== the list of files

  function norm(d) {
    if (!d.settings || d.pageCount == null) return null;
    return C.normalize(d.settings, { type: d.type, pageCount: d.pageCount, paperLabel: (id) => paper(id).label },
      state.limits, state.printers);
  }

  function docPrice(d, n) {
    n = n || norm(d);
    if (!n) return null;
    return C.price(n.settings, n.plan, state.shop.priceBwPaise, state.shop.priceColorPaise, state.shop.printing.pricing);
  }

  function busyStatus(d) {
    return ["reading", "queued", "uploading", "checking"].includes(d.status);
  }

  /** Short words for a file's settings, for its card. */
  function chips(d, n) {
    const s = n.settings, out = [];
    if (d.type === "PDF") {
      out.push(s.pages ? "pp. " + C.displaySpec(s.pages) + " (" + n.plan.printPages + ")"
        : d.pageCount === 1 ? "1 page" : "All " + d.pageCount + " pages");
    }
    if (s.copies > 1) out.push("×" + s.copies);
    out.push(s.color ? "Colour" : "B/W");
    if (s.duplex !== "ONE_SIDED") out.push("2-sided");
    out.push(s.paperSize === "A4" ? "A4" : paper(s.paperSize).id.replace("PHOTO_", "").replace("X", "×"));
    if (s.pagesPerSheet > 1) out.push(s.pagesPerSheet + "/sheet");
    if (s.marginMm === 0) out.push("Borderless");
    if (d.type !== "PDF" && s.scaling === "FILL") out.push("Fill");
    if (s.staple) out.push("Stapled");
    if (s.punch) out.push("Punched");
    if (s.bind) out.push("Bound");
    if (s.mediaType) out.push(mediaLabel(s.mediaType));
    if (s.quality === "HIGH") out.push("High quality");
    return out;
  }

  function renderQueue() {
    const box = $("queueList");
    box.innerHTML = "";
    state.docs.forEach((d, i) => box.appendChild(card(d, i)));
    renderQueueProgress();
    $("wsSub").textContent = state.docs.length
      ? plural(state.docs.length, "file", "files") + " · set up each one, then review your order."
      : "Add the files you want to print.";
  }

  function card(d, i) {
    const n = d.status === "ready" || d.status === "queued" || d.status === "uploading" || d.status === "checking" ? norm(d) : null;
    const problem = d.status === "error" ? d.error : (n && n.error) || d.serverError;
    const c = el("div", "qcard" + (d === state.selected ? " on" : "") + (problem ? " bad" : ""));
    c.tabIndex = 0;
    c.setAttribute("role", "button");
    c.setAttribute("aria-label", "File " + (i + 1) + ": " + d.name);
    c.dataset.local = d.local;
    const th = el("div", "qthumb");
    if (d.thumb) {
      const img = el("img");
      img.src = d.thumb;
      img.alt = "";
      th.appendChild(img);
    } else {
      th.appendChild(icon(d.type === "PDF" || !d.type ? "file" : "image"));
    }
    if (d.type) th.appendChild(el("span", "kind", d.type === "JPEG" ? "JPG" : d.type));
    const main = el("div", "qmain");
    main.append(el("div", "qname", d.name));
    const meta = [];
    if (d.type === "PDF" && d.pageCount != null) meta.push(plural(d.pageCount, "page", "pages"));
    if (d.type && d.type !== "PDF") meta.push("Picture");
    meta.push(C.fileSize(d.size));
    main.append(el("div", "qmeta", meta.join(" · ")));
    const st = el("div", "qstate");
    const acts = el("div", "qacts");
    const act = (label, iconName, fn, cls) => {
      const b = el("button", "btn ghost xs" + (cls ? " " + cls : ""));
      b.type = "button";
      if (iconName) b.appendChild(icon(iconName));
      b.append(label);
      b.onclick = (e) => { e.stopPropagation(); fn(); };
      acts.appendChild(b);
    };
    switch (d.status) {
      case "reading": st.append(el("div", "line", "Reading the file…")); break;
      case "queued":
        st.append(el("div", "line", "Waiting to upload…"));
        act("Cancel", null, () => cancelUpload(d));
        break;
      case "uploading": {
        const line = el("div", "line");
        line.append(el("span", "pct", "Uploading " + Math.round(d.progress * 100) + "%"));
        const bar = el("div", "bar");
        const fill = el("i");
        fill.style.width = Math.round(d.progress * 100) + "%";
        bar.appendChild(fill);
        st.append(line, bar);
        act("Cancel", null, () => cancelUpload(d));
        break;
      }
      case "checking": st.append(el("div", "line", "Checking the file…")); break;
      case "cancelled":
        st.append(el("div", "line", "Upload cancelled"));
        act("Try again", "retry", () => retry(d));
        act("Remove", null, () => removeDoc(d));
        break;
      case "error": {
        const b = el("div", "bad");
        b.append(icon("warn"), el("span", null, d.error));
        st.append(b);
        if (d.retry) act("Try again", "retry", () => retry(d));
        act("Remove", null, () => removeDoc(d));
        break;
      }
      default: break;
    }
    if (n && d.status !== "error") {
      const ch = el("div", "qchips");
      chips(d, n).forEach(t => ch.appendChild(el("span", problem ? "c" : null, t)));
      st.prepend(ch);
      if (problem) {
        const b = el("div", "bad");
        b.style.marginTop = "6px";
        b.append(icon("warn"), el("span", null, problem));
        st.append(b);
      }
    }
    main.append(st);
    if (acts.children.length) main.append(acts);
    const side = el("div", "qside");
    const price = n && !n.error ? docPrice(d, n) : null;
    side.append(el("div", "qprice", price ? rupees(price.amount) : ""));
    const rm = el("button", "qremove");
    rm.type = "button";
    rm.setAttribute("aria-label", "Remove " + d.name);
    rm.appendChild(icon("x"));
    rm.onclick = (e) => { e.stopPropagation(); removeDoc(d); };
    side.append(rm);
    // the order of the list is the order the files print in
    if (state.docs.length > 1) {
      const mv = el("div", "qmove");
      [["up", -1, "earlier"], ["down", 1, "later"]].forEach(([name, delta, words]) => {
        const b = el("button");
        b.type = "button";
        b.setAttribute("aria-label", "Print " + d.name + " " + words);
        b.title = "Move " + (delta < 0 ? "up" : "down");
        b.disabled = delta < 0 ? i === 0 : i === state.docs.length - 1;
        b.appendChild(icon(name));
        b.onclick = (e) => { e.stopPropagation(); moveDoc(d, delta); };
        mv.append(b);
      });
      side.append(mv);
    }
    c.append(th, main, side);
    const open = () => select(d, true);
    c.onclick = open;
    c.onkeydown = (e) => {
      if (e.altKey && (e.key === "ArrowUp" || e.key === "ArrowDown")) { e.preventDefault(); moveDoc(d, e.key === "ArrowUp" ? -1 : 1, true); return; }
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); open(); }
    };
    return c;
  }

  /** Moves a file earlier or later in the order (the order the files print in). */
  function moveDoc(d, delta, keepFocus) {
    const i = state.docs.indexOf(d), j = i + delta;
    if (i < 0 || j < 0 || j >= state.docs.length) return;
    state.docs.splice(i, 1);
    state.docs.splice(j, 0, d);
    saveDraft();
    renderQueue();
    if (state.selected) renderEditor();
    renderCheckout();
    if (keepFocus) {
      const c = document.querySelector('.qcard[data-local="' + d.local + '"]');
      if (c) c.focus();
    }
  }

  /** Progress only (cheap: runs on every upload progress event). */
  function renderQueueProgress() {
    for (const d of state.docs) {
      if (d.status !== "uploading") continue;
      const c = document.querySelector('.qcard[data-local="' + d.local + '"]');
      if (!c) continue;
      const pct = Math.round(d.progress * 100);
      const fill = c.querySelector(".bar > i");
      if (fill) fill.style.width = pct + "%";
      const t = c.querySelector(".pct");
      if (t) t.textContent = "Uploading " + pct + "%";
    }
    const top = $("queueTop");
    const docs = state.docs;
    const working = docs.filter(busyStatus);
    const bad = docs.filter(d => d.status === "error" || d.status === "cancelled");
    top.innerHTML = "";
    if (!docs.length) return;
    if (working.length) {
      let total = 0, done = 0;
      for (const d of docs) {
        if (d.status === "error" || d.status === "cancelled") continue;
        total += d.size;
        done += d.status === "uploading" ? d.size * d.progress
          : d.status === "checking" || d.status === "ready" ? d.size : 0;
      }
      const pct = total ? Math.round(done / total * 100) : 0;
      const row = el("div", "qt-row");
      row.append(el("span", null, "Uploading " + plural(working.length, "file", "files") + " · " + pct + "%"));
      const all = el("button", "linkish", "Cancel all");
      all.type = "button";
      all.onclick = () => working.forEach(cancelUpload);
      row.append(all);
      const bar = el("div", "bar");
      const fill = el("i");
      fill.style.width = pct + "%";
      bar.appendChild(fill);
      top.append(row, bar);
    } else {
      const ready = docs.filter(d => d.status === "ready").length;
      const row = el("div", "qt-row");
      const b = el("b", null, ready === docs.length ? (docs.length === 1 ? "Your file is ready" : "All " + docs.length + " files ready")
        : ready + " of " + docs.length + " files ready");
      row.append(b);
      if (bad.length) row.append(el("span", "muted", plural(bad.length, "needs", "need") + " attention"));
      const bar = el("div", "bar ok");
      const fill = el("i");
      fill.style.width = Math.round(ready / docs.length * 100) + "%";
      bar.appendChild(fill);
      top.append(row, bar);
    }
  }

  // ================================================================== the editor: one file

  const phone = window.matchMedia("(max-width: 899px)");

  function select(d, open) {
    state.selected = d;
    if (d) d.sheet = Math.min(d.sheet || 0, 10000);
    state.pvTab = "preview";
    if (open && phone.matches && d) {
      document.body.classList.add("editing");
      setTimeout(() => $("edClose").focus(), 50);
    }
    renderQueue();
    renderEditor();
  }

  /** Opens a file that needs something and brings what it needs into view. */
  function showProblem(d) {
    select(d, true);
    requestAnimationFrame(() => {
      const e = document.querySelector("#settingsPanel .field-error:not(:empty)");
      if (e) e.scrollIntoView({ block: "nearest", behavior: "smooth" });
    });
  }

  function closeEditor() {
    document.body.classList.remove("editing");
    const c = state.selected && document.querySelector('.qcard[data-local="' + state.selected.local + '"]');
    if (c) c.focus();
  }

  function renderEditor() {
    const d = state.selected;
    const ed = $("editor");
    ed.classList.toggle("hidden", !d);
    if (!d) return;
    const i = state.docs.indexOf(d);
    $("edName").textContent = d.name;
    const meta = [];
    meta.push(d.type === "JPEG" ? "JPG picture" : d.type === "PNG" ? "PNG picture" : d.type === "PDF" ? "PDF" : "File");
    if (d.type === "PDF" && d.pageCount != null) meta.push(plural(d.pageCount, "page", "pages"));
    meta.push(C.fileSize(d.size));
    $("edMeta").textContent = meta.join(" · ");
    $("edPos").textContent = (i + 1) + " / " + state.docs.length;
    $("edPrev").disabled = i <= 0;
    $("edNext").disabled = i >= state.docs.length - 1;
    const pagesTab = d.type === "PDF" && d.pageCount > 1;
    $("tabPages").classList.toggle("hidden", !pagesTab);
    if (!pagesTab) state.pvTab = "preview";
    $("tabPreview").classList.toggle("on", state.pvTab === "preview");
    $("tabPreview").setAttribute("aria-selected", String(state.pvTab === "preview"));
    $("tabPages").classList.toggle("on", state.pvTab === "pages");
    $("tabPages").setAttribute("aria-selected", String(state.pvTab === "pages"));
    $("pvStage").classList.toggle("hidden", state.pvTab !== "preview");
    $("pvFoot").classList.toggle("hidden", state.pvTab !== "preview");
    $("pagesPanel").classList.toggle("hidden", state.pvTab !== "pages");
    renderSettings();
    if (state.pvTab === "preview") renderPreview(); else renderPagesGrid();
    renderEditorPrice();
  }

  function renderEditorPrice() {
    const d = state.selected;
    const n = d && norm(d);
    const p = n && !n.error ? docPrice(d, n) : null;
    const problem = !!(n && (n.error || d.serverError));
    $("edPrice").textContent = p ? rupees(p.amount) : "–";
    $("edPriceSub").textContent = problem ? "needs a change" : n ? plural(n.plan.sheets * n.settings.copies, "sheet", "sheets") : "";
    $("edPriceSub").classList.toggle("warn-text", problem);
  }

  // ------------------------------------------------------------------ the print preview (one sheet at a time)

  let previewToken = 0;
  let previewTimer = null;
  function schedulePreview() {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(renderPreview, 120);
  }

  /** The chosen pages, as page numbers in print order. */
  function chosenPages(d, s) {
    if (d.type !== "PDF") return [1];
    try {
      return Array.from(C.pagesFromSpec(s.pages, d.pageCount)).sort((a, b) => a - b);
    } catch (e) {
      return [];
    }
  }

  function sheetCount(d, s) {
    const pages = chosenPages(d, s).length;
    return d.type === "PDF" && s.pagesPerSheet > 1 ? Math.ceil(pages / s.pagesPerSheet) : pages;
  }

  async function renderPreview() {
    const d = state.selected;
    const token = ++previewToken;
    const canvas = $("sheetCanvas");
    const empty = $("pvEmpty");
    const stage = $("pvStage");
    const n = d && norm(d);
    const setEmpty = (text) => {
      canvas.classList.add("hidden");
      empty.classList.remove("hidden");
      empty.textContent = text;
      $("sheetLabel").textContent = "";
      $("sheetPrev").disabled = $("sheetNext").disabled = true;
    };
    if (!d || d.status === "error") return setEmpty(d ? "This file cannot be printed." : "");
    if (!n) return setEmpty("Reading the file…");
    const s = n.settings;
    const pages = chosenPages(d, s);
    if (!pages.length) return setEmpty("Choose at least one page to see the preview.");
    const total = sheetCount(d, s);
    d.sheet = Math.max(0, Math.min(d.sheet || 0, total - 1));
    const k = d.sheet;
    const isPic = d.type !== "PDF";
    const o = C.layoutOptions(s, paper(s.paperSize), isPic);
    try {
      let sheet;
      if (isPic) {
        const info = d.image || d.localImage;
        const size = C.pictureSize(info, s.rotation);
        sheet = C.layoutSheet(0, 1, () => size, o);
      } else {
        const need = new Set([0]);
        const per = s.pagesPerSheet > 1 ? s.pagesPerSheet : 1;
        for (let j = k * per; j < Math.min(pages.length, (k + 1) * per); j++) need.add(j);
        if (per === 1) { need.clear(); need.add(k); }
        const sizes = new Map();
        for (const j of need) sizes.set(j, await pageSize(d, pages[j]));
        if (token !== previewToken) return;
        sheet = C.layoutSheet(k, pages.length, (j) => sizes.get(j), o);
      }
      await paintSheet(canvas, stage, d, s, sheet, pages, token);
      if (token !== previewToken) return;
      canvas.classList.remove("hidden");
      empty.classList.add("hidden");
      stage.classList.toggle("bw", !s.color);
      // "Sheet 2 of 5", or for two-sided "Side 3 of 9 · sheet 2, back"
      const two = s.duplex !== "ONE_SIDED";
      const label = $("sheetLabel");
      label.innerHTML = "";
      if (two) {
        label.append(el("b", null, "Side " + (k + 1) + " of " + total),
          " · sheet " + (Math.floor(k / 2) + 1) + ", " + (k % 2 === 0 ? "front" : "back"));
      } else {
        label.append(el("b", null, "Sheet " + (k + 1) + " of " + total));
      }
      if (isPic) label.append(" · " + paper(s.paperSize).id);
      $("sheetPrev").disabled = k <= 0;
      $("sheetNext").disabled = k >= total - 1;
    } catch (e) {
      if (token === previewToken) setEmpty("The preview could not be drawn. The file is still fine to print.");
    }
  }

  /** Paper, margins, and the content placed exactly as the Xerox PC will place it. */
  async function paintSheet(canvas, stage, d, s, sheet, pages, token) {
    const pad = 18;
    const boxW = Math.max(120, stage.clientWidth - pad * 2), boxH = Math.max(120, stage.clientHeight - pad * 2);
    const scale = Math.min(boxW / sheet.w, boxH / sheet.h);      // CSS px per point
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const W = Math.round(sheet.w * scale), H = Math.round(sheet.h * scale);
    // draw off screen, then swap in (no flicker while pages render)
    const off = document.createElement("canvas");
    off.width = Math.round(W * dpr);
    off.height = Math.round(H * dpr);
    const ctx = off.getContext("2d");
    ctx.scale(dpr, dpr);
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, W, H);
    const Y = (y, h) => (sheet.h - y - h) * scale;             // PDF y-up -> canvas y-down

    ctx.save();
    if (sheet.clip) {
      ctx.beginPath();
      ctx.rect(sheet.clip.x * scale, Y(sheet.clip.y, sheet.clip.h), sheet.clip.w * scale, sheet.clip.h * scale);
      ctx.clip();
    }
    for (const cell of sheet.cells) {
      const x = cell.x * scale, y = Y(cell.y, cell.h), w = cell.w * scale, h = cell.h * scale;
      ctx.save();
      if (sheet.cells.length > 1) {
        ctx.beginPath();
        ctx.rect(x, y, w, h);
        ctx.clip();
      }
      if (d.type === "PDF") {
        const img = await drawPage(d, pages[cell.page], Math.max(40, w * dpr));
        if (token !== previewToken) return;
        ctx.drawImage(img, x, y, w, h);
        if (sheet.cells.length > 1) {
          ctx.strokeStyle = "rgba(14,26,43,.15)";
          ctx.lineWidth = 1;
          ctx.strokeRect(x + .5, y + .5, w - 1, h - 1);
        }
      } else {
        const img = d.img || await loadImg(d);
        if (token !== previewToken) return;
        const turn = ((s.rotation || 0) % 360 + 360) % 360;
        ctx.translate(x + w / 2, y + h / 2);
        ctx.rotate(turn * Math.PI / 180);
        const q = turn % 180 !== 0;
        ctx.drawImage(img, -(q ? h : w) / 2, -(q ? w : h) / 2, q ? h : w, q ? w : h);
      }
      ctx.restore();
    }
    ctx.restore();
    // margin guides
    if (s.marginMm > 0) {
      const m = s.marginMm * C.MM * scale;
      ctx.save();
      ctx.setLineDash([4, 4]);
      ctx.strokeStyle = "rgba(59,111,240,.55)";
      ctx.lineWidth = 1;
      ctx.strokeRect(m + .5, m + .5, W - 2 * m - 1, H - 2 * m - 1);
      ctx.restore();
    }
    canvas.width = off.width;
    canvas.height = off.height;
    canvas.style.width = W + "px";
    canvas.style.height = H + "px";
    const c2 = canvas.getContext("2d");
    c2.drawImage(off, 0, 0);
  }

  function loadImg(d) {
    return new Promise(async (resolve, reject) => {
      try {
        const f = await source(d);
        const url = URL.createObjectURL(f);
        const img = new Image();
        img.onload = () => {
          d.img = img;
          d.imgUrl = url;
          d.localImage = { widthPx: img.naturalWidth, heightPx: img.naturalHeight, dpi: 96, exifOrientation: 1 };
          if (!d.thumb) { d.thumb = downscale(img, 120); renderQueue(); }
          resolve(img);
        };
        img.onerror = reject;
        img.src = url;
      } catch (e) { reject(e); }
    });
  }

  // ------------------------------------------------------------------ choosing pages on thumbnails

  let gridObserver = null;
  let lastClicked = null;

  function renderPagesGrid() {
    const d = state.selected;
    const grid = $("pagesGrid");
    grid.innerHTML = "";
    if (gridObserver) gridObserver.disconnect();
    if (!d || d.type !== "PDF" || !d.pageCount) return;
    const n = norm(d);
    const set = currentPageSet(d);
    updatePagesBar(d, n);
    gridObserver = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        const tile = e.target;
        gridObserver.unobserve(tile);
        const no = Number(tile.dataset.page);
        pageThumb(d, no, 110).then(url => {
          const paperBox = tile.querySelector(".paper");
          if (!paperBox || state.selected !== d) return;
          const img = el("img");
          img.src = url;
          img.alt = "";
          paperBox.innerHTML = "";
          paperBox.appendChild(img);
        }).catch(() => {});
      }
    }, { root: grid, rootMargin: "300px" });
    const frag = document.createDocumentFragment();
    for (let p = 1; p <= d.pageCount; p++) {
      const t = el("button", "ptile" + (set.has(p) ? " on" : ""));
      t.type = "button";
      t.dataset.page = p;
      t.setAttribute("aria-pressed", String(set.has(p)));
      t.setAttribute("aria-label", "Page " + p);
      const box = el("span", "paper");
      const tick = el("span", "tick");
      tick.appendChild(icon("check"));
      t.append(box, el("span", "no", "Page " + p), tick);
      frag.appendChild(t);
    }
    grid.appendChild(frag);
    grid.querySelectorAll(".ptile").forEach(t => gridObserver.observe(t));
  }

  /** The pages chosen now (all of them when the list cannot be read). */
  function currentPageSet(d) {
    try {
      return C.pagesFromSpec(d.settings.pages, d.pageCount);
    } catch (e) {
      return new Set();
    }
  }

  function updatePagesBar(d, n) {
    const set = currentPageSet(d);
    $("pagesBarText").textContent = set.size === d.pageCount ? "All " + d.pageCount + " pages · tap to leave pages out"
      : !set.size ? "No pages chosen · tap the pages you want"
      : set.size + " of " + d.pageCount + " pages · tap to add or leave out";
  }

  function setPages(d, set) {
    d.settings.pages = C.specFromPages(set, d.pageCount);     // "" = none chosen yet
    d.pagesDraft = null;
    changed(d, { keepGrid: true });
    document.querySelectorAll("#pagesGrid .ptile").forEach(t => {
      const on = set.has(Number(t.dataset.page));
      t.classList.toggle("on", on);
      t.setAttribute("aria-pressed", String(on));
    });
    updatePagesBar(d, norm(d));
    return true;
  }

  $("pagesGrid").addEventListener("click", (e) => {
    const t = e.target.closest(".ptile");
    const d = state.selected;
    if (!t || !d) return;
    const p = Number(t.dataset.page);
    const set = currentPageSet(d);
    if (e.shiftKey && lastClicked != null) {
      const on = !set.has(p) ? true : set.has(lastClicked);
      const [a, b] = [Math.min(lastClicked, p), Math.max(lastClicked, p)];
      for (let i = a; i <= b; i++) { if (on) set.add(i); else set.delete(i); }
    } else if (set.has(p)) {
      set.delete(p);
    } else {
      set.add(p);
    }
    lastClicked = p;
    setPages(d, set);
  });

  document.querySelectorAll("[data-quick]").forEach(b => {
    b.onclick = () => {
      const d = state.selected;
      if (!d || !d.pageCount) return;
      const set = new Set();
      const now = currentPageSet(d);
      for (let p = 1; p <= d.pageCount; p++) {
        const q = b.dataset.quick;
        if (q === "all" || (q === "odd" && p % 2 === 1) || (q === "even" && p % 2 === 0) || (q === "invert" && !now.has(p))) set.add(p);
        // "none": nothing, then tap the pages you want
      }
      setPages(d, set);
    };
  });

  // ================================================================== settings for one file

  /** Something changed on a file: redraw what depends on it. */
  function changed(d, opts) {
    opts = opts || {};
    d.serverError = null;
    if (!opts.keepPanel) renderSettings();
    if (state.pvTab === "preview") schedulePreview();
    else if (!opts.keepGrid) renderPagesGrid();
    renderQueue();
    renderCheckout();
    renderEditorPrice();
    saveDraftSoon();
  }

  /** Applies one choice. When the printers cannot do it with the other choices, applies the fix and says so. */
  function pick(d, apply, entry) {
    if (entry && !entry.available) {
      if (!entry.fix) { toast("That is not possible with the printers here.", "warn"); return; }
      d.settings = Object.assign({}, entry.fix.settings);
      toast("Also changed to " + entry.fix.changes.join(" and ") + ", so it can be printed.", "warn");
    } else {
      apply(d.settings);
    }
    changed(d);
  }

  const APPLY = {
    color: (v) => (s) => { s.color = v; },
    paperSize: (v) => (s) => { s.paperSize = v; },
    duplex: (v) => (s) => { s.duplex = v; },
    staple: (v) => (s) => { s.staple = v; },
    punch: (v) => (s) => { s.punch = v; },
    bind: (v) => (s) => { s.bind = v; },
    mediaType: (v) => (s) => { s.mediaType = v; },
    quality: (v) => (s) => { s.quality = v; },
    borderless: (v) => (s) => { s.marginMm = v ? 0 : (s.marginMm === 0 ? C.DEFAULT_MARGIN : s.marginMm); }
  };

  /**
   * The values of one setting students can see: those a printer supports (with
   * the fix when they conflict), plus the one chosen now even when no printer
   * can do it any more (marked "gone"), so it can be changed.
   */
  function states(dim, values, s) {
    const cur = dim === "borderless" ? s.marginMm === 0 : (s[dim] == null ? null : s[dim]);
    const list = dim === "borderless" || values.includes(cur) ? values : values.concat([cur]);
    return C.availability(dim, list, s, state.printers)
      .filter(a => a.supported || a.value === cur)
      .map(a => a.supported ? a : Object.assign({}, a, { gone: true }));
  }

  // ------------------------------------------------------------------ small building blocks

  function field(label, note) {
    const f = el("div", "field");
    const head = el("div", "field-head");
    head.append(el("span", "field-label", label));
    const n = el("span", "field-note", note || "");
    head.append(n);
    f.append(head);
    f.note = n;
    return f;
  }

  function hint(f, text) {
    const h = el("p", "field-hint", text || "");
    f.append(h);
    return h;
  }

  function errorLine(text) {
    const e = el("p", "field-error");
    if (text) e.append(icon("warn"), el("span", null, text));
    return e;
  }

  /**
   * Buttons in a row, one chosen. items: { label, sub, on, conflict, disabled, title, pick }.
   */
  function seg(items, wrap, ariaLabel) {
    const g = el("div", "seg" + (wrap ? " wrap" : ""));
    g.setAttribute("role", "radiogroup");
    if (ariaLabel) g.setAttribute("aria-label", ariaLabel);
    for (const it of items) {
      const b = el("button", (it.on ? "on" : "") + (it.conflict ? " conflict" : ""));
      b.type = "button";
      b.setAttribute("role", "radio");
      b.setAttribute("aria-checked", String(!!it.on));
      b.append(it.label);
      if (it.sub) b.append(el("small", null, it.sub));
      if (it.note) { b.classList.add("has-note"); b.append(el("small", "note", it.note)); }
      if (it.title) b.title = it.title;
      if (it.disabled) b.disabled = true;
      b.onclick = () => { if (!it.on) it.pick(); };
      g.appendChild(b);
    }
    return g;
  }

  function conflictSub(entry) {
    if (entry && entry.gone) return "not available now";
    return entry && !entry.available && entry.fix ? "→ " + entry.fix.changes.join(", ") : null;
  }

  function numberInput(value, min, max, step, onValue, label) {
    const i = el("input", "text");
    i.type = "number";
    i.inputMode = "numeric";
    i.min = min; i.max = max; i.step = step;
    i.value = value;
    i.style.width = "96px";
    i.setAttribute("aria-label", label);
    let t = null;
    i.oninput = () => {
      clearTimeout(t);
      t = setTimeout(() => {
        const v = Number(i.value);
        if (i.value !== "" && v >= min && v <= max) onValue(Math.round(v));
      }, 350);
    };
    return i;
  }

  function group(title, summary, open) {
    const g = el("details", "group");
    if (open) g.open = true;
    const sm = el("summary");
    sm.append(el("span", "field-label", title), el("span", "sum", summary));
    g.append(sm);
    g.addEventListener("toggle", () => { (state.openGroups = state.openGroups || {})[title] = g.open; });
    if (state.openGroups && title in state.openGroups) g.open = state.openGroups[title];
    return g;
  }

  // ------------------------------------------------------------------ the panel

  function renderSettings() {
    const panel = $("settingsPanel");
    const d = state.selected;
    panel.innerHTML = "";
    if (!d) return;
    if (d.status === "error") {
      const f = field("This file cannot be printed");
      f.append(errorLine(d.error));
      const row = el("div", "row");
      row.style.marginTop = "12px";
      if (d.retry) {
        const b = el("button", "btn ghost sm");
        b.type = "button";
        b.append(icon("retry"), "Try again");
        b.onclick = () => retry(d);
        row.append(b);
      }
      const r = el("button", "btn ghost sm", "Remove it");
      r.type = "button";
      r.onclick = () => removeDoc(d);
      row.append(r);
      f.append(row);
      panel.append(f);
      return;
    }
    const n = norm(d);
    if (!n) {
      const f = field("Settings");
      hint(f, "Reading the file… the settings appear in a moment.");
      panel.append(f);
      return;
    }
    const s = n.settings;
    const pic = d.type !== "PDF";
    const off = state.offered;

    const problem = n.error || d.serverError;
    if (problem && !(n.error && /page/i.test(n.error) && !pic)) {
      const f = el("div", "field");
      f.append(errorLine(problem));
      // the printers changed under these settings: one tap to the nearest thing they can do
      const fix = C.quickFix(s, state.printers);
      if (fix) {
        const b = el("button", "btn ghost sm fix-btn");
        b.type = "button";
        b.append(icon("check"), "Change to " + fix.changes.join(" and "));
        b.onclick = () => {
          d.settings = Object.assign({}, fix.settings);
          changed(d);
          toast("Changed to " + fix.changes.join(" and ") + ". It can be printed now.");
        };
        f.append(b);
      }
      panel.append(f);
    }

    if (!pic && d.pageCount > 1) panel.append(pagesField(d, n));
    panel.append(copiesField(d, n));
    panel.append(colorField(d, s));
    if (off.duplex || s.duplex !== "ONE_SIDED") panel.append(sidesField(d, n));
    panel.append(paperField(d, s));
    panel.append(pic ? pictureGroup(d, n) : layoutGroup(d, n));
    if (off.finishing.length || s.staple || s.punch || s.bind) panel.append(finishingGroup(d, n));
    if (off.mediaTypes.length || off.highQuality || s.mediaType || s.quality === "HIGH") panel.append(paperQualityGroup(d, s));
    panel.append(priceBlock(d, n));
  }

  // ------------------------------------------------------------------ pages

  function pagesField(d, n) {
    const s = n.settings;
    const f = field("Pages to print");
    const row = el("div", "row");
    const input = el("input", "text grow");
    input.id = "pagesText";
    input.autocomplete = "off";
    input.spellcheck = false;
    input.maxLength = 200;
    input.placeholder = d.settings.pages === "" ? "No pages chosen yet" : "All " + d.pageCount + " pages";
    input.value = d.pagesDraft != null ? d.pagesDraft : (s.pages ? C.displaySpec(s.pages) : "");
    input.setAttribute("aria-label", "Pages to print, for example 3, 7, 10-12");
    const pickBtn = el("button", "btn ghost sm", "Pick on pages");
    pickBtn.type = "button";
    pickBtn.onclick = () => setTab("pages");
    row.append(input, pickBtn);
    f.append(row);
    const summary = el("div", "selected-line");
    summary.id = "pagesSummary";
    const err = errorLine(null);
    err.id = "pagesError";
    f.append(summary, err);
    hint(f, "Type pages like 3, 7, 10-12 (the PDF's own page numbers), or pick them on the page pictures.");
    fillPagesSummary(d, n, summary, err);
    let t = null;
    input.addEventListener("input", () => {
      d.pagesDraft = input.value;
      clearTimeout(t);
      t = setTimeout(() => {
        d.settings.pages = input.value.trim() ? input.value : null;
        input.placeholder = "All " + d.pageCount + " pages";
        const m = norm(d);
        input.classList.toggle("bad", !!(m.error && /page|number|Pages start|Write the pages/i.test(m.error)));
        fillPagesSummary(d, m, $("pagesSummary"), $("pagesError"));
        changed(d, { keepPanel: true });
      }, 250);
    });
    input.addEventListener("blur", () => {
      const m = norm(d);
      if (!m.error || !/page|number|Pages start|Write the pages/i.test(m.error)) {
        d.pagesDraft = null;
        d.settings.pages = m.settings.pages;
        input.value = m.settings.pages ? C.displaySpec(m.settings.pages) : "";
      }
    });
    return f;
  }

  /** "Selected: 3, 7, 10–12 · Total pages to print: 5" */
  function fillPagesSummary(d, n, summary, err) {
    summary.innerHTML = "";
    err.innerHTML = "";
    const pageError = n.error && /page|number|Pages start|Write the pages|Up to/i.test(n.error);
    const s = n.settings;
    if (pageError) {
      err.append(icon("warn"), el("span", null, n.error));
      if (s.pages !== "") return;
    }
    summary.append("Selected: ", s.pages ? el("b", "sel", C.displaySpec(s.pages)) : el("b", null, s.pages === "" ? "none" : "all " + d.pageCount + " pages"),
      " · Total pages to print: ", el("b", null, String(n.plan.printPages)));
  }

  // ------------------------------------------------------------------ copies, colour, sides, paper

  function copiesField(d, n) {
    const s = n.settings;
    const f = field("Copies");
    const wrap = el("div", "copies");
    wrap.append(seg([1, 2, 3].map(v => ({ label: String(v), on: s.copies === v,
      pick: () => { d.settings.copies = v; changed(d); } })), false, "Copies"));
    const st = el("div", "stepper");
    const minus = el("button", null, "−");
    minus.type = "button";
    minus.setAttribute("aria-label", "One copy less");
    minus.disabled = s.copies <= 1;
    minus.onclick = () => { d.settings.copies = Math.max(1, s.copies - 1); changed(d); };
    const input = el("input");
    input.type = "number";
    input.inputMode = "numeric";
    input.min = 1;
    input.max = state.limits.maxCopies;
    input.value = s.copies;
    input.setAttribute("aria-label", "Number of copies");
    input.onchange = () => {
      const v = Math.round(Number(input.value));
      d.settings.copies = Math.min(state.limits.maxCopies, Math.max(1, v || 1));
      changed(d);
    };
    const plus = el("button", null, "+");
    plus.type = "button";
    plus.setAttribute("aria-label", "One copy more");
    plus.disabled = s.copies >= state.limits.maxCopies;
    plus.onclick = () => { d.settings.copies = Math.min(state.limits.maxCopies, s.copies + 1); changed(d); };
    st.append(minus, input, plus);
    wrap.append(st);
    f.append(wrap);
    if (s.copies > 1 && n.plan.sheets > 1 && !s.staple && !s.bind) {
      const c = el("div");
      c.style.marginTop = "10px";
      c.append(seg([
        { label: "Collated", sub: "1-2-3, 1-2-3", on: s.collate, pick: () => { d.settings.collate = true; changed(d); } },
        { label: "Uncollated", sub: "1-1, 2-2, 3-3", on: !s.collate, pick: () => { d.settings.collate = false; changed(d); } }
      ], false, "Copy order"));
      f.append(c);
    }
    return f;
  }

  function colorField(d, s) {
    const f = field("Colour");
    const shop = state.shop;
    const st = states("color", [false, true], s);
    if (st.length === 1) {
      f.append(el("p", "field-hint", st[0].value ? "Colour printing only (" + rupees(shop.priceColorPaise) + " per side)."
        : "Black & white only (" + rupees(shop.priceBwPaise) + " per side). Colour printing is not available."));
      return f;
    }
    f.append(seg(st.map(a => ({
      label: a.value ? "Colour" : "Black & white",
      sub: conflictSub(a) || rupees(a.value ? shop.priceColorPaise : shop.priceBwPaise) + " / side",
      on: s.color === a.value, conflict: !a.available,
      pick: () => pick(d, APPLY.color(a.value), a)
    })), false, "Colour"));
    return f;
  }

  function sidesField(d, n) {
    const s = n.settings;
    const f = field("Sides");
    const st = states("duplex", ["ONE_SIDED", "LONG_EDGE", "SHORT_EDGE"], s);
    const words = { ONE_SIDED: ["One-sided", ""], LONG_EDGE: ["Two-sided", "flip on long edge"], SHORT_EDGE: ["Two-sided", "flip on short edge"] };
    // the two two-sided choices keep their edge on show, so they can be told apart even when they need a change
    f.append(seg(st.map(a => ({
      label: words[a.value][0], sub: words[a.value][1] || conflictSub(a), note: words[a.value][1] ? conflictSub(a) : null,
      on: s.duplex === a.value, conflict: !a.available,
      pick: () => pick(d, APPLY.duplex(a.value), a)
    })), false, "Sides"));
    if (s.duplex !== "ONE_SIDED") {
      hint(f, (s.duplex === "LONG_EDGE" ? "Pages turn like a book. " : "Pages flip up like a notepad. ") +
        "Uses " + plural(n.plan.sheets, "sheet", "sheets") + " instead of " + n.plan.sides + " per copy.");
    } else if (n.plan.sides > 1 && st.some(a => a.value !== "ONE_SIDED" && a.available)) {
      hint(f, "Two-sided would use " + plural(Math.ceil(n.plan.sides / 2), "sheet", "sheets") + " instead of " + n.plan.sides + ".");
    }
    return f;
  }

  function paperField(d, s) {
    const f = field("Paper size");
    const sizes = (state.shop.printing.paperSizes || []).map(p => p.id);
    const st = states("paperSize", sizes, s);
    if (st.length === 1) {
      f.append(el("p", "field-hint", paper(st[0].value).label + " (" + dims(paper(st[0].value)) + ")"));
      return f;
    }
    if (st.length <= 6) {
      f.append(seg(st.map(a => ({
        label: shortPaper(a.value), sub: conflictSub(a) || dims(paper(a.value)),
        title: paper(a.value).label, on: s.paperSize === a.value, conflict: !a.available,
        pick: () => pick(d, APPLY.paperSize(a.value), a)
      })), st.length > 3, "Paper size"));
    } else {
      const sel = el("select", "select");
      sel.setAttribute("aria-label", "Paper size");
      st.forEach(a => {
        const o = el("option", null, paper(a.value).label + " – " + dims(paper(a.value)) + (conflictSub(a) ? "  (" + conflictSub(a) + ")" : ""));
        o.value = a.value;
        o.selected = s.paperSize === a.value;
        sel.append(o);
      });
      sel.onchange = () => { const a = st.find(x => x.value === sel.value); pick(d, APPLY.paperSize(a.value), a); };
      f.append(sel);
    }
    const pct = ((state.shop.printing.pricing || {}).paperSizePercent || {})[s.paperSize];
    if (pct != null && pct !== 100) hint(f, paper(s.paperSize).id + " costs " + (pct === 200 ? "double" : pct + " %") + " per side.");
    return f;
  }

  function shortPaper(id) {
    return id.replace("PHOTO_", "").replace("X", "×").replace("LETTER", "Letter").replace("LEGAL", "Legal")
      .replace("FOLIO", "Folio").replace("TABLOID", "Tabloid").replace("EXECUTIVE", "Exec.").replace("STATEMENT", "Stmt.");
  }
  function dims(p) {
    const r = (v) => (Math.round(v * 10) / 10).toString();
    return r(p.widthMm) + " × " + r(p.heightMm) + " mm";
  }

  // ------------------------------------------------------------------ layout (PDF) and picture

  const MARGINS = [[5, "Narrow", "5 mm"], [10, "Normal", "10 mm"], [20, "Wide", "20 mm"]];

  function marginsField(d, s) {
    const f = field("Margins");
    let bl = states("borderless", [true], s)[0];
    if (!bl && s.marginMm === 0) bl = { value: true, supported: false, available: false, gone: true };
    const items = [];
    if (bl) {
      items.push({ label: "None", sub: conflictSub(bl) || "borderless", on: s.marginMm === 0, conflict: !bl.available || bl.gone,
                   pick: () => pick(d, APPLY.borderless(true), bl) });
    }
    const preset = MARGINS.some(m => m[0] === s.marginMm) || s.marginMm === 0;
    MARGINS.forEach(([mm, label, sub]) => items.push({ label, sub, on: s.marginMm === mm,
      pick: () => { d.settings.marginMm = mm; changed(d); } }));
    items.push({ label: "Custom", sub: preset ? "mm" : s.marginMm + " mm", on: !preset,
                 pick: () => { d.settings.marginMm = 15; changed(d); } });
    f.append(seg(items, true, "Margins"));
    if (!preset) {
      const row = el("div", "row");
      row.style.marginTop = "8px";
      row.append(numberInput(s.marginMm, 1, 40, 1, (v) => { d.settings.marginMm = v; changed(d); }, "Margin in millimetres"),
        el("span", "muted small", "mm on every side"));
      f.append(row);
    }
    if (s.marginMm === 0) hint(f, "Printed right to the edge of the paper.");
    else if (s.marginMm < 5) hint(f, "Most printers cannot print the outer 3–5 mm of the paper.");
    return f;
  }

  function orientationField(d, s) {
    const f = field("Orientation");
    const auto = d.type === "PDF" ? (s.pagesPerSheet > 1 ? "best fit" : "follows each page") : "follows the picture";
    f.append(seg([["AUTO", "Auto", auto], ["PORTRAIT", "Portrait", null], ["LANDSCAPE", "Landscape", null]].map(o => ({
      label: o[1], sub: o[2], on: s.orientation === o[0], pick: () => { d.settings.orientation = o[0]; changed(d); }
    })), false, "Orientation"));
    return f;
  }

  function scalingField(d, s, pic) {
    const f = field(pic ? "Size on the paper" : "Scale");
    const opts = pic
      ? [["FIT", "Fit", "whole picture"], ["FILL", "Fill", "trim edges"], ["ACTUAL", "Actual", "100 %"], ["CUSTOM", "Custom", "%"]]
      : [["FIT", "Fit to page", null], ["ACTUAL", "Actual size", "100 %"], ["CUSTOM", "Custom", "%"]];
    f.append(seg(opts.map(o => ({ label: o[1], sub: o[0] === "CUSTOM" && s.scaling === "CUSTOM" ? s.scalePercent + " %" : o[2],
      on: s.scaling === o[0], pick: () => {
        d.settings.scaling = o[0];
        if (o[0] === "CUSTOM" && !(d.settings.scalePercent > 0 && d.settings.scalePercent !== 100)) d.settings.scalePercent = 90;
        changed(d);
      } })), pic, "Scale"));
    if (s.scaling === "CUSTOM") {
      const row = el("div", "row");
      row.style.marginTop = "8px";
      row.append(numberInput(s.scalePercent, 10, 400, 1, (v) => { d.settings.scalePercent = v; changed(d); }, "Scale in percent"),
        el("span", "muted small", "% of actual size (10–400)"));
      f.append(row);
    }
    if (pic) {
      const ns = el("p", "never-stretch");
      ns.append(icon("check"), "Proportions are always kept: never stretched.");
      f.append(ns);
      if (s.scaling === "ACTUAL") {
        const info = d.image || d.localImage;
        if (info) hint(f, "Actual size uses the picture's own resolution (" + Math.round(info.dpi || 96) + " dpi).");
      }
    } else if (s.scaling === "ACTUAL") {
      hint(f, "Pages print at their real size; anything outside the paper is cut off.");
    }
    return f;
  }

  function layoutGroup(d, n) {
    const s = n.settings;
    const parts = [];
    if (s.pagesPerSheet > 1) parts.push(s.pagesPerSheet + " per sheet");
    parts.push(s.orientation === "AUTO" ? "Auto" : s.orientation === "PORTRAIT" ? "Portrait" : "Landscape");
    if (s.pagesPerSheet === 1) parts.push(s.scaling === "FIT" ? "Fit" : s.scaling === "ACTUAL" ? "Actual size" : s.scalePercent + " %");
    parts.push(s.marginMm === 0 ? "borderless" : s.marginMm + " mm");
    const g = group("Layout", parts.join(" · "), false);
    if (n.plan.printPages > 1) {
      const f = field("Pages per sheet");
      f.append(seg(C.PAGES_PER_SHEET.map(v => ({ label: String(v), on: s.pagesPerSheet === v,
        pick: () => { d.settings.pagesPerSheet = v; changed(d); } })), false, "Pages per sheet"));
      if (s.pagesPerSheet > 1) hint(f, plural(n.plan.printPages, "page fits", "pages fit") + " on " + plural(n.plan.sides, "side", "sides") + ". Pages are shrunk to fit their space.");
      g.append(f);
    }
    g.append(orientationField(d, s));
    if (s.pagesPerSheet === 1) g.append(scalingField(d, s, false));
    g.append(marginsField(d, s));
    return g;
  }

  function pictureGroup(d, n) {
    const s = n.settings;
    const turn = s.rotation || 0;
    const parts = [s.scaling === "FIT" ? "Fit" : s.scaling === "FILL" ? "Fill" : s.scaling === "ACTUAL" ? "Actual size" : s.scalePercent + " %"];
    if (turn) parts.push("turned " + turn + "°");
    parts.push(s.marginMm === 0 ? "borderless" : s.marginMm + " mm");
    const g = group("Picture", parts.join(" · "), true);
    g.append(scalingField(d, s, true));
    const f = field("Turn", turn ? "turned " + turn + "°" : "upright");
    const row = el("div", "rotate");
    const l = el("button", "btn ghost sm");
    l.type = "button";
    l.append(icon("rotl"), "Turn left");
    l.onclick = () => { d.settings.rotation = ((turn - 90) % 360 + 360) % 360; changed(d); };
    const r = el("button", "btn ghost sm");
    r.type = "button";
    r.append("Turn right", icon("rotr"));
    r.onclick = () => { d.settings.rotation = (turn + 90) % 360; changed(d); };
    row.append(l, r);
    f.append(row);
    g.append(f);
    g.append(orientationField(d, s));
    if (s.scaling === "ACTUAL" || s.scaling === "CUSTOM") {
      const p = field("Position");
      p.append(seg([{ label: "Centred", on: s.center, pick: () => { d.settings.center = true; changed(d); } },
                    { label: "Top-left", on: !s.center, pick: () => { d.settings.center = false; changed(d); } }], false, "Position"));
      g.append(p);
    }
    g.append(marginsField(d, s));
    return g;
  }

  // ------------------------------------------------------------------ finishing, paper type, quality

  function finishingGroup(d, n) {
    const s = n.settings;
    const parts = [];
    if (s.staple) parts.push("stapled");
    if (s.punch) parts.push("punched");
    if (s.bind) parts.push("bound");
    const g = group("Finishing", parts.length ? parts.join(", ") : "none", !!parts.length);
    const words = { STAPLE: ["Staple", "staple"], PUNCH: ["Hole punch", "punch"], BIND: ["Binding", "bind"] };
    for (const grp of C.FINISHING_GROUPS) {
      const key = words[grp][1];
      const positions = C.finishingPositions(state.offered.finishing, grp);
      if (s[key] && positions.indexOf(s[key]) < 0) positions.push(s[key]);
      if (!positions.length) continue;
      const st = states(key, [null].concat(positions), s);
      const f = field(words[grp][0]);
      const sel = el("select", "select");
      sel.setAttribute("aria-label", words[grp][0]);
      st.forEach(a => {
        const label = a.value == null ? "None" : finishingLabel(grp + "_" + a.value).replace(/^[^:]*:\s*/, "");
        const o = el("option", null, label.charAt(0).toUpperCase() + label.slice(1) + (conflictSub(a) ? "  (" + conflictSub(a) + ")" : ""));
        o.value = a.value == null ? "" : a.value;
        o.selected = (s[key] || "") === o.value;
        sel.append(o);
      });
      sel.onchange = () => {
        const v = sel.value || null;
        const a = st.find(x => x.value === v);
        pick(d, APPLY[key](v), a);
      };
      f.append(sel);
      if ((grp === "STAPLE" || grp === "BIND") && n.plan.sheets < 2) hint(f, "Needs at least 2 sheets of paper.");
      const fp = ((state.shop.printing.pricing || {}).finishingPaise || {})[grp];
      if (fp > 0 && s[key]) hint(f, rupees(fp) + " per copy.");
      g.append(f);
    }
    return g;
  }

  function paperQualityGroup(d, s) {
    const parts = [s.mediaType ? mediaLabel(s.mediaType) : "usual paper"];
    if (s.quality === "HIGH") parts.push("high quality");
    const g = group("Paper type & quality", parts.join(" · "), !!s.mediaType || s.quality === "HIGH");
    if (state.offered.mediaTypes.length || s.mediaType) {
      const st = states("mediaType", [null].concat(state.offered.mediaTypes), s);
      const f = field("Paper type");
      const sel = el("select", "select");
      sel.setAttribute("aria-label", "Paper type");
      st.forEach(a => {
        const o = el("option", null, (a.value == null ? "Usual paper" : mediaLabel(a.value)) + (conflictSub(a) ? "  (" + conflictSub(a) + ")" : ""));
        o.value = a.value == null ? "" : a.value;
        o.selected = (s.mediaType || "") === o.value;
        sel.append(o);
      });
      sel.onchange = () => {
        const v = sel.value || null;
        pick(d, APPLY.mediaType(v), st.find(x => x.value === v));
      };
      f.append(sel);
      const pct = s.mediaType ? ((state.shop.printing.pricing || {}).mediaTypePercent || {})[s.mediaType] : null;
      if (pct != null && pct !== 100) hint(f, "This paper costs " + pct + " % of the normal price per side.");
      g.append(f);
    }
    if (state.offered.highQuality || s.quality === "HIGH") {
      const st = states("quality", ["STANDARD", "HIGH"], s);
      if (st.length > 1) {
        const f = field("Print quality");
        f.append(seg(st.map(a => ({ label: a.value === "HIGH" ? "High" : "Standard", sub: conflictSub(a),
          on: s.quality === a.value, conflict: !a.available, pick: () => pick(d, APPLY.quality(a.value), a) })), false, "Print quality"));
        g.append(f);
      }
    }
    return g;
  }

  // ------------------------------------------------------------------ price of this file

  function priceBlock(d, n) {
    const wrap = el("div");
    const p = n.error ? null : docPrice(d, n);
    const box = el("div", "doc-price");
    const left = el("div");
    left.append(el("span", "field-label", "This file"));
    if (p) {
      const s = n.settings;
      const words = (d.type === "PDF" ? plural(n.plan.printPages, "page", "pages") + " → " : "") +
        plural(n.plan.sheets, "sheet", "sheets") + (s.copies > 1 ? " × " + s.copies + " copies" : "") +
        " · " + rupees(p.perSide) + " per side" + (p.finishing ? " + " + rupees(p.finishing) + " finishing" : "");
      left.append(el("small", null, words));
    } else {
      left.append(el("small", null, n.error ? "Fix the choice above to see the price." : ""));
    }
    box.append(left, el("b", null, p ? rupees(p.amount) : "–"));
    wrap.append(box);
    if (state.docs.filter(x => x !== d && x.settings).length) {
      const b = el("button", "btn ghost sm apply-all");
      b.type = "button";
      b.append(icon("copy"), "Use these settings for all files");
      b.onclick = () => applyToAll(d);
      wrap.append(b);
    }
    return wrap;
  }

  /** Copies this file's choices (not its pages or turn) to every other file. */
  function applyToAll(from) {
    const s = norm(from).settings;
    let n = 0;
    for (const d of state.docs) {
      if (d === from || !d.settings) continue;
      const pic = d.type !== "PDF";
      Object.assign(d.settings, {
        copies: s.copies, color: s.color, duplex: s.duplex, paperSize: s.paperSize, orientation: s.orientation,
        marginMm: s.marginMm, collate: s.collate, staple: s.staple, punch: s.punch, bind: s.bind,
        mediaType: s.mediaType, quality: s.quality
      });
      if (!pic) d.settings.pagesPerSheet = s.pagesPerSheet;
      if (s.scaling !== "FILL" || pic) { d.settings.scaling = s.scaling; d.settings.scalePercent = s.scalePercent; }
      n++;
    }
    toast("Settings copied to " + plural(n, "other file", "other files") + ".", "ok");
    changed(from);
  }

  function setTab(tab) {
    state.pvTab = tab;
    renderEditor();
  }

  // ================================================================== the bar at the bottom: total and "Review order"

  function renderCheckout() {
    const docs = state.docs;
    let total = 0, sheets = 0;
    const busy = docs.filter(busyStatus);
    const bad = docs.filter(d => d.status === "error" || d.status === "cancelled");
    const invalid = [];
    for (const d of docs) {
      const n = (d.status === "ready" || busyStatus(d)) ? norm(d) : null;
      if (!n) continue;
      if (n.error || d.serverError) { invalid.push(d); continue; }
      total += docPrice(d, n).amount;
      sheets += n.plan.sheets * n.settings.copies;
    }
    let why = "";
    if (!docs.length) why = "Add a file to print.";
    else if (busy.length) why = "Wait until every file is uploaded.";
    else if (bad.length) why = (bad.length === 1 ? "One file needs" : bad.length + " files need") + " attention: try again or remove.";
    else if (invalid.length) why = (invalid.length === 1 ? "One file needs" : invalid.length + " files need") + " a change.";
    $("cbTotal").textContent = docs.length ? rupees(total) : "–";
    $("cbDetail").textContent = docs.length ? plural(docs.length, "file", "files") + " · " + plural(sheets, "sheet", "sheets") +
      (busy.length ? " · uploading…" : "") : "";
    // tapping the reason opens the first file that needs something
    const needs = bad[0] || invalid[0];
    $("cbWhy").textContent = why;
    $("cbWhy").disabled = !needs || !!busy.length;
    $("cbWhy").onclick = () => { if (needs && state.docs.includes(needs)) showProblem(needs); };
    $("reviewBtn").disabled = !!why || state.reviewing;
    $("reviewBtn").textContent = state.reviewing ? "Checking…" : "Review order";
  }

  // ================================================================== review: the server checks and prices everything

  async function review() {
    if ($("reviewBtn").disabled) return;
    const o = state.draft;
    if (!o) return;
    state.reviewing = true;
    renderCheckout();
    showError(null);
    try {
      const body = { documents: state.docs.map(d => ({ id: d.id, settings: norm(d).settings })) };
      const v = await api("POST", "/api/v1/orders/" + o.orderId + "/review", body, o.key);
      takeServerSettings(v);
      remember({ orderId: o.orderId, key: o.key, code: o.code, fileName: orderName(v), at: Date.now() });
      if (v.status !== "AWAITING_PAYMENT") {         // nothing to pay (free shop)
        clearDraft(false);
        return openStatus(recent()[0], v, true);
      }
      showReview(v);
    } catch (e) {
      if (e.code === "CHECK_SETTINGS" && e.data && e.data.documents) {
        let first = null;
        for (const p of e.data.documents) {
          const d = state.docs.find(x => x.id === p.id);
          if (!d) continue;
          d.serverError = p.message;
          if (!first) first = d;
        }
        renderQueue();
        if (first) showProblem(first);
        toast(e.message, "bad");
        loadShop();                    // the printers may have changed since the page was opened
      } else if (e.code === "PRICED") {
        await api("POST", "/api/v1/orders/" + o.orderId + "/edit", null, o.key).catch(() => {});
        toast("Please press Review order once more.", "warn");
      } else {
        toast(e.message, "bad");
      }
    } finally {
      state.reviewing = false;
      renderCheckout();
    }
  }

  /** The server's tidy settings are the truth from now on. */
  function takeServerSettings(v) {
    state.order = v;
    for (const sd of v.documents || []) {
      const d = state.docs.find(x => x.id === sd.id);
      if (d && sd.settings) {
        d.settings = Object.assign({}, sd.settings);
        d.pagesDraft = null;
      }
    }
    saveDraft();
  }

  function orderName(v) {
    const docs = (v.documents || []).filter(d => d.status !== "CANCELLED");
    if (!docs.length) return v.fileName || "Order";
    return docs[0].fileName + (docs.length > 1 ? " + " + (docs.length - 1) + " more" : "");
  }

  function settingRows(sd) {
    const s = sd.settings;
    const pic = sd.fileType !== "PDF";
    const rows = [];
    if (!s) return rows;
    rows.push(["Pages", pic ? "Picture" : (s.pages ? "Selected: " + sd.pagesText + " · " + plural(sd.printPages, "page", "pages") : sd.pagesText)]);
    rows.push(["Copies", s.copies + (s.copies > 1 ? (s.collate ? " · collated" : " · uncollated") : "")]);
    rows.push(["Colour", s.color ? "Colour" : "Black & white"]);
    rows.push(["Sides", s.duplex === "ONE_SIDED" ? "One-sided" : "Two-sided, flip on " + (s.duplex === "LONG_EDGE" ? "long" : "short") + " edge"]);
    rows.push(["Paper", paper(s.paperSize).label + (s.mediaType ? " · " + mediaLabel(s.mediaType) : "")]);
    const layout = [];
    if (!pic && s.pagesPerSheet > 1) layout.push(s.pagesPerSheet + " pages per sheet");
    layout.push(s.orientation === "AUTO" ? "auto orientation" : s.orientation.toLowerCase());
    if (pic) layout.push(s.scaling === "FIT" ? "fit to page" : s.scaling === "FILL" ? "fill page" : s.scaling === "ACTUAL" ? "actual size" : s.scalePercent + " %");
    else if (s.pagesPerSheet === 1) layout.push(s.scaling === "FIT" ? "fit to page" : s.scaling === "ACTUAL" ? "actual size" : s.scalePercent + " %");
    if (pic && s.rotation) layout.push("turned " + s.rotation + "°");
    if (pic && !s.center && (s.scaling === "ACTUAL" || s.scaling === "CUSTOM")) layout.push("top-left");
    layout.push(s.marginMm === 0 ? "borderless" : s.marginMm + " mm margins");
    rows.push(["Layout", layout.join(", ")]);
    const fin = [];
    if (s.staple) fin.push(finishingLabel("STAPLE_" + s.staple));
    if (s.punch) fin.push(finishingLabel("PUNCH_" + s.punch));
    if (s.bind) fin.push(finishingLabel("BIND_" + s.bind));
    if (fin.length) rows.push(["Finishing", fin.join("; ")]);
    if (s.quality === "HIGH") rows.push(["Quality", "High"]);
    rows.push(["Paper used", plural(sd.sheets, "sheet", "sheets") + (s.copies > 1 ? " × " + s.copies + " = " + plural(sd.sheets * s.copies, "sheet", "sheets") : "")]);
    return rows;
  }

  function showReview(v) {
    state.order = v;
    show("review");
    const box = $("rvDocs");
    box.innerHTML = "";
    let pages = 0, sheets = 0, sum = 0;
    for (const sd of v.documents.filter(x => x.status !== "CANCELLED")) {
      const local = state.docs.find(x => x.id === sd.id);
      const c = el("div", "card rv-doc");
      const th = el("div", "qthumb");
      if (local && local.thumb) { const img = el("img"); img.src = local.thumb; img.alt = ""; th.append(img); }
      else th.append(icon(sd.fileType === "PDF" ? "file" : "image"));
      th.append(el("span", "kind", sd.fileType === "JPEG" ? "JPG" : sd.fileType));
      const main = el("div");
      main.style.minWidth = "0";
      main.append(el("h3", null, sd.position + ". " + sd.fileName));
      const dl = el("dl");
      for (const [k, val] of settingRows(sd)) dl.append(el("dt", null, k), el("dd", null, val));
      main.append(dl);
      const price = el("div", "rv-price");
      price.append(el("b", null, rupees(sd.amountPaise)));
      c.append(th, main, price);
      box.append(c);
      pages += (sd.printPages || 0) * (sd.settings ? sd.settings.copies : 1);
      sheets += (sd.sheets || 0) * (sd.settings ? sd.settings.copies : 1);
      sum += sd.amountPaise || 0;
    }
    const lines = $("rvLines");
    lines.innerHTML = "";
    const line = (a, b) => { const r = el("div"); r.append(el("span", null, a), el("span", null, b)); lines.append(r); };
    line("Files", String(v.documents.filter(x => x.status !== "CANCELLED").length));
    line("Pages printed", String(pages));
    line("Sheets of paper", String(sheets));
    if (v.amountPaise > sum) line("Minimum online payment", rupees(v.amountPaise - sum));
    $("rvTotal").textContent = rupees(v.amountPaise);
    $("rvNote").textContent = v.amountPaise > sum ? "Online payments start at ₹1." : "";
    const demo = state.shop && state.shop.paymentMode === "demo";
    $("demoNote").classList.toggle("hidden", !demo);
    $("payBtn").textContent = (demo ? "Pay (test) " : "Pay ") + rupees(v.amountPaise);
    $("payBtn").disabled = false;
    $("payError").classList.add("hidden");
    $("editBtn").classList.toggle("hidden", !v.editable);
    const s = state.shop;
    const online = s && (s.bwOnline || s.colorOnline);
    $("offlineNote").textContent = "The printers are offline at the moment. You can still order: it prints as soon as they are back.";
    $("offlineNote").classList.toggle("hidden", !!online);
  }

  async function editOrder() {
    const o = state.draft || (state.order && recent().find(x => x.orderId === state.order.orderId));
    if (!o) return;
    try {
      await api("POST", "/api/v1/orders/" + o.orderId + "/edit", null, o.key);
      if (!state.draft) state.draft = { orderId: o.orderId, key: o.key, code: o.code };
      if (!state.docs.length) await restoreDraft(true);
      show("setup");
      renderQueue();
      renderEditor();
      renderCheckout();
    } catch (e) {
      toast(e.message, "bad");
    }
  }

  // ================================================================== payment

  function payError(msg) {
    $("payError").textContent = msg;
    $("payError").classList.remove("hidden");
    $("payBtn").disabled = false;
  }

  function currentOrderRef() {
    const v = state.order;
    return v && (recent().find(x => x.orderId === v.orderId) ||
      (state.draft && state.draft.orderId === v.orderId ? Object.assign({ fileName: orderName(v) }, state.draft) : null));
  }

  async function pay() {
    const o = currentOrderRef();
    if (!o) return;
    $("payBtn").disabled = true;
    $("payError").classList.add("hidden");
    try {
      const c = await api("POST", "/api/v1/orders/" + o.orderId + "/payment", null, o.key);
      $("editBtn").classList.add("hidden");            // payment started: the order is fixed now
      if (c.provider === "demo") {
        const v = await api("POST", "/api/v1/orders/" + o.orderId + "/payment/demo", null, o.key);
        clearDraft(false);
        return openStatus(o, v, true);
      }
      await loadScript(RAZORPAY_JS);
      const rzp = new window.Razorpay({
        key: c.keyId, amount: c.amountPaise, currency: c.currency,
        name: state.shop ? state.shop.centerName : "Campus Print",
        description: c.description, order_id: c.gatewayOrderId, theme: { color: "#14263B" },
        handler: async (r) => {
          clearDraft(false);
          try {
            const v = await api("POST", "/api/v1/orders/" + o.orderId + "/payment/confirm",
              { paymentId: r.razorpay_payment_id, signature: r.razorpay_signature }, o.key);
            openStatus(o, v, true);
          } catch (e) {
            // Money may have been taken: never show the Pay button again here. The server re-checks by itself.
            openStatus(o, null, true);
            showError(e.message);
          }
        },
        modal: { ondismiss: () => { $("payBtn").disabled = false; } }
      });
      rzp.on("payment.failed", (resp) => {
        payError("Payment failed: " + ((resp && resp.error && resp.error.description) || "please try again") +
          ". No money was taken for this attempt.");
      });
      rzp.open();
    } catch (e) {
      payError(e.message);
    }
  }

  async function cancelOrder() {
    const o = currentOrderRef();
    if (!o || !confirm("Cancel this order? Your files will be deleted.")) return;
    try { await api("POST", "/api/v1/orders/" + o.orderId + "/cancel", null, o.key); } catch (e) { /* ignore */ }
    forget(o.orderId);
    clearDraft(true);
    resetToStart();
  }

  // ================================================================== status and pickup code

  function lampFor(v) {
    if (v.status === "COMPLETED") return "ready";
    if (["FAILED", "EXPIRED", "CANCELLED"].includes(v.status)) return "stop";
    return "work";
  }
  function docLamp(sd) {
    if (sd.status === "COMPLETED") return "ready";
    if (sd.status === "FAILED" || sd.status === "CANCELLED" || sd.status === "REJECTED") return "stop";
    return "work";
  }

  function renderStatus(v) {
    watchReady(v);
    $("tStage").dataset.status = v.status;
    $("tCode").textContent = v.pickupCode;
    $("tLamp").className = "lamp " + lampFor(v);
    $("tStage").textContent = v.stage;
    $("tMessage").textContent = v.message || "";
    const paid = !!v.paidAt;
    const printing = ["PRINTING", "COMPLETED"].includes(v.status);
    const steps = [["Paid", paid], ["Printing", v.status === "COMPLETED"], ["Ready at the counter", v.status === "COMPLETED"]];
    const nowIndex = steps.findIndex(s => !s[1]);
    const ul = $("track");
    ul.innerHTML = "";
    if (paid && v.status !== "CANCELLED") {
      steps.forEach((s, i) => {
        const li = el("li", s[1] ? "done" : (i === nowIndex ? "now" : ""));
        li.append(el("span", "lamp " + (s[1] ? "ready" : (i === nowIndex && (i !== 1 || printing || v.status === "QUEUED") ? "work" : ""))),
          el("span", null, i === 1 && !s[1] && v.status === "QUEUED" ? "Waiting for a printer" : s[0]));
        ul.appendChild(li);
      });
    }
    const docs = $("tDocs");
    docs.innerHTML = "";
    const list = (v.documents || []);
    if (list.length) {
      for (const sd of list) {
        const li = el("li");
        const text = el("div");
        text.append(el("b", null, sd.position + ". " + sd.fileName),
          el("span", null, sd.stage + (sd.settings ? " · " + (sd.settings.color ? "colour" : "B/W") +
            " · " + plural((sd.sheets || 0) * sd.settings.copies, "sheet", "sheets") : "")));
        li.append(el("span", "lamp " + docLamp(sd)), text);
        docs.append(li);
      }
    }
    const sheets = v.totalSheets;
    $("tDetails").textContent = plural(list.filter(x => x.status !== "CANCELLED").length || 1, "file", "files") +
      (sheets ? " · " + plural(sheets, "sheet", "sheets") : "") + (v.amountPaise != null ? " · " + rupees(v.amountPaise) : "");
  }

  /** afterPayment: the student has just paid (or tried to): never offer the Pay button again from here. */
  async function openStatus(o, first, afterPayment) {
    clearInterval(state.poll);
    show("status");
    location.hash = "order=" + o.orderId;
    let firstTick = true;
    const tick = async () => {
      try {
        const v = await api("GET", "/api/v1/orders/" + o.orderId, null, o.key);
        if (v.status === "AWAITING_PAYMENT" && firstTick && !afterPayment) {
          clearInterval(state.poll);
          state.order = v;
          return showReview(v);
        }
        if (v.status === "AWAITING_UPLOAD" && firstTick) {
          clearInterval(state.poll);
          if (await restoreDraft(true)) return;
        }
        firstTick = false;
        renderStatus(v);
        if (v.status === "AWAITING_PAYMENT") $("tMessage").textContent = "Checking your payment with the bank. This can take a minute.";
        if (FINAL.includes(v.status) && v.status !== "COMPLETED") clearInterval(state.poll);
        if (v.status === "COMPLETED" && v.collected) clearInterval(state.poll);
      } catch (e) {
        if (e.status === 404) { clearInterval(state.poll); forget(o.orderId); showError("This order no longer exists."); }
      }
    };
    if (first) renderStatus(first);
    else $("tCode").textContent = o.code;
    await tick();
    const st = $("tStage").dataset.status || "";
    if (state.step === "status" && !(FINAL.includes(st) && st !== "COMPLETED")) state.poll = setInterval(tick, 3000);
  }

  function resetToStart() {
    clearInterval(state.poll);
    document.title = BASE_TITLE;
    $("notifyNote").textContent = "";
    state.order = null;
    history.replaceState(null, "", location.pathname);
    show("choose");
    renderRecent();
    loadShop();
  }

  // ================================================================== keeping a draft across a page reload

  function saveDraft() {
    if (!state.draft) return;
    storageSet(DRAFT, Object.assign({}, state.draft, {
      at: Date.now(),
      docs: state.docs.filter(d => d.id).map(d => ({ id: d.id, settings: d.settings }))
    }));
  }
  let saveTimer = null;
  function saveDraftSoon() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(saveDraft, 400);
  }
  function clearDraft(dropFiles) {
    storageSet(DRAFT, null);
    for (const d of state.docs) {
      if (dropFiles) d.removed = true;
      if (d.xhr) d.xhr.abort();
      if (d.imgUrl) URL.revokeObjectURL(d.imgUrl);
      closePdf(d);
    }
    uploads.waiting.length = 0;
    state.draft = null;
    state.docs = [];
    state.selected = null;
    document.body.classList.remove("editing");
  }

  /**
   * Phones often reload a page left in the background (say, to fetch another
   * PDF from WhatsApp). The files already sent are kept on the server: show
   * them again with the settings chosen, and fetch them back for the preview.
   */
  async function restoreDraft(quiet) {
    const saved = storageGet(DRAFT, null);
    if (!saved || !saved.orderId) return false;
    let v;
    try {
      v = await api("GET", "/api/v1/orders/" + saved.orderId, null, saved.key);
    } catch (e) {
      if (e.status === 404) storageSet(DRAFT, null);
      return false;
    }
    if (!(v.status === "AWAITING_UPLOAD" || (v.status === "AWAITING_PAYMENT" && v.editable))) {
      storageSet(DRAFT, null);
      return false;
    }
    state.draft = { orderId: saved.orderId, key: saved.key, code: saved.code };
    state.docs = [];
    // in the student's order (they may have moved files since the server last heard)
    const rank = (id) => { const k = (saved.docs || []).findIndex(x => x.id === id); return k < 0 ? 1e6 : k; };
    const docs = v.documents.slice().sort((a, b) => rank(a.id) - rank(b.id) || a.position - b.position);
    for (const sd of docs) {
      const mine = (saved.docs || []).find(x => x.id === sd.id);
      const d = newDoc(new File([], sd.fileName));
      d.file = null;
      d.id = sd.id;
      d.name = sd.fileName;
      d.size = sd.sizeBytes || 0;
      d.type = sd.fileType;
      d.pageCount = sd.pageCount;
      d.image = sd.image;
      d.settings = Object.assign(C.defaults(sd.fileType !== "PDF"), (mine && mine.settings) || sd.settings || {});
      if (sd.status === "READY") {
        d.status = "ready";
      } else if (sd.status === "REJECTED") {
        d.status = "error"; d.error = sd.problem || "This file cannot be printed."; d.retry = false;
      } else {
        d.status = "error"; d.error = "The upload was interrupted. Remove it and add the file again."; d.retry = false;
      }
      state.docs.push(d);
    }
    if (v.status === "AWAITING_PAYMENT") {
      state.order = v;
      showReview(v);
    } else {
      show("setup");
      select(state.docs[0] || null, false);
    }
    renderQueue();
    renderCheckout();
    if (!quiet && state.docs.length) toast("Your files from before are still here.", "ok");
    // pictures of the first pages, a couple at a time (this downloads the files again)
    state.docs.filter(d => d.status === "ready" && d.size < 15 * 1048576).forEach(d => readSlot(async () => {
      if (d.removed) return;
      if (d.type === "PDF") d.thumb = await pageThumb(d, 1, 120);
      else await loadImg(d);
      renderQueue();
      if (state.step === "review" && state.order) showReview(state.order);
    }).catch(() => {}));
    return true;
  }

  // ================================================================== ready alerts, sharing, installing

  const BASE_TITLE = document.title;
  const alerts = { on: false, last: {} };
  function watchReady(v) {
    const before = alerts.last[v.orderId];
    alerts.last[v.orderId] = v.status;
    const ready = v.status === "COMPLETED" && !v.collected;
    document.title = ready ? "✅ Ready · " + v.pickupCode + " — Campus Print" : BASE_TITLE;
    const canAsk = "Notification" in window && !FINAL.includes(v.status) && Notification.permission !== "denied";
    $("notifyBtn").classList.toggle("hidden", !canAsk || alerts.on);
    if (ready && before && before !== "COMPLETED") {
      if (navigator.vibrate) navigator.vibrate([200, 100, 200]);
      if (alerts.on) notifyReady(v);
    }
  }
  function notifyReady(v) {
    const title = "Your prints are ready";
    const opts = { body: "Show code " + v.pickupCode + " at the counter.", icon: "icon-192.png", tag: v.orderId };
    if (navigator.serviceWorker && navigator.serviceWorker.controller) {
      navigator.serviceWorker.ready.then(r => r.showNotification(title, opts)).catch(() => {});
    } else {
      try { new Notification(title, opts); } catch (e) { /* not allowed here */ }
    }
  }
  $("notifyBtn").onclick = async () => {
    let p = "denied";
    try { p = await Notification.requestPermission(); } catch (e) { /* old browser */ }
    alerts.on = p === "granted";
    $("notifyBtn").classList.add("hidden");
    $("notifyNote").textContent = alerts.on
      ? "Done. This device will alert you when your prints are ready (keep this page open in the background)."
      : "Notifications are off for this site. Keep this page open to see when it is ready.";
  };
  $("shareBtn").onclick = async () => {
    const url = location.origin + location.pathname.replace(/index\.html$/, "");
    const data = { title: "Campus Print", text: "Print from your phone and skip the Xerox queue:", url };
    try {
      if (navigator.share) { await navigator.share(data); return; }
      await navigator.clipboard.writeText(url);
      toast("Link copied", "ok");
    } catch (e) { /* closed the share sheet */ }
  };
  let installPrompt = null;
  window.addEventListener("beforeinstallprompt", (e) => {
    e.preventDefault();
    installPrompt = e;
    $("installBtn").classList.remove("hidden");
  });
  $("installBtn").onclick = async () => {
    if (!installPrompt) return;
    installPrompt.prompt();
    try { await installPrompt.userChoice; } catch (e) { /* ignore */ }
    installPrompt = null;
    $("installBtn").classList.add("hidden");
  };
  window.addEventListener("appinstalled", () => $("installBtn").classList.add("hidden"));
  if ("serviceWorker" in navigator && /^https?:$/.test(location.protocol)) {
    navigator.serviceWorker.register("sw.js").catch(() => { /* works without it */ });
  }

  // ================================================================== wiring

  ["fileInput", "addInput", "addInput2"].forEach(id => {
    $(id).addEventListener("change", (e) => {
      const files = Array.from(e.target.files || []);
      e.target.value = "";
      addFiles(files);
    });
  });

  // Drop files anywhere on the page (the landing page and the workspace).
  let dragDepth = 0;
  const hasFiles = (e) => e.dataTransfer && Array.from(e.dataTransfer.types || []).includes("Files");
  const canDrop = () => state.step === "choose" || state.step === "setup";
  window.addEventListener("dragenter", (e) => {
    if (!hasFiles(e) || !canDrop()) return;
    e.preventDefault();
    dragDepth++;
    $("dropOverlay").classList.remove("hidden");
    $("drop").classList.add("over");
  });
  window.addEventListener("dragover", (e) => { if (hasFiles(e) && canDrop()) e.preventDefault(); });
  window.addEventListener("dragleave", (e) => {
    if (!hasFiles(e)) return;
    dragDepth = Math.max(0, dragDepth - 1);
    if (!dragDepth) { $("dropOverlay").classList.add("hidden"); $("drop").classList.remove("over"); }
  });
  window.addEventListener("drop", (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    dragDepth = 0;
    $("dropOverlay").classList.add("hidden");
    $("drop").classList.remove("over");
    if (canDrop()) addFiles(e.dataTransfer.files);
  });
  // Paste screenshots or copied files.
  window.addEventListener("paste", (e) => {
    const files = e.clipboardData && Array.from(e.clipboardData.files || []);
    if (!files || !files.length || !canDrop()) return;
    if (document.activeElement && /INPUT|TEXTAREA/.test(document.activeElement.tagName) && document.activeElement.type !== "file") return;
    e.preventDefault();
    addFiles(files.map((f, i) => f.name && f.name !== "image.png" ? f
      : new File([f], "Pasted picture " + (i + 1) + (f.type === "image/jpeg" ? ".jpg" : ".png"), { type: f.type })));
  });

  $("tabPreview").onclick = () => setTab("preview");
  $("tabPages").onclick = () => setTab("pages");
  $("sheetPrev").onclick = () => { const d = state.selected; if (d) { d.sheet--; renderPreview(); } };
  $("sheetNext").onclick = () => { const d = state.selected; if (d) { d.sheet++; renderPreview(); } };
  $("edPrev").onclick = () => { const i = state.docs.indexOf(state.selected); if (i > 0) select(state.docs[i - 1], false); };
  $("edNext").onclick = () => { const i = state.docs.indexOf(state.selected); if (i < state.docs.length - 1) select(state.docs[i + 1], false); };
  $("edClose").onclick = closeEditor;
  $("edDone").onclick = closeEditor;
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && document.body.classList.contains("editing")) closeEditor();
  });
  phone.addEventListener("change", () => { if (!phone.matches) document.body.classList.remove("editing"); schedulePreview(); });
  if ("ResizeObserver" in window) new ResizeObserver(() => schedulePreview()).observe($("pvStage"));
  setInterval(() => { if (state.step === "setup" && !document.hidden) loadShop(); }, 60000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden && state.step === "setup") loadShop(); });
  $("reviewBtn").onclick = review;
  $("editBtn").onclick = editOrder;
  $("payBtn").onclick = pay;
  $("cancelBtn").onclick = cancelOrder;
  $("newBtn").onclick = resetToStart;
  $("year").textContent = new Date().getFullYear();

  (async function start() {
    if (!API) { showError("config.js has no apiBase. Open config.js and set it."); return; }
    await loadShop();
    const m = location.hash.match(/order=([0-9a-f-]{36})/);
    const saved = m && recent().find(o => o.orderId === m[1]);
    if (saved) { openStatus(saved); return; }
    if (await restoreDraft(false)) return;
    renderRecent();
  })();
})();
