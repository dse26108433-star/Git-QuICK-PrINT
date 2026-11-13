# Your own XeoGo server, on the Xerox center's PC

From Station 5.0.0 the PC in the Xerox center can be the XeoGo server itself. This page says why, what
it needs, how to set it up once, and what happens on an ordinary day and on a bad one.

**Short version:** install XeoGo Station 5.0.0, choose **This PC is the XeoGo server**, and give the PC an
address on the internet with a free Cloudflare Tunnel (needs a domain name: about ₹700–900 a year). After
that nobody starts or stops anything: the server starts with the Station, the Station starts with Windows.

---

## Why

The server used to be Render's free plan (a tenth of one processor, 512 MB of memory, in Singapore) with a
free Supabase database and file store (in Mumbai). That is what made the app hang when many students came
at once. Measured on 11 November 2026, on the live service:

| | Free cloud (before) | This PC as the server |
|---|---|---|
| Processor and memory | 0.1 processor, 512 MB | the whole PC (an i5, 8 GB or more) |
| After a quiet time | asleep: **more than 3 minutes** without an answer | never asleep while the PC is on |
| One small question ("how is my order") | about 0.45 s | about 0.01 s |
| A file's way | phone → Supabase → server → Xerox PC (three trips) | phone → Xerox PC (one trip) |
| A dropped connection during an upload | the whole file again | carries on from where it stopped |
| File traffic allowed | 5 GB a month (about a week at 100 files a day), then the project is restricted | none: your own disk |
| Database | pauses after a week without use (holidays) until someone restores it by hand | on this PC, always there |
| When the Xerox PC is off | students can still order (it prints next morning) | **students cannot order**: "closed right now" |
| Monthly cost | ₹0 | ₹0 (the domain: about ₹700–900 a year) |

The one thing you give up is ordering while the PC is switched off. The website and the apps say so
calmly ("The Xerox center's computer is not on right now…") and carry on by themselves when it is back.
If ordering at night matters more than speed, the alternative is a paid cloud server (about ₹4,000 a
month for one that is fast enough, plus Supabase Pro); everything in this version still works that way.

## The parts

```
 students' phones and laptops                       the Xerox center's PC
 ────────────────────────────                       ─────────────────────────────────────────────────
 website (Netlify) / XeoGo app                      XeoGo Station  (starts with Windows)
        │                                             ├─ counter screen, printing            (as before)
        │  https://print.yourcollege.in               └─ starts, watches and restarts:
        ▼                                                  1. the database       PostgreSQL 17
   Cloudflare  ◄══════ tunnel (the PC calls out) ══════    2. the print service  the same server program
                                                           3. the tunnel         cloudflared
                                                        files, database, backups: on this PC's disk
                                                           │
                                                           └─ a copy of every backup ──► your Supabase project
```

- Nothing is opened in the router. The PC calls out to Cloudflare, and Cloudflare passes students' requests
  down that connection. The database and the print service listen on this PC only (127.0.0.1).
- The files (PDFs, pictures, Word files) are kept in a folder on this PC until they are printed, then deleted.
- The database is "connected to the cloud" in this sense: the PC works on its own fast database, and a copy
  of it goes to your Supabase project every half hour. See *Backups*.

## What you need

| | |
|---|---|
| The PC | Windows 10 or 11, 64-bit, 8 GB of memory or more, 20 GB free. It can be switched off every evening. |
| Internet at the Xerox center | The same connection the Station uses now. The network must allow **outgoing port 7844** (most do; some college firewalls do not: the Station tells you). |
| A domain name | For example `yourcollege-print.in`, from any registrar. It is the address students' phones use. |
| A Cloudflare account | Free. The domain is added to it. |
| The installer | `XeoGoStation-Setup-5.0.0.exe` (about 120 MB). It contains everything: no Java, no database to install. |

---

## Setting it up (once)

Do it on an evening after closing, when no paid order is waiting.

### 1. Windows on the Xerox PC (2 minutes)

- [ ] **Settings → Update & Security → Windows Update → Change active hours**: 9:00 to 19:00. Windows then
      never restarts for an update while the shop is open. (During our own tests Windows Update restarted the
      test computer in the middle of the night: this setting is what prevents that during the day.)
