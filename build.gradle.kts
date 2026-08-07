// Root build for the nodeterm Android companion client.
// Modules: :core (pure Kotlin/JVM protocol layer, Android-free, unit-tested with a JDK)
//          :app  (Android application: Compose UI, OkHttp transport, FCM entry, persistence)

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
