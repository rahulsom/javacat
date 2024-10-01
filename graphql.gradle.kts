import com.netflix.graphql.dgs.codegen.gradle.GenerateJavaTask
import de.undercouch.gradle.tasks.download.Download

plugins {
    alias(libs.plugins.javaLibrary)
    alias(libs.plugins.dgs)
    alias(libs.plugins.waenaPublished)
    alias(libs.plugins.download)
}

dependencies {
    api(libs.jacksonCore)
}

fun getUrl(projectVariant: String): String {
    return if (projectVariant.matches(Regex("ghes-\\d+\\.\\d+"))) {
        "https://docs.github.com/public/$projectVariant/schema.docs-enterprise.graphql"
    } else {
        "https://docs.github.com/public/$projectVariant/schema.docs.graphql"
    }
}

val projectVariant = project.name.replace("${rootProject.name}-graphql-", "")

val downloadSchema = tasks.register<Download>("downloadSchema") {
    src(getUrl(projectVariant))
    dest(file("${project.layout.buildDirectory.get()}/resources/main/schema.graphqls"))
    onlyIfModified(true)
    tempAndMove(true)
    useETag(true)

    inputs.property("url", getUrl(projectVariant))
    outputs.file(file("${project.layout.buildDirectory.get()}/resources/main/schema.graphqls"))
}

tasks.named<GenerateJavaTask>("generateJava") {
    dependsOn(downloadSchema)

    schemaPaths = mutableListOf("${project.layout.buildDirectory.get()}/resources/main/schema.graphqls")
    packageName = "io.github.pulpogato.graphql"
    generateClientv2 = true
    includeQueries = mutableListOf("")
    includeMutations = mutableListOf("")

    typeMapping = mutableMapOf(
        "Base64String" to "java.lang.String",
        "BigInt" to "java.math.BigInteger",
        "Date" to "java.time.LocalDate",
        "DateTime" to "java.time.LocalDateTime",
        "GitObjectID" to "java.lang.String",
        "GitRefname" to "java.lang.String",
        "GitSSHRemote" to "java.lang.String",
        "GitTimestamp" to "java.time.OffsetDateTime",
        "HTML" to "java.lang.String",
        "PreciseDateTime" to "java.time.OffsetDateTime",
        "URI" to "java.net.URI",
        "X509Certificate" to "java.lang.String",
    )

    doLast {
        delete(fileTree("${project.layout.buildDirectory.get()}/generated/sources/dgs-codegen") {
            include("**/DgsConstants.java")
        })
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.processResources {
    dependsOn(downloadSchema)
}

tasks.withType<Javadoc>().configureEach {
    isFailOnError = false
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        addStringOption("encoding", "UTF-8")
        addStringOption("charSet", "UTF-8")
    }
}