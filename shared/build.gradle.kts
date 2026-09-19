// Plain-JVM module -- no Android, no Glance, no WorkManager.
//
// WGT-7: the Wear tile/complication must reuse the phone widgets' honesty-rule pure functions
// (tickerDisplay/portfolioDisplay/widgetAgeLabel/WIDGET_STALE_AFTER_MS) rather than reimplementing
// them, so a stale price or a partial portfolio total reads the same way on the watch as it does on
// the phone. Those functions and the two data classes they take (Quote, PortfolioSummary) had zero
// Android dependency to begin with -- they were already pulled out of the Glance composables
// specifically to run under a plain JVM test -- so moving them here costs nothing on the phone side:
// `:app` gets a `typealias` back to the original names (see Models.kt, PortfolioWidgetState.kt) so
// every existing import/call site in `:app` is unchanged, and `:wear` depends on this module
// directly without pulling in Compose/Glance/WorkManager/OkHttp/the rest of the phone app.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    testImplementation("junit:junit:4.13.2")
}
