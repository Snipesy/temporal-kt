package com.surrealdev.temporal.testing

import com.surrealdev.temporal.activity.ActivityCancelledException
import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
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

/**
 * Tests for the [ActivityTestHarness].
 */
class ActivityTestHarnessTest {
    // =========================================================================
    // Test Activities
    // =========================================================================

    class GreetingActivity {
        @Activity
        fun greet(name: String): String = "Hello, $name!"

        @Activity
        fun greetMultiple(
            firstName: String,
            lastName: String,
        ): String = "Hello, $firstName $lastName!"
    }

    class ContextGreetingActivity {
        @Activity
        fun ActivityContext.greet(name: String): String {
            // Access context info to verify it's properly populated
            return "Hello, $name! (activity: ${info.activityId})"
        }
    }

    class SuspendGreetingActivity {
        @Activity
        suspend fun greet(name: String): String {
            delay(1) // Simulate async work
            return "Hello, $name!"
        }
    }

    class SuspendContextActivity {
        @Activity
        suspend fun ActivityContext.greet(name: String): String {
            delay(1)
            return "Hello, $name! (attempt: ${info.attempt})"
        }
    }

    class HeartbeatingActivity {
        @Activity
        suspend fun ActivityContext.longRunning(iterations: Int): Int {
            for (i in 1..iterations) {
                heartbeat(i)
                delay(1)
            }
            return iterations
        }

        @Activity
        suspend fun ActivityContext.longRunningWithCancellationCheck(iterations: Int): Int {
            for (i in 1..iterations) {
                heartbeat(i)
                delay(1)
            }
            return iterations
        }
    }

    class FailingActivity {
        @Activity
        fun fail(): String = throw IllegalStateException("Intentional failure")

        @Activity
        fun failWithCustomMessage(message: String): String = throw RuntimeException(message)
    }

    class CalculatorActivity {
        @Activity
        fun add(
            a: Int,
            b: Int,
        ): Int = a + b

        @Activity
        fun multiply(
            a: Int,
            b: Int,
        ): Int = a * b
    }

    class NullResultActivity {
        @Activity
        fun returnNull(): String? = null

        @Activity
        fun returnNullable(value: String?): String? = value
    }

    class UnitReturnActivity {
        var sideEffect: String = ""

        @Activity
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
        @Activity
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
    inner class RegistrationAndExecution {
        @Test
        fun `activity can be registered and executed`() =
            runActivityTest {
                register(GreetingActivity())

                val result = execute<String>("greet", "World")

                assertEquals("Hello, World!", result)
            }

        @Test
        fun `multiple activities can be registered`() =
            runActivityTest {
                register(GreetingActivity())
                register(CalculatorActivity())

                val greeting = execute<String>("greet", "Test")
                val sum = execute<Int>("add", 2, 3)

                assertEquals("Hello, Test!", greeting)
                assertEquals(5, sum)
            }

        @Test
        fun `same activity can be executed multiple times`() =
            runActivityTest {
                register(GreetingActivity())

                val result1 = execute<String>("greet", "Alice")
                val result2 = execute<String>("greet", "Bob")

                assertEquals("Hello, Alice!", result1)
                assertEquals("Hello, Bob!", result2)
            }
    }

    @Nested
    inner class ArgumentSerialization {
        @Test
        fun `single argument is serialized correctly`() =
            runActivityTest {
                register(GreetingActivity())

                val result = execute<String>("greet", "Test")

                assertEquals("Hello, Test!", result)
            }

        @Test
        fun `multiple arguments are serialized correctly`() =
            runActivityTest {
                register(GreetingActivity())

                val result = execute<String>("greetMultiple", "John", "Doe")

                assertEquals("Hello, John Doe!", result)
            }

        @Test
        fun `integer arguments work correctly`() =
            runActivityTest {
                register(CalculatorActivity())

                val sum = execute<Int>("add", 10, 20)
                val product = execute<Int>("multiply", 5, 6)

                assertEquals(30, sum)
                assertEquals(30, product)
            }

        @Test
        fun `complex serializable arguments work correctly`() =
            runActivityTest {
                register(ComplexActivity())

                val input =
                    ComplexInput(
                        name = "Test",
                        count = 5,
                        tags = listOf("a", "b", "c"),
                    )
                val result = execute<ComplexOutput>("process", input)

                assertEquals("Hello, Test!", result.greeting)
                assertEquals(10, result.processedCount)
            }
    }

