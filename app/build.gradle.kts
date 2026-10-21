plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "uz.dublaj"
    compileSdk = 35

    defaultConfig {
        applicationId = "uz.dublaj"
        minSdk = 26
        targetSdk = 35
        // CI: -PbuildNumber=<run_number>; yangilanish tekshiruvi shu raqamga tayanadi
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 4
        versionCode = buildNumber
        versionName = "0.4.$buildNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Prebuilt whisper AAR is arm64-v8a only (free tier)
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Doimiy kalit: har bir build bir xil imzo bilan chiqadi -> APK ustidan yangilanadi,
        // ilovani o'chirmasdan o'rnatsa bo'ladi (modellar saqlanadi).
        create("dublaj") {
            storeFile = file("dublaj-sign.keystore")
            storePassword = "android"
            keyAlias = "dublaj"
            keyPassword = "android"
        }
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (!ks.isNullOrBlank() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 JNI/ONNX bilan muammo chiqarmasligi uchun o'chiq (ishonchlilik > hajm)
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // O'z keystore bo'lmasa doimiy 'dublaj' kalit bilan imzolanadi -> APK o'rnatiladi
            signingConfig = if (System.getenv("KEYSTORE_FILE").isNullOrBlank())
                signingConfigs.getByName("dublaj")
            else
                signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dublaj")
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
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ONNX Runtime — NLLB + VITS
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // Whisper.cpp prebuilt AAR (no NDK)
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")

    // Model download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.jakewharton.timber:timber:5.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
