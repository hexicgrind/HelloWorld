import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ai.sotto.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.sotto.assistant"
        // Design Doc 1: "Android SDK: Minimum API level 30. Target API level 35."
        minSdk = 30
        targetSdk = 35
        versionCode = 9
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            // Phones only. The x86/x86_64 MediaPipe and TFLite libraries exist for
            // emulators and cost ~29 MB of APK that no real device will ever load.
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            // Also emit a universal APK, so there is always one file that installs
            // anywhere without the user having to know their phone's architecture.
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("sideload") {
            storeFile = file("sotto-sideload.jks")
            storePassword = "sottosideload"
            keyAlias = "sotto"
            keyPassword = "sottosideload"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // Shipped as a self-signed sideload build: this is a personal proof of
            // concept, not a Play Store release.
            signingConfig = signingConfigs.getByName("sideload")

            // Shrinking stays on: without it the APK doubles to 99 MB, because
            // MediaPipe ships an enormous generated-protobuf surface that R8 removes.
            // Obfuscation is off, though — it buys nothing here and it turns the stack
            // traces in the in-app diagnostics report into single letters, which is
            // precisely when they matter most. See -dontobfuscate in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            // The non-optimising default. See -dontoptimize in proguard-rules.pro.
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    androidResources {
        // The TFLite / MediaPipe models must not be compressed or they cannot be
        // memory-mapped at runtime.
        noCompress += listOf("tflite", "lite", "task")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

/**
 * Fails the build if any packaged native library is not 16 KB page aligned.
 *
 * This exists because of a real shipped bug: MediaPipe 0.10.14 and
 * tensorflow-lite 2.16.1 emit 4 KB-aligned `.so` files, and Android 15+ devices
 * running with 16 KB memory pages refuse to `dlopen` them. The app installed and
 * launched fine, then failed to load either model at runtime — a failure no unit
 * test or lint check could see, because Robolectric never loads native code.
 *
 * A dependency bump can silently reintroduce it, so the check is mechanical.
 */
val verifyNativeLibAlignment by tasks.registering {
    group = "verification"
    description = "Checks that every packaged .so is 16 KB page aligned."

    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    outputs.upToDateWhen { false }

    doLast {
        val apks = apkDir.get().asFile.listFiles().orEmpty().filter { it.name.endsWith(".apk") }
        if (apks.isEmpty()) {
            logger.lifecycle("No release APKs to check.")
            return@doLast
        }

        val required = 16 * 1024L
        val problems = mutableListOf<String>()

        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    // 32-bit ABIs always run on 4 KB pages; the requirement is 64-bit only.
                    .filter { !it.name.contains("armeabi") && !it.name.contains("/x86/") }
                    .forEach { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val align = maxLoadAlignment(bytes)
                        if (align != null && align < required) {
                            problems += "${apk.name}: ${entry.name} is ${align / 1024} KB aligned"
                        }
                    }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Native libraries are not 16 KB page aligned.")
                    appendLine("These will fail to load on Android 15+ devices with 16 KB pages:")
                    problems.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("Upgrade the offending dependency to a 16 KB-aligned release.")
                }
            )
        }
        logger.lifecycle("Native library alignment: all 64-bit .so files are 16 KB aligned.")
    }
}

/**
 * Returns the smallest p_align across an ELF64 file's LOAD segments, or null when the
 * bytes are not a 64-bit ELF. Parsed by hand so the check needs no external tooling.
 */
fun maxLoadAlignment(bytes: ByteArray): Long? {
    if (bytes.size < 64) return null
    if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
        bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
    ) return null
    if (bytes[4].toInt() != 2) return null   // ELFCLASS64 only

    val buffer = ByteBuffer.wrap(bytes).order(
        if (bytes[5].toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    )
    val phoff = buffer.getLong(0x20)
    val phentsize = buffer.getShort(0x36).toInt() and 0xFFFF
    val phnum = buffer.getShort(0x38).toInt() and 0xFFFF

    var smallest: Long? = null
    for (i in 0 until phnum) {
        val base = (phoff + i.toLong() * phentsize).toInt()
        if (base < 0 || base + phentsize > bytes.size) break
        if (buffer.getInt(base) != 1) continue   // PT_LOAD
        val align = buffer.getLong(base + 0x30)
        if (align > 0 && (smallest == null || align < smallest)) smallest = align
    }
    return smallest
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyNativeLibAlignment)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.litert)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    // Compose under Robolectric. Two user-visible faults in a row lived in layout and
    // button-enablement logic, which no view-model test can see.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
