import codegen.Main
import de.undercouch.gradle.tasks.download.Download

plugins {
    alias(libs.plugins.javaLibrary)
    alias(libs.plugins.waenaPublished)
}

dependencies {
    compileOnly(libs.annotations)
    compileOnly(libs.springWeb)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    api(libs.retrofit)
    api(project(":${rootProject.name}-common"))

    testImplementation(libs.junit)
    testImplementation(libs.assertj)
}

fun getUrl(projectVariant: String): String {
    val path = if (projectVariant == "fpt") "api.github.com" else projectVariant
    return "https://github.com/github/rest-api-description/raw/main/descriptions-next/$path/$path.json"
}

val projectVariant = project.name.replace("${rootProject.name}-rest-", "")

val downloadSchema = tasks.register<Download>("downloadSchema") {
    src(getUrl(projectVariant))
    dest(file("${project.layout.buildDirectory.get()}/generated/resources/main/schema.json"))
    onlyIfModified(true)
    tempAndMove(true)
    useETag(true)

    inputs.property("url", getUrl(projectVariant))
    outputs.file(file("${project.layout.buildDirectory.get()}/generated/resources/main/schema.json"))
}

val generateJava = tasks.register("generateJava") {
    dependsOn(downloadSchema)
    inputs.file("${project.layout.buildDirectory.get()}/generated/resources/main/schema.json")
    inputs.dir("${rootDir}/buildSrc/src")
    inputs.file("${rootDir}/buildSrc/build.gradle.kts")

    doLast {
        file("${project.layout.buildDirectory.get()}/generated/sources/rest-codegen").mkdirs()

        Main().process(
                file("${project.layout.buildDirectory.get()}/generated/resources/main/schema.json"),
                file("${project.layout.buildDirectory.get()}/generated/sources/rest-codegen"),
                "io.github.pulpogato"
        )
    }
    outputs.dir(file("${project.layout.buildDirectory.get()}/generated/sources/rest-codegen"))
}

tasks.compileJava {
    dependsOn(generateJava)
}
tasks.named("sourcesJar") {
    dependsOn(generateJava)
}
tasks.named("javadocJar") {
    dependsOn(generateJava)
}
tasks.processResources {
    dependsOn(downloadSchema)
}

sourceSets {
    named("main") {
        java.srcDir("${project.layout.buildDirectory.get()}/generated/sources/rest-codegen")
        resources.srcDir("${project.layout.buildDirectory.get()}/generated/resources/main")
    }
}

tasks.withType<Javadoc>().configureEach {
    isFailOnError = false
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:none", "-quiet")
        addStringOption("encoding", "UTF-8")
        addStringOption("charSet", "UTF-8")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}