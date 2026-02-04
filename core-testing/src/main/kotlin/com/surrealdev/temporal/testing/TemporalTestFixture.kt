package com.surrealdev.temporal.testing

import com.surrealdev.temporal.annotation.TemporalDsl
import com.surrealdev.temporal.application.ShutdownConfig
import com.surrealdev.temporal.application.ShutdownConfigBuilder
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.TemporalApplicationBuilder
import com.surrealdev.temporal.application.VersioningBehavior
import com.surrealdev.temporal.application.WorkerDeploymentOptions
import com.surrealdev.temporal.application.WorkerDeploymentVersion
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.client.TemporalClientImpl
import com.surrealdev.temporal.common.SearchAttributeKey
import com.surrealdev.temporal.core.EphemeralServer
import com.surrealdev.temporal.core.TemporalDevServer
import com.surrealdev.temporal.core.TemporalRuntime
import com.surrealdev.temporal.core.TemporalTestServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Builder for configuring and running Temporal test applications.
 *
 * Similar to Ktor's `ApplicationTestBuilder`, this provides a DSL for:
 * - Configuring the application via [application] block
 * - Running test code after the app starts
 * - Automatic cleanup when tests complete
 * - Time manipulation when using [TemporalTestServer]
 *
 * Example:
 * ```kotlin
 * runTemporalTest {
 *     application {
 *         taskQueue("test-queue") {
 *             workflow<MyWorkflowImpl>()
 *         }
 *     }
 *
 *     // Test code runs here after app is started
 *     val client = client()
 *     val handle = client.startWorkflow(...)
 * }
 * ```
 *
 * For time-skipping tests:
 * ```kotlin
 * runTemporalTest(timeSkipping = true) {
 *     application {
 *         taskQueue("test-queue") {
 *             workflow<MyWorkflowWithTimerImpl>()
 *         }
 *     }
 *
 *     val client = client()
 *     // Start workflow with 1-hour timer - completes instantly when result() is called
 *     // Time skipping is automatically unlocked during result() and locked again after
 *     val result = client.startWorkflow(...).result()
 * }
 * ```
 */
