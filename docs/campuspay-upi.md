# XeoGo Pay — the Xerox center's own UPI payment gateway

*Part of XeoGo. Designed & developed by Vedant Pravin Surve.*

Students pay the Xerox center **straight into its own bank account** with any UPI app (Google Pay, PhonePe,
Paytm, BHIM, any bank's app). No payment company in between, no gateway fees, no KYC with a third party.
XeoGo Pay confirms every payment **automatically, in about a second**, and only then the files go to the printer.

```
 Student (website or app)             XeoGo server (XeoGo Pay)          Xerox center
 ────────────────────────             ───────────────────────────────          ─────────────────────────────
 1. files, colour/B&W, copies ──────► checks and prices the order
 2. Pay ₹20 ────────────────────────► fixes the amount ₹20.01 (unique) and
                                      builds the upi://pay link
 3. phone shows its UPI apps
    (GPay, PhonePe, Paytm…) → pays ─────────────────────── money, bank to bank (UPI) ───────► shop's bank account
                                                                                            │
                                                                     UPI business app: "₹20.01 received" (1–3 s)
                                                                     bank SMS: "credited Rs 20.01 …" (backup)
                                                                                            │
                                      ◄──────────── XeoGo Pay Verifier app on the shop phone passes it on
                                      matches ₹20.01 → exactly one order → PAID
 4. comes back to the site / app ◄─── "Payment successful" → queue ──────────► Station prints it
 5. the files and times, "Printing", "Ready" ◄──────────────────────────────── printed
```

## The parts

| Part | What it does | Where |
|---|---|---|
| **XeoGo Pay API** | Payment for each order: a unique amount, the UPI link, the payment's status; takes bank messages; never pays an order twice or two orders with one payment | `backend/…/payment/Upi*.java`, `upi_*` functions in `db/setup.sql` |
| **XeoGo Pay Checkout** | The payment screen. Android phones: the phone's own list of installed UPI apps + one button per app. iPhone: a button per app. Laptop: a QR code. Comes back and confirms by itself | website `js/upi-pay.js` + `js/app.js`; Android app `ui/UpiPayScreen.kt` (lists the UPI apps really installed, with their icons) |
| **XeoGo Pay Verifier** | Android app on the **shop's phone**. Passes on the UPI business app's "₹X received" notification (instant) and the bank's "credited" SMS (backup). Nothing else leaves the phone | `android/verifier` → `XeoGoPay-Verifier-1.1.0.apk` |
| **Dashboard** | Counter → **UPI payments**: today's payments, the Verifier phone's status, every bank message and the order it paid, and a backup "Money received" button | Station app, `web/counter.html` |

## Why it is instant, and why it does not depend on SMS

Big gateways (Razorpay, PhonePe PG) are licensed by RBI and get a callback from their bank the moment a UPI payment
succeeds. A business UPI app gets the same signal: its server pushes **"₹20.01 received"** to the phone within
seconds (the same push that makes a shop's soundbox speak). The Verifier reads that notification and sends it on
at once. Tested: **0.55 s** from the notification to "Payment successful" on the server.

The bank's SMS is only a backup (it can come late when the bank or network is slow). If both arrive, XeoGo Pay
knows it is the same payment and counts it once.

## What you need

1. **A UPI ID for the shop's bank account.** Best: a free **business** UPI ID — PhonePe Business, Paytm for
   Business, Google Pay for Business or BharatPe (PAN + the bank account; free QR, zero fees). Business UPI IDs work
   with payment links in every UPI app, and their app gives the instant notification. A personal UPI ID also works,
   but some UPI apps refuse *links* to personal IDs (scanning the QR still works) and the bank SMS is then the only
   signal.
   **Never share the bank account number or IFSC**: XeoGo Pay only needs the UPI ID.
2. **The shop's Android phone** with that business UPI app, signed in, and the **XeoGo Pay Verifier** app.
   Keep it on, on the internet (Wi-Fi + mobile data) and charging.
3. **Four settings on the server** (see below).

## Set up (15 minutes)

### A. The server

In `backend/.env` (on Render: your service → **Environment**):

```
PAYMENT_MODE=upi
UPI_ID=xeroxshop@ybl                # your UPI ID, or paste the text of your shop's UPI QR (see below)
UPI_NAME=Main Xerox Center          # the name students see in their UPI app
UPI_MERCHANT_CODE=                  # 4 digits from a business QR ("mc=…"), optional
UPI_ALERT_TOKEN=<40 random letters and digits>   # the Verifier's password; Render: "Generate"
```

Easiest way to get the UPI ID and merchant code right: scan your shop's UPI QR sticker with **Google Lens**, copy
the text (`upi://pay?pa=…&pn=…&mc=…`) and paste the whole thing as `UPI_ID`. XeoGo Pay reads `pa`, `pn` and `mc`
from it and ignores the rest.

Restart or deploy the server (it brings its database up to date by itself). The log says
`Payments: XeoGo Pay - direct UPI to …`.

Two optional values:

- `UPI_SMS_SENDERS`: your bank's SMS sender name(s), e.g. `SVCBNK, TJSBBK`, if it is not one of the well-known
  banks the server already knows (SBI, HDFC, ICICI, Axis, Kotak, PNB, BoB and about forty more). You can also do
  this without touching the server: the first message shows on the counter as *Not counted*, with a button
  **This is our bank**.
- `UPI_TRUSTED_APPS`: the package name of another app whose "money received" notifications should count (for
  example your bank's own app). Only for apps in which nobody else can write to you.

### B. The Verifier phone

1. Copy `XeoGoPay-Verifier-1.1.0.apk` to the shop phone and install it (allow "install unknown apps" once).
2. Open **XeoGo Pay Verifier** → type the server address (`https://campus-print-backend.onrender.com`; it must
   be an `https://` address, or the token is never sent) and the token (`UPI_ALERT_TOKEN`) → **Save and connect**.
   It says *Connected*.
3. **UPI app notifications → Allow** → switch on *XeoGo Pay Verifier*.
4. **Bank SMS → Allow**.
5. **Never paused by battery saver → Allow**.
6. Android 13 and newer may say **"Restricted setting"** for steps 3–4 (apps not from the Play Store). Then:
   **Open App info → ⋮ (top right) → Allow restricted settings**, go back, and tap Allow again.
7. Xiaomi / Oppo / Vivo / Realme: also switch on **Autostart** for the Verifier in the phone's settings.

The top of the Verifier now says **"Working: students' payments confirm by themselves"**, and the counter's
**UPI payments** tab shows *Verifier phone "…" online*.

### C. Try it

Order one page on the website from another phone, pay the ₹2.01 it asks, and watch: the page says *Confirming your
payment…*, then **Payment successful**, and the Station prints it. The Verifier's *Recent* list shows
*Confirmed order K7M4X*.

## What students see

- **Review and pay** → **Pay ₹20 with UPI**.
- **Android phone**: *Pay ₹20.01 with a UPI app* opens the phone's own list of the UPI apps installed on it; or tap
  Google Pay / PhonePe / Paytm / BHIM directly. The amount and the shop are filled in.
  **XeoGo Android app**: it lists the UPI apps really installed on the phone, with their icons.
- **iPhone**: a button per app. **Laptop**: a QR code to scan with any UPI app.
- They pay, come back (the UPI app returns by itself), the page says *Confirming your payment…* and a second later
  **Payment successful → Your files are going to the printer**, then their files with the times, *Printing*,
  *Ready*. At the counter they tap **I'm at the counter** and show the files: no code.
- **Nothing to type, nothing to press.** Only if no confirmation came after 3 minutes, a small link *Paid, but
  nothing happens?* lets them type the UPI reference number from their receipt; it is matched with the bank's
  message by itself.

Why ₹20.**01**? The few paise make every open payment's amount different, so the bank's "₹20.01 received" points
to exactly one order. Online payments are never whole rupees, so they are never confused with someone paying the
counter's QR sticker by hand.

## The rules that keep the money safe

- **Nothing prints on anyone's word.** An order is paid only when a bank message proves the money arrived (or,
  as an emergency backup, staff confirm it). What the student's phone or UPI app says is never proof.
- **One payment pays one order.** A UPI reference number can pay only one order; the same message sent twice, or the
  notification and the SMS of the same payment, count once; an amount just paid is not given to another order for
  15 minutes; amount-only matching needs exactly one open order with that exact amount, opened in the last hour.
- **Only the Verifier can report payments**: it sends a secret token (`UPI_ALERT_TOKEN`); wrong tokens are refused
  and slowed down. The Verifier sends the token only over `https://`.
- **Only a message nobody else could have written counts.** Anyone can text the shop's phone "Rs 20.01 credited",
  and in the ordinary Google Pay / PhonePe / Paytm apps anyone can send the shop a chat message. So a message pays
  an order by itself only when it is an **SMS from a bank's sender name** (like `AX-SBIUPI`; never from a phone
  number) or a **notification of a business UPI app** (PhonePe Business, Paytm for Business, Google Pay for
  Business, BharatPe: they have no chat). Everything else is kept, shown on the counter as *Not counted* with the
  reason, and pays nothing. The Verifier (1.1.0) does not even send texts from phone numbers or chat apps.
- **"I have paid" needs the reference number** while payments confirm by themselves, so the counter's list
  cannot be filled with empty claims.
- **Privacy**: the Verifier sends only "money received" messages (OTPs, money going out, chats and every other SMS
  never leave the phone); the server keeps only those, with the account balance hidden.
- All matching runs inside the database under one lock (`upi_*` functions in `db/setup.sql`), so two messages, a
  student and staff acting at the same second cannot break these rules.
- If the Verifier phone is silent for 3 hours, the website switches to *"I have paid"* + staff check by itself, and
  the counter shows a red warning, so no student is ever stuck.

## Staff: the UPI payments tab

Station → Counter → **UPI payments** (and `web/counter.html`):
- *Students pay xeroxshop@ybl … Today: 42 orders paid, ₹610.25 (42 confirmed by the bank).*
- *Verifier phone "Redmi Note 12" online · last payment message 11:02.*
- The list of bank messages, and which order each one paid (*by amount*, *by reference*, *same payment*).
  A message that was not counted says why; for an SMS from a sender name the server does not know yet:
  **This is our bank (NAME)**, once. Confirmed senders are listed at the bottom and can be removed.
- Backup only: a payment the bank's message did not prove (phone off) shows with **Money received** / **Not
  found**; **Paste a bank SMS** checks a message by hand.
- Refunds: send the money back by UPI from the shop's app (the student's UPI ID is in the bank message).

