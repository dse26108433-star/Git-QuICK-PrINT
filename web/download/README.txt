The Android apps are downloaded from here (the page is app.html, the QR code on the poster opens /app).

Put these two files in this folder before publishing the website:
    XeoGo.apk          the student app  (android: gradlew assembleStudentDist -> app-student-dist.apk)
    XeoGo-Staff.apk    the staff app    (android: gradlew assembleStaffDist   -> app-staff-dist.apk)

Keep these names: the download page and the QR code use them, so a new version only replaces the files.
They are not in GitHub (too big for every version); the delivery folder has them in place.
