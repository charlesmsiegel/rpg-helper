plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

repositories {
    google()
    mavenCentral()
}

/**
 * The Android `libjllama.so`, lifted out of the llama.cpp jar into `jniLibs`.
 *
 * AGP does not package native libraries that live at arbitrary paths inside a jar — they
 * have to be under `lib/<abi>/` in the APK, which is what `jniLibs` produces. Without this
 * the classes were in the dex, the loader found no library, and the app declined every
 * model with "no runtime can load it": correct behaviour, and useless.
 *
 * The loader's own Android path is `System.loadLibrary` against the APK's `lib` directory,
 * so a file placed here is exactly what it goes looking for — no extraction to a temp
 * directory, and no writable-executable-file question to answer.
 *
 * arm64 only. Every Android device that can hold a multi-gigabyte model is arm64; shipping
 * an x86 build for emulators would add five megabytes to every real user's download.
 */
val extractLlamaJni by tasks.registering(Copy::class) {
    val jni = configurations.named("releaseRuntimeClasspath").map { classpath ->
        classpath.files.single { it.name.startsWith("llama-") && it.extension == "jar" }
    }
    from(jni.map { zipTree(it) }) {
        include("de/kherud/llama/Linux-Android/aarch64/libjllama.so")
        eachFile { path = "arm64-v8a/libjllama.so" }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/jniLibs"))
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

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // The llama.cpp artifact carries native libraries for Linux, macOS, Windows and
        // Android, as ordinary jar resources. Only the Android ones can ever load on a
        // phone, and the rest are tens of megabytes of a desktop build that would be
        // downloaded by every user and executed by none.
        resources.excludes += "de/kherud/llama/Linux/**"
        resources.excludes += "de/kherud/llama/Linux-Android/**"
        resources.excludes += "de/kherud/llama/Mac/**"
        resources.excludes += "de/kherud/llama/Windows/**"
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

// The extraction has to happen before the native libraries are merged, on every variant.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(extractLlamaJni) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }
    .configureEach { dependsOn(extractLlamaJni) }

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
    implementation(project(":routing"))
    implementation(project(":model"))
    implementation(project(":capabilities"))
    // The one place a question becomes cards. Without this the Ask surface had no way to
    // ask anything: it took a feed as a parameter and nothing ever supplied one, so the
    // APK assembled, installed, and answered nothing -- and every test passed, because
    // every test exercises a layer below the one that was missing.
    implementation(project(":session"))
    // llama.cpp. The artifact carries native libraries for several platforms; only the
    // Android ones are packaged -- see the exclusions below, which are what keep a desktop
    // build's worth of `.so` files out of a phone's APK.
    implementation(project(":runtime-llamacpp"))

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
