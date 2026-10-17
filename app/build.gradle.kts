plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jabs.lumen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jabs.lumen"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The release workflow passes these from the git tag (v1.2.3 -> name 1.2.3, code 10203).
        // A local build keeps the defaults below.
        versionCode = (project.findProperty("lumenVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("lumenVersionName") as String?) ?: "0.1.0"
        // Where the in-app updater looks for releases. Override with -PlumenRepo=owner/name.
        buildConfigField("String", "GITHUB_REPO", "\"${project.findProperty("lumenRepo") ?: "jabsdev/lumen"}\"")

        ndk {
            // arm64-v8a only for what ships; an emulator build can ask for x86_64 with
            // -PlumenAbis=arm64-v8a,x86_64.
            abiFilters += (project.findProperty("lumenAbis") as String?)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Signed with the debug key so a release build can be side-loaded for testing.
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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
        // BuildConfig carries the version and the GitHub repo the updater talks to.
        buildConfig = true
    }
    androidResources {
        // The TFLite model is memory-mapped at load time; keep it uncompressed in the APK.
        noCompress += listOf("tflite")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Offline person/subject segmentation for the "Pessoa" mask (bundled model, no network).
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    // On-device tests for the bundled models. These never ship in the app.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
