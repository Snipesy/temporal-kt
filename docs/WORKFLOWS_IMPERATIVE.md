# Workflows Imperative

This document describes how to declare and utilize imperative workflows in Temporal-KT. The process is similar to Python.

## Quick Example

### Workflow Definition

```kotlin
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun

@Workflow
class OrdersWorkflow {
    @WorkflowRun
    suspend fun runOrderWorkflow(orderId: String): String {
        // Workflow logic here
        return "Order $orderId processed"
    }
}

```


### Application Definition

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
        taskQueue("hello-world-queue") {
            workflow<GreetingWorkflow>()
        }
    }
    .start(wait = true)
}
```
### Invoke From Client

```kotlin
fun main() = runBlocking {
    val client = TemporalClient.connect {
        target = "http://localhost:7233"
        namespace = "default"
    }
    
    // Types are <Return, Argument, Argument2...>
    val workflowHandle = client.startWorkflow<String, String>(
        OrdersWorkflow::class, // or "OrdersWorkflow" as a string
        "hello-world-queue",
        arg = "12345" // orderId argument
    )
    
    val result = workflowHandle.result()
    println("Workflow result: $result")
}
```


## Workflow Features

### Start Activities

Activities perform side effects like calling external services or databases. Define them with `@Activity` and call them from workflows.

#### Define an Activity

```kotlin
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.activity.activity

class GreetingActivity {
    @Activity("formatGreeting")
    fun formatGreeting(name: String): String {
        // Perform side effects here (API calls, database operations, etc.)
        return "Hello, $name! Welcome to Temporal."
    }

    @Activity
    suspend fun getLocalizedGreeting(
        name: String,
        language: String,
    ): String {
        // Use activity() helper to access ActivityContext from coroutine context
        val info = activity().info
        val greeting = when (language) {
            "es" -> "Hola"
            "fr" -> "Bonjour"
            else -> "Hello"
        }
        return "$greeting, $name!"
    }
}
```

#### Call Activities from a Workflow

```kotlin
import kotlin.time.Duration.Companion.seconds

@Workflow("GreetingWorkflow")
class GreetingWorkflow {
    @WorkflowRun
    suspend fun run(name: String): String {
        // Using function reference
        val greeting = workflow()
            .startActivity<String, String>(
                GreetingActivity::formatGreeting,
                arg = name,
                scheduleToCloseTimeout = 10.seconds,
            ).result()

        // Using activity type string with multiple arguments
        val localized = workflow()
            .startActivity<String, String, String>(
                activityType = "getLocalizedGreeting",
                arg1 = name,
                arg2 = "es",
                scheduleToCloseTimeout = 10.seconds,
            ).result()

        return "$greeting ($localized)"
    }
}
```

#### Register Activities

```kotlin
fun main() {
    embeddedTemporal(configure = {
        connection {
            target = "http://localhost:7233"
            namespace = "default"
        }
    })
    {
        taskQueue("hello-world-queue") {
            workflow<GreetingWorkflow>()
            activity(GreetingActivity())
        }
    }
    .start(wait = true)
}
```

### Signals

Signals are asynchronous messages that modify workflow state. They are fire-and-forget and do not return values.

#### Define Signal Handlers

```kotlin
@Workflow("OrderWorkflow")
class OrderWorkflow {
    private val items = mutableListOf<String>()
    private var completed = false

    @WorkflowRun
    suspend fun run(): List<String> {
        workflow().awaitCondition { completed }
        return items.toList()
    }

    @Signal("addItem")
    fun addItem(item: String) {
        items.add(item)
    }

    @Signal("complete")
    fun complete() {
        completed = true
    }
}
```

#### Send Signals from Client

```kotlin
fun main() = runBlocking {
    val client = TemporalClient.connect {
        target = "http://localhost:7233"
        namespace = "default"
    }

    val handle = client.startWorkflow<List<String>>(
        OrderWorkflow::class,
        "orders-queue",
    )

    // Send signals to the running workflow
    handle.signal("addItem", "Apple")
    handle.signal("addItem", "Banana")
    handle.signal("complete")

    val result = handle.result()
    println("Order items: $result")
}
```

#### Dynamic Signal Handlers

```kotlin
@Workflow("DynamicSignalWorkflow")
class DynamicSignalWorkflow {
    private val signals = mutableMapOf<String, String>()

