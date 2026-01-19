# Core-Bridge

This is the module that provides FFM bindings to the Temporal Core Rust library. It also generates the kotlin protobuf
definitions used by the SDK-Core Rust library.

Most (if not all) interop is done against
[temporal-sdk-core-c-bridge.h](./rust/sdk-core/crates/sdk-core-c-bridge/include/temporal-sdk-core-c-bridge.h) found in
the Temporal [SDK-Core submodule](./rust/sdk-core/).

The SDK-Core submodule is used [in a small rust project](./rust) which builds its C-Compatible shared library. This rust
project is then build as part of the Gradle build for this module.

## Build (Your Platform)

```bash
gradle build
```

## Build (All Platforms)

```bash
gradle cargoBuildAll
gradle copyAllNativeLibs
gradle build
```

## JExtract 

JExtract is used to generate Java bindings for the native libraries.
https://jdk.java.net/jextract/

Regeneration is currently a manual process. To regenerate the bindings, run the following command from the root of the repository:

```bash
jextract @includes.txt --output ./src/main/java --target-package io.temporal.sdkbridge ./rust/sdk-core/crates/sdk-core-c-bridge/include/temporal-sdk-core-c-bridge.h
```

To regenerate a new includes do

```bash
jextract --dump-includes includes.txt ./rust/sdk-core/crates/sdk-core-c-bridge/include/temporal-sdk-core-c-bridge.h
```

Then remove any non-portable files. (i.e. MAC OS / Darwin specific things) which are not important for the bindings.
Commit the results without modification.

Note that in rust many structs are opaque and will generate errors (thats fine)

```
temporal-sdk-core-c-bridge.h:68:16: warning: Skipping TemporalCoreClientGrpcOverrideRequest (type Declared(TemporalCoreClientGrpcOverrideRequest) is not supported)
```
