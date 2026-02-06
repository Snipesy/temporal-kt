# Configuration Guide

This guide covers configuring Temporal-Kt applications, task queues, and workers.

## Quick Start

```kotlin
fun main() {
    embeddedTemporal(
        configure = {
            connection {
                target = "http://localhost:7233"
                namespace = "default"
            }
        },
        module = {
            taskQueue("my-queue") {
                workflow<MyWorkflow>()
                activity(MyActivity())
            }
        }
    ).start(wait = true)
}
```

## Application Configuration

### Connection Settings

Configure the connection to the Temporal server.

```kotlin
embeddedTemporal(configure = {
    connection {
        target = "http://localhost:7233"  // Server address
        namespace = "default"              // Temporal namespace
    }
})
```

For Temporal Cloud or TLS-enabled servers, see the [Authentication Guide](AUTH_SETUP_GUIDE.md).

### Shutdown Behavior

Configure graceful shutdown timeouts.

```kotlin
embeddedTemporal(configure = {
    shutdown {
        gracePeriodMs = 10_000L   // Wait 10s for workers to finish gracefully
        forceTimeoutMs = 5_000L  // Then wait 5s more after force cancellation
    }
})
```

### Worker Deployment Versioning

Configure worker versioning for safe deployments.

```kotlin
embeddedTemporal(configure = {
    deployment {
        deploymentName = "order-service"
        buildId = "v1.2.3"
        useWorkerVersioning = true
        defaultVersioningBehavior = VersioningBehavior.AUTO_UPGRADE
    }
})
```

Or using the direct API:

```kotlin
embeddedTemporal(configure = {
    deployment(
        version = WorkerDeploymentVersion("order-service", "v1.2.3"),
        useVersioning = true,
        defaultVersioningBehavior = VersioningBehavior.AUTO_UPGRADE,
    )
})
```

**Versioning Behaviors:**
- `UNSPECIFIED` - Workflow must specify its own behavior
- `PINNED` - Workflow stays on the version it started with
- `AUTO_UPGRADE` - Workflow automatically upgrades to latest version

### Dispatcher Configuration

Override the default coroutine dispatcher.

```kotlin
embeddedTemporal(configure = {
    dispatcher = Dispatchers.Default.limitedParallelism(4)
})
```

## Task Queue Configuration

Each task queue can be configured independently.

### Concurrency Limits

Control how many workflows and activities run concurrently.

```kotlin
taskQueue("my-queue") {
    maxConcurrentWorkflows = 200    // Default: 200
    maxConcurrentActivities = 200   // Default: 200

    workflow<MyWorkflow>()
    activity(MyActivity())
}
```

### Heartbeat Throttling

Configure activity heartbeat throttling to reduce server load.

```kotlin
taskQueue("my-queue") {
    // Maximum interval between heartbeats (regardless of activity settings)
    maxHeartbeatThrottleIntervalMs = 60_000L  // Default: 60s

    // Default interval when no heartbeat timeout is configured
    // When heartbeat timeout IS configured, uses 80% of that timeout instead
    defaultHeartbeatThrottleIntervalMs = 30_000L  // Default: 30s
}
```

### Deadlock Detection

Configure workflow deadlock detection timeout.

```kotlin
taskQueue("my-queue") {
    // Time before a stuck workflow activation throws WorkflowDeadlockException
    // Set to 0 to disable
    workflowDeadlockTimeoutMs = 2000L  // Default: 2s
}
```

### Shutdown Timeouts

Configure per-queue shutdown behavior.

```kotlin
taskQueue("my-queue") {
    shutdownGracePeriodMs = 10_000L  // Wait for graceful completion

    // Force exit timeout when threads are completely unresponsive
    forceExitTimeout = 1.minutes  // Default: 1 minute
}
```

### Zombie Thread Eviction

Configure handling of threads that don't respond to interruption.

```kotlin
taskQueue("my-queue") {
    zombieEviction = ZombieEvictionConfig(
        maxZombieCount = 10,           // Fatal error threshold (0 = disabled)
        gracePeriod = 10.seconds,      // Time before counting as zombie
        retryInterval = 1.seconds,     // Initial retry delay
        retryMaxDelay = 60.seconds,    // Max retry delay (exponential backoff)
        giveUpAfter = 1.hours,         // Stop retrying after this
        shutdownTimeout = 30.seconds,  // Timeout for eviction during shutdown
    )
}
```

### Namespace Override

Use a different namespace for specific task queues.

```kotlin
taskQueue("orders-queue") {
    namespace = "orders-namespace"  // Override application namespace
    workflow<OrderWorkflow>()
}
```

## YAML Configuration

Configure applications using `application.yaml` in your resources.

### Basic Configuration

```yaml
temporal:
  connection:
    target: "http://localhost:7233"
    namespace: "default"
  modules:
    - com.example.ModulesKt.ordersModule
```

### With Deployment Versioning

```yaml
temporal:
  connection:
    target: "http://localhost:7233"
    namespace: "default"
  deployment:
    deploymentName: "order-service"
    buildId: "v1.2.3"
    useWorkerVersioning: true
    defaultVersioningBehavior: "AUTO_UPGRADE"
  modules:
    - com.example.ModulesKt.ordersModule
```

### With TLS

```yaml
temporal:
  connection:
    target: "https://myns.abc123.tmprl.cloud:7233"
    namespace: "myns.abc123"
    tls:
      clientCertPath: "/path/to/client.pem"
      clientKeyPath: "/path/to/client-key.pem"
      serverCaCertPath: "/path/to/ca.pem"  # Optional
      domain: "temporal.example.com"        # Optional
```

