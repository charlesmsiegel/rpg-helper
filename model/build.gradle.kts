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
    // The contract types (EmbedderContract, ChunkRef-shaped ids) live in :pack, which is
    // what lets PackValidator take the supported set as a parameter rather than reading a
    // global -- and lets :pack stay honest about owning no weights.
    implementation(project(":pack"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
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
