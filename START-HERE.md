# START HERE

Do the steps **in order**. Each step is small. Do not jump ahead.
After each step there is a **"You are done when..."** line. Only go on when that is true.

If something goes wrong, look in `docs/troubleshooting.md`.

## Progress

`[x]` = done and checked · `[ ]` = still to do · 💻 = done on the admin laptop · 🖨️ = must be done at the Xerox center PC

| Step | Status |
|---|---|
| 0. Tools on the admin laptop (Java 21, Maven, Python, Android Studio) | ✅ Done |
| 1. Printing from Java | ✅ Done on laptop test printers · 🖨️ on the Xerox PC this is now the Station's **Test print** button |
| 2. Database (Supabase) | ✅ Done · 🖨️ put the REAL Canon names in later (from `list-printers.bat`) |
| 3. Backend | ✅ Done (runs on http://localhost:8080) |
| 4. Connect the Xerox PC | ✅ Replaced by the **XeoGo Station installer** (see ★ below) · 🖨️ install it on the Xerox PC |
| 5. First real order | ✅ Done (B/W PDF + colour JPG) |
| 6. Staff counter screen | ✅ Done |
| 7. Razorpay test payments | ⏳ Needs your Razorpay test keys |
| 8. Android app | ✅ Builds (`app-debug.apk`) · ⏳ set `API_BASE` + run on your phone |
| ★ No more cover sheets (paper saving) | ✅ Done and tested · 🖨️ check the label once on each real Canon |
| ★ Choose pages (e.g. only 333-390 of a 1000-page PDF) | ✅ Done and tested (website, server, Xerox PC, counter, app) |
| ★ XeoGo Station: one installer, no Java needed | ✅ Built, installed on this laptop and tested · 🖨️ install at the Xerox center |
| ★ "Order number on pages" on/off switch | ✅ Done and tested (Station counter + counter.html) |
| ★ Student website on Netlify + backend online | ⏳ Files ready · needs your Render + Netlify accounts (see ★ Going online) |
| ★ New student website design + phone app + QR poster | ✅ Done and tested (desktop + phone) · 🪧 print the poster |
| ★★ Version 4: many files per order, every print option, printer-aware | ✅ Done and tested · ✅ live Supabase upgraded · ✅ backend live on Render · ⏳ website on Netlify · 🖨️ install Station 4.1.0 (see ★★ below) |
| ★★★ XeoGo Pay: your own UPI payment gateway, automatic | ✅ Done and tested (server, website, app 3.2.0, Station 4.2.0, Verifier 1.0.0) · ⏳ your UPI ID + token on the server · 📱 Verifier on the shop phone (see ★★★ below) |
| ★★★★ Version 5 — **XeoGo**: no pickup code (show your files), free staff printing, updates with one push | ✅ Done and tested (server, both websites, both apps, Station 4.3.1, Verifier 1.1.0) · 🖨️ install Station 4.3.1 · 📱 install the new apps · make the staff IDs (see ★★★★ below) |

---

## What you are building (30-second picture)

```
 Student's phone / laptop          Backend (Spring Boot)          Xerox center PC
 ─────────────────────────         ─────────────────────          ───────────────────
 1. choose PDF / PNG / JPG
 2. see the file, pick
    B/W or colour + copies
 3. pay (UPI / Razorpay)  ───────►  checks payment
 4. the files stay on the phone,    puts order in queue  ◄──────  "any work for Printer 2?"
    with the time they are ready                                   prints it on a free printer
                                                                   (order number printed small
                                                                    on the first page: no
                                                                    extra sheet)
 5. "I'm at the counter": shows  ◄─  counter screen: the same
    the files, takes the pages       files, "Handed over"
```

College staff do the same in **XeoGo Staff** with a staff ID, without step 3: free, up to a number of pages a month.

- **Many files, one order.** Each file has its own pages, copies, colour, sides, paper, layout and finishing.
- **No login.** Each order has a secret key saved on the student's phone.
- **Supabase** = the database and file storage.
- **One PC** in the Xerox center runs the "agent" program. It drives **all** the Canon printers at the same time.

## What you need

| Done | Thing | Where |
|---|---|---|
| [x] | Java 21 | 💻 laptop: Java 21.0.8 installed · 🖨️ Xerox PC: install Eclipse Temurin 21, tick "Add to PATH" |
| [x] | Maven | 💻 Maven 3.9.11 installed |
| [x] | A free Supabase account | supabase.com (project connected) |
| [x] | Python 3 (only to open the web page) | 💻 Python 3.12.0 installed |
| [x] | Android Studio (only for the app) | 💻 installed, with Android SDK |

---

## STEP 1 — Can the Xerox PC print from Java?  (do this first!)

> ★ **Now much easier:** install **XeoGo Station** (see the ★ section below). Its setup wizard scans the
> printers and has a **Test print** button for each one: that replaces everything in this step and in STEP 4.
> The steps below are the old manual way, kept for reference.

This checks the printers before anything else exists.

- [x] 1. On your laptop, open a terminal in the `agent` folder. Type:
   ```
   mvn package
   ```
   Wait for `BUILD SUCCESS`. You now have `agent/target/print-agent.jar`.
   > ✅ Built with no errors. *(The old `xerox-pc-package` folder was replaced by `installer/`.)*
   > (jar + all scripts + `print-agent.exe` (WinSW) already downloaded, so the Xerox PC needs no internet for it).
- [ ] 2. 🖨️ Make a folder `C:\CampusPrintAgent` on the **Xerox PC**.
- [ ] 3. 🖨️ Copy into it:
   - `agent/target/print-agent.jar`
   - everything inside `agent/winsw/`
   > Easier: use the XeoGo Station installer instead (★ section below).
- [ ] 4. 🖨️ On the Xerox PC, double-click **`list-printers.bat`**.
   You see the printer names, for example `"Canon iR2625"`. **Write them down exactly.**
   > ✅ Checked on the laptop: `list-printers.bat` works and lists every Windows printer.
- [ ] 5. 🖨️ Open a Command Prompt in `C:\CampusPrintAgent` and type (use YOUR printer name):
   ```
   test-print.bat "Canon iR2625"
   ```
- [ ] 6. 🖨️ Do it once for **every** printer. For the colour printer add `--color`:
   ```
   test-print.bat "Canon iR-ADV C3530" --color
   ```

**You are done when:** each printer gave you **one** test page with the label **Order TEST1** in its
bottom-right corner, fully readable (not cut off by the edge of the paper). No cover sheet any more.
The red box must be **red** on the colour test and **grey** on the B/W tests.

> ✅ **Laptop test passed.** Two test printers were added to this laptop:
> `CampusPrint Test BW` and `CampusPrint Test Colour`. They save pages as PDF in `C:\CampusPrintTest\`.
> Both test prints finished with `Result: COMPLETED - printed`. The TEST1 cover sheet and the test page
> came out correctly (see `C:\CampusPrintTest\step1-*.pdf`). *(That was before the paper-saving change;
> the new test page with the `Order TEST1` label was also checked: see the ★ section below.)*
> The "grey on B/W" check can only be done on a real Canon, because the PDF test printer always keeps colour.

> Tip: the printers should be installed in Windows "for all users"
> (Settings → Printers → Add → by IP address / TCP/IP). Then the program can see them even when nobody is logged in.

---

## STEP 2 — Make the database (Supabase)

- [x] 1. Go to supabase.com → **New project**. Pick region **Mumbai (ap-south-1)**. Save the database password somewhere safe.
- [x] 2. Left menu → **SQL Editor** → **New query**.
- [x] 3. Open `db/setup.sql`, copy **all** of it, paste, press **Run**.
   You should see `Success. No rows returned`.
   *(If it says "run reset.sql first": run `db/reset.sql` once, then `setup.sql` again.)*
- [x] 4. New query. Open `db/seed.sql`.
   - **Step 1 part:** change the prices if you want. Select those lines → Run.
   - **Step 2 part:** change the printer names to the ones you wrote down in STEP 1.
     Keep only as many lines as you have printers. `true` = colour printer. Select → Run.
   - **Step 3 part:** select `select * from enroll_agent('Xerox PC');` → Run.
     **Copy `agent_id` and `agent_secret` now** (into a notepad). The secret is shown only once.

**You are done when:** `select * from printers;` shows your printers, and you have agent_id + agent_secret saved.

> ✅ Done. `setup.sql` was run again (it is safe to repeat): tables, queue functions and the private
> `print-documents` bucket are all there. Prices: Rs 2 B/W, Rs 10 colour.
> - The 4 Canon printers still have the **example names** from seed.sql. They are now linked to the
>   **"Xerox PC"** agent (`6c2e6da5-…`). 🖨️ After `list-printers.bat` on the Xerox PC, fix the names, e.g.
>   `update printers set windows_printer_name = 'Canon iR2625 UFR II' where name = 'Printer 1 (B/W)';`
> - The "Xerox PC" agent's secret was made before this session. If you did not save it, run
>   `select * from enroll_agent('Xerox PC');` again, then
>   `update printers set agent_id = '<new agent_id>' where windows_printer_name like 'Canon%';`
> - For testing on the laptop, a second agent **"Admin Laptop (test)"** was enrolled with two printers
>   `Laptop Test 1 (B/W)` and `Laptop Test 2 (Colour)`. Only the laptop can use them.

---

## STEP 3 — Start the backend (on your laptop)

- [x] 1. In the `backend` folder, copy `.env.example` to a new file named `.env`.
- [x] 2. Fill in `.env` (each line has a comment telling you where to find it):
   - [x] `DB_URL`, `DB_USER`, `DB_PASSWORD` → Supabase → **Connect** → **Session pooler**
   - [x] `SUPABASE_URL`, `SUPABASE_SERVICE_KEY` → Supabase → Project Settings → **API Keys** (the *secret* key)
   - [x] `PAYMENT_MODE=demo` (keep demo for now — no real money)
   - [x] `COUNTER_PASSWORD=` something you choose, 8+ characters  *(a random one was set — see `.env`)*
   - [x] `AGENT_TOKEN_SECRET=` any long random text, 32+ characters  *(64 random characters were set)*
- [x] 3. In a terminal in the `backend` folder:
   ```
   mvn spring-boot:run
   ```
   > ✅ The backend compiles with no errors (`mvn package` → `target/print-server-1.0.0.jar`).
- [x] 4. In your browser open: `http://localhost:8080/api/v1/shop`

**You are done when:** the browser shows text with your center name and prices.
The log also shows a big **"DEMO mode"** warning — that is expected for now.

> ✅ Done. The shop endpoint shows `"centerName":"Main Xerox Center","priceBwPaise":200,"priceColorPaise":1000`
> and the log shows the DEMO mode warning.

---

## STEP 4 — Connect the Xerox PC

> ★ **Now:** double-click `installer/CampusPrintStation-Setup-4.1.0.exe` on the Xerox PC and follow its wizard
> (`installer/HOW-TO-INSTALL.txt`). No `agent.yml`, no SQL, no service scripts. The manual way below still works.

The backend must be reachable from the Xerox PC.
- For testing on the same Wi-Fi: use your laptop's IP, e.g. `http://192.168.1.10:8080`
  (find it with `ipconfig`; allow Java through the Windows firewall on your laptop).
- For real use: put the backend on a server with **https://** (see README, "Going live").

**First try it in a window (easier to see problems):**
- [x] 1. On the Xerox PC, open `C:\CampusPrintAgent\` and create `agent.yml` by copying this (use your values):
   ```yaml
   backendUrl: "http://192.168.1.10:8080"
   agentId: "paste agent_id here"
   agentSecret: "paste agent_secret here"
   ```
- [x] 2. Double-click **`run-agent-console.bat`**.

**You are done when:** the window shows `Signed in to the backend` and one line per printer
like `Printer Printer 1 (B/W): "Canon iR2625" B/W only`. No "NOT FOUND" messages.
Close the window (Ctrl+C) before step 4b.

> ✅ Laptop test passed, using the "Admin Laptop (test)" agent (`C:\CampusPrintTest\agent\agent.yml`):
> `Signed in to the backend`, `Printer Laptop Test 1 (B/W): "CampusPrint Test BW" B/W only`,
> `Printer Laptop Test 2 (Colour): "CampusPrint Test Colour" colour`. No NOT FOUND.
> 🖨️ Repeat on the Xerox PC with the **"Xerox PC"** agent_id/secret and the laptop's Wi-Fi IP as `backendUrl`.

**4b. Install it as a Windows service** (so it runs by itself, even after a restart):
- [ ] 1. Start menu → type **PowerShell** → right-click → **Run as administrator**.
- [ ] 2. Type (one line, your values):
   ```
   cd C:\CampusPrintAgent
   powershell -ExecutionPolicy Bypass -File .\install-service.ps1 -BackendUrl "http://192.168.1.10:8080" -AgentId "..." -AgentSecret "..."
   ```

**You are done when:** it prints `Service status: Running`.

> 🖨️ Install the service **only on the Xerox PC**, never on the admin laptop.

---

## STEP 5 — Print your first real order (student side)

- [x] 1. Open `web/config.js`. Make sure `apiBase` is `"http://localhost:8080"`.  *(checked: it is)*
- [x] 2. In a terminal in the `web` folder:
   ```
   python -m http.server 3000
   ```
- [x] 3. Open `http://localhost:3000` in the browser.
- [x] 4. Choose a small PDF → you see the pages → choose **B/W**, **1 copy** → **Continue to payment** → **Pay (test)**.

**You are done when:** you see your file with the times, the status goes
"Paid → Printing on Printer 1 → Ready", and the first page shows the label **Order CODE** (the order's number)
in its bottom-right corner. There is no extra cover sheet.

- [x] Try again with a JPG photo and **Colour**: it must go to the colour printer.

> ✅ Done (the same calls the web page makes: create → upload to Supabase → check → demo pay → watch):
> - **KF34M**: 3-page PDF, B/W, 1 copy → server counted 3 pages → **Rs 6** → Paid → Printing on *Laptop Test 1 (B/W)* → **Ready**. All 3 pages printed.
> - **4LKKD**: JPG photo, **Colour**, 2 copies → **Rs 20** → went to *Laptop Test 2 (Colour)* → **Ready**. 2 colour copies printed.
> - After printing, both files were deleted from Supabase storage (0 files left), as designed.
> - Web pages load, CORS allows `http://localhost:3000`, and the page scripts pass a syntax check.

---

## STEP 6 — The staff counter screen

- [x] 1. Open `http://localhost:3000/counter.html`
- [x] 2. Type the `COUNTER_PASSWORD` from your `.env`.

You see: every printer with a lamp (green = ready), orders being printed, orders
ready to hand over, and problems. Staff press **Handed over** when the student takes the paper.

**Handing over (no code, no cover sheet):** the student opens the order on their phone and taps **I'm at the
counter** → the order appears in the green box at the top of this screen, with a picture of each file → the same
pictures are on the student's phone → staff take those pages (each file starts with the page that has the small
**Order …** label in its bottom-right corner) → **Handed over** → the phone shows **Collected**.
A phone without internet: type the file's name in **Find**.

**You are done when:** your test order from STEP 5 is under "Ready to hand over", and "Handed over" moves it away.

> ✅ Done. A wrong password is refused (401). Lamps: test printers READY, Canons OFFLINE (the Xerox PC is not connected yet: correct).
> KF34M and 4LKKD were under "Ready to hand over". **Handed over** on KF34M moved it away.

---

## ★ Paper saving: no more cover sheets  (new)

**The problem:** every order printed an extra cover sheet with the pickup code. For the many 1–3 page
orders that sheet was thrown away after checking, so the Xerox center lost paper and toner on every order.

**The fix:** the code is now printed **small in the bottom-right corner of the first page** of each order,
e.g. `Order K7M4X · 3 pages × 2`. No extra sheet.

- The student's layout is kept. If that corner is blank (almost always) the label goes into the margin and
  nothing else changes. If something is there (a page number, a full-page photo) only page 1 is shrunk by
  about 4 % to make a white strip. Filled-in form boxes move with the page.
- Every copy starts with the labelled page, and the label says how many sheets to count.
- The label is 6 mm from the edge, inside the area every printer can reach.
- If adding the label ever fails, the order still prints, with a cover sheet instead.
- Optional: a cover sheet only for **big** orders. In `agent.yml` set `coverSheetMinSheets: 30`
  (orders of 30+ sheets get one). Default `0` = never.
- The student's status screen now says: *"The same code is printed small at the bottom of your first page."*
- The counter shows *"N sheets · first page has Order CODE"* and a short how-to for staff.

- [x] Label added in the blank margin (normal notes) — tested
- [x] Page with a page number in the corner → page 1 shrunk slightly, label in a clean strip — tested
- [x] Full-page photo, rotated page, US Letter page, form fields — tested (form boxes stay in place)
- [x] Real order, 3 pages × 2 copies → exactly **6 sheets**, label on sheet 1 and sheet 4 — tested
- [x] Colour photo order → label added, no cover sheet — tested
- [x] Step 1 test tool: `test-print.bat "<printer>"` prints the label; `--cover` also prints a cover sheet — tested
- [x] Backup print method (SumatraPDF, `printStrategy: external`) prints the label too — tested
- [x] Counter screen and student message updated; backend rebuilt
- [x] New agent built into the XeoGo Station installer
- [ ] 🖨️ On the Xerox PC: run `test-print.bat` once per Canon and check the label is fully readable

> Also fixed while testing on the Epson: the agent used to report an order as **printed** if it could not
> read the Windows print queue even once (this happened while the Epson was out of paper). It now keeps
> watching for up to a minute, so a student is never told "Ready" while the paper is still waiting.

---

## ★ Choose pages  (new)

**The problem:** a student with a 1000-page PDF who needs only page 102, or pages 333-390, had to print
(and pay for) the whole file. Files over 300 pages were refused completely.

**The fix:** on the "Check" step there is now a **Pages** choice:

- **All pages**, or **Choose pages** and type them like in any print dialog:
  `102` · `333-390` · `1-5, 8, 12-15` · `333-` (to the end).
- The message says what was understood (*"58 pages selected: 333-390"*) or what is wrong
  (*"Page 1200 does not exist: this PDF has 1000 pages"*). The price counts **only the chosen pages**
  (*58 pages × 1 copy × ₹2 = ₹116*), and *Continue* stays locked until the choice is valid.
- **The preview shows the chosen pages**, so students can check that page 333 of the PDF is really the
  page they want (a book's printed page numbers often differ from the PDF's).
- Files can now have up to **2000 pages** and **50 MB**; up to **300 pages are printed** per order. A file
  with more than 300 pages opens with "Choose pages" already selected.
- The server checks the choice again against the real file and sets the price; the phone's number is only
  a preview. The Xerox PC prints only those pages, with the pickup code on the first printed page.
- The counter shows e.g. *"58 of 1000 pages (333-390) · 58 sheets"*.

- [x] Database: `setup.sql` run again on your Supabase (2 new columns, 50 MB file limit); all old orders kept
- [x] Website, server and Android app read page choices **identically** (20 tricky inputs tested on all three)
- [x] 1000-page PDF, pages `333-390` → ₹116, **58 sheets printed**: first = PDF page 333 (with the label), last = 390
- [x] Page `102` → ₹2, 1 sheet · `390-385, 5, 1-3, 2` × 2 copies → tidied to `1-3,5,385-390`, 20 sheets, ₹40
- [x] Mistakes refused with a clear message and no charge: `abc`, page `1200`, all 1000 pages, `1-400`, `700-`
- [x] Real browser test (Microsoft Edge): choose file → type pages → preview → pay (test) → Ready → counter
- [x] Phone-size screen: fits, no sideways scrolling
- [x] Android app: same Pages choice, builds (`app-debug.apk`), unit test passes
- [x] New agent built into the XeoGo Station installer
- [ ] 📱 Try it once in the Android app on your phone (STEP 8)
- [ ] 🖨️ Install XeoGo Station on the Xerox PC (★ section below)

---

## ★ XeoGo Station — the Xerox center software  (new)

**What it is:** one installer, `installer/CampusPrintStation-Setup-4.1.0.exe` (37 MB; version 3.0.0 until ★★). Like big companies' apps, it
carries its **own private Java inside**, so the Xerox PC needs **nothing else installed**. No admin password needed.

**At the college:** double-click the installer → Next → Install → Finish ("Open XeoGo Station now" is ticked).
The app opens with a 3-step wizard:
1. **Connect** — server address, counter password, PC name. The PC registers itself (no SQL any more).
2. **Printers** — it **scans every printer in Windows**; tick the ones to use, name them, choose B/W / colour /
   colour only, and press **Test B/W** / **Test colour** (the Step 1 test page with "Order TEST1").
3. **Ready** — opens the **counter**. Paid orders from the website now print by themselves.

After that it **starts with Windows**, keeps printing when the window is closed (icon next to the clock), and a
newer installer updates it in place. Full guide: `installer/HOW-TO-INSTALL.txt`.

**The counter in the app:** type the student's code → the order card shows printer + number of sheets → **Enter**
hands it over. Printer lamps with "Taking orders" switches, tabs (Printing / Ready / Problems / All), prices, and:

**"Order number on pages" switch** (top right of the counter, and in Settings): untick it for a while when a student
wants **only their own PDF/JPG on the paper** — orders then print with nothing added. Tick it again afterwards.
(Also added to `web/counter.html`.)

- [x] Installer built (jlink private Java + jpackage + Inno Setup), 37 MB, installs without admin
- [x] Installed on this laptop: Start menu + desktop shortcuts, uninstall entry in Windows Settings → Apps
- [x] Setup wizard: wrong password refused; PC registered; **8 Windows printers found** (real ones first, virtual ones marked)
- [x] Test print from the wizard → "Order TEST1" test page came out
- [x] Orders from the student website printed **by the installed app** (B/W PDF, colour photo, pages 333-390)
- [x] Counter: code typed → "Ready on Front B/W · 3 sheets" → **Enter** → handed over
- [x] Switch OFF → order printed with **nothing** in the corner · switch ON → label back
- [x] Start with Windows ✓ · second double-click just brings the window forward · an update over the running app works
- [x] Safety: the app's screens only answer on this PC, with a secret per-window token (tested: 401 / 403 otherwise)
- [ ] 🖨️ Install at the Xerox center and press Test print on each real Canon
- [ ] 💳 Optional: code-sign the installer so Windows shows no "More info → Run anyway" warning

---

## ★ Going online: student website on Netlify + backend  (new)

The student website runs in students' browsers, so the **backend must be on the internet with HTTPS** (not on a
laptop). Everything is prepared:

