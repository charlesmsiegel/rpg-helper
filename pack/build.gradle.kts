import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    // PackForge is a fixture, not a test: :state's install tests need to forge packs too,
    // and a fixture shared through a real source set beats duplicating the schema.
    `java-test-fixtures`
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(libs.sqlite.jdbc)

    // SQLite **bundled**, not the platform's. `00-pack-schema.md` names the reason: the
    // pack format's whole lexical half is an FTS5 virtual table, and Android's own SQLite
    // is not built with FTS5 across every version and vendor image -- a book that opens on
    // one phone and not another is not a format. `api` because `Db` implementations are
    // constructed by callers on both platforms.
    //
    // One coordinate serves both: Gradle resolves the JVM variant here and the Android
    // variant in `:app`, so the binding below is the *same code* on the device and on the
    // desktop rather than two implementations that agree until they do not.
    api("androidx.sqlite:sqlite-bundled:2.7.0")
    testFixturesImplementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test"))
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
