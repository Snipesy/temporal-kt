# TKT-0003: Typed Workflow Companions + Inline Activities

## Goal

Eliminate ceremony when calling Temporal workflows from client code. Users should not need to
restate the workflow's argument or return type at the call site, nor invent an extra annotation
to declare the wiring. The compiler plugin synthesises typed `start(...)` / `execute(...)`
helpers on each `@Workflow` class's companion object — modelled after kotlinx.serialization's
`@Serializable class Foo : Companion : KSerializer<Foo>` pattern — so

```kotlin
client.startWorkflow("Greeter", "queue", arg).result<String>()  // before
Greeter.execute(client, "queue", arg)                           // after
```

is the only call you need to write, and the return type is captured automatically.

## Source of truth

`@Workflow` class declarations are the **only** source of truth. There are no separate
`@TemporalModule`, `WorkflowDecl`, or `ActivityDecl` annotations duplicating names or types.

```kotlin
@Workflow("Greeter")
class Greeter {
    @WorkflowRun
    suspend fun WorkflowContext.run(arg: String): String = "Hello, $arg"
}
```

The plugin reads:
- the class's `@Workflow("name")` annotation argument (or the class simple name as fallback),
- the `@WorkflowRun` method's value-parameter type for the workflow argument type,
- the `@WorkflowRun` method's return type for the workflow result type.

Any change to the user class is automatically reflected in the synthesised helpers — no second
declaration site to drift out of sync.

## Synthesised companion

For every `@Workflow`-annotated class the plugin augments (or, if absent, creates) a companion
object with these members:

```kotlin
class Greeter {
    // ... user code ...

    companion object {
        suspend fun start(
            client: TemporalClient,
            taskQueue: String,
            arg: String,
            options: WorkflowStartOptions = WorkflowStartOptions(),
        ): TypedWorkflowHandle<String>

        suspend fun execute(
            client: TemporalClient,
            taskQueue: String,
            arg: String,
            options: WorkflowStartOptions = WorkflowStartOptions(),
        ): String
    }
}
```

If the user's `@WorkflowRun` method is no-arg, the synthesised `start`/`execute` drop the `arg`
parameter accordingly.

If the user already wrote `companion object { ... }`, the plugin **augments** it — adds
`start`/`execute` alongside the user's declarations without crashing or replacing.

`TypedWorkflowHandle<R>` is a thin wrapper around the existing `WorkflowHandle` that captures
`R` so `.result()` doesn't need a reified type parameter at the call site.

## Client-side usage

```kotlin
// `start(...)` returns a typed `Greeter.Handle<String>`. The result type R is captured —
// `.result()` is statically `String` without `<R>` ceremony.
val handle: Greeter.Handle<String> = Greeter.start(client, "queue", "World")
val result: String = handle.result()

// Or wrap an existing run by ID:
val existing: Greeter.Handle<String> = Greeter.handle(client, "some-workflow-id")
val ongoing: String = existing.result()
```

## Typed signal / query / update wrappers

Each `@Signal` / `@Query` / `@Update` method on the workflow class projects to a typed wrapper
on `<UserClass>.Handle<R>`. The wrapper:

- mirrors the user's value parameters (same names + types),
- drops any `WorkflowContext` extension receiver (Handle is client-side),
- is always `suspend` (the runtime dispatch goes through `signalWithPayloads` etc.),
- returns `Unit` for `@Signal`; the user's declared return type for `@Query` / `@Update`,
- uses `@Signal("wire-name")` / `@Query("wire-name")` / `@Update("wire-name")` as the wire
  name when sending to the server. The Kotlin method on Handle keeps the user's method name
  (which is always a valid identifier).

```kotlin
@Workflow("Cart")
class Cart {
    @WorkflowRun
    suspend fun WorkflowContext.run(): Int = 0

    @Signal("cancel")
    fun WorkflowContext.cancel(reason: String) { /* ... */ }

    @Query("status")
    fun status(): Int = 42

    @Update("addItem")
    suspend fun WorkflowContext.addItem(item: String): Int = 1
}

suspend fun useCart(client: TemporalClient) {
    val handle: Cart.Handle<Int> = Cart.start(client, "queue")
    handle.cancel("user requested")     // typed signal — no string, no untyped args
    val n: Int = handle.status()         // typed query — no <Int>
    val s: Int = handle.addItem("milk")  // typed update — both arg + return are typed
}
```

`@Signal(dynamic = true)` / `@Query(dynamic = true)` / `@Update(dynamic = true)` handlers do
**not** get typed wrappers — they catch all unhandled names by accepting the wire name as a
parameter, so there's no fixed dispatch target. `@UpdateValidator` methods are server-side
only and never produce wrappers.

## Inline activities

TKT-0003 also supports inline `activity("name") { ... }` calls inside `@WorkflowRun` methods.
The compiler plugin lifts the activity lambda to a top-level `@Activity("name")` function,
synthesises a workflow companion registration hook, and rewrites the workflow call site to
Temporal's standard activity dispatch.

Inline workflow declarations inside `taskQueue { ... }` are intentionally not supported. To
register workflows, use the class-based runtime API:

```kotlin
@Workflow("Foo")
class Foo {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        return activity("Bar") {
            "done"
        }
    }
}

embeddedTemporal {
    taskQueue("q") {
        workflow<Foo>()
    }
}
```

## Annotation strategy (carry-over)

`@WorkflowRun`, `@Signal`, `@Query`, `@Update` methods are **not** marked `@JvmSynthetic` —
they contain user logic and must remain visible in stack traces and tooling. Plugin-synthesised
plumbing (the companion's `start`/`execute`, future inline-activity hooks) is internal but
remains visible to Kotlin callers; `@JvmSynthetic` would unnecessarily hide it from Java
interop.

## Debugging and stack traces

The inline activity IR pass preserves `IrElement.startOffset`/`endOffset` from the original
lambda expressions so that JVM line-number tables — and therefore stack traces and IDE
breakpoints — point back to the user's source location, not the synthesised wrapper function.
