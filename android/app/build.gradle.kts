plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val keyProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Custom build variants. Flags come from -P properties (local builds) or
// from android/variant.properties (written by CI; space-safe for branded
// labels like "Mezchaju Gold"):
//   lite=true          skip bundled web UI assets (gateway + CLI agents only)
//   onlyArm64=true     package arm64-v8a native libs only
//   unsigned=true      unsigned release APK (for self-signing)
//   brandName=...      override the app label (branded build)
//   brandAccent=...    override the accent hex, e.g. 22D3EE (branded build)
val variantProps = Properties().apply {
    val f = rootProject.file("variant.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val prop = { name: String, default: String ->
    (variantProps.getProperty(name) ?: (project.findProperty(name) as String?))
        ?.takeIf { it.isNotBlank() } ?: default
}
val isLite = prop("lite", "false") == "true"
val onlyArm64 = prop("onlyArm64", "false") == "true"
val unsignedBuild = prop("unsigned", "false") == "true"
val brandName = prop("brandName", "Mezchaju")
val brandAccent = prop("brandAccent", "FF7849")

android {
    namespace = "com.codex.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codex.mobile"
        minSdk = 24
        if (onlyArm64) {
            ndk {
                // arm64-only builds drop x86 / x86_64 / armeabi-v7a libs.
                abiFilters += listOf("arm64-v8a")
            }
        }
        // targetSdk 28 allows executing binaries from app data directory.
        // Android 10+ (targetSdk 29+) enforces W^X which blocks this via SELinux.
        // Termux (F-Droid) uses the same approach.
        targetSdk = 28
        versionCode = 9
        versionName = "1.6.0"

        // Variant build config consumed by the app (dashboard, widget, UI).
        buildConfigField("boolean", "LITE", if (isLite) "true" else "false")
        buildConfigField("String", "BRAND_NAME", "\"$brandName\"")
        buildConfigField("String", "BRAND_ACCENT", "\"$brandAccent\"")
        resValue("string", "app_name", brandName)
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (keyProps.isNotEmpty()) {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (unsignedBuild) {
                null
            } else if (keyProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // targetSdk 28 is an intentional workaround (W^X / executing binaries
        // from app data); not eligible for Play review, so don't fail on it.
        disable += "ExpiredTargetSdkVersion"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Don't compress bootstrap zip or server bundle in assets
    androidResources {
        noCompress += listOf("zip", "tar.gz")
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
    // Termux terminal emulator + view — real PTY terminal, native rendering
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
}
