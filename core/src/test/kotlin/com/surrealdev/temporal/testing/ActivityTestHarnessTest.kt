package com.surrealdev.temporal.testing

import com.surrealdev.temporal.activity.ActivityCancelledException
import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.activity.heartbeat
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the simplified [ActivityTestHarness].
 *
 * The new approach calls activity methods directly with a mock ActivityContext,
 * skipping serialization and dispatcher overhead.
 */
class ActivityTestHarnessTest {
    // =========================================================================
    // Test Activities
    // =========================================================================

    class GreetingActivity {
        fun greet(name: String): String = "Hello, $name!"

        fun greetMultiple(
            firstName: String,
            lastName: String,
        ): String = "Hello, $firstName $lastName!"
    }

    class ContextGreetingActivity {
        fun ActivityContext.greet(name: String): String {
            // Access context info to verify it's properly populated
            return "Hello, $name! (activity: ${info.activityId})"
        }
    }

    class SuspendGreetingActivity {
        suspend fun greet(name: String): String {
            delay(1) // Simulate async work
            return "Hello, $name!"
        }
    }

    class SuspendContextActivity {
        suspend fun ActivityContext.greet(name: String): String {
            delay(1)
            return "Hello, $name! (attempt: ${info.attempt})"
        }
    }

    class HeartbeatingActivity {
        suspend fun ActivityContext.longRunning(iterations: Int): Int {
            for (i in 1..iterations) {
                heartbeat(i)
                delay(1)
            }
            return iterations
        }

        suspend fun ActivityContext.longRunningWithCancellationCheck(iterations: Int): Int {
            for (i in 1..iterations) {
                ensureNotCancelled()
                heartbeat(i)
                delay(1)
            }
            return iterations
        }
    }

    class FailingActivity {
        fun fail(): String = throw IllegalStateException("Intentional failure")

        fun failWithCustomMessage(message: String): String = throw RuntimeException(message)
    }

    class CalculatorActivity {
        fun add(
            a: Int,
            b: Int,
        ): Int = a + b

        fun multiply(
            a: Int,
            b: Int,
        ): Int = a * b
    }

    class NullResultActivity {
        fun returnNull(): String? = null

        fun returnNullable(value: String?): String? = value
    }

    class UnitReturnActivity {
        var sideEffect: String = ""

        fun doSomething(value: String) {
            sideEffect = value
        }
    }

    @Serializable
    data class ComplexInput(
        val name: String,
        val count: Int,
        val tags: List<String>,
    )

    @Serializable
    data class ComplexOutput(
        val greeting: String,
        val processedCount: Int,
    )

    class ComplexActivity {
        fun process(input: ComplexInput): ComplexOutput =
            ComplexOutput(
                greeting = "Hello, ${input.name}!",
                processedCount = input.count * 2,
            )
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Nested
    inner class BasicExecution {
        @Test
        fun `activity can be called directly`() =
            runActivityTest {
                val activity = GreetingActivity()

                val result =
                    withActivityContext {
                        activity.greet("World")
                    }

                assertEquals("Hello, World!", result)
            }

        @Test
        fun `multiple activities can be called`() =
            runActivityTest {
                val greeting = GreetingActivity()
                val calculator = CalculatorActivity()

                val greetResult =
                    withActivityContext {
                        greeting.greet("Test")
                    }

                val sumResult =
                    withActivityContext {
                        calculator.add(2, 3)
                    }

                assertEquals("Hello, Test!", greetResult)
                assertEquals(5, sumResult)
            }

        @Test
        fun `same activity can be called multiple times`() =
            runActivityTest {
                val activity = GreetingActivity()

                val result1 =
                    withActivityContext {
                        activity.greet("Alice")
                    }

                val result2 =
                    withActivityContext {
                        activity.greet("Bob")
                    }

                assertEquals("Hello, Alice!", result1)
                assertEquals("Hello, Bob!", result2)
            }
    }

    @Nested
    inner class Arguments {
        @Test
        fun `single argument works`() =
            runActivityTest {
                val activity = GreetingActivity()

                val result =
                    withActivityContext {
                        activity.greet("Test")
                    }

                assertEquals("Hello, Test!", result)
            }

        @Test
        fun `multiple arguments work`() =
            runActivityTest {
                val activity = GreetingActivity()

                val result =
                    withActivityContext {
                        activity.greetMultiple("John", "Doe")
                    }

                assertEquals("Hello, John Doe!", result)
            }

        @Test
        fun `integer arguments work`() =
            runActivityTest {
                val activity = CalculatorActivity()

                val sum =
                    withActivityContext {
                        activity.add(10, 20)
                    }

                val product =
                    withActivityContext {
                        activity.multiply(5, 6)
                    }

                assertEquals(30, sum)
                assertEquals(30, product)
            }

        @Test
        fun `complex objects work`() =
            runActivityTest {
                val activity = ComplexActivity()
                val input =
                    ComplexInput(
                        name = "Test",
                        count = 5,
                        tags = listOf("a", "b", "c"),
                    )

                val result =
                    withActivityContext {
                        activity.process(input)
                    }

                assertEquals("Hello, Test!", result.greeting)
                assertEquals(10, result.processedCount)
            }
    }