    @Signal(dynamic = true)
    fun handleAnySignal(signalName: String, value: String) {
        signals[signalName] = value
    }
}
```

### Queries

Queries are synchronous read-only operations that inspect workflow state without modifying it.

#### Define Query Handlers

```kotlin
@Workflow("CounterWorkflow")
class CounterWorkflow {
    private var counter = 0

    @WorkflowRun
    suspend fun run(): Int {
        repeat(10) {
            workflow().sleep(100.milliseconds)
            counter++
        }
        return counter
    }

    @Query("getCounter")
    fun getCounter(): Int = counter

    @Query("getStatus")
    fun getStatus(): String =
        if (counter < 5) "warming up" else "running"
}
```

#### Query from Client

```kotlin
fun main() = runBlocking {
    val client = TemporalClient.connect {
        target = "http://localhost:7233"
        namespace = "default"
    }

    val handle = client.startWorkflow<Int>(
        CounterWorkflow::class,
        "counter-queue",
    )

    // Query the running workflow
    // Type parameters are <WorkflowResult, QueryResult>
    delay(500.milliseconds)
    val currentCount = handle.query<Int, Int>("getCounter")
    println("Current counter: $currentCount")

    val status = handle.query<Int, String>("getStatus")
    println("Status: $status")
}
```

### Timers

Timers create durable delays that survive workflow replay. Use `workflow().sleep()` or `workflow().awaitCondition()` for workflow-safe delays.

#### Basic Timer

```kotlin
@Workflow("ReminderWorkflow")
class ReminderWorkflow {
    @WorkflowRun
    suspend fun run(message: String): String {
        println("Reminder scheduled: $message")

        // Durable timer - survives restarts and replays
        workflow().sleep(1.hours)

        println("Reminder triggered: $message")
        return "Reminder sent: $message"
    }
}
```

#### Await Condition with Timeout

```kotlin
@Workflow("ApprovalWorkflow")
class ApprovalWorkflow {
    private var approved = false

    @WorkflowRun
    suspend fun run(): String {
        return try {
            // Wait up to 24 hours for approval
            workflow().awaitCondition(
                timeout = 24.hours,
                timeoutSummary = "Approval timed out"
            ) { approved }
            "Request approved"
        } catch (e: WorkflowConditionTimeoutException) {
            "Request expired - no approval received"
        }
    }

    @Signal("approve")
    fun approve() {
        approved = true
    }
}
```

#### Get Current Workflow Time

```kotlin
@Workflow("TimestampWorkflow")
class TimestampWorkflow {
    @WorkflowRun
    suspend fun run(): String {
        val ctx = workflow()
        val startTime = ctx.now()  // Deterministic workflow time
        ctx.sleep(5.seconds)
        val endTime = ctx.now()
        return "Started at $startTime, ended at $endTime"
    }
}
```

### Update

Updates are synchronous operations that can both read and modify workflow state, returning a result to the caller.

#### Define Update Handlers

```kotlin
@Workflow("CartWorkflow")
class CartWorkflow {
    private val items = mutableListOf<CartItem>()
    private var checkedOut = false

    @WorkflowRun
    suspend fun run(): OrderSummary {
        workflow().awaitCondition { checkedOut }
        return OrderSummary(items = items.toList(), total = items.sumOf { it.price })
    }

    @Update("addItem")
    fun addItem(item: CartItem): Int {
        items.add(item)
        return items.size  // Return updated cart size
    }

    @Update("removeItem")
    fun removeItem(itemId: String): Boolean {
        return items.removeIf { it.id == itemId }
    }

    @Update("checkout")
    fun checkout(): Double {
        checkedOut = true
        return items.sumOf { it.price }
    }
}
```

#### Update with Validation

```kotlin
@Workflow("AccountWorkflow")
class AccountWorkflow {
    private var balance = 0.0

    @Update("withdraw")
    fun withdraw(amount: Double): Double {
        balance -= amount
        return balance
    }

