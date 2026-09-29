"use strict";
/* Campus Print Station: the screens of the Xerox center app.
 *   /local/...          this PC (printers, test prints, setup)   - LocalServer.java
 *   /api/v1/counter/... the Campus Print server (passed through)  - CounterController.java
 */
const $ = (id) => document.getElementById(id);
const TOKEN = (() => {
  const t = new URL(location.href).searchParams.get("t");
  if (t) { sessionStorage.setItem("station.t", t); history.replaceState(null, "", "/"); }
  return t || sessionStorage.getItem("station.t") || "";
})();
const state = { local: null, summary: null, view: "counter", list: "active", editors: {}, found: null, askedPassword: false };

/* ------------------------------------------------------------------ helpers */
async function call(method, path, body) {
  const headers = { "X-Station-Token": TOKEN };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (e) {
    throw new Error("Campus Print is not responding. Open it again from its icon.");
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
      ? "Campus Print starts together with Windows and keeps printing when this window is closed."
      : "Campus Print keeps printing when this window is closed (the icon next to the clock stays).";
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
  ["counter", "printers", "settings"].forEach(v => $("view-" + v).classList.toggle("hidden", v !== view));
  document.querySelectorAll(".nav button").forEach(b => b.classList.toggle("on", b.dataset.view === view));
  showError("appError", null);
  if (view === "counter") { refreshCounter(); setTimeout(() => $("findCode").focus(), 0); }
  if (view === "printers") loadPrinterEditor("printerEditor");
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
  else if (!p.connected) setPc("work", "Connecting…", p.problem || "Waiting for the Campus Print server.");
  else setPc(p.busy ? "work" : "ready", p.busy ? "Printing an order" : "Ready to print",
    plural(p.printers.length, "printer", "printers") + " on this PC" + (missing ? " · " + missing + " not found" : ""));
  $("navPrinters").classList.toggle("hidden", missing === 0);
  if (state.view === "settings") fillPcInfo();
}
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
    const [s, list] = await Promise.all([
      server("GET", "summary"), server("GET", "orders?view=" + state.list)
    ]);
    state.summary = s;
    renderSummary(s);
    renderOrders(list);
    showError("appError", null);
  } catch (e) {
    handleServerError(e);
  }
}

function renderSummary(s) {
  $("sideCenter").textContent = s.centerName;
  $("demoBanner").classList.toggle("hidden", s.paymentMode !== "demo");
  $("nActive").textContent = s.waiting + s.printing;
  $("nReady").textContent = s.ready;
  $("nProblems").textContent = s.problems;
  $("tabProblems").classList.toggle("alert", s.problems > 0);
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

function orderRow(o) {
  const row = el("div", "order");
  const [words, lamp] = STATUS[o.status] || [o.status, ""];
  const info = el("div");
  info.style.minWidth = "0";
  const docs = o.documents || [];
  const head = el("div", "meta head");
  head.append(el("span", "badge " + lamp, words),
    el("span", null, plural(docs.length, "file", "files") + " · " + plural(o.totalSheets || 0, "sheet", "sheets")),
    el("span", null, rupees(o.amountPaise)),
    el("span", null, when(o.paidAt || o.createdAt)));
  if (o.collectedAt) head.append(el("span", null, "handed over " + when(o.collectedAt)));
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
  row.append(el("div", "code", o.pickupCode), info, acts);
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
    plural((d.sheets || 0) * d.copies, "sheet", "sheets") + (d.amountPaise != null ? " · " + rupees(d.amountPaise) : "")));
  if (d.status === "FAILED" || (d.status === "CANCELLED" && d.errorMessage)) {
    r.append(el("div", "err", d.errorMessage || d.errorCode || ""));
  }
  const acts = [];
  if (d.status === "FAILED" && o.paidAt && !o.collectedAt && d.fileKept) {
    acts.push({ label: "Print this again", cls: "ghost", run: async () => {
      if (!confirm("Check the printer tray first: look for \"" + d.fileName + "\" with \"Pickup " + o.pickupCode +
                   "\" in the corner. Nothing there? Print it again?")) return;
      await server("POST", "documents/" + d.id + "/print-again");
      toast(d.fileName + " will print again");
      refreshCounter();
    } });
  }
  if ((d.status === "QUEUED" && (d.noPrinter || docsStarted(o))) || (d.status === "FAILED" && o.paidAt && !o.collectedAt)) {
    acts.push({ label: "Cancel this file", cls: "ghost", run: async () => {
      if (!confirm("Cancel \"" + d.fileName + "\"? The rest of the order still prints. Refund " +
                   rupees(d.amountPaise) + " in the Razorpay dashboard.")) return;
      const res = await server("POST", "documents/" + d.id + "/cancel");
      toast("Cancelled. Refund " + rupees(res.refundPaise) + " to the student.");
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
      if (!confirm("Check the printer trays first. Print the " + failed.length + " failed files of " +
                   o.pickupCode + " again?")) return;
      await server("POST", "orders/" + o.id + "/print-again");
      toast(o.pickupCode + ": failed files will print again");
      refreshCounter();
    } });
  }
  if ((o.status === "FAILED" || failed.length || o.refundDuePaise) && o.paidAt && !o.collectedAt) {
    list.push({ label: "Refunded / done", cls: "ghost", run: async () => {
      if (!confirm("Mark " + o.pickupCode + " as handled (printed by hand, handed over, or refunded in Razorpay)?")) return;
      await server("POST", "orders/" + o.id + "/collected");
      refreshCounter();
    } });
  }
  if (o.status === "QUEUED") {
    list.push({ label: "Cancel order", cls: "ghost", run: async () => {
      if (!confirm("Cancel " + o.pickupCode + "? You must refund the student in the Razorpay dashboard.")) return;
      await server("POST", "orders/" + o.id + "/cancel");
      refreshCounter();
    } });
  }
  return list;
}

