/*
 * XeoGo - the staff counter in a browser (counter.html). The Station
 * app on the Xerox PC has the same counter built in; this page is for a
 * second screen. Kept in its own file so the page needs no inline script.
 *
 * Handing over needs no pickup code: a student who opens their paid order at
 * the counter shows up at the top, with a picture of each file's first sheet.
 */
"use strict";
const API = (window.CONFIG && window.CONFIG.apiBase || "").replace(/\/+$/, "");
const $ = (id) => document.getElementById(id);
const SESSION_KEY = "campusprint.counter.session";   // the sign-in token (this tab only); the password itself is not kept
const PW_KEY = "campusprint.counter";                // only against a server older than the sign-in tokens
const state = { view: "active", find: "", timer: null, settingsLoaded: false, mode: "", pasteResult: "", pictures: new Map(),
                staff: null, center: "" };

function rupees(paise) {
  if (paise == null) return "-";
  return "\u20B9" + (paise % 100 === 0 ? String(paise / 100) : (paise / 100).toFixed(2));
}
function when(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) +
    (d.toDateString() === new Date().toDateString() ? "" : " " + d.toLocaleDateString());
}
function showError(msg) {
  $("error").textContent = msg || "";
  $("error").classList.toggle("hidden", !msg);
}

function authHeaders() {
  const session = sessionStorage.getItem(SESSION_KEY);
  return session ? { "X-Counter-Session": session } : { "X-Counter-Password": sessionStorage.getItem(PW_KEY) || "" };
}

