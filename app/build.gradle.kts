import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Resolve the Finnhub key from (in order): -PFINNHUB_API_KEY, env FINNHUB_API_KEY,
// or local.properties. Falls back to "" so the project always builds.
val finnhubApiKey: String = (project.findProperty("FINNHUB_API_KEY") as String?)
    ?: System.getenv("FINNHUB_API_KEY")
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }.getProperty("FINNHUB_API_KEY")
    }
    ?: ""

// Derive a monotonic versionCode from the release version (e.g. 1.2.3 -> 10203) so installs
// actually upgrade. CI passes -Pversion.name from the tag; local builds fall back to 1.
fun versionCodeFrom(name: String?): Int {
    val parts = name?.split(".")?.mapNotNull { it.toIntOrNull() } ?: return 1
    if (parts.size < 3) return 1
    return parts[0] * 10000 + parts[1] * 100 + parts[2]
}

android {
    namespace = "com.stocktracker.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stocktracker.app"
        minSdk = 26
        targetSdk = 35
        // CI passes -Pversion.name=X.Y.Z (from the tag); falls back for local builds.
        val vName = (project.findProperty("version.name") as String?) ?: "0.1.0"
        versionName = vName
        versionCode = (project.findProperty("version.code") as String?)?.toIntOrNull()
            ?: versionCodeFrom(project.findProperty("version.name") as String?)

        buildConfigField("String", "FINNHUB_API_KEY", "\"$finnhubApiKey\"")
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // signingConfig is wired by CI (KEYSTORE_BASE64 secret) in release.yml
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose (app UI)
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle / navigation
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Glance (home-screen widgets)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Background refresh
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Persistence (no Room — DataStore + JSON keeps the build annotation-processor-free)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Networking + JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
