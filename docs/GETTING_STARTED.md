# Temporal Kotlin

Temporal-Kt is a Kotlin SDK for building Temporal applications around the Rust Core SDK. It provides a different
API from the older Java SDK, and contains many Kotlin backend features at its core. Temporal Kotlin is more similar
to the Python SDK than it is to the Java SDK.

## Getting Started

### Prerequisites

- **JDK 25+** (Java 25 is needed for stable FFM)
- **Temporal Server** running locally or remotely
  - For local development: `temporal server start-dev`
- Your Kotlin Version should match Temporal-Kt's build (functional ABIs between kotlin versions is not currently a priority).

### Installation

The Temporal Gradle plugin automatically handles platform detection:

```kotlin
plugins {
    id("com.surrealdev.temporal") version "VERSION"
}

dependencies {
    implementation("com.surrealdev.temporal:core:VERSION")
}

temporal {
    native()  // Adds the correct platform-specific native library
}
```

Or with an explicit version for the native library:

```kotlin
temporal {
    native("1.0.0")  // Use specific version
}
```

#### Manual Installation

If you prefer not to use the plugin, specify your platform directly:

| Platform                      | Classifier          |
|-------------------------------|---------------------|
| Linux x86_64                  | `linux-x86_64-gnu`  |
| Linux aarch64                 | `linux-aarch64-gnu` |
| macOS x86_64                  | `macos-x86_64`      |
| macOS aarch64 (Apple Silicon) | `macos-aarch64`     |
| Windows x86_64                | `windows-x86_64`    |

### Hello World

Here's a minimal example to get you started:

```kotlin
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.embeddedTemporal
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.workflow.startActivity
import com.surrealdev.temporal.workflow.workflow
import kotlin.time.Duration.Companion.seconds

fun main() {
    val app = embeddedTemporal(
        module = {
            taskQueue("hello-world-queue") {
                workflow<GreetingWorkflow>()
                activity(GreetingActivity())
            }
        }
    )

    println("Starting Temporal application on task queue: hello-world-queue")
    println("Start a workflow with: temporal workflow start --task-queue hello-world-queue --type GreetingWorkflow --input '\"World\"'")

    app.start(wait = true)
}

@Workflow("GreetingWorkflow")
class GreetingWorkflow {
    @WorkflowRun
    suspend fun run(name: String): String {
        val greeting = workflow()
            .startActivity(
                GreetingActivity::formatGreeting,
                arg = name,
                scheduleToCloseTimeout = 10.seconds
            ).result<String>()
        return greeting
    }
}

class GreetingActivity {
    @Activity("formatGreeting")
    fun formatGreeting(name: String): String {
        return "Hello, $name!"
    }
}
```

### Running Your Application

1. Start the Temporal dev server:
   ```bash
   temporal server start-dev
   ```

2. Run your application:
   ```bash
   ./gradlew run
   ```

3. Start a workflow using the Temporal CLI:
   ```bash
   temporal workflow start \
       --task-queue hello-world-queue \
       --type GreetingWorkflow \
       --input '"World"'
   ```

For more examples, see the [examples](../examples) directory.

## Structured Concurrent Workflows

The biggest feature of Temporal Kotlin is its implementations of workflows. Workflows are written as coroutines which
allows for [structured execution](https://kotlinlang.org/docs/coroutines-basics.html#coroutine-scope-and-structured-concurrency)
not possible with Java (even with virtual threads).

Example Workflow

```kotlin
@Workflow("MyWorkflowName")
class MyWorkflow {
    @WorkflowRun
    suspend fun run(input: InputModel): OutputModel {
        val ctx = workflow()

        // Run 2 activities sequentially
        val resultA = ctx.startActivity(
            activityType = "activityA",
            arg = input.paramA,
            scheduleToCloseTimeout = 10.seconds
        ).result<String>()

        val resultB = ctx.startActivity(
            activityType = "activityB",
            arg = input.paramB,
            scheduleToCloseTimeout = 10.seconds
        ).result<String>()

        // Run multiple activities in parallel using handles
        val handleC = ctx.startActivity(
            activityType = "activityC",
            arg = resultA,
            scheduleToCloseTimeout = 10.seconds
        )
        val handleD = ctx.startActivity(
            activityType = "activityD",
            arg = resultB,
            scheduleToCloseTimeout = 10.seconds
        )
        val results = listOf(handleC.result<String>(), handleD.result<String>())

        // Or use coroutine async for parallel execution
        val deferred1 = async {
            ctx.startActivity(
                activityType = "activityC",
                arg = resultA,
                scheduleToCloseTimeout = 10.seconds
            ).result<String>()
        }
        val deferred2 = async {
            ctx.startActivity(
                activityType = "activityD",
                arg = resultB,
                scheduleToCloseTimeout = 10.seconds
            ).result<String>()
        }
        val results2 = awaitAll(deferred1, deferred2)

        return OutputModel(results[0], results[1])
    }
}
```

## Sync Activities

Activities run in a dedicated virtual thread, so you can simply block a coroutine without consequence.


```kotlin
@Activity("MyBlockingActivity")
fun doSomethingBlocking(param: String): String {
    Thread.sleep(1000) // This is fine, it runs in a virtual thread
    return "Done"
}
```

Blocking in a suspend function activity is also fine (but your IDE won't be happy about it).

```kotlin
@Activity("MyBlockingSuspendActivity")
suspend fun doSomethingBlockingSuspend(param: String): String {
    Thread.sleep(1000) // This is fine
    return "Done"
}
```

Blocking in workflows should be avoided if possible.

## Structured Concurrency


### Structure Escape Anti-Patterns

Launching an uncontrolled coroutine or thread inside a workflow or activity will cause memory leaks or performance issues
on the temporal application in an uncontrollable way, and would defeat the purpose of this library.

```kotlin
@Activity("MyBadActivity")
fun badActivity(param: String): String {
    Thread {
        // Some background work
    }.start()
    return "Done"
}

// or with coroutines
@Workflow("MyBadWorkflow")
class MyBadWorkflow {
    @WorkflowRun
    suspend fun run(input: InputModel): OutputModel {
        return GlobalScope.async {
            // Some background work
        }
    }
}
```

### What to Do Instead

Instead, use structured concurrency to launch coroutines that are scoped to the workflow or activity.

In almost all cases you can simply piggy back on the activity or workflow's dispatcher like this.

```kotlin
@Activity("MyGoodActivity")
suspend fun goodActivity(param: String): String = coroutineScope {
    launch {
        // Some background work
    }
    "Done"
}
```

If you MUST have parallelism within an activity use `withContext` to switch to a different dispatcher. This guarantees
cancellation when the activity is canceled or finishes.

```kotlin
@Activity("MyGoodActivityWithContext")
suspend fun goodActivityWithContext(param: String): String = coroutineScope {
    withContext(Dispatchers.Default) {
        // Some background work on a different thread pool
    }
    "Done"
}
```
