# Temporal KT - Implementation Progress

This document tracks the implementation status of Temporal SDK features in the Kotlin SDK.

## Status Legend

| Status | Description |
|--------|-------------|
| **Stable** | Feature is complete and tested |
| **Incubating** | Feature is implemented but API may change |
| **In Development** | Feature is actively being worked on |
| **Not Implemented** | Feature is planned but not yet started |
| **Infeasible** | Feature cannot be implemented or is not applicable |

---

## API Design

| Feature | Status          | Description | Kotlin Source |
|---------|-----------------|-------------|---------------|
| Annotation-Based Definitions | Incubating      | `@Workflow`, `@Activity`, `@WorkflowRun`, `@ActivityMethod` | [Annotations.kt](core/src/main/kotlin/com/surrealdev/temporal/annotation/Annotations.kt) |
| Interface-Based Definitions | Not Implemented | For interop and compiler plugin stubs | [Annotations.kt](core/src/main/kotlin/com/surrealdev/temporal/annotation/Annotations.kt) |
| Inline Declarative | Not Implemented | Lambda-based workflow/activity definitions | - |
| DSL Scope Safety | Incubating      | `@TemporalDsl` marker annotation | [Annotations.kt](core/src/main/kotlin/com/surrealdev/temporal/annotation/Annotations.kt) |

## Core Components

