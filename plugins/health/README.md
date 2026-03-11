# Health Check Plugin

HTTP health check endpoints for Kubernetes readiness/liveness probes, powered by the JDK's built-in `HttpServer`.

## Installation

```kotlin
app.install(HealthCheckPlugin) {
    port = 8080          // default; use 0 for OS-assigned port
    readyPath = "/readyz"
    livePath = "/livez"
    healthPath = "/healthz"
}
```

## Endpoints

| Path       | Logic                            | 200            | 503                     |
|------------|----------------------------------|----------------|-------------------------|
| `/readyz`  | `app.isReady()`                  | `ready`        | `not ready`             |
| `/livez`   | `app.isAlive()`                  | `alive`        | `degraded or shutting down` |
| `/healthz` | `app.health()` + resource checks | JSON report    | JSON report             |

### `/healthz` response

```json
{
    "status": "HEALTHY",
    "workers": [
        {
            "task_queue": "my-queue",
            "namespace": "default",
            "status": "READY",
            "workflow_zombie_count": 0,
            "activity_zombie_count": 0
        }
    ],
    "resources": [
        {
            "name": "DatabasePool",
            "healthy": true
        }
    ]
}
```

Returns **200** when all workers are healthy and all resource checks pass, **503** otherwise.

## DI Plugin Integration

When the DI plugin (`plugins:di`) is installed, you can attach health checks to managed dependencies using the `healthCheck` DSL:

```kotlin
app.dependencies {
    activityOnly<DatabasePool> { DatabasePool(config) }
        .cleanup { it.shutdown() }
        .healthCheck { it.isConnected() }
}
```

The health check lambda receives the dependency instance and returns `true` if healthy.

**Lazy instantiation behavior:** Dependencies are created lazily on first use. If a dependency with a registered health check has not yet been instantiated (e.g., no workflow or activity has resolved it yet), the health check is **skipped and the resource is considered healthy**. This prevents health probes from triggering premature initialization of resources that may not be needed yet.

Once the dependency has been instantiated, the health check runs normally on every `/healthz` request.

## Accessing the Bound Port

When using `port = 0`, retrieve the actual port from the installed plugin instance:

```kotlin
val instance = app.plugin(HealthCheckPlugin)
val actualPort = instance.port
```
