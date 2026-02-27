plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
}

dependencies {
    // Jib extension API for implementing JibGradlePluginExtension
    implementation(libs.jibGradlePluginExtensionApi)
}

mavenPublishing {
    coordinates(artifactId = "jib-plugin")

    pom {
        name.set("Temporal Kotlin Jib Extension")
        description.set("Jib extension that filters Temporal native classifier JARs per container platform")
    }
}
