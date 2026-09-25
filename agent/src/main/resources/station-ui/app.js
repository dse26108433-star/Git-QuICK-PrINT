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
/** "58 of 1000 pages (333-390)" or "12 pages". */
function pagesText(o) {
  const n = o.printPages || o.pageCount;
  if (!n) return "? pages";
  if (!o.pages) return plural(n, "page", "pages");
  return n + " of " + o.pageCount + " pages (" + o.pages.replace(/,/g, ", ") + ")";
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
  QUEUED: ["Waiting for a printer", "work"], CLAIMED: ["Starting", "work"], DOWNLOADING: ["Starting", "work"],
  SUBMITTED: ["Printing", "work"], COMPLETED: ["Printed", "ready"], FAILED: ["Problem", "stop"],
  CANCELLED: ["Cancelled", ""], EXPIRED: ["Not paid", ""], AWAITING_UPLOAD: ["Uploading", ""], AWAITING_PAYMENT: ["Not paid yet", ""]
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
  info.append(el("div", "file", o.fileName));
  const meta = el("div", "meta");
  const status = el("span", "badge " + lamp, words + (o.printerName ? " · " + o.printerName : ""));
  meta.append(status,
    el("span", null, pagesText(o) + " × " + o.copies),
    el("span", null, o.color ? "Colour" : "B/W"),
    el("span", null, rupees(o.amountPaise)),
    el("span", null, when(o.paidAt || o.createdAt)));
  if (o.collectedAt) meta.append(el("span", null, "handed over " + when(o.collectedAt)));
  info.append(meta);
  if (o.status === "FAILED" || o.status === "CANCELLED") {
    info.append(el("div", "err", (o.errorMessage || o.errorCode || "") + (o.paymentId ? "  [payment " + o.paymentId + "]" : "")));
  }
  const acts = el("div", "acts");
  for (const a of actionsFor(o)) {
    const b = el("button", "btn sm " + a.cls, a.label);
    b.onclick = () => busy(b, null, async () => {
      try { await a.run(); } catch (e) { handleServerError(e); }
    });
    acts.appendChild(b);
  }
  row.append(el("div", "code", o.pickupCode), info, acts);
  return row;
}

