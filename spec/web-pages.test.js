/*
 * The web pages themselves:  node spec/web-pages.test.js
 *
 *   - the staff website (web/staff.html) is the student page with a staff head, nothing else
 *   - every element the scripts look up by id is in the page
 *   - no page carries a script or a style of its own that the site's Content-Security-Policy
 *     (web/_headers) would refuse, and none loads a script from another website
 *   - the page that works offline (web/sw.js) lists files that exist
 */
"use strict";
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const { staffPage } = require("./staff-page.js");

const web = path.join(__dirname, "..", "web");
const read = (name) => fs.readFileSync(path.join(web, name), "utf8");
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

const index = read("index.html");
const staff = read("staff.html");

check("staff.html is index.html with the staff head (run: node spec/staff-page.js --write)", () => {
  assert.strictEqual(staff, staffPage(index));
  assert.ok(/<html lang="en" data-app="staff">/.test(staff));
  assert.ok(!/data-app=/.test(index), "the student page must not say it is the staff page");
});

check("the two pages install as two apps", () => {
  const student = JSON.parse(read("manifest.webmanifest"));
  const forStaff = JSON.parse(read("staff.webmanifest"));
  assert.notStrictEqual(student.name, forStaff.name);
  assert.notStrictEqual(student.id, forStaff.id);
  assert.ok(/staff\.html/.test(forStaff.start_url));
  for (const m of [student, forStaff]) {
    for (const icon of m.icons) assert.ok(fs.existsSync(path.join(web, icon.src)), "missing " + icon.src);
  }
});

/** Every $("id") in a script must be an element of its page. */
function idsUsed(script) {
  const ids = new Set();
  for (const m of script.matchAll(/\$\("([A-Za-z0-9_-]+)"\)/g)) ids.add(m[1]);
  return ids;
}
function idsIn(html) {
  const ids = new Set();
  for (const m of html.matchAll(/\sid="([^"]+)"/g)) ids.add(m[1]);
  return ids;
}
for (const [page, script] of [["index.html", "js/app.js"], ["counter.html", "js/counter.js"], ["poster.html", "js/poster.js"]]) {
  check(page + " has every element " + script + " looks up", () => {
    const have = idsIn(read(page));
    const code = read(script);
    // elements the script makes itself and gives an id
    for (const m of code.matchAll(/\.id = "([A-Za-z0-9_-]+)"/g)) have.add(m[1]);
    // (ids put together in the script, like "s-" + step, are checked below)
    const missing = [...idsUsed(code)].filter(id => !have.has(id));
    assert.deepStrictEqual(missing, []);
  });
}
check("every step of the order has its section", () => {
  const have = idsIn(index);
  for (const step of ["login", "choose", "setup", "review", "pay", "status"]) assert.ok(have.has("s-" + step), "s-" + step);
});

check("no id is used twice in a page", () => {
  for (const page of ["index.html", "counter.html", "poster.html"]) {
    const seen = new Set();
    for (const m of read(page).matchAll(/\sid="([^"]+)"/g)) {
      assert.ok(!seen.has(m[1]), page + ": id \"" + m[1] + "\" twice");
      seen.add(m[1]);
    }
  }
});

for (const page of ["index.html", "staff.html", "counter.html", "poster.html"]) {
  check(page + " keeps to the Content-Security-Policy", () => {
    const html = read(page);
    // script-src 'self': no script written into the page, no onclick="..." and the like
    for (const m of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)) {
      assert.ok(/\ssrc="/.test(m[1]) && !m[2].trim(), "a script inside " + page);
      const src = /\ssrc="([^"]+)"/.exec(m[1])[1];
      assert.ok(!/^(https?:)?\/\//.test(src), page + " loads a script from another website: " + src);
      assert.ok(fs.existsSync(path.join(web, src)), page + ": missing " + src);
    }
    assert.ok(!/\son[a-z]+\s*=\s*["']/i.test(html.replace(/<svg[\s\S]*?<\/svg>/g, "")), "an inline event handler in " + page);
    assert.ok(!/javascript:/i.test(html), "a javascript: link in " + page);
    for (const m of html.matchAll(/<link\b[^>]*rel="stylesheet"[^>]*>/g)) {
      const href = /href="([^"]+)"/.exec(m[0])[1];
      if (/^https:\/\/fonts\.googleapis\.com\//.test(href)) continue;
      assert.ok(!/^(https?:)?\/\//.test(href), page + " loads a stylesheet from another website: " + href);
      assert.ok(fs.existsSync(path.join(web, href)), page + ": missing " + href);
    }
    for (const m of html.matchAll(/<img\b[^>]*\ssrc="([^"]+)"/g)) {
      assert.ok(fs.existsSync(path.join(web, m[1])), page + ": missing picture " + m[1]);
    }
  });
}

check("the pages saved for offline use exist", () => {
  const shell = /const SHELL = \[([\s\S]*?)\];/.exec(read("sw.js"))[1];
  const files = [...shell.matchAll(/"([^"]+)"/g)].map(m => m[1]).filter(f => f !== "./");
  assert.ok(files.includes("staff.html") && files.includes("index.html"));
  for (const f of files) assert.ok(fs.existsSync(path.join(web, f)), "sw.js lists a missing file: " + f);
});

check("the Content-Security-Policy lets no other website's script in (except the payment company's)", () => {
  const csp = /^\s+Content-Security-Policy: (.+)$/m.exec(read("_headers"))[1].trim();
  const scripts = /script-src ([^;]+)/.exec(csp)[1].trim().split(/\s+/);
  assert.deepStrictEqual(scripts, ["'self'", "https://*.razorpay.com"]);
  assert.ok(/object-src 'none'/.test(csp) && /base-uri 'self'/.test(csp) && /frame-ancestors 'none'/.test(csp));
});

check("the headers and redirects are in the files Netlify reads for a hand-uploaded folder too", () => {
  const headers = read("_headers");
  for (const h of ["X-Content-Type-Options: nosniff", "X-Frame-Options: DENY", "Strict-Transport-Security:", "Content-Security-Policy:"]) {
    assert.ok(headers.includes(h), "_headers lacks " + h);
  }
  assert.ok(/^\/\*\n/m.test(headers), "_headers has no rule for every page");
  const redirects = read("_redirects");
  assert.ok(/^\/staff\s+\/staff\.html\s+301$/m.test(redirects));
  assert.ok(/^\/counter\.html\s+\/\s+302!$/m.test(redirects));
  // nothing is said twice: the settings file only names the folder
  const toml = read("netlify.toml");
  assert.ok(!/\[\[headers\]\]|\[\[redirects\]\]/.test(toml), "netlify.toml repeats what _headers / _redirects say");
});

if (!process.exitCode) console.log("All " + passed + " checks passed.");
