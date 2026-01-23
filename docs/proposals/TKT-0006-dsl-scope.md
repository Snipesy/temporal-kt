# TKT-0006: DSL Scope Safety

Use `@TemporalDsl` marker annotation to prevent accidental scope leakage in nested builders.

```kotlin
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class TemporalDsl

@TemporalDsl
class TaskQueueContext { ... }

@TemporalDsl
class WorkflowOptionsContext { ... }
```

This ensures that within a nested lambda, only the immediate receiver's members are accessible
without explicit qualification:

```kotlin
taskQueue("queue-1") {
    workflow<MyWorkflow> {
        // Only WorkflowOptionsContext members accessible here
        // Cannot accidentally call taskQueue() or other outer scope methods
    }
}
```