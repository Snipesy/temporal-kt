# Temporal KT

A Kotlin-first wrapper around the [Temporal Core SDK](https://github.com/temporalio/sdk-core).

**This is NOT a drop in replacement for [temporal-java](https://docs.temporal.io/develop/java)**. This is a complete
redesign of the SDK to provide a more idiomatic Kotlin experience, leveraging Kotlin language features

Currently, this is super in development and in the move fast break things phase. Expect breaking changes frequently.

See [progress.md](Progress.md) to see how close we are to feature parity with temporal-java.

## Prerequisites

- **Java 25** (GraalVM recommended) - `sdk install java 25.0.1-graal`
- **Gradle 9.2+** - `sdk install gradle 9.2.1`
- **Rust (with rustup)** - [rustup.rs](https://rustup.rs)
- **Protobuf** - `brew install protobuf`

```bash
sdk env install
```
### Supported Platforms

Temporal-KT currently only supports:

* MacOS aarch64
* Linux x86_64
* Linux aarch64
* Windows x86_64 (GNU)

### Cross-Platform Build (Optional)

To build native libraries for all platforms, install:

- **cargo-zigbuild** - `cargo install cargo-zigbuild`
- **Zig** - `brew install zig` (macOS) or [ziglang.org/download](https://ziglang.org/download/)
- **Rust targets** - Install the cross-compilation targets:
  ```bash
  rustup target add x86_64-unknown-linux-gnu
  rustup target add aarch64-unknown-linux-gnu
  rustup target add x86_64-pc-windows-gnu
  rustup target add aarch64-apple-darwin
  ```
- Platform specific build essentials (follow the errors)
  
Then you can build cross-platform artifacts with
```bash
gradle cargoBuildAll
gradle copyAllNativeLibs
```


## Cloning

This repo uses git submodules. Clone with:

```bash
git clone --recurse-submodules https://github.com/snipesy/temporal-kt.git
```

Or if already cloned:

```bash
git submodule update --init --recursive
```

## Building

```bash
./gradlew build
```

## Core Foundation

The core foundation of temporal-kt is built around 2 APIs, Declarative and Imperative (although they work together).

### Imperative API [TKT-0001](proposals/TKT-0001-annotations.md) and [TKT-0002](proposals/TKT-0002-interfaces.md)

```kotlin
@Workflow("MyWorkflow")
class MyWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.execute(arg: WorkflowArg): String {
        val greeting = activity<MyActivity>().greet(arg.name)
        return "$greeting! Count: ${arg.count}"
    }
}

class MyActivity {
    @Activity
    suspend fun ActivityContext.greet(name: String): String = "Hello, $name"
}

fun TemporalApplication.module() {
    install(PayloadSerialization) { json() }
    taskQueue("my-queue") {
        workflow<MyWorkflow>()
        activity<MyActivity>()
    }
}
```

### Declarative API [TKT-0003](proposals/TKT-0003-inline.md)

```kotlin
fun TemporalApplication.module() {
    install(PayloadSerialization) { json() }
    taskQueue("my-queue") {
        workflow<WorkflowArg>("MyWorkflow") { arg ->
            val greeting = activity<String>("MyActivity") { name ->
                "Hello, $name"
            }
            "$greeting! Count: ${arg.count}"
        }
    }
}
```

## Proposals

The main goal of this project is to provide powerful kotlin-first APIs to develop Temporal workflows and activities.

These niceties are collected in various TKT (Temporal Kotlin) proposals.

See [proposals/](proposals/) for API design proposals:

| Proposal | Description |
|----------|-------------|
| [TKT-0001](proposals/TKT-0001-annotations.md) | Annotation-based definitions (primary) |
| [TKT-0002](proposals/TKT-0002-interfaces.md) | Interface-based definitions (interop) |
| [TKT-0003](proposals/TKT-0003-inline.md) | Inline declarative definitions |
| [TKT-0004](proposals/TKT-0004-dependency-injection.md) | Dependency injection |
| [TKT-0005](proposals/TKT-0005-modules.md) | Module registration |
| [TKT-0006](proposals/TKT-0006-dsl-scope.md) | DSL scope safety |
| [TKT-0007](proposals/TKT-0007-determinism.md) | Determinism checks |
| [TKT-0008](proposals/TKT-0008-non-suspending.md) | Non-suspending annotations/interfaces |
| [TKT-0009](proposals/TKT-0009-routing.md) | Routing |
| [TKT-0010](proposals/TKT-0010-testing.md) | Test application environment |
| [TKT-0011](proposals/TKT-0011-payload-codec.md) | Payload codec (encryption/compression) |
| [TKT-0012](proposals/TKT-0012-payload-serializer.md) | Payload serializer |
| [TKT-0013](proposals/TKT-0013-ktor-subroutine.md) | Ktor subroutine (unified JAR) |
| [TKT-0014](proposals/TKT-0014-coroutine-scopes.md) | Coroutine scope management |
| [TKT-0015](proposals/TKT-0015-context-resolution.md) | Workflow context resolution |
