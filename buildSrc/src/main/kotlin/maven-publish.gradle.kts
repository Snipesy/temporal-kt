// Convention plugin for Maven publishing with complete metadata for Maven Central compliance
package buildsrc.convention

import org.gradle.api.publish.maven.MavenPublication
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
            pom {
                // Complete POM metadata for Maven Central compliance
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
        maven {
            name = "MavenCentral"
            url =
                uri(
                    if (version.toString().endsWith("SNAPSHOT")) {
                        "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                    } else {
                        "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                    },
                )
            credentials {
                username = findProperty("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME")
                password = findProperty("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD")
            }
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
