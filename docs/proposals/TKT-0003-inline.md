# TKT-0003: Inline Declarative

In many cases, activities and workflows are only defined in one place, and it would be faster to simply
inline them with their usage.

```kotlin
fun TemporalApplication.myMainModule() {
    install(SerializationPlugin) {
        json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
    taskQueue("my-task-queue") {

        workflow<WorkflowArg>("MyWorkflow") { arg ->
            // Define local activity inline which is directly executed
            val greeting = activity<String>("MyActivity") { name ->
                "Hello, $name"
            }
            "$greeting! You have requested count: ${arg.count}"
        }

        activity<String>("UnnestedActivity") { name ->
            "Hi there, $name"
        }
    }
}
```

## Roadblocks

The above syntax has some problems without compiler support:

- No typed client stubs (must specify types at call site)
- Cannot share workflow/activity definitions across modules
- Cannot detect nested workflows/activities at run time

This means every time you want to call a workflow or activity, you must specify the types again.

```kotlin
val client = app.client { ... }
val result: String = client.executeWorkflow<WorkflowArg, String>(
    workflowType = "MyWorkflow",
    arg = WorkflowArg(name = "Test"),
)
```

Thus, the main blocker is not necessarily the DSL syntax, but providing the type-safe client stubs automatically.
This will require some form of gradle plugin or kotlin compiler.

## Compile Time Resolution

The compiler plugin converts declarative definitions into imperative classes and flattens them.
This produces three outputs:

1. **Generated implementation classes** — workflow/activity classes with annotations
2. **Generated client stubs with typed handles** — type-safe objects for calling workflows/activities and interacting with their handles without reflection
3. **Rewritten registration** — the original DSL block is replaced with factory-based registration (no reflection)

A key goal is eliminating runtime reflection entirely. The imperative API currently relies on reflection
for annotation scanning, instance creation, and method invocation. The compiler plugin replaces all of
this with direct factory calls, making the output compatible with GraalVM native image compilation
without additional reflection configuration.

### Transformation Pipeline

Given this input:

```kotlin
fun TemporalApplication.myMainModule() {
    taskQueue("my-task-queue") {
        workflow<WorkflowArg>("MyWorkflow") { arg ->
            val greeting = activity<String>("MyActivity") { name ->
                "Hello, $name"
            }
            "$greeting! You have requested count: ${arg.count}"
        }

        activity<String>("UnnestedActivity") { name ->
            "Hi there, $name"
        }
    }
}
```

#### Output 1: Generated Implementation Classes

```kotlin
@Workflow("MyWorkflow")
class __MyWorkflow {
    @WorkflowRun
    suspend fun run(arg: WorkflowArg): String {
        val greeting = workflow()
            .startActivity(
                __MyActivity::greet,
                arg = arg.name,
                scheduleToCloseTimeout = 10.seconds,
            ).result<String>()
        return "$greeting! You have requested count: ${arg.count}"
    }
}

class __MyActivity {
    @Activity("MyActivity")
    fun greet(name: String): String {
        return "Hello, $name"
    }
}

class __UnnestedActivity {
    @Activity("UnnestedActivity")
    fun execute(name: String): String {
        return "Hi there, $name"
    }
}
```

#### Output 2: Generated Client Stubs with Typed Handles

The stubs return **typed handles** (defined as interfaces for testability) so that downstream
operations (signals, queries, updates, result awaiting) are fully typed without requiring type
parameters at the call site.

##### Typed Handle Interfaces

```kotlin
// Generated interface — easy to mock in tests
interface MyWorkflowHandle {
    val workflowId: String
    val runId: String

    /** Await the workflow result. */
    suspend fun result(): String

    /** Signal the workflow (generated per declared signal). */
    suspend fun approve()

    /** Query the workflow (generated per declared query). */
    suspend fun status(): String
}

// Generated internal implementation wrapping the untyped handle
internal class MyWorkflowHandleImpl(
    private val handle: WorkflowHandle,
) : MyWorkflowHandle {
    override val workflowId: String get() = handle.workflowId
    override val runId: String get() = handle.runId
    override suspend fun result(): String = handle.result<String>()
    override suspend fun approve() = handle.signal("approve")
    override suspend fun status(): String = handle.query<String>("status")
}
```