async function handOver(o) {
  await server("POST", "orders/" + o.id + "/collected");
  toast(o.pickupCode + " handed over", "ok");
  if (state.found && state.found.id === o.id) clearFind();
  refreshCounter();
}

document.querySelectorAll(".tab").forEach(t => {
  t.onclick = () => {
    document.querySelectorAll(".tab").forEach(x => x.classList.toggle("on", x === t));
    state.list = t.dataset.list;
    refreshCounter();
  };
});

/* Find a pickup code: the student shows it, staff type it, Enter hands over. */
function clearFind() {
  $("findCode").value = "";
  $("findResult").innerHTML = "";
  state.found = null;
  $("findCode").focus();
}
$("findCode").addEventListener("input", async () => {
  const code = $("findCode").value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 5);
  $("findCode").value = code;
  state.found = null;
  const box = $("findResult");
  if (code.length < 5) { box.innerHTML = ""; return; }
  let list;
  try { list = await server("GET", "orders?code=" + encodeURIComponent(code)); }
  catch (e) { handleServerError(e); return; }
  if ($("findCode").value !== code) return;            // typed on meanwhile
  box.innerHTML = "";
  if (!list.length) {
    const c = el("div", "found bad");
    c.append(el("div", "code", code), el("div", "what", "No order has this code. Check it on the student's phone."));
    box.appendChild(c);
    return;
  }
  const o = list[0];
  const docs = (o.documents || []).filter(d => d.status !== "CANCELLED");
  const sheets = o.totalSheets || 0;
  const canHand = o.status === "COMPLETED" && !o.collectedAt;
  const c = el("div", "found" + (canHand ? "" : o.status === "FAILED" ? " bad" : " warn"));
  const what = el("div", "what");
  const where = [...new Set(docs.map(d => d.printerName).filter(Boolean))];
  const headline = canHand ? "Ready on " + (where.length ? where.join(" and ") : "the printer")
    : o.collectedAt ? "Already handed over " + when(o.collectedAt)
    : (STATUS[o.status] || [o.status])[0];
  what.append(el("b", null, headline));
  for (const d of docs) {
    what.append(el("span", "line", d.position + ". " + d.fileName + " · " + d.settingsText + " · ×" + d.copies +
      (d.printerName ? " · " + d.printerName : "")));
  }
  const pile = el("div", "pile", String(sheets));
  pile.append(el("small", null, sheets === 1 ? "sheet" : "sheets"));
  c.append(el("div", "code", o.pickupCode), what, pile);
  if (canHand) {
    const b = el("button", "btn go lg", "Handed over");
    b.onclick = () => busy(b, null, async () => { try { await handOver(o); } catch (e) { handleServerError(e); } });
    c.append(b);
    state.found = o;
  }
  box.appendChild(c);
});
$("findCode").addEventListener("keydown", e => {
  if (e.key === "Enter" && state.found) { e.preventDefault(); $("findResult").querySelector(".btn.go")?.click(); }
  if (e.key === "Escape") clearFind();
});

/* The pickup-code switch (on the counter and in Settings). */
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
    toast(on ? "Pickup code is printed on pages again" : "Pickup code switched off: orders print with nothing added",
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
    toast(state.local.autostart ? "Campus Print will start with Windows" : "Campus Print will not start with Windows");
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
  if (!confirm("Disconnect this PC? It stops printing, and its printers are removed from Campus Print.")) return;
  try {
    state.local = await local("POST", "disconnect");
    startSetup();
  } catch (e) { toast(e.message, "stop"); }
});

boot();
