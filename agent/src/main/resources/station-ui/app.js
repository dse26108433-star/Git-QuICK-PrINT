"use strict";
/* XeoGo Station: the screens of the Xerox center app.
 *   /local/...          this PC (printers, test prints, setup)   - LocalServer.java
 *   /api/v1/counter/... the XeoGo server (passed through)  - CounterController.java
 */
const $ = (id) => document.getElementById(id);
const TOKEN = (() => {
  const t = new URL(location.href).searchParams.get("t");
  if (t) { sessionStorage.setItem("station.t", t); history.replaceState(null, "", "/"); }
  return t || sessionStorage.getItem("station.t") || "";
})();
const state = { local: null, summary: null, view: "counter", list: "active", editors: {}, askedPassword: false,
                pasteResult: "", pictures: new Map(), atCounter: "", find: "", staff: null };

/* ------------------------------------------------------------------ helpers */
async function call(method, path, body) {
  const headers = { "X-Station-Token": TOKEN };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (e) {
    throw new Error("XeoGo is not responding. Open it again from its icon.");
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { /* not JSON */ }
  if (!res.ok) {
    const err = new Error((data && data.message) || ("Something went wrong (" + res.status + ")."));
    err.status = res.status;
    throw err;
  }
  return data;
}
const local = (method, path, body) => call(method, "/local/" + path, body);
const server = (method, path, body) => call(method, "/api/v1/counter/" + path, body);

function rupees(paise) {
  if (paise == null) return "–";
  return "₹" + (paise % 100 === 0 ? String(paise / 100) : (paise / 100).toFixed(2));
}
function when(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) +
    (d.toDateString() === new Date().toDateString() ? "" : " · " + d.toLocaleDateString());
}
function plural(n, one, many) { return n + " " + (n === 1 ? one : many); }
function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}
function toast(message, kind) {
  const t = el("div", "toast" + (kind ? " " + kind : ""), message);
  $("toasts").appendChild(t);
  setTimeout(() => t.remove(), 4200);
}
function showError(id, message) {
  $(id).textContent = message || "";
  $(id).classList.toggle("hidden", !message);
}
async function busy(button, label, fn) {
  const old = button.textContent;
  button.disabled = true;
  if (label) button.textContent = label;
  try { return await fn(); } finally { button.disabled = false; button.textContent = old; }
}
/* ------------------------------------------------------------------ start */
async function boot() {
  try {
    state.local = await local("GET", "state");
  } catch (e) {
    $("boot").textContent = e.message;
    return;
  }
  document.querySelectorAll(".ver").forEach(x => { x.textContent = state.local.version; });
  $("boot").classList.add("hidden");
  if (state.local.configured) openApp(); else startSetup();
  setInterval(refreshLocal, 5000);
  setInterval(() => { if (state.view === "counter" && !$("app").classList.contains("hidden")) refreshCounter(); }, 4000);
  setInterval(() => { if (state.view === "staff" && !$("app").classList.contains("hidden")) loadStaff(); }, 20000);
}

/* ------------------------------------------------------------------ setup wizard */
function setupStep(n) {
  ["setupConnect", "setupPrinters", "setupDone"].forEach((id, i) => $(id).classList.toggle("hidden", i + 1 !== n));
  for (let i = 1; i <= 3; i++) {
    $("ss" + i).className = i < n ? "done" : i === n ? "on" : "";
  }
}
function startSetup() {
  $("app").classList.add("hidden");
  $("setup").classList.remove("hidden");
  $("suUrl").value = state.local.backendUrl || state.local.defaultBackendUrl || "";
  $("suName").value = state.local.pcName || "";
  $("suPassword").value = "";
  showError("suError", null);
  setupStep(1);
  ($("suUrl").value ? $("suPassword") : $("suUrl")).focus();
}
$("suConnect").onclick = () => busy($("suConnect"), "Connecting…", async () => {
  showError("suError", null);
  try {
    state.local = await local("POST", "connect", {
      backendUrl: $("suUrl").value, password: $("suPassword").value, pcName: $("suName").value
    });
    setupStep(2);
    await loadPrinterEditor("suPrinterList");
  } catch (e) {
    showError("suError", e.message);
  }
});
["suUrl", "suPassword", "suName"].forEach(id => $(id).addEventListener("keydown", e => {
  if (e.key === "Enter") $("suConnect").click();
}));
$("suSavePrinters").onclick = () => busy($("suSavePrinters"), "Saving…", async () => {
  showError("suPrintersError", null);
  try {
    const used = await savePrinters("suPrinterList");
    if (used === 0) { showError("suPrintersError", "Tick at least one printer, so orders have somewhere to print."); return; }
    state.local = await local("GET", "state");
    $("suAutostartLine").textContent = state.local.autostart
      ? "XeoGo starts together with Windows and keeps printing when this window is closed."
      : "XeoGo keeps printing when this window is closed (the icon next to the clock stays).";
    setupStep(3);
  } catch (e) {
    showError("suPrintersError", e.message);
  }
});
$("suFinish").onclick = () => { $("setup").classList.add("hidden"); openApp(); };

/* ------------------------------------------------------------------ the app */
function openApp() {
  $("setup").classList.add("hidden");
  $("app").classList.remove("hidden");
  show("counter");
  refreshLocal();
}
function show(view) {
  state.view = view;
  ["counter", "printers", "staff", "settings"].forEach(v => $("view-" + v).classList.toggle("hidden", v !== view));
  document.querySelectorAll(".nav button").forEach(b => b.classList.toggle("on", b.dataset.view === view));
  showError("appError", null);
  if (view === "counter") refreshCounter();
  if (view === "printers") loadPrinterEditor("printerEditor");
  if (view === "staff") loadStaff();
  if (view === "settings") fillSettings();
}
document.querySelectorAll(".nav button").forEach(b => { b.onclick = () => show(b.dataset.view); });

/* This PC, in the corner of the sidebar. */
async function refreshLocal() {
  try {
    state.local = await local("GET", "state");
  } catch (e) {
    setPc("stop", "Not responding", e.message);
    return;
  }
  const p = state.local.printing;
  const missing = p.printers.filter(x => !x.found).length;
  if (!state.local.configured) setPc("stop", "Not connected", "Finish the setup to start printing.");
  else if (!p.running) setPc("stop", "Not printing", p.problem || "");
  else if (!p.connected) setPc("work", "Connecting…", p.problem || "Waiting for the XeoGo server.");
  else setPc(p.busy ? "work" : "ready", p.busy ? "Printing an order" : "Ready to print",
    plural(p.printers.length, "printer", "printers") + " on this PC" + (missing ? " · " + missing + " not found" : ""));
  $("navPrinters").classList.toggle("hidden", missing === 0);
  if (state.view === "settings") fillPcInfo();
  renderWord(p.word);
}

/* Word files: can this PC turn them into pages (its Microsoft Word passed the test), or why not. */
function renderWord(w) {
  const el = $("wordState");
  if (!el) return;
  if (!w || (!w.ready && w.note === "Not checked yet")) {
    el.className = "notice work";
    el.textContent = state.local && state.local.configured ? "Checking Microsoft Word on this PC…"
      : "Connect this PC first.";
    return;
  }
  el.className = "notice " + (w.ready ? "ok" : "stop");
  el.textContent = w.ready
    ? "Ready: " + w.note + ". Students can add Word files while this PC is on."
    : "Not here: " + w.note + " Until it works, students are asked to send a PDF instead.";
}
$("wordCheck").onclick = () => busy($("wordCheck"), "Checking…", async () => {
  await local("POST", "word-check").catch(() => {});
  $("wordState").className = "notice work";
  $("wordState").textContent = "Checking Microsoft Word on this PC…";
  await new Promise(r => setTimeout(r, 9000));      // Word needs a moment to start and make its test page
  await refreshLocal();
});
function setPc(lamp, text, detail) {
  $("pcLamp").className = "lamp " + lamp;
  $("pcText").textContent = text;
  $("pcDetail").textContent = detail || "";
}

function handleServerError(e) {
  if (e.status === 401) {
    showError("appError", "The counter password has changed. Type the new one in Settings → Counter password.");
    if (!state.askedPassword) { state.askedPassword = true; show("settings"); $("newPassword").focus(); }
  } else {
    showError("appError", e.message);
  }
}

