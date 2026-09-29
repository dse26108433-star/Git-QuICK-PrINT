# Campus Print — Xerox center remote printing

*Designed & developed by Vedant Pravin Surve.*

Students anywhere on campus send a file from their phone or laptop, pay online,
and collect the printout at the **one Xerox center** with a **pickup code**.
No accounts, no login.

**New here? Open [`START-HERE.md`](START-HERE.md) and follow the steps in order.**

## What a student does

1. Opens the website and adds **all their files at once**: PDFs, JPGs and PNGs, dragged in, picked, or pasted
   (up to 25 files, 50 MB each). Each file uploads with its own progress bar and can be cancelled, tried
   again or removed; more can be added at any time.
2. Sets up **each file on its own**, with a live **print preview** of every sheet:
   - **pages** (typed like `3, 7, 10-12`, or tapped on the page pictures), shown as
     *"Selected: 3, 7, 10–12 · Total pages to print: 5"*
   - **copies**, **black & white or colour**, **one- or two-sided**, **paper size**, orientation, scale,
     **pages per sheet**, margins, and **staple / hole punch / binding** when a printer has them
   - pictures: fit, fill, actual size or custom size, turn left / right, borderless — **never stretched**
3. Only choices the connected printers can really do are shown. The price updates live.
4. **Review and pay**: the server checks and prices every file; the student sees exactly what will be printed,
   file by file, and pays once (UPI / card via Razorpay).
5. Gets **one pickup code** for everything, e.g. `K7M4X`, and watches each file: *Waiting → Printing on
   Printer 2 → Ready*.
6. Shows the code at the counter and takes the pages.

It works the same on a phone (one file at a time, full-screen settings), a tablet and a laptop (list,
preview and settings side by side).

## What happens in the Xerox center

- One Windows PC is connected to the 3–4 Canon printers. It runs **Campus Print Station**: one installer
  (`installer/CampusPrintStation-Setup-4.0.0.exe`, about 37 MB) with its own Java inside, so nothing else is
  installed. A setup wizard connects the PC to the server and **scans the printers**; after that the Station
  shows the counter and **prints paid files by itself**, starts with Windows and keeps printing from the tray.
- The Station **reads what each printer can do** (paper sizes, two-sided, colour, stapling, hole punching…) and
  keeps the server up to date; staff tick what they want to offer on each printer. The website offers exactly
  that, and nothing a printer cannot do.
- **Every file is its own print job.** A free printer takes the next paid file it can do completely: colour
  files go to colour printers, A3 two-sided to the printer that has both, and so on. One order's files stay
  on one printer when possible. All printers work at the same time.
- **No paper is wasted on cover sheets.** The pickup code is printed small in the bottom-right corner of the
  first sheet of each file (`Pickup K7M4X · 2/3 · 3 sheets × 2`), so staff can match pages to students.
- Staff use the **counter** in the Station: type the student's code and press Enter to hand over; each file shows
  its printer and sheets, with **Print again** and **Cancel**; printer lamps (green / amber / red), printers
  on/off, prices (per printed side, extra % for A3 or special paper, finishing prices), and the
  **"Pickup code on pages"** switch. `web/counter.html` is the same counter in a browser.

## Folders

| Folder | What it is |
|---|---|
| `db/` | `setup.sql` (tables, queue, printer rules, safety rules; safe to run again, upgrades older databases), `seed.sql`, `reset.sql` |
| `backend/` | Spring Boot service: orders and their files, file checks, settings checks and prices, payment, counter API, PC API |
| `agent/` | Campus Print Station (the Xerox PC app: `station/` + screens in `resources/station-ui/`), printer discovery and the printing code |
| `agent/packaging/` | `build-installer.ps1` makes the installer (jlink + jpackage + Inno Setup), icons |
| `installer/` | The finished installer and `HOW-TO-INSTALL.txt` for the person installing it |
| `web/` | The student website for Netlify: `index.html` + `css/` + `js/` (`print-core.js` = the shared rules, `app.js` = the screens), installable as a phone app (`manifest.webmanifest`, `sw.js`); `poster.html` (A4 QR poster), `config.js`, `netlify.toml`; and `counter.html` |
| `spec/` | Shared test cases (pages, prices, printer rules, layout) checked against the backend, the Station, the database and the website: `node spec/web-core.test.js` |
| `render.yaml`, `backend/Dockerfile` | Put the backend online with HTTPS (Render or any Docker host) |
| `android/` | Android app (Kotlin, Jetpack Compose, Razorpay): the same multi-file orders and settings as the website (`core/PrintCore.kt` = the shared rules, checked against `spec/cases`) |
| `docs/` | `how-it-works.md`, `troubleshooting.md` |

