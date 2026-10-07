# XeoGo — QuICK PrINT

*Xerox center remote printing. Designed & developed by Vedant Pravin Surve.*

Students anywhere on campus send files from their phone or laptop, pay online, and collect the printout at the
**one Xerox center** by **showing their files on their phone**. No accounts, no login, no code to remember.

College staff print **for free** with a staff ID (a username and a password made at the Xerox center), up to a number
of pages every month.

**New here? Open [`START-HERE.md`](START-HERE.md) and follow the steps in order.**

## The parts

| What | Who uses it | Where it is |
|---|---|---|
| **XeoGo** website and Android app | students | `web/` (`index.html`), `android/app` (the "student" app) |
| **XeoGo Staff** website and Android app | college staff, free printing | `web/staff.html`, `android/app` (the "staff" app) |
| **XeoGo Station** (Windows) | the Xerox center: prints, counter, staff IDs | `agent/`, installer in `installer/` |
| **XeoGo Pay Verifier** (Android) | the Xerox center's phone: confirms UPI payments | `android/verifier` |
| The server | everything talks to it | `backend/`, `db/` |

## What a student does

1. Opens the website (or the app) and adds **all their files at once**: PDFs, Word files (.docx), JPGs and PNGs, dragged in, picked,
   shared or pasted (up to 25 files, 50 MB each). Each file uploads with its own progress bar and can be cancelled,
   tried again, removed or moved up and down (the list order is the print order).
2. Sets up **each file on its own**, with a live **print preview** of every sheet:
   - **pages** (typed like `3, 7, 10-12`, or tapped on the page pictures)
   - **copies**, **black & white or colour**, **one- or two-sided**, **paper size**, orientation, scale,
     **pages per sheet**, margins, and **staple / hole punch / binding** when a printer has them
   - pictures: fit, fill, actual size or custom size, turn left / right, borderless — **never stretched**
3. Only choices the connected printers can really do are shown. The price updates live.
4. **Review and pay**: the server checks and prices every file; the student sees exactly what will be printed and
   pays once. With **XeoGo Pay** (the center's own UPI gateway) the phone shows its UPI apps, a laptop a QR code; the
   money goes straight to the center's bank account and is confirmed automatically
   ([`docs/campuspay-upi.md`](docs/campuspay-upi.md)). Razorpay is the other payment mode.
5. **The files go to the printer, and stay on the student's phone**: the order shows a picture of every file's
   first sheet, exactly as it prints, with the times: *Paid 10:42 · Ready at about 10:47*, then *Ready since…*
6. **At the counter** the student opens the order and taps **I'm at the counter**. The order appears on the Xerox
   center's screen, with the same pictures. They hand over the pages that look the same and press **Handed over**;
   the phone shows **Collected**.

A Word file is checked by the server (nothing in it that can run or fetch), turned into pages by the Xerox PC's own
Microsoft Word, and is from then on a PDF like any other: every setting, the same preview, the same price.

No pickup code is shown or typed anywhere. A screenshot, or the same PDF on a friend's phone, cannot collect: only
the phone that made the order can put it on the counter's screen. No internet at the counter? The order still opens
with its pictures, and the Xerox center finds it by a file's name.

## What a staff member does

1. Gets a **staff ID** at the Xerox center: a username and a password, nothing else (the password is made by the
   server, shown once, and only its hash is kept).
2. Signs in on `staff.html` or in the **XeoGo Staff** app. The top of the page says how many **free pages** are left
   this month (one printed side is one page; the count starts again on the 1st).
3. Adds files and sets them up like a student, then presses **Print now · free**. No payment of any kind.
4. Collects the same way: **I'm at the counter** on any phone signed in with the ID (send from the office
   computer, collect with the phone).

The Xerox center decides the number of free pages (for everyone, or per person), whether colour is free too, and
can give a new password, switch an ID off, or remove it: any of these signs every device out at once.

## What happens in the Xerox center

- One Windows PC is connected to the printers. It runs **XeoGo Station**: one installer
  (`installer/XeoGoStation-Setup-4.3.0.exe`) with its own Java inside. A setup wizard connects the PC to the server
  and **scans the printers**; after that the Station shows the counter and **prints paid files by itself**, starts
  with Windows and keeps printing from the tray.
- The Station **reads what each printer can do** and keeps the server up to date; the Xerox center ticks what to
  offer. The website offers exactly that, and nothing a printer cannot do.
- **A printer that is offline, out of paper or jammed takes no new files.** Paid files wait safely and print once
  a printer that can do them is ready. Nothing is ever printed twice by accident.
- **Every file is its own print job**, sent to a printer that can do everything it needs. All printers work at
  the same time. While it prints, the Station sends a small picture of the first sheet to the server: that is the
  picture the counter shows.
- **The counter** (in the Station; `web/counter.html` is the same in a browser): the green box **At the counter
  now** with the students who are standing there and pictures of their files, **Handed over**, **Find** by file
  name, *Print again* / *Cancel*, printer lamps, prices, UPI payments, and the **Staff** screen for staff IDs.
- Each file's first sheet carries a small label (`Order K7M4X · 2/3 · 3 sheets`), so piles are easy to tell
  apart. Nobody has to read or type it.

## Folders

