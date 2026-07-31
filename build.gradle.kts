// Declared here with `apply false` so every module resolves the same plugin versions from
// one place. Without this a module asking for the Kotlin plugin by version hits "already
// on the classpath with an unknown version" -- Gradle cannot check compatibility once
// another module has put it there.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
}
