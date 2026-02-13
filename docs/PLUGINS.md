# Plugins and Interceptors

Temporal-Kt uses a plugin system for extending application behavior. Plugins can register
two kinds of extension points:

- **Interceptors** — chain-of-responsibility wrappers around individual operations (signals, queries,
  activities, etc.). Interceptors can modify inputs, modify outputs, measure timing, short-circuit
  execution, or add before/after behavior. **Use interceptors for most use cases.**

- **Observer hooks** — fire-and-forget notifications at boundaries where no interceptor exists:
  application lifecycle events and workflow activation-level events.

## Creating a Plugin

```kotlin
val MyPlugin = createApplicationPlugin(
    name = "MyPlugin",
    createConfiguration = { MyPluginConfig() },
) { config ->
    application {
        onSetup { ctx ->
            println("Application started")
        }
    }

    workflow {
        // Interceptor: wraps each workflow execution
        onExecute { input, proceed ->
            println("Executing workflow: ${input.workflowType}")
            proceed(input)
        }

        // Observer hook: fires once per activation (which may contain many operations)
        onTaskCompleted { ctx ->
            println("Activation took ${ctx.duration}")
        }
    }

    activity {
        // Interceptor: wraps each activity execution
        onExecute { input, proceed ->
            println("Executing activity: ${input.activityType}")
            proceed(input)
        }
    }

    Unit
}

data class MyPluginConfig(
    var enabled: Boolean = true,
)
```

Install it:

```kotlin
val app = TemporalApplication {
    connection { target = "localhost:7233" }
}

app.install(MyPlugin) {
    enabled = true
}
```

## Interceptors

