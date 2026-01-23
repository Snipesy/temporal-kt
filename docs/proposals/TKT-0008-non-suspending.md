# TKT-0008: Non-Suspending Annotations/Interfaces

Support for non-suspending workflow and activity definitions. This mirrors TKT-0001 and TKT-0002
but allows defining methods without the `suspend` modifier.

## Use Cases

- Interop with blocking Java libraries
- Simple synchronous activities that don't benefit from coroutines
- Legacy code migration
- Testing scenarios

## Annotation-Based (Class)

```kotlin
@Workflow("SyncWorkflow")
class SyncWorkflow {
    @WorkflowRun
    fun WorkflowContext.execute(arg: WorkflowArg): String {
        val greeting = activity<SyncActivity>().greet(arg.name)
        return "$greeting! Count: ${arg.count}"
    }
}

@Activity("SyncActivity")
class SyncActivity {
    @ActivityMethod
    fun ActivityContext.greet(name: String): String {
        // Blocking call is OK here
        return "Hello, $name"
    }
}
```

## Interface-Based

```kotlin
@Workflow("SyncWorkflow")
interface SyncWorkflow {
    @WorkflowRun
    fun WorkflowContext.execute(arg: WorkflowArg): String
}

@Activity("SyncActivity")
interface SyncActivity {
    @ActivityMethod
    fun ActivityContext.greet(name: String): String
}
```

## Implementation Notes

- The same annotations (`@Workflow`, `@Activity`, etc.) work for both suspend and non-suspend methods
- Runtime detects method signature and dispatches appropriately
- Exact threading model TBD (Project Loom / virtual threads may simplify this)
- Declarative API will likely not ever support this (why would it?)
