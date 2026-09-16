import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Picovoice AccessKey is a per-account secret. It is read from
// local.properties (gitignored) and baked into BuildConfig for the spike ONLY.
// The shipping app must not embed it in the APK; see the plan's M5 notes.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val picovoiceAccessKey: String = localProps.getProperty("PICOVOICE_ACCESS_KEY", "")

android {
    namespace = "com.houseoftech.voicelock"
    // Mirrors the estate's ColorJoy baseline and the plan's Play requirement.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.houseoftech.voicelock"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"$picovoiceAccessKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    // Wake engine: Vosk (Apache-2.0), run as a restricted-grammar phrase
    // spotter with the Apache-2.0 vosk-model-small-en-in-0.4 (Indian-English).
    // The AAR bundles the native libs; jna is its runtime dependency.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    // Dormant fallback behind the KeywordEngine seam until Vosk passes M0, then
    // removed. Offline inference; custom phrases trained on console.picovoice.ai.
    implementation("ai.picovoice:porcupine-android:4.0.2")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
}
