plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ro.yt.downloader"
    compileSdk = 34
    defaultConfig {
        applicationId = "ro.yt.downloader"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }
    signingConfigs {
        // Reuses the standard debug keystore so `assembleRelease` produces an installable APK
        // for GitHub / sideloading. Use your own keystore for Play Store releases.
        create("releaseSideload") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("releaseSideload")
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
dependencies {
    val youtubedlAndroid = "0.17.3"
    val media3 = "1.2.1"
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.5.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.mediarouter:mediarouter:1.6.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")
}
