package com.surrealdev.temporal.testing

import com.surrealdev.temporal.workflow.internal.SkipDispatcherCheck
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a workflow unit test with dispatcher validation disabled.
 *
 * Use this for unit testing workflow context operations (like startActivity,
 * sleep, etc.) without spinning up a full Temporal server or workflow environment.
 *
 * This is thread-safe and can be used with parallel test execution, unlike
 * a static flag approach.
 *
 * Example:
 * ```kotlin
 * @Test
 * fun `startActivity generates correct command`() = runWorkflowUnitTest {
 *     val context = createTestContext()
 *     context.startActivity<String>("MyActivity", options)
 *     // assertions...
 * }
 * ```
 *
 * @param timeout Test timeout (default 60 seconds)
 * @param block The test block to execute
 */
fun runWorkflowUnitTest(
    timeout: Duration = 60.seconds,
    block: suspend () -> Unit,
): TestResult =
    runTest(timeout = timeout) {
        withContext(SkipDispatcherCheck) {
            block()
        }
    }