    @Nested
    inner class Results {
        @Test
        fun `string result works`() =
            runActivityTest {
                val activity = GreetingActivity()

                val result =
                    withActivityContext {
                        activity.greet("World")
                    }

                assertEquals("Hello, World!", result)
            }

        @Test
        fun `integer result works`() =
            runActivityTest {
                val activity = CalculatorActivity()

                val result =
                    withActivityContext {
                        activity.add(100, 200)
                    }

                assertEquals(300, result)
            }

        @Test
        fun `complex result works`() =
            runActivityTest {
                val activity = ComplexActivity()
                val input = ComplexInput("User", 7, listOf("tag1"))

                val result =
                    withActivityContext {
                        activity.process(input)
                    }

                assertNotNull(result)
                assertEquals("Hello, User!", result.greeting)
                assertEquals(14, result.processedCount)
            }

        @Test
        fun `null result works`() =
            runActivityTest {
                val activity = NullResultActivity()

                val result =
                    withActivityContext {
                        activity.returnNull()
                    }

                assertNull(result)
            }

        @Test
        fun `nullable result with value works`() =
            runActivityTest {
                val activity = NullResultActivity()

                val result =
                    withActivityContext {
                        activity.returnNullable("test")
                    }

                assertEquals("test", result)
            }

        @Test
        fun `nullable result with null works`() =
            runActivityTest {
                val activity = NullResultActivity()

                val result =
                    withActivityContext {
                        activity.returnNullable(null)
                    }

                assertNull(result)
            }

        @Test
        fun `Unit return type works`() =
            runActivityTest {
                val activity = UnitReturnActivity()

                withActivityContext {
                    activity.doSomething("test-value")
                }

                assertEquals("test-value", activity.sideEffect)
            }
    }

    @Nested
    inner class ContextReceiver {
        @Test
        fun `activity with context receiver works`() =
            runActivityTest {
                val activity = ContextGreetingActivity()

                val result =
                    withActivityContext {
                        with(activity) { greet("World") }
                    }

                assertTrue(result.startsWith("Hello, World!"))
                assertTrue(result.contains("activity:"))
            }

        @Test
        fun `activity without context receiver works`() =
            runActivityTest {
                val activity = GreetingActivity()

                val result =
                    withActivityContext {
                        activity.greet("World")
                    }

                assertEquals("Hello, World!", result)
            }
    }

    @Nested
    inner class SuspendActivities {
        @Test
        fun `suspend activity works`() =
            runActivityTest {
                val activity = SuspendGreetingActivity()

                val result =
                    withActivityContext {
                        activity.greet("World")
                    }

                assertEquals("Hello, World!", result)
            }

        @Test
        fun `non-suspend activity works`() =
            runActivityTest {
                val activity = GreetingActivity()

                val result =
                    withActivityContext {
                        activity.greet("World")
                    }

                assertEquals("Hello, World!", result)
            }

        @Test
        fun `suspend activity with context receiver works`() =
            runActivityTest {
                val activity = SuspendContextActivity()

                val result =
                    withActivityContext {
                        with(activity) { greet("World") }
                    }

                assertTrue(result.startsWith("Hello, World!"))
                assertTrue(result.contains("attempt:"))
            }
    }

