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

## Using Dependencies

### In Workflows

```kotlin
@Workflow("OrderWorkflow")
class OrderWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(orderId: String): OrderResult {
        // Get your dependency
        val config: AppConfig by workflowDependencies()

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
        val httpClient: HttpClient by activityDependencies()

        // Workflow-safe dependencies also work in activities
        val config: AppConfig by workflowDependencies()

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

## Qualifiers

Have multiple instances of the same type? Use qualifiers:

```kotlin
dependencies {
    workflowSafe<Database>(qualifier = "users") { UsersDatabase() }
    workflowSafe<Database>(qualifier = "orders") { OrdersDatabase() }
}

// In your workflow
val usersDb: Database by workflowDependencies(qualifier = "users")
val ordersDb: Database by workflowDependencies(qualifier = "orders")
```
