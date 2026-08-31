import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 35

    defaultConfig {
        applicationId = "top.kncloud.com"
        minSdk = 24
        targetSdk = 35
        versionCode = 704
        versionName = "1.10.52"
        multiDexEnabled = true

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (abiFilterList != null && abiFilterList.isNotEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = project.rootProject.file("../keystore/kncloud.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "kncloud123456"
                keyAlias = "kncloud"
                keyPassword = "kncloud123456"
            }
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

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

    applicationVariants.all {
        val variant = this
        val versionCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4, "universal" to 0)

        variant.outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
                val abi = output.getFilter("ABI") ?: "universal"
                output.outputFileName = "KNcloud_${variant.versionName}_${abi}.apk"
                if (versionCodes.containsKey(abi)) {
                    output.versionCodeOverride =
                        (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                }
            }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

// Ensure workspace android_keystore.jks is always synchronized from the repository permanent release keystore
val permanentKeystore = project.rootProject.file("../keystore/kncloud.keystore")
val workspaceKeystore = project.rootProject.file("../android_keystore.jks")

fun syncPermanentKeystore() {
    try {
        if (permanentKeystore.exists()) {
            val srcKs = KeyStore.getInstance("PKCS12")
            val srcPass = "kncloud123456".toCharArray()
            permanentKeystore.inputStream().use { inputStream ->
                srcKs.load(inputStream, srcPass)
            }
            val key = srcKs.getKey("kncloud", srcPass)
            val chain = srcKs.getCertificateChain("kncloud")

            val dstKs = KeyStore.getInstance("PKCS12")
            dstKs.load(null, null)
            dstKs.setKeyEntry("androiddebugkey", key, "android".toCharArray(), chain)
            workspaceKeystore.outputStream().use { outputStream ->
                dstKs.store(outputStream, "android".toCharArray())
            }
        }
    } catch (e: Exception) {
        logger.warn("Keystore sync warning: ${e.message}")
    }
}

syncPermanentKeystore()

tasks.configureEach {
    if (name.contains("Signing", ignoreCase = true) || name.contains("Package", ignoreCase = true) || name.contains("Assemble", ignoreCase = true)) {
        doFirst {
            syncPermanentKeystore()
        }
    }
}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)

    // UI Libraries
    implementation(libs.material)
    implementation(libs.toasty)
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