##### Workflow Stub (Client-Side)


```kotlin
class MyWorkflowStub(
    private val client: TemporalClient,
    private val options: WorkflowStartOptions = WorkflowStartOptions(),
) : AbstractWorkflowStub<MyWorkflowStub> {

    override fun build(client: TemporalClient, options: WorkflowStartOptions): MyWorkflowStub {
        return MyWorkflowStub(client, options)
    }

    /** Start and await result in one call. */
    suspend fun execute(arg: WorkflowArg): String {
        return start(arg).result()
    }

    /** Start the workflow and return a typed handle for further interaction. */
    fun start(arg: WorkflowArg): MyWorkflowHandle {
        val handle = client.startWorkflow(
            workflowType = "MyWorkflow",
            taskQueue = "my-task-queue",
            arg = arg,
            options = options,
        )
        return MyWorkflowHandleImpl(handle)
    }

    companion object {
        /** Workflow type descriptor — single source of truth for method metadata. */
        val descriptor = WorkflowDescriptor(
            workflowType = "MyWorkflow",
            taskQueue = "my-task-queue",
            argType = typeOf<WorkflowArg>(),
            returnType = typeOf<String>(),
            signals = listOf("approve"),
            queries = listOf("status"),
        )

        /** Reflection-free factory for worker-side registration. */
        fun newInstance(): __MyWorkflow = __MyWorkflow()
    }
}
```

Client usage becomes:

```kotlin
// Before (no stubs — must repeat types, signals are stringly-typed)
val handle = client.startWorkflow<WorkflowArg>(
    workflowType = "MyWorkflow",
    taskQueue = "my-task-queue",
    arg = WorkflowArg(name = "Test"),
)
handle.signal("approve")
val result: String = handle.result<String>()

// After (with generated stub — fully typed, chainable)
val stub = MyWorkflowStub(client)
val handle = stub.start(WorkflowArg(name = "Test"))
handle.approve()            // typed signal, no string
val result = handle.result() // no type parameter needed

// Builder chaining (like gRPC's withDeadline, withCompression, etc.)
val handle = stub
    .withOptions(WorkflowStartOptions(workflowId = "my-id"))
    .start(WorkflowArg(name = "Test"))
```

##### Activity Stub (Workflow-Side)

Activity stubs follow the same pattern, returning typed handles from within workflow code:

```kotlin
// Generated interface
interface MyActivityHandle {
    suspend fun result(): String
    fun cancel()
}

internal class MyActivityHandleImpl(
    private val handle: ActivityHandle,
) : MyActivityHandle {
    override suspend fun result(): String = handle.result<String>()
    override fun cancel() = handle.cancel()
}

object MyActivityStub {
    /** Start the activity and return a typed handle. */
    suspend fun start(
        name: String,
        options: ActivityOptions = ActivityOptions(scheduleToCloseTimeout = 10.seconds),
    ): MyActivityHandle {
        val handle = workflow().startActivity(
            activityType = "MyActivity",
            arg = name,
            options = options,
        )
        return MyActivityHandleImpl(handle)
    }

    /** Start and await result in one call. */
    suspend fun execute(
        name: String,
        options: ActivityOptions = ActivityOptions(scheduleToCloseTimeout = 10.seconds),
    ): String {
        return start(name, options).result()
    }

    val descriptor = ActivityDescriptor(
        activityType = "MyActivity",
        argType = typeOf<String>(),
        returnType = typeOf<String>(),
    )

    fun newInstance(): __MyActivity = __MyActivity()
}
```

Workflow-side usage:

