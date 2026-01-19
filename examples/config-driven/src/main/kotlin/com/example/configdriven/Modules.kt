package com.example.configdriven

import com.surrealdev.temporal.application.TemporalApplication
import com.surrealdev.temporal.application.taskQueue

/**
 * Orders module - configures the orders task queue.
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
        // Register order-related workflows and activities
        // workflow(OrderWorkflowImpl())
        // activity(OrderActivityImpl())
    }

    println("Orders module loaded - listening on: orders-queue")
}
