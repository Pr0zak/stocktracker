// WGT-7: Wear OS companion — a tile + complication that MIRROR the phone widgets. This module
// fetches nothing on its own; see WearRepository for why, and shared/build.gradle.kts for why
// Quote/PortfolioSummary/tickerDisplay/portfolioDisplay/widgetAgeLabel live in :shared rather than
// being reimplemented here.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stocktracker.wear"
    compileSdk = 35

    defaultConfig {
        // Same applicationId as the phone app -- the Wear Data Layer only pairs a watch app with a
        // phone app when both share one. They install on distinct devices (or distinct Wear OS
        // "modules" of one app), so there is no install conflict; this pattern is already proven out
        // in this workspace by zonik/mobile/wear.
        applicationId = "com.stocktracker.app"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Quote/PortfolioSummary/WearSnapshot/wearContent/tickerDisplay/portfolioDisplay/widgetAgeLabel
    // -- the whole point of WGT-7's design: reuse, not reimplement.
    implementation(project(":shared"))

    implementation("androidx.core:core-ktx:1.13.1")

    // Decoding the WearSnapshot JSON the phone pushes.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // TileService.onTileRequest returns a Guava ListenableFuture; CoroutineScope.future{} bridges it.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")

    // Tiles (ProtoLayout-backed — androidx.wear.tiles.TileService plus androidx.wear.protolayout
    // builders, which tiles-material/tiles pull in transitively as of 1.4.x).
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.tiles:tiles-material:1.4.1")

    // Complications
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

    // Wear Data Layer — receives the WearSnapshot pushed by the phone (WGT-7's whole point: the
    // watch never fetches on its own).
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    testImplementation("junit:junit:4.13.2")
}
