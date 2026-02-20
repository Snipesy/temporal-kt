# Module di

Temporal KT Dependency Injection Plugin - A lightweight dependency injection plugin for Temporal KT that keeps your workflows deterministic and your activities flexible.

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.surrealdev.temporal:di:$version")
}
```

## Quick Start

```kotlin
val app = embeddedTemporal(
    module = {
        // Register your dependencies
        dependencies {
            workflowSafe<ConfigService> { MyConfigService() }
            activityOnly<HttpClient> { OkHttpClient() }
        }

        taskQueue("my-queue") {
            workflow<MyWorkflow>()
            activity(MyActivity())
        }
    }
)

app.start(wait = true)
```

### Singleton semantics

Every dependency registered in a `DependencyRegistry` is a **singleton within that registry**. The factory lambda is called at most once; all contexts created from the same registry share the same instance:

```kotlin
app.dependencies {
    activityOnly<HttpClient> { OkHttpClient() }  // created once, shared by all activity executions
}
```

## Using Dependencies

### In Workflows

```kotlin
@Workflow("OrderWorkflow")
class OrderWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(orderId: String): OrderResult {
        // Get your dependency using property delegation
        val config: AppConfig by workflowDependencies

        // Use it
        val maxRetries = config.orderMaxRetries

        // ... rest of workflow
    }
}
```

### In Activities

```kotlin
class PaymentActivity {
    @Activity
    suspend fun ActivityContext.processPayment(amount: Double): PaymentResult {
        // Activity-only dependencies work here
        val httpClient: HttpClient by activityDependencies

        // Workflow-safe dependencies also work in activities
        val config: AppConfig by workflowDependencies

        return httpClient.post(config.paymentUrl, amount)
    }
}
```

## Task Queue Specific Dependencies

Need different implementations for different task queues? No problem:

```kotlin
val app = embeddedTemporal(
    module = {
        // Default for the whole app
        dependencies {
            workflowSafe<FeatureFlags> { ProductionFeatureFlags() }
        }

        // Override for a specific queue
        taskQueue("beta-queue") {
            dependencies {
                workflowSafe<FeatureFlags> { BetaFeatureFlags() }
            }
            workflow<MyWorkflow>()
        }

        taskQueue("production-queue") {
            // Uses the app-level ProductionFeatureFlags
            workflow<MyWorkflow>()
        }
    }
)
```

## Lifecycle & Cleanup

### Automatic shutdown

Registries are closed automatically at the right lifecycle point:

| Registry level | Closed when |
|---|---|
| `app.dependencies { }` | `ApplicationShutdown` — before any workers stop |
| `taskQueue("q") { dependencies { } }` | `WorkerStopped` for that specific task queue |

### AutoCloseable

If a singleton instance implements `AutoCloseable`, its `close()` method is called automatically when the registry closes — no extra configuration needed:

```kotlin
app.dependencies {
    activityOnly<HttpClient> { OkHttpClient() }          // closed automatically on shutdown
    workflowSafe<ConfigService> { MyConfigService() }    // closed automatically on shutdown
}
```

### Explicit `cleanup { }` DSL

For instances that need custom teardown logic, chain a `cleanup` block after registration:

```kotlin
app.dependencies {
    activityOnly<DatabasePool> { DatabasePool(config) } cleanup { it.shutdown() }
    activityOnly<HttpClient>   { OkHttpClient() }       cleanup { it.dispatcher.executorService.shutdown() }
}
```

The `cleanup` block is called **instead of** `AutoCloseable.close()` when one is provided. Each key may have at most one cleanup hook — registering a second one throws immediately during configuration.

### Task-queue-scoped cleanup

Resources that belong to a specific worker can be registered at the task-queue level. They are released when that worker stops, independently of the rest of the application:

```kotlin
taskQueue("payments-queue") {
    dependencies {
        activityOnly<PaymentGatewayClient> { PaymentGatewayClient(config) } cleanup { it.close() }
    }
}
```

## Qualifiers

Have multiple instances of the same type? Use qualifiers:

```kotlin
dependencies {
    workflowSafe<Database>(qualifier = "users") { UsersDatabase() }
    workflowSafe<Database>(qualifier = "orders") { OrdersDatabase() }
}

