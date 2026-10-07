/*
 * XeoGo Pay on the student website: turns the server's upi://pay link into
 * what each phone understands.
 *
 *   Android  upi://pay?...        Android shows the UPI apps installed on the phone
 *            intent://pay?...     opens one app directly (Google Pay, PhonePe...)
 *   iPhone   gpay://upi/pay?...   each app has its own link on iOS
 *   laptop   the same upi:// link as a QR code, scanned with any UPI app
 *
 * The server builds the link (the amount, the UPI ID, the note): nothing about
 * the payment is decided here. spec/upi-pay.test.js checks this file.
 */
"use strict";
(function (root) {
  // Package names (Android) and link prefixes (iOS) the apps register.
  const APPS = [
    { id: "gpay", name: "Google Pay", android: "com.google.android.apps.nbu.paisa.user", ios: "gpay://upi/pay", color: "#1A73E8", mark: "G" },
    { id: "phonepe", name: "PhonePe", android: "com.phonepe.app", ios: "phonepe://pay", color: "#5F259F", mark: "Pe" },
    { id: "paytm", name: "Paytm", android: "net.one97.paytm", ios: "paytmmp://upi/pay", color: "#00B9F1", mark: "P" },
    { id: "bhim", name: "BHIM", android: "in.org.npci.upiapp", ios: null, color: "#F26F21", mark: "B" }
  ];

  /** "android", "ios" or "desktop" (laptops and anything else: show the QR code). */
  function platform(userAgent, maxTouchPoints) {
    const ua = String(userAgent || "");
    if (/android/i.test(ua)) return "android";
    if (/iphone|ipad|ipod/i.test(ua) || (/macintosh/i.test(ua) && (maxTouchPoints || 0) > 1)) return "ios";
    return "desktop";
  }

  function query(uri) {
    const i = String(uri).indexOf("?");
    return i < 0 ? "" : String(uri).slice(i + 1);
  }

  /**
   * The link that opens this app with the payment filled in, or null if the
   * app has none on this platform. fallbackUrl (Android): where Chrome goes if
   * the app is not installed, instead of the Play Store.
   */
  function appLink(app, uri, plat, fallbackUrl) {
    const q = query(uri);
    if (!q) return null;
    if (plat === "android") {
      return "intent://pay?" + q + "#Intent;scheme=upi;package=" + app.android +
        (fallbackUrl ? ";S.browser_fallback_url=" + encodeURIComponent(fallbackUrl) : "") + ";end";
    }
    if (plat === "ios" && app.ios) return app.ios + "?" + q;
    return null;
  }

  /** The app buttons for this platform: [{id, name, color, mark, href}]. None on a laptop. */
  function appButtons(uri, plat, fallbackFor) {
    return APPS.map(a => ({
      id: a.id, name: a.name, color: a.color, mark: a.mark,
      href: appLink(a, uri, plat, fallbackFor ? fallbackFor(a) : null)
    })).filter(b => b.href);
  }

  /** "6273 1234-5678" -> "627312345678"; anything else stays as typed (the server refuses it). */
  function cleanRef(typed) {
    const s = String(typed == null ? "" : typed).trim();
    const digits = s.replace(/[\s-]/g, "");
    return /^\d+$/.test(digits) ? digits : s;
  }

  /** Empty (not known) or exactly 12 digits. */
  function refOk(typed) {
    const r = cleanRef(typed);
    return r === "" || /^\d{12}$/.test(r);
  }

  /** "627312345678" -> "6273 1234 5678", easier to compare with the UPI app's receipt. */
  function groupRef(ref) {
    return /^\d{12}$/.test(ref || "") ? ref.slice(0, 4) + " " + ref.slice(4, 8) + " " + ref.slice(8) : (ref || "");
  }

  /** 1 -> "1 paisa", 3 -> "3 paise". */
  function paiseWords(n) {
    return n + (n === 1 ? " paisa" : " paise");
  }

  const api = { APPS, platform, appLink, appButtons, cleanRef, refOk, groupRef, paiseWords };
  root.UpiPay = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : globalThis);
