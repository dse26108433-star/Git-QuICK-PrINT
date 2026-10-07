plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "edu.campus.printapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.campus.printapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "3.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the print service runs: the live one, unless built with -PapiBase=http://... (see AppConfig.kt)
        val apiBase = (project.findProperty("apiBase") as String?) ?: "https://campus-print-backend.onrender.com"
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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // "dist": the build that is handed out (gradlew assembleStudentDist assembleStaffDist). Like release it
        // talks https only and cannot be debugged; unlike release its code is left as it is (exactly what the
        // tests ran), and it is signed with this computer's debug key, the key of every copy handed out so
        // far, so it installs over them and keeps their orders. For the Play Store, sign "release" with a
        // key of your own instead.
        create("dist") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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

    // Razorpay payment screen (UPI, cards, wallets)
    implementation("com.razorpay:checkout:1.6.41")

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
