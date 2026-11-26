import java.util.Properties

plugins {
    // Built-in Kotlin is opted out of in gradle.properties.
    id("com.android.application") version "9.5.3"
    id("org.jetbrains.kotlin.android") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

android {
    namespace = "org.cryomonitor.companion"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "org.cryomonitor.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 61
        versionName = "0.7.0"
    }

    // Release signing: keystore + credentials live OUTSIDE version control
    // (android/keystore.properties, android/keystore/*.jks — git-ignored).
    // A stable, non-debug signature is what keeps Play Protect from
    // flagging sideloaded builds as harmful (debug-signed + SEND_SMS is
    // its classic heuristic trip) and enables in-place updates.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // The gateway role (SMS/call at server command) ships only in the
    // sideload flavor: SEND_SMS/CALL_PHONE conflict with Play Store policy.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("sideload") { dimension = "distribution" }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // android.util.Log no-ops in JVM tests; real org.json comes from
        // the explicit test dependency below.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // PebbleKit2: primary watch transport (Core app >= 1.0.7.7).
    // The Classic intent transport in PebbleTransport.kt stays as fallback.
    implementation("io.rebble.pebblekit2:client:1.3.2")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    // Jetpack Compose + Material 3 (change companion-compose-ui): the BOM
    // pins ui/material3/activity-compose to one tested set.
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    testImplementation("org.json:json:20260814")
}
