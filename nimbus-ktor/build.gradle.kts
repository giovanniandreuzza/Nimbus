import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileNotFoundException
import java.util.Properties

plugins {
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
}

val localProperties = loadProperties()

group = "io.github.giovanniandreuzza"
version = localProperties.getVersion()

kotlin {
    explicitApi()

    jvm()
    android {
        namespace = "io.github.giovanniandreuzza.nimbus.ktor"
        compileSdk = 36
        minSdk = 21
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":nimbus"))
            api(libs.ktor)
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.giovanniandreuzza",
        artifactId = "nimbus-ktor",
        version = localProperties.getVersion()
    )

    pom {
        name.set("Nimbus Ktor adapter")
        description.set("Ktor-based NimbusDownloadPort implementation for the Nimbus KMP download library.")
        inceptionYear.set("2025")
        url.set("https://github.com/giovanniandreuzza/nimbus")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("giovanniandreuzza")
                name.set("Giovanni Andreuzza")
                email.set("giovi.andre@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/giovanniandreuzza/nimbus")
        }
    }

    publishToMavenCentral()

    signAllPublications()
}

fun loadProperties() = rootProject.file("versions.properties").let {
    if (!it.exists()) {
        throw FileNotFoundException("File ${it.absolutePath} not found")
    }

    Properties().also { properties ->
        properties.load(it.inputStream())
    }
}

fun Properties.getVersion() = getProperty("VERSION") ?: "1.0.0"
