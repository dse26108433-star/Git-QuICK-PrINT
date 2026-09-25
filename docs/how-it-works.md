# How it works

## The parts

```
 web/index.html  ─┐                              ┌─ Supabase Postgres (orders, printers, prices)
 Android app     ─┼─► backend (Spring Boot) ─────┤
 web/counter.html ┘        ▲                      └─ Supabase Storage (private bucket: the files)
                           │ HTTPS, PC always calls out
                    Xerox center PC (agent, Windows service)
                           │ Windows print queue + Canon drivers
                    Printer 1   Printer 2   Printer 3   Printer 4 (colour)
```

Phones and browsers **never** talk to the database. Only the backend does, with its own connection.
The database has row level security switched on and gives nothing to Supabase's public keys.

## An order's life

| Status | Meaning | Who moves it |
|---|---|---|
| `AWAITING_UPLOAD` | order created, file being sent | student app |
| `AWAITING_PAYMENT` | file checked, pages counted, price fixed | backend |
| `QUEUED` | paid, waiting for a printer | backend (after verifying payment) |
| `CLAIMED` | a printer took it | PC |
| `DOWNLOADING` | PC is fetching and checking the file | PC |
| `SUBMITTED` | handed to Windows for printing | PC |
| `COMPLETED` | left the printer queue: pages are out | PC |
| `FAILED` | problem; paid ones show at the counter | PC / backend |
| `CANCELLED` | before payment by student, or by staff | student / staff |
| `EXPIRED` | never paid (24 h) or never uploaded (1 h) | backend |

A trigger in the database (`guard_order_transition`) refuses any other move, so a bug cannot put an
order into an impossible state. Every change is written to `order_events`.

## No login: how an order stays private

When the app creates an order, the backend makes a random 256-bit **access key**, returns it once, and
stores only its SHA-256. The app saves the key on the device and sends it as `X-Order-Key`. Without it,
nobody can see, pay for, or cancel that order. The **pickup code** (5 characters, no look-alike letters)
is only for the counter and the label on the printed pages.

## Matching paper to students (no cover sheet)

Printing a separate cover sheet for every order wasted one sheet per order, which is a lot for 1-3 page
orders. Instead, the agent prints the pickup code **small in the bottom-right corner of the first page**:

    [ Pickup K7M4X · 3 pages × 2 ]

- Every copy starts with that page, so staff see where each order (and each copy) begins in the tray,
  and how many sheets to count (pages × copies).
- The student's layout is kept. The agent first draws page 1 at low resolution and checks the corner.
  If it is blank (almost always) the label goes into the margin. If something is there (page number,
  full-page photo), only page 1 is shrunk by about 4 % to make a white strip. Filled-in form fields move
  with the page.
- The label is at least 6 mm from the paper edge, inside the area every printer can reach.
- Only the copy in memory is changed. If adding the label fails for any reason, the order still
  prints, with a cover sheet instead.
- `agent.yml`: `pickupCodeOnPage: true` (default) and `coverSheetMinSheets: 0` (default, never).
  Set for example `coverSheetMinSheets: 30` to add a cover sheet only for thick orders of 30+ sheets.
  With `pickupCodeOnPage: false` every order gets a cover sheet (the old behaviour).

At the counter: the student shows the code on their phone, staff type it in the **Code** box, see the
printer and number of sheets, take the pages whose first page shows that code, and press **Handed over**.

## Campus Print Station (the Xerox center app)

One installer (`installer/CampusPrintStation-Setup-<version>.exe`, built by `agent/packaging/build-installer.ps1`).
It contains a trimmed private Java made with `jlink` and a launcher made with `jpackage`, wrapped by Inno Setup,
so the PC needs nothing else. It installs per user (no administrator), adds shortcuts and an uninstaller.

- **Setup wizard** (first start): server address + counter password + PC name. The Station checks the password,
  then registers itself with `POST /api/v1/counter/pcs` (the server runs `enroll_agent` and returns the PC's
  own sign-in, kept in `%LOCALAPPDATA%\CampusPrint\station.json`). No SQL needed any more.
- **Printer scan**: every Windows printer with colour ability, connection (USB / network) and status; virtual
  printers (PDF, OneNote, Fax) are marked. Ticked printers are created on the server for this PC
  (`POST/PUT/DELETE /api/v1/counter/printers`); "Test B/W" / "Test colour" print the Step 1 test page.
- **Printing**: the same agent code as before, started inside the app. It keeps printing when the window is
  closed (tray icon), and starts with Windows (`HKCU\...\Run`, `--background`).
- **Screens**: served by a tiny web server on `127.0.0.1:47800` and shown in a Microsoft Edge app window (no
  address bar). It only answers requests for 127.0.0.1/localhost and each call needs the random token the
  window was opened with, so other websites cannot use it. Counter actions are passed on to the server with
  the saved counter password.
