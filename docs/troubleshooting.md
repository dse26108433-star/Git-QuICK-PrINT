# Troubleshooting

Find your problem, try the fix, then do the step again.

## "Waking up the print service" stays for a minute or two

That is Render's free plan: the server sleeps after about 15 minutes without a visitor and takes a minute or two
to start again. The website and the apps wait and carry on by themselves; files chosen meanwhile are added when
the server is up. During opening hours the Station on the Xerox PC keeps the server awake. If it should never
sleep (first visitors at night too), Render's paid "Starter" plan does that, or a free uptime checker that opens
`https://<your backend>/api/v1/shop` every 10 minutes.

If it stays for more than five minutes: Render → your service → **Logs**. A line that starts with
`The database could not be brought up to date` or `PAYMENT_MODE` says what to fix.

## Word files

| What you see | What to do |
|---|---|
| Station → Printers → Word files: "Not here: Microsoft Word is not installed on this PC." | Word files need Microsoft Word on the Xerox PC. Install it, then press **Check again**. Until then students are told to send a PDF. |
| "Not here: Microsoft Word did not answer…" | Open Word once by hand on that PC: it is asking something (activation, a first-start question). Answer it, close Word, press **Check again**. |
| "Not here: Microsoft Word could not save a PDF…" | The Word on that PC cannot export PDFs (Word 2007 without Service Pack 2, or a Word that is not activated). Update or activate it, then **Check again**. |
| A student sees "Word files are turned into pages by the Xerox center's computer, and it is not online right now." | The Station is off, or its Word is not ready (see above). The student can save the file as PDF, or try again when the center is open. |
| "This Word file has something in it that cannot be prepared automatically…" | The file has a macro, an embedded object (for example an old equation), a linked picture or the like. That is refused on purpose. In Word: File → Save As → PDF, and add the PDF. |
| "This is an older kind of Word file (.doc)…" | Old `.doc` files are not accepted. In Word: File → Save As → Word Document (.docx) or PDF. |
| A Word file you opened yourself on the Xerox PC appears a moment later than usual | It opened in the hidden Word the Station had running; the Station noticed, handed that window to you and started another for itself. Nothing is lost. |
| The pages look different from the student's own screen | They are made by the Word on the Xerox PC, with the fonts on that PC. A font the PC does not have is replaced. The student sees these pages before paying; to keep a special font, they save as PDF on their own computer. |

## Many customers at once

Tried on a test computer: 500 customers arriving within 40 seconds, every order printed, no wrong answer.
Whether the real service carries that depends on what it runs on:

- **Render's free plan** has a tenth of a processor: fine for a quiet shop, slow in a rush (the service does not
  break; phones are told to ask less often and files wait their turn). For hundreds of customers in a break
  take the paid **Starter** plan or bigger.
- **Supabase's free plan** has 1 GB for files and about 5 GB of traffic a month. Every file is fetched twice
  (checked by the server, printed by the Xerox PC): 300 orders a day of 3 MB each is about 2 GB a day. For that
  volume take the **Pro** plan. Files are deleted after printing, so the space itself rarely fills up.
- Put the server and the database **in the same region** (for India: both in Mumbai or Singapore). Far apart,
  every answer waits several times for the distance.
- With a paid database set `DB_POOL_SIZE=15` on the server (Render → Environment). The free pooler has few slots;
  5 is the safe number there.

## Collecting (show your files, no code)

| What you see | What to do |
|---|---|
| A student taps **I'm at the counter** but nothing appears in the green box | Their phone has no internet (the screen then says "This phone is offline"). Type a file's name, or the order number, in **Find an order**: the order comes up with its pictures. Check the phone shows the same order, then **Handed over**. |
| "This student has not tapped I'm at the counter" when pressing Handed over | You found the order yourself (by name). Hand over only if the student's phone shows this same order: same files, same order number. |
| The order is on the phone but there is no picture, only the file names | The phone that ordered was changed or its browser data was cleared, and the file has not printed yet. The picture appears once the Xerox PC printed the file (it sends its own picture). The names and settings are always shown. |
| A student shows a screenshot or the PDF itself | That is not the order. Ask them to open the order in XeoGo and tap **I'm at the counter**: only the phone that ordered can make it appear on your screen. |
| "This order was already handed over" | It was. One order is handed over once; **All recent** shows when. |
| The student's phone still says "Ready" after handing over | It turns to **Collected** within a few seconds when it has internet. Nothing else to do. |
| The time "Ready at about…" was wrong | It is an estimate from the queue and the printers' recent speed. With the printers offline it says "Prints as soon as a printer is back online". |

