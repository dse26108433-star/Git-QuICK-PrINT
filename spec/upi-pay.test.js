/*
 * XeoGo Pay on the website (web/js/upi-pay.js): the links that open UPI apps.
 *   node spec/upi-pay.test.js
 * The upi://pay link itself comes from the server (UpiPayee.java, checked in
 * the backend's BankAlertParserTest); this checks what each phone gets from it.
 */
"use strict";
const assert = require("assert");
const path = require("path");
const upi = require(path.join(__dirname, "..", "web", "js", "upi-pay.js"));

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

const URI = "upi://pay?pa=xeroxshop@okaxis&pn=Main%20Xerox%20Center&tn=Main%20Xerox%20Center%20K7M4X&am=10.03&cu=INR";
const Q = URI.slice(URI.indexOf("?") + 1);
const ANDROID = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36";
const IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
const IPAD = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15";
const WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36 Edg/129.0";

check("which phone is this", () => {
  assert.strictEqual(upi.platform(ANDROID, 5), "android");
  assert.strictEqual(upi.platform(IPHONE, 5), "ios");
  assert.strictEqual(upi.platform(IPAD, 5), "ios");            // iPads say "Macintosh" but have a touch screen
  assert.strictEqual(upi.platform(IPAD, 0), "desktop");        // a real Mac
  assert.strictEqual(upi.platform(WINDOWS, 0), "desktop");
  assert.strictEqual(upi.platform(undefined, undefined), "desktop");
});

check("Android: every app opens directly, with the payment filled in", () => {
  const gpay = upi.APPS.find(a => a.id === "gpay");
  assert.strictEqual(upi.appLink(gpay, URI, "android"),
    "intent://pay?" + Q + "#Intent;scheme=upi;package=com.google.android.apps.nbu.paisa.user;end");
  const back = "https://gitprint.netlify.app/#order=abc&missing=gpay";
  assert.strictEqual(upi.appLink(gpay, URI, "android", back),
    "intent://pay?" + Q + "#Intent;scheme=upi;package=com.google.android.apps.nbu.paisa.user;S.browser_fallback_url=" +
    encodeURIComponent(back) + ";end");
  const buttons = upi.appButtons(URI, "android", a => "x#missing=" + a.id);
  assert.deepStrictEqual(buttons.map(b => b.id), ["gpay", "phonepe", "paytm", "bhim"]);
  assert.ok(buttons.every(b => b.href.startsWith("intent://pay?pa=xeroxshop@okaxis&")));
  assert.ok(buttons.every(b => b.href.includes("&am=10.03&cu=INR#Intent;")));
});

check("iPhone: each app's own link", () => {
  const b = upi.appButtons(URI, "ios");
  assert.deepStrictEqual(b.map(x => x.href), [
    "gpay://upi/pay?" + Q, "phonepe://pay?" + Q, "paytmmp://upi/pay?" + Q
  ]);                                                          // BHIM has no iPhone link: not offered
});

check("laptop: no app buttons (the QR code is shown)", () => {
  assert.deepStrictEqual(upi.appButtons(URI, "desktop"), []);
  assert.strictEqual(upi.appLink(upi.APPS[0], "upi://pay", "android"), null);     // no payment details: nothing
});

check("UPI reference numbers", () => {
  assert.strictEqual(upi.cleanRef(" 6273 1234-5678 "), "627312345678");
  assert.ok(upi.refOk("627312345678"));
  assert.ok(upi.refOk("6273 1234 5678"));
  assert.ok(upi.refOk(""));                                    // optional
  assert.ok(upi.refOk(null));
  assert.ok(!upi.refOk("12345"));
  assert.ok(!upi.refOk("62731234567a"));
  assert.ok(!upi.refOk("6273123456789"));
  assert.strictEqual(upi.groupRef("627312345678"), "6273 1234 5678");
  assert.strictEqual(upi.paiseWords(1), "1 paisa");
  assert.strictEqual(upi.paiseWords(3), "3 paise");
});

console.log((process.exitCode ? "Some checks FAILED. " : "All ") + passed + " checks passed.");
