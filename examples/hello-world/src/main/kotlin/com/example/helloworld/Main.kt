package com.example.helloworld

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.ActivityMethod
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.embeddedTemporal
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlin.time.Duration.Companion.seconds

/**
 * Hello World example demonstrating basic Temporal application setup.
 *
 * This example shows how to:
 * - Create an embedded Temporal application
 * - Define a workflow with the @Workflow and @WorkflowRun annotations
 * - Define an activity with the @Activity and @ActivityMethod annotations
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
                    workflow(GreetingWorkflow())
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
    suspend fun WorkflowContext.run(name: String): String {
        // Log workflow start (will appear in workflow history)
        println("Workflow started for: $name")

        // Wait a bit to demonstrate timers
        sleep(1.seconds)

        // Generate a unique greeting ID using deterministic random
        val greetingId = randomUuid().take(8)

        // The greeting is composed locally since we don't have activity invocation yet
        // In a full implementation, we would call:
        // val greeting = activity<GreetingActivity>().formatGreeting(name)
        val greeting = "Hello, $name! (ID: $greetingId)"

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
@Activity("GreetingActivity")
class GreetingActivity {
    /**
     * Formats a greeting message for the given name.
     *
     * @param name The name to include in the greeting
     * @return A formatted greeting message
     */
    @ActivityMethod
    suspend fun ActivityContext.formatGreeting(name: String): String {
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
    @ActivityMethod
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
