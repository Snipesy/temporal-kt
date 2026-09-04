plugins {
    id("buildsrc.convention.kotlin-jvm")
    // Runnable: needs the Core native library on the runtime classpath.
    id("buildsrc.convention.temporal-native-runtime")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.example.multiworker.MainKt")
}
