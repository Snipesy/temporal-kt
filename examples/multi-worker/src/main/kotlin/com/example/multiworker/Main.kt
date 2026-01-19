package com.example.multiworker

import com.surrealdev.temporal.application.embeddedTemporal
import com.surrealdev.temporal.application.taskQueue

/**
 * Multi-worker example demonstrating multiple task queues.
 *
 * This example shows how to:
 * - Configure multiple task queues in a single application
 * - Separate concerns by domain (orders, notifications, analytics)
 * - Use modules to organize task queue configuration
 *
 * In production, you might run separate workers for each task queue
 * to scale them independently. This example shows all queues in one
 * application for simplicity.
 *
 * Prerequisites:
 * - A Temporal server running on localhost:7233
 */
fun main() {
    val app =
        embeddedTemporal(
            module = {
                // Orders domain - handles order processing workflows
                taskQueue("orders-queue") {
                    // workflow(CreateOrderWorkflow())
                    // workflow(FulfillOrderWorkflow())
                    // activity(InventoryActivity())
                    // activity(PaymentActivity())
                }

                // Notifications domain - handles sending notifications
                taskQueue("notifications-queue") {
                    // workflow(NotificationWorkflow())
                    // activity(EmailActivity())
                    // activity(SmsActivity())
                    // activity(PushNotificationActivity())
                }

                // Analytics domain - handles data processing
                taskQueue("analytics-queue") {
                    // workflow(DailyReportWorkflow())
                    // workflow(UserMetricsWorkflow())
                    // activity(AggregationActivity())
                    // activity(ReportGenerationActivity())
                }
            },
        )

    println("Starting Multi-Worker Temporal application...")
    println("Listening on task queues:")
    println("  - orders-queue")
    println("  - notifications-queue")
    println("  - analytics-queue")
    println("Press Ctrl+C to stop")

    app.start(wait = true)
}
