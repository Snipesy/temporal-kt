# Temporal KT

A Kotlin-first wrapper around the [Temporal Core SDK](https://github.com/temporalio/sdk-core).

**This is NOT a drop in replacement for [temporal-java](https://docs.temporal.io/develop/java)**. This is a complete
redesign of the SDK to provide a more idiomatic Kotlin experience, leveraging Kotlin language features

Currently, this is super in development and in the move fast break things phase. Expect breaking changes frequently.

See [progress.md](Progress.md) to see how close we are to feature parity with temporal-java.

## Prerequisites

- **Java 25** (GraalVM recommended) - `sdk install java 25.0.1-graal`
- **Gradle 9.2+** - `sdk install gradle 9.2.1`
- **Rust** - [rustup.rs](https://rustup.rs)
- **Protobuf** - `brew install protobuf`

```bash
sdk env install
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

@Activity("MyActivity")
class MyActivity {
    @ActivityMethod
    suspend fun ActivityContext.greet(name: String): String = "Hello, $name"
}

fun TemporalApplication.module() {
    install(KotlinxSerialization)
    taskQueue("my-queue") {
        workflow<MyWorkflow>()
        activity<MyActivity>()
    }
}
```

### Declarative API [TKT-0003](proposals/TKT-0003-inline.md)

```kotlin
fun TemporalApplication.module() {
    install(KotlinxSerialization)
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
