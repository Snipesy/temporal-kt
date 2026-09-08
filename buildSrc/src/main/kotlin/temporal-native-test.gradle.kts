// Convention plugin: puts the Temporal Core native library on the TEST runtime classpath.
//
// Apply this ONLY to modules whose tests actually execute native code (they start a
// TemporalRuntime, a dev/test server, or a worker). Modules that merely compile against the SDK
// -- `compiler-plugin`, `gradle-plugin`, `plugins:jib` -- must not apply it.
package buildsrc.convention

import buildsrc.TemporalNative

val override = TemporalNative.overridePath(project)
if (override != null) {
    tasks.withType<Test>().configureEach {
        systemProperty("temporal.native.libraryPath", override)
    }
} else {
    dependencies {
        add("testRuntimeOnly", TemporalNative.coordinate(project))
    }
}