/* ------------------------------------------------------------------ counter */
async function refreshCounter() {
  try {
    if (state.list === "payments") {
      const [s, p] = await Promise.all([server("GET", "summary"), server("GET", "payments")]);
      state.summary = s;
      renderSummary(s);
      renderAtCounter(s.atCounter || []);
      // Do not redraw while staff paste a bank message.
      const typing = document.activeElement && document.activeElement.id === "pasteSms" && document.activeElement.value;
      if (!typing) renderPayments(p);
      showError("appError", null);
      return;
    }
    const [s, list] = await Promise.all([
      server("GET", "summary"), server("GET", "orders?view=" + state.list)
    ]);
    state.summary = s;
    renderSummary(s);
    renderAtCounter(s.atCounter || []);
    renderOrders(list);
    showError("appError", null);
  } catch (e) {
    handleServerError(e);
  }
}

/* ------------------------------------------------------------------ pictures of the first printed sheets */
/** A small picture of a file's first sheet (this PC made it while printing). Click: large, next to the student's phone. */
function picture(d, big) {
  const b = el("button", "pic" + (big ? " big" : "") + (d.color ? "" : " bw"));
  b.type = "button";
  b.title = d.fileName;
  b.append(d.fileType === "PDF" ? "PDF" : "Photo", el("span", "n", String(d.position)));
  const show = (url) => {
    const img = document.createElement("img");
    img.src = url;
    img.alt = "First sheet of " + d.fileName;
    b.firstChild.replaceWith(img);
    b.classList.add("has");
    b.onclick = () => zoom(url, d);
  };
  if (state.pictures.has(d.id)) {
    show(state.pictures.get(d.id));
  } else if (d.hasPreview) {
    fetch("/api/v1/counter/documents/" + d.id + "/preview", { headers: { "X-Station-Token": TOKEN } })
      .then(r => (r.ok ? r.blob() : null))
      .then(blob => {
        if (!blob || !/^image\//.test(blob.type)) return;
        if (state.pictures.size > 300) {
          for (const u of state.pictures.values()) URL.revokeObjectURL(u);
          state.pictures.clear();
        }
        const url = URL.createObjectURL(blob);
        state.pictures.set(d.id, url);
        if (b.isConnected) show(url);
      }).catch(() => { /* the name and settings are shown */ });
  }
  return b;
}
function zoom(url, d) {
  const z = el("div", "zoom" + (d.color ? "" : " bw"));
  const img = document.createElement("img");
  img.src = url;
  img.alt = d.fileName;
  z.append(img);
  z.onclick = () => z.remove();
  document.body.append(z);
}

/**
 * Students who opened their paid order at the counter ("I'm at the counter"
 * on their phone). Their order comes up here by itself, with its pictures:
 * the same pictures are on their phone. Nobody shows or types a code.
 */
function renderAtCounter(list) {
  const box = $("atCounter");
  const key = JSON.stringify(list.map(o => [o.id, o.status, (o.documents || []).map(d => [d.id, d.status, d.hasPreview])]));
  if (state.atCounter === key) return;                       // nothing changed: do not redraw under the mouse
  const before = (state.atCounter && JSON.parse(state.atCounter).map(x => x[0])) || [];
  const arrived = list.some(o => !before.includes(o.id));
  state.atCounter = key;
  box.innerHTML = "";
  if (!list.length) return;
  const h = el("h2");
  h.append(el("span", "lamp ready"), "At the counter now (" + list.length + ")");
  box.append(h, el("p", "sub", "These students opened their order here. Their phone shows the same pictures: give them those pages, then press Handed over."));
  for (const o of list) box.append(orderRow(o, true));
  if (arrived) {
    box.classList.remove("new");
    void box.offsetWidth;                                    // start the highlight again
    box.classList.add("new");
    chime();
  }
}

/** A short, soft sound when a student arrives, so staff look up. */
let audio = null;
function chime() {
  try {
    audio = audio || new (window.AudioContext || window.webkitAudioContext)();
    const t = audio.currentTime;
    [880, 1175].forEach((hz, i) => {
      const o = audio.createOscillator(), g = audio.createGain();
      o.type = "sine";
      o.frequency.value = hz;
      g.gain.setValueAtTime(0.0001, t + i * 0.16);
      g.gain.exponentialRampToValueAtTime(0.12, t + i * 0.16 + 0.02);
      g.gain.exponentialRampToValueAtTime(0.0001, t + i * 0.16 + 0.3);
      o.connect(g).connect(audio.destination);
      o.start(t + i * 0.16);
      o.stop(t + i * 0.16 + 0.32);
    });
  } catch (e) { /* no sound on this PC: the box still lights up */ }
}

function renderSummary(s) {
  $("sideCenter").textContent = s.centerName;
  $("demoBanner").classList.toggle("hidden", s.paymentMode !== "demo");
  $("nActive").textContent = s.waiting + s.printing;
  $("nReady").textContent = s.ready;
  $("nProblems").textContent = s.problems;
  $("tabProblems").classList.toggle("alert", s.problems > 0);
  $("tabPayments").classList.toggle("hidden", s.paymentMode !== "upi");
  const [vk, vt] = s.paymentMode === "upi" ? verifierLine(s.upi) : ["ok", ""];
  $("verifierBanner").classList.toggle("hidden", !(s.paymentMode === "upi" && vk === "stop"));
  $("verifierBanner").textContent = vt;
  $("nPay").textContent = s.paymentsToCheck || 0;
  $("tabPayments").classList.toggle("alert", (s.paymentsToCheck || 0) > 0);
  if (s.paymentMode !== "upi" && state.list === "payments") state.list = "active";
  $("navReady").textContent = s.ready;
  $("navReady").classList.toggle("hidden", !s.ready);
  const ready = s.printers.filter(p => p.enabled && p.status === "READY").length;
  $("counterSub").textContent = s.centerName + " · " + ready + " of " + plural(s.printers.length, "printer", "printers") +
    " ready" + (s.waiting ? " · " + s.waiting + " waiting" : "");
  setStampUi(s.stampCode);
  $("noPrinterBanner").classList.toggle("hidden", !s.noPrinter);
  $("noPrinterBanner").textContent = s.noPrinter ? plural(s.noPrinter, "paid file waits", "paid files wait") +
    " for a printer that can print it (for example the A3 or colour printer is switched off). " +
    "Switch that printer on, or cancel the file on its order below and refund it." : "";

  const box = $("printerStrip");
  box.innerHTML = "";
  if (!s.printers.length) {
    const e = el("div", "notice work empty-printers", "No printers yet. Open Printers and tick the printers this PC may use.");
    box.appendChild(e);
  }
  for (const p of s.printers) {
    const lamp = !p.enabled ? "" : p.status === "READY" ? "ready" : p.status === "ERROR" ? "work" : "stop";
    const words = !p.enabled ? "Switched off" : p.status === "READY" ? "Ready"
      : p.status === "ERROR" ? "Needs attention" : p.status === "MISSING" ? "Not found in Windows" : "Offline";
    const card = el("div", "pcard" + (p.enabled ? "" : " off"));
    const row = el("div", "row");
    row.append(el("span", "lamp " + lamp), el("span", "name", p.name),
      el("span", "badge", p.supportsColor ? (p.acceptsBw ? "Colour" : "Colour only") : "B/W"));
    const detail = el("div", "detail", words + (p.statusDetail ? " · " + p.statusDetail : ""));
    const take = el("label", "take");
    take.append(el("span", null, "Taking orders"));
    const sw = el("span", "switch");
    const cb = document.createElement("input");
    cb.type = "checkbox"; cb.checked = p.enabled; cb.setAttribute("aria-label", "Taking orders: " + p.name);
    cb.onchange = async () => {
      try {
        await server("POST", "printers/" + p.id + "/enabled", { enabled: cb.checked });
        toast(p.name + (cb.checked ? " is taking orders again" : " is switched off"));
        refreshCounter();
      } catch (e) { cb.checked = !cb.checked; handleServerError(e); }
    };
    sw.append(cb, el("i"));
    take.append(sw);
    card.append(row, detail, take);
    box.appendChild(card);
  }
}

const STATUS = {
  QUEUED: ["Waiting for a printer", "work"], PRINTING: ["Printing", "work"], CLAIMED: ["Starting", "work"],
  DOWNLOADING: ["Starting", "work"], SUBMITTED: ["Printing", "work"], COMPLETED: ["Printed", "ready"],
  FAILED: ["Problem", "stop"], CANCELLED: ["Cancelled", ""], EXPIRED: ["Not paid", ""],
  AWAITING_UPLOAD: ["Adding files", ""], AWAITING_PAYMENT: ["Not paid yet", ""],
  UPLOADING: ["Uploading", ""], READY: ["Not paid yet", ""], REJECTED: ["Refused", ""]
};

function renderOrders(list) {
  const box = $("orderList");
  box.innerHTML = "";
  if (!list.length) {
    const words = { active: "Nothing printing or waiting right now.", ready: "Nothing waiting to be handed over.",
                    problems: "No problems. 👍", all: "No orders yet." };
    box.appendChild(el("div", "empty", words[state.list]));
    return;
  }
  for (const o of list) box.appendChild(orderRow(o));
}

/** atCounter: the student is standing here (large pictures). */
function orderRow(o, atCounter) {
  const row = el("div", "order");
  const [words, lamp] = o.status === "AWAITING_PAYMENT" && o.paymentClaimedAt ? ["Payment to check", "work"]
    : STATUS[o.status] || [o.status, ""];
  const info = el("div");
  info.style.minWidth = "0";
  const docs = o.documents || [];
  const head = el("div", "meta head");
  head.append(el("span", "badge " + lamp, o.staffName && o.status === "AWAITING_PAYMENT" ? "Not sent yet" : words));
  if (o.staffName) head.append(el("span", "badge staff", "Staff · free"), el("span", "who", o.staffName));
  head.append(el("span", null, plural(docs.length, "file", "files") + " · " + plural(o.totalSheets || 0, "sheet", "sheets")),
    el("span", null, o.staffName ? plural(o.staffPages || 0, "free page", "free pages") : rupees(o.amountPaise)),
    el("span", null, o.paidAt ? (o.staffName ? "sent " : "paid ") + when(o.paidAt) : when(o.createdAt)));
  if (o.completedAt && !o.collectedAt) head.append(el("span", null, "ready " + when(o.completedAt)));
  if (o.collectedAt) head.append(el("span", null, "handed over " + when(o.collectedAt)));
  head.append(el("span", "no", "Order " + o.pickupCode));
  if (o.refundDuePaise) head.append(el("span", "badge stop", "Refund due " + rupees(o.refundDuePaise)));
  info.append(head);
  const list = el("div", "docs");
  for (const d of docs) list.append(docRow(o, d));
  info.append(list);
  if ((o.status === "FAILED" || o.status === "CANCELLED") && o.errorMessage && !docs.some(d => d.errorMessage)) {
    info.append(el("div", "err", o.errorMessage + (o.paymentId ? "  [payment " + o.paymentId + "]" : "")));
  }
  const acts = el("div", "acts");
  for (const a of actionsFor(o)) acts.appendChild(actionButton(a));
  const pics = el("div", "pics");
  for (const d of docs.filter(x => x.status !== "CANCELLED")) pics.append(picture(d, atCounter));
  row.append(pics, info, acts);
  return row;
}

/** One file of an order: what to print, how, and where it is. */
function docRow(o, d) {
  const r = el("div", "doc");
  const [words, lamp] = STATUS[d.status] || [d.status, ""];
  const top = el("div", "doc-top");
  top.append(el("span", "doc-n", d.position + "."), el("span", "doc-name", d.fileName));
  top.append(el("span", "badge " + (d.noPrinter ? "stop" : lamp),
    d.noPrinter ? "No printer can do this now" : words + (d.printerName ? " · " + d.printerName : "")));
  r.append(top);
  const pages = d.fileType === "PDF" ? (d.pages ? "pages " + d.pages.replace(/,/g, ", ") + " of " + d.pageCount
    : plural(d.pageCount || 0, "page", "pages")) : "picture";
  r.append(el("div", "doc-meta", pages + " · " + d.settingsText + " · ×" + d.copies + " · " +
    plural((d.sheets || 0) * d.copies, "sheet", "sheets") +
    (d.amountPaise != null && !o.staffName ? " · " + rupees(d.amountPaise) : "")));
  if (d.status === "FAILED" || (d.status === "CANCELLED" && d.errorMessage)) {
    r.append(el("div", "err", d.errorMessage || d.errorCode || ""));
  }
  const acts = [];
  if (d.status === "FAILED" && o.paidAt && !o.collectedAt && d.fileKept) {
    acts.push({ label: "Print this again", cls: "ghost", run: async () => {
      if (!confirm("Check the printer tray first: look for \"" + d.fileName + "\" with \"Order " + o.pickupCode +
                   "\" in the corner. Nothing there? Print it again?")) return;
      await server("POST", "documents/" + d.id + "/print-again");
      toast(d.fileName + " will print again");
      refreshCounter();
    } });
  }
  if ((d.status === "QUEUED" && (d.noPrinter || docsStarted(o))) || (d.status === "FAILED" && o.paidAt && !o.collectedAt)) {
    acts.push({ label: "Cancel this file", cls: "ghost", run: async () => {
      if (!confirm("Cancel \"" + d.fileName + "\"? The rest of the order still prints. " + (o.staffName
            ? "Its pages go back to " + o.staffName + "'s free pages."
            : "Refund " + rupees(d.amountPaise) + " " + refundWhere() + "."))) return;
      const res = await server("POST", "documents/" + d.id + "/cancel");
      toast(o.staffName ? "Cancelled. Its pages are free again." : "Cancelled. Refund " + rupees(res.refundPaise) + " to the student.");
      refreshCounter();
    } });
  }
  if (acts.length) {
    const box = el("div", "doc-acts");
    acts.forEach(a => box.append(actionButton(a, true)));
    r.append(box);
  }
  return r;
}

function docsStarted(o) {
  return (o.documents || []).some(d => d.status !== "QUEUED");
}

function actionButton(a, small) {
  const b = el("button", "btn sm " + (a.cls || "") + (small ? " xs" : ""), a.label);
  b.onclick = () => busy(b, null, async () => {
    try { await a.run(); } catch (e) { handleServerError(e); }
  });
  return b;
}

function actionsFor(o) {
  const list = [];
  const failed = (o.documents || []).filter(d => d.status === "FAILED");
  if (o.status === "COMPLETED" && !o.collectedAt) {
    list.push({ label: "Handed over", cls: "go", run: () => handOver(o) });
  }
  if (failed.length > 1 && o.paidAt && !o.collectedAt && failed.every(d => d.fileKept)) {
    list.push({ label: "Print failed files again", cls: "", run: async () => {
      if (!confirm("Check the printer trays first. Print the " + failed.length + " failed files of order " +
                   o.pickupCode + " again?")) return;
      await server("POST", "orders/" + o.id + "/print-again");
      toast("Order " + o.pickupCode + ": failed files will print again");
      refreshCounter();
    } });
  }
  if ((o.status === "FAILED" || failed.length || o.refundDuePaise) && o.paidAt && !o.collectedAt) {
    list.push({ label: o.staffName ? "Done" : "Refunded / done", cls: "ghost", run: async () => {
      if (!confirm("Mark order " + o.pickupCode + " as handled (printed by hand" +
                   (o.staffName ? " or handed over" : ", handed over, or refunded " + refundWhere()) + ")?")) return;
      await server("POST", "orders/" + o.id + "/collected");
      refreshCounter();
    } });
  }
  if (o.status === "AWAITING_PAYMENT" && upiMode() && !o.staffName) list.push(...paymentActions(o));
  if (o.status === "QUEUED") {
    list.push({ label: "Cancel order", cls: "ghost", run: async () => {
      if (!confirm("Cancel order " + o.pickupCode + "? " + (o.staffName
            ? "Its pages go back to " + o.staffName + "'s free pages."
            : "You must refund the student " + refundWhere() + "."))) return;
      await server("POST", "orders/" + o.id + "/cancel");
      refreshCounter();
    } });
  }
  return list;
}

/**
 * The pages were given to the student. The usual way: their own phone said
 * "I'm at the counter", so this order came up in the green box. Without that
 * (a phone with no internet, found by file name) staff make sure themselves.
 */
async function handOver(o) {
  if (!o.arrivedAt && !confirm("This student has not tapped “I’m at the counter” on their phone.\n\n" +
      "Hand over only if their phone shows this same order (same files, order " + o.pickupCode + ").\n\nHand over now?")) {
    return;
  }
  await server("POST", "orders/" + o.id + "/collected");
  toast("Order " + o.pickupCode + " handed over", "ok");
  if (state.find) runFind();
  refreshCounter();
}

document.querySelectorAll(".tab").forEach(t => {
  t.onclick = () => {
    document.querySelectorAll(".tab").forEach(x => x.classList.toggle("on", x === t));
    state.list = t.dataset.list;
    refreshCounter();
  };
});

/* Find an order by a file's name or its order number: for a student whose phone has no internet. */
let findTimer = null;
async function runFind() {
  const text = state.find;
  const box = $("findResult");
  if (text.length < 2) { box.innerHTML = ""; return; }
  let list;
  try { list = await server("GET", "orders?q=" + encodeURIComponent(text)); }
  catch (e) { handleServerError(e); return; }
  if (state.find !== text) return;                     // typed on meanwhile
  box.innerHTML = "";
  if (!list.length) {
    box.append(el("div", "notice work", "No paid order has a file or number like “" + text + "”. Check the spelling on the student's phone."));
    return;
  }
  for (const o of list) box.append(orderRow(o, false));
}
$("findText").addEventListener("input", () => {
  state.find = $("findText").value.trim();
  clearTimeout(findTimer);
  findTimer = setTimeout(runFind, 300);
});
$("findText").addEventListener("keydown", e => {
  if (e.key === "Escape") { $("findText").value = ""; state.find = ""; $("findResult").innerHTML = ""; }
});

/* The order-number label on pages (switch on the counter and in Settings). */
function setStampUi(on) {
  $("stampToggle").checked = on;
  $("stampToggle2").checked = on;
  $("stampCard").classList.toggle("off", !on);
  $("stampHint").textContent = on ? "Printed small in the corner of each first page"
    : "OFF: orders print with nothing added. Switch on again when done.";
}
async function setStamp(on) {
  try {
    await server("PUT", "settings", { stampCode: on });
    setStampUi(on);
    toast(on ? "The order number is printed on pages again" : "Label switched off: orders print with nothing added",
          on ? "ok" : "");
  } catch (e) {
    setStampUi(!on);
    handleServerError(e);
  }
}
$("stampToggle").onchange = () => setStamp($("stampToggle").checked);
$("stampToggle2").onchange = () => setStamp($("stampToggle2").checked);

/* ------------------------------------------------------------------ printers */
const TYPES = [["bw", "Black & white only"], ["color", "Colour and black & white"], ["coloronly", "Colour only"]];
/** Offered to students when staff have not chosen (same list as the server's PaperSize.DEFAULT_OFFERED). */
const DEFAULT_PAPER = ["A4", "A3", "A5", "LEGAL", "FOLIO"];
function typeOf(p) { return !p.supportsColor ? "bw" : p.acceptsBw ? "color" : "coloronly"; }
function statusBadge(s) {
  const t = (s || "Normal").toLowerCase();
  if (t === "normal") return ["Ready", "ready"];
  if (t.includes("paperout")) return ["Paper out", "work"];
  if (t.includes("offline")) return ["Offline", "stop"];
  if (t.includes("error") || t.includes("jam")) return ["Error", "stop"];
  return [s, "work"];
}
function paperLabel(id) {
  const p = ((state.summary && state.summary.paperSizes) || []).find(x => x.id === id);
  return p ? p.label : id;
}
function finishingLabel(id) {
  return ((state.summary && state.summary.finishingLabels) || {})[id] || id;
}

async function loadPrinterEditor(boxId) {
  const box = $(boxId);
  box.innerHTML = "";
  box.appendChild(el("div", "empty", "Scanning the printers on this PC and reading what each can do…"));
  let scan, summary;
  try {
    [scan, summary] = await Promise.all([local("GET", "windows-printers"), server("GET", "summary")]);
  } catch (e) {
    box.innerHTML = "";
    box.appendChild(el("div", "notice stop", e.message));
    return;
  }
  state.summary = summary;
  const mine = summary.printers.filter(p => p.agentId === state.local.agentId);
  const rows = [];
  const firstSetup = mine.length === 0;
  let n = 0;
  for (const w of scan) {
    const existing = mine.find(m => m.windowsPrinterName === w.name) || null;
    const use = existing ? true : firstSetup && !w.virtual;
    if (use) n++;
    rows.push({ w, existing, use, name: existing ? existing.name : use ? "Printer " + n : "",
                type: existing ? typeOf(existing) : (w.color ? "color" : "bw") });
  }
  for (const m of mine) {
    if (!scan.some(w => w.name === m.windowsPrinterName)) {
      rows.push({ w: { name: m.windowsPrinterName, missing: true, capabilities: m.capabilities }, existing: m, use: true,
                  name: m.name, type: typeOf(m) });
    }
  }
  box.innerHTML = "";
  if (!rows.length) {
    box.appendChild(el("div", "notice work", "Windows has no printers on this PC. Install the printer (Settings → Printers & scanners → Add), then press Scan again."));
  }
  state.editors[boxId] = rows.map(r => printerRow(box, r));
}

/** What Windows says the printer can do, as small labels. */
function featureChips(c) {
  const chips = el("div", "chips");
  if (!c) {
    chips.append(el("span", "badge work", "Windows did not describe this printer"));
    return chips;
  }
  const sizes = (c.paperSizes || []).map(p => p.id);
  chips.append(el("span", "badge", sizes.length ? "Paper: " + sizes.slice(0, 6).join(", ") + (sizes.length > 6 ? " +" + (sizes.length - 6) : "") : "No known paper sizes"));
  chips.append(el("span", "badge" + (c.duplex ? " ready" : ""), c.duplex ? "Two-sided" : "One-sided only"));
  chips.append(el("span", "badge", c.color ? "Colour" : "Black & white"));
  if ((c.finishing || []).length) chips.append(el("span", "badge ready", "Finishing: " + c.finishing.map(f => finishingLabel(f).split(":")[0]).filter((v, i, a) => a.indexOf(v) === i).join(", ")));
  if ((c.mediaTypes || []).length > 1) chips.append(el("span", "badge", plural(c.mediaTypes.length, "paper type", "paper types")));
  if (c.borderless) chips.append(el("span", "badge", "Borderless"));
  if (c.highQuality) chips.append(el("span", "badge", "High quality"));
  if ((c.trays || []).length) chips.append(el("span", "badge", plural(c.trays.length, "tray", "trays")));
  if (c.maxCopies) chips.append(el("span", "badge", "Copies up to " + c.maxCopies));
  return chips;
}

function checkbox(label, checked, hint) {
  const l = el("label", "opt-check");
  const c = document.createElement("input");
  c.type = "checkbox"; c.checked = !!checked;
  l.append(c, el("span", null, label));
  if (hint) l.append(el("small", null, hint));
  return [l, c];
}

/** "What students can choose" on one printer: only what it can do, narrowed by staff. */
function offerEditor(r) {
  const caps = r.w.capabilities || (r.existing && r.existing.capabilities) || null;
  const offered = (r.existing && r.existing.offered) || null;
  const box = el("details", "offer");
  const summary = el("summary", null, "What students can choose on this printer");
  box.append(summary);
  const body = el("div", "offer-body");
  box.append(body);
  const ed = { sizes: [], duplex: null, finishing: [], media: [], borderless: null, high: null, caps };

  const known = caps ? (caps.paperSizes || []).map(p => p.id) : ["A4", "A3", "A5", "LEGAL", "FOLIO", "LETTER"];
  const sizeWanted = offered && offered.paperSizes ? offered.paperSizes : DEFAULT_PAPER;
  const g1 = el("div", "offer-group");
  g1.append(el("b", null, "Paper sizes"));
  if (!caps) g1.append(el("small", "muted", "Windows could not tell what this printer takes: tick only sizes it really has."));
  const sizesBox = el("div", "offer-list");
  for (const id of known) {
    const [l, c] = checkbox(paperLabel(id), caps ? sizeWanted.includes(id) : (offered && offered.paperSizes ? offered.paperSizes.includes(id) : id === "A4"));
    sizesBox.append(l);
    ed.sizes.push([id, c]);
  }
  g1.append(sizesBox);
  body.append(g1);

  const g2 = el("div", "offer-group");
  g2.append(el("b", null, "Other options"));
  const others = el("div", "offer-list");
  if (!caps || caps.duplex) {
    const [l, c] = checkbox("Two-sided (both sides of the paper)", offered && offered.duplex != null ? offered.duplex : !!caps);
    others.append(l); ed.duplex = c;
  }
  if (caps && caps.borderless) {
    const [l, c] = checkbox("Borderless (photos to the edge)", !(offered && offered.borderless === false));
    others.append(l); ed.borderless = c;
  }
  if (caps && caps.highQuality) {
    const [l, c] = checkbox("High quality", !(offered && offered.highQuality === false));
    others.append(l); ed.high = c;
  }
  if (!others.children.length) others.append(el("small", "muted", "This printer prints one-sided only."));
  g2.append(others);
  body.append(g2);

  const fin = caps ? (caps.finishing || []) : [];
  if (fin.length) {
    const g = el("div", "offer-group");
    g.append(el("b", null, "Finishing"));
    g.append(el("small", "muted", "Tick an option only after a test print with it came out right: drivers often list a stapler or puncher that is not fitted."));
    const list = el("div", "offer-list");
    for (const id of fin) {
      const [l, c] = checkbox(finishingLabel(id), !!(offered && offered.finishing && offered.finishing.includes(id)));
      list.append(l); ed.finishing.push([id, c]);
    }
    g.append(list);
    body.append(g);
  }
  const media = caps ? (caps.mediaTypes || []) : [];
  if (media.length > 1) {
    const g = el("div", "offer-group");
    g.append(el("b", null, "Paper types students may pick"));
    g.append(el("small", "muted", "Tick only paper you keep in stock. Nothing ticked = students get the paper that is loaded."));
    const list = el("div", "offer-list");
    for (const m of media) {
      const [l, c] = checkbox(m.name, offered && offered.mediaTypes && offered.mediaTypes.includes(m.id));
      list.append(l); ed.media.push([m.id, c]);
    }
    g.append(list);
    body.append(g);
  }
  const describe = () => {
    const sizes = ed.sizes.filter(x => x[1].checked).map(x => x[0]);
    const parts = [sizes.length ? sizes.join(", ") : "no paper size!"];
    if (ed.duplex && ed.duplex.checked) parts.push("two-sided");
    const f = ed.finishing.filter(x => x[1].checked).length;
    if (f) parts.push(plural(f, "finishing option", "finishing options"));
    const m = ed.media.filter(x => x[1].checked).length;
    if (m) parts.push(plural(m, "paper type", "paper types"));
    summary.textContent = "Students can choose: " + parts.join(" · ");
  };
  body.addEventListener("change", describe);
  describe();
  ed.collect = () => {
    const o = { paperSizes: ed.sizes.filter(x => x[1].checked).map(x => x[0]) };
    if (ed.duplex) o.duplex = ed.duplex.checked;
    if (ed.finishing.length) o.finishing = ed.finishing.filter(x => x[1].checked).map(x => x[0]);
    if (ed.media.length) o.mediaTypes = ed.media.filter(x => x[1].checked).map(x => x[0]);
    if (ed.borderless) o.borderless = ed.borderless.checked;
    if (ed.high) o.highQuality = ed.high.checked;
    return o;
  };
  ed.element = box;
  return ed;
}

function printerRow(box, r) {
  const w = r.w;
  const row = el("div", "prow" + (r.use ? " use" : "") + (w.virtual ? " virtual" : ""));
  const check = document.createElement("input");
  check.type = "checkbox"; check.className = "check"; check.checked = r.use;
  check.setAttribute("aria-label", "Use " + w.name);
  const body = el("div");
  body.style.minWidth = "0";
  const top = el("div", "top");
  top.append(el("span", "wname", w.name));
  if (w.missing) {
    top.append(el("span", "badge stop", "Not found in Windows"));
  } else {
    const [st, cls] = statusBadge(w.status);
    top.append(el("span", "badge " + cls, st));
    if (w.connection) top.append(el("span", "badge", w.connection));
    if (w.virtual) top.append(el("span", "badge work", "Virtual: saves a file, no paper"));
  }
  if (r.existing) top.append(el("span", "badge ready", "In use"));
  body.append(top);
  if (w.driver) body.append(el("div", "facts", w.driver + (w.port ? " · " + w.port : "")));
  body.append(featureChips(w.capabilities || (r.existing && r.existing.capabilities)));

  const edit = el("div", "edit");
  const nameField = el("label", "field");
  nameField.append(el("span", null, "Name on the counter"));
  const name = document.createElement("input");
  name.className = "input"; name.maxLength = 60; name.value = r.name.trim();
  nameField.append(name);
  const typeField = el("label", "field");
  typeField.append(el("span", null, "What it prints"));
  const type = document.createElement("select");
  type.className = "input";
  for (const [v, label] of TYPES) {
    const o = document.createElement("option");
    o.value = v; o.textContent = label;
    type.append(o);
  }
  type.value = r.type;
  typeField.append(type);
  const tests = el("div");
  tests.style.cssText = "display:flex;gap:6px;flex-wrap:wrap";
  const msg = el("div", "test-msg muted");
  if (!w.missing) {
    const mk = (label, color, twoSided) => {
      const b = el("button", "btn ghost sm", label);
      b.onclick = () => busy(b, "Printing…", async () => {
        msg.className = "test-msg muted"; msg.textContent = "Sending a test page…";
        try {
          const res = await local("POST", "test-print", { printer: w.name, color, twoSided });
          msg.className = "test-msg"; msg.style.color = "var(--lamp-ready)"; msg.textContent = res.message;
        } catch (e) {
          msg.className = "test-msg"; msg.style.color = "var(--lamp-stop)"; msg.textContent = e.message;
        }
      });
      return b;
    };
    tests.append(mk("Test B/W", false, false));
    if (w.color) tests.append(mk("Test colour", true, false));
    if (w.capabilities && w.capabilities.duplex) tests.append(mk("Test two-sided", false, true));
  }
  edit.append(nameField, typeField, tests);
  const warn = el("div", "notice work small hidden");
  const offer = offerEditor(r);
  body.append(edit, warn, offer.element, msg);
  row.append(check, body);
  const caps = w.capabilities;
  const checkColour = () => {
    const bad = caps && caps.color === false && type.value !== "bw";
    warn.textContent = bad ? "Windows says this printer prints black & white only. Choose \"Black & white only\" unless you are sure." : "";
    warn.classList.toggle("hidden", !bad);
  };
  type.onchange = checkColour;
  checkColour();
  check.onchange = () => {
    row.classList.toggle("use", check.checked);
    if (check.checked && !name.value.trim()) {
      const used = (state.editors[box.id] || []).filter(x => x.check.checked).length;
      name.value = "Printer " + Math.max(1, used);
    }
  };
  box.appendChild(row);
  return { w, existing: r.existing, check, name, type, offer };
}

/** Creates, changes or removes printers on the server so they match the ticks. Returns how many are used. */
async function savePrinters(boxId) {
  const rows = state.editors[boxId] || [];
  let used = 0;
  for (const r of rows) {
    const use = r.check.checked;
    const t = r.type.value;
    const offered = r.offer.collect();
    if (use && !offered.paperSizes.length) {
      throw new Error("Tick at least one paper size for " + (r.name.value.trim() || r.w.name) + ".");
    }
    const body = { name: r.name.value.trim() || r.w.name, windowsPrinterName: r.w.name,
                   supportsColor: t !== "bw", acceptsBw: t !== "coloronly", offered };
    if (r.w.capabilities) {
      body.capabilities = r.w.capabilities;
      body.capabilitiesHash = r.w.capabilitiesHash;
    }
    if (use) used++;
    if (use && !r.existing) {
      await server("POST", "printers", Object.assign({ agentId: state.local.agentId }, body));
    } else if (use && r.existing) {
      await server("PUT", "printers/" + r.existing.id, body);
    } else if (!use && r.existing) {
      await server("DELETE", "printers/" + r.existing.id);
    }
  }
  await local("POST", "refresh").catch(() => {});       // this PC picks the change up at once
  return used;
}
$("savePrinters").onclick = () => busy($("savePrinters"), "Saving…", async () => {
  showError("printersError", null);
  try {
    const used = await savePrinters("printerEditor");
    $("printersSaved").textContent = "Saved. " + plural(used, "printer takes", "printers take") + " orders from this PC.";
    toast("Printers saved: students see the new choices at once", "ok");
    await loadPrinterEditor("printerEditor");
  } catch (e) {
    showError("printersError", e.message);
  }
});
document.querySelectorAll("[data-scan]").forEach(b => {
  b.onclick = () => {
    local("POST", "rescan").catch(() => {});
    loadPrinterEditor($("setup").classList.contains("hidden") ? "printerEditor" : "suPrinterList");
  };
});

/* ------------------------------------------------------------------ settings */
async function fillSettings() {
  fillPcInfo();
  try {
    const s = state.summary || await server("GET", "summary");
    state.summary = s;
    $("setName").value = s.centerName;
    $("setBw").value = s.priceBwPaise / 100;
    $("setColor").value = s.priceColorPaise / 100;
    setStampUi(s.stampCode);
    renderPricing(s);
  } catch (e) {
    handleServerError(e);
  }
}
function fillPcInfo() {
  const l = state.local;
  if (!l) return;
  $("setPcName").textContent = l.pcName;
  $("setServer").textContent = l.backendUrl || "–";
  $("setPrinting").textContent = !l.printing.running ? "Stopped" : l.printing.connected ? "Running" : "Connecting…";
  $("autostartToggle").checked = l.autostart;
  $("autostartToggle").disabled = !l.autostartSupported;
}
$("saveShop").onclick = () => busy($("saveShop"), "Saving…", async () => {
  const bw = Math.round(parseFloat($("setBw").value) * 100);
  const color = Math.round(parseFloat($("setColor").value) * 100);
  if (!(bw >= 0) || !(color >= 0)) { $("shopSaved").textContent = "Enter both prices."; return; }
  try {
    await server("PUT", "settings", { centerName: $("setName").value, priceBwPaise: bw, priceColorPaise: color });
    state.summary = null;
    $("shopSaved").textContent = "Saved.";
    toast("Prices saved", "ok");
  } catch (e) { handleServerError(e); }
});
/**
 * Extra prices, only for what the printers offer students: paper sizes other
 * than A4 (% of the A4 price per side), paper types (% of the normal price) and
 * finishing (rupees per copy).
 */
function renderPricing(s) {
  const box = $("pricingRules");
  box.innerHTML = "";
  const rules = s.pricing || {};
  const sizes = new Set(), finishing = new Set(), media = {};
  for (const p of s.printers.filter(x => x.enabled)) {
    const f = p.features || {};
    (f.paperSizes || []).forEach(x => sizes.add(x));
    (f.finishing || []).forEach(x => finishing.add(x.split("_")[0]));
    Object.assign(media, f.mediaTypeNames || {});
  }
  sizes.delete("A4");
  const inputs = [];
  const row = (label, value, unit, kind, key, step) => {
    const field = el("label", "field price-rule");
    field.append(el("span", null, label));
    const wrap = el("div", "suffix");
    const i = document.createElement("input");
    i.className = "input"; i.type = "number"; i.min = "0"; i.step = step; i.value = value;
    wrap.append(i, el("b", null, unit));
    field.append(wrap);
    box.append(field);
    inputs.push({ kind, key, input: i });
  };
  const ordered = (s.paperSizes || []).map(p => p.id).filter(id => sizes.has(id));
  for (const id of ordered) {
    row(paperLabel(id) + " price", (rules.paperSizePercent || {})[id] ?? 100, "% of A4", "size", id, "5");
  }
  for (const id of Object.keys(media)) {
    row(media[id] + " paper", (rules.mediaTypePercent || {})[id] ?? 100, "% of normal", "media", id, "5");
  }
  const words = { STAPLE: "Stapling", PUNCH: "Hole punching", BIND: "Binding" };
  for (const g of ["STAPLE", "PUNCH", "BIND"].filter(g => finishing.has(g))) {
    row(words[g] + ", per copy", ((rules.finishingPaise || {})[g] ?? 0) / 100, "₹", "finishing", g, "0.5");
  }
  if (!inputs.length) {
    box.append(el("p", "small muted", "Your printers offer only A4, the usual paper and no finishing, so there is nothing extra to price."));
  }
  state.pricingInputs = inputs;
  $("savePricing").classList.toggle("hidden", !inputs.length);
}
$("savePricing").onclick = () => busy($("savePricing"), "Saving…", async () => {
  const s = state.summary || await server("GET", "summary");
  const rules = JSON.parse(JSON.stringify(s.pricing || {}));
  rules.paperSizePercent = rules.paperSizePercent || {};
  rules.mediaTypePercent = rules.mediaTypePercent || {};
  rules.finishingPaise = rules.finishingPaise || {};
  for (const x of state.pricingInputs || []) {
    const v = parseFloat(x.input.value);
    if (!(v >= 0)) { $("pricingSaved").textContent = "Fill in every price."; return; }
    if (x.kind === "size") rules.paperSizePercent[x.key] = Math.round(v);
    if (x.kind === "media") rules.mediaTypePercent[x.key] = Math.round(v);
    if (x.kind === "finishing") rules.finishingPaise[x.key] = Math.round(v * 100);
  }
  try {
    await server("PUT", "settings", { pricing: rules });
    state.summary = null;
    $("pricingSaved").textContent = "Saved.";
    toast("Prices saved", "ok");
  } catch (e) { handleServerError(e); }
});

$("autostartToggle").onchange = async () => {
  try {
    state.local = await local("POST", "autostart", { enabled: $("autostartToggle").checked });
    toast(state.local.autostart ? "XeoGo will start with Windows" : "XeoGo will not start with Windows");
  } catch (e) { $("autostartToggle").checked = !$("autostartToggle").checked; toast(e.message, "stop"); }
};
$("savePassword").onclick = () => busy($("savePassword"), null, async () => {
  try {
    state.local = await local("POST", "password", { password: $("newPassword").value });
    $("newPassword").value = "";
    state.askedPassword = false;
    showError("appError", null);
    toast("Counter password saved", "ok");
  } catch (e) { toast(e.message, "stop"); }
});
$("openLogs").onclick = () => local("POST", "open-logs").catch(e => toast(e.message, "stop"));
$("disconnect").onclick = () => busy($("disconnect"), null, async () => {
  if (!confirm("Disconnect this PC? It stops printing, and its printers are removed from XeoGo.")) return;
  try {
    state.local = await local("POST", "disconnect");
    startSetup();
  } catch (e) { toast(e.message, "stop"); }
});

boot();

/* ------------------------------------------------------------------ XeoGo Pay: UPI payments */
/** The XeoGo Pay Verifier phone: is it online, and what may it read? */
function verifierLine(u) {
  const vs = (u && u.verifiers) || [];
  const live = vs.filter(v => Date.now() - new Date(v.lastSeenAt).getTime() < 3 * 3600 * 1000);
  const fresh = live.find(v => v.sms || v.notifications) || live[0];
  if (u && !u.alertsConfigured) return ["work", "Automatic confirmation is off: set UPI_ALERT_TOKEN on the server and install XeoGo Pay Verifier on the shop's phone."];
  if (fresh) {
    const reads = [fresh.notifications ? "UPI app notifications" : null, fresh.sms ? "bank SMS" : null].filter(Boolean).join(" + ") || "nothing yet (allow it on the phone)";
    return [fresh.notifications || fresh.sms ? "ok" : "work", "Verifier phone “" + fresh.device + "” online · checked in " + when(fresh.lastSeenAt) +
      (fresh.lastAlertAt ? " · last payment message " + when(fresh.lastAlertAt) : "") + " · reads " + reads + "."];
  }
  if (vs.length) return ["stop", "Verifier phone “" + vs[0].device + "” not heard from since " + when(vs[0].lastSeenAt) +
    ". Until it is back, students press “I have paid” and you confirm here. Is it switched on, online and charging?"];
  return ["work", "No Verifier phone yet: install XeoGo Pay Verifier on the shop's phone for automatic payments."];
}

function upiMode() { return !!(state.summary && state.summary.paymentMode === "upi"); }
function refundWhere() { return upiMode() ? "by UPI from the shop's account" : "in the Razorpay dashboard"; }
function groupRef(r) { return /^\d{12}$/.test(r || "") ? r.slice(0, 4) + " " + r.slice(4, 8) + " " + r.slice(8) : (r || ""); }

/** Staff saw the money in the bank or UPI app (or could not find it). */
function paymentActions(o) {
  return [
    { label: "Money received", cls: "go", run: async () => {
      let ref = o.paymentClaimRef;
      if (ref) {
        if (!confirm("Did " + rupees(o.amountPaise) + " with reference " + groupRef(ref) +
                     " arrive in the bank or UPI app? The order prints straight away.")) return;
      } else {
        ref = prompt("Did " + rupees(o.amountPaise) + " for " + o.pickupCode + " arrive in the bank or UPI app?\n" +
                     "Type its 12-digit UPI reference number if you see it (or leave it empty), then OK.", "");
        if (ref === null) return;
      }
      await server("POST", "orders/" + o.id + "/payment/approve", { reference: ref || null });
      toast("Order " + o.pickupCode + ": payment confirmed, printing", "ok");
      if (state.find) runFind();
      refreshCounter();
    } },
    { label: "Not found", cls: "ghost", run: async () => {
      const why = prompt("Tell the student why (optional), for example: no payment of " + rupees(o.amountPaise) + " today", "");
      if (why === null) return;
      await server("POST", "orders/" + o.id + "/payment/reject", { reason: why || null });
      toast("Order " + o.pickupCode + ": the student is asked to check the payment");
      refreshCounter();
    } }
  ];
}

function renderPayments(p) {
  const box = $("orderList");
  box.innerHTML = "";
  const u = p.upi || {};
  const info = el("div", "notice " + (u.autoConfirm ? "ok" : "work"));
  info.append("Students pay ", el("b", null, u.payeeVpa || "–"), " (" + (u.payeeName || "") + ") from any UPI app. ",
    u.autoConfirm ? "Bank messages from the shop's phone confirm payments by themselves: below are only the ones they could not."
                  : "Check each payment in the bank or UPI app, then press Money received: only then it prints.",
    " Today: " + plural(p.today.paidOrders || 0, "order", "orders") + " paid, " + rupees(p.today.paidPaise || 0) +
    (u.autoConfirm ? " (" + (p.today.confirmedByBank || 0) + " confirmed by the bank)." : "."));
  box.append(info);
  const [vk, vt] = verifierLine(u);
  box.append(el("div", "notice " + vk, vt));

  box.append(el("h3", "pay-h", "Students who say they paid (" + p.toCheck.length + ")"));
  if (!p.toCheck.length) box.append(el("div", "empty", "Nothing to check. 👍"));
  for (const o of p.toCheck) {
    const row = el("div", "order pay-order");
    const mid = el("div");
    mid.style.minWidth = "0";
    const head = el("div", "meta head");
    head.append(el("span", "badge work", "Payment to check"), el("b", "pay-amount", rupees(o.amountPaise)),
      el("span", null, o.paymentClaimRef ? "reference " : "no reference typed"));
    if (o.paymentClaimRef) head.append(el("span", "pay-ref", groupRef(o.paymentClaimRef)));
    head.append(el("span", null, "said paid " + when(o.paymentClaimedAt)),
      el("span", null, plural((o.documents || []).length, "file", "files") + " · " + plural(o.totalSheets || 0, "sheet", "sheets")));
    mid.append(head);
    for (const a of (p.hints[o.id] || [])) {
      mid.append(el("div", "pay-hint", a.trusted === false
        ? "A message " + when(a.receivedAt) + " says " + rupees(a.amountPaise) + ", but NOT from the bank: do not count it. Look in the bank or UPI app."
        : "Bank message " + when(a.receivedAt) + ": " + rupees(a.amountPaise) +
          (a.refs.length ? " · ref " + a.refs.map(groupRef).join(", ") : "") + " (same amount, not matched automatically)"));
    }
    const acts = el("div", "acts");
    for (const a of paymentActions(o)) acts.append(actionButton(a));
    row.append(el("div", "code", o.pickupCode), mid, acts);
    box.append(row);
  }

  const paste = el("div", "card paste");
  paste.append(el("b", null, "Paste a bank SMS"),
    el("div", "sub", "Copy the bank's \"credited\" message from the shop's phone and paste it here: it pays the order it proves, like a forwarded one."));
  const ta = el("textarea", "input");
  ta.id = "pasteSms";
  ta.rows = 3;
  ta.placeholder = "e.g. A/C X1234 credited by Rs.20.01 ... Ref No 627312345678";
  const res = el("div", "sub", state.pasteResult || "");
  const go = el("button", "btn sm", "Check this message");
  go.onclick = () => busy(go, "Checking…", async () => {
    try {
      const r = await server("POST", "payments/alerts", { text: ta.value });
      state.pasteResult = r.kind !== "CREDIT" ? "Not a money-received message." : r.paidOrder
        ? "Paid order " + r.paidOrder + " (" + rupees(r.amountPaise) + ")." : r.duplicate ? "Already pasted before."
        : rupees(r.amountPaise) + " received, but no order waits for exactly this amount.";
      if (r.paidOrder) toast(state.pasteResult, "ok");
      ta.value = "";
      refreshCounter();
    } catch (e) { handleServerError(e); }
  });
  paste.append(ta, go, res);
  box.append(el("h3", "pay-h", "Bank messages"), paste);

  if (p.alerts.length) {
    const t = el("table", "pay-alerts");
    const head = el("tr");
    ["Time", "Amount", "Order", "Message"].forEach(h => head.append(el("th", null, h)));
    const th = el("thead");
    th.append(head);
    const tb = el("tbody");
    const how = { REFERENCE: "by reference", AMOUNT: "by amount", COUNTER: "by staff" };
    for (const a of p.alerts) {
      const tr = el("tr", a.trusted === false ? "untrusted" : null);
      const msg = el("td", "msg", (a.sender ? a.sender + ": " : "") + a.message);
      if (a.trusted === false) {
        msg.append(el("span", "why", "Not counted. " + (a.trustNote || "")));
        if (a.senderKey) {
          // Only for an SMS with a sender NAME; never offered for a phone number or an app with chat.
          msg.append(actionButton({ label: "This is our bank (" + a.senderKey + ")", cls: "ghost", run: async () => {
            if (!confirm("Is " + a.senderKey + " the name your bank's SMS come from? Check it in the SMS list on the shop's phone.\n\n" +
                "After this, every SMS from " + a.senderKey + " that says money came in confirms an order by itself.")) return;
            await server("POST", "payments/alerts/" + a.id + "/trust-sender");
            toast("SMS from " + a.senderKey + " now confirm payments", "ok");
            refreshCounter();
          } }, true));
        }
      }
      tr.append(el("td", null, when(a.receivedAt)), el("td", null, rupees(a.amountPaise)),
        el("td", null, a.pickupCode ? "order " + a.pickupCode + " · " + (how[a.matchMethod] || "") : "no order"), msg);
      tb.append(tr);
    }
    t.append(th, tb);
    box.append(t);
  }
  if (p.waiting.length) {
    box.append(el("h3", "pay-h", "Payment screen open, not paid yet (" + p.waiting.length + ")"));
    box.append(el("div", "sub", p.waiting.map(o => o.pickupCode + " " + rupees(o.amountPaise) + " (" + when(o.paymentStartedAt) + ")").join(" · ")));
  }
  if (p.paid.length) {
    box.append(el("h3", "pay-h", "Paid by UPI, last 24 hours (" + p.paid.length + ")"));
    box.append(el("div", "sub", p.paid.map(o => o.pickupCode + " " + rupees(o.amountPaise) + " " + when(o.paidAt) +
      (o.paymentVerifiedBy === "counter" ? " (staff)" : " (bank)")).join(" · ")));
  }
  if ((p.senders || []).length) {
    box.append(el("h3", "pay-h", "Bank SMS senders you confirmed"));
    const line = el("div", "sub");
    for (const sender of p.senders) {
      line.append(sender + " ", actionButton({ label: "Remove", cls: "ghost", run: async () => {
        if (!confirm("Stop counting SMS from " + sender + "?")) return;
        await server("DELETE", "payments/senders/" + encodeURIComponent(sender));
        refreshCounter();
      } }, true), " ");
    }
    box.append(line);
  }
}

/* ------------------------------------------------------------------ staff IDs: free printing for college staff */
/* The Xerox center makes each ID here: a username and a password, nothing else.
 * The server makes the password and shows it once (StaffService.java); this
 * screen passes it on, as a slip to copy or print, and never keeps it. */
async function loadStaff() {
  try {
    const o = await server("GET", "staff");
    state.staff = o;
    if (!state.staffTyping) $("staffPages").value = o.monthlyPages;
    $("staffColor").checked = o.colorAllowed;
    $("staffSiteRow").classList.toggle("hidden", !o.site);
    $("staffSite").textContent = o.site ? o.site + "  (or the XeoGo Staff app)" : "";
    renderStaff();
    showError("appError", null);
  } catch (e) {
    if (e.status === 404) showError("appError", "This server is an older version without staff IDs. Update the server first.");
    else handleServerError(e);
  }
}

function dayWords(isoDate) {
  const d = new Date(isoDate + "T00:00:00");
  return isNaN(d) ? isoDate : d.toLocaleDateString([], { day: "numeric", month: "long" });
}

function renderStaff() {
  const o = state.staff;
  if (!o) return;
  const q = $("staffFilter").value.trim().toLowerCase();
  const list = o.accounts.filter(a => !q || a.name.toLowerCase().includes(q) || a.username.includes(q));
  $("staffCount").textContent = "(" + o.accounts.length + ")";
  $("staffMonth").textContent = o.month + " · the free pages start again on " + dayWords(o.resetsOn);
  const box = $("staffList");
  box.innerHTML = "";
  if (!o.accounts.length) { box.append(el("div", "empty", "No staff IDs yet. Make the first one above.")); return; }
  if (!list.length) { box.append(el("div", "empty", "No staff ID like “" + q + "”.")); return; }
  for (const a of list) box.append(staffRow(a));
}

function staffRow(a) {
  const row = el("div", "staff-row" + (a.active ? "" : " off"));
  const who = el("div", "who");
  who.append(el("b", null, a.name), el("span", "user", a.username));
  if (!a.active) who.append(el("span", "badge stop", "Switched off"));
  if (a.waiting) who.append(el("span", "badge work", "Wrong passwords: waiting"));

  const use = el("div", "use");
  const bar = el("div", "bar");
  const fill = el("i");
  const pct = a.monthlyPages > 0 ? Math.min(100, Math.round(a.usedPages * 100 / a.monthlyPages)) : (a.usedPages ? 100 : 0);
  fill.style.width = pct + "%";
  if (a.leftPages <= 0) fill.className = "full";
  bar.append(fill);
  use.append(el("div", "n", a.usedPages + " of " + a.monthlyPages + " pages used" + (a.ownMonthlyPages != null ? " (own number)" : "")),
    bar, el("div", "last", a.lastPrintAt ? "last print " + when(a.lastPrintAt) : "has not printed yet"));

  const acts = el("div", "acts");
  const act = (label, cls, run) => acts.append(actionButton({ label, cls, run }, true));
  act("New password", "ghost", async () => {
    if (!confirm("Make a new password for " + a.name + "?\n\nThe old password stops working, and every phone or computer " +
                 "signed in with it is signed out.")) return;
    showSlip(await server("POST", "staff/" + a.id + "/password"), "New password");
    loadStaff();
  });
  act("Pages", "ghost", async () => {
    const typed = prompt("Free pages per month for " + a.name + ".\nLeave empty for the usual " + state.staff.monthlyPages + ".",
      a.ownMonthlyPages != null ? String(a.ownMonthlyPages) : "");
    if (typed === null) return;
    const t = typed.trim();
    if (t !== "" && !/^\d{1,6}$/.test(t)) { toast("Type a number of pages, or leave it empty.", "stop"); return; }
    await server("PUT", "staff/" + a.id, t === "" ? { usualPages: true } : { monthlyPages: parseInt(t, 10) });
    toast(a.name + ": " + (t === "" ? "the usual " + state.staff.monthlyPages : t) + " free pages a month", "ok");
    loadStaff();
  });
  act("Rename", "ghost", async () => {
    const typed = prompt("Name of this staff member (the username " + a.username + " stays):", a.name);
    if (typed === null || !typed.trim() || typed.trim() === a.name) return;
    await server("PUT", "staff/" + a.id, { name: typed.trim() });
    loadStaff();
  });
  act(a.active ? "Switch off" : "Switch on", "ghost", async () => {
    if (a.active && !confirm("Switch off " + a.name + "'s staff ID?\n\nThey cannot sign in or print until you switch it on " +
                             "again. Nothing is deleted.")) return;
    await server("PUT", "staff/" + a.id, { active: !a.active });
    toast(a.name + (a.active ? ": switched off" : ": switched on again"), a.active ? "" : "ok");
    loadStaff();
  });
  act("Remove", "danger", async () => {
    if (!confirm("Remove " + a.name + "'s staff ID (" + a.username + ") for good?\n\nWhat they already sent still prints. " +
                 "If they should only stop for a while, use Switch off instead.")) return;
    await server("DELETE", "staff/" + a.id);
    toast("Removed " + a.name);
    loadStaff();
  });
  row.append(who, use, acts);
  return row;
}

/** The username and password, right after they were made: to copy or print now. They are not shown again. */
function showSlip(made, title) {
  const a = made.account;
  const box = $("staffSlip");
  box.innerHTML = "";
  box.classList.remove("hidden");
  box.append(el("b", null, title + " · " + a.name));
  const dl = el("dl");
  dl.append(el("dt", null, "Username"), el("dd", null, a.username), el("dt", null, "Password"), el("dd", "big", made.password));
  box.append(dl, el("p", null, "Give these to the staff member now: the password is not shown again. " +
    "Forgotten later? Press New password on their ID. Capitals and the dash do not matter when typing it."));
  const acts = el("div", "acts");
  const copy = el("button", "btn sm ghost", "Copy");
  copy.onclick = async () => {
    const site = state.staff && state.staff.site;
    try {
      await navigator.clipboard.writeText("XeoGo staff ID\nName: " + a.name + "\nUsername: " + a.username + "\nPassword: " +
        made.password + (site ? "\nSign in: " + site : ""));
      toast("Copied", "ok");
    } catch (e) { toast("Could not copy. Write it down, or print the slip.", "stop"); }
  };
  const print = el("button", "btn sm ghost", "Print slip");
  print.onclick = () => printSlip(a, made.password);
  const close = el("button", "btn sm", "Done");
  close.onclick = () => { box.classList.add("hidden"); box.innerHTML = ""; $("printSlip").innerHTML = ""; };
  acts.append(copy, print, close);
  box.append(acts);
  box.scrollIntoView({ block: "nearest" });
}

function printSlip(a, password) {
  const p = $("printSlip");
  p.innerHTML = "";
  const dl = el("dl");
  dl.append(el("dt", null, "Name"), el("dd", null, a.name), el("dt", null, "Username"), el("dd", null, a.username),
    el("dt", null, "Password"), el("dd", null, password));
  const site = state.staff && state.staff.site;
  p.append(el("h1", null, "XeoGo · staff ID"), el("p", "where", (state.summary && state.summary.centerName) || ""), dl,
    el("p", "note", plural(a.monthlyPages, "free page", "free pages") + " every month. Sign in " +
      (site ? "at " + site + " or " : "") + "in the XeoGo Staff app. Keep this slip to yourself."));
  window.print();
}

$("staffMake").onclick = () => busy($("staffMake"), "Making…", async () => {
  const name = $("staffName").value.trim();
  showError("staffMadeNote", null);
  if (name.length < 2) { showError("staffMadeNote", "Type the staff member's name."); $("staffName").focus(); return; }
  try {
    const made = await server("POST", "staff", { name, username: $("staffUsername").value.trim() || null });
    $("staffName").value = "";
    $("staffUsername").value = "";
    showSlip(made, "Staff ID made");
    loadStaff();
  } catch (e) {
    if (e.status === 400 || e.status === 409) showError("staffMadeNote", e.message); else handleServerError(e);
  }
});
["staffName", "staffUsername"].forEach(id => $(id).addEventListener("keydown", e => { if (e.key === "Enter") $("staffMake").click(); }));
$("staffSave").onclick = () => busy($("staffSave"), "Saving…", async () => {
  const pages = parseInt($("staffPages").value, 10);
  if (!(pages >= 0 && pages <= 100000)) { $("staffSaved").textContent = "Type a number of pages (0 to 100000)."; return; }
  try {
    await server("PUT", "staff/settings", { monthlyPages: pages });
    state.staffTyping = false;
    $("staffSaved").textContent = "Saved.";
    setTimeout(() => { $("staffSaved").textContent = ""; }, 4000);
    loadStaff();
  } catch (e) { $("staffSaved").textContent = ""; handleServerError(e); }
});
$("staffColor").onchange = async () => {
  const on = $("staffColor").checked;
  try {
    await server("PUT", "staff/settings", { colorAllowed: on });
    toast(on ? "Staff can print colour and on special paper for free" : "Free staff printing is black & white on the usual paper", on ? "ok" : "");
  } catch (e) { $("staffColor").checked = !on; handleServerError(e); }
};
$("staffPages").addEventListener("input", () => { state.staffTyping = true; $("staffSaved").textContent = ""; });
$("staffFilter").addEventListener("input", renderStaff);
