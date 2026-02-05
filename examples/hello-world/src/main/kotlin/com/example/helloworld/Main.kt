package com.example.helloworld

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.embeddedTemporal
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.workflow.WorkflowContext
import com.surrealdev.temporal.workflow.result
import com.surrealdev.temporal.workflow.startActivity
import com.surrealdev.temporal.workflow.workflow
import kotlin.time.Duration.Companion.seconds

/**
 * Hello World example demonstrating basic Temporal application setup.
 *
 * This example shows how to:
 * - Create an embedded Temporal application
 * - Define a workflow with the @Workflow and @WorkflowRun annotations
 * - Register workflows and activities on a task queue
 *
 * Prerequisites:
 * - A Temporal server running on localhost:7233
 *   (or use the dev server: `temporal server start-dev`)
 */
fun main() {
    val app =
        embeddedTemporal(
            module = {
                taskQueue("hello-world-queue") {
                    workflow<GreetingWorkflow>()
                    activity(GreetingActivity())
                }
            },
        )

    println("Starting Hello World Temporal application...")
    println("Listening on task queue: hello-world-queue")
    println()
    println("To start a workflow, use the Temporal CLI:")
    println("  temporal workflow start --task-queue hello-world-queue --type GreetingWorkflow --input '\"World\"'")
    println()
    println("Press Ctrl+C to stop")

    app.start(wait = true)
}

// =============================================================================
// Workflow Definition
// =============================================================================

/**
 * A simple greeting workflow that orchestrates the greeting process.
 *
 * This workflow:
 * 1. Waits for a short delay (demonstrating timers)
 * 2. Calls an activity to format the greeting
 * 3. Returns the result
 */
@Workflow("GreetingWorkflow")
class GreetingWorkflow {
    /**
     * The main workflow entry point.
     *
     * @param name The name to greet
     * @return A personalized greeting message
     */
    @WorkflowRun
    suspend fun run(name: String): String {
        // Log workflow start (will appear in workflow history)
        println("Workflow started for: $name")

        // Wait a bit to demonstrate timers
        workflow().sleep(1.seconds)

        // Generate a unique greeting ID using deterministic random
        val greetingId = workflow().randomUuid().take(8)

        // Call the activity to format the greeting
        // Using string-based API (activity type from @Activity annotation)
        val greeting =
            workflow()
                .startActivity(
                    GreetingActivity::formatGreeting,
                    arg = name,
                    scheduleToCloseTimeout = 10.seconds,
                ).result<String>()

        // Alternative: Use reflection-based API with function reference
        // This automatically extracts the activity type from the @Activity annotation.
        // For extension functions, use: GreetingActivity::class.declaredFunctions.first { it.name == "formatGreeting" }
        // Or for simpler cases with regular functions, just: MyActivity::myFunction

        println("Workflow completed with: $greeting")
        return greeting
    }
}

// =============================================================================
// Activity Definition
// =============================================================================

/**
 * Activity for formatting greeting messages.
 *
 * Activities are where side effects happen - they can make network calls,
 * access databases, or perform any other non-deterministic operations.
 */
class GreetingActivity {
    /**
     * Formats a greeting message for the given name.
     *
     * @param name The name to include in the greeting
     * @return A formatted greeting message
     */
    @Activity("formatGreeting")
    fun formatGreeting(name: String): String {
        // In a real application, this might call an external service
        // or access a database to get personalized greeting templates
        println("Activity: Formatting greeting for $name")

        // Simulate some work
        Thread.sleep(100)

        return "Hello, $name! Welcome to Temporal."
    }

    /**
     * Gets a greeting in a specific language.
     *
     * @param name The name to greet
     * @param language The language code (e.g., "en", "es", "fr")
     * @return A localized greeting
     */
    @Activity
    suspend fun ActivityContext.getLocalizedGreeting(
        name: String,
        language: String,
    ): String {
        val greeting =
            when (language) {
                "es" -> "Hola"
                "fr" -> "Bonjour"
                "de" -> "Hallo"
                "ja" -> "こんにちは"
                else -> "Hello"
            }
        return "$greeting, $name!"
    }
}

// =============================================================================
// Example: Simple Activities (for demonstrating reflection-based API)
// =============================================================================

/**
 * Simple activity class with regular functions (not extension functions).
 * This demonstrates the reflection-based API where you can pass function references.
 */
class SimpleActivities {
    /**
     * A simple calculation activity.
     * The @Activity annotation specifies a custom name.
     */
    @Activity("calculate")
    fun performCalculation(
        x: Int,
        y: Int,
    ): Int = x + y

    /**
     * Another activity that uses the function name as the activity type.
     */
    @Activity
    fun processData(data: String): String = data.uppercase()
}

fun functionActivity() {
}

/**
 * Workflow demonstrating reflection-based activity invocation.
 */
@Workflow("ReflectionDemoWorkflow")
class ReflectionDemoWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.run(): String {
        // Using function reference - activity type is automatically extracted
        // from the @Activity annotation (which says "calculate")
        val sum =
            startActivity(
                SimpleActivities::performCalculation,
                arg1 = 10,
                arg2 = 20,
                scheduleToCloseTimeout = 10.seconds,
            ).result<Int>()

        // Using function reference - activity type defaults to function name "processData"
        val processed =
            startActivity(
                SimpleActivities::processData,
                arg = "hello",
                scheduleToCloseTimeout = 10.seconds,
            ).result<String>()

        return "Sum: $sum, Processed: $processed"
    }
}
