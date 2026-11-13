import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The key the handed-out apps are signed with (XeoGo, XeoGo Staff). Its file and password are NOT in this
// repository: android/keystore.properties says where they are (storeFile, storePassword, keyAlias, keyPassword).
// Android installs an update over an app only when both are signed with the same key, so keep that key safe.
val releaseKey = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val hasReleaseKey = releaseKey.getProperty("storeFile")?.let { file(it).isFile } == true

// -PtestServer=https://...: a separate "XeoGo TEST" app for a test server (for example a laptop behind a free
// Cloudflare quick tunnel). It installs next to the real XeoGo and never talks to the live server.
val testServer = (project.findProperty("testServer") as String?)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

android {
    namespace = "edu.campus.printapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.campus.printapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "3.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the print service runs: the live one, unless built with -PapiBase=http://... (see AppConfig.kt)
        val apiBase = testServer ?: (project.findProperty("apiBase") as String?) ?: "https://campus-print-backend.onrender.com"
        manifestPlaceholders["appLabel"] = "@string/app_name"
        buildConfigField("String", "API_BASE", "\"$apiBase\"")
        buildConfigField("boolean", "STAFF", "false")
    }

    // Two apps from the same code, installed side by side:
    //   student  "XeoGo"        edu.campus.printapp        students pay and print (no login)
    //   staff    "XeoGo Staff"  edu.campus.printapp.staff  college staff sign in with a staff ID and print for free
    flavorDimensions += "who"
    productFlavors {
        create("student") {
            dimension = "who"
        }
        create("staff") {
            dimension = "who"
            applicationIdSuffix = ".staff"
            versionNameSuffix = "-staff"
            buildConfigField("boolean", "STAFF", "true")
        }
    }

    signingConfigs {
        if (hasReleaseKey) create("xeogo") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // "dist": the build that is handed out (gradlew assembleStudentDist assembleStaffDist), and the one
        // uploaded to Google Play (gradlew bundleStudentDist bundleStaffDist). Like release it talks https only
        // and cannot be debugged; unlike release its code is left as it is (exactly what the tests ran).
        // Signed with XeoGo's own key (keystore.properties). Up to 3.3.0 the apps were signed with a computer's
        // debug key; that key is lost, and a debug-signed app is one Play Protect warns about.
        create("dist") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("xeogo") else signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            if (testServer != null) {
                applicationIdSuffix = ".test"
                versionNameSuffix = "-test"
                manifestPlaceholders["appLabel"] = "XeoGo TEST"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            // the shared rule cases (spec/cases) and, when asked, the real backend (-Dcampusprint.api=http://localhost:8080)
            it.systemProperty("campusprint.spec", rootProject.file("../spec/cases").absolutePath)
            System.getProperty("campusprint.api")?.let { api -> it.systemProperty("campusprint.api", api) }
            it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // No card payment library (Razorpay) in the apps: they pay with UPI, so they need no extra permissions.
    // A shop that takes cards is paid on the website.

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")   // older ones break on Android 16 (InputManager)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// A handed-out build without XeoGo's key would be signed with this computer's debug key: say so loudly.
gradle.taskGraph.whenReady {
    if (!hasReleaseKey && allTasks.any { it.name.contains("Dist") && (it.name.startsWith("assemble") || it.name.startsWith("bundle")) }) {
        logger.warn("WARNING: android/keystore.properties (XeoGo's signing key) was not found. This build is signed with " +
            "the debug key: it will not install over the apps handed out, and Play Protect warns about it.")
    }
}
