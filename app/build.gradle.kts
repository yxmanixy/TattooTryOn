plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "at.tattoo.tryon"
    compileSdk = 35

    defaultConfig {
        applicationId = "at.tattoo.tryon"
        minSdk = 29
        targetSdk = 34
        versionCode = 8
        versionName = "2.6"

        ndk {
            // Nur echte Smartphone-Prozessoren -> deutlich kleinere APK
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Fester Signaturschlüssel: Jede neue Version lässt sich über die alte installieren.
    // (Ohne ihn erzeugt GitHub bei jedem Build einen neuen Schlüssel -> Update schlägt fehl.)
    signingConfigs {
        create("shared") {
            storeFile = file("tattoo.keystore")
            storePassword = "tattoo-tryon"
            keyAlias = "tattoo"
            keyPassword = "tattoo-tryon"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Das KI-Modell muss unkomprimiert in der APK liegen
    androidResources {
        noCompress += "tflite"
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.material:material:1.12.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Bild-Tracking für die AR-Ansicht (Optical Flow, Homographie)
    implementation("org.opencv:opencv:4.10.0")

    // KI-Körpererkennung (Segmentierung: Haut / Kleidung / Hintergrund), läuft offline
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
