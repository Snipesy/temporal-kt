# Activities Imperative API

This document describes how to define and use activities in Temporal-KT using the imperative API.

## Quick Example

### Activity Definition

```kotlin
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.activity.activity

class GreetingActivity {
    @Activity("greet")
    fun greet(name: String): String {
        return "Hello, $name!"
    }
}
```

### Register Activities

```kotlin
import com.surrealdev.temporal.application.embeddedTemporal
import com.surrealdev.temporal.application.taskQueue

fun main() {
    embeddedTemporal(configure = {
        connection {
            target = "http://localhost:7233"
            namespace = "default"
        }
    })
    {
        taskQueue("my-task-queue") {
            workflow<MyWorkflow>()
            activity(GreetingActivity())
        }
    }
    .start(wait = true)
}
```

### Call from Workflow

```kotlin
@Workflow("MyWorkflow")
class MyWorkflow {
    @WorkflowRun
    suspend fun run(name: String): String {
        val greeting = workflow()
            .startActivity<String, String>(
                GreetingActivity::greet,
                arg = name,
                scheduleToCloseTimeout = 10.seconds,
            ).result()

        return greeting
    }
}
```

## Activity Definition

### Basic Activity

Activities can be simple functions. The `@Activity` annotation marks a method as an activity. The activity type name defaults to the function name if not specified.

```kotlin
class PaymentActivity {
    // Activity type will be "processPayment"
    @Activity
    fun processPayment(orderId: String, amount: Double): PaymentResult {
        // Call payment gateway
        return PaymentResult(transactionId = "txn-123", success = true)
    }

    // Explicit activity type name
    @Activity("chargeCard")
    fun chargeCustomerCard(cardToken: String, amount: Double): ChargeResult {
        // Process charge
        return ChargeResult(approved = true)
    }
}
```

### Suspend Activities

Activities can be suspend functions for async operations.

```kotlin
class EmailActivity {
    @Activity
    suspend fun sendEmail(to: String, subject: String, body: String): Boolean {
        // Async email sending
        emailClient.send(to, subject, body)
        return true
    }
}
```

### Accessing Activity Context

Use the `activity()` helper to access the `ActivityContext` from within a suspend activity. This provides access to activity info, heartbeat functionality, and cancellation checking.

```kotlin
import com.surrealdev.temporal.activity.activity

class DataProcessingActivity {
    @Activity
    suspend fun processLargeDataset(datasetId: String): ProcessingResult {
        val ctx = activity()

        // Access activity information
        println("Activity ID: ${ctx.info.activityId}")
        println("Attempt: ${ctx.info.attempt}")
        println("Task Queue: ${ctx.info.taskQueue}")

        // Process data...
        return ProcessingResult(success = true)
    }
}
```

## Heartbeats

Heartbeats allow long-running activities to report progress and enable cancellation detection. The Temporal server tracks heartbeats and can cancel activities that stop heartbeating.

### Basic Heartbeat

```kotlin
class BatchProcessingActivity {
    @Activity
    suspend fun processBatch(items: List<Item>): BatchResult {
        val ctx = activity()

        items.forEachIndexed { index, item ->
            // Process item...
            processItem(item)

            // Send heartbeat to indicate progress
            ctx.heartbeat()
        }

        return BatchResult(processed = items.size)
    }
}
```

### Heartbeat with Progress Details

You can include typed progress details with heartbeats. These details are available on retry via `info.heartbeatDetails`.

```kotlin
@Serializable
data class ProcessingProgress(
    val itemsProcessed: Int,
    val lastProcessedId: String,
)

class ReportGenerationActivity {
    @Activity
    suspend fun generateReport(reportId: String, sections: List<Section>): Report {
        val ctx = activity()

        val results = mutableListOf<SectionResult>()

        sections.forEachIndexed { index, section ->
            // Process section...
            val result = processSection(section)
            results.add(result)

            // Heartbeat with progress details
            ctx.heartbeat(ProcessingProgress(
                itemsProcessed = index + 1,
                lastProcessedId = section.id,
            ))
        }

        return Report(reportId, results)
    }
}
```

### Resuming from Heartbeat Details

When an activity retries, the last heartbeat details are available. Use this to resume from where the activity left off.

