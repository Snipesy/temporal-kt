# TKT-0013: Ktor Subroutine

Enables Ktor to run alongside Temporal workers within a single application via `install(Ktor)`.

## Basic Setup

```kotlin
fun main() = temporalApplication {
    install(Ktor) {
        port = 8080
    }

    taskQueue("main") {
        // HTTP routes alongside workflows/activities
        get("/health") {
            call.respondText("OK")
        }

        post("/orders") {
            val request = call.receive<CreateOrderRequest>()
            val handle = temporalClient.startWorkflow(request)
            call.respond(OrderResponse(workflowId = handle.workflowId))
        }

        workflow<CreateOrderWorkflow>()
        activity(PaymentActivity())
    }
}.start()
```

## With Routing (TKT-0009)

HTTP routes follow the same routing hierarchy as workflows/activities:

```kotlin
fun TemporalApplication.ordersModule() {
    install(Ktor) { port = 8080 }

    taskQueue("orders") {
        route("v1") {
            // HTTP: POST /v1/orders
            post("/orders") {
                val request = call.receive<CreateOrderRequest>()
                val handle = temporalClient.startWorkflow(request)
                call.respond(handle.workflowId)
            }

            // HTTP: GET /v1/orders/{id}
            get("/orders/{id}") {
                val id = call.parameters["id"]!!
                val handle = temporalClient.getWorkflowHandle(id)
                call.respond(handle.query(CreateOrderWorkflow::getStatus))
            }

            // Workflow type: "v1/CreateOrder"
            workflow<CreateOrderWorkflow>()
            activity(PaymentActivity())
        }
    }
}
```

## With Dependency Injection (TKT-0004)

Shared dependencies across HTTP handlers and workflows/activities:

```kotlin
fun TemporalApplication.module() {
    install(Ktor) { port = 8080 }

    dependencies {
        provide<OrderRepository> { PostgresOrderRepository() }
        provide<TemporalClient> { temporalClient }
    }

    taskQueue("orders") {
        post("/orders") {
            val repo: OrderRepository by dependencies
            val client: TemporalClient by dependencies
            // ...
        }

        workflow<CreateOrderWorkflow>()
    }
}
```

## Accessing Temporal Client

The `temporalClient` is available in HTTP route handlers without further configuration.

```kotlin
get("/workflows/{id}/result") {
    val handle = temporalClient.getWorkflowHandle(call.parameters["id"]!!)
    val result: MyResult = handle.result()
    call.respond(result)
}

post("/workflows/{id}/signal") {
    val handle = temporalClient.getWorkflowHandle(call.parameters["id"]!!)
    handle.signal(MyWorkflow::mySignal, call.receive())
    call.respond(HttpStatusCode.Accepted)
}
```

## Worker Configuration

The main advantage of co-locating Ktor with Temporal workers is a single deployment with seamless interop between HTTP 
and workflow orchestration. However, production deployments often benefit from separation—running workflows on dedicated 
workers while the HTTP layer scales independently.

Since workflows and activities are defined in code but execute on any worker polling the same task queue, the same 
codebase can be deployed in different configurations via YAML:

```yaml
# application.yaml
temporal:
  ktor:
    enabled: true
    port: 8080
  workers:
    enabled: true
```
