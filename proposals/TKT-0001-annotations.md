# TKT-0001: Annotation-Based Definitions (Primary)

Workflows and activities are defined using annotations similar to the Python SDK's decorator pattern.
This provides compile-time discoverability, IDE support, and type-safe client stubs.

```kotlin
@Serializable
data class WorkflowArg(
    val name: String,
    val count: Int = 0
)

@Workflow("MyWorkflow")
class MyWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.execute(arg: WorkflowArg): String {
        val greeting = activity<MyActivity>().greet(arg.name)
        return "$greeting! You have requested count: ${arg.count}"
    }
}

@Activity("MyActivity")
class MyActivity {
    @ActivityMethod
    suspend fun ActivityContext.greet(name: String): String {
        return "Hello, $name"
    }
}

fun TemporalApplication.myMainModule() {
    install(KotlinxSerialization) {
        json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
    taskQueue("my-task-queue") {
        workflow<MyWorkflow>()
        activity<MyActivity>()
    }
}
```

## Available Annotations

| Annotation | Target | Description |
|------------|--------|-------------|
| `@Workflow(name)` | Class | Marks a workflow definition. Name defaults to class name. |
| `@WorkflowRun` | Function | The workflow entry point (exactly one per workflow). |
| `@Activity(name)` | Class | Marks an activity definition. Name defaults to class name. |
| `@ActivityMethod(name)` | Function | An activity method. Name defaults to function name. |
| `@Signal(name, dynamic)` | Function | Signal handler. Dynamic handlers receive all unhandled signals. |
| `@Query(name, dynamic)` | Function | Query handler. Must not modify state. |
| `@Update(name, dynamic)` | Function | Update handler. Can modify state and return values. |
| `@UpdateValidator(updateName)` | Function | Validator for an update handler. |