import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
    google()
}

/**
 * llama.cpp, behind the `ModelRuntime` seam.
 *
 * A module of its own because the JNI artifact is twelve megabytes of native libraries for
 * several platforms, and `:model` is imported by everything including the APK. Whoever wants
 * inference depends on this; nobody else pays for it.
 */
dependencies {
    api(project(":model"))
    implementation(libs.llama)
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
