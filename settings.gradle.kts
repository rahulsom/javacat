plugins {
    id("com.gradle.develocity").version("3.18.1")
}

develocity {
    buildScan {
        termsOfUseUrl.set("https://gradle.com/terms-of-service")
        termsOfUseAgree.set("yes")
    }
}

rootProject.name = "pulpogato"

private fun createProject(variant: String,  ghesVersion:String) {
    val projectName = "${rootProject.name}-$variant-$ghesVersion"
    if (!file(projectName).exists()) {
        file(projectName).mkdirs()
    }
    include(projectName)
    project(":${projectName}").buildFileName = "../${variant}.gradle.kts"
}

//createProject("graphql", "ghec")
//createProject("graphql", "fpt")
createProject("graphql", "ghes-3.13")
createProject("graphql", "ghes-3.12")
createProject("graphql", "ghes-3.11")
createProject("graphql", "ghes-3.10")

//createProject("rest", "ghec")
//createProject("rest", "fpt")
createProject("rest", "ghes-3.13")
createProject("rest", "ghes-3.12")
createProject("rest", "ghes-3.11")
createProject("rest", "ghes-3.10")

include("${rootProject.name}-common")
include("${rootProject.name}-rest-tests")
