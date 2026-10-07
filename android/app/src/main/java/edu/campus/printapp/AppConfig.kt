package edu.campus.printapp

/**
 * Where the print service runs. Set when the app is built:
 *
 *   gradlew assembleDebug                                    the live service (https://campus-print-backend.onrender.com)
 *   gradlew assembleDebug -PapiBase=http://192.168.1.10:8080 your laptop on the same Wi-Fi (debug builds allow http://)
 *   gradlew assembleDebug -PapiBase=http://10.0.2.2:8080     the laptop, from the Android emulator
 *
 * Each build makes two apps: app-student-debug.apk (XeoGo) and app-staff-debug.apk (XeoGo Staff).
 *
 * "localhost" does NOT work from a phone.
 */
object AppConfig {
    val API_BASE: String = BuildConfig.API_BASE

    /** The staff app ("XeoGo Staff"): college staff sign in with a staff ID and print for free. */
    val STAFF: Boolean = BuildConfig.STAFF
}
