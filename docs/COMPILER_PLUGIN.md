# Compiler Plugin Guide

The Temporal Kotlin compiler plugin provides:

- **FIR-time determinism validation** — calls inside `@Workflow` classes are checked against
  `determinism-rules.json`; violations surface as `TEMPORAL_NONDETERMINISTIC_CALL` diagnostics.
  Inline activity bodies are exempt.
- **Central reified workflow API** (kotlinx-rpc-style) — `client.startWorkflow<W>("queue", arg)`,
  `client.workflowHandle<W>(id)`, `WorkflowContext.startChildWorkflow<W>(arg)`, and
  `WorkflowContext.externalHandle<W>(id)` return a value typeable as `W` (the user's `@Workflow`
  class). The runtime instance is `W.Handle<R>` / `W.ChildHandle<R>` / `W.ExternalHandle` —
  nested classes the plugin synthesises that extend `W` for virtual dispatch.
- **Auto-open `@Workflow` classes** — the FIR status transformer marks every `@Workflow` class
  and its members `open` so the synthesised handle can extend the class.
- **Typed handle with signal / query / update wrappers** — each `@Signal` / `@Query` / `@Update`
  method projects to a typed wrapper on the synthesised handle. Cast to `W.Handle<R>` to call
  them (the wrappers are suspend; user handlers are typically not — they don't override).
- **Inline activity lifting** — `workflow().inlineActivity("name") { ... }` calls inside
  `@WorkflowRun` methods are lifted to registered activities.
- **Receiver-shape diagnostic** — declaring `@WorkflowRun` / `@Signal` / `@Query` / `@Update`
  with a `WorkflowContext` extension receiver triggers `TEMPORAL_HANDLER_HAS_EXTENSION_RECEIVER`.
  Top-level handlers must be receiverless; use `workflow()` inside the body when needed.

```kotlin
import com.surrealdev.temporal.client.startWorkflow

@Workflow("Greeter")
class Greeter {
    @WorkflowRun
    suspend fun run(arg: String): String = "Hello, $arg"
}

// Returns a value typeable as Greeter (runtime: Greeter.Handle<String>):
val greeter: Greeter = client.startWorkflow<Greeter>("my-queue", "World")
@Suppress("UNCHECKED_CAST")
val handle = greeter as Greeter.Handle<String>
val result: String = handle.result()
```

## Quick Start

```kotlin
// build.gradle.kts
plugins {
    id("com.surrealdev.temporal") version "x.y.z"
}

temporal {
    compiler {
        enabled = true  // Default: true
    }
    native {
        enabled = true  // Default: true - auto-detects platform
    }
}
```

## Enable/Disable

### Enable the Compiler Plugin

```kotlin
temporal {
    compiler {
        enabled = true
    }
}
```

### Disable the Compiler Plugin

```kotlin
temporal {
    compiler {
        enabled = false  // This is the default
    }
}
```

### Disable Native Library (Keep Compiler Only)

```kotlin
temporal {
    compiler {
        enabled = true
    }
    native {
        enabled = false  // Don't include native Rust SDK binaries
    }
}
```

## Why Disabled by Default?

**The Kotlin compiler plugin API is unstable.**

Each Kotlin version release can break binary compatibility with compiler plugins, causing compilation errors when you update Kotlin versions.
Projects like Jetpack Compose avoid this problem by being "part of the club" - they ship directly with the Kotlin compiler.

It's bad for IR, and even worse for FIR.

For third-party plugins like Temporal-Kt, this creates an unfair maintenance burden.

**On version mismatch**: You may see errors like:
```
NoSuchMethodError: org.jetbrains.kotlin.gradle.plugin.KotlinCompilation...
ClassNotFoundException: org.jetbrains.kotlin.ir.declarations...
```

Disable the compiler plugin if you encounter these errors:
```kotlin
temporal {
    compiler {
        enabled = false
    }
}
```

## Development Roadmap

Development will be slow until Kotlin stabilizes the compiler plugin API.
Tracking the API across versions is unsustainable for external projects.
