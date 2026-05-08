[![Maven Central Version](https://img.shields.io/maven-central/v/com.surrealdev.temporal/core?link=https%3A%2F%2Fcentral.sonatype.com%2Fartifact%2Fcom.surrealdev.temporal%2Fcore)](https://central.sonatype.com/artifact/com.surrealdev.temporal/core)

# Temporal KT

Kotlin SDK for Temporal, backed by Temporal Core.

Alpha. Requires JDK 25+. The compiler plugin is enabled by default; disable it if Kotlin compiler API drift breaks your build.

## Install

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("com.surrealdev.temporal") version "VERSION"
}

temporal {
    compiler {
        enabled = true
    }
}
```

The Gradle plugin adds `com.surrealdev.temporal:core` and the platform native `core-bridge` runtime dependency.

## Workflow + Activity

```kotlin
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Signal
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.dsl.inlineActivity
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import kotlin.time.Duration.Companion.seconds

@Workflow("OrderWorkflow")
class OrderWorkflow {
    private var approved = false

    @WorkflowRun
    suspend fun WorkflowContext.run(orderId: String): String {
        val normalized = inlineActivity("normalizeOrderId") {
            orderId.trim().uppercase()
        }

        awaitCondition { approved }

        return startActivity(
            OrderActivities::ship,
            arg = normalized,
            scheduleToCloseTimeout = 30.seconds,
        ).result<String>()
    }

    @Signal("approve")
    fun WorkflowContext.approve() {
        approved = true
    }
}

class OrderActivities {
    @Activity("ship")
    fun ship(orderId: String): String = "shipped:$orderId"
}
```

## Worker

```kotlin
import com.surrealdev.temporal.application.embeddedTemporal
import com.surrealdev.temporal.application.taskQueue

fun main() {
    embeddedTemporal(
        configure = {
            connection {
                target = "localhost:7233"
                namespace = "default"
            }
        },
        module = {
            taskQueue("orders") {
                workflow<OrderWorkflow>()
                activity(OrderActivities())
            }
        },
    ).start(wait = true)
}
```

```bash
temporal server start-dev
./gradlew run
```

## Start From Client

With the compiler plugin enabled:

```kotlin
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.client.startWorkflow

suspend fun submitOrder(client: TemporalClient) {
    val order: OrderWorkflow = client.startWorkflow<OrderWorkflow>("orders", " order-123 ")

    // Cast to Handle for typed `result()` and suspend signal wrappers.
    val handle = order as OrderWorkflow.Handle<String>
    handle.approve()
    val result: String = handle.result()
}
```

Without the compiler plugin (untyped, raw):

```kotlin
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.workflow.result

suspend fun submitOrderRaw(client: TemporalClient) {
    val handle = client.startWorkflow(
        workflowType = "OrderWorkflow",
        taskQueue = "orders",
        arg = " order-123 ",
    )

    val result: String = handle.result()
}
```

## Compiler Plugin Examples

Typed workflow start/result:

```kotlin
@Workflow("Greeter")
class Greeter {
    @WorkflowRun
    suspend fun WorkflowContext.run(name: String): String = "Hello, $name"
}

suspend fun call(client: TemporalClient) {
    val greeter: Greeter = client.startWorkflow<Greeter>("greetings", "Ada")
    @Suppress("UNCHECKED_CAST")
    val handle = greeter as Greeter.Handle<String>
    val greeting: String = handle.result()
}
```

Attach to an existing workflow:

```kotlin
import com.surrealdev.temporal.client.workflowHandle

val greeter: Greeter = client.workflowHandle<Greeter>(workflowId = "customer-123")
@Suppress("UNCHECKED_CAST")
val handle = greeter as Greeter.Handle<String>
```

Typed signal/query/update wrappers:

```kotlin
@Workflow("Cart")
class Cart {
    private val items = mutableListOf<String>()

    @WorkflowRun
    suspend fun WorkflowContext.run(): Int {
        awaitCondition { items.isNotEmpty() }
        return items.size
    }

    @Signal("add")
    fun WorkflowContext.add(item: String) {
        items += item
    }

    @Query("size")
    fun WorkflowContext.size(): Int = items.size

    @Update("remove")
    fun WorkflowContext.remove(item: String): Int {
        items -= item
        return items.size
    }
}

suspend fun useCart(client: TemporalClient) {
    // Use Handle directly when calling typed signal/query/update wrappers — those are suspend
    // and don't override the user's non-suspend handlers via static `Cart` type.
    val cart: Cart.Handle<Int> = client.startWorkflow<Cart>("cart") as Cart.Handle<Int>

    cart.add("apple")
    val size: Int = cart.size()
    val remaining: Int = cart.remove("apple")
}
```

Child workflows:

```kotlin
@Workflow("Parent")
class Parent {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        // `startChildWorkflow<W>` returns the user's @Workflow class type (kotlinx-rpc style);
        // cast to ChildHandle to access typed result()/awaitStart()/cancel().
        val greeter: Greeter = startChildWorkflow<Greeter>("Ada")
        @Suppress("UNCHECKED_CAST")
        val child = greeter as Greeter.ChildHandle<String>
        return child.result()
    }
}
```

Inline activities:

```kotlin
@Workflow("Normalize")
class Normalize {
    @WorkflowRun
    suspend fun WorkflowContext.run(input: String): String {
        val suffix = "!"

        return inlineActivity("normalize") {
            input.trim().uppercase() + suffix
        }
    }
}
```

The plugin lifts the lambda to a real `@Activity`, passes captures as activity arguments, and auto-registers it when
`workflow<Normalize>()` is registered on a task queue.

## Modules

```text
core                     public SDK API
core-common              shared core config types
core-bridge              JVM FFM bridge to Temporal Core
core-testing             test fixtures and helpers
compiler-plugin          FIR/IR compiler plugin
compiler-plugin-runtime  runtime support for generated code
gradle-plugin            com.surrealdev.temporal Gradle plugin
plugins/di               dependency injection
plugins/opentelemetry    traces, metrics, log correlation
plugins/health           readiness/liveness/health HTTP endpoints
plugins/jib              Jib native-classifier filtering
```

## Dev

```bash
./gradlew build
./gradlew test
./gradlew jvmTest
./gradlew :compiler-plugin:test
./gradlew :examples:hello-world:run
```

More docs:

- [Compiler Plugin](docs/COMPILER_PLUGIN.md)
- [Configuration](docs/CONFIGURATION.md)
- [Testing](docs/TESTING.md)
- [Plugins](docs/PLUGINS.md)
- [Serialization](docs/CODECS_AND_SERIALIZATION.md)
- [Development Setup](docs/DEV_SETUP.md)
