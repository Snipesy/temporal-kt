# Testing Guide

Quick guide for testing Temporal workflows and activities.

## Dependency

```kotlin
// build.gradle.kts
dependencies {
    testImplementation("com.surrealdev.temporal:testing:x.y.z")
}
```

## Basic Usage

```kotlin
import com.surrealdev.temporal.testing.runTemporalTest
import com.surrealdev.temporal.client.startWorkflow
import com.surrealdev.temporal.workflow.result
import kotlin.test.Test
import kotlin.test.assertEquals

class MyWorkflowTest {
    @Test
    fun `test workflow`() = runTemporalTest {
        // Configure workflows and activities
        application {
            taskQueue("test-queue") {
                workflow<MyWorkflow>()
                activity(MyActivities())
            }
        }

        // Start workflow
        val handle = client().startWorkflow(
            workflowType = "MyWorkflow",
            taskQueue = "test-queue",
            workflowId = "test-wf-123",
            arg = "input"
        )

        // Wait for result
        val result: String = handle.result()
        assertEquals("expected", result)
    }
}
```

## Time Skipping

For workflows with timers/delays, use time-skipping mode to run tests instantly:

```kotlin
@Test
fun `test workflow with timer`() = runTemporalTest(timeSkipping = true) {
    application {
        taskQueue("test-queue") {
            workflow<WorkflowWithTimer>()
        }
    }

    // Start workflow that waits 1 hour
    val handle = client().startWorkflow(
        workflowType = "WorkflowWithTimer",
        taskQueue = "test-queue"
    )

    // Completes instantly - time is automatically skipped during result()
    val result: String = handle.result()
}
```

### Manual Time Control

```kotlin
@Test
fun `manual time control`() = runTemporalTest(timeSkipping = true) {
    application { /* ... */ }

    // Lock time to prevent auto-skipping
    lockTimeSkipping()

    val handle = client().startWorkflow(
        workflowType = "MyWorkflow",
        taskQueue = "test-queue"
    )

    // Manually advance time
    skipTime(1.hours)

    // Unlock to let workflow continue
    unlockTimeSkipping()

    val result: String = handle.result()
}
```

## Accessing Client

```kotlin
@Test
fun `multiple workflow executions`() = runTemporalTest {
    application { /* ... */ }

    val client = client()

    // Start multiple workflows
    val handle1 = client.startWorkflow(
        workflowType = "WorkflowA",
        taskQueue = "test-queue"
    )
    val handle2 = client.startWorkflow(
        workflowType = "WorkflowB",
        taskQueue = "test-queue"
    )

    // Wait for both
    val result1: String = handle1.result()
    val result2: String = handle2.result()
}
```

## Asserting History

Verify workflow execution history:

```kotlin
import com.surrealdev.temporal.testing.assertHistory

@Test
fun `assert workflow history`() = runTemporalTest {
    application { /* ... */ }

    val handle = client().startWorkflow(
        workflowType = "MyWorkflow",
        taskQueue = "test-queue"
    )
    handle.result<String>()

    // Assert specific events occurred
    handle.assertHistory {
        completed()
        hasTimerStarted()
        hasTimerFired()
        timerCount(1)
        noFailedActivities()
    }
}
```

### Available History Assertions

```kotlin
handle.assertHistory {
    // Workflow completion states
    completed()        // Workflow completed successfully
    failed()          // Workflow failed
    canceled()        // Workflow was canceled
    terminated()      // Workflow was terminated
    timedOut()        // Workflow timed out

    // Timer assertions
    hasTimerStarted()  // At least one timer started
    hasTimerFired()    // At least one timer fired
    timerCount(1)      // Exact number of timers

    // Activity assertions
    hasActivityScheduled()          // At least one activity scheduled
    hasActivityScheduled("myActivity") // Specific activity scheduled
    hasActivityCompleted()          // At least one activity completed
    hasActivityFailed()             // At least one activity failed
    noFailedActivities()            // No activities failed

    // Signal assertions
    hasSignal()                     // At least one signal received
    hasSignal("signalName")         // Specific signal received

    // Child workflow assertions
    hasChildWorkflowStarted()       // At least one child started
    hasChildWorkflowCompleted()     // At least one child completed

    // Custom assertions
    check("Custom assertion") { history ->
        history.isCompleted && history.timerCount() > 0
    }
}
```

## Test Server Access

For advanced time manipulation:

```kotlin
@Test
fun `advanced time control`() = runTemporalTest(timeSkipping = true) {
    application { /* ... */ }

    // Get current server time
    val time = getCurrentTime()
    println("Server time: $time")

    // Skip time forward by duration
    skipTime(30.minutes)

    // Advance to specific time
    advanceTimeTo(Instant.parse("2024-01-01T12:00:00Z"))
}
```

## Activity Unit Testing

For fast unit tests of activity logic without a Temporal server:

```kotlin
import com.surrealdev.temporal.testing.runActivityTest
import com.surrealdev.temporal.testing.assertHeartbeatCount

@Test
fun `test activity directly`() = runActivityTest {
    val activity = GreetingActivity()

    // Call activity method directly with mock context
    val result = withActivityContext {
        activity.greet("World")
    }

    assertEquals("Hello, World!", result)
    assertNoHeartbeats()
}

@Test
fun `test activity with heartbeats`() = runActivityTest {
    val activity = ProcessingActivity()

    val result = withActivityContext {
        activity.processItems(listOf("a", "b", "c"))
    }

    assertEquals(3, result)
    assertHeartbeatCount(3)
    assertLastHeartbeat { it == "progress: 100%" }
}

@Test
fun `test activity cancellation`() = runActivityTest {
    val activity = LongRunningActivity()

    requestCancellation()

    assertThrows<ActivityCancelledException> {
        withActivityContext {
            activity.longTask(1000)
        }
    }
}
```

### Configuring Activity Info

```kotlin
import com.surrealdev.temporal.testing.configureActivityInfo

@Test
fun `test activity with custom info`() = runActivityTest {
    val activity = RetryableActivity()

    configureActivityInfo {
        activityType = "CustomActivity"
        attempt = 3
        deadlineIn(30.seconds)
    }

    val result = withActivityContext {
        // Activity can check info.attempt
        activity.work()
    }
}
```

### Activity Test Assertions

```kotlin
assertHeartbeatCount(5)           // Exact count
assertNoHeartbeats()              // No heartbeats recorded
assertHasHeartbeats()             // At least one heartbeat
assertHeartbeats { it.size > 3 }  // Custom predicate
assertLastHeartbeat { it == 100 } // Check last value
```

## Tips

- **Time skipping** - Use `timeSkipping = true` for any workflow with timers/delays (this is the default)
- **Unique IDs** - Use `UUID.randomUUID()` for workflow IDs to avoid conflicts
- **Integration tag** - Tag slow tests with `@Tag("integration")` to skip in fast CI
- **Cleanup** - Server and workers automatically cleaned up after test
- **Real parameters** - Use named parameters for `startWorkflow()`: `workflowType`, `taskQueue`, `workflowId`, `arg`
- **Activity unit tests** - Use `runActivityTest` for fast activity logic tests without a server
- **Integration tests** - Use `runTemporalTest` for full end-to-end testing with real workflows

## See Also

- [Workflow Development](WORKFLOWS_IMPERATIVE.md)
- [Activity Development](ACTIVITIES_IMPERATIVE.md)