```kotlin
// Before (untyped)
val handle = workflow().startActivity(
    activityType = "MyActivity",
    arg = "World",
    scheduleToCloseTimeout = 10.seconds,
)
val greeting: String = handle.result<String>()

// After (with generated stub — fully typed, resolves WorkflowContext automatically)
val handle = MyActivityStub.start("World")
val greeting = handle.result() // typed, no type parameter
// or one-shot:
val greeting = MyActivityStub.execute("World")
```

#### Output 3: Rewritten Registration

```kotlin
// Generated on the impl class — self-registration via method references
@Workflow("MyWorkflow")
class __MyWorkflow {
    // ... run, signal, query methods ...

    companion object {
        /** Register this workflow with method references — no reflection. */
        fun bind(registry: WorkflowRegistry) {
            val instance = __MyWorkflow()
            registry.register(
                workflowType = "MyWorkflow",
                factory = ::__MyWorkflow,
                run = __MyWorkflow::run,
                signals = mapOf("approve" to __MyWorkflow::approve),
                queries = mapOf("status" to __MyWorkflow::status),
            )
        }
    }
}
```

The rewritten module registration:

```kotlin
fun TemporalApplication.myMainModule() {
    taskQueue("my-task-queue") {
        __MyWorkflow.bind(workflowRegistry)
        __MyActivity.bind(activityRegistry)
        __UnnestedActivity.bind(activityRegistry)
    }
}
```

## Local vs Regular Activities

Activities defined inside a `workflow { }` block are **local activities** — they execute in the same
worker process as the workflow and are registered to the same task queue.

Activities defined directly inside a `taskQueue { }` block (but outside any workflow) are
**regular activities** — they are independently registered and scheduled through the server.

```kotlin
taskQueue("my-task-queue") {
    workflow<WorkflowArg>("MyWorkflow") { arg ->
        // Local activity — same worker, no server round-trip
        val greeting = activity<String>("MyActivity") { name ->
            "Hello, $name"
        }
        greeting
    }

    // Regular activity — independently scheduled
    activity<String>("UnnestedActivity") { name ->
        "Hi there, $name"
    }
}
```

The compiler plugin tracks this distinction via the `isLocal` flag in `ActivityMetadata` and generates
the appropriate `startActivity` vs `startLocalActivity` call in the workflow implementation.

## Signals, Queries, and Updates

The imperative API supports `@Signal`, `@Query`, and `@Update` handlers as methods on a workflow class.
The declarative API needs a way to express these within the lambda.

```kotlin
taskQueue("my-task-queue") {
    workflow<WorkflowArg>("MyWorkflow") {
        var approved = false

        signal("approve") {
            approved = true
        }

        query<String>("status") {
            if (approved) "approved" else "pending"
        }

        run { arg ->
            workflow().awaitCondition { approved }
            "Approved: ${arg.name}"
        }
    }
}
```

This compiles to:

```kotlin
@Workflow("MyWorkflow")
class __MyWorkflow {
    private var approved = false

    @Signal("approve")
    fun approve() {
        approved = true
    }

    @Query("status")
    fun status(): String {
        return if (approved) "approved" else "pending"
    }

    @WorkflowRun
    suspend fun run(arg: WorkflowArg): String {
        workflow().awaitCondition { approved }
        return "Approved: ${arg.name}"
    }
}
```

When signals/queries/updates are present, the workflow lambda must use an explicit `run { }` block
to separate the run body from the handler declarations. When there are no handlers, the lambda body
*is* the run body (the simple case shown earlier).

The generated `MyWorkflowHandle` (from Output 2) includes typed methods for all declared
signals, queries, and updates — so the client never needs to use string-based `handle.signal("name")`.

## Mix and Match

The declarative and imperative APIs are not mutually exclusive. An imperative workflow class
can use declaratively-defined activity stubs, and declarative inline activities can be called
from hand-written workflow classes. The compiler plugin generates stubs for both styles.

### Imperative Workflow Using Declarative Activity Stubs

