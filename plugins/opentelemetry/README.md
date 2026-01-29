# OpenTelemetry Plugin for Temporal KT

Adds observability to your Temporal workflows and activities through distributed tracing, metrics, and log correlation.

## Quick Start

```kotlin
fun main() {
    val app = embeddedTemporal(
        module = {
            // Install the OpenTelemetry plugin
            install(OpenTelemetryPlugin) {
                tracerName = "my-service"
            }

            taskQueue("my-queue") {
                workflow(OrderWorkflow())
                activity(PaymentActivity())
            }
        }
    )

    app.start(wait = true)
}
```

That's it! Your workflows and activities will now emit spans and metrics.

## Configuration

```kotlin
install(OpenTelemetryPlugin) {
    // OpenTelemetry instance (defaults to GlobalOpenTelemetry)
    openTelemetry = myOtel

    // Tracer/meter name (identifies this instrumentation)
    tracerName = "my-service"
    tracerVersion = "1.0.0"  // optional

    // Feature toggles (all default to true)
    enableWorkflowSpans = true
    enableActivitySpans = true
    enableMdcIntegration = true
    enableMetrics = true
}
```

## Using the Logger

The SDK provides convenient logger extensions that automatically use the workflow/activity type as the logger name:

```kotlin
@Workflow("OrderWorkflow")
class OrderWorkflow {
    @WorkflowRun
    suspend fun run(orderId: String): String {
        val log = workflow().logger()  // Logger named "temporal.workflow.OrderWorkflow"
        log.info("Processing order: {}", orderId)

        // ... workflow logic
        return "completed"
    }
}

class PaymentActivity {
    @Activity("chargeCard")
    suspend fun ActivityContext.charge(amount: Int): Boolean {
        val log = logger()  // Logger named "temporal.activity.chargeCard"
        log.info("Charging {} cents", amount)

        // ... activity logic
        return true
    }
}
```

MDC values are automatically populated:
- **Workflows**: `workflowId`, `runId`, `workflowType`, `taskQueue`, `namespace`
- **Activities**: `activityId`, `activityType`, `workflowId`, `runId`, `taskQueue`, `namespace`

## Current Limitations

This plugin is a **first iteration** with some important limitations compared to official Temporal SDK OpenTelemetry implementations:

* No cross service (i.e. workflow to activity) context propagation
* Plugins currently have limited hooks / interceptors
