pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-downloads the JDK 25 toolchain requested by jvmToolchain(25)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pv-addon-livetranslate"
