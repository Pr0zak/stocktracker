// Root build file. Plugin versions declared here, applied in :app.
// Versions cribbed from the known-good lakemap Android build.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    // Plain-JVM module (:shared) so the wear module can reuse the phone's honesty-rule pure
    // functions without pulling in Android/Glance/WorkManager — see shared/build.gradle.kts.
    id("org.jetbrains.kotlin.jvm") version "2.0.20" apply false
}
