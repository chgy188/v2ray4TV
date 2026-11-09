plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.v2ray.ang.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.v2ray.ang.tv"
        minSdk = 21
        // Android 5/6 TV boxes are the target of this module. Keeping targetSdk below the
        // POST_NOTIFICATIONS (33) and foreground-service-type (34) boundaries avoids a
        // runtime permission prompt that a D-pad-only TV has no way to complete.
        targetSdk = 28
        versionCode = 2
        versionName = "1.0.1"

        // Emit one APK per ABI. libgojni.so is large, so a combined v7a+v8a "compatible"
        // build would make every TV box download a native library it cannot use. Android 5
        // boxes are short on storage and CPU, so the per-ABI APK is the primary artifact;
        // a universal APK is kept as a fallback for x86 boxes and unknown ABIs.
        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }
    }

    applicationVariants.all {
        val variant = this
        val abiCodes = mapOf(
            "armeabi-v7a" to 1,
            "arm64-v8a" to 2,
            "x86" to 3,
            "x86_64" to 4,
            "universal" to 0
        )
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
                val abi = output.getFilter("ABI") ?: "universal"
                output.outputFileName = "v2rayNG-TV_${variant.versionName}_${abi}.apk"
                abiCodes[abi]?.let { code ->
                    output.versionCodeOverride = 100 * variant.versionCode + code
                }
            }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // libv2ray.aar carries libgojni.so for each ABI. It is a generated input staged from
    // V2rayNG/app/libs by CI. An absent directory yields an empty file tree rather than a
    // configuration failure, so this module still compiles before the AAR is available.
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    androidResources {
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "az",
            "bn",
            "bqi-rIR"
        )
    }

    packaging {
        jniLibs {
            // Android 5/6 load native libraries from nativeLibraryDir. Without extraction
            // the .so stays inside the APK and System.loadLibrary fails at runtime.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    lint {
        // This APK is sideloaded onto Android 5/6 TV boxes and is not distributed through
        // Play, so the Play target-API policy does not apply. Keeping targetSdk at 28 avoids
        // the POST_NOTIFICATIONS (33) and foreground-service-type (34) flows that a D-pad-only
        // TV cannot complete. Removal condition: publishing this module on Play, which needs
        // targetSdk >= 33 plus an accessible permission UI.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(libs.core) // com.google.zxing:core - QR encoding only, no camera
    implementation(libs.gson) // pure Java, no Android minSdk constraint
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
