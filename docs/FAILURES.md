# Failure Handling

This document describes how to throw and handle failures in Temporal-KT activities and workflows.

## Quick Example

### Throwing from an Activity

```kotlin
import com.surrealdev.temporal.common.exceptions.ApplicationFailure

class PaymentActivity {
    @Activity("processPayment")
    fun processPayment(cardNumber: String, amount: Double): Receipt {
        // Non-retryable: invalid input, no point retrying
        if (!isValidCard(cardNumber)) {
            throw ApplicationFailure.nonRetryable(
                message = "Invalid card number format",
                type = "ValidationError",
            )
        }

        // Retryable: temporary issue, worth retrying
        if (isGatewayUnavailable()) {
            throw ApplicationFailure.failure(
                message = "Payment gateway temporarily unavailable",
                type = "GatewayError",
            )
        }

        return processCard(cardNumber, amount)
    }
}
```

### Catching in a Workflow

```kotlin
@Workflow("PaymentWorkflow")
class PaymentWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(cardNumber: String, amount: Double): String {
        return try {
            startActivity(
                activityType = "processPayment",
                arg1 = cardNumber,
                arg2 = amount,
                options = ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    retryPolicy = RetryPolicy(maximumAttempts = 3),
                ),
            ).result<Receipt>()
            "Payment successful"
        } catch (e: WorkflowActivityFailureException) {
            val failure = e.applicationFailure
            when (failure?.type) {
                "ValidationError" -> "Invalid card: ${failure.message}"
                "GatewayError" -> "Payment failed after retries"
                else -> "Unknown error: ${e.message}"
            }
        }
    }
}
```

### Catching on the Client

```kotlin
try {
    val result = handle.result<String>()
} catch (e: ClientWorkflowFailedException) {
    val failure = e.applicationFailure
    when (failure?.type) {
        "ValidationError" -> println("Bad input: ${failure.message}")
        else -> println("Workflow failed: ${e.message}")
    }
} catch (e: ClientWorkflowCancelledException) {
    println("Workflow was cancelled")
}
```

## Exception Hierarchy

All exceptions in the SDK organized by where they are used.

```
RuntimeException
├── TemporalRuntimeException (sealed)                      [com.surrealdev.temporal.common.exceptions]
│   │
│   ├── ApplicationFailure                                 [common - throw & receive side]
│   │
│   ├── WorkflowActivityException (sealed)                 [workflow code - catching activity results]
│   │   ├── WorkflowActivityFailureException               ← activity failed with error
│   │   ├── WorkflowActivityTimeoutException               ← activity timed out
│   │   └── WorkflowActivityCancelledException             ← activity was cancelled
│   │
│   ├── ExternalWorkflowException (sealed)                 [workflow code - external workflow operations]
│   │   ├── SignalExternalWorkflowFailedException          ← signal delivery failed
│   │   └── CancelExternalWorkflowFailedException          ← cancel request failed
│   │
│   └── ClientWorkflowException (sealed)                   [client code - waiting for workflow results]
│       ├── ClientWorkflowFailedException                  ← workflow execution failed
│       ├── ClientWorkflowCancelledException               ← workflow was cancelled
│       ├── ClientWorkflowTerminatedException              ← workflow was terminated
│       ├── ClientWorkflowTimedOutException                ← workflow execution timed out
│       ├── ClientWorkflowResultTimeoutException           ← client wait timed out
│       ├── ClientWorkflowNotFoundException                ← workflow not found
│       ├── ClientWorkflowAlreadyExistsException           ← workflow ID already exists
│       ├── ClientWorkflowUpdateFailedException            ← workflow update failed
│       └── ClientWorkflowQueryRejectedException           ← workflow query rejected
│
├── ChildWorkflowException (sealed)                        [workflow code - catching child workflow results]
│   ├── ChildWorkflowFailureException                      ← child workflow failed
│   ├── ChildWorkflowCancelledException                    ← child workflow was cancelled
│   └── ChildWorkflowStartFailureException                 ← child workflow failed to start
│
├── TemporalCoreException                                  [internal - Rust FFI bridge error]
│
├── DuplicatePluginException (IllegalStateException)       [setup - duplicate plugin installed]
└── MissingPluginException (IllegalStateException)         [setup - required plugin missing]

CancellationException
└── TemporalCancellationException (sealed)                 [com.surrealdev.temporal.common.exceptions]
    ├── ActivityCancelledException (sealed)                 [activity code - detecting cancellation]
    │   ├── ActivityCancelledException.NotFound             ← activity no longer exists on server
    │   ├── ActivityCancelledException.Cancelled            ← explicitly cancelled by workflow/user
    │   ├── ActivityCancelledException.TimedOut             ← activity exceeded timeout
    │   ├── ActivityCancelledException.WorkerShutdown       ← worker shutting down
    │   ├── ActivityCancelledException.Paused               ← activity was paused
    │   └── ActivityCancelledException.Reset                ← activity was reset
    │
    ├── WorkflowCancelledException                         [workflow code - workflow cancellation signal]
    ├── WorkflowConditionTimeoutException                  [workflow code - awaitCondition() timed out]
    └── WorkflowDeadlockException                          [internal - workflow didn't yield]
```