    @UpdateValidator("withdraw")
    fun validateWithdraw(amount: Double) {
        require(amount > 0) { "Amount must be positive" }
        require(amount <= balance) { "Insufficient funds" }
    }
}
```

#### Send Updates from Client

```kotlin
fun main() = runBlocking {
    val client = TemporalClient.connect {
        target = "http://localhost:7233"
        namespace = "default"
    }

    val handle = client.startWorkflow<OrderSummary>(
        CartWorkflow::class,
        "cart-queue",
    )

    // Updates return values and wait for completion
    // Type parameters are <WorkflowResult, ArgumentType, UpdateResult>
    val cartSize = handle.update<OrderSummary, CartItem, Int>("addItem", CartItem("1", "Apple", 1.99))
    println("Cart now has $cartSize items")

    // For updates without arguments, type parameters are <WorkflowResult, UpdateResult>
    val total = handle.update<OrderSummary, Double>("checkout")
    println("Order total: $$total")
}
```

### Child Workflows

Child workflows are separate workflow executions started and managed by a parent workflow.

#### Define a Child Workflow

```kotlin
@Workflow("ProcessOrderWorkflow")
class ProcessOrderWorkflow {
    @WorkflowRun
    suspend fun run(orderId: String): OrderResult {
        // Child workflow logic
        workflow().sleep(1.seconds)
        return OrderResult(orderId = orderId, status = "processed")
    }
}
```

#### Start Child Workflows

```kotlin
@Workflow("BatchProcessorWorkflow")
class BatchProcessorWorkflow {
    @WorkflowRun
    suspend fun run(orderIds: List<String>): List<OrderResult> {
        val results = mutableListOf<OrderResult>()

        for (orderId in orderIds) {
            // Start child workflow with type-safe API
            val result = workflow().startChildWorkflow<OrderResult, String>(
                workflowClass = ProcessOrderWorkflow::class,
                arg = orderId,
                options = ChildWorkflowOptions(
                    workflowId = "process-$orderId",
                ),
            ).result()

            results.add(result)
        }

        return results
    }
}
```

#### Parallel Child Workflows

```kotlin
@Workflow("ParallelProcessorWorkflow")
class ParallelProcessorWorkflow {
    @WorkflowRun
    suspend fun run(orderIds: List<String>): List<OrderResult> {
        val ctx = workflow()

        // Start all child workflows
        val handles = orderIds.map { orderId ->
            ctx.startChildWorkflow<OrderResult, String>(
                workflowClass = ProcessOrderWorkflow::class,
                arg = orderId,
                options = ChildWorkflowOptions(),
            )
        }

        // Wait for all to complete
        return handles.map { it.result() }
    }
}
```

#### Child Workflow Options

```kotlin
val options = ChildWorkflowOptions(
    workflowId = "custom-child-id",           // Custom ID (default: auto-generated)
    taskQueue = "different-queue",            // Override task queue (default: parent's queue)
    workflowExecutionTimeout = 1.hours,       // Max total execution time
    workflowRunTimeout = 30.minutes,          // Max time per run
    parentClosePolicy = ParentClosePolicy.TERMINATE,  // What happens when parent closes
    cancellationType = ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED,
)
```

#### Handle Child Workflow Failures

```kotlin
@Workflow("ResilientParentWorkflow")
class ResilientParentWorkflow {
    @WorkflowRun
    suspend fun run(): String {
        return try {
            workflow().startChildWorkflow<String>(
                workflowType = "UnreliableChildWorkflow",
                options = ChildWorkflowOptions(),
            ).result()
        } catch (e: ChildWorkflowFailureException) {
            "Child failed: ${e.failure?.message}"
        }
    }
}
```

#### Signal Child Workflow

```kotlin
@Workflow("ChildWithSignalWorkflow")
class ChildWithSignalWorkflow {
    private var data = ""

    @WorkflowRun
    suspend fun run(): String {
        workflow().awaitCondition { data.isNotEmpty() }
        return data
    }

    @Signal("setData")
    fun setData(value: String) {
        data = value
    }
}

