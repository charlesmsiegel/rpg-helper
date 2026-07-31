import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // The one module allowed to know about all the others: it is the wiring, and every
    // seam it crosses is a seam the app will have to cross too.
    implementation(project(":session"))
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