- [ ] The PC must not fall asleep while the shop is open. The Station sees to that itself: it sets "Sleep:
      Never (plugged in)" and, while the server runs, asks Windows not to sleep from being idle. Only somebody
      choosing **Sleep** by hand (or closing a laptop's lid) still puts it to sleep: tell staff to use
      **Shut down** in the evening and nothing else during the day.
- [ ] Optional, so the PC comes back by itself after a power cut: in the PC's BIOS, "Restore on AC power
      loss: Power on", and Windows signing in without a password (`netplwiz`). Without these, somebody
      switches the PC on and signs in in the morning, as now.

### 2. Install the Station and make this PC the server

- [ ] Run `XeoGoStation-Setup-5.0.0.exe` (see `installer/HOW-TO-INSTALL.txt`).
- [ ] In the setup choose **This PC is the XeoGo server**, choose the counter password (8 or more
      characters), and under *The shop's data* either:
  - **Start with an empty shop**, or
  - **Bring everything over from the cloud database** (staff IDs, prices, printers, orders): type the three
    values the cloud server uses, `DB_URL`, `DB_USER`, `DB_PASSWORD` (Render → your service → Environment).
    They are used once and not kept.
- [ ] Press **Start the server**. The first time takes about a minute; the screen says what it is doing.
- [ ] Tick the printers, test-print, finish.

> A Station that was already installed and connected to the cloud keeps working as it was after the update.
> To make it the server: **Settings → This PC → Make this PC the XeoGo server → Set up…** (do NOT press
> "Disconnect this PC" first: that would make the cloud forget this PC's printers before they are brought
> over). With the data brought over from the cloud, this PC's printers and their settings are kept.

### 3. Give the PC its address on the internet

- [ ] Buy the domain. At **cloudflare.com** make a free account → **Add a site** → your domain → Free plan.
      Cloudflare shows two nameservers: put them in at the registrar where you bought the domain. Wait until
      Cloudflare says **Active** (minutes to a few hours).
- [ ] Cloudflare → **Zero Trust** → **Networks → Tunnels** → **Create a tunnel** → *Cloudflared* → name it
      `xerox-pc`. (Cloudflare may ask you to choose the free Zero Trust plan first.) It shows a line to
      install the tunnel on Windows with a long token that starts with `eyJ…`. **Copy that line.** Do not
      run it: the Station runs the tunnel itself.
- [ ] In the same tunnel: **Public hostname → Add**: subdomain `print`, your domain, type **HTTP**, URL
      `127.0.0.1:47810` → Save.
- [ ] Station → **Settings → The server on this PC → Internet address**: paste the line (or just the token),
      type `https://print.yourdomain` → **Save**.

**You are done when** the *Internet address* lamp is green and says *"Students can reach this PC"*. The
Station asks that address from outside every minute, so the lamp means what it says.

If it says the network blocks the tunnel: ask whoever runs the network to allow outgoing port **7844**
(TCP and UDP) to Cloudflare, or connect the PC to another internet connection.

### Testing before you have a domain (free, temporary)

For trying the server out (on a laptop first, for example) there is a free way that needs no domain and no
account: Cloudflare's *quick tunnel*.

1. Set up the PC with **This PC is the XeoGo server** (payment *Test only*). Leave the internet address boxes empty.
2. Double-click **`FREE-TEST-ADDRESS.bat`** (next to the installer). After a few seconds it shows an address like
   `https://some-random-words.trycloudflare.com`. Keep that window open while testing.
3. Make a **test copy of the website**: copy the `web` folder, set `apiBase` in its `config.js` to that address,
   and put it on a **second** Netlify site (Netlify → *Add new site → Deploy manually* → drag the folder; free).
   Never change the live site's `config.js` for a test.
4. On a phone **on mobile data** (Wi-Fi off), open the test site: add a PDF, a photo and a Word file, pay (test),
   and watch it print on that PC.

Limits: the address changes every time the window is closed or the PC restarts (then put the new one in the test
`config.js` and drag the folder again); Cloudflare gives no guarantee for quick tunnels and allows about 200
requests at the same moment. Fine for testing, not for the shop. The apps cannot be pointed at a test address
(they follow the real move later, through `MOVED_TO`); they talk to the server exactly as the website does.

**Keep the Xerox center's PC on the cloud server until the domain is ready.** Updating its Station to 5.0.0 and
leaving it on Render ("The server is somewhere else") is safe and already tests the new Station with the real
printers. Switching it to "This PC is the XeoGo server" means the orders students send to Render stop printing
there, so do that once, for real, on the evening you have the domain and the tunnel (steps 2 and 3).

### 4. Payments, and the copy in the cloud

Station → **Settings → The server on this PC**:

- [ ] **How students pay**: XeoGo Pay (the shop's UPI ID) or Razorpay (Key ID and Key Secret). Until this is
      set, nobody can pay. What you type is checked before it is saved.
- [ ] XeoGo Pay: on the shop's phone, open **XeoGo Pay Verifier** and put in the new server address and the
      token shown under *How students pay* (**Copy token**).
- [ ] **A copy of the database in the cloud**: your Supabase project's address (`https://….supabase.co`) and
      its **secret key** → Save. The first copy is sent at once.

### 5. Point the website and the apps at the new address

- [ ] **Website**: in `web/config.js` set `apiBase: "https://print.yourdomain"` and, if you like,
      `openHours: "9:30 AM to 6 PM"`. Put the `web` folder on Netlify again.
- [ ] **Apps**: XeoGo 3.4.0 and XeoGo Staff 3.4.0 go to the new address by themselves once the old server
      says so (next step); nothing has to be reinstalled for that. For new phones later, build the apps with
      the address inside: `gradlew assembleStudentDist assembleStaffDist -PapiBase=https://print.yourdomain`.
- [ ] **The old cloud server** (Render): push this version to GitHub so Render runs it, then Render →
      Environment → add `MOVED_TO` = `https://print.yourdomain` → Save. From then on it tells every website
      and app that asks where XeoGo is now, and takes no new orders (an app older than 3.4.0 shows "XeoGo has
      moved… update the app"). Keeping it costs nothing.

### 6. One real order

- [ ] From a phone on mobile data (not the Xerox center's Wi-Fi): add a PDF and a photo, pay, watch it
      print, tap **I'm at the counter**, press **Handed over** in the Station.
- [ ] Switch the Xerox PC off and on again. Half a minute after signing in, the sidebar says **Ready to
      print** and the phone works again.

---

## An ordinary day

Nothing to do. The PC is switched on and signed in → the Station starts → the database starts → the print
service starts → the tunnel connects. The sidebar shows *Starting the server…* and then *Ready to print*.
In the evening Windows is shut down as usual; nothing has to be closed first.

On our test computer (a 2016 laptop, see *What was measured*): a first start, including making the database,
took **37 to 43 seconds**; every later start **22 to 34 seconds**. If a start ever takes longer (after a
power cut the database first checks itself), the screen says what it is doing and waits as long as it takes.

## What happens when…

| What happens | What the Station does | What you do |
|---|---|---|
| The PC is shut down in the evening with orders waiting | Paid orders and their files stay on the disk and print the next morning. | Nothing. |
| The power is cut, or Windows is killed, in the middle of the rush | The database repairs itself at the next start (tested: under 5 seconds), everything else starts as usual. Uploads that were cut carry on from where they stopped; paid files print. A file that was *at the printer* in that moment is reported as it really ended (printed, or "check the tray"). | Switch the PC on. |
| The PC is put to sleep by hand (or a laptop's lid is closed) | Nothing runs while it sleeps: students see "closed right now". On waking, everything carries on where it was (tested: 4 hours asleep in the middle of 400 orders, all printed afterwards). | Wake it up. Use Shut down, not Sleep. |
| The internet goes down | Printing of what is already here carries on. Students see "closed right now" and are let in again by themselves when it is back. A yellow line in the Station says students cannot reach the PC. | Nothing (or fix the internet). |
| A student's phone loses its connection during an upload | The pieces that arrived stay. When the phone has a connection again, the upload carries on by itself. | Nothing. |
| The print service or the tunnel stops | It is started again within seconds. | Nothing. |
| The database cannot be started three times in a row (a damaged disk area) | The damaged data folder is **put aside, never deleted**, a new database is made, and the latest backup is put into it. A notice in the Station says which backup. | Read the notice. Orders made after that backup are not in it: check the UPI app for payments after that time. |
| The disk is nearly full (less than 2 GB free) | New uploads are refused with "the Xerox center's computer is out of space". Nothing breaks. | Free some space. |
| Windows wants to update | With *active hours* set it waits until the PC is shut down or switched on. | Nothing. |
| The PC breaks or is replaced | — | Install the Station on the new PC, **This PC is the XeoGo server → This PC replaces another XeoGo server PC**, and type the Supabase address and key: the latest cloud backup is put back. Then paste the same tunnel token. |
| The address has to change (a new domain) | — | New public hostname in Cloudflare, type the new address in the Station, change `web/config.js`, and set `MOVED_TO` on the old cloud server to the new address: apps follow by themselves. |

## Backups

- **Every half hour** while the server runs (the first one a few minutes after the start), and when you press
  **Back up now**: a full copy of the database into `…\CampusPrint\server\backups`. The newest 30 are kept,
  and one a day for 45 days.
- **In the cloud** (if filled in): each backup is also sent to your Supabase project, bucket `xeogo-backups`
  (private): `latest.dump`, and one a day for 30 days. The shop keeps working when the cloud cannot be
  reached; the Station then says the copy did not work and tries again with the next backup. Using it also
  keeps the Supabase project from pausing while the shop is open.
- **Not in the backup:** the students' files themselves (they are short-lived and can be large) and the small
  pictures of first pages. After a restore, an order that was paid but not printed is still in the list;
  its file is gone, so it shows as a problem at the counter for staff to settle with the student.
- A backup is an ordinary PostgreSQL dump (`pg_dump -Fc`); the tools are in the Station's folder
  (`server\pgsql\bin`).

## Where things are on the PC

| | |
|---|---|
| The programs | `%LOCALAPPDATA%\Programs\XeoGo Station` (replaced by an update) |
| Settings, logs | `%LOCALAPPDATA%\CampusPrint` (`station.json`, `server.json`, `logs\`) |
| Database, files, backups | `%LOCALAPPDATA%\CampusPrint\server` (`pgdata\`, `files\`, `backups\`) |
| Logs to look at | `logs\agent.log` (the Station), `logs\server.log` (the print service), `logs\database-Mon.log`… (one per weekday), `logs\tunnel.log` |
| Ports, on this PC only | 47800 the Station's screens, 47810 the print service, 47811 tunnel status, 47812 the database |

`server.json` holds secrets (the database's password, the tunnel token, the payment keys). Only this
Windows user can read it. Do not send it to anyone.

## If something is wrong

| What you see | What to do |
|---|---|
| Sidebar: **Starting the server…** for more than five minutes | Read the words under it. Usually the database is checking itself after a power cut: wait. |
| **Server problem**: "Another program on this PC uses port 47810 (or 47812)" | Close that program, or restart the PC. |
| **Server problem**: "The print service stopped: …" again and again | The words after the colon say why (for example a payment setting). Correct it under *How students pay*. Still there: send `logs\server.log`. |
| Internet address: "Cloudflare does not accept the tunnel token" | Copy the token again from the tunnel's page at Cloudflare and paste it. |
| Internet address: "This PC cannot reach Cloudflare… port 7844" | The network blocks the tunnel: see step 3. |
| On the server PC itself, "The server is somewhere else" with the PC's own internet address was chosen | From 5.0.0 the Station notices the address leads to itself and stays the server. If the server was already switched off: Settings → Disconnect this PC → choose **This PC is the XeoGo server** (its database is used as it is). |
| Internet address: "The address exists, but it does not lead to this PC yet" | In Cloudflare the tunnel's public hostname must be type HTTP, URL `127.0.0.1:47810`. |
| Backup: "The copy in the cloud did not work: the cloud project is paused" | supabase.com → your project → **Restore**. Backups on the PC went on meanwhile. |
| Students: "The Xerox center's computer is not on right now" while it is on | Station → Settings → the *Internet address* lamp says what is wrong. |
| A notice: "The database was put back from the backup of …" | See the table above. The damaged data is in `server\pgdata.damaged-…`; keep it until you are sure nothing is missing. |

---

## What was measured, and on what

Everything below was run for real: real HTTP, real files, the real server, the real Station printing through
Windows. Nothing inside the server was simulated. The tool is `backend/src/test/java/edu/campus/demo/LoadTest.java`;
it makes "students" that each open the shop, start an order, send 1 to 3 files (PDFs with real pages and
pictures, JPGs, PNGs; from 1 KB to **49.4 MB**) at the speed of a phone connection (2 to 30 Mbit/s), have them
checked and priced, pay (test payments), and then ask "how is my order" as the apps do until every file is
printed.

**The computer:** a laptop with an Intel Core i5-6440HQ (4 cores, 2016), 8 GB of memory, Windows 10. The
server, the database, the Station **and the test itself** all ran on it at the same time, so the server had
less than the whole machine. Printers: two Windows printers that write PDFs (the real Windows print path,
without paper).

### Run 1: 100 students within one minute (11 November 2026)

162 files, 620 MB in all, five of them between 48.6 and 49.4 MB; pages chosen as students do (2,480 sheets).

| | |
|---|---|
| Orders | 100 started, **100 paid, 100 printed**, 0 failed |
| All 100 paid | about 2 minutes after the first student arrived |
| The server's check of a file | middle 48 ms; a file of just under 50 MB: 0.6 s (slowest 1.2 s) |
| Starting an order / paying | middle 27 ms / 18 ms (slowest 0.8 s / 0.5 s) |
| Sending a 49 MB file (at 2–30 Mbit/s) | 24 s in the middle, 37 s the slowest |
| Last order printed | 44 minutes after paying (two printers; see *does not show* below) |
| "How is my order" (21,763 questions) | middle 5 ms, 99 % within 0.5 s. **8 got no answer within two minutes, one took 85 s.** |

About those 8: Windows Update was installing on the test computer during this run, free memory was down to
261 MB, and right after the run Windows restarted the computer by force. The apps ask again by themselves, so
no order was affected, but it is why step 1 (active hours) is in this guide. In run 2, on a quiet computer,
there was no such gap.

### Run 2: 400 students within four minutes (12 November 2026, the installed program)

584 files, 1.98 GB in all, eight of them just under 50 MB. So that the run is not mostly waiting for paper,
each file printed at most 4 pages (1,956 sheets).

| | |
|---|---|
| Orders | 400 started, **400 paid, 400 printed**, 0 failed |
| All 400 paid | **4 minutes 25 seconds** after the first one (they arrived over 4 minutes) |
| Errors while ordering, sending and paying | **none**. No piece of a file had to be sent twice; the server never answered "busy". |
| At the busiest moment | 16 files arriving at once, 378 students waiting for their paper |
| The server's check of a file | middle 44 ms, slowest 1.8 s; a file of just under 50 MB: 0.5 s (slowest 1.2 s) |
| Starting an order / pricing / paying | middle 25 ms / 14 ms / 10 ms; 99 % within 0.7 s / 0.5 s / 0.3 s |
| "How is my order" (15,169 questions) | middle 5 ms, 90 % within 15 ms, 99 % within 2.7 s |
| The computer | processor 76 % on average (often 100 %: the test itself and the PDF printers take most of it); server 0.5–1.1 GB of memory, database 0.5–0.7 GB, Station 0.2–0.7 GB |
| Afterwards | no file left on the disk (each is deleted when printed) |

**What went differently from the plan in run 2, told as it was:** six minutes into the run the laptop was put
to sleep (Windows: "Sleep Reason: Application API", i.e. on request, not by itself) and stayed asleep for
4 hours 18 minutes. The test's own count therefore says only 34 orders printed "in time". When the laptop was
woken, the Station carried on by itself; during the rest of the queue it was also updated twice (a new
installer over the running program). The database shows the end of it: **all 400 orders printed, 584 files,
none failed**, the last one 65 minutes of running time after the wake-up. So this run proves the taking-in
of 400 orders and that a long interruption loses nothing; it is not a clean measurement of how long 400
orders take to print.

### What was broken on purpose

| What was done | What happened |
|---|---|
| **Power cut in the rush:** all 34 XeoGo programs were ended in the same instant while 2 orders were printing and 14 were paid and waiting | Switched on again: the database repaired itself in under a second; everything was back and printing after 44 seconds. All 23 paid orders in the database were printed, none failed. Uploads that had been cut carried on from where they were (8 pieces sent again). |
| **Windows restarted by force** (Windows Update, the night of 11 November) while the server ran | Next start: database repaired itself in 4 seconds, all 108 orders there, running after 34 seconds. |
| **Update in the middle of the queue** (237 orders waiting, 35 in progress) | The installer stopped the database properly and replaced the program in 30 seconds; 43 seconds after starting again it was printing. No file failed. |
| **The PC is replaced:** a new, empty PC is set up with "put the latest backup back" | It fetched the backup from the cloud copy (a stand-in for Supabase, see below) and ran after 38 seconds with the same shop: 114 orders, 191 files, 2 printers. |
| **The shop moves from the cloud to this PC:** a Station that is connected to a server somewhere else is made the server with "bring everything over from the cloud database" (13 November) | A wrong database password was said plainly and changed nothing. With the right one: copied, and running after 47 seconds with the same shop (114 orders, 191 files, 2 printers); the PC kept its registration, so its printers were there, found and ready to print without scanning again. |
| **The database is damaged** (its control file was destroyed) | Three failed starts, then by itself: the damaged folder put aside (kept), a new database, the latest backup put in. Running after 56 seconds with all 114 orders. |
| **The database's password is lost** (settings file replaced) | Noticed, set again, running after 23 seconds; the sign-in rules were closed again afterwards. |
| **A phone loses its connection** at 20 % of an 18 MB upload (website in Microsoft Edge, network cut on purpose), another file is added while offline | The file said "It carries on by itself when the connection is back"; back online both files finished without anyone pressing anything. |
| **The server's answer to a check gets lost** (the file did arrive) | The website asked how the file stands and showed it as ready; it was not sent again. |
| **Word files, the whole way** (13 November, the installed program): two .docx files made in Word (3 and 40 pages) sent in pieces like a phone does | The Station's Microsoft Word (2013 on the test laptop) turned both into pages in 8 seconds with the right page counts; priced correctly (8 sheets); both printed 26 seconds after paying. |
| **The PC falls asleep by itself?** | While the server runs the Station asks Windows not to sleep from being idle. Checked by asking Windows: request in force with the Station's helper, gone when the helper is stopped by hand, back when the Station restarts it. |

Automatic tests, all passing: the server 135 (8 of them for files on this PC's disk: pieces, a gap, a piece
sent twice, forged and expired links, too-large files, tidying up), the Station 38, the apps 77 (13 of them
for exactly the "connection lost" cases), the website's rules 93.

**What these runs do not show**, because it cannot be tested from here:

- **Real Canon printers.** How fast the queue empties depends on the printers. Four printers at 30 pages a
  minute print about 7,000 pages an hour; 400 orders arriving in four minutes is more than that for a while,
  and the last student then waits accordingly, whatever the software does.
- **Your network and the tunnel: tried once, from a laptop on home Wi-Fi.** On 13 November a free Cloudflare
  quick tunnel (`FREE-TEST-ADDRESS.bat`) to the laptop's server worked from outside: the shop answered, a
  test website was allowed to use it, the upload link pointed at the https address, and a PDF sent in
  pieces through it was accepted. Not tried yet: your own domain with a named tunnel, the college's network
  (it must let the tunnel out, port 7844), and a 50 MB file over your line. **Steps 3 and 6 are that test:
  do them before telling students.**
- **The copy in the cloud with your real Supabase project.** It was run against a stand-in that answers like
  Supabase's storage (make the bucket, send, fetch back, restore). With your own project and key the Station
  says at once whether the first copy arrived (Settings → Backup lamp).
- **The move from your real Supabase database.** "Bring everything over from the cloud database" was run
  against an ordinary PostgreSQL playing the cloud database (same tables, same data), not against a Supabase
  project. It copies only the shop's own part (`public`) and the screen says at once if the copy failed; your
  cloud database is only read, never changed, so if it does not work nothing is lost and the cloud server
  goes on as before.
- **Real phones on mobile data**, beyond the apps' own automatic tests and the website driven in Microsoft
  Edge (where the connection was cut on purpose in the middle of an upload).