function actionsFor(o) {
  const list = [];
  if (o.status === "COMPLETED" && !o.collectedAt) {
    list.push({ label: "Handed over", cls: "go", run: () => handOver(o) });
  }
  if (o.status === "FAILED" && o.paidAt && !o.collectedAt) {
    if (o.fileKept) {
      list.push({ label: "Print again", cls: "", run: async () => {
        if (!confirm("Check the printer tray first: look for a page with \"Pickup " + o.pickupCode +
                     "\" in its corner. Nothing there? Print it again?")) return;
        await server("POST", "orders/" + o.id + "/print-again");
        toast(o.pickupCode + " will print again");
        refreshCounter();
      } });
    }
    list.push({ label: "Refunded / done", cls: "ghost", run: async () => {
      if (!confirm("Mark " + o.pickupCode + " as handled (printed by hand or refunded in Razorpay)?")) return;
      await server("POST", "orders/" + o.id + "/collected");
      refreshCounter();
    } });
  }
  if (o.status === "QUEUED") {
    list.push({ label: "Cancel", cls: "ghost", run: async () => {
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
  const sheets = (o.printPages || o.pageCount || 0) * o.copies;
  const canHand = o.status === "COMPLETED" && !o.collectedAt;
  const c = el("div", "found" + (canHand ? "" : o.status === "FAILED" ? " bad" : " warn"));
  const what = el("div", "what");
  const headline = canHand ? "Ready on " + (o.printerName || "the printer")
    : o.collectedAt ? "Already handed over " + when(o.collectedAt)
    : (STATUS[o.status] || [o.status])[0] + (o.printerName ? " on " + o.printerName : "");
  what.append(el("b", null, headline),
    el("span", null, o.fileName + " · " + pagesText(o) + " × " + o.copies + " · " + (o.color ? "Colour" : "B/W")));
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
function typeOf(p) { return !p.supportsColor ? "bw" : p.acceptsBw ? "color" : "coloronly"; }
function statusBadge(s) {
  const t = (s || "Normal").toLowerCase();
  if (t === "normal") return ["Ready", "ready"];
  if (t.includes("paperout")) return ["Paper out", "work"];
  if (t.includes("offline")) return ["Offline", "stop"];
  if (t.includes("error") || t.includes("jam")) return ["Error", "stop"];
  return [s, "work"];
}

async function loadPrinterEditor(boxId) {
  const box = $(boxId);
  box.innerHTML = "";
  box.appendChild(el("div", "empty", "Scanning the printers on this PC…"));
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
      rows.push({ w: { name: m.windowsPrinterName, missing: true }, existing: m, use: true, name: m.name, type: typeOf(m) });
    }
  }
  box.innerHTML = "";
  if (!rows.length) {
    box.appendChild(el("div", "notice work", "Windows has no printers on this PC. Install the printer (Settings → Printers & scanners → Add), then press Scan again."));
  }
  state.editors[boxId] = rows.map(r => printerRow(box, r));
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
    top.append(el("span", "badge " + cls, st), el("span", "badge", w.color ? "Colour printer" : "Black & white"));
    if (w.connection) top.append(el("span", "badge", w.connection));
    if (w.virtual) top.append(el("span", "badge work", "Virtual: saves a file, no paper"));
  }
  if (r.existing) top.append(el("span", "badge ready", "In use"));
  body.append(top);
  if (w.driver) body.append(el("div", "facts", w.driver + (w.port ? " · " + w.port : "")));

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
    const mk = (label, color) => {
      const b = el("button", "btn ghost sm", label);
      b.onclick = () => busy(b, "Printing…", async () => {
        msg.className = "test-msg muted"; msg.textContent = "Sending a test page…";
        try {
          const res = await local("POST", "test-print", { printer: w.name, color });
          msg.className = "test-msg"; msg.style.color = "var(--lamp-ready)"; msg.textContent = res.message;
        } catch (e) {
          msg.className = "test-msg"; msg.style.color = "var(--lamp-stop)"; msg.textContent = e.message;
        }
      });
      return b;
    };
    tests.append(mk("Test B/W", false));
    if (w.color) tests.append(mk("Test colour", true));
  }
  edit.append(nameField, typeField, tests);
  body.append(edit, msg);
  row.append(check, body);
  check.onchange = () => {
    row.classList.toggle("use", check.checked);
    if (check.checked && !name.value.trim()) {
      const used = (state.editors[box.id] || []).filter(x => x.check.checked).length;
      name.value = "Printer " + Math.max(1, used);
    }
  };
  box.appendChild(row);
  return { w, existing: r.existing, check, name, type };
}

/** Creates, changes or removes printers on the server so they match the ticks. Returns how many are used. */
async function savePrinters(boxId) {
  const rows = state.editors[boxId] || [];
  let used = 0;
  for (const r of rows) {
    const use = r.check.checked;
    const t = r.type.value;
    const body = { name: r.name.value.trim() || r.w.name, windowsPrinterName: r.w.name,
                   supportsColor: t !== "bw", acceptsBw: t !== "coloronly" };
    if (use) used++;
    if (use && !r.existing) {
      await server("POST", "printers", Object.assign({ agentId: state.local.agentId }, body));
    } else if (use && r.existing) {
      const e = r.existing;
      if (e.name !== body.name || e.supportsColor !== body.supportsColor || e.acceptsBw !== body.acceptsBw) {
        await server("PUT", "printers/" + e.id, body);
      }
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
    toast("Printers saved", "ok");
    await loadPrinterEditor("printerEditor");
  } catch (e) {
    showError("printersError", e.message);
  }
});
document.querySelectorAll("[data-scan]").forEach(b => {
  b.onclick = () => loadPrinterEditor($("setup").classList.contains("hidden") ? "printerEditor" : "suPrinterList");
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