## ApplicationFailure

`ApplicationFailure` is the exception you throw from activities (or workflows) to signal application-level failures. It provides control over retry behavior. The same type is also used on the receive side when inspecting failures from activities or child workflows.

### Import

```kotlin
import com.surrealdev.temporal.common.exceptions.ApplicationFailure
import com.surrealdev.temporal.common.exceptions.ApplicationErrorCategory
```

### Factory Methods

#### Retryable Failure

Creates a failure that will be retried according to the retry policy.

```kotlin
throw ApplicationFailure.failure(
    message = "Temporary error occurred",
    type = "TemporaryError",
)
```

#### Non-Retryable Failure

Creates a failure that stops retries immediately, regardless of retry policy.

```kotlin
throw ApplicationFailure.nonRetryable(
    message = "Invalid input provided",
    type = "ValidationError",
)
```

#### Failure with Custom Retry Delay

Creates a retryable failure with an explicit delay before the next attempt, overriding the calculated backoff.

```kotlin
throw ApplicationFailure.failureWithDelay(
    message = "Rate limited, retry after delay",
    nextRetryDelay = 30.seconds,
    type = "RateLimitError",
)
```

### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `message` | `String` | The error message |
| `type` | `String` | Error type/category (default: "ApplicationFailure") |
| `details` | `List<String>` | Additional string details for debugging |
| `category` | `ApplicationErrorCategory` | Affects logging/metrics behavior |
| `cause` | `Throwable?` | Optional underlying cause |

### Error Type

The `type` parameter categorizes your error. It's used for:

1. **Workflow error handling** - Match specific error types in catch blocks
2. **Non-retryable matching** - Configure `nonRetryableErrorTypes` in retry policy

```kotlin
// Activity throws typed error
throw ApplicationFailure.nonRetryable(
    message = "User not found",
    type = "NotFoundError",
)

// Workflow handles by type
catch (e: WorkflowActivityFailureException) {
    when (e.applicationFailure?.type) {
        "NotFoundError" -> createUser()
        "ValidationError" -> rejectRequest()
        else -> escalate()
    }
}
```

### Non-Retryable Error Types in Retry Policy

You can also make errors non-retryable by matching their type in the retry policy:

```kotlin
startActivity(
    activityType = "myActivity",
    options = ActivityOptions(
        startToCloseTimeout = 30.seconds,
        retryPolicy = RetryPolicy(
            maximumAttempts = 5,
            nonRetryableErrorTypes = listOf(
                "ValidationError",
                "AuthenticationError",
                "NotFoundError",
            ),
        ),
    ),
)
```

### Details

Include additional context as string details:

