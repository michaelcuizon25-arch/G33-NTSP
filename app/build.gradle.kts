import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    kotlin("kapt")
}

android {
    namespace = "com.example.note2snap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.note2snap"
        minSdk = 24
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "1.0"
        // Opt in for evaluation: -PENABLE_OPENCV_ENHANCEMENT=true
        buildConfigField("boolean", "ENABLE_OPENCV_ENHANCEMENT",
            providers.gradleProperty("ENABLE_OPENCV_ENHANCEMENT").orElse("false").get().toBooleanStrict().toString())
        val singleScanMode = providers.gradleProperty("SINGLE_SCAN_RECOGNITION_MODE").orElse("CRNN_ONLY").get()
        require(singleScanMode in setOf("CRNN_ONLY", "PADDLE_PRIMARY_WITH_CRNN_FALLBACK")) {
            "Unsupported SINGLE_SCAN_RECOGNITION_MODE: $singleScanMode"
        }
        buildConfigField("String", "SINGLE_SCAN_RECOGNITION_MODE", "\"$singleScanMode\"")
        val evaluationExports = providers.gradleProperty("ENABLE_OCR_EVALUATION_EXPORTS").orElse("false").get().toBooleanStrict()
        require(!evaluationExports || (singleScanMode == "CRNN_ONLY" && providers.gradleProperty("ENABLE_PADDLE_OCR").orElse("false").get().toBooleanStrict())) {
            "Evaluation exports require CRNN_ONLY and ENABLE_PADDLE_OCR=true"
        }
        buildConfigField("boolean", "ENABLE_OCR_EVALUATION_EXPORTS", evaluationExports.toString())
        // Optional shadow comparison in CRNN_ONLY mode; independent from primary selection.
        buildConfigField("boolean", "ENABLE_PADDLE_OCR",
            providers.gradleProperty("ENABLE_PADDLE_OCR").orElse("false").get().toBooleanStrict().toString())
        // Research-only image/tensor/JSON exports; lightweight timings remain independent.
        buildConfigField("boolean", "ENABLE_CRNN_DIAGNOSTIC_EXPORTS",
            providers.gradleProperty("ENABLE_CRNN_DIAGNOSTIC_EXPORTS").orElse("false").get().toBooleanStrict().toString())
        // Independent from OpenCV and the existing CRNN preprocessing-mode selector.
        buildConfigField("boolean", "ENABLE_OFFLINE_TEXT_CORRECTION",
            providers.gradleProperty("ENABLE_OFFLINE_TEXT_CORRECTION").orElse("false").get().toBooleanStrict().toString())
    }

    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    buildFeatures {
        mlModelBinding = true
        buildConfig = true
    }
}

// Fail before packaging if a Windows checkout/editor changed the byte-verified vocabulary.
val verifyPaddleDictionary by tasks.registering {
    val dictionary = layout.projectDirectory.file("src/main/assets/ocr/paddle/ppocrv5_en_dict.txt")
    inputs.file(dictionary)
    doLast {
        val expected = "e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6"
        val actual = MessageDigest.getInstance("SHA-256").digest(dictionary.asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == expected) {
            "Paddle dictionary bytes changed: expected $expected, got $actual. Restore official UTF-8/LF bytes using .gitattributes; do not replace the pinned hash."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyPaddleDictionary) }

// Explicitly forces KAPT and Kotlin tasks to use JVM 17
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    // Bundled CPU inference only; no runtime model downloads.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // Official OpenCV Android AAR; native processing is entirely on-device.
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.airbnb.android:lottie:6.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX
    val cameraVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Google ML Kit Text Recognition (OCR)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // TensorFlow Lite - CRNN handwriting recognition
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.16.1")

    // Coil & Lottie
    implementation("io.coil-kt:coil:2.6.0")
    implementation("com.airbnb.android:lottie:6.4.0")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Coroutines & Lifecycle KTX
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
