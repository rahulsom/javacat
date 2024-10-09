plugins {
    alias(libs.plugins.javaLibrary)
}

dependencies {
    api(libs.junit)
    api(libs.assertj)

    implementation("javax.json:javax.json-api:1.1.4")
    implementation("org.glassfish:javax.json:1.1.2")

    implementation(libs.bundles.jackson)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