// In your workflow
val usersDb: Database by workflowDependency("users")
val ordersDb: Database by workflowDependency("orders")
```

## Context Propagation

The `ContextPropagation` plugin propagates typed context values across Temporal service boundaries (client -> workflow -> activity -> child workflow) via Temporal headers. This is useful for propagating things like tenant IDs, request IDs, or authentication context without threading them through every method signature.

TODO: DI is still in development and is not guaranteed to propagate across all boundaries correctly. This means it is currently
not safe to use in critical scenarios (i.e. tenant isolation or user security).

### Client Side — Attach Context

Register providers on the client to inject context into every outgoing call:

```kotlin
val client = TemporalClient.connect {
    install(ContextPropagation) {
        context("tenantId") { currentTenant() }
        context("requestId") { UUID.randomUUID().toString() }
    }
}
```

### Application Side — Receive and Forward

On the worker, register which context keys to forward across boundaries:

```kotlin
val app = embeddedTemporal(
    module = {
        install(ContextPropagation) {
            passThrough("tenantId")                     // forward raw bytes from inbound headers
            context("serverRegion") { "us-east-1" }     // add new server-side context
        }

        taskQueue("my-queue") {
            workflow<MyWorkflow>()
            activity(MyActivity())
        }
    }
)
```

### Reading Context in Workflows and Activities

```kotlin
@Workflow("OrderWorkflow")
class OrderWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(orderId: String): OrderResult {
        val tenant = context<Tenant>("tenantId")

        // Context is automatically forwarded to activities and child workflows
        val result = startActivity(
            activityType = "processOrder",
            options = ActivityOptions(startToCloseTimeout = 30.seconds),
            orderId,
        ).result<OrderResult>()

        return result
    }
}

class OrderActivity {
    @Activity("processOrder")
    suspend fun ActivityContext.processOrder(orderId: String): OrderResult {
        val tenant = context<Tenant>("tenantId")  // same value as the workflow
        // ...
    }
}
```

Context is automatically propagated to:
- Activities (`startActivity`)
- Local activities (`startLocalActivity`)
- Child workflows (`startChildWorkflow`)
- Continue-as-new (`continueAsNew`)
- External workflow signals (`signalExternalWorkflow`)

### Provider Behavior and Replay Safety

By default, application-side providers use `ProviderBehavior.SKIP_IF_PRESENT`. This means if an inbound header already exists for a key (e.g., from the client, or from history during replay), the provider lambda is skipped entirely. This prevents non-deterministic providers from causing replay divergence.

```kotlin
install(ContextPropagation) {
    // Default: SKIP_IF_PRESENT — safe for replay, client value preserved
    context("tenantId") { fallbackTenant() }

    // Explicit: ALWAYS_EXECUTE — provider must be deterministic
    context("serverTimestamp", ProviderBehavior.ALWAYS_EXECUTE) { Instant.now().toString() }
}
```

| Behavior | When inbound header exists | When no inbound header | Replay safe? |
|---|---|---|---|
| `SKIP_IF_PRESENT` (default) | Use inbound value, skip provider | Execute provider | Yes |
| `ALWAYS_EXECUTE` | Execute provider, overwrite inbound | Execute provider | Only if deterministic |

### Signal and Update Handling

Context is set once when the workflow starts (`onExecute`). Signals and updates that carry headers with the same keys are validated against the existing context — if the values differ, an error is thrown. Signals and updates that don't carry propagated headers (the typical case) work fine and see the original context.

```kotlin
@Workflow("MyWorkflow")
class MyWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        val tenant = context<Tenant>("tenantId")
        awaitCondition { done }
        return tenant.name
    }

    @Signal("complete")
    fun WorkflowContext.complete() {
        // Signal handlers see the same context set at workflow start
        val tenant = context<Tenant>("tenantId")
    }

    @Query("getTenant")
    fun WorkflowContext.getTenant(): String {
        val tenant = context<Tenant>("tenantId")
        return tenant.name
    }
}
```
