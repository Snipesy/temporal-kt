plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(project(":core"))
    // The Core native library, as a JAR -- the same shape a published consumer resolves from
    // Maven as a classifier artifact. Replaces the old resources-srcDir + processResources
    // dependsOn(":core-bridge:copyNativeLib") wiring.
    runtimeOnly(project(mapOf("path" to ":core-bridge", "configuration" to "nativeRuntime")))
}

application {
    mainClass.set("com.example.configdriven.MainKt")
}
