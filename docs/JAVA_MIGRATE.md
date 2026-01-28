# Java SDK Migration Guide

This guide helps Java SDK users migrate to Temporal-Kt.

## Data Models

Temporal-Kt uses kotlinx.serialization. Annotate data classes with `@Serializable`:

```java
// Java (Jackson)
public class OrderRequest {
    private String orderId;
    private List<String> items;
    // getters, setters...
}
```

```kotlin
// Kotlin
@Serializable
data class OrderRequest(
    val orderId: String,
    val items: List<String>,
)
```

Polymorphic types use sealed classes:

```kotlin
@Serializable
sealed class PaymentEvent {
    @Serializable
    data class Approved(val transactionId: String) : PaymentEvent()

    @Serializable
    data class Declined(val reason: String) : PaymentEvent()
}
```

## Activities

No interface required. Annotate methods directly with `@Activity`:

```java
// Java
@ActivityInterface
public interface GreetingActivities {
    @ActivityMethod
    String composeGreeting(String greeting, String name);
}

public class GreetingActivitiesImpl implements GreetingActivities {
    @Override
    public String composeGreeting(String greeting, String name) {
        return greeting + " " + name;
    }
}
```

```kotlin
// Kotlin
class GreetingActivities {
    @Activity("composeGreeting")
    fun composeGreeting(greeting: String, name: String): String {
        return "$greeting $name"
    }
}
```

### Activity with Context

Use `ActivityContext` as a receiver to access heartbeat and info:

```kotlin
class FileProcessingActivities {
    @Activity("processFile")
    suspend fun ActivityContext.processFile(path: String): Int {
        var processed = 0
        for (chunk in readChunks(path)) {
            process(chunk)
            processed++
            heartbeat(processed)  // Report progress
        }
        return processed
    }
}
```

Or use the `activity()` helper to grab context from any coroutine:

```kotlin
class FileProcessingActivities {
    @Activity("processFile")
    suspend fun processFile(path: String): Int {
        var processed = 0
        for (chunk in readChunks(path)) {
            process(chunk)
            processed++
            activity().heartbeat(processed)
        }
        return processed
    }
}
```

## Workflows

Use `@Workflow` on the class and `@WorkflowRun` on the entry point:

```java
// Java
@WorkflowInterface
public interface GreetingWorkflow {
    @WorkflowMethod
    String getGreeting(String name);
}

public class GreetingWorkflowImpl implements GreetingWorkflow {
    private final GreetingActivities activities = Workflow.newActivityStub(...);

    @Override
    public String getGreeting(String name) {
        return activities.composeGreeting("Hello", name);
    }
}
```

```kotlin
// Kotlin
@Workflow("GreetingWorkflow")
class GreetingWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(name: String): String {
        return startActivity<String, String, String>(
            activityType = "composeGreeting",
            arg1 = "Hello",
            arg2 = name,
            startToCloseTimeout = 10.seconds,
        ).result()
    }
}
```

Or use the `workflow()` helper to grab context from any coroutine:

```kotlin
@Workflow("GreetingWorkflow")
class GreetingWorkflow {
    @WorkflowRun
    suspend fun run(name: String): String {
        return workflow().startActivity<String, String, String>(
            activityType = "composeGreeting",
            arg1 = "Hello",
            arg2 = name,
            startToCloseTimeout = 10.seconds,
        ).result()
    }
}
```

### Timers

```java
// Java
Workflow.sleep(Duration.ofSeconds(10));
```

```kotlin
// Kotlin
sleep(10.seconds)
delay(10.seconds)  // from kotlinx.coroutines will also work when called inside a workflow context
```

### Random & UUID

```java
// Java
int random = Workflow.newRandom().nextInt();
String uuid = Workflow.randomUUID().toString();
```

```kotlin
// Kotlin
val random = random().nextInt()
val uuid = randomUuid()
```

## Signals

```java
// Java
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(Order order);

    @SignalMethod
    void approve(String approver);
}
```

```kotlin
// Kotlin
@Workflow("OrderWorkflow")
class OrderWorkflow {
    private var approved = false
    private var approver: String? = null

    @WorkflowRun
    suspend fun WorkflowContext.run(order: Order): OrderResult {
        awaitCondition { approved }
        return OrderResult(order.id, approver!!)
    }

    @Signal("approve")
    fun WorkflowContext.approve(approver: String) {
        this@OrderWorkflow.approved = true
        this@OrderWorkflow.approver = approver
    }
}
```

## Queries

```java
// Java
@WorkflowInterface
public interface OrderWorkflow {
    @QueryMethod
    OrderStatus getStatus();
}
```

```kotlin
// Kotlin
@Workflow("OrderWorkflow")
class OrderWorkflow {
    private var status = OrderStatus.PENDING

    @Query("getStatus")
    fun WorkflowContext.getStatus(): OrderStatus = status

    @WorkflowRun
    suspend fun WorkflowContext.run(order: Order): OrderResult {
        status = OrderStatus.PROCESSING
        // ...
    }
}
```

## Updates

```java
// Java
@WorkflowInterface
public interface CartWorkflow {
    @UpdateMethod
    int addItem(CartItem item);

    @UpdateValidatorMethod(updateName = "addItem")
    void validateAddItem(CartItem item);
}
```

```kotlin
// Kotlin
@Workflow("CartWorkflow")
class CartWorkflow {
    private val items = mutableListOf<CartItem>()

    @UpdateValidator("addItem")
    fun validateAddItem(item: CartItem) {
        require(item.quantity > 0) { "Quantity must be positive" }
    }

    @Update("addItem")
    suspend fun WorkflowContext.addItem(item: CartItem): Int {
        items.add(item)
        return items.size
    }

    @WorkflowRun
    suspend fun WorkflowContext.run(): CartResult {
        awaitCondition { items.isNotEmpty() }
        // ...
    }
}
```

## Starting Workers

```java
// Java
WorkflowClient client = WorkflowClient.newInstance(service);
WorkerFactory factory = WorkerFactory.newInstance(client);
Worker worker = factory.newWorker("my-task-queue");
worker.registerWorkflowImplementationTypes(GreetingWorkflowImpl.class);
worker.registerActivitiesImplementations(new GreetingActivitiesImpl());
factory.start();
```

```kotlin
// Kotlin
fun main() {
    embeddedTemporal(module = {
        taskQueue("my-task-queue") {
            workflow(GreetingWorkflow())
            activity(GreetingActivities())
        }
    }).start(wait = true)
}
```

## Starting Workflows (Client)

```java
// Java
WorkflowClient client = WorkflowClient.newInstance(service);
GreetingWorkflow workflow = client.newWorkflowStub(
    GreetingWorkflow.class,
    WorkflowOptions.newBuilder()
        .setTaskQueue("my-task-queue")
        .build()
);
String result = workflow.getGreeting("World");
```

```kotlin
// Kotlin
val client = TemporalClient.connect("localhost:7233", "default")
val handle = client.startWorkflow<String, String>(
    workflowType = "GreetingWorkflow",
    taskQueue = "my-task-queue",
    arg = "World",
)
val result = handle.result()
```

### Signals & Queries from Client

```kotlin
// Send signal
handle.signal("approve", "manager@example.com")

// Query workflow
val status = handle.query<OrderStatus>("getStatus")
```