```kotlin
throw ApplicationFailure.nonRetryable(
    message = "Validation failed",
    type = "ValidationError",
    details = listOf(
        "field=email",
        "reason=invalid format",
        "value=not-an-email",
    ),
)

// Or using vararg syntax
throw ApplicationFailure.nonRetryable(
    message = "Multiple validation errors",
    type = "ValidationError",
    "field=email", "field=phone", "field=address",
)
```

### Error Category

The `ApplicationErrorCategory` affects how errors are logged and metered:

```kotlin
enum class ApplicationErrorCategory {
    UNSPECIFIED,  // Default - normal error logging and metrics
    BENIGN,       // Expected business error - DEBUG logging, no error metrics
}
```

Use `BENIGN` for expected business outcomes that aren't operational errors:

```kotlin
// "User not found" is a valid business case, not an error
throw ApplicationFailure.nonRetryable(
    message = "User not found",
    type = "NotFoundError",
    category = ApplicationErrorCategory.BENIGN,
)

// "Insufficient balance" is expected, not an operational failure
throw ApplicationFailure.nonRetryable(
    message = "Insufficient balance for transfer",
    type = "InsufficientFundsError",
    category = ApplicationErrorCategory.BENIGN,
)
```

## Catching Activity Failures (Workflow Side)

When an activity fails, the workflow receives an exception from the `WorkflowActivityException` sealed hierarchy.

### WorkflowActivityException

```kotlin
sealed class WorkflowActivityException : TemporalRuntimeException {
    val activityType: String
    val activityId: String
}
```

| Subclass | When | Key Properties |
|----------|------|----------------|
| `WorkflowActivityFailureException` | Activity failed with an error | `failureType`, `retryState`, `applicationFailure` |
| `WorkflowActivityTimeoutException` | Activity timed out | `timeoutType` |
| `WorkflowActivityCancelledException` | Activity was cancelled | — |

### ActivityRetryState

Indicates why retries stopped:

```kotlin
enum class ActivityRetryState {
    UNSPECIFIED,
    IN_PROGRESS,
    NON_RETRYABLE_FAILURE,      // ApplicationFailure.nonRetryable() or matching type
    TIMEOUT,
    MAXIMUM_ATTEMPTS_REACHED,   // Hit maximumAttempts limit
    RETRY_POLICY_NOT_SET,
    INTERNAL_SERVER_ERROR,
    CANCEL_REQUESTED,
}
```

### ActivityTimeoutType

```kotlin
enum class ActivityTimeoutType {
    SCHEDULE_TO_START,   // Waited too long for worker to pick up
    START_TO_CLOSE,      // Single attempt took too long
    SCHEDULE_TO_CLOSE,   // Total time (including retries) exceeded
    HEARTBEAT,           // No heartbeat received within timeout
}
```

### Handling Different Failure Types

```kotlin
try {
    activityHandle.result<String>()
} catch (e: WorkflowActivityFailureException) {
    // Activity failed with application error
    val failure = e.applicationFailure
    when (e.retryState) {
        ActivityRetryState.NON_RETRYABLE_FAILURE ->
            "Permanent failure: ${failure?.message}"
        ActivityRetryState.MAXIMUM_ATTEMPTS_REACHED ->
            "Failed after max retries: ${failure?.message}"
        else ->
            "Failed: ${failure?.message}"
    }
} catch (e: WorkflowActivityTimeoutException) {
    when (e.timeoutType) {
        ActivityTimeoutType.START_TO_CLOSE -> "Activity took too long"
        ActivityTimeoutType.HEARTBEAT -> "Activity stopped heartbeating"
        ActivityTimeoutType.SCHEDULE_TO_START -> "No worker available"
        else -> "Timeout: ${e.timeoutType}"
    }
} catch (e: WorkflowActivityCancelledException) {
    "Activity was cancelled"
}
```

### Inspecting ApplicationFailure

On the receive side, `ApplicationFailure` is extracted from the cause chain of `WorkflowActivityFailureException`:

