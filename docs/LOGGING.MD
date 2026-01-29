# LOGGING

Temporal-KT Sets the SLF4J logging context for activity and workflow coroutine scopes.

Simply construct a SL4J logger, and use it as normal. The MDC context will automatically include useful information
such as workflow ID, run ID, activity ID, etc.

## Workflow Maps

* taskQueue: The task queue the workflow or activity is running on.
* namespace: The Temporal namespace the workflow or activity is running in.
* workflowType: The type of the workflow.
* runId: The unique run ID of the workflow execution.
* taskQueue: The task queue the workflow or activity is running on.

## Activity Maps

* taskQueue: The task queue the workflow or activity is running on.
* namespace: The Temporal namespace the workflow or activity is running in.
* activityType: The type of the activity.
* activityId: The unique ID of the activity execution.
* workflowId: The unique ID of the workflow execution the activity belongs to.
* runId: The unique run ID of the workflow execution the activity belongs to.

## Generic Activity Logger

If you do not want to create your own logger, the activity context and workflow context provides a generic logger.

```kotlin
@Activity("example-activity")
fun exampleActivity() {
    val logger = activity().logger()
    logger.info("This is an activity log message")
}
```
