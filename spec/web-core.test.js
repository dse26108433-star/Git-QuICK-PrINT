/*
 * Runs the shared cases against the website's rules (web/js/print-core.js):
 *   node spec/web-core.test.js
 * The same cases run against the server (backend SharedRulesTest, DatabaseTest)
 * and the Xerox PC (agent LayoutTest), so all three always agree.
 */
"use strict";
const assert = require("assert");
const path = require("path");
const fs = require("fs");
const core = require(path.join(__dirname, "..", "web", "js", "print-core.js"));

const cases = (name) => JSON.parse(fs.readFileSync(path.join(__dirname, "cases", name), "utf8"));
let passed = 0;
function check(name, fn) {
  try {
    fn();
    passed++;
  } catch (e) {
    console.error("FAILED: " + name + "\n  " + e.message);
    process.exitCode = 1;
  }
}
const close = (a, b, what) => assert.ok(Math.abs(a - b) < 0.001, what + ": " + a + " vs " + b);

// ------------------------------------------------------------------ page ranges
for (const c of cases("page-ranges.json").cases) {
  check("pages " + JSON.stringify(c.text), () => {
    if (c.error) {
      assert.throws(() => core.parsePages(c.text, c.total));
      return;
    }
    const r = core.parsePages(c.text, c.total);
    assert.strictEqual(r.spec, c.spec);
    assert.strictEqual(r.count, c.count);
    // clicking pages on thumbnails gives the same tidy form
    assert.strictEqual(core.specFromPages(core.pagesFromSpec(r.spec, c.total), c.total), c.spec);
  });
}

// ------------------------------------------------------------------ pricing
const pricing = cases("pricing.json");
for (const c of pricing.cases) {
  check("price " + c.name, () => {
    const s = Object.assign(core.defaults(false), c.settings);
    const pl = core.plan(c.printPages, s);
    const q = core.price(s, pl, c.priceBwPaise != null ? c.priceBwPaise : pricing.priceBwPaise,
      pricing.priceColorPaise, pricing.rules);
    assert.strictEqual(pl.sides, c.sides, "sides");
    assert.strictEqual(pl.sheets, c.sheets, "sheets");
    assert.strictEqual(q.perSide, c.perSide, "per side");
    assert.strictEqual(q.amount, c.amount, "amount");
  });
}

// ------------------------------------------------------------------ printer rules
const rules = cases("printer-rules.json");
for (const c of rules.cases) {
  check("printer " + c.printer + " " + JSON.stringify(c.req), () => {
    const p = rules.printers[c.printer];
    assert.strictEqual(core.canDo({ color: p.color, bw: p.bw, features: p.features || undefined }, c.req), c.can);
  });
}

// ------------------------------------------------------------------ layout
for (const c of cases("layout.json").cases) {
  check("layout " + c.name, () => {
    const s = c.settings;
    const o = {
      paperW: c.paperMm[0] * core.MM, paperH: c.paperMm[1] * core.MM, orientation: s.orientation || "AUTO",
      marginPt: (s.marginMm != null ? s.marginMm : 5) * core.MM, scaling: s.scaling || "FIT",
      scalePercent: s.scalePercent || 100, pagesPerSheet: s.pagesPerSheet || 1, center: s.center !== false
    };
    const pages = c.pages.map((p) => ({ w: p[0], h: p[1] }));
    const got = core.layout(pages, o);
    assert.strictEqual(got.length, c.sheets.length, "sheets");
    got.forEach((g, i) => {
      const w = c.sheets[i];
      close(g.w, w.w, "sheet w");
      close(g.h, w.h, "sheet h");
      assert.strictEqual(!!g.clip, !!w.clip, "clip");
      if (g.clip) { close(g.clip.x, w.clip[0], "clip x"); close(g.clip.w, w.clip[2], "clip w"); }
      assert.strictEqual(g.cells.length, w.cells.length, "cells");
      g.cells.forEach((gc, k) => {
        const wc = w.cells[k];
        assert.strictEqual(gc.page, wc.page);
        close(gc.x, wc.x, "x"); close(gc.y, wc.y, "y"); close(gc.w, wc.w, "w"); close(gc.h, wc.h, "h");
      });
      // one sheet at a time gives the same answer (the preview draws one sheet)
      const one = core.layoutSheet(i, pages.length, (j) => pages[j], o);
      assert.deepStrictEqual(one, g);
    });
  });
}