async function api(method, path, body) {
  const headers = authHeaders();
  if (body != null) headers["Content-Type"] = "application/json";
  let res;
  try {
    res = await fetch(API + path, { method, headers, body: body == null ? undefined : JSON.stringify(body) });
  } catch (e) {
    throw new Error("Cannot reach the backend at " + API + ". Is it running?");
  }
  if (res.status === 401) {
    const signedIn = !!sessionStorage.getItem(SESSION_KEY);
    signOut(signedIn ? "Please sign in again." : "Wrong password.");
    throw new Error(signedIn ? "Please sign in again." : "Wrong password.");
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { /* not JSON */ }
  if (!res.ok) throw new Error((data && data.message) || ("Error " + res.status));
  return data;
}

/* ---------------- sign in ---------------- */
async function signIn() {
  if ($("password").value) {
    // The password goes to the server once and is swapped for a sign-in token.
    sessionStorage.removeItem(SESSION_KEY);
    let res;
    try {
      res = await fetch(API + "/api/v1/counter/session", { method: "POST", headers: { "X-Counter-Password": $("password").value } });
    } catch (e) {
      showError("Cannot reach the backend at " + API + ". Is it running?");
      return;
    }
    if (res.ok) {
      sessionStorage.setItem(SESSION_KEY, (await res.json()).token);
      sessionStorage.removeItem(PW_KEY);
    } else if (res.status === 404 || res.status === 405) {
      sessionStorage.setItem(PW_KEY, $("password").value);          // a server from before sign-in tokens
    } else {
      let data = null;
      try { data = await res.json(); } catch (e) { /* not JSON */ }
      showError(res.status === 401 ? "Wrong password." : (data && data.message) || ("Error " + res.status));
      return;
    }
    $("password").value = "";
  }
  try {
    await refresh();
    $("loginBox").classList.add("hidden");
    $("mainBox").classList.remove("hidden");
    showError(null);
    clearInterval(state.timer);
    state.timer = setInterval(() => refresh().catch(e => showError(e.message)), 3000);
  } catch (e) {
    showError(e.message);
  }
}
function signOut(msg) {
  clearInterval(state.timer);
  sessionStorage.removeItem(PW_KEY);
  sessionStorage.removeItem(SESSION_KEY);
  $("mainBox").classList.add("hidden");
  $("loginBox").classList.remove("hidden");
  $("password").value = "";
  showError(msg || null);
}

/* ---------------- screen ---------------- */
async function refresh() {
  const staffView = state.view === "staff" && !state.find;
  $("staffBox").classList.toggle("hidden", !staffView);
  $("orders").classList.toggle("hidden", staffView);
  if (staffView) {
    const [summary, staff] = await Promise.all([api("GET", "/api/v1/counter/summary"), api("GET", "/api/v1/counter/staff")]);
    renderSummary(summary);
    state.center = summary.centerName;
    $("hint").classList.add("hidden");
    renderStaff(staff);
    showError(null);
    return;
  }
  if (state.view === "payments" && !state.find) {
    const [summary, payments] = await Promise.all([api("GET", "/api/v1/counter/summary"), api("GET", "/api/v1/counter/payments")]);
    renderSummary(summary);
    // Do not redraw while staff type a bank message.
    if (!(document.activeElement && document.activeElement.tagName === "TEXTAREA" && document.activeElement.value)) {
      renderPayments(payments);
    }
    showError(null);
    return;
  }
  const [summary, orders] = await Promise.all([
    api("GET", "/api/v1/counter/summary"),
    api("GET", "/api/v1/counter/orders?view=" + (state.view === "payments" || state.view === "staff" ? "all" : state.view) +
      (state.find ? "&q=" + encodeURIComponent(state.find) : ""))
  ]);
  renderSummary(summary);
  renderOrders(orders);
  showError(null);
}

/* ---------------- pictures of the files (made by the Xerox PC while printing) ---------------- */
/** A small picture of a file's first sheet; click to see it large next to the student's phone. */
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
    b.onclick = () => zoom(url, d);
  };
  if (state.pictures.has(d.id)) {
    show(state.pictures.get(d.id));
  } else if (d.hasPreview) {
    fetch(API + "/api/v1/counter/documents/" + d.id + "/preview", { headers: authHeaders() })
      .then(r => (r.ok ? r.blob() : null))
      .then(blob => {
        if (!blob || !/^image\//.test(blob.type)) return;
        const url = URL.createObjectURL(blob);
        if (state.pictures.size > 300) { for (const u of state.pictures.values()) URL.revokeObjectURL(u); state.pictures.clear(); }
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

/** Students who opened their paid order at the counter: hand over without any code. */
function renderAtCounter(list) {
  const box = $("atCounter");
  box.classList.toggle("hidden", !list.length);
  const key = JSON.stringify(list.map(o => [o.id, o.status, (o.documents || []).map(d => [d.id, d.status, d.hasPreview])]));
  if (box.dataset.key === key) return;                 // nothing changed: do not redraw under the mouse
  const before = (box.dataset.key && JSON.parse(box.dataset.key).map(x => x[0])) || [];
  box.dataset.key = key;
  box.innerHTML = "";
  if (!list.length) return;
  const h = el("h2");
  h.append(el("span", "lamp ready"), "At the counter now (" + list.length + ")");
  box.append(h, el("p", "sub", "These students opened their order here. Their phone shows the same pictures: give them those pages, then press Handed over."));
  for (const o of list) box.append(orderRow(o, true));
  if (list.some(o => !before.includes(o.id))) chime();
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
  } catch (e) { /* no sound here: the green box still shows */ }
}

function renderSummary(s) {
  $("centerName").textContent = s.centerName;
  $("demoBanner").classList.toggle("hidden", s.paymentMode !== "demo");
  $("nActive").textContent = s.waiting + s.printing;
  $("nReady").textContent = s.ready;
  $("nProblems").textContent = s.problems;
  $("tabProblems").classList.toggle("alert", s.problems > 0);
  state.mode = s.paymentMode;
  $("tabPayments").classList.toggle("hidden", s.paymentMode !== "upi");
  const [vk, vt] = s.paymentMode === "upi" ? verifierLine(s.upi) : ["ok", ""];
  $("verifierBanner").classList.toggle("hidden", !(s.paymentMode === "upi" && vk === "stop"));
  $("verifierBanner").textContent = vt;
  $("nPay").textContent = s.paymentsToCheck || 0;
  $("tabPayments").classList.toggle("alert", (s.paymentsToCheck || 0) > 0);
  setStampUi(s.stampCode !== false);
  renderAtCounter(s.atCounter || []);

  const pcOnline = s.pcs.some(p => p.online);
  $("pcStatus").innerHTML = '<span class="lamp ' + (pcOnline ? "ready" : "stop") + '"></span> ' +
    (s.pcs.length === 0 ? "No PC enrolled (seed.sql step 3)" : pcOnline ? "Xerox PC online" : "Xerox PC OFFLINE");

  const box = $("printers");
  box.innerHTML = "";
  if (s.printers.length === 0) {
    box.innerHTML = '<div class="notice">No printers set up yet. Add them on the Printers screen of XeoGo Station.</div>';
  }
  for (const p of s.printers) {
    const lamp = !p.enabled ? "" : p.status === "READY" ? "ready" : p.status === "ERROR" ? "work" : "stop";
    const words = !p.enabled ? "Switched off" : p.status === "READY" ? "Ready" : p.status === "ERROR" ? "Needs attention"
      : p.status === "MISSING" ? "Not found in Windows" : "Offline";
    const card = document.createElement("div");
    card.className = "printer" + (p.enabled ? "" : " off");
    card.innerHTML =
      '<div class="row"><span class="lamp ' + lamp + '"></span><span class="name"></span>' +
      '<span class="small muted">' + (p.supportsColor ? "Colour" : "B/W") + '</span></div>' +
      '<div class="detail"></div>' +
      '<label class="switch"><input type="checkbox"> Taking orders</label>';
    card.querySelector(".name").textContent = p.name;
    card.querySelector(".detail").textContent = words + (p.statusDetail ? " - " + p.statusDetail : "");
    const cb = card.querySelector("input");
    cb.checked = p.enabled;
    cb.onchange = async () => {
      try { await api("POST", "/api/v1/counter/printers/" + p.id + "/enabled", { enabled: cb.checked }); refresh(); }
      catch (e) { showError(e.message); cb.checked = !cb.checked; }
    };
    box.appendChild(card);
  }

  if (!state.settingsLoaded) {
    $("setName").value = s.centerName;
    $("setBw").value = s.priceBwPaise / 100;
    $("setColor").value = s.priceColorPaise / 100;
    state.settingsLoaded = true;
  }
}

const STATUS_WORDS = {
  QUEUED: "Waiting for a printer", PRINTING: "Printing", CLAIMED: "Starting", DOWNLOADING: "Starting",
  SUBMITTED: "Printing", COMPLETED: "Printed", FAILED: "Problem", CANCELLED: "Cancelled",
  EXPIRED: "Not paid (expired)", AWAITING_UPLOAD: "Adding files", AWAITING_PAYMENT: "Not paid yet",
  UPLOADING: "Uploading", READY: "Not paid yet", REJECTED: "Refused"
};

function renderOrders(list) {
  const box = $("orders");
  box.innerHTML = "";
  $("hint").classList.toggle("hidden", !(state.view === "ready" || state.find));
  if (!list.length) {
    box.append(el("div", "empty", state.find ? "No paid order has a file or number like “" + state.find + "”." : "Nothing here right now."));
    return;
  }
  for (const o of list) box.appendChild(orderRow(o, false));
}

/** One order: pictures of its files, what to hand over, and what staff can do. atCounter: the student is here. */
function orderRow(o, atCounter) {
  const row = document.createElement("div");
  row.className = "order";
  const docs = o.documents || [];
  const pics = el("div", "pics");
  for (const d of docs.filter(x => x.status !== "CANCELLED")) pics.append(picture(d, atCounter));
  const mid = document.createElement("div");
  const meta = el("div", "meta");
  const lamp = o.status === "COMPLETED" ? "ready" : (o.status === "FAILED" ? "stop" : "work");
  meta.append(el("span", "lamp " + lamp), " " +
    (o.collectedAt ? "Handed over " + when(o.collectedAt)
      : o.staffName && o.status === "AWAITING_PAYMENT" ? "Not sent yet" : STATUS_WORDS[o.status] || o.status) + " · ");
  // A college staff member's order: whose it is, and that nothing was paid.
  if (o.staffName) meta.append(el("span", "staff-tag", "Staff · free"), " " + o.staffName + " · ");
  meta.append(docs.length + (docs.length === 1 ? " file" : " files") +
    " · " + (o.totalSheets || 0) + (o.totalSheets === 1 ? " sheet" : " sheets") + " · " +
    (o.staffName ? (o.staffPages || 0) + " free pages" : rupees(o.amountPaise)) + " · " +
    (o.paidAt ? (o.staffName ? "sent " : "paid ") + when(o.paidAt) : when(o.createdAt)) +
    (o.completedAt && !o.collectedAt ? " · ready " + when(o.completedAt) : "") +
    (o.refundDuePaise ? " · REFUND DUE " + rupees(o.refundDuePaise) : ""));
  const docBox = el("div", "docs");
  const err = el("div", "err");
  mid.append(meta, docBox, err, el("div", "no", "Order " + o.pickupCode));
  for (const d of docs) {
    const r = document.createElement("div");
    r.className = "doc";
    const title = document.createElement("b");
    title.textContent = d.position + ". " + d.fileName;
    const dm = document.createElement("div");
    dm.className = "meta";
    const pages = d.fileType === "PDF" ? (d.pages ? "pages " + d.pages.replace(/,/g, ", ") + " of " + d.pageCount
      : (d.pageCount || 0) + " pages") : "picture";
    dm.textContent = (d.noPrinter ? "NO PRINTER CAN DO THIS NOW" : (STATUS_WORDS[d.status] || d.status) +
      (d.printerName ? " on " + d.printerName : "")) + " · " + pages + " · " + d.settingsText +
      " × " + d.copies + " · " + (d.sheets || 0) * d.copies + " sheets";
    r.append(title, dm);
    if (d.status === "FAILED" && d.errorMessage) {
      const e = document.createElement("div");
      e.className = "err";
      e.textContent = d.errorMessage;
      r.append(e);
    }
    const acts = document.createElement("div");
    acts.className = "actions";
    const add = (label, fn) => {
      const b = document.createElement("button");
      b.className = "btn small ghost"; b.textContent = label;
      b.onclick = async () => {
        b.disabled = true;
        try { await fn(); await refresh(); } catch (e) { showError(e.message); b.disabled = false; }
      };
      acts.appendChild(b);
    };
    if (d.status === "FAILED" && o.paidAt && !o.collectedAt && d.fileKept) {
      add("Print this again", () => {
        if (!confirm("Check the printer tray first. Nothing there? Print \"" + d.fileName + "\" again?")) return Promise.resolve();
        return api("POST", "/api/v1/counter/documents/" + d.id + "/print-again");
      });
    }
    if ((d.status === "QUEUED" && d.noPrinter) || (d.status === "FAILED" && o.paidAt && !o.collectedAt)) {
      add("Cancel this file", () => {
        if (!confirm("Cancel \"" + d.fileName + "\"? " + (o.staffName ? "Its pages go back to " + o.staffName + "'s free pages."
              : "Refund " + rupees(d.amountPaise) + " " + refundWhere() + "."))) return Promise.resolve();
        return api("POST", "/api/v1/counter/documents/" + d.id + "/cancel");
      });
    }
    if (acts.children.length) r.append(acts);
    docBox.append(r);
  }
  if (o.status === "COMPLETED" && !o.collectedAt) {
    const sheets = document.createElement("div");
    sheets.className = "sheets";
    sheets.textContent = (o.totalSheets || 0) + " sheets to hand over";
    docBox.after(sheets);
  }
  if ((o.status === "FAILED" || o.status === "CANCELLED") && !docs.some(d => d.errorMessage)) {
    err.textContent = (o.errorMessage || o.errorCode || "") + (o.paymentId ? "  [payment " + o.paymentId + "]" : "");
  }
  const actions = el("div", "actions");
  const add = (label, cls, fn) => {
    const b = document.createElement("button");
    b.className = "btn small " + cls; b.textContent = label;
    b.onclick = async () => {
      b.disabled = true;
      try { await fn(); await refresh(); } catch (e) { showError(e.message); b.disabled = false; }
    };
    actions.appendChild(b);
  };
  const failed = docs.filter(d => d.status === "FAILED");
  if (o.status === "COMPLETED" && !o.collectedAt) {
    add("Handed over", "", () => {
      // The usual way: the student's own phone says "I'm at the counter". Without that, staff make sure themselves.
      if (!o.arrivedAt && !confirm("This student has not tapped “I’m at the counter” on their phone.\n\n" +
          "Hand over only if their phone shows this same order (same files, order " + o.pickupCode + ").\n\nHand over now?")) {
        return Promise.resolve();
      }
      return api("POST", "/api/v1/counter/orders/" + o.id + "/collected");
    });
  }
  if ((o.status === "FAILED" || failed.length || o.refundDuePaise) && o.paidAt && !o.collectedAt) {
    add(o.staffName ? "Done" : "Refunded / done", "ghost", () => {
      if (!confirm("Mark order " + o.pickupCode + " as handled (printed by hand" +
                   (o.staffName ? " or handed over" : " or refunded " + refundWhere()) + ")?")) return Promise.resolve();
      return api("POST", "/api/v1/counter/orders/" + o.id + "/collected");
    });
  }
  if (o.status === "AWAITING_PAYMENT" && state.mode === "upi" && !o.staffName) {
    for (const a of paymentActions(o)) add(a.label, a.cls, a.run);
  }
  if (o.status === "QUEUED") {
    add("Cancel", "ghost", () => {
      if (!confirm("Cancel order " + o.pickupCode + "? " + (o.staffName ? "Its pages go back to " + o.staffName + "'s free pages."
            : "You must refund the student " + refundWhere() + "."))) return Promise.resolve();
      return api("POST", "/api/v1/counter/orders/" + o.id + "/cancel");
    });
  }
  row.append(pics, mid, actions);
  return row;
}

/* ---------------- XeoGo Pay: UPI payments ---------------- */
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

function refundWhere() {
  return state.mode === "upi" ? "by UPI from the shop's account" : "in the Razorpay dashboard";
}

/** Staff saw the money in the bank / UPI app, or could not find it. */
function paymentActions(o) {
  return [
    { label: "Money received", cls: "", run: () => {
      let ref = o.paymentClaimRef;
      if (ref) {
        if (!confirm("Did " + rupees(o.amountPaise) + " with reference " + groupRef(ref) +
                     " arrive in the bank or UPI app? The order prints straight away.")) return Promise.resolve();
      } else {
        ref = prompt("Did " + rupees(o.amountPaise) + " for " + o.pickupCode + " arrive in the bank or UPI app?\n" +
                     "Type its 12-digit UPI reference number if you see it (or leave it empty), then OK.", "");
        if (ref === null) return Promise.resolve();
      }
      return api("POST", "/api/v1/counter/orders/" + o.id + "/payment/approve", { reference: ref || null });
    } },
    { label: "Not found", cls: "ghost", run: () => {
      const why = prompt("Tell the student why (optional), for example: no payment of " + rupees(o.amountPaise) + " today", "");
      if (why === null) return Promise.resolve();
      return api("POST", "/api/v1/counter/orders/" + o.id + "/payment/reject", { reason: why || null });
    } }
  ];
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}
function groupRef(r) { return /^\d{12}$/.test(r || "") ? r.slice(0, 4) + " " + r.slice(4, 8) + " " + r.slice(8) : (r || ""); }
function actionButton(label, cls, fn) {
  const b = el("button", "btn small " + cls, label);
  b.onclick = async () => {
    b.disabled = true;
    try { await fn(); await refresh(); } catch (e) { showError(e.message); b.disabled = false; }
  };
  return b;
}

function renderPayments(p) {
  const box = $("orders");
  box.innerHTML = "";
  $("hint").classList.add("hidden");
  const u = p.upi || {};
  const info = el("div", "pay-info");
  info.append("Students pay ", el("b", null, u.payeeVpa || "-"), " (" + (u.payeeName || "") + ") from any UPI app. ",
    u.autoConfirm ? "Bank messages from the shop's phone confirm payments by themselves; below are only the ones they could not."
                  : "Check each payment in the bank or UPI app, then press Money received: only then it prints.",
    " Today: " + (p.today.paidOrders || 0) + " paid, " + rupees(p.today.paidPaise || 0) +
    (u.autoConfirm ? " (" + (p.today.confirmedByBank || 0) + " confirmed by the bank)" : "") + ".");
  box.append(info);
  const [vk, vt] = verifierLine(u);
  box.append(el("div", "notice" + (vk === "stop" ? " stop" : ""), vt));

  box.append(el("h3", "pay-h", "Students who say they paid (" + p.toCheck.length + ")"));
  if (!p.toCheck.length) box.append(el("div", "empty", "Nothing to check."));
  for (const o of p.toCheck) {
    const row = el("div", "pay-order");
    const mid = el("div");
    mid.append(el("div", "amount", rupees(o.amountPaise)));
    const meta = el("div", "meta");
    meta.append(o.paymentClaimRef ? "Reference " : "No reference typed",
      o.paymentClaimRef ? el("span", "ref", groupRef(o.paymentClaimRef)) : "",
      " · said paid " + when(o.paymentClaimedAt) + " · " + (o.documents || []).length + " file(s), " + (o.totalSheets || 0) + " sheets");
    mid.append(meta);
    for (const a of (p.hints[o.id] || [])) {
      mid.append(el("div", "hintline", a.trusted === false
        ? "A message " + when(a.receivedAt) + " says " + rupees(a.amountPaise) + ", but NOT from the bank: do not count it. Look in the bank or UPI app."
        : "Bank message " + when(a.receivedAt) + ": " + rupees(a.amountPaise) +
          (a.refs.length ? " · ref " + a.refs.map(groupRef).join(", ") : "") + " (same amount, not matched automatically)"));
    }
    const acts = el("div", "actions");
    for (const a of paymentActions(o)) acts.append(actionButton(a.label, a.cls, a.run));
    row.append(el("div", "code", o.pickupCode), mid, acts);
    box.append(row);
  }

  const paste = el("div", "paste");
  paste.append(el("b", null, "Paste a bank SMS"),
    el("span", "small muted", "Copy the bank's \"credited\" message from the shop's phone and paste it here: it pays the order it proves, exactly like a forwarded one."));
  const ta = document.createElement("textarea");
  ta.placeholder = "e.g. A/C X1234 credited by Rs.20.01 ... Ref No 627312345678";
  const res = el("span", "small");
  const go = el("button", "btn small", "Check this message");
  go.onclick = async () => {
    go.disabled = true;
    try {
      const r = await api("POST", "/api/v1/counter/payments/alerts", { text: ta.value });
      state.pasteResult = r.kind !== "CREDIT" ? "Not a money-received message." : r.paidOrder
        ? "Paid order " + r.paidOrder + " (" + rupees(r.amountPaise) + ")." : r.duplicate ? "Already pasted before."
        : rupees(r.amountPaise) + " received, but no order waits for exactly this amount.";
      ta.value = "";
      await refresh();
    } catch (e) { showError(e.message); go.disabled = false; }
  };
  res.textContent = state.pasteResult || "";
  paste.append(ta, go, res);
  box.append(el("h3", "pay-h", "Bank messages"), paste);

  if (p.alerts.length) {
    const t = el("table", "alerts");
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
          msg.append(actionButton("This is our bank (" + a.senderKey + ")", "ghost", () => {
            if (!confirm("Is " + a.senderKey + " the name your bank's SMS come from? Check it in the SMS list on the shop's phone.\n\n" +
                "After this, every SMS from " + a.senderKey + " that says money came in confirms an order by itself.")) return Promise.resolve();
            return api("POST", "/api/v1/counter/payments/alerts/" + a.id + "/trust-sender");
          }));
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
    box.append(el("div", "small-list", p.waiting.map(o => o.pickupCode + " " + rupees(o.amountPaise) +
      " (" + when(o.paymentStartedAt) + ")").join(" · ")));
  }
  if (p.paid.length) {
    box.append(el("h3", "pay-h", "Paid by UPI, last 24 hours (" + p.paid.length + ")"));
    box.append(el("div", "small-list", p.paid.map(o => o.pickupCode + " " + rupees(o.amountPaise) + " " + when(o.paidAt) +
      (o.paymentVerifiedBy === "counter" ? " (staff)" : " (bank)")).join(" · ")));
  }
  if ((p.senders || []).length) {
    box.append(el("h3", "pay-h", "Bank SMS senders you confirmed"));
    const line = el("div", "small-list");
    for (const sender of p.senders) {
      line.append(sender + " ", actionButton("Remove", "ghost", () => {
        if (!confirm("Stop counting SMS from " + sender + "?")) return Promise.resolve();
        return api("DELETE", "/api/v1/counter/payments/senders/" + encodeURIComponent(sender));
      }), " ");
    }
    box.append(line);
  }
}

/* The "Print the order number on pages" tick: untick for a while if a student wants only their own file on the paper. */
function setStampUi(on) {
  $("stampToggle").checked = on;
  $("stampBox").classList.toggle("off", !on);
  $("stampHint").textContent = on
    ? "A small label in the corner of each file's first page, so staff can tell the orders apart in the tray."
    : "OFF: orders print with nothing added. Tick again when done.";
}
$("stampToggle").onchange = async () => {
  const on = $("stampToggle").checked;
  try { await api("PUT", "/api/v1/counter/settings", { stampCode: on }); setStampUi(on); }
  catch (e) { setStampUi(!on); showError(e.message); }
};

async function saveSettings() {
  const bw = Math.round(parseFloat($("setBw").value) * 100);
  const color = Math.round(parseFloat($("setColor").value) * 100);
  if (!(bw >= 0) || !(color >= 0)) { $("settingsMsg").textContent = "Enter both prices."; return; }
  try {
    await api("PUT", "/api/v1/counter/settings", { centerName: $("setName").value, priceBwPaise: bw, priceColorPaise: color });
    $("settingsMsg").textContent = "Saved.";
    state.settingsLoaded = false;
    refresh();
  } catch (e) { $("settingsMsg").textContent = e.message; }
}

/* ---------------- staff IDs: free printing for college staff ---------------- */
/* The Xerox center makes each ID here: a username and a password, nothing else. The server makes the
 * password and shows it once; this page passes it on (copy or print a slip) and never keeps it. */
function dayWords(isoDate) {
  const d = new Date(isoDate + "T00:00:00");
  return isNaN(d) ? isoDate : d.toLocaleDateString([], { day: "numeric", month: "long" });
}

function renderStaff(o) {
  state.staff = o;
  if (!state.staffTyping) $("staffPages").value = o.monthlyPages;
  $("staffColor").checked = o.colorAllowed;
  $("staffSite").textContent = o.site ? "Staff sign in at " + o.site + " (or in the XeoGo Staff app)." : "";
  $("staffHead").textContent = "Staff IDs (" + o.accounts.length + ") · " + o.month + " · free pages start again on " + dayWords(o.resetsOn);
  const box = $("staffList");
  box.innerHTML = "";
  if (!o.accounts.length) { box.append(el("div", "empty", "No staff IDs yet. Make the first one above.")); return; }
  for (const a of o.accounts) box.append(staffRow(a));
}

function staffRow(a) {
  const row = el("div", "staff-row" + (a.active ? "" : " off"));
  const who = el("div");
  who.append(el("b", null, a.name), el("span", "user", a.username));
  if (!a.active) who.append(" ", el("span", "staff-tag", "Switched off"));
  if (a.waiting) who.append(" ", el("span", "staff-tag", "Wrong passwords: waiting"));
  const use = el("div", "use");
  const bar = el("div", "bar");
  const fill = el("i", a.leftPages <= 0 ? "full" : null);
  fill.style.width = (a.monthlyPages > 0 ? Math.min(100, Math.round(a.usedPages * 100 / a.monthlyPages)) : (a.usedPages ? 100 : 0)) + "%";
  bar.append(fill);
  use.append(a.usedPages + " of " + a.monthlyPages + " pages used" + (a.ownMonthlyPages != null ? " (own number)" : ""), bar,
    el("div", "small muted", a.lastPrintAt ? "last print " + when(a.lastPrintAt) : "has not printed yet"));
  const acts = el("div", "actions");
  const act = (label, fn) => acts.append(actionButton(label, "ghost", fn));
  act("New password", async () => {
    if (!confirm("Make a new password for " + a.name + "?\n\nThe old password stops working, and every phone or computer " +
                 "signed in with it is signed out.")) return;
    showSlip(await api("POST", "/api/v1/counter/staff/" + a.id + "/password"), "New password");
  });
  act("Pages", async () => {
    const typed = prompt("Free pages per month for " + a.name + ".\nLeave empty for the usual " + state.staff.monthlyPages + ".",
      a.ownMonthlyPages != null ? String(a.ownMonthlyPages) : "");
    if (typed === null) return;
    const t = typed.trim();
    if (t !== "" && !/^\d{1,6}$/.test(t)) throw new Error("Type a number of pages, or leave it empty.");
    await api("PUT", "/api/v1/counter/staff/" + a.id, t === "" ? { usualPages: true } : { monthlyPages: parseInt(t, 10) });
  });
  act("Rename", async () => {
    const typed = prompt("Name of this staff member (the username " + a.username + " stays):", a.name);
    if (typed === null || !typed.trim() || typed.trim() === a.name) return;
    await api("PUT", "/api/v1/counter/staff/" + a.id, { name: typed.trim() });
  });
  act(a.active ? "Switch off" : "Switch on", async () => {
    if (a.active && !confirm("Switch off " + a.name + "'s staff ID?\n\nThey cannot sign in or print until you switch it on " +
                             "again. Nothing is deleted.")) return;
    await api("PUT", "/api/v1/counter/staff/" + a.id, { active: !a.active });
  });
  act("Remove", async () => {
    if (!confirm("Remove " + a.name + "'s staff ID (" + a.username + ") for good?\n\nWhat they already sent still prints. " +
                 "If they should only stop for a while, use Switch off instead.")) return;
    await api("DELETE", "/api/v1/counter/staff/" + a.id);
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
  const dl = el("dl");
  dl.append(el("dt", null, "Username"), el("dd", null, a.username), el("dt", null, "Password"), el("dd", "big", made.password));
  const site = state.staff && state.staff.site;
  const copy = el("button", "btn small ghost", "Copy");
  copy.onclick = async () => {
    try {
      await navigator.clipboard.writeText("XeoGo staff ID\nName: " + a.name + "\nUsername: " + a.username + "\nPassword: " +
        made.password + (site ? "\nSign in: " + site : ""));
      copy.textContent = "Copied";
    } catch (e) { copy.textContent = "Could not copy: write it down"; }
  };
  const print = el("button", "btn small ghost", "Print slip");
  print.onclick = () => {
    const p = $("printSlip");
    p.innerHTML = "";
    const list = el("dl");
    list.append(el("dt", null, "Name"), el("dd", null, a.name), el("dt", null, "Username"), el("dd", null, a.username),
      el("dt", null, "Password"), el("dd", null, made.password));
    p.append(el("h1", null, "XeoGo · staff ID"), el("p", "where", state.center || ""), list,
      el("p", "note", a.monthlyPages + " free pages every month. Sign in " + (site ? "at " + site + " or " : "") +
        "in the XeoGo Staff app. Keep this slip to yourself."));
    window.print();
  };
  const close = el("button", "btn small", "Done");
  close.onclick = () => { box.classList.add("hidden"); box.innerHTML = ""; $("printSlip").innerHTML = ""; };
  const acts = el("div", "actions");
  acts.append(copy, print, close);
  box.append(el("b", null, title + " · " + a.name), dl,
    el("p", null, "Give these to the staff member now: the password is not shown again. Forgotten later? Press New password " +
      "on their ID. Capitals and the dash do not matter when typing it."), acts);
}

$("staffMake").onclick = async () => {
  const name = $("staffName").value.trim();
  $("staffMadeNote").textContent = "";
  if (name.length < 2) { $("staffMadeNote").textContent = "Type the staff member's name."; $("staffName").focus(); return; }
  $("staffMake").disabled = true;
  try {
    const made = await api("POST", "/api/v1/counter/staff", { name, username: $("staffUsername").value.trim() || null });
    $("staffName").value = "";
    $("staffUsername").value = "";
    showSlip(made, "Staff ID made");
    await refresh();
  } catch (e) {
    $("staffMadeNote").textContent = e.message;
  } finally {
    $("staffMake").disabled = false;
  }
};
$("staffSave").onclick = async () => {
  const pages = parseInt($("staffPages").value, 10);
  if (!(pages >= 0 && pages <= 100000)) { $("staffSaved").textContent = "Type a number of pages (0 to 100000)."; return; }
  try {
    await api("PUT", "/api/v1/counter/staff/settings", { monthlyPages: pages, colorAllowed: $("staffColor").checked });
    $("staffSaved").textContent = "Saved.";
    state.staffTyping = false;
    await refresh();
  } catch (e) { $("staffSaved").textContent = e.message; }
};
$("staffPages").addEventListener("input", () => { state.staffTyping = true; $("staffSaved").textContent = ""; });
$("staffColor").onchange = async () => {
  const on = $("staffColor").checked;
  try { await api("PUT", "/api/v1/counter/staff/settings", { colorAllowed: on }); }
  catch (e) { $("staffColor").checked = !on; showError(e.message); }
};

/* ---------------- wiring ---------------- */
document.querySelectorAll(".tab").forEach(t => t.onclick = () => {
  document.querySelectorAll(".tab").forEach(x => x.classList.toggle("on", x === t));
  state.view = t.dataset.view;
  state.find = "";
  $("search").value = "";
  refresh().catch(e => showError(e.message));
});
let findTimer = null;
$("search").addEventListener("input", () => {
  const text = $("search").value.trim();
  clearTimeout(findTimer);
  findTimer = setTimeout(() => {
    state.find = text.length >= 2 ? text : "";
    refresh().catch(e => showError(e.message));
  }, 300);
});
$("loginBtn").onclick = signIn;
$("password").addEventListener("keydown", (e) => { if (e.key === "Enter") signIn(); });
$("saveSettings").onclick = saveSettings;
$("logout").onclick = () => signOut();

if (!API) showError("config.js has no apiBase. Open config.js and set it.");
if (sessionStorage.getItem(SESSION_KEY) || sessionStorage.getItem(PW_KEY)) {
  signIn();                                   // this tab is signed in already
} else {
  $("loginBox").classList.remove("hidden");
}
