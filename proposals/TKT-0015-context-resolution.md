# Workflow Context Resolution

Currently, workflows require WorkflowContext to be explicitly specified.

```kotlin
@Workflow("MyWorkflow")
class MyWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.execute(arg: WorkflowArg): String {
        val greeting = activity<MyActivity>().greet(arg.name)
        return "$greeting! Count: ${arg.count}"
    }
}
```

We can instead just resolve these from the receiver scope in a similar manner to how launch,
async, etc.. Work. This is also similar to how python handles it

```kotlin
@Workflow("MyWorkflow")
class MyWorkflow {
    @WorkflowRun
    suspend fun execute(arg: WorkflowArg): String {
        val greeting = activity<MyActivity>().greet(arg.name)
        return "$greeting! Count: ${arg.count}"
    }
}
```

The only concern is this means the user can call these functions outside a workflow context which would
result in a runtime error.
