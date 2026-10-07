# How it works

## The parts

```
 students: web/index.html, XeoGo app        ─┐                         ┌─ Supabase Postgres (orders, their files,
 staff:    web/staff.html, XeoGo Staff app  ─┼─► backend (Spring Boot) ─┤   printers, prices, staff IDs)
 Xerox center's phone: XeoGo Pay Verifier   ─┘        ▲                 └─ Supabase Storage (private bucket: the files)
                                                      │ HTTPS, PC always calls out
                                               Xerox center PC (XeoGo Station: printing, counter, staff IDs)
                                                      │ Windows print queue + printer drivers
                                               Printer 1   Printer 2   Printer 3   Printer 4 (colour)
```

Phones and browsers **never** talk to the database. Only the backend does, with its own connection.
The database has row level security switched on and gives nothing to Supabase's public keys.

**The database keeps itself up to date.** `db/setup.sql` owns the tables and the rules and can be run again and
again. A copy travels inside the server (`backend/src/main/resources/db/setup.sql`); when the server starts it
compares that copy with what the database was last brought up to (`schema_version`) and, if it differs, runs it
in one transaction (`DatabaseSetup.java`). So a new version goes online with one push: nobody runs SQL by hand.
If the update cannot be done the new server does not start (the host keeps the previous one running).

## One order, many files

A student puts **all their files in one order** (up to 25: PDFs, JPGs and PNGs mixed) and sets up each
file on its own: pages, copies, colour, sides, paper size, layout, finishing. They pay once and collect
everything together, by showing their files (see "Collecting").

In the database an order (`orders`) has one row per file (`order_documents`). **Each file is its own print
job**: it has its own settings, price, status and printer. The order's status follows from its files.

Example (the one this was designed around), all in one order:

| File | Settings | Goes to |
|---|---|---|
| Notes.pdf | pages 1–10, 1 copy, B/W, two-sided | a B/W printer that can print two-sided |
| Assignment.pdf | pages 3, 7, 2 copies, colour, one-sided | the colour printer |
| Photo.jpg | 1 copy, colour, A4, fill the page | the colour printer |

## An order's life

| Order status | Meaning |
|---|---|
| `AWAITING_UPLOAD` | files are being added and set up |
| `AWAITING_PAYMENT` | the server checked and priced every file (the student is on "Review and pay") |
| `QUEUED` | paid, no file has started printing yet |
| `PRINTING` | at least one file is at a printer |
| `COMPLETED` | every file is printed (or cancelled by staff): ready at the counter |
| `FAILED` | a paid file had a problem: shows at the counter |
| `CANCELLED` / `EXPIRED` | by the student or staff / never paid (24 h) or never finished uploading |

| File status | Meaning | Who moves it |
|---|---|---|
| `UPLOADING` | being sent to storage | student |
| `READY` / `REJECTED` | checked: pages counted / not a usable file | backend |
| `QUEUED` | paid, waiting for a printer that can do it | backend |
| `CLAIMED` → `DOWNLOADING` → `SUBMITTED` | a printer took it, the PC fetched it, Windows has it | PC |
| `COMPLETED` / `FAILED` / `CANCELLED` | printed / problem / cancelled by staff | PC / staff |

Triggers in the database (`guard_order_transition`, `guard_document_transition`) refuse any other move, and
`sync_order_status` keeps a paid order's status in line with its files, so a bug cannot put anything into an
impossible state. Every change is written to `order_events` (with the file it was about).

"Change something" after "Review and pay" puts the order back to `AWAITING_UPLOAD` (`POST /orders/{id}/edit`),
so files can be added, removed or changed; the next review checks and prices everything again.

## No login: how an order stays private

When the website creates an order, the backend makes a random 256-bit **access key**, returns it once, and
stores only its SHA-256. The website saves it on the device and sends it as `X-Order-Key`. Without it,
nobody can see, change, pay for, or cancel that order. Each order also has a short **order number** (5
characters, no look-alike letters): the label on the printed pages, and a way to search. It opens nothing.

