package com.example.multiworker

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.ActivityMethod
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.embeddedTemporal
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Multi-worker example demonstrating multiple task queues with domain separation.
 *
 * This example shows how to:
 * - Configure multiple task queues in a single application
 * - Separate concerns by domain (orders, notifications, analytics)
 * - Define domain-specific workflows and activities
 *
 * In production, you might run separate workers for each task queue
 * to scale them independently. This example shows all queues in one
 * application for simplicity.
 *
 * Prerequisites:
 * - A Temporal server running on localhost:7233
 *   (or use the dev server: `temporal server start-dev`)
 */
fun main() {
    val app =
        embeddedTemporal(
            module = {
                // Orders domain - handles order processing workflows
                taskQueue("orders-queue") {
                    workflow(CreateOrderWorkflow())
                    activity(InventoryActivity())
                    activity(PaymentActivity())
                }

                // Notifications domain - handles sending notifications
                taskQueue("notifications-queue") {
                    workflow(NotificationWorkflow())
                    activity(EmailActivity())
                    activity(SmsActivity())
                }

                // Analytics domain - handles data processing
                taskQueue("analytics-queue") {
                    workflow(DailyReportWorkflow())
                    activity(AggregationActivity())
                }
            },
        )

    println("Starting Multi-Worker Temporal application...")
    println("Listening on task queues:")
    println("  - orders-queue (order processing)")
    println("  - notifications-queue (email, SMS)")
    println("  - analytics-queue (reports)")
    println()
    println("To start workflows, use the Temporal CLI:")
    println(
        "  temporal workflow start --task-queue orders-queue " +
            "--type CreateOrderWorkflow --input '{\"orderId\": \"123\", \"items\": [\"widget\"]}'",
    )
    println(
        "  temporal workflow start --task-queue notifications-queue " +
            "--type NotificationWorkflow --input '{\"userId\": \"user1\", \"message\": \"Hello!\"}'",
    )
    println(
        "  temporal workflow start --task-queue analytics-queue " +
            "--type DailyReportWorkflow --input '{\"date\": \"2024-01-15\"}'",
    )
    println()
    println("Press Ctrl+C to stop")

    app.start(wait = true)
}

// =============================================================================
// ORDERS DOMAIN
// =============================================================================

@Serializable
data class Order(
    val orderId: String,
    val items: List<String>,
    val customerId: String = "guest",
)

@Serializable
data class OrderConfirmation(
    val orderId: String,
    val confirmationNumber: String,
    val totalAmount: Double,
    val status: String,
)

/**
 * Creates and processes a new order.
 */
@Workflow("CreateOrderWorkflow")
class CreateOrderWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.execute(order: Order): OrderConfirmation {
        println("Creating order: ${order.orderId}")

        // Validate and process
        if (order.items.isEmpty()) {
            return OrderConfirmation(
                orderId = order.orderId,
                confirmationNumber = "",
                totalAmount = 0.0,
                status = "REJECTED: No items",
            )
        }

        // Simulate inventory check
        sleep(500.milliseconds)

        val total = order.items.size * 29.99
        val confirmation = randomUuid().take(8).uppercase()

        return OrderConfirmation(
            orderId = order.orderId,
            confirmationNumber = confirmation,
            totalAmount = total,
            status = "CONFIRMED",
        )
    }
}

@Activity("InventoryActivity")
class InventoryActivity {
    @ActivityMethod
    suspend fun ActivityContext.checkStock(items: List<String>): Map<String, Int> {
        println("Checking stock for: $items")
        return items.associateWith { 100 } // All items in stock
    }

    @ActivityMethod
    suspend fun ActivityContext.reserveStock(
        orderId: String,
        items: List<String>,
    ): Boolean {
        println("Reserving stock for order $orderId")
        Thread.sleep(50)
        return true
    }
}

@Activity("PaymentActivity")
class PaymentActivity {
    @ActivityMethod
    suspend fun ActivityContext.chargeCard(
        orderId: String,
        amount: Double,
    ): String {
        println("Charging $$amount for order $orderId")
        Thread.sleep(100)
        return "TXN-${System.currentTimeMillis()}"
    }
}

// =============================================================================
// NOTIFICATIONS DOMAIN
// =============================================================================

@Serializable
data class NotificationRequest(
    val userId: String,
    val message: String,
    val channels: List<String> = listOf("email"),
)

@Serializable
data class NotificationResult(
    val userId: String,
    val sentVia: List<String>,
    val success: Boolean,
)

/**
 * Sends notifications through multiple channels.
 */
@Workflow("NotificationWorkflow")
class NotificationWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.send(request: NotificationRequest): NotificationResult {
        println("Sending notification to: ${request.userId}")

        // In a real implementation, we would call activities for each channel
        // For now, simulate with a timer
        sleep(1.seconds)

        return NotificationResult(
            userId = request.userId,
            sentVia = request.channels,
            success = true,
        )
    }
}

@Activity("EmailActivity")
class EmailActivity {
    @ActivityMethod
    suspend fun ActivityContext.sendEmail(
        to: String,
        subject: String,
        body: String,
    ): Boolean {
        println("Sending email to $to: $subject")
        Thread.sleep(100)
        return true
    }
}

@Activity("SmsActivity")
class SmsActivity {
    @ActivityMethod
    suspend fun ActivityContext.sendSms(
        phoneNumber: String,
        message: String,
    ): Boolean {
        println("Sending SMS to $phoneNumber")
        Thread.sleep(50)
        return true
    }
}

// =============================================================================
// ANALYTICS DOMAIN
// =============================================================================

@Serializable
data class ReportRequest(
    val date: String,
    val metrics: List<String> = listOf("orders", "revenue"),
)

@Serializable
data class ReportResult(
    val date: String,
    val generatedAt: String,
    val metrics: Map<String, Double>,
)

/**
 * Generates daily analytics reports.
 */
@Workflow("DailyReportWorkflow")
class DailyReportWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.generate(request: ReportRequest): ReportResult {
        println("Generating report for: ${request.date}")

        // Simulate aggregation time
        sleep(2.seconds)

        // Generate report ID
        val reportId = randomUuid().take(8)

        return ReportResult(
            date = request.date,
            generatedAt = now().toString(),
            metrics =
                mapOf(
                    "orders" to 150.0,
                    "revenue" to 4499.50,
                    "avgOrderValue" to 29.99,
                ),
        )
    }
}

@Activity("AggregationActivity")
class AggregationActivity {
    @ActivityMethod
    suspend fun ActivityContext.aggregateMetrics(
        date: String,
        metrics: List<String>,
    ): Map<String, Double> {
        println("Aggregating metrics for $date: $metrics")
        Thread.sleep(200)
        return metrics.associateWith { Math.random() * 1000 }
    }
}
