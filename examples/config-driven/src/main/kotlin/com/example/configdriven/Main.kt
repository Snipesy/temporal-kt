package com.example.configdriven

import com.surrealdev.temporal.application.temporalMain

/**
 * Config-driven Temporal application example.
 *
 * This example demonstrates how to:
 * - Load configuration from application.yaml
 * - Automatically load modules declared in config
 * - Use the TemporalMain entry point for production deployments
 *
 * The configuration is loaded from src/main/resources/application.yaml
 * which specifies:
 * - Connection settings (target URL, namespace)
 * - Modules to load (containing task queue definitions)
 *
 * This approach is ideal for production deployments where you want to:
 * - Externalize configuration from code
 * - Use environment-specific config files
 * - Deploy the same binary with different configurations
 *
 * Prerequisites:
 * - A Temporal server running on localhost:7233
 *   (or use the dev server: `temporal server start-dev`)
 */
fun main(args: Array<String>) {
    println("Starting config-driven Temporal application...")
    println("Configuration loaded from: application.yaml")
    println()
    println("To start a workflow, use the Temporal CLI:")
    println(
        "  temporal workflow start --task-queue orders-queue " +
            "--type OrderWorkflow --input '{\"orderId\": \"123\", \"items\": [\"item1\"]}'",
    )
    println()

    // This loads config and modules automatically from application.yaml
    temporalMain(args)
}
