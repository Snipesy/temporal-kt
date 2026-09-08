// Convention plugin: puts the Temporal Core native library on the RUNTIME classpath.
//
// For runnable modules (the examples). Libraries must not apply this -- their consumers choose
// the classifier for their own platform.
package buildsrc.convention

import buildsrc.TemporalNative

val override = TemporalNative.overridePath(project)
if (override != null) {
    tasks.withType<JavaExec>().configureEach {
        systemProperty("temporal.native.libraryPath", override)
    }
} else {
    dependencies {
        add("runtimeOnly", TemporalNative.coordinate(project))
    }
}