## Money and safety, in short

- **The price is always worked out by the backend** from the real uploaded files and the checked settings,
  never from what the phone says. What the student sees on "Review and pay" is exactly what is printed.
- **Payment is verified on the server** (Razorpay signature + asking Razorpay directly). Only then do the files
  enter the print queue. If a student closes the page right after paying, the backend finds the payment itself
  within a minute.
- **A file is never printed twice by accident.** The PC writes "sent" to its own disk before printing,
  and after that point nothing automatic ever sends it again. A second print only happens if staff press **Print again**.
- **A file is never printed differently from what was chosen.** If a printer's driver drops a setting (e.g.
  stapling), the file is not printed and goes back to the queue with a clear message.
- A printer that runs out of paper or jams is **not** treated as a failure: Windows keeps the document and
  prints it when staff fix the printer. The counter screen shows the printer needs attention.
- Files are private (Supabase private bucket, 5-minute links), and are deleted after printing.
- Secret keys live only in `backend/.env` and the Xerox PC — never in the web page or the app.

## Going live (after START-HERE works)

1. **Put the backend online with HTTPS.** Easiest: push this folder to GitHub, then render.com → New →
   Blueprint → pick the repository (`render.yaml` does the rest) and paste the values from `backend/.env`.
   Any Docker host works too (`backend/Dockerfile`). You get an address like `https://campus-print-backend.onrender.com`.
2. **Put the student website on Netlify.** Set `apiBase` in `web/config.js` to that https address, then drag the
   `web` folder onto app.netlify.com/drop (or connect the repository with base directory `web`). Put the Netlify
   address (e.g. `https://campus-print.netlify.app`) in `WEB_ORIGINS` on the backend and restart it.
   `web/netlify.toml` keeps the staff counter off the public site.
3. **Install Campus Print Station on the Xerox PC** (`installer/HOW-TO-INSTALL.txt`). For a college, build an
   installer with the address built in: `build-installer.ps1 -BackendUrl "https://..."`
   (`installer/CampusPrintStation-Setup-4.0.0-GitQuickPrint.exe` is already built for
   `https://campus-print-backend.onrender.com`).
4. **Razorpay live mode:** finish KYC, generate live keys (`rzp_live_...`), set them in `.env`, restart.
   Refunds for problem orders are done in the Razorpay dashboard (the counter screen shows the payment id).
5. **Android:** the app talks to `https://campus-print-backend.onrender.com` unless built with `-PapiBase=...`;
   build a signed release (Build → Generate Signed Bundle/APK). Version 3.0.0 has the full multi-file order.
6. Change `COUNTER_PASSWORD` to something strong. Remove `PAYMENT_MODE=demo` — demo mode prints without payment.

## Upgrading from version 3 (one file per order)

`db/setup.sql` upgrades the database in place: every old order becomes an order with one file, nothing is lost.
**The backend, the website and the Station must then be updated together**: the old backend cannot claim
work from the new database. Order: run `setup.sql` → deploy the new backend → deploy the website → install
Station 4.0.0 on the Xerox PC (it updates the old one in place). A Station older than 4.0 still prints plain
A4 one-sided files, but everything else waits for 4.0.

## Honest note

Everything here is built and tested end to end on a Windows laptop: the backend and database rules with
automatic tests against a real PostgreSQL 17 (the same version as Supabase), the Station printing through the
real Windows print path to virtual PDF printers (actual size, A3 two per sheet, print tickets), and the student
website driven in Microsoft Edge on phone, tablet and laptop sizes, including an order printed by the real
Station code whose output matched the preview. Some things can only be checked on site: stapling, hole
punching and binding on the real Canons (each driver names them differently: print one test order per option),
and colour vs. grey on real paper. Follow START-HERE step by step: each step tells you exactly what "working"
looks like, so a problem shows up at the step where it is, not at the end.
