package edu.campus.printapp

/**
 * The ONE setting you change in the app.
 *
 * Testing with the backend on your laptop: use the laptop's Wi-Fi address,
 * for example "http://192.168.1.10:8080" (phone and laptop on the same Wi-Fi;
 * find the address with `ipconfig` on Windows). "localhost" does NOT work
 * from a phone. http:// only works in debug builds.
 *
 * Real use: "https://your-backend-address"
 */
object AppConfig {
    const val API_BASE = "http://192.168.1.10:8080"
}
