plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

// Optional per-ABI builds (e.g. for F-Droid): ./gradlew assembleRelease -PabiFilter=arm64-v8a
// Each ABI gets a distinct versionCode (base * 10 + n) so they can be published side by side.
val baseVersionCode = 9
val abiVersionOffsets = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)
val abiFilter: String? = providers.gradleProperty("abiFilter").orNull?.also {
    require(it in abiVersionOffsets) { "Unknown abiFilter '$it'. Use one of ${abiVersionOffsets.keys}" }
}

android {
    namespace = "com.fearmikey.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fearmikey.camera"
        minSdk = 31
        targetSdk = 35
        versionCode = abiFilter?.let { baseVersionCode * 10 + abiVersionOffsets.getValue(it) } ?: baseVersionCode
        versionName = "1.1.0"

        abiFilter?.let { ndk { abiFilters += it } }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // The OpenCV AAR ships libc++_shared, so our library must use the same STL.
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Both the OpenCV AAR and our CMake build provide libc++_shared.so.
            pickFirsts += "lib/*/libc++_shared.so"
        }
    }

    // F-Droid: don't embed the Google-signed dependency metadata blob in the APK/AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)

    // OpenCV (Apache-2.0, Maven Central) - native code consumed via prefab
    implementation(libs.opencv)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
