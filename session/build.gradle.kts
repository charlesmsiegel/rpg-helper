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
    // The wiring layer: the one place that knows how a query becomes cards, so the
    // command-line tool and the app cannot drift into two different answers to that
    // question. Exposed as `api` because a caller of `ask` handles the cards, the
    // citations, and the library rows it returns.
    //
    // Not yet usable on a device: `:pack` binds SQLite through `sqlite-jdbc`, which ships
    // native desktop libraries. The `Db` interface is the seam that swap goes through and
    // the Android side of it is not written -- naming that here rather than letting a
    // dependency edge imply otherwise.
    api(project(":pack"))
    api(project(":state"))
    api(project(":retrieval"))
    api(project(":routing"))
    api(project(":model"))
    implementation(project(":capabilities"))
    testImplementation(kotlin("test"))
    testImplementation(project(":builder"))
    testImplementation(libs.sqlite.jdbc)
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
