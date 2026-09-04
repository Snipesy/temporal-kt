import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    alias(libs.plugins.protobuf)
}

// The generated protobuf classes for Temporal's API and SDK-Core's own `coresdk.*` messages.
//
// These live in their own module because they are the single largest cost in a clean build --
// 70 .proto files expand to roughly 2,100 generated sources (~1,340 .java + ~760 .kt) -- and they
// change only when Temporal's API or SDK-Core's local protos change, which is far less often than
// anything else in this repo. Keeping them here means a change to the bridge or to `core` no
// longer regenerates and recompiles all of them.
//
// The .proto sources currently come from the sdk-core submodule. When the bridge moves to its own
// repository these are vendored into `protos/src/main/proto` instead, so that building the JVM
// artifacts needs neither cargo nor a submodule.
dependencies {
    // Both are part of this module's ABI: message types come from protobuf-java, and the
    // generated Kotlin DSL builders (e.g. `coresdk.activityTaskCompletion { ... }`) come from
    // protobuf-kotlin. Consumers cannot compile against the generated code without them.
    api(libs.protobufJava)
    api(libs.protobufKotlin)

    testImplementation(kotlin("test"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("kotlin")
            }
        }
    }
}

val sdkCoreProtos =
    rootProject.layout.projectDirectory.dir("core-bridge/rust/sdk-core/crates/protos/protos")

sourceSets {
    main {
        proto {
            srcDir(sdkCoreProtos.dir("local"))
            srcDir(sdkCoreProtos.dir("api_upstream"))
            srcDir(sdkCoreProtos.dir("testsrv_upstream"))
            // Exclude the google protobuf well-known types - use the runtime versions instead.
            // This prevents version conflicts between generated code and the protobuf-java runtime.
            exclude("**/google/protobuf/**")
        }
    }
}

// Generated code is not documented. Suppressing the source sets keeps Dokka from walking ~2,100
// generated files, which is what forced `org.gradle.jvmargs=-Xmx8g` and the suppressedFiles
// workaround that used to live in core-bridge.
dokka {
    dokkaSourceSets.configureEach {
        suppress.set(true)
    }
}

mavenPublishing {
    // No Dokka javadoc jar: there is nothing here worth rendering, and building one over the
    // generated sources is slow and memory-hungry. The sources jar is kept so IDEs can navigate
    // into the generated code.
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))

    coordinates(artifactId = "protos")

    pom {
        name.set("Temporal KT Protos")
        description.set("Generated protobuf classes for the Temporal API and SDK-Core")
    }
}
