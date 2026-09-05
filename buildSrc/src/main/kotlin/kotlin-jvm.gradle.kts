// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
// `buildSrc` is a Gradle-recognized directory and every plugin there will be easily available in the rest of the build.
package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin in JVM projects.
    kotlin("jvm")
    // ktlint for code formatting
    id("org.jlleitschuh.gradle.ktlint")
    // Dokka for documentation generation
    id("org.jetbrains.dokka")
}

configure<KtlintExtension> {
    version.set("1.5.0")
    filter {
        exclude("**/generated/**")
        exclude("**/generated-sources/**")
    }
}

kotlin {
    jvmToolchain(25)
}

// Enable native access for FFM (Foreign Function & Memory) API
val nativeAccessArgs = listOf("--enable-native-access=ALL-UNNAMED")

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform {
        providers.gradleProperty("excludeTags").orNull?.let {
            excludeTags(*it.split(",").toTypedArray())
        }
    }

    // Enable native access for FFM
    jvmArgs(nativeAccessArgs)

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
        )
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
    }
}

tasks.withType<JavaExec>().configureEach {
    // Enable native access for FFM
    jvmArgs(nativeAccessArgs)
}

// NOTE: the native library is deliberately NOT wired in here.
//
// This plugin is applied to every module, so wiring the native in here would put it on the test
// classpath of modules that never execute native code. Modules that need it apply
// `buildsrc.convention.temporal-native-test` (tests) or `temporal-native-runtime` (runnable)
// instead, which resolve the published classifier artifact from temporal-kt-bridge.
