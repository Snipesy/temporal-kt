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

Zombie threads do **NOT** count toward `maxConcurrentWorkflows` or `maxConcurrentActivities` after task completion.

## Configuration

Key concurrency settings per task queue:

### Concurrency Limits

| Setting                  | Default | Description                         |
|--------------------------|---------|-------------------------------------|
| `maxConcurrentWorkflows` | 200     | Max concurrent workflow activations |
| `maxConcurrentActivities`| 200     | Max concurrent activity executions  |

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