## Honest limits

- XeoGo Pay is a payment *system* on top of UPI, not an RBI-licensed payment aggregator: the money moves by UPI
  directly between the two banks, and XeoGo Pay does everything around it (amounts, links, QR, confirmation,
  matching, receipts, dashboard). That is allowed for a shop receiving payments into its own account.
- The instant confirmation depends on the shop phone being on and online. Without it, payments are still safe and
  checked at the counter.
- Some UPI apps refuse *links* to personal UPI IDs: use a business UPI ID.
- On iPhone, each UPI app must be installed to open from its button; the QR code always works.

## For a presentation

> "I built XeoGo Pay, our own payment gateway. When a student pays, our server creates a payment with a unique
> amount and a UPI link. On Android the phone shows the student's own UPI apps; on a laptop a QR code. The money goes
> directly bank to bank through UPI: no Razorpay, no fees. The moment the payment company's server notifies our shop
> phone, our Verifier app forwards it with a secret token, and the server matches it to exactly one order inside the
> database, under a lock, and releases the print job. It takes about a second. The student's page updates by itself:
> Payment successful, printing, ready, with a pickup code. Nothing prints until the money is proven, a payment can
> never be used twice, and the bank's SMS is a backup channel."

Demo: laptop shows the QR; pay ₹2.01 from your phone; the laptop flips to *Payment successful* and the Station
prints. Or locally: `LocalDemo --upi` (see `backend/src/test/java/edu/campus/demo/LocalDemo.java`).