Define activities inline in the module, then call their generated stubs from a hand-written
workflow class:

```kotlin
// Module definition — activities are declarative, workflow is imperative
fun TemporalApplication.myModule() {
    taskQueue("order-queue") {
        // Declarative activity — compiler generates ValidateOrderStub
        activity<OrderRequest>("ValidateOrder") { order ->
            ValidationResult(valid = order.total > 0, reason = "OK")
        }

        // Declarative activity — compiler generates ChargePaymentStub
        activity<PaymentRequest>("ChargePayment") { payment ->
            PaymentResult(transactionId = "txn-${payment.orderId}", success = true)
        }

        // Imperative workflow — hand-written class
        workflow<OrderWorkflow>()
    }
}

// Hand-written workflow using generated activity stubs
@Workflow("OrderWorkflow")
class OrderWorkflow {
    @WorkflowRun
    suspend fun run(order: OrderRequest): String {
        // Use generated stubs — fully typed, no strings, no type parameters
        val validation = ValidateOrderStub.execute(order)
        if (!validation.valid) return "Rejected: ${validation.reason}"

        val payment = ChargePaymentStub.execute(PaymentRequest(orderId = order.id))
        return "Charged: ${payment.transactionId}"
    }
}
```

### Generated Stubs for Imperative Workflows

The compiler plugin also generates stubs for imperative `@Workflow` and `@Activity` classes —
not just declarative lambdas. Any workflow or activity registered in a module gets a typed stub,
a typed handle, and a descriptor.

```kotlin
// Hand-written imperative workflow
@Workflow("OrderWorkflow")
class OrderWorkflow {
    @WorkflowRun
    suspend fun run(order: OrderRequest): String { ... }

    @Signal("cancel")
    fun cancel(reason: String) { ... }

    @Query("status")
    fun status(): OrderStatus { ... }
}

// Compiler generates all of these automatically:

interface OrderWorkflowHandle {
    val workflowId: String
    val runId: String
    suspend fun result(): String
    suspend fun cancel(reason: String)    // typed signal
    suspend fun status(): OrderStatus     // typed query
}

class OrderWorkflowStub(
    private val client: TemporalClient,
    private val options: WorkflowStartOptions = WorkflowStartOptions(),
) : AbstractWorkflowStub<OrderWorkflowStub> {

    override fun build(client: TemporalClient, options: WorkflowStartOptions): OrderWorkflowStub {
        return OrderWorkflowStub(client, options)
    }

    suspend fun execute(order: OrderRequest): String = start(order).result()

    fun start(order: OrderRequest): OrderWorkflowHandle { ... }

    companion object {
        val descriptor = WorkflowDescriptor(
            workflowType = "OrderWorkflow",
            taskQueue = "order-queue",
            argType = typeOf<OrderRequest>(),
            returnType = typeOf<String>(),
            signals = listOf("cancel"),
            queries = listOf("status"),
        )

        fun newInstance(): OrderWorkflow = OrderWorkflow()
    }
}
```

Client code is the same regardless of whether the workflow was defined declaratively or imperatively:

```kotlin
val stub = OrderWorkflowStub(client)
val handle = stub.start(OrderRequest(id = "order-123", total = 99.99))

val status = handle.status()       // typed query
handle.cancel("out of stock")      // typed signal
val result = handle.result()       // typed result
```

## Debugging and Stack Traces

Generated code must not pollute stack traces. When a workflow or activity fails, the developer
should see their original source location — not generated class names like `__MyWorkflow.run()`.

### Source Offset Preservation

When the compiler plugin extracts a lambda body into a generated class method, it must **copy the
IR source offsets** (`startOffset`/`endOffset`) from the original lambda expressions. This causes
the JVM to emit line number table entries pointing back to the user's original source file, so
stack traces and debugger breakpoints land in the right place.

