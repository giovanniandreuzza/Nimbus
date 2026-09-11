import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kotlinSerialization)
}

val libraryVersion = rootProject.file("version.txt").readText().trim()

group = "io.github.giovanniandreuzza"
version = libraryVersion

kotlin {
    explicitApi()

    jvm()
    android {
        namespace = "io.github.giovanniandreuzza.nimbus"
        compileSdk = 36
        minSdk = 21
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "nimbus"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.protobuf)
            api(libs.kotlinx.io)
            api(libs.explicitarchitecture)
            implementation(libs.hash.sha2)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.protobuf)
        }
    }
}

mavenPublishing {
    // Define coordinates for the published artifact
    coordinates(
        groupId = "io.github.giovanniandreuzza",
        artifactId = "nimbus",
        version = libraryVersion
    )

    // Configure POM metadata for the published artifact
    pom {
        name.set("KMP Library for downloading files in a concurrent way")
        description.set("This library can be used by Android and iOS targets for the shared functionality of downloading files in a concurrent way.")
        inceptionYear.set("2025")
        url.set("https://github.com/giovanniandreuzza/nimbus")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        // Specify developer information
        developers {
            developer {
                id.set("giovanniandreuzza")
                name.set("Giovanni Andreuzza")
                email.set("giovi.andre@gmail.com")
            }
        }

        // Specify SCM information
        scm {
            url.set("https://github.com/giovanniandreuzza/nimbus")
        }
    }

    // Configure publishing to Maven Central
    publishToMavenCentral()

    // Enable GPG signing for all publications
    signAllPublications()
}