```kotlin
@Serializable
data class UploadProgress(
    val bytesUploaded: Long,
    val chunkIndex: Int,
)

class FileUploadActivity {
    @Activity
    suspend fun uploadFile(fileId: String, totalChunks: Int): UploadResult {
        val ctx = activity()

        // Check for previous progress on retry
        val previousProgress = ctx.info.heartbeatDetails?.get<UploadProgress>()
        val startChunk = previousProgress?.chunkIndex ?: 0

        println("Starting from chunk $startChunk (attempt ${ctx.info.attempt})")

        for (chunkIndex in startChunk until totalChunks) {
            // Upload chunk...
            uploadChunk(fileId, chunkIndex)

            // Heartbeat with progress for potential resume
            ctx.heartbeat(UploadProgress(
                bytesUploaded = (chunkIndex + 1) * CHUNK_SIZE,
                chunkIndex = chunkIndex + 1,
            ))
        }

        return UploadResult(success = true, totalChunks = totalChunks)
    }
}
```

### Heartbeat Timeout Configuration

Set `heartbeatTimeout` in activity options to enable cancellation detection. If no heartbeat is received within this timeout, the activity can be cancelled.

```kotlin
@Workflow("UploadWorkflow")
class UploadWorkflow {
    @WorkflowRun
    suspend fun run(fileId: String): UploadResult {
        return workflow().startActivity<UploadResult, String>(
            FileUploadActivity::uploadFile,
            arg = fileId,
            scheduleToCloseTimeout = 1.hours,
            heartbeatTimeout = 30.seconds,  // Activity must heartbeat every 30s
        ).result()
    }
}
```

## Activity Info

The `ActivityInfo` provides metadata about the current activity execution.

```kotlin
data class ActivityInfo(
    val activityId: String,           // Unique identifier for this execution
    val activityType: String,         // The activity type name
    val taskQueue: String,            // Task queue this activity runs on
    val attempt: Int,                 // Attempt number (1-based)
    val startTime: Instant,           // When this attempt started
    val deadline: Instant?,           // When this activity will timeout
    val heartbeatDetails: HeartbeatDetails?,  // Details from last heartbeat (on retry)
    val workflowInfo: ActivityWorkflowInfo,   // Parent workflow information
)

data class ActivityWorkflowInfo(
    val workflowId: String,
    val runId: String,
    val workflowType: String,
    val namespace: String,
)
```

### Using Activity Info

```kotlin
class AuditActivity {
    @Activity
    suspend fun auditOperation(operationId: String): AuditResult {
        val ctx = activity()
        val info = ctx.info

        // Log audit trail
        auditLog.record(
            operationId = operationId,
            activityId = info.activityId,
            attempt = info.attempt,
            workflowId = info.workflowInfo.workflowId,
            timestamp = info.startTime,
        )

        // Check if we're running out of time
        val deadline = info.deadline
        if (deadline != null) {
            val remainingTime = deadline.toEpochMilli() - System.currentTimeMillis()
            if (remainingTime < 5000) {
                // Less than 5 seconds remaining, wrap up
                return AuditResult(partial = true)
            }
        }

        // Continue processing...
        return AuditResult(partial = false)
    }
}
```

## Cancellation Handling

Activities can detect and respond to cancellation requests from the workflow.

### Check Cancellation Status

```kotlin
class LongRunningActivity {
    @Activity
    suspend fun processQueue(queueId: String): ProcessResult {
        val ctx = activity()
        var processed = 0

        while (hasMoreItems(queueId)) {
            // Check if cancellation was requested
            if (ctx.isCancellationRequested) {
                // Clean up and return partial result
                cleanup()
                return ProcessResult(processed = processed, cancelled = true)
            }

            // Process next item
            processNextItem(queueId)
            processed++
            ctx.heartbeat(processed)
        }

        return ProcessResult(processed = processed, cancelled = false)
    }
}
```

### Throw on Cancellation

Use `ensureNotCancelled()` to throw an exception if cancellation was requested.

```kotlin
class DataExportActivity {
    @Activity
    suspend fun exportData(exportId: String): ExportResult {
        val ctx = activity()

        // This throws ActivityCancelledException.Cancelled if cancelled
        ctx.ensureNotCancelled()

        val data = fetchData(exportId)

        ctx.ensureNotCancelled()

        val file = writeToFile(data)

        return ExportResult(filePath = file.path)
    }
}
```

### Activity-Side Cancellation Exceptions

These exceptions are thrown within activity code (from `com.surrealdev.temporal.activity`):

```kotlin
sealed class ActivityCancelledException : RuntimeException {
    class NotFound      // Activity no longer exists on server
    class Cancelled     // Explicitly cancelled by workflow
    class TimedOut      // Activity exceeded timeout
    class WorkerShutdown // Worker is shutting down
    class Paused        // Activity was paused
    class Reset         // Activity was reset
}
```

