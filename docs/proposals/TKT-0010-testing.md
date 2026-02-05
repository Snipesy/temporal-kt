# TKT-0010: Test Application Environment

A testing framework for unit and integration testing of workflows and activities.

## Test Application

Similar to Ktor's `testApplication`, provides an isolated environment without real Temporal server connections:

```kotlin
@Test
fun `test order workflow`() = testTemporalApplication {
    application {
        ordersModule()
    }

    val result = executeWorkflow<CreateOrderWorkflow, OrderResult>(
        arg = CreateOrderRequest(itemId = "123", quantity = 2)
    )

    assertEquals(OrderStatus.COMPLETED, result.status)
}
```

## Activity Testing

Test activities in isolation using `ActivityEnvironment`:

```kotlin
@Test
fun `test payment activity`() = runTest {
    val env = ActivityEnvironment()

    val result = env.run(PaymentActivity()::charge, ChargeRequest(100.0))

    assertEquals(ChargeResult.SUCCESS, result)
}
```

### Heartbeat Testing

Capture and verify heartbeats:

```kotlin
@Test
fun `test long running activity heartbeats`() = runTest {
    val env = ActivityEnvironment()
    val heartbeats = mutableListOf<Any?>()
    env.onHeartbeat = { heartbeats.add(it) }

    env.run(LongRunningActivity()::process, data)

    assertEquals(listOf(25, 50, 75, 100), heartbeats)
}
```

## Workflow Testing with Mocked Activities

Replace activities with mocks for isolated workflow testing:

```kotlin
@Test
fun `test workflow with mocked activities`() = testTemporalApplication {
    application {
        taskQueue("orders") {
            workflow<CreateOrderWorkflow>()

            // Mock the activity
            activity<PaymentActivity> {
                onCharge { ChargeResult.SUCCESS }
                onRefund { RefundResult.SUCCESS }
            }
        }
    }

    val result = executeWorkflow<CreateOrderWorkflow, OrderResult>(
        arg = CreateOrderRequest(itemId = "123")
    )

    assertEquals(OrderStatus.COMPLETED, result.status)
}
```

## Time Skipping

Fast-forward through workflow timers without real delays:

```kotlin
@Test
fun `test workflow with delays`() = testTemporalApplication {
    timeSkipping = true  // Enable automatic time skipping

    application {
        ordersModule()
    }

    // This workflow has a 24-hour delay, but test completes instantly
    val result = executeWorkflow<DelayedWorkflow, Result>(arg)

    assertEquals(Expected, result)
}
```

### Manual Time Control

```kotlin
@Test
fun `test timeout behavior`() = testTemporalApplication {
    application { ... }

    val handle = startWorkflow(OrderWorkflow::class, "test-queue", arg = arg)

    // Advance time manually
    advanceTime(Duration.ofHours(1))

    // Verify workflow state after 1 hour
    val status = handle.query(OrderWorkflow::getStatus)
    assertEquals(OrderStatus.PENDING_REMINDER, status)

    advanceTime(Duration.ofHours(23))

    val result = handle.result()
    assertEquals(OrderStatus.EXPIRED, result.status)
}
```

## Event History Replay

Validate workflow determinism by replaying historical executions:

```kotlin
@Test
fun `test workflow replay`() = runTest {
    val replayer = WorkflowReplayer(
        workflows = listOf(OrderWorkflow::class)
    )

    // Load history from file or test resource
    val history = loadWorkflowHistory("order-workflow-history.json")

    // Throws if replay fails (non-determinism detected)
    replayer.replayWorkflow(history)
}
```