## Staff printing (free, with a staff ID)

| What you see | What to do |
|---|---|
| A staff member forgot the password | Station → **Staff** → **New password** on their ID. Give them the new one; the old one stops working and every device is signed out. |
| "Wrong username or password" although it is right | After 8 wrong tries an ID waits 15 minutes (the Staff screen shows "Wrong passwords: waiting"). **New password** ends the wait at once. Capitals and the dash do not matter when typing. |
| "This order takes N pages, but only M of your free pages are left" | The month's pages are used up. Print fewer pages, wait for the 1st, or the Xerox center raises the number (**Pages** on that ID, or "Free pages" for everyone). |
| A staff member wants colour | Staff → switch **Colour and special paper are free too**. Off: free staff printing is black & white on the usual paper. |
| The staff page asks to sign in again | The ID got a new password, was switched off, or the sign-in is older than 90 days without use. Sign in again. |
| "This staff ID is switched off" | Station → Staff → **Switch on**. |
| Someone left, or an ID is misused | **Switch off** (can be switched on again) or **Remove** (for good). What was already sent still prints. |
| The Staff screen says "This server is an older version without staff IDs" | Put the new server online first (push the code), then open the screen again. |
| Pages of a file that failed or was cancelled were counted | They are not: only files that printed or are printing count. The number on the staff page updates within a minute. |

## Updating (new versions)

| What you see | What to do |
|---|---|
| How do I put a new version online? | Push the code to GitHub. The server builds, starts, and brings its database up to date by itself; the websites are published from the same push. Then install the new Station and apps. |
| Render: the deploy failed; log says "The database could not be brought up to date" | The line says why. The previous version keeps running. Usual reasons: the database was unreachable for a moment (deploy again), or "run reset.sql first" (a database from the very first version). |
| After the update students cannot pay: "Online payment is not set up" | `PAYMENT_MODE` is empty on the server. Set it to `upi` (XeoGo Pay), `razorpay`, or `demo` (testing only: prints without payment) and deploy again. An empty value no longer means demo. |
| Bank messages arrive but are "Not counted" on the UPI payments tab | The message did not come from a bank sender name the server knows, or from a business UPI app. If the SMS really is from your bank, press **This is our bank** next to it (once). Messages from phone numbers and chat apps never count. |
| The old "Campus Print" app is still on a phone | It keeps working (it shows the order number). Install the new XeoGo app over it: orders are kept. |

## XeoGo Pay (UPI payments)

| What you see | What to do |
|---|---|
| Server log: "PAYMENT_MODE is upi but UPI_ID is not a UPI ID" | Set `UPI_ID` to your UPI ID (`name@bank`) or paste the whole text of your shop's UPI QR (`upi://pay?pa=…`). |
| Google Pay / PhonePe says the payment "cannot be completed" or "exceeded bank limit" | Many UPI apps refuse *links* to personal UPI IDs. Use a business UPI ID (PhonePe Business, Paytm for Business, Google Pay for Business, BharatPe). Scanning the QR code works meanwhile. |
| Students see "I have paid" instead of an automatic confirmation | The server has not heard from the Verifier phone for 3 hours, or `UPI_ALERT_TOKEN` is empty. Open XeoGo Pay Verifier on the shop phone: it must say "Working". |
| Verifier says "The server refused the token" | Copy `UPI_ALERT_TOKEN` from the server settings again (it is case-sensitive) → Save and connect. |
| Verifier: "Restricted setting" when allowing SMS or notifications | Android 13+ for apps not from the Play Store: App info → ⋮ (top right) → Allow restricted settings → try again. |
| Verifier works, then stops after some hours | Battery saver or the phone's cleaner stopped it: Verifier → "Never paused by battery saver" → Allow; on Xiaomi / Oppo / Vivo / Realme also switch on Autostart. Keep the phone charging and online. |
| A payment arrived but the order did not confirm | Counter → UPI payments → Bank messages: find it. "no order" usually means the student paid a different amount (not the exact ₹xx.xx). Use **Money received** on the order, or refund. |
| A student paid twice | The second payment shows as a bank message with "no order". Refund it by UPI from the shop's app. |
| Payment confirmations are slow | The bank SMS is being used (can take minutes). Make sure "UPI app notifications" is allowed in the Verifier and the business UPI app is signed in on that phone with notifications on. |

