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


## Configuration

Key concurrency settings per task queue:

| Setting | Default | Description |
|---------|---------|-------------|
| `maxConcurrentWorkflows` | 100 | Max concurrent workflow activations |
| `maxConcurrentActivities` | 100 | Max concurrent activity executions |
| `workflowDeadlockTimeoutMs` | 2000 | Deadlock detection timeout (0 to disable) |
| `shutdownGracePeriodMs` | 10000 | Time to wait for graceful shutdown |
| `maxZombieCount` | 10 | Zombie threshold before fatal error |

