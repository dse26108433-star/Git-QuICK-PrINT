/*
 * Campus Print - the printing rules, shared with the server and the Xerox PC.
 *
 * Everything here has a twin that decides what really prints, and a shared
 * test (spec/cases/*.json, run by spec/web-core.test.js) keeps them equal:
 *
 *   parsePages     backend PageRanges.java          spec/cases/page-ranges.json
 *   layout         agent Layout.java                spec/cases/layout.json
 *   plan, price    backend PrintPlan/Pricing.java   spec/cases/pricing.json
 *   canDo          backend PrinterRules.java, db printer_can_do()  spec/cases/printer-rules.json
 *   normalize      backend SettingsChecker.java
 *
 * The website uses them to show the right options, the live price and the
 * print preview; the server checks everything again before payment, and the
 * summary the student pays for shows the server's answer.
 *
 * No dependencies. Works in browsers (window.PrintCore) and in Node (require).
 */
(function (root) {
  "use strict";

  var MM = 72 / 25.4;
  var GAP = 3 * MM;
  var GRIDS = { 2: [[1, 2], [2, 1]], 4: [[2, 2]], 6: [[2, 3], [3, 2]], 9: [[3, 3]], 16: [[4, 4]] };
  var PAGES_PER_SHEET = [1, 2, 4, 6, 9, 16];
  var DEFAULT_MARGIN = 5;

  var FINISHING_GROUPS = ["STAPLE", "PUNCH", "BIND"];

  // ------------------------------------------------------------------ pages

  /**
   * Which pages print: "102", "333-390", "1-5, 8", "333-" (to the end).
   * Pages print in order, each once. Returns { ranges, count, spec } where
   * spec is the tidy form ("1-3,8") or null for every page. Throws an Error
   * whose message is written for the student.
   */
  function parsePages(text, total) {
    var t = String(text == null ? "" : text).trim().replace(/[–—]/g, "-").replace(/;/g, ",");
    if (!t) return { ranges: [[1, total]], count: total, spec: null };
    if (t.length > 200) throw new Error("The page list is too long. Use ranges like 10-50.");
    var parts = [];
    var raw = t.split(",");
    for (var i = 0; i < raw.length; i++) {
      var p = raw[i].trim();
      if (!p) continue;
      var m = p.match(/^(\d{1,6})(?:\s*-\s*(\d{1,6})?)?$/);
      if (!m) throw new Error("“" + p + "” is not a page number. Write pages like 5, 10-20 or 1-3, 8.");
      var from = Number(m[1]);
      var to = p.indexOf("-") < 0 ? from : (m[2] === undefined ? total : Number(m[2]));
      if (to < from) { var x = from; from = to; to = x; }
      if (from < 1) throw new Error("Pages start at 1.");
      if (to > total) throw new Error("Page " + to + " does not exist: this PDF has " + total + (total === 1 ? " page." : " pages."));
      parts.push([from, to]);
    }
    if (!parts.length) throw new Error("Write the pages you want, like 5 or 10-20.");
    parts.sort(function (a, b) { return a[0] - b[0]; });
    var merged = [];
    for (var j = 0; j < parts.length; j++) {
      var last = merged[merged.length - 1];
      if (last && parts[j][0] <= last[1] + 1) last[1] = Math.max(last[1], parts[j][1]);
      else merged.push(parts[j].slice());
    }
    var count = merged.reduce(function (n, r) { return n + r[1] - r[0] + 1; }, 0);
    var spec = merged.map(function (r) { return r[0] === r[1] ? String(r[0]) : r[0] + "-" + r[1]; }).join(",");
    return { ranges: merged, count: count, spec: count === total ? null : spec };
  }

  /** A set of page numbers (1-based) -> tidy spec, or null when it is every page. */
  function specFromPages(pages, total) {
    var list = Array.from(pages).filter(function (n) { return n >= 1 && n <= total; }).sort(function (a, b) { return a - b; });
    if (!list.length) return "";
    var out = [];
    var start = list[0], prev = list[0];
    for (var i = 1; i <= list.length; i++) {
      var n = list[i];
      if (n === prev + 1) { prev = n; continue; }
      out.push(start === prev ? String(start) : start + "-" + prev);
      start = prev = n;
    }
    return list.length === total ? null : out.join(",");
  }

  /** Spec (null = all, "" = none chosen yet) -> Set of page numbers. */
  function pagesFromSpec(spec, total) {
    var set = new Set();
    if (spec === "") return set;
    var r = parsePages(spec || "", total);
    r.ranges.forEach(function (x) { for (var n = x[0]; n <= x[1]; n++) set.add(n); });
    return set;
  }

  /** "1-3,8" -> "1–3, 8" for people. */
  function displaySpec(spec) {
    return spec ? spec.replace(/,/g, ", ").replace(/-/g, "–") : "";
  }

  // ------------------------------------------------------------------ layout

  /** Where pages go on the paper: same rules as the Xerox PC (Layout.java). Sizes in points, y up. */
  function layout(pages, o) {
    var out = [];
    if (!pages.length) return out;
    var n = Math.max(1, o.pagesPerSheet || 1);
    var count = n === 1 ? pages.length : Math.ceil(pages.length / n);
    for (var k = 0; k < count; k++) out.push(layoutSheet(k, pages.length, function (i) { return pages[i]; }, o));
    return out;
  }

  /** One sheet only (k from 0), asking sizeOf(i) just for the pages it needs. Same numbers as layout(). */
  function layoutSheet(k, pageCount, sizeOf, o) {
    var shortSide = Math.min(o.paperW, o.paperH), longSide = Math.max(o.paperW, o.paperH);
    var m = o.marginPt;
    var n = Math.max(1, o.pagesPerSheet || 1);
    if (n === 1) {
      var p = sizeOf(k);
      var landscape = o.orientation === "LANDSCAPE" || (o.orientation === "AUTO" && p.w > p.h);
      var sw = landscape ? longSide : shortSide, sh = landscape ? shortSide : longSide;
      var aw = sw - 2 * m, ah = sh - 2 * m;
      var s;
      if (o.scaling === "FILL") s = Math.max(aw / p.w, ah / p.h);
      else if (o.scaling === "ACTUAL") s = 1;
      else if (o.scaling === "CUSTOM") s = o.scalePercent / 100;
      else s = Math.min(aw / p.w, ah / p.h);
      var w = p.w * s, h = p.h * s;
      var x = o.center ? (sw - w) / 2 : m;
      var y = o.center ? (sh - h) / 2 : sh - m - h;
      return { w: sw, h: sh, cells: [{ page: k, x: x, y: y, w: w, h: h }],
               clip: o.scaling === "FILL" ? { x: m, y: m, w: aw, h: ah } : null };
    }
    var grids = GRIDS[n];
    if (!grids) throw new Error("Pages per sheet must be 1, 2, 4, 6, 9 or 16");
    var first = sizeOf(0);
    var orientations = o.orientation === "AUTO" ? [false, true] : [o.orientation === "LANDSCAPE"];
    var best = -1, W = 0, H = 0, cw = 0, ch = 0, cols = 1;
    orientations.forEach(function (land) {
      var ww = land ? longSide : shortSide, hh = land ? shortSide : longSide;
      grids.forEach(function (g) {
        var cellW = (ww - 2 * m - (g[0] - 1) * GAP) / g[0];
        var cellH = (hh - 2 * m - (g[1] - 1) * GAP) / g[1];
        var sc = Math.min(cellW / first.w, cellH / first.h);
        if (sc > best + 1e-9) { best = sc; W = ww; H = hh; cw = cellW; ch = cellH; cols = g[0]; }
      });
    });
    var cells = [];
    for (var j = 0; j < n && k * n + j < pageCount; j++) {
      var q = sizeOf(k * n + j);
      var col = j % cols, row = Math.floor(j / cols);
      var cellX = m + col * (cw + GAP);
      var cellY = H - m - row * (ch + GAP) - ch;
      var sc2 = Math.min(cw / q.w, ch / q.h);
      var ww2 = q.w * sc2, hh2 = q.h * sc2;
      cells.push({ page: k * n + j, x: cellX + (cw - ww2) / 2, y: cellY + (ch - hh2) / 2, w: ww2, h: hh2 });
    }
    return { w: W, h: H, cells: cells, clip: null };
  }

  /** Layout options from print settings and a paper (mm). */
  function layoutOptions(settings, paper, isPicture) {
    return {
      paperW: paper.widthMm * MM, paperH: paper.heightMm * MM,
      orientation: settings.orientation || "AUTO", marginPt: settings.marginMm * MM,
      scaling: settings.scaling || "FIT", scalePercent: settings.scalePercent || 100,
      pagesPerSheet: isPicture ? 1 : settings.pagesPerSheet || 1,
      center: isPicture ? settings.center !== false : true
    };
  }

  /**
   * A picture's size on paper at 100 % (points), as it looks: after the
   * camera's EXIF turn and the student's turn. Same as the Xerox PC.
   */
  function pictureSize(image, rotation) {
    var exifQuarter = image.exifOrientation >= 5 && image.exifOrientation <= 8;
    var userQuarter = ((rotation || 0) / 90) % 2 !== 0;
    var swap = exifQuarter !== userQuarter;
    var dpi = image.dpi > 0 ? image.dpi : 96;
    var w = (swap ? image.heightPx : image.widthPx) / dpi * 72;
    var h = (swap ? image.widthPx : image.heightPx) / dpi * 72;
    return { w: w, h: h };
  }

  // ------------------------------------------------------------------ paper and price

  function plan(printPages, s) {
    var perSheet = Math.max(1, s.pagesPerSheet || 1);
    var sides = Math.ceil(printPages / perSheet);
    var sheets = s.duplex && s.duplex !== "ONE_SIDED" ? Math.ceil(sides / 2) : sides;
    return { printPages: printPages, sides: sides, sheets: sheets };
  }

  /** Price of one document in paise; whole numbers, exactly as the server (Pricing.java). */
  function price(s, pl, priceBwPaise, priceColorPaise, rules) {
    rules = rules || {};
    var sizePct = (rules.paperSizePercent || {})[s.paperSize];
    var mediaPct = s.mediaType ? (rules.mediaTypePercent || {})[s.mediaType] : 100;
    var base = s.color ? priceColorPaise : priceBwPaise;
    var perSide = Math.floor((base * (sizePct == null ? 100 : sizePct) * (mediaPct == null ? 100 : mediaPct) + 5000) / 10000);
    var fin = rules.finishingPaise || {};
    var finishing = (s.staple ? fin.STAPLE || 0 : 0) + (s.punch ? fin.PUNCH || 0 : 0) + (s.bind ? fin.BIND || 0 : 0);
    return { perSide: perSide, finishing: finishing, amount: s.copies * (pl.sides * perSide + finishing) };
  }

  // ------------------------------------------------------------------ printers

  function requirements(s) {
    var finishing = [];
    if (s.staple) finishing.push("STAPLE_" + s.staple);
    if (s.punch) finishing.push("PUNCH_" + s.punch);
    if (s.bind) finishing.push("BIND_" + s.bind);
    return { color: !!s.color, paperSize: s.paperSize, duplex: s.duplex || "ONE_SIDED", finishing: finishing,
             mediaType: s.mediaType || null, borderless: s.marginMm === 0, quality: s.quality || "STANDARD" };
  }

  /** Same rule as the print queue: can this printer print a document that needs req? */
  function canDo(p, req) {
    var f = p.features || { paperSizes: ["A4"] };
    var sizes = f.paperSizes || ["A4"];
    if (!(req.color ? p.color : p.bw)) return false;
    if (req.paperSize != null && sizes.indexOf(req.paperSize) < 0) return false;
    if (req.duplex != null && req.duplex !== "ONE_SIDED" && !f.duplex) return false;
    var fin = f.finishing || [];
    if ((req.finishing || []).some(function (x) { return fin.indexOf(x) < 0; })) return false;
    if (req.mediaType != null && (f.mediaTypes || []).indexOf(req.mediaType) < 0) return false;
    if (req.borderless && !f.borderless) return false;
    return req.quality == null || req.quality === "STANDARD" || !!f.highQuality;
  }

  function anyCanDo(printers, req) {
    return printers.some(function (p) { return canDo(p, req); });
  }

  /** The website's printer list (shop.printing.printers) in the shape canDo expects. */
  function candidates(shop) {
    return ((shop && shop.printing && shop.printing.printers) || []).map(function (p) {
      return { id: p.id, name: p.name, online: p.online, color: p.color, bw: p.bw,
               features: { paperSizes: p.paperSizes, duplex: p.duplex, finishing: p.finishing,
                           mediaTypes: p.mediaTypes, borderless: p.borderless, highQuality: p.highQuality } };
    });
  }

  // ------------------------------------------------------------------ settings

  function defaults(isPicture) {
    return { copies: 1, color: false, pages: null, duplex: "ONE_SIDED", paperSize: "A4", orientation: "AUTO",
             scaling: "FIT", scalePercent: 100, pagesPerSheet: 1, marginMm: DEFAULT_MARGIN, rotation: 0,
             center: true, collate: true, staple: null, punch: null, bind: null, mediaType: null,
             quality: "STANDARD" };
  }

  /**
   * Tidies a document's settings the way the server does (SettingsChecker):
   * pictures have no page list or pages per sheet, PDFs are always centred and
   * never turned, several per sheet are always fitted, one copy is collated.
   * Returns { settings, plan, error } (error: words for the student, or null).
   */
  function normalize(raw, facts, limits, printers) {
    var pdf = facts.type === "PDF";
    var s = Object.assign(defaults(!pdf), raw || {});
    var error = null;
    var printPages = 1;
    if (pdf && s.pages === "") {
      error = "Choose at least one page to print.";
      printPages = 0;
    } else if (pdf) {
      try {
        var sel = parsePages(s.pages || "", facts.pageCount);
        s.pages = sel.spec;
        printPages = sel.count;
        if (printPages > limits.maxPages) {
          error = (sel.spec == null ? "This file has " : "You chose ") + printPages + " pages. Up to " +
            limits.maxPages + " pages can be printed from one document: choose the pages you need.";
        }
      } catch (e) {
        error = e.message;
        printPages = facts.pageCount;
      }
    } else {
      s.pages = null;
      s.pagesPerSheet = 1;
      s.rotation = (((s.rotation || 0) % 360) + 360) % 360;
    }
    if (!pdf && s.scaling === "FILL") { /* allowed */ } else if (s.scaling === "FILL") s.scaling = "FIT";
    if (pdf) { s.rotation = 0; s.center = true; }
    if (s.pagesPerSheet > 1) { s.scaling = "FIT"; s.scalePercent = 100; }
    if (s.scaling !== "CUSTOM") s.scalePercent = 100;
    if (!error && (s.copies < 1 || s.copies > limits.maxCopies)) error = "Choose between 1 and " + limits.maxCopies + " copies.";
    if (s.copies === 1 || s.staple || s.bind) s.collate = true;
    var pl = plan(printPages, s);
    if (!error && (s.staple || s.bind) && pl.sheets < 2) {
      error = (s.staple ? "Stapling" : "Binding") + " needs at least 2 sheets of paper; this document prints on 1 sheet.";
    }
    if (!error && printers && !anyCanDo(printers, requirements(s))) {
      error = whyNot(printers, requirements(s), s, facts.paperLabel);
    }
    return { settings: s, plan: pl, error: error };
  }

  /**
   * Why no printer can do it, in words a student can act on: a single choice
   * nobody can do first, else the combination. Same words as the server
   * (PrinterRules.whyNot).
   */
  function whyNot(printers, req, s, paperLabel) {
    var label = paperLabel || function (id) { return id; };
    if (!printers.length) return "No printer is taking orders right now. Please try again later.";
    var plain = { color: false, paperSize: null, duplex: null, finishing: [], mediaType: null, borderless: false, quality: null };
    var choices = [];
    if (req.color) {
      if (!anyCanDo(printers, Object.assign({}, plain, { color: true }))) return "Colour printing is not available. Choose black & white.";
      choices.push("colour");
    } else if (!anyCanDo(printers, plain)) {
      return "Black & white printing is not available. Choose colour.";
    }
    if (req.paperSize != null) {
      if (!anyCanDo(printers, Object.assign({}, plain, { color: req.color, paperSize: req.paperSize }))
          && !anyCanDo(printers, Object.assign({}, plain, { color: !req.color, paperSize: req.paperSize }))) {
        return "No printer takes " + label(req.paperSize) + " paper. Choose another paper size.";
      }
      if (req.paperSize !== "A4") choices.push(label(req.paperSize));
    }
    var has = function (fn) { return printers.some(function (p) { return fn(p.features || { paperSizes: ["A4"] }); }); };
    if (req.duplex && req.duplex !== "ONE_SIDED") {
      if (!has(function (f) { return f.duplex; })) return "Two-sided printing is not available. Choose one-sided.";
      choices.push("two-sided");
    }
    for (var i = 0; i < (req.finishing || []).length; i++) {
      var fin = req.finishing[i];
      if (!has(function (f) { return (f.finishing || []).indexOf(fin) >= 0; })) return "That finishing is not available.";
      choices.push(fin.toLowerCase().replace(/_/g, " "));
    }
    if (req.mediaType != null) {
      if (!has(function (f) { return (f.mediaTypes || []).indexOf(req.mediaType) >= 0; })) return "That paper type is not available. Choose the usual paper.";
      choices.push("that paper type");
    }
    if (req.borderless) {
      if (!has(function (f) { return f.borderless; })) return "Borderless printing is not available. Choose a margin.";
      choices.push("borderless");
    }
    if (req.quality === "HIGH") {
      if (!has(function (f) { return f.highQuality; })) return "High quality is not available. Choose standard quality.";
      choices.push("high quality");
    }
    return "No single printer can do " + choices.join(" + ") + " together. Change one of these choices.";
  }

  // ------------------------------------------------------------------ what the student may choose

  /**
   * The values a setting can have, and for each: can any printer do it at all
   * (else it is not shown), and can it be done with the other current choices
   * (else it is shown with what it would change).
   */
  var DIMENSIONS = {
    color: function (s, v) { s.color = v; },
    paperSize: function (s, v) { s.paperSize = v; },
    duplex: function (s, v) { s.duplex = v; },
    staple: function (s, v) { s.staple = v; },
    punch: function (s, v) { s.punch = v; },
    bind: function (s, v) { s.bind = v; },
    mediaType: function (s, v) { s.mediaType = v; },
    quality: function (s, v) { s.quality = v; },
    borderless: function (s, v) { s.marginMm = v ? 0 : (s.marginMm === 0 ? DEFAULT_MARGIN : s.marginMm); }
  };

  /** Requirements that only look at one setting (for "can any printer do this at all?"). */
  function onlyThis(dim, v) {
    var s = defaults(false);
    DIMENSIONS[dim](s, v);
    var r = requirements(s);
    if (dim !== "color") r.color = null;
    return r;
  }

  function canDoLoose(p, req) {
    // color null = either is fine
    if (req.color == null) return canDo(Object.assign({}, p, { color: true, bw: true }), req);
    return canDo(p, req);
  }

  /**
   * For one setting: { value, supported, available, fix } for every value.
   * fix: when not available with the other choices, the settings after
   * changing what stands in the way, and which choices changed (labels).
   */
  function availability(dim, values, s, printers) {
    return values.map(function (v) {
      var supported = printers.some(function (p) { return canDoLoose(p, onlyThis(dim, v)); });
      var t = Object.assign({}, s);
      DIMENSIONS[dim](t, v);
      var available = anyCanDo(printers, requirements(t));
      var fix = null;
      if (supported && !available) fix = fixFor(t, dim, printers);
      return { value: v, supported: supported, available: available, fix: fix };
    });
  }

  /**
   * Settings t cannot be printed as they are; find what to give up (least
   * important first) so they can, keeping dim as chosen.
   */
  var GIVE_UP = [
    ["quality", "STANDARD", "standard quality"], ["mediaType", null, "the usual paper"],
    ["borderless", false, "a margin"], ["bind", null, "no binding"], ["punch", null, "no hole punch"],
    ["staple", null, "no staples"], ["duplex", "ONE_SIDED", "one-sided"], ["paperSize", "A4", "A4 paper"],
    ["color", false, "black & white"], ["color", true, "colour"]
  ];

  function fixFor(t, dim, printers) {
    var tries = GIVE_UP.filter(function (g) { return g[0] !== dim; });
    // one change
    for (var i = 0; i < tries.length; i++) {
      var u = Object.assign({}, t);
      if (current(u, tries[i][0]) === tries[i][1]) continue;
      DIMENSIONS[tries[i][0]](u, tries[i][1]);
      if (anyCanDo(printers, requirements(u))) return { settings: u, changes: [tries[i][2]] };
    }
    // several, in order
    var w = Object.assign({}, t), changes = [];
    for (var j = 0; j < tries.length; j++) {
      if (current(w, tries[j][0]) === tries[j][1]) continue;
      DIMENSIONS[tries[j][0]](w, tries[j][1]);
      changes.push(tries[j][2]);
      if (anyCanDo(printers, requirements(w))) return { settings: w, changes: changes };
    }
    return null;
  }

  /**
   * Settings no printer can do any more (the printers changed): the smallest
   * change that makes them printable, as { settings, changes }, or null when
   * they are fine or nothing helps.
   */
  function quickFix(s, printers) {
    if (!printers.length || anyCanDo(printers, requirements(s))) return null;
    return fixFor(Object.assign({}, s), null, printers);
  }

  function current(s, dim) {
    if (dim === "borderless") return s.marginMm === 0;
    return s[dim] == null ? null : s[dim];
  }

  /** Everything any printer offers (the union), for building the choices. */
  function offered(printers) {
    var out = { color: false, bw: false, paperSizes: [], duplex: false, finishing: [], mediaTypes: [],
                borderless: false, highQuality: false };
    printers.forEach(function (p) {
      var f = p.features || {};
      out.color = out.color || p.color;
      out.bw = out.bw || p.bw;
      (f.paperSizes || ["A4"]).forEach(function (x) { if (out.paperSizes.indexOf(x) < 0) out.paperSizes.push(x); });
      out.duplex = out.duplex || !!f.duplex;
      (f.finishing || []).forEach(function (x) { if (out.finishing.indexOf(x) < 0) out.finishing.push(x); });
      (f.mediaTypes || []).forEach(function (x) { if (out.mediaTypes.indexOf(x) < 0) out.mediaTypes.push(x); });
      out.borderless = out.borderless || !!f.borderless;
      out.highQuality = out.highQuality || !!f.highQuality;
    });
    return out;
  }

  /** A setting is shown only when it offers a real choice. */
  function finishingPositions(offeredFinishing, group) {
    return offeredFinishing.filter(function (f) { return f.indexOf(group + "_") === 0; })
      .map(function (f) { return f.substring(group.length + 1); });
  }

  // ------------------------------------------------------------------ words

  function rupees(paise) {
    if (paise == null) return "–";
    return "₹" + (paise % 100 === 0 ? String(paise / 100) : (paise / 100).toFixed(2));
  }

  function plural(n, one, many) {
    return n + " " + (n === 1 ? one : many);
  }

  function fileSize(bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1048576) return Math.round(bytes / 1024) + " KB";
    return (bytes / 1048576).toFixed(bytes < 10485760 ? 1 : 0) + " MB";
  }

  var api = {
    MM: MM, GAP: GAP, PAGES_PER_SHEET: PAGES_PER_SHEET, DEFAULT_MARGIN: DEFAULT_MARGIN,
    FINISHING_GROUPS: FINISHING_GROUPS,
    parsePages: parsePages, specFromPages: specFromPages, pagesFromSpec: pagesFromSpec, displaySpec: displaySpec,
    layout: layout, layoutSheet: layoutSheet, layoutOptions: layoutOptions, pictureSize: pictureSize,
    plan: plan, price: price, requirements: requirements, canDo: canDo, anyCanDo: anyCanDo,
    candidates: candidates, defaults: defaults, normalize: normalize, availability: availability, whyNot: whyNot, quickFix: quickFix,
    offered: offered, finishingPositions: finishingPositions, rupees: rupees, plural: plural, fileSize: fileSize
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.PrintCore = api;
})(typeof self !== "undefined" ? self : this);
