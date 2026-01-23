package com.surrealdev.temporal.dependencies

/**
 * Defines the scope and safety guarantees for a dependency.
 */
enum class DependencyScope {
    /**
     * Workflow-safe dependencies that are deterministic and can be used in workflows.
     *
     * Examples: Configuration objects, pure functions, deterministic calculators.
     *
     * MUST NOT have side effects or non-deterministic behavior.
     */
    WORKFLOW_SAFE,

    /**
     * Activity-only dependencies that can have side effects.
     *
     * Examples: HTTP clients, databases, file systems, random number generators.
     *
     * Attempting to use these in workflows will throw an exception.
     */
    ACTIVITY_ONLY,
}
