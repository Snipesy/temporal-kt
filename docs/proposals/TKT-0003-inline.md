# TKT-0003: Inline Declarative

In many cases, activities and workflows are only defined in one place, and it would be faster to simply
inline them with their usage.

```kotlin
fun TemporalApplication.myMainModule() {
    install(PayloadSerialization) {
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

The above syntax has some... Very annoying problems.

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

Probably the easiest way to provide a declarative interface is to simply convert the declarative definitions into 
imperative interfaces, and then flatten them.

For example, the above inline workflow/activity definitions could be converted to:

```kotlin
@Workflow("MyWorkflow")
class MyWorkflow : WorkflowInterface<WorkflowArg, String> {
    @WorkflowRun
    override suspend fun WorkflowContext.execute(arg: WorkflowArg): String {
        val greeting = activity<MyActivity>().greet(arg.name)
        return "$greeting! You have requested count: ${arg.count}"
    }
}

class MyActivity : ActivityInterface<String, String> {
    @Activity("greet")
    override suspend fun ActivityContext.greet(name: String): String {
        return "Hello, $name"
    }
}

class UnnestedActivity : ActivityInterface<String, String> {
    @Activity("greet")
    override suspend fun ActivityContext.greet(name: String): String {
        return "Hi there, $name"
    }
}
```

And the actual application module would register these generated classes instead.

```kotlin
// During Compilation
fun TemporalApplication.myMainModule() {
    install(PayloadSerialization) {
        json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
    taskQueue("my-task-queue") {
        workflow<MyWorkflow>()
        activity<MyActivity>()
        activity<UnnestedActivity>()
    }
}
```

### Nested Task Queue Resolution

By default, nested activities and workflows would simply register themselves to their parent task queue.
But this many times is not what we want. We may want to call an activity or workflow on another task queue.

Since task queues have configuration options its important only one task queue definition declares config (probably at
the door DSL) in a similar manner to how Auth is handled in Ktor.

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
            activity<String>("MyActivity", options) { name ->
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
            activity<ExternalActivity>(options).doWork(arg.param)
        }
    }
}
```
