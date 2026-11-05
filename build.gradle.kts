plugins {
    // AGP 9 has built-in Kotlin support; the separate kotlin-android plugin is gone.
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