@Workflow("ParentSignalingWorkflow")
class ParentSignalingWorkflow {
    @WorkflowRun
    suspend fun run(): String {
        val childHandle = workflow().startChildWorkflow<String>(
            workflowType = "ChildWithSignalWorkflow",
            options = ChildWorkflowOptions(),
        )

        // Signal the child workflow
        childHandle.signal("setData", "Hello from parent!")

        return childHandle.result()
    }
}
```

### Workflow Info

Access metadata about the current workflow execution via `workflow().info`.

```kotlin
data class WorkflowInfo(
    val workflowId: String,    // Unique workflow identifier
    val runId: String,         // Run ID for this specific run
    val workflowType: String,  // The workflow type name
    val taskQueue: String,     // Task queue this workflow runs on
    val namespace: String,     // Temporal namespace
    val attempt: Int,          // Attempt number (1-based)
    val startTime: Instant,    // When this run started
)
```

#### Using Workflow Info

```kotlin
@Workflow("AuditWorkflow")
class AuditWorkflow {
    @WorkflowRun
    suspend fun run(): AuditResult {
        val ctx = workflow()
        val info = ctx.info

        // Log workflow metadata
        println("Workflow ${info.workflowId} (run: ${info.runId})")
        println("Type: ${info.workflowType}, Queue: ${info.taskQueue}")
        println("Attempt: ${info.attempt}, Started: ${info.startTime}")

        // Use workflow ID for external references
        val externalRef = "audit-${info.workflowId}"

        return AuditResult(reference = externalRef)
    }
}
```

### Random & UUID

Workflows must be deterministic. Use the workflow context's random and UUID generators instead of `java.util.Random` or `UUID.randomUUID()`.

```kotlin
@Workflow("RandomExampleWorkflow")
class RandomExampleWorkflow {
    @WorkflowRun
    suspend fun run(): String {
        val ctx = workflow()

        // Generate a deterministic UUID (same on replay)
        val uniqueId = ctx.randomUuid()

        // Use for creating unique identifiers
        val orderId = "order-${uniqueId.take(8)}"

        return orderId
    }
}
```

### Versioning (Patched)

Use `patched()` to safely evolve workflow code while maintaining replay compatibility with running workflows.

```kotlin
@Workflow("EvolvingWorkflow")
class EvolvingWorkflow {
    @WorkflowRun
    suspend fun run(data: String): String {
        val ctx = workflow()

        // Check if we should use the new code path
        if (ctx.patched("v2-improved-validation")) {
            // New code path - used for new executions
            // and replays where this patch was already recorded
            return improvedValidation(data)
        } else {
            // Legacy code path - used for replaying old workflows
            // that don't have this patch in their history
            return legacyValidation(data)
        }
    }

    private fun improvedValidation(data: String): String {
        // New improved logic
        return "v2: $data"
    }

