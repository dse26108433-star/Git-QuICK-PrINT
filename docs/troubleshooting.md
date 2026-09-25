# Troubleshooting

Find your problem, try the fix, then do the step again.

## Campus Print Station (Xerox PC app)

| What you see | What to do |
|---|---|
| "Windows protected your PC" when installing | The installer is not code-signed yet: "More info" → "Run anyway". |
| Setup says "Cannot reach the Campus Print server" | Check the address (https://...) and the internet. Open the address + `/api/v1/shop` in a browser: it must show the shop. |
| Setup says "Wrong counter password" | Use `COUNTER_PASSWORD` from the backend settings. Too many tries: wait 10 minutes. |
| A printer is missing in the scan | Install it in Windows first (Settings → Printers & scanners), print a Windows test page, then "Scan again". |
| Sidebar says "Connecting…" for a long time | The server is asleep (free hosting) or the internet is down. It retries by itself. |
| Counter says "The counter password has changed" | Settings → Counter password → type the new one → Save. |
| Nothing prints after a restart | Settings → "Start with Windows" must be on. Or open Campus Print from the Start menu. |
| Need the log | Settings → Log files → Open folder (`%LOCALAPPDATA%\CampusPrint\logs\agent.log`). |
| Moving to a new PC | Old PC: Settings → Disconnect this PC. New PC: install and run the setup again. |

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
| A paid order shows "check the tray" | Look in the tray for a page with the label **Pickup CODE** in its bottom-right corner. Nothing there → **Print again**. Pages there → hand them over, then **Refunded / done**. |
| The pickup code label is cut off at the paper edge | Very unusual (it is 6 mm from the edge). Check the driver's paper size is A4 and "scale/fit" is not forced in the Canon driver defaults. |
| Staff want a big cover sheet again | In `agent.yml` set `coverSheetMinSheets: 30` (cover only for orders of 30+ sheets) or `pickupCodeOnPage: false` (cover for every order), then restart the service. |
| Service does not start | Look at `C:\ProgramData\CampusPrintAgent\service-logs\` and `...\logs\agent.log`. Most common: Java not found (reinstall Java with "Add to PATH", then run install-service.ps1 again) or wrong agentId/agentSecret. |
| `The backend rejected agentId/agentSecret` | Run `select * from enroll_agent('Xerox PC');` again and reinstall the service with the new values. |
| `backendUrl must start with https://` | Use https for a real server. http is allowed only for `localhost` or `192.168.x.x` / `10.x.x.x` addresses while testing. |

## Backend

| What you see | What to do |
|---|---|
| `AGENT_TOKEN_SECRET must be at least 32 characters` | Make the value in `.env` longer. |
| `Connection refused` / `password authentication failed` to the database | Use the **Session pooler** string (port **5432**), user like `postgres.abcd...` (with the dot), and `?sslmode=require` at the end of `DB_URL`. |
| `relation "orders" does not exist` | Run `db/setup.sql` in the Supabase SQL Editor. |
| `The shop is not set up yet` | Run `db/setup.sql` (it creates the settings row). |
| Uploads fail with 400/403 | Check `SUPABASE_URL` and that `SUPABASE_SERVICE_KEY` is the **secret** key, not the publishable/anon key. |
| `PAYMENT_MODE is razorpay but RAZORPAY_KEY_ID ... are empty` (on Render: deploy fails, "No open ports detected") | Fill both keys: `.env` on a laptop, or Render → your service → **Environment** → `RAZORPAY_KEY_ID` + `RAZORPAY_KEY_SECRET` (test keys `rzp_test_…` from Razorpay → Account & Settings → API Keys) → Save, rebuild and deploy. `PAYMENT_MODE=demo` only for private tests: demo prints without payment. |
| Razorpay 401 in the log | Wrong key id/secret pair, or test key used in live mode (or the other way). |

## Website / app

| What you see | What to do |
|---|---|
| "Page 1200 does not exist: this PDF has 1000 pages" | The student typed a page after the end of the file. Page numbers are the PDF's own (1 = first page), check the preview. |
| The wrong pages printed (e.g. the chapter starts 12 pages later) | A book's printed page numbers often differ from the PDF's own numbering (cover, contents in Roman numbers). Tell students to check the preview: it shows the chosen pages with their PDF page number. |
| "Up to 300 pages can be printed per order" | Split the job into two orders, or raise `max-pages` in `application.yml` and restart the backend. |
| "Files can have up to 2000 pages" / "smaller than 50 MB" | Limits in `application.yml` (`max-file-pages`, `max-file-size-bytes`). 50 MB is the most a free Supabase project accepts. |
| Error `column "page_ranges" does not exist` in the backend log | Run the latest `db/setup.sql` in the Supabase SQL editor (safe to run again), then restart the backend. |
| "Cannot reach the print service" | Is the backend running? Is `apiBase` in `web/config.js` right? |
| Browser console says **CORS** | Put the exact address you open the page from (e.g. `http://localhost:3000`) in `WEB_ORIGINS` in `.env`, restart the backend. |
| PDF preview stays blank | The page loads PDF.js from the internet (jsdelivr). Check the internet connection. |
| "This PDF is locked with a password" | Open it, "Print to PDF" / save a copy without password, and upload that. |
| Counter says "Wrong password" | Use `COUNTER_PASSWORD` from `.env` (after changing it, restart the backend). |
| Counter says "Set COUNTER_PASSWORD" | It is empty or shorter than 8 characters. |
| Phone app cannot connect | `API_BASE` must be your laptop's Wi-Fi address (not localhost), phone on the same Wi-Fi, Windows firewall allowing Java on port 8080. Only **debug** builds allow `http://`. |
| Paid but the order still says "Waiting for payment" | Wait one minute: the backend checks Razorpay by itself. Still stuck? Check the payment in the Razorpay dashboard and the backend log. |

## Handy SQL (Supabase SQL Editor)

```sql
-- Is the PC online? (last_seen_at within the last minute)
select name, host_name, agent_version, last_seen_at from agents;

-- What the PC sees for each printer
select name, windows_printer_name, enabled, status, status_detail, status_at from printers;

-- Last 20 orders
select pickup_code, status, file_name, copies, color, amount_paise, error_message, created_at
  from orders order by created_at desc limit 20;

-- History of one order
select e.* from order_events e join orders o on o.id = e.order_id
 where o.pickup_code = 'K7M4X' order by e.id;
```
