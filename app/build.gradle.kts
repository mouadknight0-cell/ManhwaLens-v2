import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Release builds can inject version info (CI sets VERSION_NAME / VERSION_CODE from the git tag).
val appVersionName: String = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.1.30-dev"
val appVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

// A candidate build (release-candidate APK for device testing) has a distinguishable
// versionName suffix, e.g. "1.1.42-rc1". Nothing else about the release differs.
val releaseCandidate: Boolean = appVersionName.endsWith("-rc1") || appVersionName.contains("-rc")
// A release candidate must NOT burn the per-commit versionCode of the eventual release:
// the tag workflow derives its own code from the tagged commit's timestamp. The candidate
// therefore ships `last_published + 1` (installable over the installed official app, and
// strictly monotonic), never the big per-commit number, and never the last release itself.
val candidateVersionCodeHold: Int = System.getenv("CANDIDATE_VERSION_CODE_HOLD")?.toIntOrNull() ?: 0

// A candidate's versionCode is `hold + 1`, where the hold is the highest of every floor
// (newest official release, installed build, explicit minimum). It must never be silently
// reduced: `coerceAtMost(appVersionCode)` used to clamp it down to the commit's per-commit
// code, which can be *lower* than the installed build. Android then rejects the APK as a
// downgrade, and the only way forward is an uninstall -- which would destroy the user's
// history, samples and benchmark records. Fail closed and say why instead.
val effectiveVersionCode: Int = if (releaseCandidate && candidateVersionCodeHold > 0) {
    val candidate = candidateVersionCodeHold + 1
    if (candidate > appVersionCode) {
        throw GradleException(
            "Candidate versionCode $candidate is above this commit's per-commit code " +
                "$appVersionCode. A candidate must stay below it so it does not consume the " +
                "number the tagged release will use. Refusing to clamp down to $appVersionCode: " +
                "that would ship an APK the installed build can reject as a downgrade.",
        )
    }
    candidate
} else appVersionCode

// Build identity: the full commit SHA of the source being built.
//
// A value that is present but malformed is a hard failure, not a best-effort
// string. Recording "main" or a 7-char abbreviation as an engine revision would
// let a run look reproducible when it cannot be resolved back to a commit.
// Absent means "unknown", and the app then reports the run as non-reproducible.
val gitRevision: String = System.getenv("GIT_REVISION")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
    require(Regex("^[0-9a-f]{40}$").matches(raw)) {
        "GIT_REVISION must be a full 40-character lowercase hex commit SHA, got '$raw'. " +
            "Use `git rev-parse HEAD`, not a short SHA or a branch name."
    }
    raw
} ?: "unknown"

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = effectiveVersionCode
        versionName = appVersionName
        // Build identity only. CI supplies the full 40-hex commit so an evaluation run
        // can be tied to the code that produced it. This value is never read by any
        // translation, OCR or rendering code.
        buildConfigField(
            "String",
            "GIT_REVISION",
            "\"${gitRevision}\"",
        )
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

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Installable builds must be signed with the pinned production identity: a release with no
// key is refused here, unless a workflow that only needs an *unsigned* intermediate sets
// ALLOW_UNSIGNED_RELEASE (release.yml, which signs offline in sign-release.yml). Nothing
// signed with the ephemeral debug key can ever be produced as a release.
tasks.configureEach {
    if (name == "preReleaseBuild") doFirst {
        check(hasReleaseKeystore || System.getenv("ALLOW_UNSIGNED_RELEASE") == "true") { "Stable signing key missing. Refusing an incompatible release; restore the original signing key in GitHub secrets." }
        // A release candidate is always signed in-job: the unsigned escape hatch must not leak in.
        if (System.getenv("CANDIDATE_RELEASE_BUILD") == "true") {
            check(hasReleaseKeystore) { "Release candidate without the production keystore: refusing to build an unsigned or debug-signed APK." }
        }
    }
}
