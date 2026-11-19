import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Release builds can inject version info (CI sets VERSION_NAME / VERSION_CODE from the git tag).
val appVersionName: String = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0-dev"
val appVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

// Stable release signing: CI decodes the keystore from the MANHWALENS_KEYSTORE_FILE secret.
// Release assembly fails closed without this key: never publish ephemeral debug signing as a release.
val releaseKeystorePath: String? = System.getenv("MANHWALENS_KEYSTORE_FILE")
val hasReleaseKeystore: Boolean = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.manhwalens.app"
    compileSdk = 35

    defaultConfig {
        // Permanent application id — must never change between releases.
        applicationId = "com.manhwalens.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(releaseKeystorePath!!)
                storeType = "PKCS12"
                storePassword = System.getenv("MANHWALENS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MANHWALENS_KEY_ALIAS") ?: "manhwalens"
                keyPassword = System.getenv("MANHWALENS_KEY_PASSWORD") ?: System.getenv("MANHWALENS_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
        release {
            // Minification intentionally off for v1.0: maximum device compatibility.
            // Enable R8 with care once device-testing is in place.
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // On-device OCR (script-aware) + on-device translation. No API keys required.
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.korean)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.text.recognition.chinese)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

// CI debug artifacts remain available for diagnostics; production releases require continuity.
tasks.configureEach {
    if (name == "preReleaseBuild") doFirst {
        check(hasReleaseKeystore || System.getenv("ALLOW_UNSIGNED_RELEASE") == "true") { "Stable signing key missing. Refusing an incompatible release; restore the original signing key in GitHub secrets." }
    }
}
