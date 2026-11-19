import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.minios.tgwsproxy"
    compileSdk = 34

    defaultConfig {
        // Own ID (fork of crim50n's dev.minios.tgwsproxy): installs alongside the original.
        // Never change it after the first public release, or updates stop installing over it.
        applicationId = "io.github.megaflow19.tgflow19"
        minSdk = 26
        targetSdk = 34
        // Version code scheme: major*10000 + minor*100 + patch (1.0.0 -> 10000); must match update.json.
        versionCode = 10001
        versionName = "1.0.1"
    }

    val hasPropertiesFile = File("app/keystore/signing.properties").exists()
    if (hasPropertiesFile) {
        val props = Properties()
        props.load(FileInputStream(file("keystore/signing.properties")))
        val alias = props["keystore.alias"] as String
        signingConfigs {
            create("release") {
                storeFile = file(props["keystore"] as String)
                storePassword = props["keystore.password"] as String
                keyAlias = alias
                keyPassword = props["keystore.password"] as String
            }
        }
        println("[Signature] -> Build will be signed with: $alias")
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    // Build stamp (Moscow time) to tell test builds apart in About and diagnostics.
    // Update checks compare versionCode, not versionName, so the suffix is harmless there.
    val buildStamp = LocalDateTime.now(ZoneId.of("Europe/Moscow"))
        .format(DateTimeFormatter.ofPattern("MMdd-HHmm"))

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev.$buildStamp" // e.g. 1.0.0-dev.1119-0130
            manifestPlaceholders["appLabel"] = "TG Flow19 Dev"
        }
        release {
            // Local signed builds get -local.<stamp>; GitHub Actions releases keep the plain
            // versionName so it matches the tag (Obtainium compares them).
            if (System.getenv("GITHUB_ACTIONS") != "true") {
                versionNameSuffix = "-local.$buildStamp" // e.g. 1.0.0-local.1119-0235
            }
            manifestPlaceholders["appLabel"] = "@string/app_name"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Compose detectors bundled with this AGP cannot read Kotlin 2.1 metadata.
        disable += setOf("CoroutineCreationDuringComposition", "StateFlowValueCalledInComposition")
    }
}

dependencies {
    // Compose BOM (use older version compatible with SDK 34)
    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // DataStore for config persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Kotlin serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // OkHttp for CF domain fetching
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
