import java.util.Properties

plugins {
    // AGP >= 8.9.1 + compileSdk 36: required by PebbleKit2's androidx deps.
    // Kotlin >= 2.3: PebbleKit2 1.2.0 ships Kotlin 2.3 metadata.
    id("com.android.application") version "8.9.2"
    id("org.jetbrains.kotlin.android") version "2.3.20"
}

android {
    namespace = "org.cryomonitor.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.cryomonitor.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "0.6.3"
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
    implementation("io.rebble.pebblekit2:client:1.2.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
