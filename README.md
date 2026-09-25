# Campus Print — Xerox center remote printing

*Designed & developed by Vedant Pravin Surve.*

Students anywhere on campus send a file from their phone or laptop, pay online,
and collect the printout at the **one Xerox center** with a **pickup code**.
No accounts, no login.

**New here? Open [`START-HERE.md`](START-HERE.md) and follow the steps in order.**

## What a student does

1. Opens the website (or Android app) and chooses a **PDF, PNG or JPG**.
2. **Sees the file** on screen (every page for PDFs, the picture for images).
3. Chooses **which pages** (all, or e.g. `102`, `333-390`, `1-5, 8`), **Black & white or Colour**, and
   **how many copies**. The price updates live and counts only the chosen pages.
   (A PDF can have up to 2000 pages and 50 MB; up to 300 pages are printed per order.)
4. Taps **Continue to payment** and pays (UPI / card via Razorpay).
5. Gets a big **pickup code**, e.g. `K7M4X`, and watches: *Paid → Printing on Printer 2 → Ready*.
6. Shows the code at the counter and takes the pages.

## What happens in the Xerox center

- One Windows PC is connected to the 3–4 Canon printers. It runs **Campus Print Station**: one installer
  (`installer/CampusPrintStation-Setup-3.0.0.exe`, about 37 MB) with its own Java inside, so nothing else is
  installed. A setup wizard connects the PC to the server and **scans the printers**; after that the Station
  shows the counter and **prints paid orders by itself**, starts with Windows and keeps printing from the tray.
- The Station runs **one worker per printer**. A free printer takes the next paid order it can do:
  colour orders only go to colour printers; B/W orders go to any printer that accepts B/W.
  So all printers work at the same time.
- **No paper is wasted on cover sheets.** The pickup code is printed small in the bottom-right corner of
  each order's first page (`Pickup K7M4X · 3 pages × 2`), so staff can match pages to students.
  (A cover sheet for big orders can be switched on in `agent.yml`: see docs/how-it-works.md.)
- Staff use the **counter** in the Station: type the student's code and press Enter to hand over, printer lamps
  (green / amber / red), orders printing / ready / problems ("Print again" after checking the tray), printers
  on/off, prices, and the **"Pickup code on pages"** switch (off for a while when a student wants nothing but
  their own file on the paper). `web/counter.html` is the same counter in a browser.

## Folders

| Folder | What it is |
|---|---|
| `db/` | `setup.sql` (tables, queue, safety rules), `seed.sql` (prices, printers, enroll the PC), `reset.sql` |
| `backend/` | Spring Boot service: orders, file checks and page counting, payment, counter API, PC API |
| `agent/` | Campus Print Station (the Xerox PC app: `station/` + screens in `resources/station-ui/`) and the printing code |
| `agent/packaging/` | `build-installer.ps1` makes the installer (jlink + jpackage + Inno Setup), icons |
| `installer/` | The finished installer and `HOW-TO-INSTALL.txt` for the person installing it |
| `web/` | The student website for Netlify: `index.html` (installable as a phone app: `manifest.webmanifest`, `sw.js`), `poster.html` (A4 QR poster for the queue), `config.js`, `netlify.toml`; and `counter.html` |
| `render.yaml`, `backend/Dockerfile` | Put the backend online with HTTPS (Render or any Docker host) |
| `android/` | Android app (Kotlin, Jetpack Compose, Razorpay) |
| `docs/` | `how-it-works.md`, `troubleshooting.md` |

## Money and safety, in short

- **The price is always worked out by the backend** from the real page count of the uploaded file,
  never from what the phone says.
- **Payment is verified on the server** (Razorpay signature + asking Razorpay directly). Only then does an order enter the print queue.
  If a student closes the app right after paying, the backend finds the payment itself within a minute.
- **An order is never printed twice by accident.** The PC writes "sent" to its own disk before printing,
  and after that point nothing automatic ever sends it again. A second print only happens if staff press **Print again**.
- A printer that runs out of paper or jams is **not** treated as a failure: Windows keeps the document and
  prints it when staff fix the printer. The counter screen shows the printer needs attention.
- Files are private (Supabase private bucket, 5-minute links), and are deleted after printing.
- Secret keys live only in `backend/.env` and the Xerox PC's `agent.yml` — never in the web page or the app.

## Going live (after START-HERE works)

1. **Put the backend online with HTTPS.** Easiest: push this folder to GitHub, then render.com → New →
   Blueprint → pick the repository (`render.yaml` does the rest) and paste the values from `backend/.env`.
   Any Docker host works too (`backend/Dockerfile`). You get an address like `https://campus-print-backend.onrender.com`.
2. **Put the student website on Netlify.** Set `apiBase` in `web/config.js` to that https address, then drag the
   `web` folder onto app.netlify.com/drop (or connect the repository with base directory `web`). Put the Netlify
   address (e.g. `https://campus-print.netlify.app`) in `WEB_ORIGINS` on the backend and restart it.
   `web/netlify.toml` keeps the staff counter off the public site.
3. **Install Campus Print Station on the Xerox PC** (`installer/HOW-TO-INSTALL.txt`). For a college, build an
   installer with the address built in: `build-installer.ps1 -BackendUrl "https://..."`.
4. **Razorpay live mode:** finish KYC, generate live keys (`rzp_live_...`), set them in `.env`, restart.
   Refunds for problem orders are done in the Razorpay dashboard (the counter screen shows the payment id).
5. **Android:** set `API_BASE` to the https address and build a signed release (Build → Generate Signed Bundle/APK).
6. Change `COUNTER_PASSWORD` to something strong. Remove `PAYMENT_MODE=demo` — demo mode prints without payment.

## Honest note

Everything here is built and tested end to end on a Windows laptop: the backend against a real Supabase
project, the Station installed from its installer and printing to virtual PDF printers, the student website
driven in Microsoft Edge. Two things can only be checked on site: printing on the real Canon printers (use
the Station's "Test print" buttons) and the Docker/Render deployment (no Docker on the test laptop). Follow START-HERE step by step: each step tells you exactly what "working" looks like, so a
problem shows up at the step where it is, not at the end.
