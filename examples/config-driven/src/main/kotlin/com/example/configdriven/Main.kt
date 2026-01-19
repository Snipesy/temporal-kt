package com.example.configdriven

import com.surrealdev.temporal.application.temporalMain

/**
 * Config-driven Temporal application example.
 *
 * This example demonstrates how to:
 * - Load configuration from application.yaml
 * - Automatically load modules declared in config
 * - Use the TemporalMain entry point
 *
 * The configuration is loaded from src/main/resources/application.yaml
 * which specifies the connection settings and modules to load.
 *
 * Prerequisites:
 * - A Temporal server running on localhost:7233
 */
fun main(args: Array<String>) {
    println("Starting config-driven Temporal application...")
    println("Configuration loaded from: application.yaml")

    // This loads config and modules automatically
    temporalMain(args)
}
