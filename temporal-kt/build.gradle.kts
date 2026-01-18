plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    api(project(":core-bridge"))
    api(libs.bundles.kotlinxEcosystem)

    testImplementation(kotlin("test"))
}
