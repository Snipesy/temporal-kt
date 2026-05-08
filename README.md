[![Maven Central Version](https://img.shields.io/maven-central/v/com.surrealdev.temporal/core?link=https%3A%2F%2Fcentral.sonatype.com%2Fartifact%2Fcom.surrealdev.temporal%2Fcore)](https://central.sonatype.com/artifact/com.surrealdev.temporal/core)

# Temporal KT

A powerful application engine around the [Temporal Core SDK](https://github.com/temporalio/sdk-core).

## Features

### Activities and Workflows as coroutines

Activities and Workflows run as coroutines with full
`await` support.

```kotlin
@Activity("MyCoroutineActivity")
suspend fun myActivity(param: String): String = coroutineScope {
    launch {
        // Some background work
    }
    "Done"
}

@Workflow("MyCoroutineWorkflow")
class MyBadWorkflow {
    @WorkflowRun
    suspend fun run(): String  = coroutineScope {
        (0.100). map {
            async {
                myActivity.?????TODO.execute()
            }
        }.joinToString()
    }
}
```

_Dont want to use Coroutines? No problems. Each coroutine also runs in a dedicated `Virtual Thread` so block away!_

### Declarative Worker Building

TODO

### Built for Kotlinx Serialization

Temporal-KT supports end to end inlined types which works with kotlinx serialization out of box.

_Dont like Kotlinx serialization? No problem. You can make your own custom Serializer_

### Generated Stubs

TODO

### Powerful Plugins

Dependency injection, advanced hooks, health monitoring? You got it!

* ...
* ...
* ...



## Using Temporal KT

Read [./docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) for a full getting started guide.


## Developing Temporal KT

Read [./docs/DEV_SETUP.md](docs/DEV_SETUP.md) for development setup and architecture overview.