    @Nested
    inner class ResultDeserialization {
        @Test
        fun `string result is deserialized correctly`() =
            runActivityTest {
                register(GreetingActivity())

                val result = execute<String>("greet", "World")

                assertEquals("Hello, World!", result)
            }

        @Test
        fun `integer result is deserialized correctly`() =
            runActivityTest {
                register(CalculatorActivity())

                val result = execute<Int>("add", 100, 200)

                assertEquals(300, result)
            }

        @Test
        fun `complex result is deserialized correctly`() =
            runActivityTest {
                register(ComplexActivity())

                val input = ComplexInput("User", 7, listOf("tag1"))
                val result = execute<ComplexOutput>("process", input)

                assertNotNull(result)
                assertEquals("Hello, User!", result.greeting)
                assertEquals(14, result.processedCount)
            }

        @Test
        fun `null result is handled correctly`() =
            runActivityTest {
                register(NullResultActivity())

                val result = execute<String?>("returnNull")

                assertNull(result)
            }

        @Test
        fun `nullable result with value works correctly`() =
            runActivityTest {
                register(NullResultActivity())

                val result = execute<String?>("returnNullable", "test")

                assertEquals("test", result)
            }

        @Test
        fun `nullable result with null works correctly`() =
            runActivityTest {
                register(NullResultActivity())

                val result = execute<String?>("returnNullable", null)

                assertNull(result)
            }

        @Test
        fun `Unit return type is handled correctly`() =
            runActivityTest {
                val activity = UnitReturnActivity()
                register(activity)

                execute<Unit>("doSomething", "test-value")

                assertEquals("test-value", activity.sideEffect)
            }
    }

    @Nested
    inner class ContextReceiver {
        @Test
        fun `activity with context receiver works`() =
            runActivityTest {
                register(ContextGreetingActivity())

                val result = execute<String>("greet", "World")

                assertTrue(result.startsWith("Hello, World!"))
                assertTrue(result.contains("activity:"))
            }

        @Test
        fun `activity without context receiver works`() =
            runActivityTest {
                register(GreetingActivity())

                val result = execute<String>("greet", "World")

                assertEquals("Hello, World!", result)
            }
    }

    @Nested
    inner class SuspendActivities {
        @Test
        fun `suspend activity works`() =
            runActivityTest {
                register(SuspendGreetingActivity())

                val result = execute<String>("greet", "World")

                assertEquals("Hello, World!", result)
            }

        @Test
        fun `non-suspend activity works`() =
            runActivityTest {
                register(GreetingActivity())

                val result = execute<String>("greet", "World")

                assertEquals("Hello, World!", result)
            }

        @Test
        fun `suspend activity with context receiver works`() =
            runActivityTest {
                register(SuspendContextActivity())

                val result = execute<String>("greet", "World")

                assertTrue(result.startsWith("Hello, World!"))
                assertTrue(result.contains("attempt:"))
            }
    }

    @Nested
    inner class Heartbeats {
        @Test
        fun `heartbeats are recorded`() =
            runActivityTest {
                register(HeartbeatingActivity())

                val result = execute<Int>("longRunning", 5)

                assertEquals(5, result)
                assertEquals(5, heartbeats.size)
            }

        @Test
        fun `heartbeats can be cleared`() =
            runActivityTest {
                register(HeartbeatingActivity())

                execute<Int>("longRunning", 3)
                assertEquals(3, heartbeats.size)

                clearHeartbeats()
                assertEquals(0, heartbeats.size)

                execute<Int>("longRunning", 2)
                assertEquals(2, heartbeats.size)
            }

        @Test
        fun `heartbeat records contain task token`() =
            runActivityTest {
                register(HeartbeatingActivity())

                execute<Int>("longRunning", 1)

                assertEquals(1, heartbeats.size)
                assertTrue(heartbeats[0].taskToken.isNotEmpty())
            }
    }

    @Nested
    inner class ActivityFailure {
        @Test
        fun `activity failure propagates correctly`() =
            runActivityTest {
                register(FailingActivity())

                val exception =
                    assertThrows<ActivityTestException> {
                        execute<String>("fail")
                    }

                assertEquals("Intentional failure", exception.message)
                assertEquals("fail", exception.activityType)
                assertNotNull(exception.stackTrace)
            }

        @Test
        fun `activity failure includes custom message`() =
            runActivityTest {
                register(FailingActivity())

                val exception =
                    assertThrows<ActivityTestException> {
                        execute<String>("failWithCustomMessage", "Custom error!")
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
                register(HeartbeatingActivity())

                execute<Int>("longRunning", 2)
                requestCancellation()

                assertTrue(heartbeats.isNotEmpty())
                assertTrue(isCancellationRequested)

                reset()

                assertTrue(heartbeats.isEmpty())
                assertFalse(isCancellationRequested)
            }

        @Test
        fun `activity sees cancellation through heartbeat mechanism`() =
            runActivityTest {
                register(HeartbeatingActivity())

                // Request cancellation before the activity runs
                requestCancellation()

                // Activity should fail when it tries to heartbeat
                val exception =
                    assertThrows<ActivityTestException> {
                        execute<Int>("longRunningWithCancellationCheck", 10)
                    }

                // The exception should indicate cancellation (via ActivityCancelledException)
                assertTrue(
                    exception.message?.contains("cancelled") == true ||
                        exception.cause is ActivityCancelledException,
                )
            }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `unregistered activity type throws exception`() =
            runActivityTest {
                // Don't register any activities

                val exception =
                    assertThrows<ActivityTestException> {
                        execute<String>("unknownMethod", "arg")
                    }

                assertTrue(exception.message?.contains("not registered") == true)
            }
    }
}
