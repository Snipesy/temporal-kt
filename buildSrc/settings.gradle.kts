dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
            // -Pkotlin.lang=<X> overrides the catalog's `kotlin` version. Drives KGP version (this
            // file is read by buildSrc which loads `kotlinGradlePlugin`) and every kotlin-* library
            // referenced via `version.ref = "kotlin"` in the TOML.
            //
            // Skip `-ij` versions: IDE-bundled Kotlin builds (e.g. `2.4.0-ij261-32`) are not
            // published as kotlin-gradle-plugin artifacts — only `kotlin-compiler-embeddable` etc.
            // exist under that prefix, and even those only on JetBrains Space, not Central.
            // For `-ij` requests we keep KGP at the pinned version; the build.gradle.kts
            // `requestedIsIdeOnly` guard handles the publish-coordinate forge separately.
            providers.gradleProperty("kotlin.lang").orNull
                ?.takeIf { !it.contains("-ij") }
                ?.let { version("kotlin", it) }
        }
    }
}

rootProject.name = "buildSrc"