### Workflow-Side Activity Exceptions

These exceptions are thrown when awaiting activity results (from `com.surrealdev.temporal.workflow`):

```kotlin
sealed class ActivityException : RuntimeException {
    // Activity failed with application error
    class ActivityFailureException(
        val failureType: String,
        val retryState: ActivityRetryState,
        val applicationFailure: ApplicationFailure?,
    )

    // Activity timed out
    class ActivityTimeoutException(
        val timeoutType: ActivityTimeoutType,  // SCHEDULE_TO_START, START_TO_CLOSE, etc.
    )

    // Activity was cancelled
    class ActivityCancelledException
}
```

### Workflow-Side Cancellation

```kotlin
@Workflow("CancellableWorkflow")
class CancellableWorkflow {
    @WorkflowRun
    suspend fun run(): String {
        val handle = workflow().startActivity<String>(
            activityType = "longRunningTask",
            scheduleToCloseTimeout = 10.minutes,
            heartbeatTimeout = 30.seconds,
            cancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
        )

        // Wait a bit then cancel
        workflow().sleep(5.seconds)
        handle.cancel()

        return try {
            handle.result()
        } catch (e: com.surrealdev.temporal.workflow.ActivityCancelledException) {
            "Activity was cancelled"
        } catch (e: ActivityTimeoutException) {
            "Activity timed out: ${e.timeoutType}"
        } catch (e: ActivityFailureException) {
            "Activity failed: ${e.applicationFailure?.message}"
        }
    }
}
```

## Activity Options

### ActivityOptions (Remote Activities)

```kotlin
data class ActivityOptions(
    val startToCloseTimeout: Duration?,      // Max time for single attempt
    val scheduleToCloseTimeout: Duration?,   // Max total time (including retries)
    val scheduleToStartTimeout: Duration?,   // Max time until worker picks up
    val heartbeatTimeout: Duration?,         // Max time between heartbeats
    val activityId: String?,                 // Custom ID (auto-generated if null)
    val taskQueue: String?,                  // Override task queue
    val retryPolicy: RetryPolicy?,           // Custom retry policy
    val cancellationType: ActivityCancellationType,  // How to handle cancellation
    val priority: Int,                       // Scheduling priority (0-100)
    val disableEagerExecution: Boolean,      // Disable eager execution
)
```

### Timeout Options Explained

```kotlin
@Workflow("TimeoutExampleWorkflow")
class TimeoutExampleWorkflow {
    @WorkflowRun
    suspend fun run(): String {
        return workflow().startActivity<String>(
            activityType = "myActivity",

            // Max time for a single execution attempt
            // If exceeded, activity is retried (if retries remain)
            startToCloseTimeout = 30.seconds,

            // Max total time from scheduling to completion
            // Includes all retry attempts
            scheduleToCloseTimeout = 5.minutes,

            // Max time waiting for a worker to pick up the task
            // Non-retryable if exceeded
            scheduleToStartTimeout = 1.minutes,

            // Max time between heartbeats
            // Required for cancellation detection
            heartbeatTimeout = 10.seconds,
        ).result()
    }
}
```

### Retry Policy

```kotlin
data class RetryPolicy(
    val initialInterval: Duration,       // First retry delay (default: 1s)
    val maximumInterval: Duration?,      // Max retry delay
    val backoffCoefficient: Double,      // Multiplier for each retry (default: 2.0)
    val maximumAttempts: Int,            // Max attempts (0 = unlimited)
    val nonRetryableErrorTypes: List<String>,  // Errors that shouldn't retry
)
```

### Using Retry Policy

```kotlin
@Workflow("RetryExampleWorkflow")
class RetryExampleWorkflow {
    @WorkflowRun
    suspend fun run(): String {
        return workflow().startActivity<String>(
            activityType = "unreliableService",
            scheduleToCloseTimeout = 10.minutes,
            retryPolicy = RetryPolicy(
                initialInterval = 1.seconds,
                maximumInterval = 60.seconds,
                backoffCoefficient = 2.0,
                maximumAttempts = 5,
                nonRetryableErrorTypes = listOf(
                    "ValidationException",
                    "AuthenticationException",
                ),
            ),
        ).result()
    }
}
```

## Local Activities

Local activities run in the same worker process as the workflow. They're suitable for short operations that don't need server-side scheduling.

### When to Use Local Activities

- Short operations (< 1 minute recommended)
- No need for heartbeats
- Want to avoid server round-trip latency
- Operations that don't need independent retry visibility