- [x] `render.yaml` + `backend/Dockerfile` — put the backend on Render (or any Docker host)
- [x] `web/_headers`, `web/_redirects`, `web/netlify.toml` — the `web` folder is the Netlify site (dragged onto Netlify by hand, or published from git); the staff counter page is kept off it
- [x] Website polished: logo, clear headline, 3-step "how it works", tab icon, share preview for WhatsApp
**Cost to start: ₹0** (GitHub, Render free, Netlify free, Supabase free; Razorpay only takes a fee per payment).
The project was checked: no passwords or keys will be uploaded (`backend/.env` stays on this laptop).

**A. Accounts (15 minutes, all free)**
- [ ] github.com — sign up
- [ ] render.com — "Sign in with GitHub"
- [ ] netlify.com — "Sign up with GitHub"
- [ ] dashboard.razorpay.com — stay in **Test mode** for now

**B. Code on GitHub**
- [ ] github.com → **New repository** → name `campus-print` → **Private** → Create (add nothing else)
- [ ] On this laptop (a GitHub login window opens on `push`):
      ```
      cd "D:\Downloads\files (6)\remoteprint\remoteprint"
      git add -A
      git commit -m "XeoGo"
      git remote add origin https://github.com/YOUR-NAME/campus-print.git
      git push -u origin main
      ```

