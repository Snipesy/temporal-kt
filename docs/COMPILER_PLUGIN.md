# Compiler Plugin Guide

The Temporal Kotlin compiler plugin provides compile-time validation for deterministic workflow code.

## Quick Start

```kotlin
// build.gradle.kts
plugins {
    id("com.surrealdev.temporal") version "x.y.z"
}

temporal {
    compiler {
        enabled = true  // Default: false - must explicitly enable
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