```kotlin
catch (e: WorkflowActivityFailureException) {
    val failure = e.applicationFailure  // ApplicationFailure?
    if (failure != null) {
        println("Type: ${failure.type}")
        println("Message: ${failure.message}")
        println("Non-retryable: ${failure.isNonRetryable}")
        println("Category: ${failure.category}")
        println("Has details: ${failure.encodedDetails != null}")
    }
}
```

## Catching Child Workflow Failures (Workflow Side)

When a child workflow fails, the workflow receives an exception from the `ChildWorkflowException` sealed hierarchy.

### ChildWorkflowException

```kotlin
sealed class ChildWorkflowException : RuntimeException  // not yet migrated to TemporalRuntimeException
```

| Subclass | When | Key Properties |
|----------|------|----------------|
| `ChildWorkflowFailureException` | Child workflow execution failed | `childWorkflowId`, `childWorkflowType`, `failure`, `applicationFailure` |
| `ChildWorkflowCancelledException` | Child workflow was cancelled | `childWorkflowId`, `childWorkflowType`, `failure` |
| `ChildWorkflowStartFailureException` | Child workflow failed to start | `childWorkflowId`, `childWorkflowType`, `startFailureCause` |

### StartChildWorkflowFailureCause

```kotlin
enum class StartChildWorkflowFailureCause {
    WORKFLOW_ALREADY_EXISTS,
    UNKNOWN,
}
```

### Handling Child Workflow Failures

```kotlin
try {
    val result = childHandle.result<String>()
} catch (e: ChildWorkflowFailureException) {
    val failure = e.applicationFailure
    println("Child workflow failed: ${failure?.type} - ${failure?.message}")
} catch (e: ChildWorkflowCancelledException) {
    println("Child workflow was cancelled")
} catch (e: ChildWorkflowStartFailureException) {
    when (e.startFailureCause) {
        StartChildWorkflowFailureCause.WORKFLOW_ALREADY_EXISTS ->
            println("Workflow ${e.childWorkflowId} already exists")
        StartChildWorkflowFailureCause.UNKNOWN ->
            println("Failed to start child workflow")
    }
}
```

## External Workflow Operation Failures (Workflow Side)

When signaling or cancelling an external workflow fails, the workflow receives an exception from the `ExternalWorkflowException` sealed hierarchy.

### ExternalWorkflowException

```kotlin
sealed class ExternalWorkflowException : TemporalRuntimeException
```

| Subclass | When | Key Properties |
|----------|------|----------------|
| `SignalExternalWorkflowFailedException` | Signal delivery to external workflow failed | `targetWorkflowId`, `signalName`, `failure` |
| `CancelExternalWorkflowFailedException` | Cancel request to external workflow failed | `targetWorkflowId`, `failure` |

### Handling External Workflow Operation Failures

```kotlin
// Signaling
try {
    externalHandle.signal("mySignal", payload)
} catch (e: SignalExternalWorkflowFailedException) {
    println("Failed to signal ${e.targetWorkflowId}: ${e.message}")
}

// Cancelling
try {
    externalHandle.cancel()
} catch (e: CancelExternalWorkflowFailedException) {
    println("Failed to cancel ${e.targetWorkflowId}: ${e.message}")
}

// Exhaustive handling
catch (e: ExternalWorkflowException) {
    when (e) {
        is SignalExternalWorkflowFailedException ->
            println("Signal '${e.signalName}' failed for ${e.targetWorkflowId}")
        is CancelExternalWorkflowFailedException ->
            println("Cancel failed for ${e.targetWorkflowId}")
    }
}
```

## Client-Side Exceptions

When waiting for workflow results from the client, exceptions come from the `ClientWorkflowException` sealed hierarchy.

### ClientWorkflowException

```kotlin
sealed class ClientWorkflowException : TemporalRuntimeException
```

