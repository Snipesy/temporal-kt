# Failure Handling

This document describes how to throw and handle failures in Temporal-KT activities and workflows.

## Quick Example

### Throwing from an Activity

```kotlin
import com.surrealdev.temporal.common.ApplicationError

class PaymentActivity {
    @Activity("processPayment")
    fun processPayment(cardNumber: String, amount: Double): Receipt {
        // Non-retryable: invalid input, no point retrying
        if (!isValidCard(cardNumber)) {
            throw ApplicationError.nonRetryable(
                message = "Invalid card number format",
                type = "ValidationError",
            )
        }

        // Retryable: temporary issue, worth retrying
        if (isGatewayUnavailable()) {
            throw ApplicationError.failure(
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
        } catch (e: ActivityFailureException) {
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

## ApplicationError

`ApplicationError` is the exception you throw from activities (or workflows) to signal application-level failures. It extends `TemporalFailure` and provides control over retry behavior.

### Import

```kotlin
import com.surrealdev.temporal.common.ApplicationError
import com.surrealdev.temporal.common.ApplicationErrorCategory
```

### Factory Methods

#### Retryable Failure

Creates a failure that will be retried according to the retry policy.

```kotlin
throw ApplicationError.failure(
    message = "Temporary error occurred",
    type = "TemporaryError",
)
```

#### Non-Retryable Failure

Creates a failure that stops retries immediately, regardless of retry policy.

```kotlin
throw ApplicationError.nonRetryable(
    message = "Invalid input provided",
    type = "ValidationError",
)
```

#### Failure with Custom Retry Delay

Creates a retryable failure with an explicit delay before the next attempt, overriding the calculated backoff.

```kotlin
throw ApplicationError.failureWithDelay(
    message = "Rate limited, retry after delay",
    nextRetryDelay = 30.seconds,
    type = "RateLimitError",
)
```

### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `message` | `String` | The error message |
| `type` | `String` | Error type/category (default: "ApplicationError") |
| `details` | `List<String>` | Additional string details for debugging |
| `category` | `ApplicationErrorCategory` | Affects logging/metrics behavior |
| `cause` | `Throwable?` | Optional underlying cause |

### Error Type

The `type` parameter categorizes your error. It's used for:

1. **Workflow error handling** - Match specific error types in catch blocks
2. **Non-retryable matching** - Configure `nonRetryableErrorTypes` in retry policy

```kotlin
// Activity throws typed error
throw ApplicationError.nonRetryable(
    message = "User not found",
    type = "NotFoundError",
)

