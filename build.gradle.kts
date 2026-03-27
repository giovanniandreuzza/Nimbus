plugins {
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false

    // Sample Android
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}