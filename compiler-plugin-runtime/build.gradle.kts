plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
}

dependencies {
    api(project(":core"))
}

mavenPublishing {
    coordinates(artifactId = "compiler-plugin-runtime")

    pom {
        name.set("Temporal Kotlin Compiler Plugin Runtime")
        description.set(
            "Annotations and DSL stubs for the Temporal Kotlin compiler plugin. " +
                "Pulled in automatically when the compiler plugin is enabled.",
        )
    }
}
