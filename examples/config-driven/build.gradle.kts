plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.example.configdriven.MainKt")
}