### LocalActivityOptions

```kotlin
data class LocalActivityOptions(
    val startToCloseTimeout: Duration?,
    val scheduleToCloseTimeout: Duration?,
    val scheduleToStartTimeout: Duration?,
    val activityId: String?,
    val retryPolicy: RetryPolicy?,
    val localRetryThreshold: Duration,  // If backoff exceeds, schedules timer (default: 1min)
    val cancellationType: ActivityCancellationType,
)
```

### Starting Local Activities

```kotlin
@Workflow("LocalActivityWorkflow")
class LocalActivityWorkflow {
    @WorkflowRun
    suspend fun run(): String {
        // Simple local activity
        val result = workflow().startLocalActivity<String>(
            activityType = "quickValidation",
            startToCloseTimeout = 10.seconds,
        ).result()

        return result
    }
}
```

### Parallel Local Activities

```kotlin
@Workflow("ParallelLocalWorkflow")
class ParallelLocalWorkflow {
    @WorkflowRun
    suspend fun run(): List<String> {
        val ctx = workflow()

        // Start multiple local activities
        val handles = listOf("task1", "task2", "task3").map { taskId ->
            ctx.startLocalActivity<String, String>(
                activityType = "processTask",
                arg = taskId,
                startToCloseTimeout = 10.seconds,
            )
        }

        // Wait for all to complete
        return handles.map { it.result() }
    }
}
```

### Local vs Remote Activities Comparison

| Feature | Remote Activity | Local Activity |
|---------|-----------------|----------------|
| Heartbeats | Yes | No |
| Server Scheduling | Yes | No (in-process) |
| Retry Visibility | Full history | Markers only |
| Cancellation | Via heartbeat | Requires polling |
| Best For | Long operations | Quick operations |
| Default Cancel Type | TRY_CANCEL | WAIT_CANCELLATION_COMPLETED |

## Calling Activities from Workflows

### Using Function Reference

```kotlin
@Workflow("FunctionRefWorkflow")
class FunctionRefWorkflow {
    @WorkflowRun
    suspend fun run(name: String): String {
        // Type parameters: <ReturnType, ArgType>
        return workflow().startActivity<String, String>(
            GreetingActivity::greet,
            arg = name,
            scheduleToCloseTimeout = 10.seconds,
        ).result()
    }
}
```

### Using Activity Type String

```kotlin
@Workflow("StringTypeWorkflow")
class StringTypeWorkflow {
    @WorkflowRun
    suspend fun run(name: String): String {
        return workflow().startActivity<String, String>(
            activityType = "greet",
            arg = name,
            scheduleToCloseTimeout = 10.seconds,
        ).result()
    }
}
```

### Multiple Arguments

```kotlin
@Workflow("MultiArgWorkflow")
class MultiArgWorkflow {
    @WorkflowRun
    suspend fun run(): PaymentResult {
        // Type parameters: <ReturnType, Arg1Type, Arg2Type>
        return workflow().startActivity<PaymentResult, String, Double>(
            activityType = "processPayment",
            arg1 = "order-123",
            arg2 = 99.99,
            scheduleToCloseTimeout = 30.seconds,
        ).result()
    }
}
```

### Using Full ActivityOptions

```kotlin
@Workflow("FullOptionsWorkflow")
class FullOptionsWorkflow {
    @WorkflowRun
    suspend fun run(): String {
        return workflow().startActivity<String, String>(
            activityType = "complexOperation",
            arg = "input",
            options = ActivityOptions(
                startToCloseTimeout = 30.seconds,
                scheduleToCloseTimeout = 5.minutes,
                heartbeatTimeout = 10.seconds,
                taskQueue = "special-queue",
                retryPolicy = RetryPolicy(
                    maximumAttempts = 3,
                ),
                cancellationType = ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
            ),
        ).result()
    }
}
```

## Logging

Use the `logger()` extension function for SLF4J logging with automatic MDC context.

```kotlin
import com.surrealdev.temporal.activity.logger

class LoggingActivity {
    @Activity
    suspend fun processOrder(orderId: String): OrderResult {
        val ctx = activity()
        val log = ctx.logger()

        // MDC automatically includes: activityId, activityType,
        // workflowId, runId, taskQueue, namespace
        log.info("Processing order: {}", orderId)

        try {
            val result = doProcessing(orderId)
            log.info("Order processed successfully")
            return result
        } catch (e: Exception) {
            log.error("Failed to process order", e)
            throw e
        }
    }
}
```
