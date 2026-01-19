# TKT-0002: Interface-Based Definitions (Interop)

Interface-based definitions are supported for interoperability with external systems and for
compiler plugin generated stubs. This style separates the contract (interface) from implementation.

```kotlin
@Serializable
data class WorkflowArg(
    val name: String,
    val count: Int = 0
)

@Workflow("MyWorkflow")
interface MyWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.execute(arg: WorkflowArg): String
}

@Activity("MyActivity")
interface MyActivity {
    @ActivityMethod
    suspend fun ActivityContext.greet(name: String): String
}

class MyWorkflowImpl : MyWorkflow {
    override suspend fun WorkflowContext.execute(arg: WorkflowArg): String {
        val greeting = activity<MyActivity>().greet(arg.name)
        return "$greeting! You have requested count: ${arg.count}"
    }
}

class MyActivityImpl : MyActivity {
    override suspend fun ActivityContext.greet(name: String): String {
        return "Hello, $name"
    }
}

fun TemporalApplication.myMainModule() {
    install(PayloadSerialization) { json() }
    taskQueue("my-task-queue") {
        workflow(MyWorkflowImpl())
        activity(MyActivityImpl())
    }
}
```

## Use Cases

1. **Compiler plugin stubs** - The compiler plugin will generate interfaces from `@Workflow`/`@Activity`
   annotated classes for type-safe client usage
2. **Cross-language interop** - Define interfaces matching workflows/activities in other languages
3. **Testing** - Easy to mock interfaces for unit testing
4. **Shared contracts** - Publish interfaces in a separate module for client-only dependencies

## Generated Stubs (Future)

The compiler plugin will generate interfaces from annotated classes:

```kotlin
// User writes:
@Workflow("MyWorkflow")
class MyWorkflow { ... }

// Compiler plugin generates:
@Workflow("MyWorkflow")
interface MyWorkflowStub {
    @WorkflowRun
    suspend fun execute(arg: WorkflowArg): String
}

// Client can use:
val stub = client.newWorkflowStub<MyWorkflowStub>(...)
stub.execute(arg)
```