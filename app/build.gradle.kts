plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.firebase.appdistribution)
}

// .env 에서 키-값 읽기. 파일이 없거나 키가 없으면 빈 문자열.
fun envValue(key: String): String = rootProject.file(".env").takeIf { it.exists() }
    ?.readLines()
    ?.firstOrNull { it.trim().startsWith("$key=") }
    ?.substringAfter("=")
    ?.trim()
    ?: ""

val geminiKey: String = envValue("GEMINI_KEY")
val releaseStorePassword: String = envValue("KEYSTORE_PASSWORD")
val releaseKeyAlias: String = envValue("KEY_ALIAS")
val releaseKeyPassword: String = envValue("KEY_PASSWORD")

android {
    namespace = "com.example.photorecipe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.photorecipe"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GEMINI_KEY", "\"$geminiKey\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.jks")
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            firebaseAppDistribution {
                appId = "1:722817793573:android:dff652c05a940bc88409cc"
                artifactType = "APK"
                groups = "internal"
            }
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
        buildConfig = true
    }

    androidResources {
        // tflite 모델은 압축하지 않음 (mmap 로딩 가능하게)
        noCompress += "tflite"
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
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
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.tensorflow.lite)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.mediapipe.tasks.vision)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