    @Nested
    inner class Heartbeats {
        @Test
        fun `heartbeats are recorded`() =
            runActivityTest {
                val activity = HeartbeatingActivity()

                val result =
                    withActivityContext {
                        with(activity) { longRunning(5) }
                    }

                assertEquals(5, result)
                assertHeartbeatCount(5)
            }

        @Test
        fun `heartbeats can be cleared`() =
            runActivityTest {
                val activity = HeartbeatingActivity()

                withActivityContext {
                    with(activity) { longRunning(3) }
                }
                assertHeartbeatCount(3)

                clearHeartbeats()
                assertHeartbeatCount(0)

                withActivityContext {
                    with(activity) { longRunning(2) }
                }
                assertHeartbeatCount(2)
            }

        @Test
        fun `heartbeat values are accessible`() =
            runActivityTest {
                val activity = HeartbeatingActivity()

                withActivityContext {
                    with(activity) { longRunning(3) }
                }

                assertEquals(listOf(1, 2, 3), deserializeHeartbeats<Int>())
            }

        @Test
        fun `assertLastHeartbeat works`() =
            runActivityTest {
                val activity = HeartbeatingActivity()

                withActivityContext {
                    with(activity) { longRunning(5) }
                }

                val lastValue = deserializeHeartbeats<Int>().lastOrNull()
                assertEquals(5, lastValue)
            }

        @Test
        fun `assertHeartbeats predicate works`() =
            runActivityTest {
                val activity = HeartbeatingActivity()

                withActivityContext {
                    with(activity) { longRunning(5) }
                }

                val values = deserializeHeartbeats<Int>()
                assertTrue(values.all { it != null && it > 0 })
            }
    }

    @Nested
    inner class ActivityFailure {
        @Test
        fun `activity failure propagates correctly`() =
            runActivityTest {
                val activity = FailingActivity()

                val exception =
                    assertThrows<IllegalStateException> {
                        withActivityContext {
                            activity.fail()
                        }
                    }

                assertEquals("Intentional failure", exception.message)
            }

        @Test
        fun `activity failure includes custom message`() =
            runActivityTest {
                val activity = FailingActivity()

                val exception =
                    assertThrows<RuntimeException> {
                        withActivityContext {
                            activity.failWithCustomMessage("Custom error!")
                        }
                    }

                assertEquals("Custom error!", exception.message)
            }
    }

    @Nested
    inner class Cancellation {
        @Test
        fun `requestCancellation sets cancellation flag`() =
            runActivityTest {
                assertFalse(isCancellationRequested)

                requestCancellation()

                assertTrue(isCancellationRequested)
            }

        @Test
        fun `resetCancellation clears the flag`() =
            runActivityTest {
                requestCancellation()
                assertTrue(isCancellationRequested)

                resetCancellation()

                assertFalse(isCancellationRequested)
            }

        @Test
        fun `reset clears both heartbeats and cancellation`() =
            runActivityTest {
                val activity = HeartbeatingActivity()

                withActivityContext {
                    with(activity) { longRunning(2) }
                }
                requestCancellation()

                assertHasHeartbeats()
                assertTrue(isCancellationRequested)

                reset()

                assertNoHeartbeats()
                assertFalse(isCancellationRequested)
            }

        @Test
        fun `activity sees cancellation through heartbeat`() =
            runActivityTest {
                val activity = HeartbeatingActivity()

                requestCancellation()

                val exception =
                    assertThrows<ActivityCancelledException> {
                        withActivityContext {
                            with(activity) { longRunningWithCancellationCheck(10) }
                        }
                    }

                assertTrue(exception.message?.contains("cancelled") == true)
            }

        @Test
        fun `ensureNotCancelled throws when cancelled`() =
            runActivityTest {
                requestCancellation()

                assertThrows<ActivityCancelledException> {
                    withActivityContext {
                        ensureNotCancelled()
                    }
                }
            }
    }

    @Nested
    inner class ActivityInfo {
        @Test
        fun `activity info is accessible`() =
            runActivityTest {
                val activity = ContextGreetingActivity()

                val result =
                    withActivityContext {
                        with(activity) { greet("Test") }
                    }

                assertTrue(result.contains("activity:"))
            }

        @Test
        fun `activity info can be customized`() =
            runActivityTest {
                val activity = SuspendContextActivity()

                configureActivityInfo {
                    attempt = 3
                    activityType = "CustomActivity"
                }

                val result =
                    withActivityContext {
                        with(activity) { greet("Test") }
                    }

                assertTrue(result.contains("attempt: 3"))
            }

        @Test
        fun `deadline can be configured`() =
            runActivityTest {
                configureActivityInfo {
                    deadlineIn(30.seconds)
                }

                withActivityContext {
                    assertNotNull(info.deadline)
                }
            }
    }

    @Nested
    inner class AssertionHelpers {
        @Test
        fun `assertNoHeartbeats works`() =
            runActivityTest {
                val activity = GreetingActivity()

                withActivityContext {
                    activity.greet("Test")
                }

                assertNoHeartbeats()
            }

        @Test
        fun `assertHasHeartbeats works`() =
            runActivityTest {
                val activity = HeartbeatingActivity()

                withActivityContext {
                    with(activity) { longRunning(1) }
                }

                assertHasHeartbeats()
            }
    }
}
