# Installing the app from a QR code (no Play Store)

Students scan a QR code on the poster, Chrome opens the XeoGo download page, they download and install the
app. Android warns about every app that does not come from the Play Store; this page says what was done to
keep those warnings as few and as mild as possible, and exactly what students tap.

## How it is set up

- **The QR code opens `https://gitprint.netlify.app/app`** and **the download of `XeoGo.apk` starts by
  itself**. The page that stays on screen (`web/app.html`) explains the taps for Android's install questions,
  has a *Download for Android* button to download again, *Use XeoGo in the browser* for iPhones, and a small
  link for the staff app. (A QR code straight to the file also works:
  `https://gitprint.netlify.app/download/XeoGo.apk`, but then students see no help.)
- **The apps are on the website itself**: `web/download/XeoGo.apk` and `web/download/XeoGo-Staff.apk`, sent
  as Android app files (`web/_headers`). Keep those two names: a new version only replaces the files, and the
  QR code never changes.
- **The poster**: open `https://gitprint.netlify.app/poster.html?url=https://gitprint.netlify.app/app`, check
  the address in the box, print on A4. A ready QR picture of that address is in the delivery folder too.
  (Your site has another address? Use it in both places.)
- Publish as before: drag the whole `web` folder onto Netlify. The delivery folder's `web` already has both
  app files in `download/`.

## What was changed in the apps to cause fewer warnings

- **Signed with XeoGo's own key.** Up to 3.3.0 the apps were signed with a development ("Android Debug") key,
  the kind Play Protect treats as a test app from nobody it knows. 3.4.0 is signed with a key made for XeoGo,
  kept in `D:\Downloads\XeoGo\signing-key-KEEP-SAFE\` (*not* in GitHub).
- **Nothing sensitive is asked for.** Internet, whether there is a connection, and which UPI apps are on the
  phone: nothing else. No SMS, contacts, location, camera, microphone, storage or accessibility (those are
  what make Play Protect block an app outright).
- **No card payment library inside.** Up to 3.3.0 the apps carried Razorpay's card payment library, which
  added NFC and phone-state permissions and a lot of third-party code. 3.4.0 pays with UPI only (XeoGo Pay)
  and is smaller. A shop set to Razorpay (cards) is paid on the website; the app says so.
- **Up to date for current Android** (targets Android 15), so the "built for an older version of Android"
  warning does not appear.

**What cannot be switched off from inside an app:** Chrome asks before keeping any `.apk` file, Android asks
once whether Chrome may install apps, and Play Protect may ask to scan an app it has not seen on many phones
yet. The download page tells students what to tap for each of these. Only apps from the Play Store skip them.

## What a student taps (also on the download page)

1. **Download for Android**. Chrome: *"File might be harmful"* → **Download anyway**.
2. **Open** (or Chrome ⋮ → Downloads → XeoGo.apk).
3. If asked: **Settings** → switch on **Allow from this source** → back.
4. **Install**. If Play Protect does not know the app: **More details → Install anyway** (or **Scan app →
   Install**).

## Asking Google to review the app (do this once per new version)

Google's Play Protect team takes reviews of apps that it warns about. It is free and needs no Play Store
account. It may help, it is not guaranteed, and Google says it does not reply.

1. Go to **virustotal.com** → *Choose file* → `XeoGo.apk` (the exact file on the website). Wait for the result:
   it should show no detections. Copy the file's **SHA-256** (shown there; it is also in READ-ME-FIRST.txt of
   the delivery).
2. Open **support.google.com/googleplay/android-developer/contact/protectappeals** and fill in:
   - *Email*: yours.
   - *Developer name*: XeoGo.
   - *Package name*: `edu.campus.printapp`
   - *SHA256 hash*: the one from step 1.
   - *Additional information* (paste):
     > XeoGo is the printing app of our college's Xerox center. Students download it from our own website
     > (https://gitprint.netlify.app/app) to send files to the center's printers and pay with UPI. It asks only
     > for internet and network-state access and lists UPI apps for payment; no SMS, contacts, location,
     > camera, storage or accessibility, and no third-party SDKs that need permissions. It is signed with our own release key (certificate SHA-256
     > 01:09:A8:E5:82:29:94:20:A7:1C:FE:C2:93:5C:80:E0:C9:69:B7:73:94:77:E0:F9:D5:4C:76:E2:A2:B0:D4:62).
     > Students see a Play Protect warning when installing it. Please review it.
3. The same for the staff app: `XeoGo-Staff.apk`, package `edu.campus.printapp.staff`.

Each new version is a new file with a new SHA-256: submit it again.

## One time only: uninstall the old app

The development key that signed 3.3.0 is no longer on the build computer, and Android installs an update only
when it is signed with the same key as the app already on the phone. So a phone with XeoGo 3.3.0 says *"App not
installed"*: **uninstall the old XeoGo, then install 3.4.0.** Orders are on the server and are not lost; only
the list "Your orders on this phone" starts empty. From 3.4.0 on, every new version installs over the last one.

## Keep the key safe

`D:\Downloads\XeoGo\signing-key-KEEP-SAFE\` holds the key and its password. **Copy that folder to a pen drive
and to a private cloud drive today.** Without it no new version can ever install over 3.4.0 (every student
would have to uninstall again), and you could not register the app with Google later (see below). Never put
it on GitHub or send it to anyone.

## Coming in 2027: Android developer verification

Google has announced that, from 2027 everywhere (it started on 30 September 2026 in Brazil, Indonesia,
Singapore and Thailand), certified Android phones install apps only from **verified developers**; an app from
an unverified developer then needs an extra "advanced" way to install that most students will not manage.
When that reaches India, register once in Google's **Android Developer Console** (no Play Store needed): verify
your identity, and register the package names `edu.campus.printapp` and `edu.campus.printapp.staff` with
XeoGo's key. Choose *full distribution*: *limited distribution* (for students and hobbyists) allows only 20
phones. Then installing from the QR code works as it does today. Google's page:
https://developer.android.com/developer-verification/guides

## XeoGo Pay Verifier (the shop's own phone)

The Verifier reads the bank's SMS and payment notifications: that is its whole job. In India Play Protect
**blocks** apps with those permissions when they come from Chrome, WhatsApp or a file manager. So do not put it
on the website or a QR code. On the shop's phone:

- **Already installed?** Nothing to do. Version 1.1.0 is unchanged in this delivery and keeps working.
- **New phone:** install it from a computer with a USB cable (the block does not apply): on the phone
  *Settings → About phone* → tap *Build number* 7 times → *Developer options → USB debugging* on; on the
  computer `adb install XeoGoPay-Verifier-1.1.0.apk`; then switch USB debugging off again.

## Making the files (for whoever builds)

```
cd android
gradlew assembleStudentDist assembleStaffDist
```

gives `app/build/outputs/apk/student/dist/app-student-dist.apk` (→ `web/download/XeoGo.apk`) and
`.../staff/dist/app-staff-dist.apk` (→ `web/download/XeoGo-Staff.apk`). It needs `android/keystore.properties`
(where the key is, and its password; not in GitHub). Without it the build says so and signs with the
development key: fine for trying things out, never for handing out. Raise `versionCode` in
`android/app/build.gradle.kts` for every new version.
