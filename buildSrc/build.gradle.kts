buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    alias(libs.plugins.kotlin)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.swaggerParser)
    implementation(libs.bundles.commonmark)
    implementation(libs.commonsText)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
