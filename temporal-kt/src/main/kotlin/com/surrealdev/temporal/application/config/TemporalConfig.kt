package com.surrealdev.temporal.application.config

import com.surrealdev.temporal.application.ConnectionConfig

/**
 * Root configuration class for Hoplite-based YAML configuration.
 *
 * Example YAML:
 * ```yaml
 * temporal:
 *   connection:
 *     target: "http://localhost:7233"
 *     namespace: "my-namespace"
 *   modules:
 *     - com.example.modules.OrdersModuleKt.ordersModule
 * ```
 */
data class TemporalConfig(
    val temporal: TemporalRootConfig = TemporalRootConfig(),
)

/**
 * Root temporal configuration containing connection settings and module declarations.
 */
data class TemporalRootConfig(
    /** Connection configuration for the Temporal service. */
    val connection: ConnectionConfig = ConnectionConfig(),
    /** List of fully-qualified module function names to load. */
    val modules: List<String> = emptyList(),
)
