import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories {
    mavenCentral()
    // `:pack` exposes androidx.sqlite, which is published to Google's Maven.
    google()
}

dependencies {
    // The one module allowed to know about all the others: it is the wiring, and every
    // seam it crosses is a seam the app will have to cross too.
    implementation(project(":session"))
    // The inference runtime. `:cli` is the surface that can afford twelve megabytes of
    // native libraries, and it is where a model can be exercised against a real corpus
    // before anything is asked of a phone.
    implementation(project(":runtime-llamacpp"))
    implementation(project(":pack"))
    implementation(project(":model"))
    implementation(project(":state"))
    implementation(project(":builder"))
    implementation(project(":retrieval"))
    implementation(project(":routing"))
    implementation(project(":capabilities"))
    implementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "dev.rpghelper.cli.MainKt"
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
