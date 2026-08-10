plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
}

dependencies {
    testImplementation(kotlin("test"))
}

mavenPublishing {
    coordinates(artifactId = "core-common")

    pom {
        name.set("Temporal KT Core Common")
        description.set("Common types shared between Temporal KT core-bridge, core, and API consumers")
    }
}
