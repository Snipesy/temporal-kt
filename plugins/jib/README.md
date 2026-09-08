# Module jib-plugin

Jib extension that filters Temporal core-bridge native classifier JARs per container platform.

When building multi-arch container images with Jib, all native classifier JARs end up on the classpath.
This extension removes the ones that don't match the target platform, keeping images lean.

Linux images retain both GNU and musl libraries for their architecture; the native loader selects
the matching libc at runtime, including on Alpine.

Note: Jib uses legacy service loaders for their SPI (realllllly bad practice) 
otherwise we would probably find a better way of doing this.

It is unclear if/when or even if its planned to change this in Jib.

## How It Works

The Temporal Gradle plugin (`com.surrealdev.temporal`) automatically adds all 6 native classifier JARs
when Jib is detected. This extension then filters them during Jib's container build so each platform
image only contains native libraries for its target architecture.

For example, when building for `linux/arm64`, the extension removes
`core-bridge-*-linux-x86_64-*.jar`, `core-bridge-*-macos-*.jar`, and `core-bridge-*-windows-*.jar`.

## Installation

Due to legacy issues in Jib: This is a plain JAR (not a Gradle plugin). 
It must be on the same buildscript classpath as Jib so that Jib's ServiceLoader can discover it.

### Standard projects

```kotlin
// build.gradle.kts
buildscript {
    dependencies {
        classpath("com.surrealdev.temporal:jib-plugin:VERSION")
    }
}

plugins {
    id("com.google.cloud.tools.jib")
    id("com.surrealdev.temporal")
}

jib {
    pluginExtensions {
        pluginExtension {
            implementation = "com.surrealdev.temporal.gradle.jib.TemporalJibExtension"
        }
    }
}
```

### Convention plugin projects (build-logic)

```kotlin
// root build.gradle.kts
buildscript {
    dependencies {
        classpath("com.surrealdev.temporal:jib-plugin:VERSION")
    }
}
```

```kotlin
// build-logic/convention/src/main/kotlin/my-jib-convention.gradle.kts
plugins {
    id("com.google.cloud.tools.jib")
    id("com.surrealdev.temporal")
}

jib {
    pluginExtensions {
        pluginExtension {
            implementation = "com.surrealdev.temporal.gradle.jib.TemporalJibExtension"
        }
    }
}
```
