import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    kotlin("kapt")
    alias(libs.plugins.kotlinPluginSerialization)
}

// KEFS-compatible version: `<kotlinCompilerVersion>-<libraryVersion>`. The IDE's external-FIR-support
// tool (Mr3zee/kotlin-external-fir-support) uses this prefix to find a compiler-plugin artifact
// matching the IDE's bundled Kotlin compiler. See
// https://github.com/Mr3zee/kotlin-external-fir-support/blob/main/GUIDE.md
// and the kotlinx-rpc precedent in `RpcPluginConst.libraryKotlinPrefixedVersion`.
//
// **Building against an IDE-bundled Kotlin** (e.g. `2.4.0-ij261-32`):
//   ./gradlew :compiler-plugin:publishToMavenLocal -Pkotlin.compiler=2.4.0-ij261-32
//
// IDE-bundled `-ij` Kotlin builds are NOT published to any public Maven repo (they live only
// inside JetBrains' internal build system). When `kotlin.compiler` is set to an `-ij`-tagged or
// otherwise unresolvable version, we **forge the artifact prefix**: compile against our pinned
// `libs.versions.kotlin` ABI, but publish under the requested version string so KEFS finds it.
// This is safe today because the FIR/IR APIs temporal-kt uses are stable across the 2.3.21 →
// 2.4.x boundary modulo two WARNING-level deprecations (`pluginContext.referenceClass` /
// `referenceFunctions`). When real ABI drift starts breaking us, switch to CSM templates per
// kotlinx-rpc's pattern.
//
// For genuine `dev` / non-ij Kotlin versions (e.g. `2.4.0-dev-12345` from TeamCity), the
// `lib-kotlin/` local Maven repo set up by an equivalent of `dowload_kotlin_master.sh` would
// contain the artifact and we could resolve normally. That mechanism is not yet wired.
//
// The base library version remains in `gradle.properties`; we only override `version` for THIS
// module so other modules (core, gradle-plugin, compiler-plugin-runtime) keep stable semver.
val libraryVersion: String = rootProject.version.toString()
val pinnedKotlinVersion: String = libs.versions.kotlin.get()
val requestedKotlinVersion: String =
    (project.findProperty("kotlin.compiler") as? String)?.takeIf { it.isNotBlank() }
        ?: pinnedKotlinVersion

/** True when the requested version is an IDE-internal build that cannot be resolved from Maven. */
val requestedIsIdeOnly: Boolean = requestedKotlinVersion.contains("-ij")

/** The version we'll actually compile against — falls back when the request is unresolvable. */
val resolvedKotlinVersion: String =
    if (requestedIsIdeOnly) pinnedKotlinVersion else requestedKotlinVersion

version = "$requestedKotlinVersion-$libraryVersion"

logger.lifecycle(
    "[compiler-plugin] publishing as version=$version " +
        "(requested=$requestedKotlinVersion, compiledAgainst=$resolvedKotlinVersion, library=$libraryVersion)" +
        if (requestedIsIdeOnly) " — forging KEFS prefix; ABI is $resolvedKotlinVersion" else "",
)

mavenPublishing {
    coordinates(artifactId = "compiler-plugin")

    pom {
        name.set("Temporal Kotlin Compiler Plugin")
        description.set("Kotlin compiler plugin for Temporal workflow determinism validation and code generation")
    }
}

sourceSets {
    test {
        java.srcDir("src/test-gen")
    }
}

// `kotlin-compiler` ships com.intellij.openapi.util.io.NioFiles with `deleteRecursively` stripped by Proguard.
// The test framework calls that method. To fix, we load the proper NioFiles from `com.jetbrains.intellij.platform:util`
// by prepending it to the test runtime classpath. Do NOT use `kotlin-compiler-embeddable` here — it triggers
// `java.lang.VerifyError: Bad type on operand stack`. See kotlinx-rpc/tests/compiler-plugin-tests/build.gradle.kts.
val testPriorityRuntimeClasspath: Configuration by configurations.creating

sourceSets.test.configure {
    runtimeClasspath = testPriorityRuntimeClasspath + sourceSets.test.get().runtimeClasspath
}