- **Pickup code switch**: `shop_settings.stamp_code`, sent to the PC with every order. Off = the order prints
  with nothing added (no label, no cover sheet).
- Logs: `%LOCALAPPDATA%\CampusPrint\logs`. The old Windows-service way (`agent/winsw/`) still works for
  special cases.

## Choosing pages

A student who needs only part of a big PDF (say pages 333-390 of a 1000-page book) chooses
**Choose pages** and types the pages the way print dialogs accept them:

    102        333-390        1-5, 8, 12-15        333-  (= to the end)

- Numbers are the PDF's own (1 = first page of the file). The preview shows the chosen pages, so a
  student can see if a book's printed page numbers differ from the PDF's.
- Pages print in order, each once (`8, 1-3, 2` prints 1, 2, 3, 8). A reversed range (`390-333`) is read
  the right way round.
- Limits (`application.yml`): files up to `max-file-pages` (2000) pages and 50 MB; up to `max-pages` (300)
  pages printed per copy. A file with more than 300 pages opens with "Choose pages" already selected.
- The same rules exist three times, and a test keeps them identical: `web/index.html` (live price),
  the Android app (`PageChoice.kt`, with `PageChoiceTest`), and the server (`PageRanges.java`). **Only the
  server's answer counts**: it checks the choice against the uploaded file and sets the price.
- The database stores the tidy form in `orders.page_ranges` (`null` = all pages) and the number of
  printed pages in `orders.print_pages`. The Xerox PC gets both with the order, removes every other page
  before printing (it refuses to print if a page does not exist), and puts the pickup code on the first
  printed page. The counter shows e.g. *"58 of 1000 pages (333-390)"*.

## Price and payment

1. The app uploads the file straight to storage using a 5-minute upload link.
2. `POST /orders/{id}/uploaded`: the backend downloads the file, checks what it really is (first bytes,
   not the name), opens it (PDFBox / ImageIO), counts pages, checks the student's page choice against
   the real page count, and computes `chosen pages × copies × price per page`. Locked or broken files
   and impossible page choices are refused here, before any payment.
3. `POST /orders/{id}/payment`: the backend creates a **Razorpay order** for that exact amount.
4. The app opens Razorpay. On success it sends `payment_id` + `signature` to
   `POST /orders/{id}/payment/confirm`. The backend checks
   `HMAC-SHA256(our_razorpay_order_id + "|" + payment_id, key_secret)`, then asks Razorpay for the
   payment, checks order id and amount, and captures it if it is only "authorized".
5. Safety net: every minute (and whenever the app checks status) the backend asks Razorpay about unpaid
   orders, so a student who closed the app right after paying still gets printed.

`PAYMENT_MODE=demo` replaces steps 3–4 with a test button. The backend log and counter screen warn loudly.

## Several printers at once

The agent asks the backend for the printer list (from the `printers` table) every 20 seconds and runs
**one worker thread per printer**. A worker only asks for work when its printer is free:

```
claim_next_order(pc, printer):
   pick the oldest paid order this printer can do
     colour printer → colour orders first, then B/W (unless accepts_bw = false)
     B/W printer    → B/W orders only
   FOR UPDATE SKIP LOCKED  → two printers asking at the same moment never get the same order
```

So load spreads by itself: whichever printer is free takes the next order.

## Why an order is never printed twice by accident

On the PC, for each order:

1. tell backend `DOWNLOADING`
2. download, check size + type + SHA-256 — a problem here gives the order back (nothing printed)
3. tell backend `SUBMITTED` — **if this fails, do not print**
4. write `journal/<order>.sent` to disk and force it onto the disk
5. print the file (pickup code on its first page) — *point of no return*
6. watch the Windows queue until the document leaves it
7. tell backend `COMPLETED` / `FAILED` — if offline, the journal keeps the result and sends it later

If the PC disappears:
- before step 5 → the backend's recovery puts the order back in the queue (safe, nothing printed);
- after step 5 → the order becomes `FAILED` with "check the tray". Only a person pressing
  **Print again** on the counter prints it again. When the PC comes back it reports `COMPLETED`
  from its journal if it did print.

Paper out, jam, offline printer: Windows keeps the document, so the order stays "printing" and the
counter shows the printer lamp amber with the reason. It finishes by itself once staff fix the printer.

## Files

- Private bucket `print-documents`, max 25 MB, only PDF/PNG/JPEG.
- Uploads and downloads use 5-minute signed links made by the backend.
- On the PC, files sit in a folder only SYSTEM/administrators can open, and are overwritten then deleted after printing.
- In storage: deleted after printing; unpaid or cancelled after expiry; failed paid orders kept 24 h (for "Print again").

## Pictures

PNG/JPG become a one-page A4 PDF on the PC (centred, fitted, landscape for wide pictures).
Phone photos are turned upright using their EXIF orientation, so they print the way they looked on screen.