| Subclass | When | Key Properties |
|----------|------|----------------|
| `ClientWorkflowFailedException` | Workflow execution failed | `workflowId`, `runId`, `workflowType`, `failure`, `applicationFailure` |
| `ClientWorkflowCancelledException` | Workflow was cancelled | `workflowId`, `runId` |
| `ClientWorkflowTerminatedException` | Workflow was terminated | `workflowId`, `runId`, `reason` |
| `ClientWorkflowTimedOutException` | Workflow execution timed out | `workflowId`, `runId`, `timeoutType` |
| `ClientWorkflowResultTimeoutException` | Client's wait timed out (not the workflow) | `workflowId`, `runId` |
| `ClientWorkflowNotFoundException` | Workflow not found | `workflowId`, `runId` |
| `ClientWorkflowAlreadyExistsException` | Workflow with same ID exists | `workflowId`, `existingRunId` |
| `ClientWorkflowUpdateFailedException` | Workflow update failed | `workflowId`, `runId`, `updateName`, `updateId` |
| `ClientWorkflowQueryRejectedException` | Workflow query rejected | `workflowId`, `runId`, `queryType`, `status` |

### WorkflowTimeoutType

```kotlin
enum class WorkflowTimeoutType {
    WORKFLOW_EXECUTION_TIMEOUT,
}
```

### Handling Client Exceptions

```kotlin
try {
    val result = handle.result<String>()
} catch (e: ClientWorkflowFailedException) {
    // Inspect the application failure
    val failure = e.applicationFailure
    println("Workflow failed: ${failure?.type} - ${failure?.message}")
    // Full cause chain is available
    println("Cause: ${e.cause}")
} catch (e: ClientWorkflowCancelledException) {
    println("Workflow ${e.workflowId} was cancelled")
} catch (e: ClientWorkflowTerminatedException) {
    println("Workflow ${e.workflowId} was terminated: ${e.reason}")
} catch (e: ClientWorkflowTimedOutException) {
    println("Workflow ${e.workflowId} timed out: ${e.timeoutType}")
} catch (e: ClientWorkflowResultTimeoutException) {
    println("Timed out waiting for result from ${e.workflowId}")
}
```

## Activity-Side Cancellation

When an activity detects that it has been cancelled (via heartbeating or `ensureNotCancelled()`), it receives an `ActivityCancelledException`.

### ActivityCancelledException

```kotlin
sealed class ActivityCancelledException : TemporalCancellationException
```

| Subclass | When |
|----------|------|
| `ActivityCancelledException.NotFound` | Activity no longer exists on the server |
| `ActivityCancelledException.Cancelled` | Explicitly cancelled by the workflow or user |
| `ActivityCancelledException.TimedOut` | Activity exceeded its timeout |
| `ActivityCancelledException.WorkerShutdown` | Worker is shutting down |
| `ActivityCancelledException.Paused` | Activity was paused |
| `ActivityCancelledException.Reset` | Activity was reset |

### Handling Activity Cancellation

```kotlin
@Activity("longRunning")
suspend fun longRunningActivity(): String {
    val ctx = activity()
    for (i in 0 until 1000) {
        doWork(i)
        ctx.heartbeat(i) // Throws ActivityCancelledException if cancelled
    }
    return "done"
}

// Or check manually
@Activity("manualCheck")
suspend fun manualCheckActivity(): String {
    val ctx = activity()
    try {
        ctx.ensureNotCancelled()
    } catch (e: ActivityCancelledException) {
        when (e) {
            is ActivityCancelledException.TimedOut -> cleanup()
            is ActivityCancelledException.WorkerShutdown -> saveProgress()
            else -> {}
        }
        throw e
    }
    return "done"
}
```

## Workflow-Side Miscellaneous Exceptions

### WorkflowCancelledException

Thrown when a workflow is cancelled. Extends `TemporalCancellationException` (which extends `CancellationException`), so it integrates with Kotlin coroutine cancellation and will **not** be caught by `catch (e: RuntimeException)`.

```kotlin
try {
    someActivity.result<String>()
} catch (e: WorkflowCancelledException) {
    // Perform cleanup before the workflow completes as cancelled
    compensate()
    throw e // Re-throw to let the workflow complete as cancelled
}
```

### WorkflowConditionTimeoutException

