# Concurrency Architecture

This document describes how Temporal-Kt manages coroutines, virtual threads, and concurrency to meet Temporal's deterministic replay requirements.

## Overview

Temporal-Kt uses a layered concurrency model:

```
TemporalApplication (SupervisorJob)
  └── ManagedWorker (per task queue)
       ├── WorkflowPoller → WorkflowDispatcher → WorkflowVirtualThread (persistent)
       └── ActivityPoller → ActivityDispatcher → ActivityVirtualThread (ephemeral)
```

### Workflow Execution Job Hierarchy

Each workflow run has a **job hierarchy** for structured concurrency:

```
parentJob (child of rootExecutorJob)
    │
    ├── workflowExecutionJob (SupervisorJob)
    │       │
    │       └── job (plain Job - failures propagate up)
    │               │
    │               ├── main workflow coroutine (via ctx.async)
    │               └── ctx.launch {} calls (siblings of main, children of job)
    │
    └── handlerJob (SupervisorJob)
            │
            ├── signal handlers (via launchHandler)
            └── update handlers (via launchHandler)
```

**Note:** This diagram shows the **Job hierarchy** for structured concurrency. The inner `job` is a plain `Job` (not `SupervisorJob`), so failures in `ctx.launch {}` propagate to cancel the main workflow coroutine. Signal/update handlers share the same `WorkflowContext` instance (so they can call `startActivity()`, `sleep()`, etc.) and the same `workflowDispatcher`, but are launched under `handlerJob` instead of the context's `job`.

**Why sibling jobs for handlers?** Signal/update handlers must continue running after the main workflow coroutine completes. In Kotlin's structured concurrency, cancelling a parent Job cancels all children. By making `handlerJob` a sibling of `workflowExecutionJob`:

1. Handlers can produce commands (e.g., `UpdateResponse`) even after the main workflow finishes
2. Terminal completion cancels `workflowExecutionJob` first, processes remaining handler work, then cancels `handlerJob`
3. Eviction cancels both jobs together via `terminateAllJobs()`

**Why `ctx.launch {}` creates siblings, not children of main coroutine?** 
This is a deliberate tradeoff to support the `WorkflowContext.` extension syntax where users don't need to manually
track coroutine scopes. The downside is that launched coroutines could outlive the main workflow function if not
properly awaited. Users should ensure all launched coroutines complete before returning from the workflow, or use
`coroutineScope {}` for stricter structured concurrency when needed.

**Warning behavior:** If handlers try to schedule new work (activities, timers) after `workflowCompleted = true`, a warning is logged since those commands will be ignored by the server.

### The Workflow Event Loop

Each activation drives the workflow through `WorkflowExecutor.runOnce`, which mirrors the Python SDK's `_run_once`:

1. Drain every task queued on the `WorkflowCoroutineDispatcher` (coroutine steps resumed by timers, activity results, signal handlers, ...).
2. Re-evaluate every `awaitCondition` predicate; any that became true queue their coroutine and the loop repeats. This happens even when the cycle scheduled a command, so a handler that flips a flag *and* starts a timer still wakes the coroutine awaiting that flag in the same activation.
3. When nothing is queued, yield the activation to the server. The workflow is either waiting on a Temporal operation (timer, activity, child, condition) or parked on an ordinary Kotlin primitive such as a `Channel`, `Mutex`, `CompletableDeferred` or `Job.join()`. Both are legitimate: the latter is resumed by a signal/update handler in a later activation, exactly like an `asyncio.Future` in Python.

**Escaped coroutines.** `withContext(Dispatchers.IO)`, `launch(Dispatchers.Default)` and friends move work off the workflow dispatcher. Their resume comes back onto the workflow dispatcher from a foreign thread, and if the activation had already been completed that resume would sit in the queue until the next activation (which may never come). The loop therefore walks the workflow's job tree before yielding: every kotlinx coroutine is a child `Job` of its creator and exposes its context, so an escaped coroutine is visible as an incomplete descendant whose `ContinuationInterceptor` is not the workflow dispatcher. While one exists the loop waits for it to dispatch back (bounded by deadlock detection). Escapes are still non-deterministic on replay and remain discouraged; the wait exists so they fail loudly rather than stall silently.