### With API Key

```yaml
temporal:
  connection:
    target: "https://myns.abc123.tmprl.cloud:7233"
    namespace: "myns.abc123"
    apiKey: ${TEMPORAL_API_KEY}
```

### Environment Variable Substitution

Hoplite supports environment variable substitution:

```yaml
temporal:
  connection:
    target: ${TEMPORAL_TARGET:-http://localhost:7233}
    namespace: ${TEMPORAL_NAMESPACE:-default}
    apiKey: ${TEMPORAL_API_KEY}
```

### Loading YAML Configuration

```kotlin
// Auto-loads from application.yaml or temporal.yaml
fun main() {
    embeddedTemporal(module = {
        taskQueue("my-queue") {
            workflow<MyWorkflow>()
        }
    }).start(wait = true)
}

// Or specify a custom config path
fun main() {
    embeddedTemporal(
        configPath = "/custom-config.yaml",
        module = {
            taskQueue("my-queue") {
                workflow<MyWorkflow>()
            }
        }
    ).start(wait = true)
}
```

### Config-Driven Main

Use `TemporalMain` for fully config-driven applications:

```kotlin
fun main(args: Array<String>) {
    TemporalMain.main(args)
}
```

With CLI arguments:

```bash
java -jar myapp.jar -config=/path/to/config.yaml
```

## Modules

Organize configuration into reusable modules.

### Defining Modules

```kotlin
// In ModulesKt.kt
fun TemporalApplication.ordersModule() {
    install(SerializationPlugin) {
        json { ignoreUnknownKeys = true }
    }

    taskQueue("orders-queue") {
        maxConcurrentWorkflows = 100
        workflow<OrderWorkflow>()
        activity(OrderActivity())
    }
}

fun TemporalApplication.paymentsModule() {
    taskQueue("payments-queue") {
        maxConcurrentActivities = 50
        workflow<PaymentWorkflow>()
        activity(PaymentActivity())
    }
}
```

### Using Modules

```kotlin
// Programmatically
fun main() {
    embeddedTemporal(module = {
        ordersModule()
        paymentsModule()
    }).start(wait = true)
}
```

Or via YAML:

```yaml
temporal:
  modules:
    - com.example.ModulesKt.ordersModule
    - com.example.ModulesKt.paymentsModule
```

## Plugins

Install plugins at the application or task queue level.

### Application-Level Plugins

```kotlin
embeddedTemporal(module = {
    // Application-wide serialization
    install(SerializationPlugin) {
        json { ignoreUnknownKeys = true }
    }

    // Application-wide compression
    install(CodecPlugin) {
        compression(threshold = 1024)
    }

    taskQueue("my-queue") {
        workflow<MyWorkflow>()
    }
})
```

### Task Queue Plugin Overrides

Task queues can override application-level plugins:

```kotlin
embeddedTemporal(module = {
    install(SerializationPlugin) {
        json { prettyPrint = false }
    }

    taskQueue("debug-queue") {
        // Override with pretty printing for this queue only
        install(SerializationPlugin) {
            json { prettyPrint = true }
        }
        workflow<DebugWorkflow>()
    }

    taskQueue("production-queue") {
        // Uses application-level config (no pretty printing)
        workflow<ProductionWorkflow>()
    }
})
```

## Configuration Reference

### ConnectionConfig

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `target` | String | `"http://localhost:7233"` | Temporal server address |
| `namespace` | String | `"default"` | Temporal namespace |
| `tls` | TlsConfig? | null | TLS configuration |
| `apiKey` | String? | null | API key for Temporal Cloud |

### ShutdownConfig

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `gracePeriodMs` | Long | 10,000 | Grace period for graceful shutdown |
| `forceTimeoutMs` | Long | 5,000 | Timeout after force cancellation |

### TaskQueueBuilder

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `namespace` | String? | null | Override application namespace |
| `maxConcurrentWorkflows` | Int | 200 | Max concurrent workflow executions |
| `maxConcurrentActivities` | Int | 200 | Max concurrent activity executions |
| `shutdownGracePeriodMs` | Long | 10,000 | Shutdown grace period |
| `shutdownForceTimeoutMs` | Long | 5,000 | Force shutdown timeout |
| `maxHeartbeatThrottleIntervalMs` | Long | 60,000 | Max heartbeat throttle interval |
| `defaultHeartbeatThrottleIntervalMs` | Long | 30,000 | Default heartbeat throttle interval |
| `workflowDeadlockTimeoutMs` | Long | 2,000 | Deadlock detection timeout (0 = disabled) |
| `forceExitTimeout` | Duration | 1 minute | Force exit on stuck shutdown |
| `zombieEviction` | ZombieEvictionConfig | (see below) | Zombie thread handling |

### ZombieEvictionConfig

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `maxZombieCount` | Int | 10 | Fatal error threshold (0 = disabled) |
| `gracePeriod` | Duration | 10 seconds | Grace period before counting as zombie |
| `retryInterval` | Duration | 1 second | Initial retry interval |
| `retryMaxDelay` | Duration | 60 seconds | Max retry delay |
| `giveUpAfter` | Duration | 1 hour | Stop retrying after this |
| `shutdownTimeout` | Duration | 30 seconds | Shutdown eviction timeout |

## See Also

- [Authentication Guide](AUTH_SETUP_GUIDE.md) — TLS and API key configuration
- [Serialization Guide](CODECS_AND_SERIALIZATION.md) — Payload serialization and codecs
- [Workflows Guide](WORKFLOWS_IMPERATIVE.md) — Workflow development
- [Activities Guide](ACTIVITIES_IMPERATIVE.md) — Activity development