// Jars added to the compilation classpath of testData files (so they can `import` our annotations + coroutines).
val testDataClasspath: Configuration by configurations.creating

// Jars whose paths are passed via `org.jetbrains.kotlin.test.kotlin-*` system properties for
// EnvironmentBasedStandardLibrariesPathProvider.
val testArtifacts: Configuration by configurations.creating

// When `-Pkotlin.compiler=<version>` is set AND the version is resolvable from a public Maven repo,
// swap every `org.jetbrains.kotlin:*` dependency in this module to that version. For IDE-internal
// `-ij` versions (which aren't on any public Maven repo), keep the pinned version — the artifact
// is published with the forged prefix, but the bytecode is built against our default ABI.
if (resolvedKotlinVersion != pinnedKotlinVersion) {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-")) {
                useVersion(resolvedKotlinVersion)
                because("compiler-plugin built against $resolvedKotlinVersion via -Pkotlin.compiler")
            }
        }
    }
}

dependencies {
    compileOnly(libs.kotlinCompilerEmbeddable)
    // The plugin reads FQNs of @TemporalModule / WorkflowDecl etc as strings. It does NOT depend
    // on :compiler-plugin-runtime for compilation — the runtime is consumed by *user* code, not
    // by the plugin itself. Keeping the dependency one-way (user → runtime, plugin → no runtime)
    // means the plugin jar stays small and the runtime jar can evolve independently.
    implementation(libs.kotlinxSerialization)

    testPriorityRuntimeClasspath(libs.intellijUtil) { isTransitive = false }

    testArtifacts(kotlin("stdlib"))
    testArtifacts(libs.kotlinStdlibJdk8)
    testArtifacts(libs.kotlinReflect)
    testArtifacts(libs.kotlinTest)
    testArtifacts(libs.kotlinScriptRuntime)
    testArtifacts(libs.kotlinAnnotationsJvm)

    testImplementation(libs.kotlinReflect)
    testImplementation(libs.kotlinCompiler)
    testImplementation(libs.kotlinCompilerInternalTestFramework)

    testImplementation(libs.junit4)
    testImplementation(platform(libs.junit5Bom))
    testImplementation(libs.junit5Jupiter)
    testImplementation(libs.junit5PlatformCommons)
    testImplementation(libs.junit5PlatformLauncher)
    testImplementation(libs.junit5PlatformRunner)
    testImplementation(libs.junit5PlatformSuiteApi)

    testDataClasspath(project(":core"))
    testDataClasspath(project(":compiler-plugin-runtime"))
    testDataClasspath(libs.kotlinxCoroutines)
}

val updateTestData = (project.findProperty("kotlin.test.update.test.data") as? String) ?: "false"

val generateTests =
    tasks.register<JavaExec>("generateTests") {
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("com.surrealdev.temporal.compiler.test.GenerateTestsKt")
    }

val isCI = System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null

tasks.named<KotlinCompile>("compileTestKotlin").configure {
    if (!isCI) {
        finalizedBy(generateTests)
    }
}

tasks.test {
    dependsOn(generateTests)

    inputs
        .dir("src/test/resources/testData")
        .ignoreEmptyDirectories()
        .normalizeLineEndings()
        .withPathSensitivity(PathSensitivity.RELATIVE)

    useJUnitPlatform()

    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", rootDir.absolutePath)
    systemProperty("kotlin.test.update.test.data", updateTestData)

    setJarPathAsProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setJarPathAsProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setJarPathAsProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setJarPathAsProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setJarPathAsProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setJarPathAsProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")

    systemProperty(
        "temporal.test.runtime.classpath",
        testDataClasspath.files.joinToString(File.pathSeparator) { it.absolutePath },
    )
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
        // FIR checker overrides in 2.3.x use `context(context: CheckerContext, reporter: DiagnosticReporter)`.
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

fun Test.setJarPathAsProperty(
    propName: String,
    jarName: String,
) {
    val regex = "$jarName-\\d.*\\.jar".toRegex()
    val jar = testArtifacts.files.firstOrNull { regex.matches(it.name) }
    if (jar == null) {
        logger.warn("[compiler-plugin tests] cannot find $jarName in testArtifacts")
        return
    }
    systemProperty(propName, jar.absolutePath)
}
