plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
}

dependencies {
    // Empty by design. This module is the *runtime* surface for the temporal-kt compiler plugin:
    // the @TemporalModule / WorkflowDecl / ActivityDecl annotations, the @TemporalGenerated marker,
    // and the no-op DSL stubs (taskQueue/workflow/activity). It must stay tiny so end users only
    // pay for it when they enable the compiler plugin.
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
