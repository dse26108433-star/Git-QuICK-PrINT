/*
 * The A4 poster with the QR code (poster.html): students scan it to open the
 * print website. Kept in its own file so the site needs no inline scripts.
 */
"use strict";
(function () {
  const $ = (id) => document.getElementById(id);
  const given = new URLSearchParams(location.search).get("url");
  $("url").value = given || new URL("./", location.href).href;

  function draw() {
    const url = $("url").value.trim();
    $("urlText").textContent = url.replace(/^https?:\/\//, "").replace(/\/$/, "");
    const qr = window.qrcode(0, "M");          // size chosen automatically, 15% damage allowed
    qr.addData(url);
    qr.make();
    $("qr").innerHTML = qr.createSvgTag(10, 0);
    const svg = $("qr").querySelector("svg");
    svg.removeAttribute("width"); svg.removeAttribute("height");
    svg.setAttribute("viewBox", "0 0 " + qr.getModuleCount() * 10 + " " + qr.getModuleCount() * 10);
  }
  $("url").addEventListener("input", draw);
  $("printBtn").onclick = () => window.print();
  draw();

  // Center name and prices from the print service (the poster works without them).
  const api = (window.CONFIG && window.CONFIG.apiBase || "").replace(/\/+$/, "");
  const rupees = (p) => "₹" + (p % 100 === 0 ? p / 100 : (p / 100).toFixed(2));
  if (api) {
    fetch(api + "/api/v1/shop").then(r => r.json()).then(s => {
      $("center").textContent = s.centerName;
      $("prices").innerHTML = "";
      for (const [name, p] of [["Black & white", s.priceBwPaise], ["Colour", s.priceColorPaise]]) {
        const el = document.createElement("span");
        el.append(name + " ");
        const b = document.createElement("b");
        b.textContent = rupees(p);
        el.append(b, " / page");
        $("prices").append(el);
      }
    }).catch(() => {});
  }
})();
