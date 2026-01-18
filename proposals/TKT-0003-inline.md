# TKT-0003: Inline Declarative

For rapid prototyping, workflows and activities can be defined inline within the task queue context.
This trades type-safe client stubs for development speed.

```kotlin
fun TemporalApplication.myMainModule() {
    install(KotlinxSerialization) {
        json = Json {
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
