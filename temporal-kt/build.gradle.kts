plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    `maven-publish`
}

dependencies {
    api(project(":core-bridge"))
    api(libs.bundles.kotlinxEcosystem)

    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Temporal KT")
                description.set("Kotlin-first SDK for Temporal")
                url.set("https://github.com/anthropics/temporal-kt")

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
