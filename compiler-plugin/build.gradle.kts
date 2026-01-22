plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    kotlin("kapt")
    alias(libs.plugins.kotlinPluginSerialization)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Temporal Kotlin Compiler Plugin")
                description.set(
                    "Kotlin compiler plugin for Temporal workflow determinism validation and code generation",
                )
            }
        }
    }
}

dependencies {
    compileOnly(libs.kotlinCompilerEmbeddable)
    implementation(project(":temporal-kt"))
    implementation(libs.kotlinxSerialization)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinCompilerEmbeddable)
    testImplementation(libs.kotlinxCoroutines)
    testImplementation(project(":temporal-kt"))
}
