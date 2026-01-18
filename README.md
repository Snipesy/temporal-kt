# Temporal KT

This project aims to be a Kotlin first class wrapper around the [Temporal Core SDK](https://github.com/temporalio/sdk-core).

It is intended to be a rewrite to support Kotlin features such as Coroutines, Flow, Sealed Classes, Kotlinx Serailization,
and other kotlin specific features. **It is not intended to drop in replace [temporal-java](https://docs.temporal.io/develop/java)**.

## Prerequisites

### Required
- **Java 25** (GraalVM recommended) - `sdk install java 25.0.1-graal`
- **Gradle 9.2+** - `sdk install gradle 9.2.1`
- **Rust** - [rustup.rs](https://rustup.rs)
- **Protobuf** - `brew install protobuf`

### For Cross-Compilation (optional)
- **Zig** - `brew install zig`
- **cargo-zigbuild** - `cargo install cargo-zigbuild`

### Quick Setup with SDKMAN
```bash
sdk env install
```

## Building

```bash
# Build everything
./gradlew build

# Build native library only (current platform)
./gradlew :core-bridge:cargoBuild

# Build for Linux x86_64 (requires zig + cargo-zigbuild)
./gradlew :core-bridge:cargoBuildLinuxx8664

# Build all platforms
./gradlew :core-bridge:cargoBuildAll
```

## Quick Style

Unlike the Java SDK, coroutines are used extensively and all workflow execution, activity execution, and client calls
are suspendable functions. Workflows and Activities are declared directly rather than through interfaces, with a heavy
emphasis on context managers and extension functions.

This is somewhat similar to Ktor's DSL style... But for Temporal.

```kotlin
@Serializable
data class WorkflowArg(
    val name: String,
    val count: Int = 0
)

interface MyWorkflow {
    suspend fun WorkflowContext.execute(arg: WorkflowArg): String
}

interface MyActivity {
    suspend fun ActivityContext.greet(name: String): String
}

class MyWorkflowImpl : MyWorkflow {
    override suspend fun WorkflowContext.execute(arg: WorkflowArg): String {
        val greeting = activity<MyActivity>(
            activityType = "MyActivity",
            startToCloseTimeout = Duration.ofSeconds(120),
            // ...other activity options
        ).greet(arg.name)
        return "$greeting! You have requested count: ${arg.count}"
    }
}

class MyActivityImpl : MyActivity {
    override suspend fun ActivityContext.greet(name: String): String {
        return "Hello, $name"
    }
}

fun TemporalApplication.myMainModule() { // TemporalApplicationContext ->
    install(KotlinxSerialization) {
        json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
    taskQueue("my-task-queue") { // TemporalTaskQueueContext ->
        workflow(MyWorkflowImpl())
        activity(MyActivityImpl())
    }
}

// Client
suspend fun main() {
    val client = app.client {
        // Client configuration
    }

    val stub = client.newWorkflowStub<MyWorkflow>(
        workflowType = "MyWorkflow",
        taskQueue = "my-task-queue"
    )
    
    println("Workflow result: ${stub.execute(WorkflowArg(name = "Temporal KT", count = 5))}")

    app.stop()
}
```