**C. Backend on Render**
- [ ] render.com → **New +** → **Blueprint** → pick `campus-print` → it reads `render.yaml`
- [ ] Fill the values from `backend/.env`: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`,
      `COUNTER_PASSWORD` (choose a strong new one), `RAZORPAY_KEY_ID` + `RAZORPAY_KEY_SECRET` (**test** keys `rzp_test_…`),
      `WEB_ORIGINS` = `https://example.invalid` for now → **Apply**
- [ ] Wait for **Live** (first build ≈ 5–10 min). Open `https://<your-service>.onrender.com/api/v1/shop` → shows your prices.
      Write the address down.
- [ ] Never use `PAYMENT_MODE=demo` online: demo prints without payment.

**D. Student website on Netlify**
- [ ] In `web/config.js` set `apiBase: "https://<your-service>.onrender.com"` → `git commit -am "Backend address"` → `git push`
- [ ] netlify.com → **Add new site** → **Import an existing project** → GitHub → `campus-print`
      → Base directory `web` · Build command *(empty)* · Publish directory `web` → **Deploy**
- [ ] Site configuration → **Change site name** → e.g. `gitprint` → your site is `https://gitprint.netlify.app`
- [ ] Back on Render → Environment → `WEB_ORIGINS` = `https://gitprint.netlify.app` → Save (it restarts)