@TemporalDsl
class TemporalTestApplicationBuilder internal constructor(
    private val server: EphemeralServer,
    private val parentCoroutineContext: CoroutineContext,
) {
    private var _application: TemporalApplication? = null
    private var cachedClient: TemporalClient? = null
    private var configured = false
    private var deploymentOptions: WorkerDeploymentOptions? = null
    private var shutdownConfig: ShutdownConfig = ShutdownConfig()

    /**
     * The test server instance, if time-skipping is enabled.
     *
     * This is null when using a regular dev server (timeSkipping = false).
     * Use this for direct access to TestService APIs like [TemporalTestServer.getCurrentTime].
     */
    val testServer: TemporalTestServer?
        get() = server as? TemporalTestServer

    /**
     * Configures worker deployment versioning for the test application.
     *
     * This must be called before [application] to take effect.
     *
     * @param version The deployment version identifying this worker
     * @param useVersioning If true (default), worker participates in versioned task routing
     * @param defaultVersioningBehavior Default behavior for workflows that don't specify their own
     */
    fun deployment(
        version: WorkerDeploymentVersion,
        useVersioning: Boolean = true,
        defaultVersioningBehavior: VersioningBehavior = VersioningBehavior.UNSPECIFIED,
    ) {
        check(!configured) { "deployment() must be called before application()" }
        deploymentOptions = WorkerDeploymentOptions(version, useVersioning, defaultVersioningBehavior)
    }

    /**
     * Configures shutdown behavior for the test application.
     *
     * This must be called before [application] to take effect.
     *
     * @param block Configuration block using [ShutdownConfigBuilder] DSL
     */
    fun shutdown(block: ShutdownConfigBuilder.() -> Unit) {
        check(!configured) { "shutdown() must be called before application()" }
        shutdownConfig = ShutdownConfigBuilder().apply(block).build()
    }

    /**
     * Configures and starts the Temporal application.
     *
     * This must be called before accessing [client] or other application features.
     * The application starts immediately after configuration completes.
     *
     * @param block Configuration block using [TemporalApplication] DSL (taskQueue, install, etc.)
     */
    suspend fun application(block: TemporalApplication.() -> Unit = {}) {
        check(!configured) { "Application already configured" }
        configured = true

        // Build the application with connection pre-configured to the server
        val appBuilder = TemporalApplicationBuilder(parentCoroutineContext)
        appBuilder.connection {
            target = "http://${this@TemporalTestApplicationBuilder.server.targetUrl}"
            namespace = "default"
        }

        // Apply deployment options if configured
        deploymentOptions?.let { options ->
            appBuilder.deployment(options.version, options.useWorkerVersioning)
        }

        // Apply shutdown config
        appBuilder.shutdown {
            gracePeriodMs = this@TemporalTestApplicationBuilder.shutdownConfig.gracePeriodMs
            forceTimeoutMs = this@TemporalTestApplicationBuilder.shutdownConfig.forceTimeoutMs
        }

        _application = appBuilder.build()

        // Apply user configuration on the built application
        _application!!.block()

        // Start the application
        _application!!.start()
    }

    /**
     * Gets a workflow client for interacting with Temporal.
     *
     * When using a test server (timeSkipping = true), this returns a [TemporalTestClient]
     * that automatically manages time skipping around workflow result awaits.
     *
     * The client is cached, so multiple calls return the same instance. This ensures
     * all workflow handles share the same time-skipping state tracker.
     *
     * @throws IllegalStateException if [application] hasn't been called
     */
    suspend fun client(): TemporalClient {
        checkStarted()

        // Return cached client if available
        cachedClient?.let { return it }

        val baseClient = _application!!.client()

        // Wrap in TemporalTestClient if using a test server for time-skipping support
        val client =
            if (testServer != null) {
                TemporalTestClient(baseClient as TemporalClientImpl, testServer!!)
            } else {
                baseClient
            }

        cachedClient = client
        return client
    }

    /**
     * The underlying [TemporalApplication] instance.
     *
     * @throws IllegalStateException if [application] hasn't been called
     */
    val application: TemporalApplication
        get() {
            checkStarted()
            return _application!!
        }

    // =========================================================================
    // Time Manipulation Convenience Methods
    // =========================================================================

    /**
     * Locks (disables) time skipping.
     *
     * When time skipping is locked, time advances at real-time pace.
     * The test server starts with time-skipping locked.
     *
     * @throws IllegalStateException if not using a test server (timeSkipping = false)
     */
    suspend fun lockTimeSkipping() {
        requireTestServer().lockTimeSkipping()
    }

    /**
     * Unlocks (enables) time skipping.
     *
     * When time skipping is unlocked and all workflows are waiting on timers,
     * the server automatically advances time to the next timer.
     *
     * @throws IllegalStateException if not using a test server (timeSkipping = false)
     */
    suspend fun unlockTimeSkipping() {
        requireTestServer().unlockTimeSkipping()
    }

    /**
     * Gets the current server time.
     *
     * This may differ from system time due to time skipping.
     *
     * @return The current server time
     * @throws IllegalStateException if not using a test server (timeSkipping = false)
     */
    suspend fun getCurrentTime(): Instant = requireTestServer().getCurrentTime()

    /**
     * Temporarily unlocks time skipping and advances time by the specified duration.
     *
     * @param duration The duration to advance time by
     * @throws IllegalStateException if not using a test server (timeSkipping = false)
     */
    suspend fun skipTime(duration: Duration) {
        requireTestServer().unlockTimeSkippingWithSleep(duration)
    }

    /**
     * Advances server time to the specified timestamp.
     *
     * @param time The target time to advance to
     * @throws IllegalStateException if not using a test server (timeSkipping = false)
     */
    suspend fun advanceTimeTo(time: Instant) {
        requireTestServer().sleepUntil(time)
    }

    private fun requireTestServer(): TemporalTestServer =
        testServer
            ?: throw IllegalStateException(
                "Time manipulation requires a test server. Use runTemporalTest(timeSkipping = true)",
            )

    private fun checkStarted() {
        check(_application != null) {
            "Application not started. Call application { } first."
        }
    }

    internal suspend fun cleanup() {
        _application?.close()
    }
}