## API (for other apps)

| Call | Who | What |
|---|---|---|
| `POST /api/v1/orders/{id}/payment` | student (X-Order-Key) | `{provider:"upi", amountPaise, upi:{uri, payeeVpa, payeeName, amountText, tagPaise, merchant, autoConfirm}}` |
| `GET /api/v1/orders/{id}` | student | status; `payment:{provider, paid, tagPaise, claimRef, note, verifiedBy}` |
| `POST /api/v1/orders/{id}/payment/claim` | student | `{reference}` — fallback only |
| `POST /api/v1/payments/upi/alerts` | Verifier (X-Alert-Token) | `{text, from, source:"sms"\|"notification:<app>", sentStamp, device}` → `{kind, paidOrder, matchMethod}` |
| `POST /api/v1/payments/upi/heartbeat` | Verifier | `{device, version, sms, notifications}` |
| `GET /api/v1/counter/payments` | staff | the dashboard |
| `POST /api/v1/counter/orders/{id}/payment/approve` · `/reject` | staff | backup |

## Tested

- Server: 15 XeoGo Pay tests on a real PostgreSQL (unique amounts, whole rupees never match, bank message pays
  exactly one order, reference typed before/after the message, one reference one order, the same payment told
  twice, wrong token, OTPs never kept, expiry waits for claimed payments, Verifier alive/silent) + 9 tests of bank-message
  formats (SBI, HDFC, ICICI, Axis, Kotak, PNB, PhonePe/Paytm/GPay notifications).
- Verifier on an Android 14 phone (emulator): a real incoming SMS → order paid in 0.32 s; a real notification →
  paid in 0.55 s; OTP and chat SMS not sent.
- Website in Microsoft Edge (Android, iPhone, laptop sizes): automatic flow end to end, reload in the middle,
  missing app, fallback reference, counter mode. Android app: 5 XeoGo Pay flow tests.
