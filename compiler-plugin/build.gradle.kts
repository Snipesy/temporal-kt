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
            groupId = project.group.toString()
            artifactId = "temporal-compiler-plugin"
            version = project.version.toString()

            pom {
                name.set("Temporal Kotlin Compiler Plugin")
                description.set(
                    "Kotlin compiler plugin for Temporal workflow determinism validation and code generation",
                )
                url.set("https://github.com/Snipesy/temporal-kt")

                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://opensource.org/license/apache-2-0")
                    }
                }
            }
        }
    }
}

dependencies {
    compileOnly(libs.kotlinCompilerEmbeddable)
    implementation(project(":temporal-kt"))
    implementation(libs.kotlinxSerialization)

    // Auto-service for automatic service registration
    compileOnly(libs.autoServiceAnnotations)
    kapt(libs.autoService)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinCompilerEmbeddable)
    testImplementation(libs.kotlinxCoroutines)
    testImplementation(project(":temporal-kt"))
}