**E. Xerox center PC**
- [ ] Build the installer with the address inside:
      `agent\packaging\build-installer.ps1 -BackendUrl "https://<your-service>.onrender.com"`
      (already built for `https://campus-print-backend.onrender.com`: `installer/CampusPrintStation-Setup-4.1.0-GitQuickPrint.exe`)
- [ ] Install it on the Xerox PC → connect (counter password) → tick the Canons → **Test print** each one
- [ ] In the Station: Printers page → remove the old example rows "Printer 1–4 (Canon iR…)" if they are still listed

**F. Test with fake money, then go live**
- [ ] On your phone open the Netlify site → order 1 page → pay with Razorpay **test** UPI/card (Razorpay docs:
      "Test card details") → it must print at the Xerox PC → hand over with the code
- [ ] Razorpay → **Activate account** (KYC: PAN, bank account, business details). Razorpay usually checks that the website
      has Contact, Terms, Privacy and Refund pages — ask Claude to add them with your real details.
- [ ] After approval: Razorpay → Live mode → API keys → put `rzp_live_…` keys in Render → Save
- [ ] Print the poster from `https://gitprint.netlify.app/poster.html` and put it up. Share the link in class groups.
- [ ] Stop the backend on this laptop (it is in demo mode) and use only the online one.

**Good to know**
- Render free sleeps after 15 min without visitors; the Xerox PC keeps it awake while it is on. When students rely on
  it all day, switch the service to **Starter** in Render (a few dollars a month).
