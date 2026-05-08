package com.surrealdev.temporal.annotation

/**
 * Marker applied by the Temporal compiler plugin to all classes/methods it generates
 * (e.g. `__MyWorkflow`, `MyWorkflowStub`, `MyWorkflowHandle`). Used by the SDK's enhanced
 * stack-trace builder to flag synthetic frames as `internal_code = true` so they are hidden
 * from the Temporal UI's stack-trace view.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class TemporalGenerated
