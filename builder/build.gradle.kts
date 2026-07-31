import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
    // `:pack` exposes androidx.sqlite, which is published to Google's Maven.
    google()
}

dependencies {
    // The builder writes the canonical DDL from :pack and validates its own output through
    // the same activation gate the app uses. Emitting a pack the app would refuse is the
    // one bug a builder must not be able to ship.
    implementation(project(":pack"))
    implementation(project(":model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test"))
    testImplementation(project(":retrieval"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
