package com.example.configdriven

import com.surrealdev.temporal.activity.ActivityContext
import com.surrealdev.temporal.annotation.Activity
import com.surrealdev.temporal.annotation.Workflow
import com.surrealdev.temporal.annotation.WorkflowRun
import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.taskQueue
import com.surrealdev.temporal.workflow.WorkflowContext
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

/**
 * Orders module - defines the task queue and workers for order processing.
 *
 * This module is loaded automatically based on the application.yaml config:
 * ```yaml
 * temporal:
 *   modules:
 *     - com.example.configdriven.ModulesKt.ordersModule
 * ```
 *
 * Modules are extension functions on [TemporalApplication] that configure
 * task queues, install plugins, or perform other setup.
 */
fun TemporalApplication.ordersModule() {
    taskQueue("orders-queue") {
        workflow(OrderWorkflow())
        activity(InventoryActivity())
        activity(PaymentActivity())
    }

    println("Orders module loaded - listening on: orders-queue")
}

// =============================================================================
// Data Classes
// =============================================================================

@Serializable
data class OrderRequest(
    val orderId: String,
    val items: List<String>,
    val customerId: String = "default-customer",
)

@Serializable
data class OrderResult(
    val orderId: String,
    val status: String,
    val totalAmount: Double,
    val message: String,
)

// =============================================================================
// Workflow
// =============================================================================

/**
 * Order processing workflow.
 *
 * This workflow orchestrates the order fulfillment process:
 * 1. Validates the order
 * 2. Reserves inventory (simulated with timer)
 * 3. Returns the order result
 */
@Workflow("OrderWorkflow")
class OrderWorkflow {
    @WorkflowRun
    suspend fun WorkflowContext.processOrder(request: OrderRequest): OrderResult {
        println("Processing order: ${request.orderId}")

        // Step 1: Validate order
        if (request.items.isEmpty()) {
            return OrderResult(
                orderId = request.orderId,
                status = "REJECTED",
                totalAmount = 0.0,
                message = "Order must contain at least one item",
            )
        }

        // Step 2: Wait for inventory check (simulated with timer)
        sleep(1.seconds)

        // Step 3: Calculate total (simplified - $10 per item)
        val totalAmount = request.items.size * 10.0

        // Step 4: Generate confirmation number using deterministic random
        val confirmationNumber = randomUuid().take(8).uppercase()

        println("Order ${request.orderId} completed with confirmation: $confirmationNumber")

        return OrderResult(
            orderId = request.orderId,
            status = "COMPLETED",
            totalAmount = totalAmount,
            message = "Order confirmed. Confirmation number: $confirmationNumber",
        )
    }
}

// =============================================================================
// Activities
// =============================================================================

/**
 * Inventory management activity.
 *
 * In a real application, this would connect to an inventory management system.
 */
class InventoryActivity {
    @Activity
    suspend fun ActivityContext.checkInventory(items: List<String>): Boolean {
        println("Checking inventory for ${items.size} items")
        // Simulate inventory check
        Thread.sleep(50)
        return true
    }

    @Activity
    suspend fun ActivityContext.reserveItems(
        orderId: String,
        items: List<String>,
    ): String {
        println("Reserving items for order: $orderId")
        Thread.sleep(100)
        return "RESERVED-${orderId.take(8)}"
    }
}

/**
 * Payment processing activity.
 *
 * In a real application, this would integrate with a payment gateway.
 */
class PaymentActivity {
    @Activity
    suspend fun ActivityContext.processPayment(
        orderId: String,
        amount: Double,
    ): Boolean {
        println("Processing payment of $$amount for order: $orderId")
        // Simulate payment processing
        Thread.sleep(200)
        return true
    }

    @Activity
    suspend fun ActivityContext.refundPayment(
        orderId: String,
        amount: Double,
    ): Boolean {
        println("Refunding $$amount for order: $orderId")
        Thread.sleep(100)
        return true
    }
}
