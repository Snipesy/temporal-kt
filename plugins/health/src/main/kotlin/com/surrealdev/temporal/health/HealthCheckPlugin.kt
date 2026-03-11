package com.surrealdev.temporal.health

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.health.ApplicationStatus
import com.surrealdev.temporal.application.plugin.createApplicationPlugin
import com.surrealdev.temporal.dependencies.HealthCheckRegistryKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

private val logger = LoggerFactory.getLogger("com.surrealdev.temporal.health.HealthCheckPlugin")

@OptIn(ExperimentalSerializationApi::class)
val healthJson =
    Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

@Serializable
data class HealthResponse(
    val status: String,
    val workers: List<WorkerHealth>,
    val resources: List<ResourceHealth> = emptyList(),
)

@Serializable
data class WorkerHealth(
    val taskQueue: String,
    val namespace: String,
    val status: String,
    val workflowZombieCount: Int,
    val activityZombieCount: Int,
)

@Serializable
data class ResourceHealth(
    val name: String,
    val healthy: Boolean,
    val error: String? = null,
)

/**
 * Configuration for the [HealthCheckPlugin].
 */
@TemporalDsl
class HealthCheckConfig {
    /** Port for the health check HTTP server. Use 0 for an OS-assigned port. */
    var port: Int = 8080

    /** Path for readiness probe. */
    var readyPath: String = "/readyz"

    /** Path for liveness probe. */
    var livePath: String = "/livez"

    /** Path for full health report (JSON). */
    var healthPath: String = "/healthz"
}

/**
 * The installed instance of the health check plugin, providing access to the
 * actual bound port (useful when configured with port 0).
 */
class HealthCheckInstance internal constructor() {
    @Volatile
    private var server: HttpServer? = null

    /** The actual port the health check server is listening on. */
    val port: Int
        get() = server?.address?.port ?: error("Health check server is not running")

    internal fun start(httpServer: HttpServer) {
        server = httpServer
    }

    internal fun stop() {
        server?.stop(0)
        server = null
    }
}

/**
 * HTTP health check plugin for Temporal KT.
 *
 * Starts a lightweight JDK [HttpServer] that exposes readiness, liveness, and
 * full health report endpoints for K8s probes.
 *
 * The `/healthz` endpoint combines [TemporalApplication.health] worker status with
 * resource health checks from the DI plugin's health check registry (if present).
 *
 * ## Usage
 *
 * ```kotlin
 * val app = TemporalApplication { ... }
 * app.install(HealthCheckPlugin) {
 *     port = 8081
 * }
 * ```
 *
 * ## Endpoints
 *
 * | Path      | Logic                            | 200          | 503                   |
 * |-----------|----------------------------------|--------------|-----------------------|
 * | `/readyz` | `app.isReady()`                  | ready        | not ready             |
 * | `/livez`  | `app.isAlive()`                  | alive        | degraded/shutting down |
 * | `/healthz`| `app.health()` + resource checks | JSON report  | JSON report           |
 */
val HealthCheckPlugin =
    createApplicationPlugin("HealthCheck", ::HealthCheckConfig) { config ->
        val instance = HealthCheckInstance()

        application {
            onPreStartup { ctx ->
                instance.start(startHealthServer(config, ctx.application))
            }

            onStartupFailed {
                instance.stop()
            }

            onShutdown {
                instance.stop()
            }
        }

        instance
    }

private fun startHealthServer(
    config: HealthCheckConfig,
    app: TemporalApplication,
): HttpServer {
    val server = HttpServer.create(InetSocketAddress(config.port), 0)

    server.createContext(config.readyPath) { exchange ->
        exchange.handleSafely {
            val ready = app.isReady()
            val status = if (ready) 200 else 503
            val body = if (ready) "ready" else "not ready"
            exchange.sendResponse(status, body, "text/plain")
        }
    }

    server.createContext(config.livePath) { exchange ->
        exchange.handleSafely {
            val alive = app.isAlive()
            val status = if (alive) 200 else 503
            val body = if (alive) "alive" else "degraded or shutting down"
            exchange.sendResponse(status, body, "text/plain")
        }
    }

    server.createContext(config.healthPath) { exchange ->
        exchange.handleSafely {
            val report = app.health()
            val registry = app.attributes.getOrNull(HealthCheckRegistryKey)
            val resourceChecks = registry?.runAll() ?: emptyList()

            val allHealthy =
                report.status == ApplicationStatus.HEALTHY &&
                    resourceChecks.all { it.healthy }
            val httpStatus = if (allHealthy) 200 else 503

            val response =
                HealthResponse(
                    status = report.status.name,
                    workers =
                        report.workers.map { w ->
                            WorkerHealth(
                                taskQueue = w.taskQueue,
                                namespace = w.namespace,
                                status = w.status.name,
                                workflowZombieCount = w.workflowZombieCount,
                                activityZombieCount = w.activityZombieCount,
                            )
                        },
                    resources =
                        resourceChecks.map { r ->
                            ResourceHealth(
                                name = r.name,
                                healthy = r.healthy,
                                error = r.error,
                            )
                        },
                )

            val json = healthJson.encodeToString(response)
            exchange.sendResponse(httpStatus, json, "application/json")
        }
    }

    server.executor = null
    server.start()

    val boundPort = server.address.port
    logger.info(
        "Health check server started on port {} (ready={}, live={}, health={})",
        boundPort,
        config.readyPath,
        config.livePath,
        config.healthPath,
    )
    return server
}

private inline fun HttpExchange.handleSafely(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        logger.error("Health check endpoint error", e)
        try {
            sendResponse(500, "internal error", "text/plain")
        } catch (_: Exception) {
            close()
        }
    }
}

private fun HttpExchange.sendResponse(
    status: Int,
    body: String,
    contentType: String,
) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    responseHeaders.set("Content-Type", "$contentType; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