Thrown when `WorkflowContext.awaitCondition()` times out. Extends `TemporalCancellationException` (which extends `CancellationException`), so it will **not** be caught by `catch (e: RuntimeException)`.

```kotlin
try {
    awaitCondition(timeout = 30.seconds) { orderApproved }
} catch (e: WorkflowConditionTimeoutException) {
    println("Condition timed out after ${e.timeout}")
    // Handle timeout as business logic
}
```

## Throwing from Workflows

`ApplicationFailure` can also be thrown from workflows to fail the workflow execution:

```kotlin
@Workflow("ValidationWorkflow")
class ValidationWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(input: String): String {
        if (input.isBlank()) {
            throw ApplicationFailure.nonRetryable(
                message = "Input cannot be blank",
                type = "ValidationError",
            )
        }

        // Process input...
        return "Processed: $input"
    }
}
```

## Common Patterns

### Validation Errors (Non-Retryable)

```kotlin
@Activity("validateOrder")
fun validateOrder(order: Order): ValidationResult {
    val errors = mutableListOf<String>()

    if (order.items.isEmpty()) {
        errors.add("Order must have at least one item")
    }
    if (order.total <= 0) {
        errors.add("Order total must be positive")
    }

    if (errors.isNotEmpty()) {
        throw ApplicationFailure.nonRetryable(
            message = "Order validation failed",
            type = "ValidationError",
            details = errors,
        )
    }

    return ValidationResult(valid = true)
}
```

### External Service Errors (Retryable)

```kotlin
@Activity("callExternalApi")
suspend fun callExternalApi(request: ApiRequest): ApiResponse {
    return try {
        httpClient.post(request)
    } catch (e: IOException) {
        // Network error - should retry
        throw ApplicationFailure.failure(
            message = "Failed to reach external API: ${e.message}",
            type = "NetworkError",
            cause = e,
        )
    } catch (e: HttpException) {
        when (e.statusCode) {
            429 -> {
                // Rate limited - retry with delay
                val retryAfter = e.headers["Retry-After"]?.toLongOrNull() ?: 30
                throw ApplicationFailure.failureWithDelay(
                    message = "Rate limited by external API",
                    nextRetryDelay = retryAfter.seconds,
                    type = "RateLimitError",
                )
            }
            in 500..599 -> {
                // Server error - should retry
                throw ApplicationFailure.failure(
                    message = "External API server error: ${e.statusCode}",
                    type = "ServerError",
                )
            }
            in 400..499 -> {
                // Client error - don't retry
                throw ApplicationFailure.nonRetryable(
                    message = "External API client error: ${e.statusCode}",
                    type = "ClientError",
                )
            }
            else -> throw e
        }
    }
}
```

### Business Rule Violations (Benign)

```kotlin
@Activity("transferFunds")
fun transferFunds(from: String, to: String, amount: Double): TransferResult {
    val balance = getBalance(from)

    if (balance < amount) {
        // This is an expected business case, not an error
        throw ApplicationFailure.nonRetryable(
            message = "Insufficient funds: balance=$balance, requested=$amount",
            type = "InsufficientFundsError",
            category = ApplicationErrorCategory.BENIGN,
        )
    }

    return executeTransfer(from, to, amount)
}
```

### Partial Progress with Resume

```kotlin
@Activity("processBatch")
suspend fun processBatch(items: List<Item>): BatchResult {
    val ctx = activity()
    val processed = mutableListOf<String>()

    for ((index, item) in items.withIndex()) {
        try {
            processItem(item)
            processed.add(item.id)
            ctx.heartbeat(index)
        } catch (e: Exception) {
            // Include progress in error for debugging
            throw ApplicationFailure.failure(
                message = "Failed processing item ${item.id}: ${e.message}",
                type = "ProcessingError",
                details = listOf(
                    "failedAt=$index",
                    "processed=${processed.size}",
                    "total=${items.size}",
                ),
                cause = e,
            )
        }
    }

    return BatchResult(processed)
}
```
