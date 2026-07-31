import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    // The activation gate lives in :pack; installing is what invokes it.
    implementation(project(":pack"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":pack")))
}

java {
    // Kept level with the Kotlin target below; Gradle refuses to build if they diverge.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        // 17, not the JDK we happen to build on: this module is bound for Android, and
        // bytecode it cannot load is a problem best discovered now rather than at the
        // point someone adds the :app module.
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
