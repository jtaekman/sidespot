import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.sidespot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sidespot.app"
        minSdk = 31
        targetSdk = 31
        versionCode = 11
        versionName = "0.4.0"

        // Only target arm64 (Sidephone SP-01 is aarch64)
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("release-keystore.properties")
            if (propsFile.exists()) {
                val ks = Properties()
                propsFile.inputStream().use { ks.load(it) }
                storeFile = file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
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
            signingConfig = signingConfigs.getByName("release")
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
    }

    lint {
        // Not distributed via Google Play; suppress Play Store targetSdk requirement
        disable += "ExpiredTargetSdkVersion"
    }

    // The native .so is pre-built by cargo-ndk into jniLibs/
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity + ViewModel
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Serialization (for JSON payloads from JNI)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Image loading (for album art in later phases)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Palette (dynamic theming from album art)
    implementation("androidx.palette:palette:1.0.0")

    // Debug tools
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Task to build the native Rust library before the Android build
tasks.register<Exec>("buildNativeRelease") {
    workingDir = file("${rootProject.projectDir}/native")
    environment("ANDROID_NDK_HOME", "/opt/homebrew/share/android-commandlinetools/ndk/27.0.12077973")
    environment("PATH", "${System.getenv("HOME")}/.cargo/bin:${System.getenv("PATH")}")
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-o", "../app/src/main/jniLibs", "build", "--release")
}

// Hook native build into the Android build pipeline
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    dependsOn("buildNativeRelease")
}
