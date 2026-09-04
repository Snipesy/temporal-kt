// Convention plugin: puts the Temporal Core native library on the test runtime classpath.
//
// Apply this ONLY to modules whose tests actually execute native code (they start a
// TemporalRuntime, a dev/test server, or a worker). Modules that merely compile against the
// SDK -- `compiler-plugin`, `gradle-plugin`, `plugins:jib`, `core-common` -- must not apply it,
// because doing so puts a full Rust build behind their test tasks for no reason.
//
// The native arrives as a single JAR on the classpath, which is the same shape it has when a
// downstream consumer resolves it from Maven as a classifier artifact. `NativeLoader` finds it
// with `getResourceAsStream("/native/<platform>/<lib>")`, so a JAR and a resources directory are
// indistinguishable to it.
package buildsrc.convention

dependencies {
    add("testRuntimeOnly", project(mapOf("path" to ":core-bridge", "configuration" to "nativeRuntime")))
}
