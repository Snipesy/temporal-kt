// Convention plugin for Maven publishing with complete metadata for Maven Central compliance
package buildsrc.convention

import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.*

plugins {
    `maven-publish`
    signing
}

// Configure source and javadoc JARs after Java plugin is applied
pluginManager.withPlugin("java") {
    val sourceSets = the<SourceSetContainer>()

    // Register source JAR task
    val sourcesJar by tasks.registering(Jar::class) {
        archiveClassifier.set("sources")
        // Only include actual source files (Kotlin/Java), not resources
        from(sourceSets["main"].allJava)
    }

    // Register javadoc JAR task using Dokka HTML output
    val javadocJar by tasks.registering(Jar::class) {
        archiveClassifier.set("javadoc")
        from(tasks.named("dokkaGeneratePublicationHtml"))
    }

    // Add artifacts to publications
    publishing {
        publications {
            withType<MavenPublication> {
                // Add sources and javadoc artifacts (required by Maven Central)
                artifact(sourcesJar)
                artifact(javadocJar)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            // Default to project name as artifactId (can be overridden in module)
            // groupId and version are inherited from project automatically

            pom {
                // Default URL for all modules
                url.set("https://github.com/Snipesy/temporal-kt")

                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://opensource.org/license/apache-2-0")
                    }
                }

                scm {
                    url.set("https://github.com/Snipesy/temporal-kt")
                    connection.set("scm:git:https://github.com/Snipesy/temporal-kt.git")
                    developerConnection.set("scm:git:ssh://git@github.com:Snipesy/temporal-kt.git")
                }

                developers {
                    developer {
                        id.set("snipesy")
                        name.set("Snipesy")
                    }
                }

                organization {
                    name.set("SurrealDev")
                    url.set("https://github.com/Snipesy")
                }
            }
        }
    }

    repositories {
        // Local staging repository for JReleaser to deploy from
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    // Only sign if credentials are available (allows local builds without signing)
    val signingKey = findProperty("signingKey")?.toString() ?: System.getenv("SIGNING_KEY")
    val signingPassword = findProperty("signingPassword")?.toString() ?: System.getenv("SIGNING_PASSWORD")

    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}

// Sign only when publishing to avoid unnecessary signing during local builds
tasks.withType<Sign>().configureEach {
    onlyIf { gradle.taskGraph.allTasks.any { it.name.contains("publish") } }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(tasks.withType<Sign>())
}