    private fun legacyValidation(data: String): String {
        // Original logic - keep until all old workflows complete
        return "v1: $data"
    }
}
```

**Patch Behavior:**
- **First execution:** Returns `true`, records patch marker in history
- **Replay with marker:** Returns `true` (deterministic)
- **Replay without marker:** Returns `false` (uses legacy path)

**Best Practices:**
- Use descriptive patch IDs (e.g., "v2-improved-retry", "fix-123-null-check")
- Keep old code paths until all running workflows have completed
- Remove old code and patch checks once all workflows use the new path

### Continue As New

Long-running workflows can accumulate large histories. Use `continueAsNew()` to start a fresh execution with the same workflow ID, preserving state through arguments.

#### Basic Continue As New

```kotlin
@Workflow("LongRunningWorkflow")
class LongRunningWorkflow {
    @WorkflowRun
    suspend fun run(state: WorkflowState): String {
        val ctx = workflow()

        while (!state.isComplete) {
            // Process work...
            state.processNextBatch()

            // Check if server suggests continue-as-new
            if (ctx.isContinueAsNewSuggested()) {
                // Continue with current state - this never returns
                ctx.continueAsNew(state)
            }
        }

        return "Completed after ${state.totalProcessed} items"
    }
}
```

#### Manual History Checks

```kotlin
@Workflow("ManualHistoryCheckWorkflow")
class ManualHistoryCheckWorkflow {
    @WorkflowRun
    suspend fun run(iteration: Int): String {
        val ctx = workflow()

        // Check history size manually
        if (ctx.historyLength > 5000 || ctx.historySizeBytes > 5_000_000) {
            ctx.continueAsNew(iteration + 1)
        }

        // Process work...
        repeat(100) {
            ctx.sleep(1.seconds)
        }

        return "Iteration $iteration completed"
    }
}
```

#### Continue As New Options

```kotlin
@Workflow("ConfiguredContinueAsNewWorkflow")
class ConfiguredContinueAsNewWorkflow {
    @WorkflowRun
    suspend fun run(state: MyState): String {
        val ctx = workflow()

        if (ctx.isContinueAsNewSuggested()) {
            ctx.continueAsNew(
                state,
                options = ContinueAsNewOptions(
                    workflowType = null,  // Same workflow type (default)
                    taskQueue = null,     // Same task queue (default)
                    workflowRunTimeout = 1.hours,
                    workflowTaskTimeout = 10.seconds,
                )
            )
        }

        // ... workflow logic
        return "done"
    }
}
```

#### Continue As New to Different Workflow

```kotlin
@Workflow("MigratingWorkflow")
class MigratingWorkflow {
    @WorkflowRun
    suspend fun run(state: OldState): String {
        val ctx = workflow()

        // Migrate to a new workflow type
        val newState = migrateState(state)
        ctx.continueAsNewTo(
            NewWorkflow::class,
            newState,
        )

        // Never reached
        return ""
    }
}
```

**History Limits:**
- Event count warning: 10,240 events (hard limit: 51,200)
- Size warning: 10 MB (hard limit: 50 MB)

### Runtime Handlers

Register signal, query, and update handlers dynamically at runtime instead of using annotations.

#### Runtime Signal Handler

```kotlin
@Workflow("DynamicSignalWorkflow")
class DynamicSignalWorkflow {
    private val messages = mutableListOf<String>()
    private var done = false

    @WorkflowRun
    suspend fun run(): List<String> {
        val ctx = workflow()

        // Register handler at runtime with typed argument
        // Type parameter is the argument type
        ctx.setSignalHandler<String>("addMessage") { message ->
            messages.add(message)
        }

        // For signals without arguments, use the raw payload API
        ctx.setSignalHandlerWithPayloads("complete") { _ ->
            done = true
        }

        ctx.awaitCondition { done }
        return messages
    }
}
```

#### Runtime Query Handler

```kotlin
@Workflow("DynamicQueryWorkflow")
class DynamicQueryWorkflow {
    private var status = "initializing"

    @WorkflowRun
    suspend fun run(): String {
        val ctx = workflow()

        // Register query handler
        ctx.setQueryHandler<Unit, String>("getStatus") { _ ->
            status
        }

        status = "running"
        ctx.sleep(10.seconds)
        status = "completed"

        return status
    }
}
```

#### Runtime Update Handler

```kotlin
@Workflow("DynamicUpdateWorkflow")
class DynamicUpdateWorkflow {
    private var value = 0

    @WorkflowRun
    suspend fun run(): Int {
        val ctx = workflow()

        // Register update handler with optional validator
        ctx.setUpdateHandler<Int, Int>(
            name = "increment",
            handler = { amount ->
                value += amount
                value
            },
            validator = { amount ->
                require(amount > 0) { "Amount must be positive" }
            }
        )

        ctx.awaitCondition { value >= 100 }
        return value
    }
}
```

### Logging

Use the `logger()` extension function for SLF4J logging with automatic MDC context.

```kotlin
import com.surrealdev.temporal.workflow.logger

@Workflow("LoggingWorkflow")
class LoggingWorkflow {
    @WorkflowRun
    suspend fun run(orderId: String): String {
        val ctx = workflow()
        val log = ctx.logger()

        // MDC automatically includes: workflowId, runId,
        // taskQueue, namespace, workflowType
        log.info("Processing order: {}", orderId)

        try {
            val result = processOrder(orderId)
            log.info("Order processed successfully")
            return result
        } catch (e: Exception) {
            log.error("Failed to process order", e)
            throw e
        }
    }
}
