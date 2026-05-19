plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// .env 의 GEMINI_KEY 를 BuildConfig 로 주입. 파일이 없거나 키가 없으면 빈 문자열.
val geminiKey: String = rootProject.file(".env").takeIf { it.exists() }
    ?.readLines()
    ?.firstOrNull { it.trim().startsWith("GEMINI_KEY=") }
    ?.substringAfter("=")
    ?.trim()
    ?: ""

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

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