```
// User writes this at MyModule.kt:17
workflow<WorkflowArg>("MyWorkflow") { arg ->
    val greeting = activity<String>("MyActivity") { name ->   // line 19
        "Hello, $name"                                         // line 20
    }
    "$greeting! Count: ${arg.count}"                           // line 22
}

// Generated __MyWorkflow.run() preserves source mapping:
//   line 19 → startActivity call
//   line 22 → return statement
// Stack trace on failure shows: MyModule.kt:22, not __MyWorkflow.kt:5
```

If an IR element is purely synthetic (registration wiring, factory methods), use `UNDEFINED_OFFSET`
so the JVM knows there is no corresponding source location.

### Temporal Enhanced Stack Traces

Temporal already supports an `EnhancedStackTrace` protocol with an `internal_code` flag on each
`StackTraceFileLocation`. The compiler plugin should mark all generated frames as `internal_code = true`
so the Temporal UI hides them by default in the stack trace view.

```protobuf
message StackTraceFileLocation {
    string file_path = 1;
    int32 line = 2;
    int32 column = 3;
    string function_name = 4;
    bool internal_code = 5;  // ← set true for generated frames
}
```

The SDK's stack trace capture should detect generated classes (e.g. by a marker annotation) and
set `internal_code = true` automatically.

### Annotation Strategy

Generated classes and methods should be annotated to support filtering:

```kotlin
@TemporalGenerated  // custom marker — SDK uses this to set internal_code in stack traces
@JvmSynthetic       // hides from Java tooling, filtered by some stack trace tools
class __MyWorkflow {
    @WorkflowRun
    suspend fun run(arg: WorkflowArg): String { ... }
    // ↑ NOT @JvmSynthetic — this is the actual user logic, must be visible in traces
}
```

- **`@TemporalGenerated`** on the class — the SDK's enhanced stack trace builder checks for this
  and sets `internal_code = true` on matching frames.
- **`@JvmSynthetic`** on internal wiring (handle impls, `bind()`, `newInstance()`, descriptors) —
  hides plumbing from Java callers and IDE completion.
- **Not on `@WorkflowRun`/`@Activity` methods** — these contain the user's actual logic and must
  remain visible in stack traces and debuggers.

### Coroutine Debug Metadata

Kotlin automatically attaches `@DebugMetadata` to coroutine continuation classes, recording the
source file, line numbers at suspension points, and local variable names. As long as the generated
`suspend fun run()` preserves the original file's `IrFileEntry`, coroutine debuggers (IntelliJ,
`DebugProbes`) will show the correct source location when stepping through suspended workflows.

## Nested Task Queue Resolution

By default, nested activities and workflows register themselves to their parent task queue.
But we may want to call an activity or workflow on another task queue.

Since task queues have configuration options, only one task queue definition should declare config
in a similar manner to how Auth is handled in Ktor.

```kotlin
taskQueue("other-queue") {
    configuration {
        // custom config here
    }
}

taskQueue("my-task-queue") {
    configuration {
        // default config here
    }
    workflow<WorkflowArg>("MyWorkflow") { arg ->
        val greeting = withTaskQueue("other-queue") {
            activity<String>("MyActivity") { name ->
                "Hello, $name"
            }
        }
    }
}
```

If you use `withTaskQueue` to define an activity or workflow, and the named task queue does not exist in that module
then it should be a compile time error.

```kotlin
taskQueue("my-task-queue") {
    workflow<WorkflowArg>("MyWorkflow") { arg ->
        // Compile time error: "Task queue 'other-queue' not defined in this module"
        val greeting = withTaskQueue("other-queue") {
            activity<String>("MyActivity") { name ->
                "Hello, $name"
            }
        }
    }
}
```

For these cases you should use the generated stubs (or explicit imperative definitions) to call activities/workflows
on other task queues outside the current module.

```kotlin
taskQueue("my-task-queue") {
    workflow<WorkflowArg>("MyWorkflow") { arg ->
        withTaskQueue("external-queue") {
            ExternalActivityStub.execute(arg.param)
        }
    }
}
```
