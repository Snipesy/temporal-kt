# TKT-0004: Dependency Injection

Piggy back onto context management to inject scoped dependencies without cumbersome arg passing
in a similar manner to KTOR.

```kotlin
fun TemporalApplication.myMainModule() {
    dependencies {
        provide<SomeService> { SomeServiceImpl() }
        provide<() -> AnotherService> { { AnotherServiceImpl(get()) } }
    }
}
```

You can also provide dependencies specific to a task queue:

```kotlin
fun TemporalApplication.myMainModule() {
    taskQueue("my-task-queue") {
        dependencies {
            provide<QueueSpecificService> { QueueSpecificServiceImpl() }
        }
        workflow<MyWorkflow>()
        activity<MyActivity>()
    }
}
```

Then in your workflow or activity:

```kotlin
@Workflow("MyWorkflow")
class MyWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.execute(arg: WorkflowArg): String {
        val service: SomeService by dependencies
        // use services...
    }
}
```

## Scope Considerations

Dependencies should be categorized by their determinism requirements:

```kotlin
// Workflow-safe dependencies (must be deterministic)
val config: WorkflowConfig by workflowDependencies

// Activity dependencies (can have side effects)
val httpClient: HttpClient by activityDependencies
```