| Folder | What it is |
|---|---|
| `db/` | `setup.sql`: tables, the print queue and the safety rules. The server runs it by itself (see below); `reset.sql` |
| `backend/` | Spring Boot service: orders and their files, checks and prices, payment, staff IDs, counter API, PC API |
| `agent/` | XeoGo Station (the Xerox PC app: `station/` + screens in `resources/station-ui/`), printer discovery, printing |
| `agent/packaging/` | `build-installer.ps1` makes the installer (jlink + jpackage + Inno Setup) |
| `installer/` | `HOW-TO-INSTALL.txt` for the person installing the Station (the built installers are put here) |
| `web/` | The websites for Netlify: `index.html` (students), `staff.html` (staff; made from index.html by `spec/staff-page.js`), `css/`, `js/` (`print-core.js` = the shared rules, `app.js` = both sites' screens), `vendor/` (PDF.js and the QR library, kept with the site), `poster.html`, `counter.html`, `config.js`, `_headers` and `_redirects` (what Netlify sends with every page), `netlify.toml` |
| `android/` | `app`: one code, two apps (`student` = XeoGo, `staff` = XeoGo Staff). `verifier`: XeoGo Pay Verifier |
| `branding/` | `logo.png` and `make-icons.py`, which makes every icon of every app from it |
| `spec/` | Shared test cases (pages, prices, printer rules, layout) checked against the server, the Station, the database, the website and the app; `web-pages.test.js` checks the pages themselves |
| `render.yaml`, `backend/Dockerfile` | Put the backend online with HTTPS (Render or any Docker host) |
| `docs/` | `how-it-works.md`, `campuspay-upi.md`, `troubleshooting.md` |

## Money and safety, in short

- **The price is always worked out by the server** from the real uploaded files and the checked settings, never
  from what the phone says. What "Review and pay" shows is exactly what is printed.
- **Nothing prints before it is paid for.** XeoGo Pay: an order is paid only when a message proves the money
  arrived, and only a message nobody else could have written counts: an SMS from the bank's own sender name
  (never from a phone number) or a notification of a business UPI app (never a chat app). One payment can never
  pay two orders. Razorpay: signature + asking Razorpay directly.
- **`PAYMENT_MODE` must be set** (`upi`, `razorpay`, or `demo` for testing). If it is missing, paying is switched
  off: there is never free printing by mistake.
- **Staff printing cannot be turned into free printing for others**: a staff order answers only to its staff ID,
  the month's pages are checked and the order is queued in one database step, a staff order can never be "paid",
  and an ordinary order can never be sent as a staff one (the database keeps the two apart).
- **Collecting**: an order appears on the counter's screen only when the device holding its private key (or the
  staff sign-in) says so, and one order is handed over once.
- **A file is never printed twice by accident**, and never printed differently from what was chosen.
- Files are private (Supabase private bucket, 5-minute links) and are deleted after printing. The small pictures
  of first sheets are deleted when the order is handed over.
- The counter password is limited against guessing; the Xerox center's own screens stay signed in with a token.
- Secret keys live only in the server's settings and on the Xerox PC — never in a web page or an app.

## Putting a new version online

**Push the code. That is all.**

- The backend (Render, `render.yaml`) builds and starts the new version by itself. When it starts it **brings the
  database up to date by itself** (`DatabaseSetup`: a copy of `db/setup.sql` travels inside the server and runs in
  one transaction whenever it changed). If that cannot be done, the new version does not start and the previous
  one keeps running.
- The websites (Netlify, base directory `web`) are published from the same push.
- The Station and the apps are installed from the files built on this computer (`installer/`, the APKs).

After changing `db/setup.sql`: copy it to `backend/src/main/resources/db/setup.sql` (a test fails if they differ)
and raise the version number in its last statement.

## Going live the first time (after START-HERE works)

1. **Backend**: push this folder to GitHub, then render.com → New → Blueprint → pick the repository and paste the
   values from `backend/.env`. You get an address like `https://campus-print-backend.onrender.com`.
2. **Websites**: set `apiBase` in `web/config.js` to that https address; connect the repository on Netlify with
   base directory `web` (or drag the `web` folder onto app.netlify.com/drop). Put the Netlify address in
   `WEB_ORIGINS` on the backend. Students open `/`, staff open `/staff` (or `/staff.html`).
3. **XeoGo Station** on the Xerox PC (`installer/HOW-TO-INSTALL.txt`). For one college, build an installer with the
   address built in: `build-installer.ps1 -BackendUrl "https://..."`.
4. **Payments**: **XeoGo Pay** (your own UPI, no fees): `PAYMENT_MODE=upi`, `UPI_ID`, `UPI_NAME`, `UPI_ALERT_TOKEN`,
   and the XeoGo Pay Verifier app on the shop phone — [`docs/campuspay-upi.md`](docs/campuspay-upi.md). Or Razorpay.
5. **Apps**: `gradlew assembleDebug` in `android/` makes `app-student-debug.apk` (XeoGo), `app-staff-debug.apk`
   (XeoGo Staff) and `verifier-debug.apk` (XeoGo Pay Verifier).
6. **Staff**: Station → **Staff** → make the staff IDs and set the free pages per month.
7. Use a strong `COUNTER_PASSWORD`. Never run real use with `PAYMENT_MODE=demo`: demo mode prints without payment.

## Honest note

Everything here is built and tested end to end on a Windows laptop: the server and database rules with automatic
tests against a real PostgreSQL 17 (the same version as Supabase), the Station printing through the real Windows
print path to virtual PDF printers, and the student and staff websites driven in Microsoft Edge on phone and
laptop sizes, including orders printed by the real Station code. Some things can only be checked on site:
stapling, hole punching and binding on the real printers (each driver names them differently: print one test
order per option), colour vs. grey on real paper, and the bank's real SMS sender name (the counter shows any
message it did not count, with a one-press **This is our bank**). Follow START-HERE step by step: each step tells
you exactly what "working" looks like, so a problem shows up at the step where it is, not at the end.
