package com.surrealdev.temporal.testing

import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.TemporalApplicationBuilder
import com.surrealdev.temporal.client.TemporalClient
import com.surrealdev.temporal.core.TemporalDevServer
import com.surrealdev.temporal.core.TemporalRuntime
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * Builder for configuring and running Temporal test applications.
 *
 * Similar to Ktor's `ApplicationTestBuilder`, this provides a DSL for:
 * - Configuring the application via [application] block
 * - Running test code after the app starts
 * - Automatic cleanup when tests complete
 *
 * Example:
 * ```kotlin
 * runTemporalTest {
 *     application {
 *         taskQueue("test-queue") {
 *             workflow(MyWorkflowImpl())
 *         }
 *     }
 *
 *     // Test code runs here after app is started
 *     val client = client()
 *     val handle = client.startWorkflow(...)
 * }
 * ```
 */
class TemporalTestApplicationBuilder internal constructor(
    private val devServer: TemporalDevServer,
    private val parentCoroutineContext: CoroutineContext,
) {
    private var _application: TemporalApplication? = null
    private var configured = false

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

        // Build the application with connection pre-configured to dev server
        val appBuilder = TemporalApplicationBuilder(parentCoroutineContext)
        appBuilder.connection {
            target = "http://${devServer.targetUrl}"
            namespace = "default"
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
     * @throws IllegalStateException if [application] hasn't been called
     */
    suspend fun client(): TemporalClient {
        checkStarted()
        return _application!!.client()
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
 *             workflow(MyWorkflowImpl())
 *             activity(MyActivityImpl())
 *         }
 *     }
 *
 *     val client = client()
 *     val handle = client.startWorkflow(...)
 *     // assertions...
 * }
 * ```
 */
fun runTemporalTest(block: suspend TemporalTestApplicationBuilder.() -> Unit): TestResult =
    runTemporalTest(EmptyCoroutineContext, block)

/**
 * Runs a test with an ephemeral Temporal dev server.
 *
 * @param parentCoroutineContext Additional coroutine context elements
 * @param block The test block with application configuration and test code
 */
fun runTemporalTest(
    parentCoroutineContext: CoroutineContext,
    block: suspend TemporalTestApplicationBuilder.() -> Unit,
): TestResult =
    runTest(context = parentCoroutineContext, timeout = 60.seconds) {
        runTestApplication(parentCoroutineContext, block)
    }

/**
 * Runs a test application within an existing [TestScope].
 *
 * Useful when you need more control over the test coroutine scope
 * or want to combine with other test infrastructure.
 */
suspend fun TestScope.runTestApplication(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    block: suspend TemporalTestApplicationBuilder.() -> Unit,
) {
    TemporalRuntime.create().use { runtime ->
        TemporalDevServer.start(runtime, timeoutSeconds = 120).use { devServer ->
            val effectiveContext =
                if (parentCoroutineContext != EmptyCoroutineContext) {
                    coroutineContext + parentCoroutineContext
                } else {
                    coroutineContext
                }

            val builder =
                TemporalTestApplicationBuilder(
                    devServer = devServer,
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