Interceptors are the primary extension mechanism. Each interceptor receives an input and a `proceed`
function to call the next interceptor (or the SDK's default behavior).

```kotlin
// Signature
typealias Interceptor<TInput, TOutput> =
    suspend (input: TInput, proceed: suspend (TInput) -> TOutput) -> TOutput
```

Interceptors fire **per-operation** — once per signal, once per activity schedule, once per query,
etc. They can inspect and modify both the input (before `proceed`) and the output (after `proceed`).

### Workflow Inbound Interceptors

Intercept operations arriving at the workflow from the server.

```kotlin
workflow {
    onExecute { input, proceed ->
        println("Executing workflow: ${input.workflowType}")
        proceed(input)
    }

    onHandleSignal { input, proceed ->
        println("Signal: ${input.signalName}")
        proceed(input)
    }

    onHandleQuery { input, proceed ->
        proceed(input)
    }

    onValidateUpdate { input, proceed ->
        proceed(input)
    }

    onExecuteUpdate { input, proceed ->
        proceed(input)
    }
}
```

**Input types:**

| Interceptor        | Input                  | Key Fields                                                            |
|--------------------|------------------------|-----------------------------------------------------------------------|
| `onExecute`        | `ExecuteWorkflowInput` | `workflowType`, `runId`, `workflowId`, `taskQueue`, `headers`, `args` |
| `onHandleSignal`   | `HandleSignalInput`    | `signalName`, `args`, `runId`, `headers`                              |
| `onHandleQuery`    | `HandleQueryInput`     | `queryType`, `args`, `runId`, `headers`                               |
| `onValidateUpdate` | `ValidateUpdateInput`  | `updateName`, `protocolInstanceId`, `args`, `headers`                 |
| `onExecuteUpdate`  | `ExecuteUpdateInput`   | `updateName`, `protocolInstanceId`, `args`, `headers`                 |

### Workflow Outbound Interceptors

Intercept operations going from workflow code to the SDK.

```kotlin
workflow {
    onScheduleActivity { input, proceed ->
        println("Scheduling activity: ${input.activityType}")
        proceed(input)
    }

    onScheduleLocalActivity { input, proceed ->
        proceed(input)
    }

    onStartChildWorkflow { input, proceed ->
        println("Starting child: ${input.workflowType}")
        proceed(input)
    }

    onSleep { input, proceed ->
        println("Sleeping for: ${input.duration}")
        proceed(input)
    }

    onSignalExternalWorkflow { input, proceed ->
        proceed(input)
    }

    onCancelExternalWorkflow { input, proceed ->
        proceed(input)
    }

    onContinueAsNew { input, proceed ->
        proceed(input)
    }
}
```

**Input types:**

| Interceptor                | Input                       | Key Fields                              |
|----------------------------|-----------------------------|-----------------------------------------|
| `onScheduleActivity`       | `ScheduleActivityInput`     | `activityType`, `args`, `options`       |
| `onScheduleLocalActivity`  | `ScheduleLocalActivityInput`| `activityType`, `args`, `options`       |
| `onStartChildWorkflow`     | `StartChildWorkflowInput`   | `workflowType`, `args`, `options`       |
| `onSleep`                  | `SleepInput`                | `duration`                              |
| `onSignalExternalWorkflow` | `SignalExternalInput`       | `workflowId`, `signalName`, `args`      |
| `onCancelExternalWorkflow` | `CancelExternalInput`       | `workflowId`, `reason`                  |
| `onContinueAsNew`          | `ContinueAsNewInput`        | `options`, `args`                       |

### Activity Interceptors

```kotlin
activity {
    // Inbound: intercept activity execution
    onExecute { input, proceed ->
        println("Executing activity: ${input.activityType}")
        proceed(input)
    }

    // Outbound: intercept heartbeat sending
    onHeartbeat { input, proceed ->
        proceed(input)
    }
}
```

**Input types:**

| Interceptor    | Input                  | Key Fields                                        |
|----------------|------------------------|---------------------------------------------------|
| `onExecute`    | `ExecuteActivityInput` | `activityType`, `activityId`, `workflowId`        |
| `onHeartbeat`  | `HeartbeatInput`       | `details`, `activityType`                         |

### Modifying Inputs

Interceptors can modify the input before passing it along. Input types are data classes,
so use `copy()`:

```kotlin
workflow {
    onScheduleActivity { input, proceed ->
        // Override the activity options
        val modified = input.copy(
            options = input.options.copy(
                scheduleToCloseTimeout = 30.seconds
            )
        )
        proceed(modified)
    }
}
```

### Interceptor Ordering

Interceptors execute in registration order. The first registered interceptor is outermost
(called first, returns last):

```kotlin
workflow {
    onExecute { input, proceed ->
        println("1: before")
        val result = proceed(input)
        println("1: after")
        result
    }

    onExecute { input, proceed ->
        println("2: before")
        val result = proceed(input)
        println("2: after")
        result
    }
}

// Output:
// 1: before
// 2: before
// <workflow executes>
// 2: after
// 1: after
```

## Observer Hooks

Observer hooks are fire-and-forget notifications for boundaries where no interceptor exists.
They cannot modify inputs or outputs.

### Application Lifecycle Hooks

These fire during application and worker lifecycle events. There is no interceptor equivalent
for these — use hooks.

```kotlin
application {
    onSetup { ctx -> }         // After runtime created, before workers start
    onShutdown { ctx -> }      // Before workers stop
    onWorkerStarted { ctx -> } // After each worker starts
    onWorkerStopped { ctx -> } // After each worker stops
}
```

### Workflow Activation Hooks

These fire once per **workflow activation**, not per operation. A single activation can contain
multiple signals, updates, queries, and timer resolutions all processed together. This
activation-level granularity has no interceptor equivalent — individual interceptors like
`onExecute` or `onHandleSignal` fire per-operation within an activation.

Use these for activation-level metrics (e.g., measuring total activation processing time).

```kotlin
workflow {
    onTaskStarted { ctx -> }   // Before dispatching activation
    onTaskCompleted { ctx -> } // After activation completes (includes duration)
    onTaskFailed { ctx -> }    // When activation fails (includes error)
}
```

### Activity Task Hooks

```kotlin
activity {
    onTaskStarted { ctx -> }
    onTaskCompleted { ctx -> }
    onTaskFailed { ctx -> }
}
```

### Client Interceptors

Intercept operations from client code to the Temporal server.

```kotlin
client {
    onStartWorkflow { input, proceed ->
        println("Starting workflow: ${input.workflowType}")
        proceed(input)
    }

    onSignalWorkflow { input, proceed -> proceed(input) }
    onQueryWorkflow { input, proceed -> proceed(input) }
    onStartWorkflowUpdate { input, proceed -> proceed(input) }
    onCancelWorkflow { input, proceed -> proceed(input) }
    onTerminateWorkflow { input, proceed -> proceed(input) }
    onDescribeWorkflow { input, proceed -> proceed(input) }
    onListWorkflows { input, proceed -> proceed(input) }
    onCountWorkflows { input, proceed -> proceed(input) }
    onFetchWorkflowResult { input, proceed -> proceed(input) }
    onFetchWorkflowHistory { input, proceed -> proceed(input) }
}
```

**Input types:**

| Interceptor              | Input                       | Key Fields                                              |
|--------------------------|-----------------------------|---------------------------------------------------------|
| `onStartWorkflow`        | `StartWorkflowInput`        | `workflowType`, `taskQueue`, `workflowId`, `args`       |
| `onSignalWorkflow`       | `SignalWorkflowInput`       | `workflowId`, `runId`, `signalName`, `args`             |
| `onQueryWorkflow`        | `QueryWorkflowInput`        | `workflowId`, `runId`, `queryType`, `args`              |
| `onStartWorkflowUpdate`  | `StartWorkflowUpdateInput`  | `workflowId`, `runId`, `updateName`, `args`             |
| `onCancelWorkflow`       | `CancelWorkflowInput`       | `workflowId`, `runId`                                   |
| `onTerminateWorkflow`    | `TerminateWorkflowInput`    | `workflowId`, `runId`, `reason`                         |
| `onDescribeWorkflow`     | `DescribeWorkflowInput`     | `workflowId`, `runId`                                   |
| `onListWorkflows`        | `ListWorkflowsInput`        | `query`, `pageSize`                                     |
| `onCountWorkflows`       | `CountWorkflowsInput`       | `query`                                                 |
| `onFetchWorkflowResult`  | `FetchWorkflowResultInput`  | `workflowId`, `runId`, `timeout`                        |
| `onFetchWorkflowHistory` | `FetchWorkflowHistoryInput` | `workflowId`, `runId`                                   |

## Standalone Client

Plugins can be installed directly on a standalone `TemporalClient` — no `TemporalApplication` needed.
This is useful for REST servers or other services that only need to start workflows, send signals, etc.

```kotlin
val client = TemporalClient.connect {
    target = "localhost:7233"
    namespace = "default"

    // Serialization and codec plugins work here too
    install(SerializationPlugin) { json { prettyPrint = true } }
    install(CodecPlugin) { compression() }

    // Client interceptors
    install(MyPlugin) { enabled = true }
}
```

Only `client {}` interceptors are meaningful in this context. Scoped plugins (`createScopedPlugin`)
can be installed on standalone clients. Application-only plugins (`createApplicationPlugin`) cannot.

## Scoped Plugins

Plugins created with `createApplicationPlugin` can only be installed at the application level.
Use `createScopedPlugin` to allow installation at both application and task-queue levels:

```kotlin
val MetricsPlugin = createScopedPlugin(
    name = "Metrics",
    createConfiguration = { MetricsConfig() },
) { config ->
    workflow {
        onExecute { input, proceed ->
            val result = proceed(input)
            recordMetric("workflow.completed", input.workflowType)
            result
        }

        onTaskCompleted { ctx ->
            recordMetric("workflow.activation.duration", ctx.duration)
        }
    }

    Unit
}
```

Install at application level (applies to all task queues):

```kotlin
app.install(MetricsPlugin) { enabled = true }
```

Or install at task-queue level (applies to that queue only):

```kotlin
app.taskQueue("orders-queue") {
    install(MetricsPlugin) { enabled = true }
    workflow<OrderWorkflow>()
}
```

Task-queue plugins override application-level plugins with the same key. Interceptor registries
are merged at worker startup: application-level interceptors run before task-queue-level interceptors.

## Manual Plugin Creation

For plugins that need more control over installation:

```kotlin
class AuthPlugin(val config: AuthConfig) {
    companion object : ApplicationPlugin<AuthConfig, AuthPlugin> {
        override val key = AttributeKey<AuthPlugin>("Auth")

        override fun install(
            pipeline: TemporalApplication,
            configure: AuthConfig.() -> Unit,
        ): AuthPlugin {
            val config = AuthConfig().apply(configure)
            val plugin = AuthPlugin(config)

            val builder = createPluginBuilder(pipeline, config, key)

            builder.workflow {
                onExecute { input, proceed ->
                    if (config.requireAuth) {
                        validateHeaders(input.headers)
                    }
                    proceed(input)
                }
            }

            builder.hooks.forEach { it.install(pipeline.hookRegistry) }
            installInterceptors(builder, pipeline)

            return plugin
        }
    }
}
```

## Complete Example

A tracing plugin that uses interceptors for per-operation tracing and hooks for
activation-level metrics:

```kotlin
val TracingPlugin = createApplicationPlugin(
    name = "Tracing",
    createConfiguration = { TracingConfig() },
) { config ->
    application {
        onSetup { ctx ->
            println("[tracing] Application starting")
        }
        onWorkerStarted { ctx ->
            println("[tracing] Worker started: ${ctx.taskQueue}")
        }
    }

    workflow {
        // Interceptors: per-operation tracing
        onExecute { input, proceed ->
            println("[tracing] Workflow ${input.workflowType} started")
            try {
                val result = proceed(input)
                println("[tracing] Workflow ${input.workflowType} completed")
                result
            } catch (e: Exception) {
                println("[tracing] Workflow ${input.workflowType} failed: ${e.message}")
                throw e
            }
        }

        onScheduleActivity { input, proceed ->
            println("[tracing] Scheduling activity: ${input.activityType}")
            proceed(input)
        }

        // Hook: activation-level metric (no interceptor equivalent)
        onTaskCompleted { ctx ->
            println("[tracing] Workflow activation took ${ctx.duration}")
        }
    }

    activity {
        // Interceptor: per-activity tracing
        onExecute { input, proceed ->
            println("[tracing] Activity ${input.activityType} started")
            val result = proceed(input)
            println("[tracing] Activity ${input.activityType} completed")
            result
        }
    }

    Unit
}

data class TracingConfig(
    var verbose: Boolean = false,
)
```

```kotlin
fun main() {
    embeddedTemporal(module = {
        install(TracingPlugin) { verbose = true }

        taskQueue("my-queue") {
            workflow<MyWorkflow>()
            activity(MyActivities())
        }
    }).start(wait = true)
}
```

## See Also

- [Getting Started](GETTING_STARTED.md)
- [Codecs and Serialization](CODECS_AND_SERIALIZATION.md)
- [Testing](TESTING.md)
