# Proposals

This doc shows some proposed DSL styles for defining Temporal workflows and activities in Kotlin.

## TKT-0001 Full Declarative

Rather than declaring interfaces and implementations separately, we could define workflows and activity directly
in the task queue context. This allows for insanely fast development. You could define an activity within a 
workflow.

```kotlin

data class WorkflowArg(
    val name: String,
    val count: Int = 0
)

fun TemporalApplication.myMainModule() { // TemporalApplicationContext ->
    install(KotlinxSerialization) {
        json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
    taskQueue("my-task-queue") { // TemporalTaskQueueContext ->

        workflow<WorkflowArg>("MyWorkflow") { arg -> // WorkflowContext ->
            // Define local activity inline which is directly executed
            activity<String>("MyActivity") { name -> // ActivityContext ->
                "Hello, $name"
            }.let { greeting ->
                "$greeting! You have requested count: ${arg.count}"
            }
        }
        
        activity<String>("UnnestedActivity") { name -> // ActivityContext ->
            "Hi there, $name"
        }
    }
}
```

The problem with this is we do not have a great way of building a typed stub for this, so you would need to
redefine types on the client side.

See: https://ktor.io/docs/server-openapi.html

```
val client = app.client {
    // Client configuration
}
val result: String = client.executeWorkflow<WorkflowArg>("MyWorkflow"
    // ... workflow options
)

```

In addition, if we want to be able to register a remote activity within a workflow we would need some form of gradle plugin to
be able to parse the workflow for child activities. This would allow us to handle situations where.

1. Worker A starts Workflow W which defines Activity A inline
2. Worker B hosts Activity A (inline of Workflow W) but does not run Workflow W

Local activities could in theory work this way but there may be limitations with temporal core.

## TKT-0002 Dependency Injection

Piggy back onto context management to inject scoped dependencies without cumbersome arg passing in a similar 
manner to KTOR.

```kotlin

fun TemporalApplication.myMainModule() { 

    dependencies {
        provide<SomeService> { SomeServiceImpl() }
        provide<() -> AnotherService> { { AnotherServiceImpl(get()) } }
    }
}
```

You can also i.e. provide dependecies specific to a tasks queue

```kotlin

fun TemporalApplication.myMainModule() { // TemporalApplicationContext ->
    taskQueue("my-task-queue") { // TemporalTaskQueueContext ->

        dependencies {
            provide<QueueSpecificService> { QueueSpecificServiceImpl() }
        }

        workflow<MyWorkflowImpl>()
        activity<MyActivityImpl>()
    }
    
    taskQueue("my-other-task-queue") { // TemporalTaskQueueContext ->

        dependencies {
            provide<QueueSpecificService> { OtherQueueSpecificServiceImpl() }
        }

        workflow<OtherWorkflowImpl>()
        activity<OtherActivityImpl>()
    }
}
```

Then in your workflow or activity you can retrieve the dependencies from the context

```kotlin

class WorkflowImpl() {
    suspend fun WorkflowContext.execute(arg: WorkflowArg): String {
        val service: SomeService by dependencies
        // use services...
    }
}
```

## TKT-0003 Module Registration

Module registration is already a core idea of temporal-kt, but it needs to be fleshed out.

1. Build a env config system (likely just use Hoplite)
2. Allow modules to be selected via config


