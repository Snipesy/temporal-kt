package com.example.helloworld

import com.surrealdev.temporal.application.embeddedTemporal
import com.surrealdev.temporal.application.taskQueue

/**
 * Hello World example demonstrating basic Temporal application setup.
 *
 * This example shows how to:
 * - Create an embedded Temporal application
 * - Configure a task queue with workflows and activities
 * - Start the application and wait for termination
 *
 * Prerequisites:
 * - A Temporal server running on localhost:7233
 *   (or use the dev server by modifying the connection target)
 */
fun main() {
    val app =
        embeddedTemporal(
            module = {
                taskQueue("hello-world-queue") {
                    // Register workflows and activities here
                    // workflow(HelloWorldWorkflowImpl())
                    // activity(HelloWorldActivityImpl())
                }
            },
        )

    println("Starting Hello World Temporal application...")
    println("Listening on task queue: hello-world-queue")
    println("Press Ctrl+C to stop")

    app.start(wait = true)
}

// Example workflow interface (uncomment when workflow support is implemented)
// interface HelloWorldWorkflow {
//     fun sayHello(name: String): String
// }

// Example workflow implementation
// class HelloWorldWorkflowImpl : HelloWorldWorkflow {
//     override fun sayHello(name: String): String {
//         return "Hello, $name!"
//     }
// }