// Workflow handles by type
catch (e: ActivityFailureException) {
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
throw ApplicationError.nonRetryable(
    message = "Validation failed",
    type = "ValidationError",
    details = listOf(
        "field=email",
        "reason=invalid format",
        "value=not-an-email",
    ),
)

// Or using vararg syntax
throw ApplicationError.nonRetryable(
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
throw ApplicationError.nonRetryable(
    message = "User not found",
    type = "NotFoundError",
    category = ApplicationErrorCategory.BENIGN,
)

// "Insufficient balance" is expected, not an operational failure
throw ApplicationError.nonRetryable(
    message = "Insufficient balance for transfer",
    type = "InsufficientFundsError",
    category = ApplicationErrorCategory.BENIGN,
)
```

## Catching Activity Failures

When an activity fails, the workflow receives an exception from the `ActivityException` hierarchy.

### Exception Hierarchy

```kotlin
sealed class ActivityException : RuntimeException {
    val activityType: String
    val activityId: String
}

class ActivityFailureException : ActivityException {
    val failureType: String                    // e.g., "ApplicationFailure"
    val retryState: ActivityRetryState         // Why retries stopped
    val applicationFailure: ApplicationFailure? // Failure details
}

class ActivityTimeoutException : ActivityException {
    val timeoutType: ActivityTimeoutType       // Which timeout was exceeded
}

class ActivityCancelledException : ActivityException
```

### ActivityRetryState

Indicates why retries stopped:

```kotlin
enum class ActivityRetryState {
    UNSPECIFIED,
    IN_PROGRESS,
    NON_RETRYABLE_FAILURE,      // ApplicationError.nonRetryable() or matching type
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
@Workflow("RobustWorkflow")
class RobustWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        return try {
            startActivity(
                activityType = "riskyOperation",
                options = ActivityOptions(
                    startToCloseTimeout = 30.seconds,
                    heartbeatTimeout = 10.seconds,
                    retryPolicy = RetryPolicy(maximumAttempts = 3),
                ),
            ).result<String>()
        } catch (e: ActivityFailureException) {
            // Activity failed with application error
            val failure = e.applicationFailure
            when (e.retryState) {
                ActivityRetryState.NON_RETRYABLE_FAILURE ->
                    "Permanent failure: ${failure?.message}"
                ActivityRetryState.MAXIMUM_ATTEMPTS_REACHED ->
                    "Failed after ${e.retryState} retries: ${failure?.message}"
                else ->
                    "Failed: ${failure?.message}"
            }
        } catch (e: ActivityTimeoutException) {
            // Activity timed out
            when (e.timeoutType) {
                ActivityTimeoutType.START_TO_CLOSE ->
                    "Activity took too long"
                ActivityTimeoutType.HEARTBEAT ->
                    "Activity stopped heartbeating"
                ActivityTimeoutType.SCHEDULE_TO_START ->
                    "No worker available"
                else ->
                    "Timeout: ${e.timeoutType}"
            }
        } catch (e: ActivityCancelledException) {
            // Activity was cancelled
            "Activity was cancelled"
        }
    }
}
```

### Inspecting ApplicationFailure

The `ApplicationFailure` data class contains details about the failure:

```kotlin
data class ApplicationFailure(
    val type: String,                          // Error type
    val message: String?,                      // Error message
    val nonRetryable: Boolean,                 // Was marked non-retryable
    val details: ByteArray?,                   // Serialized details
    val category: ApplicationErrorCategory,   // UNSPECIFIED or BENIGN
)
```

Example:

```kotlin
catch (e: ActivityFailureException) {
    val failure = e.applicationFailure
    if (failure != null) {
        println("Type: ${failure.type}")
        println("Message: ${failure.message}")
        println("Non-retryable: ${failure.nonRetryable}")
        println("Category: ${failure.category}")
    }
}
```

## Throwing from Workflows

`ApplicationError` can also be thrown from workflows to fail the workflow execution:

```kotlin
@Workflow("ValidationWorkflow")
class ValidationWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(input: String): String {
        if (input.isBlank()) {
            throw ApplicationError.nonRetryable(
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
        throw ApplicationError.nonRetryable(
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
        throw ApplicationError.failure(
            message = "Failed to reach external API: ${e.message}",
            type = "NetworkError",
            cause = e,
        )
    } catch (e: HttpException) {
        when (e.statusCode) {
            429 -> {
                // Rate limited - retry with delay
                val retryAfter = e.headers["Retry-After"]?.toLongOrNull() ?: 30
                throw ApplicationError.failureWithDelay(
                    message = "Rate limited by external API",
                    nextRetryDelay = retryAfter.seconds,
                    type = "RateLimitError",
                )
            }
            in 500..599 -> {
                // Server error - should retry
                throw ApplicationError.failure(
                    message = "External API server error: ${e.statusCode}",
                    type = "ServerError",
                )
            }
            in 400..499 -> {
                // Client error - don't retry
                throw ApplicationError.nonRetryable(
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
        throw ApplicationError.nonRetryable(
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
            throw ApplicationError.failure(
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

## Exception Hierarchy Summary

```
TemporalFailure (sealed)
└── ApplicationError           ← Throw from activities/workflows

ActivityException (sealed)     ← Catch in workflows
├── ActivityFailureException   ← Activity failed with error
├── ActivityTimeoutException   ← Activity timed out
└── ActivityCancelledException ← Activity was cancelled
```

## Best Practices

1. **Use descriptive error types** - Makes error handling in workflows cleaner
2. **Use non-retryable for permanent failures** - Validation errors, auth failures, not-found
3. **Use BENIGN category for expected business cases** - Insufficient funds, user not found
4. **Include details for debugging** - But keep them as strings
5. **Set appropriate retry policies** - Use `nonRetryableErrorTypes` for known permanent failures
6. **Handle specific exceptions** - Don't catch generic `Exception` in workflows