- Supabase free projects pause after about a week without any use (e.g. holidays): Supabase dashboard → Restore.
- Every `git push` updates Render and Netlify by themselves.

---

## ★ Student website v2, phone app and QR poster  (new)

*Designed & developed by Vedant Pravin Surve* — now shown in the footer of the website, the Station app
(sidebar + Settings → About), the web counter, the Android app, the installer (Publisher) and the poster.

- [x] **New design**: "Print from your phone. Skip the queue." landing page, upload card, how it works, prices,
      questions; an app-style checkout (preview on a dark viewer, options panel); pickup code on a ticket.
      Tested in Microsoft Edge on a laptop and a phone-size screen: full order works, no errors, no sideways scrolling.
- [x] **Installable as a phone app** (no Play Store): Android shows **Install app**; iPhone: Share → Add to Home Screen.
- [x] **"Notify me when ready"** + the tab title turns into "✅ Ready · CODE", so students wait anywhere, not in the line.
- [x] **"Tell a friend"** share button (WhatsApp etc.).
- [x] **QR poster**: open `your-site/poster.html` → **Print poster** (A4). The QR code was checked with a QR scanner.
- [ ] 🪧 After going online: print 5–10 posters (queue wall, counter, library, hostels, canteen, notice boards)
- [ ] 🌐 Optional: a short own domain (e.g. `gitprint.in`) connected to Netlify, so the link looks professional