// ------------------------------------------------------------------ tidy settings (as the server's SettingsChecker)
const printers = [
  { color: false, bw: true, features: { paperSizes: ["A4", "A3"], duplex: true, finishing: ["STAPLE_TOP_LEFT"] } },
  { color: true, bw: true, features: { paperSizes: ["A4", "PHOTO_4X6"], borderless: true, highQuality: true } }
];
const limits = { maxCopies: 50, maxPages: 300 };
check("pictures lose page list and pages per sheet", () => {
  const r = core.normalize({ pages: "1-3", pagesPerSheet: 4, rotation: -90, scaling: "FILL" }, { type: "JPEG", pageCount: 1 }, limits, printers);
  assert.strictEqual(r.settings.pages, null);
  assert.strictEqual(r.settings.pagesPerSheet, 1);
  assert.strictEqual(r.settings.rotation, 270);
  assert.strictEqual(r.settings.scaling, "FILL");
  assert.strictEqual(r.error, null);
});
check("several per sheet are fitted; one copy is collated", () => {
  const r = core.normalize({ pages: "8, 1-3, 2", pagesPerSheet: 2, scaling: "ACTUAL", collate: false }, { type: "PDF", pageCount: 12 }, limits, printers);
  assert.strictEqual(r.settings.pages, "1-3,8");
  assert.strictEqual(r.settings.scaling, "FIT");
  assert.strictEqual(r.settings.collate, true);
  assert.strictEqual(r.plan.sides, 2);
});
check("stapling one sheet is refused", () => {
  const r = core.normalize({ pages: "2", staple: "TOP_LEFT" }, { type: "PDF", pageCount: 3 }, limits, printers);
  assert.ok(/Stapling needs at least 2 sheets/.test(r.error));
});
check("combinations no printer can do are refused", () => {
  const r = core.normalize({ color: true, duplex: "LONG_EDGE" }, { type: "PDF", pageCount: 3 }, limits, printers);
  assert.ok(r.error);
});

// ------------------------------------------------------------------ what students are offered
check("options nobody has are hidden, conflicts say what they change", () => {
  const s = Object.assign(core.defaults(false), { duplex: "LONG_EDGE" });
  const colour = core.availability("color", [false, true], s, printers);
  assert.strictEqual(colour[1].supported, true);
  assert.strictEqual(colour[1].available, false);
  assert.deepStrictEqual(colour[1].fix.changes, ["one-sided"]);
  assert.strictEqual(colour[1].fix.settings.duplex, "ONE_SIDED");
  const sizes = core.availability("paperSize", ["A4", "A3", "LEGAL"], s, printers);
  assert.strictEqual(sizes[2].supported, false);            // nobody has Legal: not shown
  assert.strictEqual(sizes[1].available, true);             // two-sided A3 on the B/W printer
});
check("settings the printers cannot do any more get the smallest fix", () => {
  const s = Object.assign(core.defaults(false), { color: true, duplex: "LONG_EDGE" });
  const fix = core.quickFix(s, printers);
  assert.deepStrictEqual(fix.changes, ["one-sided"]);
  assert.strictEqual(fix.settings.color, true);             // the colour stays as chosen
  assert.strictEqual(fix.settings.duplex, "ONE_SIDED");
  assert.strictEqual(s.duplex, "LONG_EDGE");                // the original is not touched
  assert.strictEqual(core.quickFix(fix.settings, printers), null);
  assert.strictEqual(core.quickFix(s, []), null);           // no printer at all: nothing to offer
});
check("pictures keep their proportions after any turn", () => {
  const a = core.pictureSize({ widthPx: 4000, heightPx: 3000, dpi: 72, exifOrientation: 6 }, 0);
  assert.ok(Math.abs(a.w / a.h - 0.75) < 1e-9);
  const b = core.pictureSize({ widthPx: 4000, heightPx: 3000, dpi: 72, exifOrientation: 6 }, 90);
  assert.ok(Math.abs(b.w / b.h - 4 / 3) < 1e-9);
});

console.log((process.exitCode ? "Some checks FAILED. " : "All ") + passed + " checks passed.");
