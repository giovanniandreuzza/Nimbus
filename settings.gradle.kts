pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
//        mavenLocal()
    }
}

rootProject.name = "nimbus"
include(":nimbus")
include(":sample_android")
