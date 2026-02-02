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

// Native library path for runtime
val nativeLibsDir = rootProject.layout.projectDirectory.dir("core-bridge/build/native-libs")
val skipNativeBuild = project.findProperty("skipNativeBuild")?.toString()?.toBoolean() ?: false

sourceSets {
    main {
        resources.srcDir(nativeLibsDir)
    }
}

tasks.named("processResources") {
    if (!skipNativeBuild) {
        dependsOn(":core-bridge:copyNativeLib")
    }
}