A **staff order** is different: it belongs to a staff ID, not to a device. It answers to any device signed in
with that ID (`X-Staff-Session`), and to nobody else; its key alone is not enough (see "Staff printing").

The website also keeps the unfinished order (files and settings, not the files' contents) in the browser, so
a reload or a closed tab comes back to the same place; the files are fetched again from storage.

## What the printers can do decides what students see

The website only offers what the connected printers can really do. Nothing is hard-coded.

1. **The Station reads each printer** when it starts, when printers change, when staff press **Scan again**,
   every 30 minutes, and at once when a printer turns out unable to do something. It asks Windows three ways:
   the driver's own *PrintCapabilities* (the document behind the printer's settings dialog), Java's view of the
   same driver, and WMI. Only what can really be asked for when printing counts: colour, two-sided (long /
   short edge), paper sizes (only those both Java can request and the driver lists), finishing (staple
   positions, hole punch, binding), paper types, borderless, print quality, trays, most copies. This is the
   printer's `capabilities` in the database, with a hash so only real changes are sent.
2. **Staff choose what to offer** on each printer (Station → Printers → "What students can choose on this
   printer"): which paper sizes they keep in stock, two-sided on/off, which finishing, which paper types.
   This is `offered`. By default: A4, A3, A5, Legal, Folio if the printer takes them, two-sided if it has it;
   **no finishing** (staff tick each staple / punch / binding option after a test print with it came out
   right: drivers often list a finisher that is not fitted) and no special paper types.
3. **What students get** is capabilities ∩ offered = `effective`, worked out by the server
   (`EffectiveFeatures`). A printer switched off at the counter ("Taking orders" off) offers nothing. A printer
   that is only offline for a while (paper jam, PC restarting) keeps its options: its files wait for it, and
   the website shows "Printers offline". The website asks for this (`GET /api/v1/shop`) on start, every minute, and when the tab
   comes back into view, and updates the choices on screen straight away.

On the website a choice appears only if **some** printer can do it. If it cannot be combined with the
student's other choices, it says what it would change (e.g. *A3 → black & white*); picking it makes that
change and says so. If the printers change while a student is setting up (a printer switched off), the file
shows what no longer works and a **Change to …** button that makes the smallest change that can be printed.

### Which printer prints a file

`claim_next_job(pc, printer)` in `db/setup.sql`. A free printer asks for work and gets **one** file:

```
only files this printer can do completely (printer_can_do: colour, paper size, sides, finishing,
   paper type, borderless, quality)
this printer's own started orders first, and leave orders another printer is busy with
   → one order's files come out of one printer when possible
then the oldest paid order; colour printers take colour work first
FOR UPDATE SKIP LOCKED → two printers asking at the same moment never get the same file
```

A paid file that **no** printer can do any more (e.g. the only two-sided printer broke) is shown at the counter
as *"No printer can do this now"*; staff can cancel it (refund) or wait for the printer.

Stations older than 4.0 still work: they only get plain A4 one-sided files (`legacy_ok`), everything else waits
for a 4.0 Station.

## Settings of one file

| Setting | Choices | Notes |
|---|---|---|
| Pages (PDF) | all, or e.g. `3, 7, 10-12` | typed, or picked on page pictures (All / None / Odd / Even / Invert). Shown as *"Selected: 3, 7, 10–12 · Total pages to print: 5"* |
| Copies | 1, 2, 3 or any number up to 50 | collated (1-2-3, 1-2-3) or not (1-1, 2-2, 3-3) |
| Colour | black & white / colour | |
| Sides | one-sided / two-sided, long edge / short edge | only if a printer can |
| Paper size | A4, A3, A5, Legal, Folio, Letter, photo sizes… | only sizes a printer takes and staff offer |
| Orientation | auto / portrait / landscape | auto turns each sheet to fit its page |
| Scale | fit, actual size, custom % (pictures: also fill) | |
| Pages per sheet | 1, 2, 4, 6, 9, 16 | pages are fitted in a grid with a 3 mm gap |
| Margins | none (borderless), 5, 10, 20 mm, custom | none only on a borderless printer |
| Pictures | turn left / right, centre | **proportions are always kept: never stretched** |
| Finishing | staple, hole punch, bind (positions the printer has) | only if a printer has it; needs 2+ sheets |
| Paper type, quality | as offered by staff | |

**The server has the last word.** `POST /orders/{id}/review` sends every file's settings; `SettingsChecker`
checks and tidies them against the file (real page count, picture size) and the printers, and prices them.
What the student then sees on "Review and pay" is exactly what is stored and printed. The website only shows
the server's answer; if the server refuses a file, the file shows why and nothing is charged.

### The same rules in three places, checked by one set of tests

The page-range reader, prices, printer rules and page layout exist in Java (backend and Station), in SQL
(`printer_can_do`) and in JavaScript (`web/js/print-core.js`, for the live price and preview). The cases in
`spec/cases/*.json` are run against all of them (`SharedRulesTest`, the Station's tests, `node
spec/web-core.test.js`), so they cannot drift apart.

## What comes out of the printer is what the preview shows

The website draws the preview from the same layout rules the Station prints with (`layout.json` cases):

- Each sheet is made at the exact paper size, and printed at **100 %** from the paper's corner
  (`ExactPageable`): the driver is not allowed to shrink it again.
- A PDF page is placed as it looks on screen (its rotation and crop box are applied); filled-in form fields
  and other printable notes are drawn too.
- Pictures are turned upright from their EXIF orientation, then turned as the student chose, then fitted,
  filled or placed at actual size. Width and height are always scaled by the same factor.
- Several pages per sheet: the grid (1×2 or 2×1, 2×2, 2×3 or 3×2, 3×3, 4×4) that makes the pages largest.
- Two-sided with an odd number of sides: a blank back is added so the next copy starts on a new sheet.
- Settings Java's printing cannot express (staple, punch, bind, borderless, paper type, quality, sometimes
  two-sided) go into a Windows **print ticket**. The Station checks the driver kept every option; if the
  driver drops one, the file is **not** printed and goes back with a clear message. The printer's own default
  settings are restored afterwards.

## Matching paper to students (no cover sheet)

The order number is printed **small in the bottom-right corner of the first sheet of each file**:

    [ Order K7M4X  ·  2/3  ·  3 sheets × 2 ]

(file 2 of the order's 3, 3 sheets per copy, 2 copies). Every copy starts with that sheet, so staff see where
each file (and each copy) begins in the tray, and how many sheets to count. The label sits in the white margin
at least 6 mm from the edge. Borderless prints get no label. If adding it ever fails, the file prints with a
cover sheet instead. The **"Order number on pages"** switch in the Station turns it off for everyone
(`shop_settings.stamp_code`); `agent.yml` `coverSheetMinSheets: 30` adds a cover sheet only for thick files.

## Word files

A student adds a Word file (`.docx`) like any other file. What happens to it:

1. **The server looks inside it** (`storage/DocxInspector`) before anything opens it. A `.docx` is a ZIP of
   XML parts; it is read strictly (the directory at the end, each part once, sizes and checksums right,
   nothing encrypted, no huge unpacking) and only a plain document passes: text, tables, pictures, charts,
   headers, page numbers, a table of contents, hyperlinks. Refused, with "save it as PDF": macros and ActiveX,
   embedded objects of the old kind (OLE), pieces of other documents merged in on opening, anything loaded
   from outside the file (linked pictures, a template on a server, a mail-merge source), fields that read
   other files or run commands (and fields whose name another field puts together), embedded fonts, EPS
   pictures, web add-ins. Old `.doc` files cannot be looked into like this and are not accepted.
2. **The Xerox PC turns it into pages.** The document waits (status `CONVERTING`); the Station asks for the
   next one (`POST /agent/v1/conversions/claim`), fetches exactly the file the server checked (same
   SHA-256, or it is not opened), lets its own Microsoft Word save it as PDF, puts the PDF where the server
   said, and reports `done`. The server checks that PDF like any upload (the PC is trusted to print, not to
   vouch for a file) and the document **becomes that PDF**: page count, preview, every setting, price and
   printing are the PDF's. The file keeps its name; `source_type` remembers it was Word.
3. **The student's device** shows "Turning it into pages at the Xerox center…" (with how many are ahead),
   then fetches the pages and shows them like any PDF. What is seen before paying is what prints.

Word runs as a second, hidden Word that belongs to the Station (`agent/.../word/WordEngine`, helper
`word-worker.ps1`): alerts and macros off, files opened read-only, tracked changes and comments left out,
none of the shop's Word settings touched. It starts with the first file, stays while files keep coming (about
half a second each), and closes after 20 quiet seconds. Every step has a time limit; a Word that stops
answering is ended and replaced. If Windows hands a file somebody opened by double-click to the hidden Word
(it does when no other Word is open), that Word is shown and left to them, and the Station starts another.

Whether a PC can do this is tried, not guessed: at start the Station turns a test page into a PDF, and only
then tells the server (`wordFiles` in the heartbeat). Students are offered Word files only while such a PC is
online (`wordFiles` in `/api/v1/shop`); otherwise they are told so before anything is uploaded. Nothing waits
for ever: a file in line for ten minutes, one two tries gave up on, or one whose PC went offline is refused
with the advice to save it as PDF.

## Collecting: show your files (no pickup code)

Nobody shows or types a code. What replaces it:

1. **After paying, the files stay visible on the phone.** The website and the app keep a small picture of every
   file's first sheet, drawn exactly as it prints (the uploaded files themselves are not kept on the phone), with
   the times: *Paid 10:42 · Ready at about 10:47* (an estimate from the queue: sheets ahead × the measured seconds
   per sheet, `ReadyEstimate`), then *Ready since…*, then *Collected…*.
2. **The Xerox PC makes the same picture** from what it really printed (`SheetPicture`, sent with
   `POST /agent/v1/jobs/{id}/preview`, kept in `document_previews` until the order is handed over). The counter
   shows it next to each file. A phone that has no picture of its own (another device) fetches this one.
3. **"I'm at the counter"** (`POST /orders/{id}/arrive`). Only the device that holds the order's key (or the staff
   sign-in) can say it. The order then appears in the green box **At the counter now** on the Xerox center's
   screen, with its pictures, within a few seconds, and stays while the phone keeps saying it (every 45 seconds;
   it wears off after 3 minutes).
4. The Xerox center compares phone and screen, hands over the pages, presses **Handed over**
   (`POST /counter/orders/{id}/collected`). The phone turns to **Collected**: the pages went to the phone that
   ordered them. One order is handed over once.

Why this cannot be cheated: a screenshot of someone's order, or the same PDF on another phone, never appears on
the counter's screen, because only the ordering device can put it there. And a phone that shows "Collected" was
handed its pages.

No internet at the counter: the order still opens (the last answer of the server and the pictures are kept on
the device) and says so; the Xerox center types a file's name or the order number in **Find**, sees the order
with its pictures, and confirms a question before handing over.

## Staff printing (free, with a staff ID)

College staff print for free, up to a number of pages a month. No payment and no code are involved.

- **The staff ID** is a username and a password, made in the Station (**Staff** screen; `POST /counter/staff`).
  The server makes the password (10 random characters), shows it once, and keeps only its bcrypt hash. Eight
  wrong passwords in a row make an ID wait 15 minutes; sign-in tries are also limited per network address.
  Every failure reads the same, so nothing tells whether a username exists.
- **Signing in** (`POST /staff/login`) gives the device a sign-in token (HMAC, 90 days, renewed while in use). The
  token names the password it was made with: a **new password**, **switching the ID off** or **removing** it signs
  every device out at once (`StaffSessions`, `StaffService.session`).
- **A staff order** is made by `POST /orders` with the sign-in. It takes the same steps as a student's, except:
  `review` counts instead of pricing (`freePages` = printed sides × copies, amount 0), and
  `POST /orders/{id}/staff-print` replaces payment.
- **The limit is kept in the database.** `staff_print()` locks the staff ID's row, adds up what the ID used since
  the 1st of the month (`staff_pages_used`: only files that printed or are printing; failed and cancelled ones do
  not count), refuses when the order does not fit, and otherwise queues it, all in one step. Two orders sent at
  the same moment are counted one after the other.
- **The two kinds of order cannot be mixed up.** `mark_order_paid()` is the only way into the print queue, and it
  lets a staff order in only as `staff` and an ordinary order never as `staff`. So no payment route can print a
  staff order, and no student order can be printed for free, whatever the apps send.
- **Colour** and special paper are free only if the Xerox center says so (Staff → switch); otherwise the staff
  site does not offer them, the review refuses them, and `staff_print()` refuses them again.
- The month is the calendar month in `SHOP_TIME_ZONE` (default Asia/Kolkata). The usual number of pages
  (`shop_settings.staff_monthly_pages`, default 1000) and one ID's own number are set on the Staff screen.
- On the counter a staff order shows **Staff · free** and the person's name, so the pages go to the right hands.

## Price and payment

```
per printed side = B/W or colour price × paper size % × paper type %     (rounded to the paise)
per copy         = printed sides × per side + finishing (staple, punch, bind)
file             = copies × per copy                  order = sum of its files (Razorpay minimum ₹1)
```

Prices and the extra percentages (e.g. A3 = 200 %) and finishing prices are set in the Station
(Settings → Shop, and "Paper sizes, paper types and finishing"); they are in `shop_settings`. A two-sided sheet
is two printed sides.

1. Each file is uploaded straight to storage with its own 5-minute upload link (`POST
   /orders/{id}/documents/{doc}/upload-url`); up to 3 at a time, each can be cancelled and tried again.
2. `POST .../documents/{doc}/uploaded`: the backend downloads the file, checks what it really is (first bytes,
   not the name), opens it (PDFBox / ImageIO), counts pages, reads picture size, resolution and EXIF turn.
   Locked or broken files are refused here, before any payment.
3. `POST /orders/{id}/review` checks and prices every file (above). `POST /orders/{id}/payment` makes a
   **Razorpay order** for that exact amount.
4. On success the website sends `payment_id` + `signature` to `POST /orders/{id}/payment/confirm`. The backend
   checks `HMAC-SHA256(our_razorpay_order_id + "|" + payment_id, key_secret)`, asks Razorpay for the payment,
   checks order id and amount, and captures it if it is only "authorized". `mark_order_paid` then puts every
   file in the queue in one step.
5. Safety net: every minute the backend asks Razorpay about unpaid orders, so a student who closed the page
   right after paying still gets printed.

`PAYMENT_MODE=upi` is XeoGo Pay (see `docs/campuspay-upi.md`). `PAYMENT_MODE=demo` replaces steps 3–4 with a
test button; the backend log and counter screen warn loudly. With no `PAYMENT_MODE` at all, paying is switched
off (`ClosedGateway`): nothing ever prints for free because a setting was forgotten.
The Android app (3.0.0) uses the same endpoints and settings as the website; its rules (`core/PrintCore.kt`) run
the shared `spec/cases`, and its tests check against the real backend that the reviewed settings, prices and the
print jobs the PC receives are identical. Older app versions (one file per order, `/orders/{id}/uploaded`) keep working.

## Why a file is never printed twice by accident

On the PC, for each file:

0. only when Windows says the printer is ready (else the file stays on the server)
1. tell the backend `DOWNLOADING`
2. download, check size + type + SHA-256, and lay out the sheets — a problem here gives the file back (nothing printed)
3. tell the backend `SUBMITTED` — **if this fails, do not print**
4. write `journal/<file>.sent` to disk and force it onto the disk
5. print — *point of no return*
6. watch the Windows queue until the document leaves it
7. tell the backend `COMPLETED` / `FAILED` — if offline, the journal keeps the result and sends it later

If the PC disappears:
- before step 5 → the backend's recovery (lease expiry) puts the file back in the queue (safe, nothing printed);
- after step 5 → the file becomes `FAILED` with "check the tray". Only a person pressing **Print again** on the
  counter prints it again. When the PC comes back it reports `COMPLETED` from its journal if it did print.

Paper out, jam, offline printer: Windows keeps the document, so the file stays "printing" and the counter
shows the printer lamp amber with the reason. It finishes by itself once staff fix the printer.

## XeoGo Station (the Xerox center app)

One installer (`installer/XeoGoStation-Setup-<version>.exe`, built by `agent/packaging/build-installer.ps1`).
It updates an older "Campus Print Station" in place and keeps its settings.
It contains a trimmed private Java made with `jlink` and a launcher made with `jpackage`, wrapped by Inno Setup,
so the PC needs nothing else. It installs per user (no administrator), adds shortcuts and an uninstaller.

- **Setup wizard** (first start): server address + counter password + PC name. The Station registers itself
  (`POST /api/v1/counter/pcs`, the server runs `enroll_agent`) and keeps its sign-in in
  `%LOCALAPPDATA%\CampusPrint\station.json`.
- **Printers**: every Windows printer with what it can do (paper sizes, two-sided, finishing…); virtual printers
  (PDF, OneNote, Fax) are marked. Ticked printers take orders; "What students can choose on this printer" sets
  what is offered; **Test B/W** / **Test colour** / **Test two-sided** print a test page.
- **Printing**: one worker per printer, each asks for the next file it can do. It keeps printing when the window
  is closed (tray icon), and starts with Windows.
- **Printer offline**: every heartbeat the Station asks Windows whether each printer is ready (offline, out of
  paper, jam, door open, paused...). A printer that is not ready takes no new files: paid files wait on the
  server, where another printer that can do them may take them, and the counter shows why. A file that was
  already in a Windows queue keeps waiting there and prints when the printer is fixed.
- **Restart safety**: the journal also records which printer and Windows queue entry a file went to. If the
  Station restarts while a file still waits in a Windows queue, it watches that entry again and reports it
  only when it really leaves the queue; it never sends the file a second time. If the queue cannot be read
  for 5 minutes the file is reported as "could not confirm: check the tray", never as printed.
- **Counter**: **At the counter now** (students standing there, with pictures of their files) and **Handed
  over**; **Find** by file name; orders with their files; per file: printer, sheets, **Print again**, **Cancel**;
  a red banner when a paid file waits that no printer can do. Staff orders show **Staff · free** and the name.
- **Staff**: make a staff ID (the password is shown once: copy it or print the slip), new password, pages per
  month, switch off, remove.
- The counter signs in once with the counter password and then uses a sign-in token, so it keeps working
  while the server pauses password sign-ins because someone on the internet keeps guessing.
- **Screens** are served on `127.0.0.1:47800` and shown in a Microsoft Edge app window; each call needs the random
  token the window was opened with, and requests that come from another website are refused.
- Logs: `%LOCALAPPDATA%\CampusPrint\logs`.

For a new printer model, `PrinterSmokeTest` shows what the Station reads and prints any combination by hand:

    java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest --features "Canon iR2625"
    java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest "Canon iR2625" file.pdf --duplex long --nup 2 --staple top-left

## Files

- Private bucket `print-documents`, max 50 MB per file, only PDF/PNG/JPEG. Up to 25 files per order, up to 2000
  pages per PDF, up to 300 pages printed per file (`application.yml`).
- Uploads and downloads use 5-minute signed links made by the backend.
- On the PC, files sit in the Station's own work folder and are deleted after printing.
- In storage: deleted after printing; unpaid or cancelled after expiry; failed paid files kept 24 h (for "Print again").
- First-sheet pictures: at most 200 KB each, in the database (`document_previews`), deleted when the order is
  handed over and after 7 days in any case.
- Orders that were never paid are removed two weeks after they ended.
