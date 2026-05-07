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
// Direct execute — start + await result in one call.
val result: String = Greeter.execute(client, "queue", "World")

// Or split — start, signal/query/cancel through the handle, await later.
val handle: TypedWorkflowHandle<String> = Greeter.start(client, "queue", "World")
val later: String = handle.result()
```

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