| Feature | Status | Temporal Docs | Kotlin Source |
|---------|--------|---------------|---------------|
| Workflows | Incubating | [Workflows](https://docs.temporal.io/workflows) | [WorkflowContext.kt](core/src/main/kotlin/com/surrealdev/temporal/workflow/WorkflowContext.kt) |
| Activities | Incubating | [Activities](https://docs.temporal.io/activities) | [ActivityContext.kt](core/src/main/kotlin/com/surrealdev/temporal/activity/ActivityContext.kt) |
| Workers | Incubating | [Workers](https://docs.temporal.io/workers) | [ManagedWorker.kt](core/src/main/kotlin/com/surrealdev/temporal/application/worker/ManagedWorker.kt) |
| Temporal Client | Incubating | [Temporal Client](https://docs.temporal.io/encyclopedia/temporal-sdks#temporal-client) | [TemporalClient.kt](core/src/main/kotlin/com/surrealdev/temporal/client/TemporalClient.kt) |

## Workflow Features

| Feature             | Status          | Temporal Docs                                                                               | Kotlin Source                                                                                             |
|---------------------|-----------------|---------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| Workflow Execution  | Incubating      | [Workflow Execution](https://docs.temporal.io/workflow-execution)                           | [WorkflowExecutor.kt](core/src/main/kotlin/com/surrealdev/temporal/workflow/internal/WorkflowExecutor.kt) |
| Workflow Options    | Incubating  | [Workflow Options](https://docs.temporal.io/develop/java/core-application#workflow-options) | [TemporalClient.kt](core/src/main/kotlin/com/surrealdev/temporal/client/TemporalClient.kt)                |
| Child Workflows     | Incubating | [Child Workflows](https://docs.temporal.io/develop/java/child-workflows)                    | [WorkflowContext.kt](core/src/main/kotlin/com/surrealdev/temporal/workflow/WorkflowContext.kt)            |
| Continue-As-New     | Incubating | [Continue-As-New](https://docs.temporal.io/develop/java/continue-as-new)                    | -                                                                                                         |
| Workflow Timers     | Incubating      | [Timers](https://docs.temporal.io/develop/java/timers)                                      | [WorkflowContext.kt](core/src/main/kotlin/com/surrealdev/temporal/workflow/WorkflowContext.kt)            |
| Workflow Versioning | Incubating | [Versioning](https://docs.temporal.io/develop/java/versioning)                              | -                                                                                                         |
| Side Effects        | Infeasible | [Side Effects](https://docs.temporal.io/develop/java/side-effects)                          | Local Activities are the go to                                                                            |
| Deterministic UUID  | Incubating      | [Determinism](https://docs.temporal.io/workflows#deterministic-constraints)                 | [WorkflowContext.kt](core/src/main/kotlin/com/surrealdev/temporal/workflow/WorkflowContext.kt)            |

## Activity Features

| Feature                   | Status          | Temporal Docs                                                                               | Kotlin Source                                                                                         |
|---------------------------|-----------------|---------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------|
| Activity Execution        | Incubating      | [Activity Execution](https://docs.temporal.io/activities#activity-execution)                | [ActivityDispatcher.kt](core/src/main/kotlin/com/surrealdev/temporal/activity/internal/ActivityDispatcher.kt) |
| Activity Options          | Incubating | [Activity Options](https://docs.temporal.io/develop/java/core-application#activity-options) | [WorkflowContext.kt](core/src/main/kotlin/com/surrealdev/temporal/workflow/WorkflowContext.kt) |
| Local Activities          | Not Implemented | [Local Activities](https://docs.temporal.io/develop/java/core-application#local-activities) | -                                                                                                     |
| Activity Heartbeats       | In Development  | [Heartbeats](https://docs.temporal.io/develop/java/failure-detection#heartbeat-an-activity) | [ActivityContext.kt](core/src/main/kotlin/com/surrealdev/temporal/activity/ActivityContext.kt) |
| Async Activity Completion | Incubating | [Async Completion](https://docs.temporal.io/develop/java/asynchronous-activity-completion)  | -                                                                                                     |
| Activity Retry Policy     | In Development  | [Retry Policy](https://docs.temporal.io/develop/java/failure-detection#retry-policy)        | [WorkflowContext.kt](core/src/main/kotlin/com/surrealdev/temporal/workflow/WorkflowContext.kt) |

## Message Passing

| Feature | Status | Temporal Docs | Kotlin Source |
|---------|--------|---------------|---------------|
| Signals | Incubating | [Signals](https://docs.temporal.io/develop/java/message-passing#signals) | [Annotations.kt](core/src/main/kotlin/com/surrealdev/temporal/annotation/Annotations.kt) |
| Queries | Incubating | [Queries](https://docs.temporal.io/develop/java/message-passing#queries) | [Annotations.kt](core/src/main/kotlin/com/surrealdev/temporal/annotation/Annotations.kt) |
| Updates | Incubating | [Updates](https://docs.temporal.io/develop/java/message-passing#updates) | [Annotations.kt](core/src/main/kotlin/com/surrealdev/temporal/annotation/Annotations.kt) |
| Dynamic Handlers | Incubating | [Dynamic Handlers](https://docs.temporal.io/develop/java/message-passing#dynamic-handler) | [Annotations.kt](core/src/main/kotlin/com/surrealdev/temporal/annotation/Annotations.kt) |

## Cancellation & Termination

| Feature | Status | Temporal Docs | Kotlin Source |
|---------|--------|---------------|---------------|
| Workflow Cancellation | Incubating | [Cancellation](https://docs.temporal.io/develop/java/cancellation) | [TemporalClient.kt](core/src/main/kotlin/com/surrealdev/temporal/client/TemporalClient.kt) |
| Workflow Termination | Incubating | [Termination](https://docs.temporal.io/develop/java/cancellation#terminate-a-workflow) | [TemporalClient.kt](core/src/main/kotlin/com/surrealdev/temporal/client/TemporalClient.kt) |
| Activity Cancellation | Incubating | [Activity Cancellation](https://docs.temporal.io/develop/java/cancellation#cancel-an-activity) | [ActivityContext.kt](core/src/main/kotlin/com/surrealdev/temporal/activity/ActivityContext.kt) |

## Failure Handling

| Feature | Status         | Temporal Docs | Kotlin Source |
|---------|----------------|---------------|---------------|
| Workflow Timeouts | Incubating     | [Timeouts](https://docs.temporal.io/develop/java/failure-detection#workflow-timeouts) | [TemporalClient.kt](core/src/main/kotlin/com/surrealdev/temporal/client/TemporalClient.kt) |
| Activity Timeouts | Incubating     | [Activity Timeouts](https://docs.temporal.io/develop/java/failure-detection#activity-timeouts) | [WorkflowContext.kt](core/src/main/kotlin/com/surrealdev/temporal/workflow/WorkflowContext.kt) |
| Retry Policies | Incubating | [Retry Policies](https://docs.temporal.io/develop/java/failure-detection#retry-policy) | [WorkflowContext.kt](core/src/main/kotlin/com/surrealdev/temporal/workflow/WorkflowContext.kt) |

## Data Conversion & Serialization

| Feature | Status | Temporal Docs | Kotlin Source |
|---------|--------|---------------|---------------|
| Data Converters | Not Implemented | [Data Converters](https://docs.temporal.io/develop/java/converters) | - |
| Kotlinx Serialization | Incubating | - | [KotlinxSerialization.kt](core/src/main/kotlin/com/surrealdev/temporal/serialization/KotlinxSerialization.kt) |
| Payload Encryption | Not Implemented | [Encryption](https://docs.temporal.io/develop/java/converters#encryption) | - |
| Payload Compression | Not Implemented | [Compression](https://docs.temporal.io/develop/java/converters#compression) | - |

## Observability

| Feature | Status | Temporal Docs | Kotlin Source |
|---------|--------|---------------|---------------|
| Logging | Not Implemented | [Logging](https://docs.temporal.io/develop/java/observability#logging) | - |
| Metrics | Not Implemented | [Metrics](https://docs.temporal.io/develop/java/observability#metrics) | - |
| Tracing | Not Implemented | [Tracing](https://docs.temporal.io/develop/java/observability#tracing) | - |
| Visibility | Not Implemented | [Visibility](https://docs.temporal.io/develop/java/observability#visibility) | - |
| Search Attributes | Not Implemented | [Search Attributes](https://docs.temporal.io/develop/java/observability#search-attributes) | - |

## Testing

| Feature | Status         | Temporal Docs | Kotlin Source |
|---------|----------------|---------------|---------------|
| Test Framework | Incubating     | [Testing](https://docs.temporal.io/develop/java/testing) | [TemporalTestFixture.kt](core/src/testFixtures/kotlin/com/surrealdev/temporal/testing/TemporalTestFixture.kt) |
| Workflow Replay | Incubating     | [Replay](https://docs.temporal.io/develop/java/testing#replay) | - |
| Time Skipping | Incubating | [Time Skipping](https://docs.temporal.io/develop/java/testing#skip-time) | [TimeSkippingTest.kt](core/src/test/kotlin/com/surrealdev/temporal/testing/TimeSkippingTest.kt) |

## Advanced Features

| Feature | Status | Temporal Docs | Kotlin Source |
|---------|--------|---------------|---------------|
| Interceptors | Not Implemented | [Interceptors](https://docs.temporal.io/develop/java/interceptors) | - |
| Schedules | Not Implemented | [Schedules](https://docs.temporal.io/develop/java/schedules) | - |
| Temporal Nexus | Not Implemented | [Nexus](https://docs.temporal.io/develop/java/nexus) | - |
| Workflow Reset | Incubating | [Reset](https://docs.temporal.io/develop/java/reset) | - |

## Infrastructure

| Feature | Status | Temporal Docs | Kotlin Source |
|---------|--------|---------------|---------------|
| Worker Configuration | Incubating | [Worker Configuration](https://docs.temporal.io/develop/java/core-application#worker) | - |
| Connection/TLS | In Development | [Connection](https://docs.temporal.io/develop/java/core-application#connect-to-a-dev-cluster) | [TemporalApplication.kt](core/src/main/kotlin/com/surrealdev/temporal/application/TemporalApplication.kt) |
| Namespaces | Incubating | [Namespaces](https://docs.temporal.io/namespaces) | [TemporalApplication.kt](core/src/main/kotlin/com/surrealdev/temporal/application/TemporalApplication.kt) |


**Last Updated:** 2026-01-26