**Cancellation and normal return.** `CancelWorkflow` cancels the main coroutine's job. Kotlin cancellation is sticky: a cancelled coroutine's return value is normally discarded and any further `await` throws immediately. The SDK records the workflow function's return value separately, so a workflow that catches `WorkflowCancelledException` and returns completes normally (Python/Java semantics). Durable cleanup after catching the cancellation must run under `withContext(NonCancellable) { ... }`.

### Activity Execution Job Hierarchy

Activities have a simpler structure - no handlers, no replay:

```
rootActivityJob (SupervisorJob, child of worker's Job)
    │
    └── activityScope.launch job (per virtual thread)
            │
            └── runBlocking's Job (inside ActivityVirtualThread)
                    │
                    └── ActivityContext's coroutineContext
                            │
                            ├── activity coroutine
                            └── ctx.launch {} calls from activity code
```

Each activity execution:
- Gets a fresh virtual thread (ephemeral, not persistent like workflows)
- Runs inside `runBlocking` on that virtual thread
- No sibling jobs needed - activities don't have signal/update handlers
- `ActivityContext` implements `CoroutineScope`, so `ctx.launch {}` creates children of the activity's job
- Cancellation propagates from `rootActivityJob` down through the hierarchy

## Virtual Thread Strategy

### Workflows: Persistent Threads

Each workflow run gets a **dedicated virtual thread** that persists across activations:

```
Activation 1 (InitializeWorkflow)
  → Thread starts, workflow executes
  → Thread parks on activationQueue.take()

Activation 2 (ResolveActivity)
  → SAME thread unparks
  → ThreadLocals preserved, execution continues
  
So on and so forth
```

### Activities: Ephemeral Threads

Each activity execution gets a **new virtual thread** that terminates after completion:

### Zombie Thread Management

When a workflow or activity is canceled or evicted, an eviction job is launched that:
1. Cancels the coroutine job (allows cleanup via `finally` blocks)
2. Waits for the termination grace period
3. Interrupts the thread if still alive
4. Retries interruption at configurable intervals

If the thread doesn't terminate after exhausting all retry attempts, it's considered a **zombie thread** and counted toward the zombie limit.

**Common causes of zombie threads:**

1. Non-interruptible blocking operations (busy loops, certain native calls)
2. Workflow/activity code that catches and ignores `InterruptedException`
3. Extreme load preventing thread scheduling

Zombie threads are a MASSIVE problem if they arise and usually represent an error in workflow or activity code. They
can be difficult to debug, so TemporalKt will kick and scream if it detects one. This is a watchdog-like system unique
to Temporal Kotlin to help raise these issues in testing and production alike.

**Eviction lifecycle:**

```
Thread termination requested
  → Immediate termination: cancel job + Thread.interrupt()
  → Wait retryInterval (1s initial) using thread.join() for fast detection
  → If terminated: done
  → If still alive: enter zombie retry loop with exponential backoff
      → Log errors only after gracePeriod (10s default)
      → Count as zombie only after gracePeriod
      → Retry with exponential backoff: 1s → 2s → 4s → ... → 60s max
      → Thread.interrupt() on each retry
      → Give up after giveUpAfter (1h default) - thread remains leaked
```

A properly tuned worker should never spawn a single zombie thread.

**Zombie threshold behavior:**

When `maxZombieCount` is exceeded, Temporal-KT initiates application shutdown. Since zombie threads don't respond to
interruption, graceful shutdown will likely fail... But this at least means other workflows and activities can stop
gracefully. After `forceExitTimeout`, the system calls `System.exit(1)`

