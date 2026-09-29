plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * CampusPay Verifier: runs on the Xerox center's phone (the one that gets the
 * UPI payments). It passes each "money received" notification of the UPI
 * business app, and each bank SMS about money received, to the Campus Print
 * server, which confirms the student's order by itself. Not for students.
 */
android {
    namespace = "edu.campus.verifier"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.campus.verifier"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // The server it talks to, unless changed on the phone (-PapiBase=http://... to build for another one)
        val apiBase = (project.findProperty("apiBase") as String?) ?: "https://campus-print-backend.onrender.com"
        buildConfigField("String", "API_BASE", "\"$apiBase\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
