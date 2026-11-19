plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ru.metwisom.micro"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "ru.metwisom.micro"
        minSdk = 26
        targetSdk = 36
        // Bumped together with the desktop client. HEAD is wire-incompatible with the released
        // 1.0.0 — a phone and a desktop across that boundary cannot connect at all — so the major
        // version moves and versionCode must never be reused across releases.
        versionCode = 2
        versionName = "2.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true       // R8: strip unused androidx/material/BC code
            isShrinkResources = true     // drop resources no kept code references
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
}
