plugins {
    id("org.jetbrains.dokka")
    id("org.jetbrains.dokka-javadoc")
}

dokka {
    moduleName.set("temporal-kt")

    dokkaPublications.html {
//        outputDirectory.set(rootProject.projectDir.resolve("docs"))
        suppressInheritedMembers.set(true)
        failOnWarning.set(false)
    }
}

dependencies {
    dokka(project(":core-bridge"))
    dokka(project(":compiler-plugin"))
    dokka(project(":gradle-plugin"))
    dokka(project(":core"))
}

// Projects with no documentable sources. `bom` is a java-platform (constraints only) and
// `protos` is ~2,100 generated files that nobody reads as docs -- running Dokka over them is what
// made the root build need -Xmx8g.
val undocumentedProjects = setOf("bom", "protos")

subprojects {
    if (name in undocumentedProjects) {
        return@subprojects
    }

    apply(plugin = "org.jetbrains.dokka")

    // Only apply dokka-javadoc to non-multiplatform projects
    // (Javadoc plugin doesn't support multiplatform)
    if (name != "common") {
        apply(plugin = "org.jetbrains.dokka-javadoc")
    }

    dokka {
        moduleName.set(name)

        // Include module README if it exists
        val moduleReadme = project.file("README.md")
        if (moduleReadme.exists()) {
            dokkaSourceSets.configureEach {
                includes.from(moduleReadme)
            }
        }

        dokkaPublications.html {
            suppressInheritedMembers.set(true)
            failOnWarning.set(false)
        }
    }
}
