plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.giovanniandreuzza.sample_android"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.giovanniandreuzza.sample_android"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8

        // Enable desugaring
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)

    // KotlinX Serialization
    implementation(libs.kotlinx.serialization.json)

    // Koin
    implementation(libs.koin.android)

    // Timber
    implementation(libs.timber)

    // OkHttp3
    implementation(libs.okhttp3)
    implementation(libs.okhttp3.logging.interceptor)

    // Ktor
    implementation(libs.ktor)
    implementation(libs.ktor.okhttp)
    implementation(libs.ktor.cio)
    implementation(libs.ktor.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization)

    // Retrofit2
    implementation(libs.retrofit2)
    implementation(libs.retrofit2.serialization)

    // Nimbus
//    implementation(libs.nimbus)
    implementation(project(":nimbus"))

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}