While that may seem harsh we cannot actually recover from zombie threads. Thread.stop() is extremely unsafe, and so the
most responsible action is to simply graceful shutdown as best we can and restart the worker entirely through some
external mechanism (Kubernetes, systemd, etc.).

Set `maxZombieCount = 0` to disable automatic shutdown.

Zombie threads do **NOT** count toward slot supplier limits after task completion.

## Configuration

Key concurrency settings per task queue:

### Slot Suppliers

| Setting                    | Default         | Description                          |
|----------------------------|-----------------|--------------------------------------|
| `workflowSlotSupplier`     | `FixedSize(10)` | Slot supplier for workflow tasks      |
| `activitySlotSupplier`     | `FixedSize(10)` | Slot supplier for activity tasks      |
| `localActivitySlotSupplier`| `FixedSize(10)` | Slot supplier for local activities    |

Slot suppliers can be `FixedSize(n)` for a simple concurrency limit, or `JvmResourceBased(...)` for
adaptive resource-based scaling using PID controllers that monitor JVM heap and CPU.

### Poller Behavior

| Setting                        | Default            | Description                                    |
|--------------------------------|--------------------|------------------------------------------------|
| `workflowPollerBehavior`       | `SimpleMaximum(5)` | Concurrent gRPC long-polls for workflow tasks   |
| `activityPollerBehavior`       | `SimpleMaximum(5)` | Concurrent gRPC long-polls for activity tasks   |
| `nexusPollerBehavior`          | `SimpleMaximum(2)` | Concurrent gRPC long-polls for nexus tasks      |
| `nonstickyToStickyPollRatio`   | `0.2`              | Fraction of workflow pollers for nonsticky queue |

Pollers are managed internally by the Rust Core SDK. Each worker has one Kotlin-side poll loop per task type, but Core SDK spawns multiple internal Tokio tasks to issue concurrent gRPC long-polls. Use `CorePollerBehavior.Autoscaling(min, max, initial)` for dynamic scaling based on server feedback.

### Deadlock Detection

| Setting                     | Default | Description                                            |
|-----------------------------|---------|--------------------------------------------------------|
| `workflowDeadlockTimeoutMs` | 2000    | Workflow activation timeout before deadlock error (0 to disable) |

### Heartbeat Throttling

| Setting                            | Default | Description                                     |
|------------------------------------|---------|------------------------------------------------|
| `maxHeartbeatThrottleIntervalMs`   | 60000   | Max interval for throttling activity heartbeats |
| `defaultHeartbeatThrottleIntervalMs` | 30000 | Default throttle when no heartbeat timeout set  |

### Shutdown

| Setting                  | Default | Description                                       |
|--------------------------|---------|---------------------------------------------------|
| `shutdownGracePeriodMs`  | 10000   | Grace period for polling jobs to complete         |
| `shutdownForceTimeoutMs` | 5000    | Additional timeout after force cancellation       |
| `forceExitTimeoutMs`     | 60000   | Timeout before System.exit(1) if shutdown stuck   |

### Zombie Thread Management (`ZombieEvictionConfig`)

| Setting           | Default   | Description                                                      |
|-------------------|-----------|------------------------------------------------------------------|
| `maxZombieCount`  | 10        | Zombie threshold before forcing shutdown (0 to disable)          |
| `retryInterval`   | 1s        | Initial interval between zombie eviction retry attempts          |
| `retryMaxDelay`   | 60s       | Maximum delay between retries (exponential backoff)              |
| `gracePeriod`     | 10s       | Time before counting thread as zombie and logging errors         |
| `giveUpAfter`     | 1h        | Time after which eviction stops retrying (thread remains leaked) |
| `shutdownTimeout` | 30s       | Timeout for waiting on eviction jobs during shutdown             |

| Setting           | Default   | Description                                                      |
|-------------------|-----------|------------------------------------------------------------------|
| `forceExitTimeout`| 1m        | Timeout before System.exit(1) if shutdown stuck due to zombies   |

