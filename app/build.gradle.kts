plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "dev.rpghelper.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.rpghelper.app"
        // 26 is where the app can rely on a modern ICU and on adoptable storage behaving.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and the manifest to inflate anything.
            isIncludeAndroidResources = true
        }
    }
}

// Unit tests run on **one** variant. `compose.ui.test.manifest` merges the activity the
// Compose test rule launches into, and it is a `debugImplementation` by design — so the
// release unit-test variant compiles the same tests against a manifest with no such
// activity and every one of them fails on "unable to resolve activity". Running them twice
// would not check anything twice; it would check the debug variant and then check whether
// the release manifest happens to contain a test scaffold.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false }
}

configurations.all {
    // `sqlite-jdbc` ships native libraries for three desktop platforms and cannot load on
    // Android at all. Left in, it adds five megabytes of Mac and Windows binaries to the
    // APK for a class the device never touches -- `BundledDb` is what runs here. Excluding
    // it makes that a build-time fact rather than a convention, and it has to be excluded
    // across every configuration because more than one module below depends on it.
    exclude(group = "org.xerial", module = "sqlite-jdbc")
}

dependencies {
    // Every module below is Android-free Kotlin/JVM. That they compose into an app
    // without any of them knowing about Android is the point of the layering, not a
    // coincidence -- and `:app` depending on all of them is what proves it.
    // `sqlite-jdbc` ships native libraries for three desktop platforms and cannot load on
    // Android at all. Left in, it added five megabytes of Mac and Windows binaries to the
    // APK -- for a class the device never touches, since `BundledDb` is what runs here.
    // Excluding it also makes that a build-time fact rather than a convention.
    implementation(project(":pack"))
    implementation(project(":state"))
    implementation(project(":retrieval"))
    implementation(project(":routing"))
    implementation(project(":model"))
    implementation(project(":capabilities"))
    // The one place a question becomes cards. Without this the Ask surface had no way to
    // ask anything: it took a feed as a parameter and nothing ever supplied one, so the
    // APK assembled, installed, and answered nothing -- and every test passed, because
    // every test exercises a layer below the one that was missing.
    implementation(project(":session"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // `:app` was the only module with no tests, and the only one where every defect this
    // project found was a *rendering* defect: the feed behind a full-screen input, a roll
    // control that could not be tapped, a quotation rule that stopped partway down at
    // accessibility text sizes. Robolectric runs the framework on the JVM so those are
    // reachable without a device.
    testImplementation(kotlin("test"))
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
