/*
 * web/staff.html, the staff website, is web/index.html with a different head:
 * the same page, told that it is the staff one (data-app="staff"), so
 * js/app.js asks for a sign-in and prints for free instead of taking payment.
 * Words that differ are in index.html twice (for-student / for-staff).
 *
 *   node spec/staff-page.js --write     makes web/staff.html again after index.html changed
 *   node spec/web-pages.test.js         fails when the two have drifted apart
 */
"use strict";
const fs = require("fs");
const path = require("path");

const web = path.join(__dirname, "..", "web");

/** Each line of index.html's head that the staff page says differently. */
const HEAD = [
  [/<html lang="en">/, '<html lang="en" data-app="staff">'],
  [/<title>[^<]*<\/title>/, "<title>XeoGo Staff · QuICK PrINT · Free printing for college staff</title>"],
  [/<meta name="description" content="[^"]*">/,
    '<meta name="description" content="Free printing for college staff at the campus Xerox center: sign in with your ' +
    'staff ID, send your files from your desk, and collect them by showing your files. No payment, no code.">'],
  [/<meta property="og:title" content="[^"]*">/, '<meta property="og:title" content="XeoGo Staff · QuICK PrINT">'],
  [/<meta property="og:description" content="[^"]*">/,
    '<meta property="og:description" content="Free printing for college staff. Sign in with your staff ID.">\n' +
    '<meta name="robots" content="noindex">'],
  [/<link rel="manifest" href="[^"]*">/, '<link rel="manifest" href="staff.webmanifest">'],
  [/<meta name="apple-mobile-web-app-title" content="[^"]*">/, '<meta name="apple-mobile-web-app-title" content="XeoGo Staff">']
];

function staffPage(indexHtml) {
  let out = indexHtml;
  for (const [find, replacement] of HEAD) {
    if (!find.test(out)) {
      throw new Error("web/index.html has no line like " + find + ": spec/staff-page.js needs an update");
    }
    out = out.replace(find, replacement);
  }
  return out;
}

module.exports = { staffPage };

if (require.main === module) {
  const page = staffPage(fs.readFileSync(path.join(web, "index.html"), "utf8"));
  if (process.argv.includes("--write")) {
    fs.writeFileSync(path.join(web, "staff.html"), page);
    console.log("Wrote web/staff.html");
  } else {
    process.stdout.write(page);
  }
}