## XeoGo Station (Xerox PC app)

| What you see | What to do |
|---|---|
| "Windows protected your PC" when installing | The installer is not code-signed yet: "More info" → "Run anyway". |
| Setup says "Cannot reach the XeoGo server" | Check the address (https://...) and the internet. Open the address + `/api/v1/shop` in a browser: it must show the shop. |
| Setup says "Wrong counter password" | Use `COUNTER_PASSWORD` from the backend settings. Too many tries: wait 10 minutes. |
| A printer is missing in the scan | Install it in Windows first (Settings → Printers & scanners), print a Windows test page, then "Scan again". |
| Sidebar says "Connecting…" for a long time | The server is asleep (free hosting) or the internet is down. It retries by itself. |
| Counter says "The counter password has changed" | Settings → Counter password → type the new one → Save. |
| Nothing prints after a restart | Settings → "Start with Windows" must be on. Or open XeoGo from the Start menu. |
| Need the log | Settings → Log files → Open folder (`%LOCALAPPDATA%\CampusPrint\logs\agent.log`). |
| Moving to a new PC | Old PC: Settings → Disconnect this PC. New PC: install and run the setup again. |

## Printer options (what students can choose)

| What you see | What to do |
|---|---|
| Printer lamp amber: "Printer is offline", "Out of paper", "Paper jam", "Paused in Windows" | Fix the printer (or Windows: printer queue → Resume). Paid files wait safely and print by themselves once it is ready. If a driver wrongly reports its printer offline while it prints fine, set `checkPrinterStatus: false` in `agent.yml` (service installs). |
| A file shows "could not confirm it printed: check the tray" | The Windows queue could not be read for 5 minutes. Look in the tray and the Windows queue before pressing **Print again**. |
| Stapling / punching / binding not offered although the printer has it | On purpose until staff tick it: print one test order with that option, check the paper, then tick it under "What students can choose on this printer" → **Save**. |
| Students do not see two-sided / A3 / stapling although the printer has it | Station → Printers: the printer's labels show what Windows reported ("Two-sided", "Paper: …", "Finishing: …"). If it is there, open "What students can choose on this printer", tick it, **Save**. If it is not there, the driver does not describe it: install the full Canon driver (UFR II / PS, not the basic one), set the options in Windows (Printer properties → Device Settings: duplex unit, finisher installed), then **Scan again**. |
| A paper size is offered but the printer has no such paper | Untick it under "What students can choose on this printer" → **Save**. Students see the change within a minute. |
| Paper types (glossy, thick…) do not appear on the website | On purpose: they appear only when staff tick them (paper you keep in stock). |
| "Windows did not describe this printer" | The driver gives no capabilities. Students get plain A4 one-sided on it. Tick only the sizes it really has; better, install the manufacturer's full driver. |
| Counter: red banner "N paid files wait for a printer that can print it" | A file needs something no printer offers now (a printer was switched off or unticked an option). Switch the printer back on, or **Cancel** the file on the counter (the student is refunded that file's price by UPI or in the Razorpay dashboard; a staff member gets the pages back). |
| A file goes back with "The printer's driver would not do: …" | The driver did not accept a setting (usually stapling / punching / binding on a driver that lists it but has no finisher installed). Fix the driver's Device Settings, or untick that option for the printer. Nothing was printed. |
| Stapled / punched in the wrong place | Each Canon driver names positions differently. Print one test order per option and check; untick positions that come out wrong. `PrinterSmokeTest "<printer>" test.pdf --staple top-left` prints one by hand. |
| Colour file came out grey (or B/W in colour) | Printer properties → the driver must follow the application's colour choice (not forced to B/W / colour). Then **Test B/W** and **Test colour** in the Station. |
| Printout smaller than the preview | Driver defaults "Fit to paper" / "Scale" must be off (100 %). The Station prints at 100 % from the paper corner. |
| A Station older than 4.0 prints only some files | Old Stations only get plain A4 one-sided files. Install `XeoGoStation-Setup-4.3.1.exe` (it updates in place). |
| A Station older than 4.3 ("Campus Print Station") shows no pictures and no Staff screen | It still prints. Install `XeoGoStation-Setup-4.3.1.exe`: it replaces the old program and keeps its settings. |

## Xerox PC / printing (details)

| What you see | What to do |
|---|---|
| `list-printers.bat` shows nothing | Install the Canon driver and add the printer in Windows first. |
| `Windows printer not found: "..."` | The name in the `printers` table must match `list-printers.bat` **exactly**. Fix with `update printers set windows_printer_name = '...' where name = '...';` |
| Test page works in the window, but the service says `NOT FOUND` | The printer was added only for your Windows user. Add it again "for all users" by IP address (TCP/IP port). |
| Colour order prints grey on the colour Canon | Printer properties → make sure the driver is the colour driver and "Colour mode" is not forced to B/W. Then run `test-print.bat "<name>" --color`. |
| B/W order prints in colour | Set the driver default to Auto/Colour mode respecting the application; or set `accepts_bw = false` for the colour printer so it never gets B/W work. |
| Pages come out wrongly scaled / fonts look odd | Install SumatraPDF and set `printStrategy: "external"` in `agent.yml`, then restart the service. |
| Printer lamp amber on the counter: "PaperOut" / "Offline" | Fix the printer. The waiting order prints by itself. Do **not** press Print again. |
| A paid order shows "check the tray" | Look in the tray for a page with the label **Order CODE** in its bottom-right corner. Nothing there → **Print again**. Pages there → hand them over, then **Refunded / done**. |
| The order number label is cut off at the paper edge | Very unusual (it is 6 mm from the edge). Check the driver's paper size is A4 and "scale/fit" is not forced in the driver defaults. |
| Staff want a big cover sheet again | In `agent.yml` set `coverSheetMinSheets: 30` (cover only for orders of 30+ sheets) or `pickupCodeOnPage: false` (cover for every order), then restart the service. |
| Service does not start | Look at `C:\ProgramData\CampusPrintAgent\service-logs\` and `...\logs\agent.log`. Most common: Java not found (reinstall Java with "Add to PATH", then run install-service.ps1 again) or wrong agentId/agentSecret. |
| `The backend rejected agentId/agentSecret` | Run `select * from enroll_agent('Xerox PC');` again and reinstall the service with the new values. |
| `backendUrl must start with https://` | Use https for a real server. http is allowed only for `localhost` or `192.168.x.x` / `10.x.x.x` addresses while testing. |

## Backend

| What you see | What to do |
|---|---|
| `AGENT_TOKEN_SECRET must be at least 32 characters` | Make the value in `.env` longer. |
| `Connection refused` / `password authentication failed` to the database | Use the **Session pooler** string (port **5432**), user like `postgres.abcd...` (with the dot), and `?sslmode=require` at the end of `DB_URL`. |
| `relation "orders" does not exist` | The server sets the database up by itself when it starts. If `DB_AUTO_SETUP=false` is set, run `db/setup.sql` in the Supabase SQL Editor. |
| `The shop is not set up yet` | As above (the setup creates the settings row). |
| Start fails: "The database could not be brought up to date" | Read the reason in the same line. "permission denied": `DB_USER` must be the user that owns the tables (the Supabase `postgres.…` user). |
| Uploads fail with 400/403 | Check `SUPABASE_URL` and that `SUPABASE_SERVICE_KEY` is the **secret** key, not the publishable/anon key. |
| `PAYMENT_MODE is razorpay but RAZORPAY_KEY_ID ... are empty` (on Render: deploy fails, "No open ports detected") | Fill both keys: `.env` on a laptop, or Render → your service → **Environment** → `RAZORPAY_KEY_ID` + `RAZORPAY_KEY_SECRET` (test keys `rzp_test_…` from Razorpay → Account & Settings → API Keys) → Save, rebuild and deploy. `PAYMENT_MODE=demo` only for private tests: demo prints without payment. |
| Razorpay 401 in the log | Wrong key id/secret pair, or test key used in live mode (or the other way). |

## Website / app

| What you see | What to do |
|---|---|
| "Only PDF, Word (.docx), JPG and PNG files can be printed." on a file | The file is something else with a .pdf name, or broken. Open it and "Print to PDF" / export as PDF, then add that. |
| A file shows "Change to one-sided" (or similar) | The printers changed while the student was setting up (e.g. the two-sided printer was switched off). One tap makes the smallest change that can be printed. |
| "Review order" stays grey | The bar says why ("Wait until every file is uploaded", "One file needs a change"): tap the words to open that file. |
| "Choose at least one page to print." | All pages were un-ticked: tick some pages, or press **All**. |
| The page reloads and the files are still there | On purpose: the unfinished order is kept in the browser until it is paid or cancelled. |
| "Page 1200 does not exist: this PDF has 1000 pages" | The student typed a page after the end of the file. Page numbers are the PDF's own (1 = first page), check the preview. |
| The wrong pages printed (e.g. the chapter starts 12 pages later) | A book's printed page numbers often differ from the PDF's own numbering (cover, contents in Roman numbers). Tell students to check the preview: it shows the chosen pages with their PDF page number. |
| "Up to 300 pages can be printed from one document" | Choose the pages needed, add the file twice with different pages, or raise `max-pages` in `application.yml` and restart the backend. |
| "One order can have up to 25 files" | Make a second order, or raise `max-documents` in `application.yml`. |
| "Files can have up to 2000 pages" / "smaller than 50 MB" | Limits in `application.yml` (`max-file-pages`, `max-file-size-bytes`). 50 MB is the most a free Supabase project accepts. |
| Error `column "..." does not exist`, `relation "..." does not exist` or `function ... does not exist` in the backend log | The database is older than the server. Restart the backend: it brings the database up to date at start (unless `DB_AUTO_SETUP=false`: then run `db/setup.sql` by hand). |
| Error `function claim_next_order(...) does not exist` in the backend log | The database is new (version 4) but the backend is old: deploy the new backend. |
| "Cannot reach the print service" | Is the backend running? Is `apiBase` in `web/config.js` right? |
| Browser console says **CORS** | Put the exact address you open the page from (e.g. `http://localhost:3000`) in `WEB_ORIGINS` in `.env`, restart the backend. |
| PDF preview stays blank | Reload the page (PDF.js is part of the website, in `web/vendor/`). Very large or damaged PDFs: open and "Print to PDF", then add that. |
| "This PDF is locked with a password" | Open it, "Print to PDF" / save a copy without password, and upload that. |
| Counter says "Wrong password" | Use `COUNTER_PASSWORD` from `.env` (after changing it, restart the backend). |
| Counter says "Set COUNTER_PASSWORD" | It is empty or shorter than 8 characters. |
| Phone app cannot connect | `API_BASE` must be your laptop's Wi-Fi address (not localhost), phone on the same Wi-Fi, Windows firewall allowing Java on port 8080. Only **debug** builds allow `http://`. |
| Paid but the order still says "Waiting for payment" | Wait one minute: the backend checks by itself. XeoGo Pay: see the UPI payments tab on the counter. Razorpay: check the payment in the Razorpay dashboard and the backend log. |
| The website looks like the old one after an update | Close the tab and open it again (the page keeps a copy for bad connections and replaces it at the next visit). |

## Handy SQL (Supabase SQL Editor)

```sql
-- Is the PC online? (last_seen_at within the last minute)
select name, host_name, agent_version, last_seen_at from agents;

-- What the PC sees for each printer, and what students may choose on it
select name, windows_printer_name, enabled, status, status_detail, status_at,
       effective, capabilities_at from printers;

-- Last 20 orders with their files
select o.pickup_code, o.status, o.amount_paise, o.created_at,
       d.position, d.file_name, d.status as file_status, d.print_pages, d.sheets, d.error_message
  from orders o left join order_documents d on d.order_id = o.id
 order by o.created_at desc, d.position limit 60;

-- Paid files waiting, and whether any printer can do them
select o.pickup_code, d.file_name, d.requirements,
       exists (select 1 from printers p where p.enabled
               and printer_can_do(p.effective, p.supports_color, p.accepts_bw, d.requirements)) as a_printer_can
  from order_documents d join orders o on o.id = d.order_id where d.status = 'QUEUED';

-- History of one order
select e.* from order_events e join orders o on o.id = e.order_id
 where o.pickup_code = 'K7M4X' order by e.id;
```