---

## ★★ Version 4: many files per order, every print option, printer-aware  (new)

**What students get now** (website): add all files at once (PDF, Word, JPG, PNG, mixed; drag & drop, pick several,
paste), each with its own upload progress, cancel, try again and remove. Then set up **each file on its own**,
with a print preview of every sheet:

- **Pages**: type `3, 7, 10-12` or tap page pictures (All / None / Odd / Even / Invert). Always shown as
  *"Selected: 3, 7, 10–12 · Total pages to print: 5"*, and the server prints exactly that selection.
- **Copies** (1 / 2 / 3 / any, collated or not), **B/W or colour**, **one- or two-sided** (long or short edge),
  **paper size**, **orientation** (auto / portrait / landscape), **scale** (fit / actual / custom), **pages per
  sheet** (1, 2, 4, 6, 9, 16), **margins**, **staple / hole punch / binding**, paper type and quality.
- **Pictures**: fit, fill, actual size, custom size, turn, centre, borderless — never stretched.
- Only what the connected printers can do is offered. Options that need another change say so
  (*"A3 → black & white"*). If the printers change meanwhile, the file offers a one-tap fix.
- **Review and pay** lists every file with every setting, sheets and price, as checked by the server.
- Phone: list → tap a file → full-screen settings with preview and page picker. Laptop: list, preview and
  settings side by side. A reload keeps the unfinished order.

**What the Xerox center gets** (Station 4.1.0): the Station reads what each printer can do and keeps the
server up to date; staff choose what to offer per printer ("What students can choose on this printer");
prices per printed side with extra % for A3 / special paper and prices for finishing; every file of an order is
its own print job, sent to a printer that can do all of it; the counter shows each file with its printer,
**Print again** and **Cancel**.

Tested:
- [x] Database: 11 automatic tests on PostgreSQL 17 (the Supabase version): printer rules, which printer gets which
      file (only files it can fully do, one order stays on one printer, older Stations only plain A4), status
      rules, recovery never reprints by itself, the upgrade of old orders
- [x] Backend: 11 end-to-end API tests (multi-file order, review, edit, pay, print, counter) + 11 shared-rule tests
- [x] Station: 24 tests, 4 of them printing through Windows to the test printers: actual size lands exactly,
      A3 two per sheet with copies, print ticket applied and the printer's defaults restored, refusals
- [x] Website: 74 shared-rule checks; full flows in Microsoft Edge at phone (390×844), tablet (820×1180) and
      laptop (1440×900) sizes: no sideways scrolling, no errors; wrong files, duplicates, slow network with
      cancel and try again, reload in the middle, a printer switched off during setup, edit after review
- [x] The example order (Notes.pdf pages 1–10 B/W two-sided, Assignment.pdf pages 3 and 7 × 2 colour one-sided,
      Photo.jpg colour A4) → ₹20 + ₹40 + ₹10 = ₹70, as expected
- [x] Real Station code against the demo backend: discovered the laptop's printers, printed an order of two files
      on two printers in 12 s; the printed PDFs match the website preview (A3 two pages per sheet; photo filling A4)
- [x] Station 4.1.0 reliability, tested on a real Windows printer: a paid order arriving while the printer is
      offline waits on the server and prints once when it is back; a Station killed while a file waits in a paused
      printer's queue watches it again after the restart and reports it printed exactly once
- [x] Files can be moved up and down (website and app); the list order is the print order, kept after a reload
- [x] Live Supabase upgraded: backup first (`D:\Downloads\files (6)\supabase-backup-before-v4-2026-09-29`), rehearsed on a
      copy, then run in one transaction; all old orders kept (each became an order with one file)

To do, in this order:
- [ ] 1. **Deploy the new backend** (Render builds it from GitHub: commit and push this folder). Until then the
      online backend is the old one and **cannot print** with the upgraded database. Check afterwards:
      `https://campus-print-backend.onrender.com/api/v1/shop` shows `"maxDocuments":25` and a `"printing"` part.
- [ ] 2. **Deploy the website** (Netlify builds it from the same push). Open it on a phone: add two files.
- [ ] 3. 🖨️ **Install Station 4.1.0** on the Xerox PC: `installer/CampusPrintStation-Setup-4.1.0-GitQuickPrint.exe`
      (server address built in) — it updates the old Station in place. Then Station → Printers: check each Canon's
      labels (paper sizes, two-sided, finishing), untick what you do not want to offer, **Save**.
- [ ] 4. 🖨️ In the Station, remove the old example rows "Printer 1–4 (Canon iR…)" if they are still listed.
- [ ] 5. 🖨️ One test order per option on the real Canons: two-sided (long and short edge), A3, 2 per sheet.
      Stapling, punching and binding are hidden until you tick them: print one test per position, and tick
      only those that came out right.
- [ ] 6. Station → Settings: prices per printed side; extra % for A3 (200 % preset) and special paper; finishing prices.
- [ ] 📱 Android 3.0.0 (many files per order, same settings as the website): install it on your phone and try one order.

---

## ★★★ XeoGo Pay — your own UPI payment gateway  (new)

