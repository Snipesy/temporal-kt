// Convention plugin for Maven publishing using vanniktech/gradle-maven-publish-plugin
package buildsrc.convention

plugins {
    id("com.vanniktech.maven.publish")
}

// Configure maven publishing
mavenPublishing {
    // Publish to Maven Central via Central Portal with auto-release: deployments go
    // PENDING → VALIDATING → VALIDATED → PUBLISHING → PUBLISHED without a manual UI click.
    // (Default `publishToMavenCentral()` uses publishingType=USER_MANAGED, which stops at
    // VALIDATED and requires a button-press at central.sonatype.com/publishing/deployments.)
    // SNAPSHOTs are unaffected — they bypass the staging flow and auto-publish anyway.
    publishToMavenCentral(automaticRelease = true)

    // Sign all publications (required for Maven Central)
    signAllPublications()

    // Configure POM metadata (required for Maven Central compliance)
    pom {
        url.set("https://github.com/Snipesy/temporal-kt")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://opensource.org/license/apache-2-0")
                distribution.set("repo")
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