/**
 * Runs a test with an ephemeral Temporal dev server.
 *
 * Example:
 * ```kotlin
 * @Test
 * fun `my workflow test`() = runTemporalTest {
 *     application {
 *         taskQueue("test-queue") {
 *             workflow<MyWorkflowImpl>()
 *             activity(MyActivityImpl())
 *         }
 *     }
 *
 *     val client = client()
 *     val handle = client.startWorkflow(...)
 *     // assertions...
 * }
 * ```
 *
 * For time-skipping tests:
 * ```kotlin
 * @Test
 * fun `workflow with timer`() = runTemporalTest(timeSkipping = true) {
 *     application {
 *         taskQueue("test-queue") {
 *             workflow<MyWorkflowWithTimerImpl>()
 *         }
 *     }
 *
 *     // Time skipping is automatically managed - unlocked during result(), locked otherwise
 *     val client = client()
 *     val result = client.startWorkflow(...).result() // 1-hour timer completes instantly
 * }
 * ```
 *
 * For tests with custom search attributes:
 * ```kotlin
 * @Test
 * fun `workflow with custom search attributes`() = runTemporalTest(
 *     searchAttributes = listOf(
 *         SearchAttributeKey.forInt("CustomIntField"),
 *         SearchAttributeKey.forKeyword("CustomKeyword"),
 *     )
 * ) {
 *     // CustomIntField and CustomKeyword are now available
 * }
 * ```
 *
 * @param timeSkipping If true, starts a test server that automatically skips time
 *                     when workflows are waiting on timers. This allows tests with
 *                     long timers (hours/days) to complete in milliseconds.
 * @param searchAttributes Custom search attributes to register with the server.
 */
fun runTemporalTest(
    timeSkipping: Boolean = true,
    searchAttributes: List<SearchAttributeKey<*>> = emptyList(),
    block: suspend TemporalTestApplicationBuilder.() -> Unit,
): TestResult = runTemporalTest(EmptyCoroutineContext, timeSkipping, searchAttributes, block)

/**
 * Runs a test with an ephemeral Temporal dev server.
 *
 * Uses real time (not virtual time) to avoid issues with timeouts completing instantly
 * in the test dispatcher against the Core SDK.
 *
 * @param parentCoroutineContext Additional coroutine context elements
 * @param timeSkipping If true, starts a test server that automatically skips time
 * @param searchAttributes Custom search attributes to register with the server.
 * @param block The test block with application configuration and test code
 */
fun runTemporalTest(
    parentCoroutineContext: CoroutineContext,
    timeSkipping: Boolean = true,
    searchAttributes: List<SearchAttributeKey<*>> = emptyList(),
    block: suspend TemporalTestApplicationBuilder.() -> Unit,
): TestResult =
    runTest(timeout = 60.seconds) {
        // Use real time dispatcher to avoid virtual time issues with timeouts
        // (similar to Ktor's runTestWithRealTime)
        // if we don't do this then we can run info FFM issues
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            runTestApplication(this.coroutineContext + parentCoroutineContext, timeSkipping, searchAttributes, block)
        }
    }

/**
 * Runs a test application within an existing [TestScope].
 *
 * Useful when you need more control over the test coroutine scope
 * or want to combine with other test infrastructure.
 *
 * @param parentCoroutineContext Additional coroutine context elements
 * @param timeSkipping If true, starts a test server that automatically skips time
 * @param searchAttributes Custom search attributes to register with the server.
 * @param block The test block with application configuration and test code
 */
suspend fun TestScope.runTestApplication(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    timeSkipping: Boolean = true,
    searchAttributes: List<SearchAttributeKey<*>> = emptyList(),
    block: suspend TemporalTestApplicationBuilder.() -> Unit,
) {
    // Convert SearchAttributeKey to CLI args format: name=type
    val searchAttributePairs = searchAttributes.map { it.name to it.typeName }

    TemporalRuntime.create().use { runtime ->
        val effectiveContext =
            if (parentCoroutineContext != EmptyCoroutineContext) {
                coroutineContext + parentCoroutineContext
            } else {
                coroutineContext
            }

        if (timeSkipping) {
            TemporalTestServer
                .start(
                    runtime = runtime,
                    searchAttributes = searchAttributePairs,
                ).use { testServer ->
                    // Time skipping starts LOCKED (Python SDK behavior)
                    // It will be automatically unlocked when awaiting workflow results
                    // via TemporalTestClient/TimeSkippingWorkflowHandle

                    val builder =
                        TemporalTestApplicationBuilder(
                            server = testServer,
                            parentCoroutineContext = effectiveContext,
                        )
                    try {
                        builder.block()
                    } finally {
                        builder.cleanup()
                    }
                }
        } else {
            TemporalDevServer
                .start(
                    runtime = runtime,
                    searchAttributes = searchAttributePairs,
                ).use { devServer ->
                    val builder =
                        TemporalTestApplicationBuilder(
                            server = devServer,
                            parentCoroutineContext = effectiveContext,
                        )
                    try {
                        builder.block()
                    } finally {
                        builder.cleanup()
                    }
                }
        }
    }
}
