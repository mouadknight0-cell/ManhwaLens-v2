import java.io.DataOutputStream
import java.util.Locale
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Release builds can inject version info (CI sets VERSION_NAME / VERSION_CODE from the git tag).
val appVersionName: String = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.1.31-dev"
val appVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

// Stable release signing: CI decodes the keystore from the MANHWALENS_KEYSTORE_FILE secret.
// Release assembly fails closed without this key: never publish ephemeral debug signing as a release.
val releaseKeystorePath: String? = System.getenv("MANHWALENS_KEYSTORE_FILE")
val hasReleaseKeystore: Boolean = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
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
        buildConfigField("boolean", "CROP_FIRST", providers.gradleProperty("cropFirst").orElse("true").get())
        buildConfigField("boolean", "STRICT_PLACEMENT", providers.gradleProperty("strictPlacement").orElse("true").get())
        // Permanent application id — must never change between releases.
        applicationId = "com.manhwalens.app"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
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

// CI debug artifacts remain available for diagnostics; production releases require continuity.
tasks.configureEach {
    if (name == "preReleaseBuild") doFirst {
        check(hasReleaseKeystore || System.getenv("ALLOW_UNSIGNED_RELEASE") == "true") { "Stable signing key missing. Refusing an incompatible release; restore the original signing key in GitHub secrets." }
    }
}

// JSON remains the editable interchange format. Compile it on the build machine so a
// cold reader session doesn't allocate an entire JSONObject tree (or run key regexes).
val phraseAssets = layout.buildDirectory.dir("generated/reader-pack")
val packPhrases by tasks.registering {
    val files = fileTree("src/main/assets/phrasebook") { include("*.json") }
    inputs.files(files)
    val packed = phraseAssets.map { it.file("phrase-index.bin") }
    outputs.file(packed)
    doLast {
        val rows = files.sortedBy { it.name }.flatMap { file ->
            val root = JsonSlurper().parse(file) as Map<*, *>
            (root["entries"] as List<*>).map { it as Map<*, *> }
        }
        val output = packed.get().asFile
        output.parentFile.mkdirs()
        DataOutputStream(output.outputStream().buffered()).use { stream ->
            val strings=linkedMapOf<String,Int>()
            fun id(value:String)=strings.getOrPut(value){strings.size}
            fun rank(row:Map<*,*>):Int {val g=row["genre"] as List<*>;return if("regression-test" in g)3 else if(g.any { it!="general" })2 else 1}
            val records=rows.sortedWith(compareByDescending<Map<*,*>>{rank(it)}.thenByDescending{(it["priority"] as Number).toInt()}).map { row ->
                val en=row["en"] as String;val key=en.uppercase(Locale.ROOT).replace('’','\'').replace(Regex("\\b([A-Z]{1,3})-(?=\\1[A-Z'])"), "").replace(Regex("[^A-Z0-9']+")," ").trim()
                val words=key.split(' ');listOf(id(en),id(row["ar"] as String),id(row["type"] as String),id((row["genre"] as List<*>).joinToString(",")),(row["priority"] as Number).toInt(),id(key),words.size)+words.map(::id)
            }
            val pool=strings.keys.joinToString("\u0000").toByteArray(Charsets.UTF_8);stream.writeInt(pool.size);stream.write(pool);stream.writeInt(records.size);records.forEach { r->r.forEach(stream::writeInt) }
        }
    }
}
android.sourceSets["main"].assets.srcDir(phraseAssets)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") || it.name=="preBuild" }.configureEach { dependsOn(packPhrases) }