**What students get:** Pay → the phone shows the UPI apps on it (Google Pay, PhonePe, Paytm, BHIM…; a laptop shows a
QR code) → they pay → come back → **Payment successful** by itself in about a second → printing → their files and times.
Nothing to type, nothing to press. The money goes straight into the Xerox center's bank account: no gateway, no fees.

**How it knows:** the business UPI app on the shop's phone gets "₹20.01 received" pushed by its server within seconds;
the new **XeoGo Pay Verifier** app on that phone passes it to the server, which matches it to exactly one order
(every payment has its own amount, ₹20.**01**) and releases the print job. The bank's SMS is a backup. Full guide,
safety rules and a presentation script: [`docs/campuspay-upi.md`](docs/campuspay-upi.md).

- [x] Server: XeoGo Pay API, bank-message reading (SBI, HDFC, ICICI, Axis, Kotak, PNB, PhonePe/Paytm/GPay texts), matching
      inside the database under one lock · 15 + 9 automatic tests
- [x] Website: UPI app buttons (Android intent / iPhone links), QR code on laptops, automatic "Confirming… → Payment
      successful", fallbacks · tested in Edge at phone, iPhone and laptop sizes
- [x] Android app 3.2.0: lists the UPI apps really installed on the phone, with icons; comes back and confirms by itself
- [x] XeoGo Pay Verifier 1.0.0 (new app for the shop phone): tested on Android 14 — real SMS → order paid in 0.3 s,
      real notification → 0.55 s; OTPs and other SMS never leave the phone
- [x] Station 4.2.0 and web counter: **UPI payments** tab (today's payments, the Verifier phone's status, every bank
      message and the order it paid, backup "Money received" button, "Paste a bank SMS")
- [ ] 1. Get a **business UPI ID** for the shop's bank account (PhonePe Business / Paytm for Business / Google Pay for
      Business / BharatPe — free). Only the UPI ID is needed: never give out the account number or IFSC.
