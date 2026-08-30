plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val qwenAsrNativeDir = providers.gradleProperty("qwenAsrNativeDir")
    .orElse(rootProject.layout.projectDirectory.dir(".qwen-asr-native/llama-b9637").asFile.absolutePath)
val generatedQwenAsrJniDir = layout.buildDirectory.dir("generated/qwenAsrJniLibs")
val qwenAsrNativeFiles = listOf(
    "llama-mtmd-cli",
    "libllama-common.so",
    "libmtmd.so",
    "libllama.so",
    "libggml.so",
    "libggml-base.so",
    "libggml-cpu-android_armv8.0_1.so",
    "libggml-cpu-android_armv8.2_1.so",
    "libggml-cpu-android_armv8.2_2.so",
    "libggml-cpu-android_armv8.6_1.so",
    "libggml-cpu-android_armv9.0_1.so",
    "libggml-cpu-android_armv9.2_1.so",
    "libggml-cpu-android_armv9.2_2.so"
)

val prepareQwenAsrNative by tasks.registering(Sync::class) {
    val sourceDir = qwenAsrNativeDir.map(::file)
    val available = sourceDir.get().takeIf { it.isDirectory }?.let { dir ->
        qwenAsrNativeFiles.all { dir.resolve(it).isFile }
    } == true
    if (available) {
        from(sourceDir) {
            include(qwenAsrNativeFiles)
            rename { fileName: String ->
                when {
                    fileName == "llama-mtmd-cli" -> "libqwen_asr_cli.so"
                    fileName == "libllama.so" -> "libqwnlm.so"
                    fileName == "libggml.so" -> "libqasr.so"
                    fileName == "libmtmd.so" -> "libqmtm.so"
                    fileName.startsWith("libggml-") -> fileName.replaceFirst("libggml-", "libqasr-")
                    else -> fileName
                }
            }
        }
    }
    into(generatedQwenAsrJniDir.map { it.dir("arm64-v8a") })
    doFirst {
        if (!available) {
            logger.warn(
                "Qwen3-ASR native files not found in ${sourceDir.get()}; " +
                    "Gemma profile still works. Run ./scripts/prepare-qwen-asr-native.sh for Qwen."
            )
        }
    }
    doLast {
        val abiDir = generatedQwenAsrJniDir.get().asFile.resolve("arm64-v8a")
        if (abiDir.isDirectory && abiDir.listFiles()?.any { it.extension == "so" } == true) {
            exec {
                commandLine(
                    "python3",
                    rootProject.layout.projectDirectory.file("scripts/rewrite-qwen-asr-sonames.py").asFile.absolutePath,
                    abiDir.absolutePath
                )
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

android {
    namespace = "com.g150446.voiceharness"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.g150446.voiceharness"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets.getByName("main").jniLibs.srcDir(generatedQwenAsrJniDir)
}

tasks.named("preBuild").configure {
    dependsOn(prepareQwenAsrNative)
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    implementation("ai.liquid.leap:leap-sdk:0.10.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.vuzix:ultralite-sdk-android:1.9")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
