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

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}