- [ ] 2. Render → Environment: `PAYMENT_MODE=upi`, `UPI_ID` (or paste your QR's text), `UPI_NAME`, `UPI_ALERT_TOKEN`
      (Render generates it) → Save. (The server brings the database up to date by itself when it starts.)
- [ ] 3. Push this folder to GitHub (Render and Netlify update by themselves).
- [ ] 4. 📱 Shop phone: install `XeoGoPay-Verifier-1.1.0.apk` → server address + token → Save and connect → Allow
      notifications, SMS and battery (Android 13+: App info → ⋮ → Allow restricted settings first).
- [ ] 5. Students' app: install `XeoGo-3.3.0.apk`. 🖨️ Xerox PC: install `XeoGoStation-Setup-4.3.1-GitQuickPrint.exe`.
- [ ] 6. Pay ₹2.01 for a one-page order from your own phone and watch it confirm and print.

---

## ★★★★ Version 5 — XeoGo: show your files, free staff printing, one-push updates  (new)

**The name.** The product is **XeoGo** (*QuICK PrINT*); the logo is `branding/logo.png` on every app. What was
"Campus Print" before is the same thing: installed copies update in place and keep their orders and settings.

**No pickup code any more.** After paying, the order shows a picture of each file with the times (paid, ready at
about, ready since). At the counter the student taps **I'm at the counter**: the order appears on the counter's
screen with the same pictures; **Handed over** turns the phone to **Collected**. A screenshot or a copy of the PDF
cannot collect. Details: `docs/how-it-works.md` → *Collecting*.

**Free printing for college staff.** Station → **Staff**: make a staff ID (name → username + password, shown
once). Staff sign in on `staff.html` or in the **XeoGo Staff** app and press **Print now · free**, up to the pages
per month you set (1000 to start with; one ID can have its own number). No payment, no code. Details:
`docs/how-it-works.md` → *Staff printing*.

**Updates with one push.** The server brings its own database up to date when it starts: you never run
`setup.sql` by hand again. Push the code → Render and Netlify update by themselves.

**The student app moves.** XeoGo (the students' app, not the staff app) opens with a two-second opening: the
logo lands, a light goes round it, the name is set, a few printed pages fly out, and the screen opens onto the
app (a tap skips it). While files are on their way, the top of *Your files* is a card with a ring that fills,
the per cent counting up and what is happening in words; each file gets a green tick when the Xerox center has
accepted it. Phones set to "remove animations" show none of it. The code is in `android/app/.../ui/Motion.kt`,
`Intro.kt` and `UploadStage.kt`.

**Word files (.docx).** Students add Word files like PDFs (website, both apps, "Share to XeoGo"). The server
first looks inside the file: only a plain document goes on (no macros, no embedded programs, nothing that
fetches or asks). Then the **Xerox PC's own Microsoft Word** turns it into pages, hidden, in about a second, so
it looks exactly as if the student had brought it on a pen drive; from then on it is a PDF like any other, with
every setting (pick pages on the page pictures, copies, colour, two-sided, pages per sheet, paper, finishing)
and the same price rules. The student sees those pages before paying. Station → **Printers** → **Word files**
says whether this PC can do it ("Ready: Microsoft Word 2016") or why not. It needs Microsoft Word on the Xerox
PC and works while the Station is on; otherwise, and for old `.doc` files, students are told to save as PDF.
Details: `docs/how-it-works.md` → *Word files*.

**Hundreds of customers at once.** Tried with 500 customers arriving within 40 seconds: every order printed,
no wrong answer. What makes that hold: each phone is told by the server how soon to ask again (every few
seconds while it prints or the student stands at the counter, seldom while nothing can change, and less often
for everybody when the server is busy); big uploads are checked one after the other, small ones together, so
memory cannot run out; a busy server makes a file wait its turn instead of failing; two printers finishing files
of one order at the same instant no longer trip over each other in the database. The limit of new orders from
one network address is 1000 a minute (a whole campus shares one address). What the free plans can carry, and
when to pay for more: `docs/troubleshooting.md` → *Many customers at once*.

**"Waking up the print service".** On Render's free plan the server sleeps after about 15 quiet minutes and
needs a minute or two for the first visitor. The website and the apps now say exactly that, calmly, keep asking
by themselves, and add the files chosen meanwhile as soon as the server is up (before: a red "Cannot reach the
print service"). While the Xerox PC's Station is running it talks to the server all the time, so the server
stays awake during opening hours.

**Closed on the way** (found while hunting for loopholes): a text message from any phone ("Rs 20 credited") or a
chat message in a UPI app could have been taken for a payment → only bank sender names and business UPI apps count
now; the counter password could be guessed without limit through a forged address → limited per address and in
total, and the counter stays signed in with a token; an empty `PAYMENT_MODE` meant free test printing → it now
means paying is switched off; other websites could reach the Station on the PC → refused; scripts from other
websites ran next to the students' order keys → PDF.js and the QR library are part of the site now, with a
Content-Security-Policy; pages typed and then left at once were lost → kept.

- [x] Server: hand-over without a code, first-sheet pictures, ready-time estimate, staff IDs and the monthly
      limit inside the database, database self-update, Word files, the asking pace · 126 automatic tests
      against a real PostgreSQL, and a run with 500 customers at once
- [x] Student website and staff website (`staff.html`): driven in Edge on phone and laptop sizes
- [x] Android: XeoGo 3.3.0 (students, with the opening and the animated uploads) and XeoGo Staff 3.3.0 from the
      same code · XeoGo Pay Verifier 1.1.0
- [x] Station 4.3.1: **At the counter now**, pictures, **Find**, **Staff** screen, **Word files** (tried with a
- [x] Station 4.3.1: a printer that is off (or offline, out of paper) when the PC starts gets no document in the first seconds either: the Station asks Windows how each printer is before it takes anything, and tells the server at once. A harmless error line ("Unmapping is not supported") is gone from the Station's log.
      real Microsoft Word); updates "Campus Print Station" in place
- [ ] 1. Push to GitHub (Render builds and starts the new server; Netlify publishes both websites).
- [ ] 2. 🖨️ Xerox PC: run `XeoGoStation-Setup-4.3.1-GitQuickPrint.exe` (it replaces the old Station and keeps its
      settings). Until then the old Station still prints; staff find an order by the **Order …** number shown
      on the student's order.
- [ ] 3. Station → **Staff** → set the free pages per month → make a staff ID for each staff member, hand out
      the slips.
- [ ] 4. 📱 Students: `XeoGo-3.3.0.apk` (or just the website). Staff: `XeoGoStaff-3.3.0.apk` (or `…/staff`).
- [ ] 5. 📱 Shop phone, only with XeoGo Pay: `XeoGoPay-Verifier-1.1.0.apk` over the old one.
- [ ] 6. One test order from a phone: pay → see the file and the times → **I'm at the counter** → **Handed
      over** → **Collected**. One staff order: sign in → **Print now · free** → the pages left go down.

---

## STEP 7 — Real payments with Razorpay (test keys first)

- [ ] 1. Make an account at dashboard.razorpay.com. Stay in **Test Mode**.
- [ ] 2. Account & Settings → **API Keys** → Generate test key.
- [ ] 3. In `backend/.env`:
   ```
   PAYMENT_MODE=razorpay
   RAZORPAY_KEY_ID=rzp_test_...
   RAZORPAY_KEY_SECRET=...
   ```
- [ ] 4. Stop the backend (Ctrl+C) and start it again (`mvn spring-boot:run`).
- [ ] 5. Make an order on the website. The Razorpay window opens. Use Razorpay's **test UPI / test card**
   (shown on Razorpay's "Test card details" docs page).

**You are done when:** after the test payment the order prints, and the payment shows in the Razorpay dashboard.

*Live money needs Razorpay KYC (business verification) and live keys `rzp_live_...`. Only do that when everything works.*

---

## STEP 8 — The Android app (optional)

- [x] 1. Android Studio → **Open** → choose the `android` folder. Wait for "Gradle sync" to finish.
   > ✅ The Gradle wrapper (`gradlew`, `gradle-wrapper.jar`) was missing and has been added. `gradlew assembleDebug`
   > → **BUILD SUCCESSFUL**. Since version 3.3.0 it makes two apps from the same code:
   > `app/build/outputs/apk/student/debug/app-student-debug.apk` (XeoGo) and
   > `app/build/outputs/apk/staff/debug/app-staff-debug.apk` (XeoGo Staff). SDK Platform 35 was installed.
- [ ] 2. Open `app/src/main/java/edu/campus/printapp/AppConfig.kt` and set `API_BASE` to your laptop's
   Wi-Fi address, e.g. `http://192.168.1.10:8080` (phone and laptop on the same Wi-Fi).
- [ ] 3. Connect your phone (USB debugging on) → press the green **Run** ▶ button.

**You are done when:** you can pick a PDF on the phone, see it, pay (test), and see your file with the times
and the **I'm at the counter** button.
You can also **Share** a PDF from WhatsApp to "XeoGo".

---

## You finished!

Next: read `README.md` → **Going live** (put the backend on HTTPS, set `WEB_ORIGINS`, switch
to live Razorpay keys, build the release app).
